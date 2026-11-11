package me.raddatz.veyq.mobile.session

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.raddatz.veyq.mobile.crypto.Primitives
import me.raddatz.veyq.mobile.navigation.Connection
import me.raddatz.veyq.mobile.navigation.Connections
import me.raddatz.veyq.mobile.navigation.Hidden
import me.raddatz.veyq.mobile.navigation.RecentUses
import me.raddatz.veyq.mobile.navigation.SpaceRef
import me.raddatz.veyq.mobile.navigation.TenantRef
import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.MsgKey

/**
 * The app's way in, for every platform (ADR 0123 decision 8, ADR 0127): where it opens — every account the person stays
 * signed in to resumes after one authentication; with none, the most recent password-trusted account asks for its
 * password; with no trusted account, the sign-in —, the full sign-in with this device's password trust after it, the
 * offer to stay signed in that follows a sign-in ([Offer]), the unlock, the switcher, a server added, a sign-out and
 * what becomes current after it, and "Manage servers"' list with each server's trust and its change.
 *
 * A UI observes [gate] (or [watch]) and draws the sign-in, the unlock or — once [Gate.Signed] — its shell over the
 * tenant an action returned [Opened]; it holds no account logic of its own. [A] is the account, [T] an opened tenant:
 * [accounts] builds the model over the real ones, a test over its own.
 */
