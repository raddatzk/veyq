package me.raddatz.veyq.mobile.session.rotation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import me.raddatz.veyq.mobile.account.KitStep
import me.raddatz.veyq.mobile.api.models.RotationBlockedReason
import me.raddatz.veyq.mobile.api.models.RotationScopeState
import me.raddatz.veyq.mobile.crypto.Primitives
import me.raddatz.veyq.mobile.session.ColdCredential
import me.raddatz.veyq.mobile.session.PasswordChange
import me.raddatz.veyq.mobile.session.VeyqAccount
import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.MsgKey

/**
 * Replace the keys (uc.account.rotate-keys, ADR 0060, ADR 0111, ADR 0148 decision 7): the web's `KeyRotationPanel`
 * order as one model over the core's port of `rotateKeys.ts` ([AccountRotation]).
 *
 * 1. [open]: a holder of the moderation key is sent to the web ([State.onTheWeb], decision 9, #965) — the rotation would
 *    have to carry that key, which the apps cannot; anyone else sees the gate's facts (the store wiped and refilled
 *    among them, [Msg.ROTATION_STORE_WIPED]).
 * 2. [prove]: the password and the Secret Key (and, preselected, a new password) against the floor
 *    ([ColdCredential]). An account secured elsewhere since this one opened ([ColdCredential.Refusal.STALE]) is
 *    resumed — nothing is minted — and finished.
 * 3. The new Secret Key is minted here and handed to a [KitStep] ([kit]) — never shown: the platform's save dialog, the
 *    core's write into the file chosen, "I saved it".
 * 4. [run], the danger step, publishes — **only once the kit step is [KitStep.State.ready]**: a key nobody saved would
 *    lock the person out once the old kit stops working.
 * 5. Each scope's status ([State.scopes], the server's word after the finish), then the *Links to renew* ([State.links],
 *    the web's `listLinksToRenew`, decision 13): the person's own links the server marks `reissueRequired` — a link made
 *    before still opens what it opened, its key being the link's own. A link whose node the open tenant holds names it
 *    ([RenewLink.node]): *Renew* opens that node's share dialog, the platform's own; [refreshLinks] reads them anew after.
 *
 * The password, the new password and both Secret Keys live in this model only between the steps and are zeroed at
 * [close] (a String cannot be zeroed: it is dropped). After [State.done] the host opens its tenant again: the account
 * holds the new keys, and every key a tenant session opened before is the old identity's.
 */
