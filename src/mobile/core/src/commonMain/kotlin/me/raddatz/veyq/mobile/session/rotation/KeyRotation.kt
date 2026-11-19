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
 * 5. Each scope's status ([State.scopes], the server's word after the finish).
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
    }

    /** What a publish came to: committed ([resumed] false) or an earlier one resumed, each scope's state after the finish. */
    class Published(val resumed: Boolean, val states: List<RotationScopeState>, val unreadable: List<String>)

    /** A refusal before or of the publish: nothing was written. [msg] is what the gate says. */
    class Refused(val msg: Msg) : Exception(msg.key)

    /** The publish's outcome could not be settled: the account was closed, the person unlocks again. */
    class OutcomeUnknown(cause: Throwable?) : Exception("rotation outcome unknown", cause)

    enum class Step { LOADING, ON_THE_WEB, GATE, KIT, WORKING, DONE }

    /** One scope's line after the finish: its id, the server's state and, when blocked, why (the words a host draws). */
    @Serializable
    data class ScopeStatus(
        val scopeId: String,
        @Serializable(with = MsgKey::class) val status: Msg,
        @Serializable(with = MsgKey::class) val blocked: Msg? = null,
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
    )

    private val _state = MutableStateFlow(State(server = server))
    val state: StateFlow<State> = _state.asStateFlow()
    val current: State get() = _state.value
    private val acting = Mutex()

    class Watch internal constructor(private val job: kotlinx.coroutines.Job) {
        fun stop() = job.cancel()
    }

    fun watch(listener: (State) -> Unit): Watch = Watch(CoroutineScope(Dispatchers.Default).launch { state.collect(listener) })

    private var password: String? = null
    private var nextPassword: String? = null
    private var secretKey: ByteArray? = null
    private var kitStep: KitStep? = null

    /** The kit step, once [prove] passed: the host's save dialog writes through it. */
    val kit: KitStep? get() = kitStep

    suspend fun open() = acting.withLock {
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
    suspend fun prove(password: String, secretKey: ByteArray, newPassword: String = "", confirm: String = "") = acting.withLock {
        val held = secretKey.copyOf()
        secretKey.fill(0)
        try {
            if (current.step != Step.GATE) return@withLock
            if (current.newPassword) {
                if (newPassword != confirm) return@withLock refuse(Msg.PASSWORD_CHANGE_MISMATCH)
                if (newPassword.length < PasswordChange.MIN_PASSWORD_LENGTH) return@withLock refuse(Msg.PASSWORD_TOO_SHORT)
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
                        current.copy(step = Step.DONE, done = true, resumed = true, scopes = statuses(states))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        current.copy(step = Step.GATE, refusal = Msg.ROTATION_ERROR_FAILED)
                    }
                }
                ColdCredential.Refusal.NO_FLOOR -> refuse(Msg.ROTATION_ERROR_NO_FLOOR)
                else -> refuse(Msg.ROTATION_ERROR_CREDENTIAL)
            }
        } finally {
            held.fill(0)
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
            _state.value = current.copy(
                step = Step.DONE, done = true, resumed = result.resumed, scopes = statuses(result.states), unreadable = result.unreadable,
            )
            forget()
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

    private fun forget() {
        password = null
        nextPassword = null
        secretKey?.fill(0)
        secretKey = null
        kitStep?.close()
    }

    /** Drops every secret the model holds. */
    fun close() = forget()

    companion object {
        internal fun statuses(states: List<RotationScopeState>): List<ScopeStatus> = states.map { s ->
            when (s.state) {
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
        }
    }
}

/** Replacing this account's keys (ADR 0148 decision 7): the model over the core's port. */
fun VeyqAccount.keyRotation(): KeyRotation = KeyRotation(AccountRotation(this), primitives, email, server)