class SessionModel<A : Connection, T : Any>(
    private val door: Door<A, T>,
    private val hidden: Hidden,
    private val recent: RecentUses,
    /** The share extension (ADR 0129): it resumes and unlocks, but never signs in — the app does. */
    private val signInElsewhere: Boolean = false,
) {
    /** What the model does with an account, the platform's suite and HTTP client behind it. */
    interface Door<A : Connection, T : Any> {
        /** The accounts this device trusts, most recent first. */
        fun trusted(): List<TrustedDevice>
        suspend fun signIn(server: String, email: String, password: String, secretKey: String): A
        suspend fun unlockOnDevice(record: TrustedDevice, password: String): A
        suspend fun resume(record: TrustedDevice): A

        /** This device trusted with [account] by the password after a sign-in (DEVICE); never fails the sign-in. */
        suspend fun trust(account: A, password: String)

        /**
         * Staying signed in with [account] at [level] (a DEVICE_KEY wrap), replacing how this device trusted it. Never
         * signs out: anything short of [TrustChange.DONE] leaves the trust it had.
         */
        suspend fun stay(account: A, level: StaySignedIn): TrustChange

        /** Back to the password trust for [account] with [password], which is checked first ([TrustChange.WRONG_PASSWORD]). */
        suspend fun trustWithPassword(account: A, password: String): TrustChange
        fun defaultTenant(account: A): TenantRef?

        /** How this device trusts [account] now, if it does. */
        fun trustedBy(account: A): TrustedDevice?
        suspend fun open(account: A, tenant: TenantRef): T

        /**
         * [session] became the tenant shown (a sign-in, an unlock, a resume, a switch) — not one read only for its spaces
         * ([loadSpaces]): what runs in the background while a tenant is open starts here (the re-key's resume).
         */
        fun opened(session: T) {}
        suspend fun spaces(session: T): List<SpaceRef>
        suspend fun signOut(account: A)
        suspend fun forgetDevice(account: A)

        /** The levels staying signed in can take on this device now ([DeviceKeys.staySignedIn]). */
        fun staySignedIn(): Set<StaySignedIn>

        /** Whether the person answered [account]'s offer with "Not now" on this device ([ThisDevice.declined]). */
        fun declined(account: A): Boolean
        fun setDeclined(account: A, declined: Boolean)
    }

    /**
     * The offer to stay signed in after a sign-in (ADR 0123 decision 8): the levels this device's store offers ([StaySignedIn], in that
     * order) for the account of connection [connection]. Answered by [stay] or [notNow]; the shell is open beneath it.
     */
    @Serializable
    data class Offer(val connection: String, val levels: List<StaySignedIn>)

    /** Where the way in stands; serializable, since the native bridge sends it as it is (ADR 0123's Consequences, the bridge). */
    @Serializable
    sealed class Gate {
        /** The remembered accounts resuming at start. */
        @Serializable @SerialName("Starting")
        object Starting : Gate()

        /** The share extension with no account it can open: the app signs in (ADR 0129). */
        @Serializable @SerialName("NeedsApp")
        object NeedsApp : Gate()

        /** The full sign-in, with the Secret Key; [server] and [email] filled in where a trusted device's record knew them. */
        @Serializable @SerialName("SignIn")
        data class SignIn(
            val working: Boolean = false,
            @Serializable(with = MsgKey::class) val error: Msg? = null,
            val adding: Boolean = false,
            val server: String? = null,
            val email: String? = null,
        ) : Gate()

        /** An account this device trusts: the password alone (DEVICE), or the person's authentication (DEVICE_KEY). */
        @Serializable @SerialName("Unlock")
        data class Unlock(val record: TrustedDevice, val working: Boolean = false, @Serializable(with = MsgKey::class) val error: Msg? = null) : Gate()

        /**
         * Signed in, the shell over tenant [tenantId] of connection [connectionKey] — while [switching], still the one
         * shown, the switch to another under way. [offer] is the offer to stay signed in a sign-in left open, shown over
         * the shell until it is answered.
         */
        @Serializable @SerialName("Signed")
        data class Signed(val connectionKey: String, val tenantId: String, val switching: Boolean = false, val offer: Offer? = null) : Gate()
    }

    /** A tenant opened: the shell goes over [session]. */
    class Opened<A : Any, T : Any>(val account: A, val tenant: TenantRef, val session: T)

    private val _gate = MutableStateFlow<Gate>(Gate.Starting)
    val gate: StateFlow<Gate> = _gate.asStateFlow()
    val current: Gate get() = _gate.value

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    class Watch internal constructor(private val job: kotlinx.coroutines.Job) {
        fun stop() = job.cancel()
    }

    fun watch(listener: (Gate) -> Unit): Watch = Watch(scope.launch { gate.collect { listener(it) } })

    private val connections = Connections<A>()

    /**
     * The levels staying signed in can take on this device now (ADR 0123 decision 8): what an offer and "Manage servers" offer, none where
     * nothing can be kept. Asked of the device's store each time.
     */
    val staySignedIn: Set<StaySignedIn> get() = door.staySignedIn()

    /** The servers signed in to. */
    val accounts: List<A> get() = connections.all

    /** The account and tenant the shell is over, if any. */
    val signedTo: Opened<A, T>? get() = opened

    private var opened: Opened<A, T>? = null
    private var started = false
    private var pendingUnlock: String? = null

    /** The offer a sign-in left open ([Gate.Signed.offer]), null once answered. */
    private var offer: Offer? = null

    /** The start, once per process: what opened, or null for the sign-in or the unlock [gate] now shows. */
    suspend fun start(): Opened<A, T>? {
        if (started) return null
        started = true
        val records = door.trusted()
        if (records.isEmpty()) {
            _gate.value = if (signInElsewhere) Gate.NeedsApp else Gate.SignIn()
            return null
        }
        var stopped: TrustedDevice? = null
        for (r in records.filter { it.method == TrustedDevice.Method.DEVICE_KEY }) {
            try {
                add(door.resume(r))
            } catch (e: SignInFailure) {
                // The person did not confirm: nothing more is asked of them this start.
                if (e.reason == SignInFailure.Reason.DEVICE_AUTH_CANCELLED) { stopped = r; break }
            } catch (e: Exception) {
                // a server that answers nothing this start: the next account
            }
        }
        val current = connections.current
        return if (current != null) openOrLock(current.first, current.second) else locked(stopped)
    }

    /**
     * The full sign-in, then this device trusted with the account by the password (DEVICE), then — where the device
     * offers a level and the person never said "Not now" for this account here — the offer to stay signed in
     * ([Gate.Signed.offer], ADR 0123 decision 8). A trust that cannot be enrolled leaves the sign-in standing: the next start then asks for
     * the Secret Key again.
     */
    suspend fun signIn(server: String, email: String, password: String, secretKey: String): Opened<A, T>? {
        started = true // a sign-in (the dev login among them) decides where the app is; the start does not run after it
        val adding = (current as? Gate.SignIn)?.adding == true
        _gate.value = Gate.SignIn(working = true, adding = adding, server = server, email = email)
        return try {
            val account = door.signIn(origin(server), email, password, secretKey)
            door.trust(account, password)
            add(account)
            offer = offerFor(account)
            val (held, tenant) = connections.current!!
            openTenant(held, tenant)
        } catch (e: Exception) {
            _gate.value = Gate.SignIn(error = words(reasonOf(e)), adding = adding, server = server, email = email)
            null
        }
    }

    /**
     * The trusted account's unlock: its password (DEVICE), or the person's authentication again (DEVICE_KEY). A password
     * unlock is a sign-in like the full one: the offer follows it where it would follow that one.
     */
    suspend fun unlock(password: String): Opened<A, T>? {
        val state = current as? Gate.Unlock ?: return null
        val record = state.record
        _gate.value = state.copy(working = true, error = null)
        return try {
            val account = when (record.method) {
                TrustedDevice.Method.DEVICE -> door.unlockOnDevice(record, password)
                TrustedDevice.Method.DEVICE_KEY -> door.resume(record)
            }
            add(account)
            if (record.method == TrustedDevice.Method.DEVICE) offer = offerFor(account)
            val (held, tenant) = connections.current!!
            openTenant(held, tenant)
        } catch (e: Exception) {
            val reason = reasonOf(e)
            _gate.value = when (reason) {
                SignInFailure.Reason.DEVICE_NOT_TRUSTED, SignInFailure.Reason.SESSION_ENDED ->
                    Gate.SignIn(error = words(reason), server = record.server, email = record.email)
                SignInFailure.Reason.DEVICE_AUTH_CANCELLED -> state.copy(working = false, error = null)
                else -> state.copy(working = false, error = words(reason))
            }
            null
        }
    }

    /** The debug build's unlock (`devlogin.py --unlock`): the password for the unlock screen, now or once it shows. */
    suspend fun devUnlock(password: String): Opened<A, T>? {
        if (current is Gate.Unlock) return unlock(password)
        pendingUnlock = password
        return null
    }

    /** From the unlock to the full sign-in, for another account. */
    fun otherAccount() {
        _gate.value = if (signInElsewhere) Gate.NeedsApp else Gate.SignIn()
    }

    /** "Add server": the sign-in again, which [cancelAdd] leaves back to the shell. */
    fun addServer() {
        _gate.value = Gate.SignIn(adding = true)
    }

    fun cancelAdd() {
        val o = opened ?: return
        _gate.value = signed(o.account.key, o.tenant.tenantId)
    }

    /** The offer's answer: stay signed in at [level] ([stay] for the offer's account); null without an offer. */
    suspend fun stay(level: StaySignedIn): TrustChange? {
        val o = offer ?: return null
        val account = connections.all.firstOrNull { it.key == o.connection } ?: return null.also { endOffer() }
        return stay(account, level)
    }

    /**
     * Staying signed in with [account] at [level], from the offer or "Manage servers" (ADR 0123 decision 8): the device's DEVICE_KEY wrap
     * for it at that level, replacing the trust it had (the other level's wrap and, unused, its key). A level the device
     * does not offer is [TrustChange.FAILED] without asking anyone; the level it has already is [TrustChange.DONE]. A
     * prompt the person cancelled ([TrustChange.CANCELLED]) leaves an offer standing; any other answer ends it, and a
     * level taken forgets an earlier "Not now".
     */
    suspend fun stay(account: A, level: StaySignedIn): TrustChange {
        val now = door.trustedBy(account)
        val change = when {
            level !in door.staySignedIn() -> TrustChange.FAILED
            now?.method == TrustedDevice.Method.DEVICE_KEY && now.unlockLevel == level -> TrustChange.DONE
            else -> door.stay(account, level)
        }
        if (change == TrustChange.DONE) door.setDeclined(account, false)
        if (offer?.connection == account.key && change != TrustChange.CANCELLED) endOffer()
        return change
    }

    /** The offer's "Not now": the password trust stays, and this account is not offered again on this device (ADR 0123 decision 8).
     *  False without an offer. */
    fun notNow(): Boolean {
        val o = offer ?: return false
        connections.all.firstOrNull { it.key == o.connection }?.let { door.setDeclined(it, true) }
        endOffer()
        return true
    }

    /**
     * "Manage servers"' way back from staying signed in to the password trust for [account] (ADR 0123 decision 8): a DEVICE wrap under
     * [password], which is checked first, replacing the DEVICE_KEY one. Already password-trusted, nothing changes.
     */
    suspend fun trustWithPassword(account: A, password: String): TrustChange {
        if (door.trustedBy(account)?.method == TrustedDevice.Method.DEVICE) return TrustChange.DONE
        return door.trustWithPassword(account, password)
    }

    /** The offer for [account] after its sign-in, or none: the share extension never offers, nor a device without a
     *  level, nor an account staying signed in already or whose offer the person declined here. */
    private fun offerFor(account: A): Offer? {
        if (signInElsewhere || door.declined(account)) return null
        if (door.trustedBy(account)?.method == TrustedDevice.Method.DEVICE_KEY) return null
        val levels = door.staySignedIn()
        return if (levels.isEmpty()) null else Offer(account.key, StaySignedIn.entries.filter { it in levels })
    }

    private fun endOffer() {
        offer = null
        (current as? Gate.Signed)?.let { _gate.value = it.copy(offer = null) }
    }

    /** The shell's gate, with the offer standing. */
    private fun signed(connectionKey: String, tenantId: String, switching: Boolean = false) =
        Gate.Signed(connectionKey, tenantId, switching, offer)

    /**
     * The switcher: another tenant of the same server is the same session at another domain; another server is its own.
     * The shell shown stays, [Gate.Signed.switching], until the other opened; one that does not open leaves it where it was.
     */
    suspend fun switchTo(account: A, tenant: TenantRef): Opened<A, T>? {
        val was = opened
        val before = connections.current
        connections.switchTo(account, tenant)
        if (was != null) _gate.value = signed(was.account.key, was.tenant.tenantId, switching = true)
        return try {
            openTenant(account, tenant)
        } catch (e: Exception) {
            before?.let { connections.switchTo(it.first, it.second) }
            if (was != null) _gate.value = signed(was.account.key, was.tenant.tenantId)
            null
        }
    }

    /** What a sign-out left: [next] when the account was current and another one opened in its place. */
    class SignedOut<A : Any, T : Any>(val wasCurrent: Boolean, val next: Opened<A, T>?)

    /**
     * Signs out of one server; if it was current, another one held becomes current, else the unlock of an account this
     * device still trusts with the password, or the sign-in — and the device forgets what was opened and hidden once no
     * account is left on it. Staying signed in ends with the sign-out; the password trust stays (as on the web).
     */
    suspend fun signOut(account: A): SignedOut<A, T> {
        val wasCurrent = connections.current?.first == account
        connections.remove(account)
        if (offer?.connection == account.key) offer = null
        if (wasCurrent) opened = null
        runCatching { door.signOut(account) }
        val next = connections.current
        return when {
            next == null -> {
                recent.clear()
                hidden.clear()
                SignedOut(wasCurrent, locked())
            }
            wasCurrent -> SignedOut(true, openOrLock(next.first, next.second))
            else -> SignedOut(false, null)
        }
    }

    /**
     * Ends this device's trust in [account]: the next sign-in on this device asks for the Secret Key again, and offers to
     * stay signed in again — an earlier "Not now" ends with the trust it was said for (ADR 0123 decision 8).
     */
    suspend fun forgetDevice(account: A) {
        runCatching { door.forgetDevice(account) }
        door.setDeclined(account, false)
    }

    /** A space on "Manage servers". */
    class ManagedSpace(val space: SpaceRef, val hidden: Boolean, val canHide: Boolean)

    /** A tenant on "Manage servers"; [spaces] is null while they load. */
    class ManagedTenant(val tenant: TenantRef, val hidden: Boolean, val current: Boolean, val spaces: List<ManagedSpace>?) {
        /** Its switch: on while it is shown — the current one always is, and cannot be hidden. */
        val shown: Boolean get() = !hidden || current
        val switchable: Boolean get() = !current

        /** The line under its name: "Shown now" for the current one, else its domain. */
        val subtitle: me.raddatz.veyq.mobile.words.Piece
            get() = if (current) me.raddatz.veyq.mobile.words.Piece.Said(me.raddatz.veyq.mobile.words.Notice(me.raddatz.veyq.mobile.words.Msg.MANAGE_CURRENT))
            else me.raddatz.veyq.mobile.words.Piece.Plain(tenant.domain)
    }

    /**
     * A server on "Manage servers": its account, its tenants, how this device trusts it ([trusted], null when it does not;
     * [level] the level a DEVICE_KEY trust is at), and the levels it may change to ([levels], the device's: [stay] for
     * one, [trustWithPassword] back to the password trust).
     */
    class ManagedServer<A : Any>(
        val account: A,
        val label: String,
        val tenants: List<ManagedTenant>,
        val trusted: TrustedDevice.Method?,
        val level: StaySignedIn? = null,
        val levels: List<StaySignedIn> = emptyList(),
    )

    /** Key of a tenant's spaces in [managed]'s `loaded`. */
    fun spacesKey(account: A, tenant: TenantRef): String = account.key + "#" + tenant.tenantId

    /** Every tenant's spaces [loadSpaces] read so far, by [spacesKey]. */
    var loadedSpaces: Map<String, List<SpaceRef>> = emptyMap()
        private set

    /** "Manage servers": every server, each tenant with what this device hides, and its spaces as far as they read. */
    fun managed(): List<ManagedServer<A>> = managed(loadedSpaces)

    internal fun managed(loaded: Map<String, List<SpaceRef>>): List<ManagedServer<A>> {
        val now = connections.current
        val levels = StaySignedIn.entries.filter { it in door.staySignedIn() }
        return connections.all.map { c ->
            val trust = door.trustedBy(c)
            ManagedServer(c, c.label, trusted = trust?.method, level = trust?.unlockLevel, levels = levels, tenants = c.tenants.map { t ->
                val spaces = loaded[spacesKey(c, t)]
                ManagedTenant(
                    tenant = t,
                    hidden = hidden.isTenantHidden(c.key, t.tenantId),
                    current = now?.first == c && now.second == t,
                    spaces = spaces?.map { ManagedSpace(it, hidden.isSpaceHidden(it.scopeId), hidden.canHideSpace(it.scopeId, spaces)) },
                )
            })
        }
    }

    /** Every tenant's spaces read anew for "Manage servers", [each] told as soon as one more read ([loadedSpaces]). */
    suspend fun loadSpaces(each: () -> Unit) {
        loadedSpaces = emptyMap()
        for (c in connections.all) for (t in c.tenants) {
            runCatching { door.spaces(door.open(c, t)) }.getOrNull()?.let { loadedSpaces = loadedSpaces + (spacesKey(c, t) to it); each() }
        }
    }

    private suspend fun add(account: A) {
        val tenant = door.defaultTenant(account) ?: throw SignInFailure(SignInFailure.Reason.NO_TENANT)
        connections.add(account, tenant).duplicate?.let { runCatching { door.signOut(it) } }
    }

    private suspend fun openTenant(account: A, tenant: TenantRef): Opened<A, T> {
        val o = Opened(account, tenant, door.open(account, tenant))
        door.opened(o.session)
        opened = o
        _gate.value = signed(account.key, tenant.tenantId)
        return o
    }

    /** The tenant opened, or — when it does not — the unlock or the sign-in with why. */
    private suspend fun openOrLock(account: A, tenant: TenantRef): Opened<A, T>? = try {
        openTenant(account, tenant)
    } catch (e: Exception) {
        connections.remove(account)
        locked(error = words(reasonOf(e)))
    }

    /** The unlock for [preferred], else the most recent account this device trusts, else the sign-in. */
    private suspend fun locked(preferred: TrustedDevice? = null, error: Msg? = null): Opened<A, T>? {
        val record = preferred ?: door.trusted().firstOrNull()
        _gate.value = when {
            record != null -> Gate.Unlock(record, error = error)
            signInElsewhere -> Gate.NeedsApp
            else -> Gate.SignIn(error = error)
        }
        val pending = pendingUnlock ?: return null
        pendingUnlock = null
        return if (record != null) unlock(pending) else null
    }

    companion object {
        /** A typed server: a bare host gets https, except a dev `*.localhost` host, which the dev backend serves on http. */
        fun origin(typed: String): String {
            val t = typed.trim().trimEnd('/')
            if ("://" in t) return t
            val host = t.substringBefore(':').substringBefore('/')
            return if (host == "localhost" || host.endsWith(".localhost")) "http://$t" else "https://$t"
        }

        /** What a failed sign-in or unlock says. */
        fun words(reason: SignInFailure.Reason): Msg = when (reason) {
            SignInFailure.Reason.UNREACHABLE -> Msg.SIGNIN_ERROR_UNREACHABLE
            SignInFailure.Reason.INCOMPATIBLE -> Msg.SIGNIN_ERROR_INCOMPATIBLE
            SignInFailure.Reason.CREDENTIALS -> Msg.SIGNIN_ERROR_CREDENTIALS
            SignInFailure.Reason.SECRET_KEY -> Msg.SIGNIN_ERROR_SECRET_KEY
            SignInFailure.Reason.NO_TENANT -> Msg.SIGNIN_ERROR_NO_TENANT
            SignInFailure.Reason.DEVICE_NOT_TRUSTED -> Msg.SIGNIN_ERROR_DEVICE_NOT_TRUSTED
            SignInFailure.Reason.SESSION_ENDED -> Msg.SIGNIN_ERROR_SESSION_ENDED
            SignInFailure.Reason.PROTOCOL, SignInFailure.Reason.DEVICE_AUTH_CANCELLED -> Msg.SIGNIN_ERROR_OTHER
        }

        private fun reasonOf(e: Exception): SignInFailure.Reason = (e as? SignInFailure)?.reason ?: SignInFailure.Reason.PROTOCOL

        /** The model over real accounts: [primitives] the platform's suite, [client] its HTTP client. */
        fun accounts(
            primitives: Primitives,
            device: ThisDevice,
            client: (block: HttpClientConfig<*>.() -> Unit) -> HttpClient,
            hidden: Hidden,
            recent: RecentUses,
            signInElsewhere: Boolean = false,
        ): SessionModel<VeyqAccount, TenantSession> = SessionModel(AccountDoor(primitives, device, client), hidden, recent, signInElsewhere)
    }
}