class KeyRotation(
    private val engine: Engine,
    private val primitives: Primitives,
    private val email: String,
    private val server: String,
    /** A new password's breached-password check (ADR 0151 decision 5): the instance's, none in a test that plays none. */
    val breach: me.raddatz.veyq.mobile.account.BreachCheck = me.raddatz.veyq.mobile.account.BreachCheck.none(),
) {
    /** What the model drives: [AccountRotation] over the signed-in account, a played one in the tests. */
    interface Engine {
        suspend fun holdsModerationKey(): Boolean

        /** The cold proof's outcome: null when it passed, else the refusal (STALE included). */
        suspend fun prove(password: String, secretKey: ByteArray): ColdCredential.Refusal?

        /** An account secured elsewhere: the account takes its current keys from the floor, then the finish runs. */
        suspend fun resume(password: String, secretKey: ByteArray): List<RotationScopeState>

        /** The publish and what follows it; throws [Refused] or [OutcomeUnknown]. */
        suspend fun publish(password: String, secretKey: ByteArray, newSecretKey: ByteArray, newPassword: String?): Published

        /** What this device can call each scope ([ScopeNames]), read once the statuses are in; none known by default. */
        suspend fun names(): ScopeNames = ScopeNames()

        /** The person's own links that need renewing, every tenant's ([RenewLink]), each space named by [names]. */
        suspend fun linksToRenew(names: ScopeNames): List<RenewLink> = emptyList()
    }

    /**
     * One link to renew (the web's `LinkToRenew`): the share, its space as the status lines name it ([scopeName] or
     * [scopeWord]), whether it is an upload link ([upload] — a drop box offers no renew: a new link replaces it, *Open*),
     * and [node] where the tenant open on this device holds it: its share dialog opens from there, named by [node]'s name.
     * Without [node] the link is another tenant's ([tenant], said by [Msg.ROTATION_LINK_ELSEWHERE]) or its name is not
     * available here ([Msg.ROTATION_LINK_NAME_UNAVAILABLE]).
     */
    @Serializable
    data class RenewLink(
        val shareId: String,
        val scopeId: String,
        val node: me.raddatz.veyq.mobile.navigation.NodeAt? = null,
        val upload: Boolean = false,
        /** The tenant the link's space is in, where it is not the one open here. */
        val tenant: String? = null,
        val scopeName: String? = null,
        @Serializable(with = MsgKey::class) val scopeWord: Msg? = Msg.ROTATION_SCOPE_NAME_UNAVAILABLE,
    )

    /**
     * What a space is called on this device, never its raw id (the web's `KeyRotationPanel.scopeLabel`, inbox #979): a
     * tenant root or a space by the name this device holds ([named]), a personal space as such ([personal], from the
     * plan's kind); any other is "a space this device hasn't opened".
     */
    class ScopeNames(val personal: Set<String> = emptySet(), val named: Map<String, String> = emptyMap())

    /** What a publish came to: committed ([resumed] false) or an earlier one resumed, each scope's state after the finish. */
    class Published(val resumed: Boolean, val states: List<RotationScopeState>, val unreadable: List<String>)

    /** A refusal before or of the publish: nothing was written. [msg] is what the gate says. */
    class Refused(val msg: Msg) : Exception(msg.key)

    /** The publish's outcome could not be settled: the account was closed, the person unlocks again. */
    class OutcomeUnknown(cause: Throwable?) : Exception("rotation outcome unknown", cause)

    enum class Step { LOADING, ON_THE_WEB, GATE, KIT, WORKING, DONE }

    /**
     * One scope's line after the finish: its id, the server's state and, when blocked, why (the words a host draws), and
     * what it is called: its [name] where this device holds one, else [nameWord] — [Msg.ROTATION_PERSONAL_SPACE] or
     * [Msg.ROTATION_SCOPE_NAME_UNAVAILABLE] ([ScopeNames]).
     */
    @Serializable
    data class ScopeStatus(
        val scopeId: String,
        @Serializable(with = MsgKey::class) val status: Msg,
        @Serializable(with = MsgKey::class) val blocked: Msg? = null,
        val name: String? = null,
        @Serializable(with = MsgKey::class) val nameWord: Msg? = Msg.ROTATION_SCOPE_NAME_UNAVAILABLE,
    )

    @Serializable
    data class State(
        val step: Step = Step.LOADING,
        /** The server's address, for [Msg.ACCOUNT_ON_THE_WEB]. */
        val server: String = "",
        /** "Also choose a new password", preselected as on the web. */
        val newPassword: Boolean = true,
        /** The publish committed (or an earlier one was resumed): [Msg.ROTATION_DONE_OLD_KIT]. */
        val done: Boolean = false,
        /** An earlier publish had committed: this run minted nothing, the kit saved was not installed. */
        val resumed: Boolean = false,
        val scopes: List<ScopeStatus> = emptyList(),
        /** Scopes this device could not open and declared: each keeps its grant and gets no new lock. */
        val unreadable: List<String> = emptyList(),
        @Serializable(with = MsgKey::class) val refusal: Msg? = null,
        /** *Links to renew*, read once the rotation is done ([RenewLink]); empty where there are none or the read failed. */
        val links: List<RenewLink> = emptyList(),
    )

    private val _state = MutableStateFlow(State(server = server))
    val state: StateFlow<State> = _state.asStateFlow()
    val current: State get() = _state.value
    private val acting = Mutex()

    class Watch internal constructor(private val job: kotlinx.coroutines.Job) {
        fun stop() = job.cancel()
    }

    fun watch(listener: (State) -> Unit): Watch = Watch(CoroutineScope(Dispatchers.Default).launch { state.collect(listener) })

    /** The gate's Secret Key control (ADR 0151 decision 1): a pasted kit token, a kit file or a scan, held for this flow. */
    val intake = me.raddatz.veyq.mobile.account.KeyIntake(primitives, me.raddatz.veyq.mobile.account.KeyIntake.Account(email, server))

    private var password: String? = null
    private var nextPassword: String? = null
    private var secretKey: ByteArray? = null
    private var kitStep: KitStep? = null

    /** The kit step, once [prove] passed: the host's save dialog writes through it. */
    val kit: KitStep? get() = kitStep

    suspend fun open() = acting.withLock {
        if (current.newPassword) runCatching { breach.load() }.onFailure { if (it is CancellationException) throw it }
        val moderator = runCatching { engine.holdsModerationKey() }.getOrElse { if (it is CancellationException) throw it else false }
        _state.value = current.copy(step = if (moderator) Step.ON_THE_WEB else Step.GATE)
    }

    fun setNewPassword(on: Boolean) {
        _state.value = current.copy(newPassword = on, refusal = null)
    }

    /**
     * The gate: [password] and [secretKey] (32 bytes; this call takes it — the caller's array is zeroed) proven against
     * the floor; [newPassword] with [confirm] when [State.newPassword]. On success the kit step opens with a key minted
     * here.
     */
    suspend fun prove(password: String, secretKey: String, newPassword: String = "", confirm: String = "") =
        prove(password, intake.key(secretKey), newPassword, confirm)

    /** [prove] with the key read: [secretKey] (32 bytes) zeroed by this call. */
    internal suspend fun prove(password: String, secretKey: ByteArray, newPassword: String = "", confirm: String = "") = acting.withLock {
        val held = secretKey.copyOf()
        secretKey.fill(0)
        try {
            if (current.step != Step.GATE) return@withLock
            if (current.newPassword) {
                if (newPassword != confirm) return@withLock refuse(Msg.PASSWORD_CHANGE_MISMATCH)
                if (newPassword.length < PasswordChange.MIN_PASSWORD_LENGTH) return@withLock refuse(Msg.PASSWORD_TOO_SHORT)
                // the web's submit guard: a hit waits for *Use it anyway* (the check's own state says so)
                if (!breach.proceed(newPassword)) {
                    _state.value = current.copy(refusal = null)
                    return@withLock
                }
            }
            _state.value = current.copy(refusal = null, step = Step.WORKING)
            val refusal = try {
                engine.prove(password, held)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = current.copy(step = Step.GATE, refusal = Msg.ROTATION_ERROR_FAILED)
                return@withLock
            }
            when (refusal) {
                null -> {
                    this.password = password
                    this.nextPassword = if (current.newPassword) newPassword else null
                    this.secretKey = held.copyOf()
                    val minted = primitives.randomBytes(32)
                    kitStep = KitStep(primitives, email, server, minted)
                    _state.value = current.copy(step = Step.KIT)
                }
                ColdCredential.Refusal.STALE -> {
                    _state.value = try {
                        val states = engine.resume(password, held)
                        current.copy(step = Step.DONE, done = true, resumed = true, scopes = statuses(states, names()))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        current.copy(step = Step.GATE, refusal = Msg.ROTATION_ERROR_FAILED)
                    }
                    if (current.done) _state.value = current.copy(links = links(names()))
                }
                ColdCredential.Refusal.NO_FLOOR -> refuse(Msg.ROTATION_ERROR_NO_FLOOR)
                else -> refuse(Msg.ROTATION_ERROR_CREDENTIAL)
            }
        } finally {
            held.fill(0)
            // the gate passed (the kit step opened, or an earlier rotation was resumed): the held kit's key is spent
            if (current.step == Step.KIT || current.done) intake.spent()
        }
    }

    private fun refuse(msg: Msg) {
        _state.value = current.copy(step = Step.GATE, refusal = msg)
    }

    /**
     * The danger step: publishes the new keys — refused (nothing sent) unless the kit step is ready (a file written and
     * "I saved it" checked). Its outcome: [State.done] with each scope's status; a declared refusal (nothing changed);
     * or an unknown outcome ([Msg.ROTATION_ERROR_UNKNOWN_OUTCOME]: the account is closed, the person unlocks with the
     * new kit, which opens only if the publish committed).
     */
    suspend fun run() = acting.withLock {
        val step = kitStep
        val pw = password
        val sk = secretKey
        if (current.step != Step.KIT || step == null || pw == null || sk == null) return@withLock
        if (!step.current.ready) return@withLock
        _state.value = current.copy(step = Step.WORKING, refusal = null)
        val newKey = step.keyCopy()
        try {
            val result = engine.publish(pw, sk, newKey, nextPassword)
            val names = names()
            _state.value = current.copy(
                step = Step.DONE, done = true, resumed = result.resumed, scopes = statuses(result.states, names), unreadable = result.unreadable,
            )
            forget()
            _state.value = current.copy(links = links(names))
        } catch (e: Refused) {
            _state.value = current.copy(step = Step.KIT, refusal = e.msg)
        } catch (e: OutcomeUnknown) {
            _state.value = current.copy(step = Step.DONE, refusal = Msg.ROTATION_ERROR_UNKNOWN_OUTCOME)
            forget()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = current.copy(step = Step.KIT, refusal = Msg.ROTATION_ERROR_FAILED)
        } finally {
            newKey.fill(0)
        }
    }

    /** *Links to renew* read anew — after a link's share dialog closed (a renewed link drops out). Nothing before [State.done]. */
    suspend fun refreshLinks() = acting.withLock {
        if (!current.done) return@withLock
        _state.value = current.copy(links = links(names()))
    }

    /** The links, best effort: a read that fails lists none (the web's `.catch(() => [])`). */
    private suspend fun links(names: ScopeNames): List<RenewLink> = try {
        engine.linksToRenew(names)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        emptyList()
    }

    /** The names, best effort: a read that fails names nothing (every line then "a space this device hasn't opened"). */
    private suspend fun names(): ScopeNames = try {
        engine.names()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ScopeNames()
    }

    private fun forget() {
        password = null
        nextPassword = null
        secretKey?.fill(0)
        secretKey = null
        kitStep?.close()
        intake.clear()
    }

    /** A new password's field was left with [newPassword] in it: the breached-password check runs ([BreachCheck.check]). */
    suspend fun check(newPassword: String) = breach.check(newPassword)

    /** The new password was edited: the check's verdict, and any acceptance of it, is gone. Sends nothing. */
    fun edited() = breach.edited()

    /** *Use it anyway* for a breached new password, or taken back. */
    fun useAnyway(on: Boolean) = breach.useAnyway(on)

    /** Drops every secret the model holds. */
    fun close() {
        forget()
        breach.close()
    }

    companion object {
        /** [link]'s space named as a status line's ([statuses]' order). */
        internal fun named(link: RenewLink, names: ScopeNames): RenewLink {
            val name = names.named[link.scopeId]?.takeIf { it.isNotBlank() }
            return when {
                name != null -> link.copy(scopeName = name, scopeWord = null)
                link.scopeId in names.personal -> link.copy(scopeWord = Msg.ROTATION_PERSONAL_SPACE)
                else -> link
            }
        }

        internal fun statuses(states: List<RotationScopeState>, names: ScopeNames = ScopeNames()): List<ScopeStatus> = states.map { s ->
            val status = when (s.state) {
                RotationScopeState.State.secured -> ScopeStatus(s.scopeId, Msg.ROTATION_STATUS_SECURED)
                RotationScopeState.State.rekeying -> ScopeStatus(s.scopeId, Msg.ROTATION_STATUS_REKEYING)
                RotationScopeState.State.blocked -> ScopeStatus(
                    s.scopeId, Msg.ROTATION_STATUS_BLOCKED,
                    when (s.reason) {
                        RotationBlockedReason.tenant_suspended -> Msg.ROTATION_BLOCKED_TENANT_SUSPENDED
                        RotationBlockedReason.scope_deleting -> Msg.ROTATION_BLOCKED_SCOPE_DELETING
                        else -> Msg.ROTATION_BLOCKED_UNREADABLE
                    },
                )
                else -> ScopeStatus(s.scopeId, Msg.ROTATION_STATUS_WAITING)
            }
            // the web's order: a tenant root's or a space's own name, the personal space as such, else not opened here
            val name = names.named[s.scopeId]?.takeIf { it.isNotBlank() }
            when {
                name != null -> status.copy(name = name, nameWord = null)
                s.scopeId in names.personal -> status.copy(nameWord = Msg.ROTATION_PERSONAL_SPACE)
                else -> status
            }
        }
    }
}

/**
 * Replacing this account's keys (ADR 0148 decision 7): the model over the core's port. [open] is the tenant the host has
 * open, if any: its spaces' names name the status lines (inbox #979); the tenants' own names come from the account.
 */
fun VeyqAccount.keyRotation(open: me.raddatz.veyq.mobile.session.TenantSession?): KeyRotation =
    KeyRotation(
        AccountRotation(this, { open?.spaces().orEmpty() }) { scopeId, nodeId ->
            open?.node(scopeId, nodeId)?.let { me.raddatz.veyq.mobile.navigation.NodeAt(it.scopeId, it.id, it.kind, it.name) }
        },
        primitives, email, server, me.raddatz.veyq.mobile.account.BreachCheck.of(this),
    )