/** [SessionModel.Door] over [VeyqAccount]. */
class AccountDoor(
    private val primitives: Primitives,
    private val device: ThisDevice,
    private val client: (block: HttpClientConfig<*>.() -> Unit) -> HttpClient,
) : SessionModel.Door<VeyqAccount, TenantSession> {
    override fun trusted(): List<TrustedDevice> = device.trusted()

    override suspend fun signIn(server: String, email: String, password: String, secretKey: String): VeyqAccount =
        VeyqAccount.signIn(primitives, server, email, password, secretKey, device, client)

    override suspend fun unlockOnDevice(record: TrustedDevice, password: String): VeyqAccount =
        VeyqAccount.unlockOnDevice(primitives, record, password, device, client)

    override suspend fun resume(record: TrustedDevice): VeyqAccount = VeyqAccount.resume(primitives, record, device, client)

    override suspend fun trust(account: VeyqAccount, password: String) {
        runCatching { account.trustThisDevice(password, level = null) }
            .onFailure { if (it is CancellationException) throw it; println("veyq: this device could not be trusted with the account: $it") }
    }

    override suspend fun stay(account: VeyqAccount, level: StaySignedIn): TrustChange = changed("staying signed in") {
        if (account.trustThisDevice(null, level) == VeyqAccount.TrustOutcome.REMEMBERED) TrustChange.DONE else TrustChange.FAILED
    }

    override suspend fun trustWithPassword(account: VeyqAccount, password: String): TrustChange = changed("the password trust") {
        when {
            !account.isPassword(password) -> TrustChange.WRONG_PASSWORD
            account.trustThisDevice(password, level = null) == VeyqAccount.TrustOutcome.TRUSTED -> TrustChange.DONE
            else -> TrustChange.FAILED
        }
    }

    /** A change of trust's outcome: a cancelled prompt is [TrustChange.CANCELLED], any other failure logged and FAILED. */
    private suspend fun changed(what: String, block: suspend () -> TrustChange): TrustChange = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: DeviceAuthFailed) {
        if (e.cancelled) TrustChange.CANCELLED else TrustChange.FAILED.also { println("veyq: $what was not enrolled: $e") }
    } catch (e: Exception) {
        println("veyq: $what was not enrolled: $e")
        TrustChange.FAILED
    }

    override fun defaultTenant(account: VeyqAccount): TenantRef? = account.defaultTenant
    override fun trustedBy(account: VeyqAccount): TrustedDevice? = account.trusted
    /** A tenant opened re-keys by the build's kinds ([AppKinds.rules]): a write pulls its path forward, the resume finishes a scope. */
    override suspend fun open(account: VeyqAccount, tenant: TenantRef): TenantSession =
        account.open(tenant).also { it.rekeyRules = me.raddatz.veyq.mobile.AppKinds.rules }

    /** The tenant shown: the scopes served as re-keying are finished in the background ([TenantSession.resumeRekeys]). */
    override fun opened(session: TenantSession) {
        session.resumeRekeys()
    }
    override suspend fun spaces(session: TenantSession): List<SpaceRef> = session.spaces()
    override suspend fun signOut(account: VeyqAccount) = account.signOut()
    override suspend fun forgetDevice(account: VeyqAccount) = account.forgetThisDevice()
    override fun staySignedIn(): Set<StaySignedIn> = device.keys.staySignedIn
    override fun declined(account: VeyqAccount): Boolean = device.declined(account.key)
    override fun setDeclined(account: VeyqAccount, declined: Boolean) = device.setDeclined(account.key, declined)
}

/** What a change of this device's trust came to (ADR 0123 decision 8): [DONE]; the person [CANCELLED] the prompt (an offer stays); the
 *  password was not the account's; or it [FAILED] otherwise — the trust the account had stands in every case but DONE. */
enum class TrustChange { DONE, CANCELLED, WRONG_PASSWORD, FAILED }
