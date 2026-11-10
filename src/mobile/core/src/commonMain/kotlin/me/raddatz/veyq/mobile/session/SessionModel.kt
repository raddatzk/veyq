package me.raddatz.veyq.mobile.session

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
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
 * password; with no trusted account, the sign-in —, the full sign-in with this device's trust after it, the unlock, the
 * switcher, a server added, a sign-out and what becomes current after it, and "Manage servers"' list.
 *
 * A UI observes [gate] (or [watch]) and draws the sign-in, the unlock or — once [Gate.Signed] — its shell over the
 * tenant an action returned [Opened]; it holds no account logic of its own. [A] is the account, [T] an opened tenant:
 * [accounts] builds the model over the real ones, a test over its own.
 */
class SessionModel<A : Connection, T : Any>(
    private val door: Door<A, T>,
    private val hidden: Hidden,
    private val recent: RecentUses,
    /** The share extension (ADR 0126): it resumes and unlocks, but never signs in — the app does. */
    private val signInElsewhere: Boolean = false,
) {
    /** What the model does with an account, the platform's suite and HTTP client behind it. */
    interface Door<A : Connection, T : Any> {
        /** The accounts this device trusts, most recent first. */
        fun trusted(): List<TrustedDevice>
        suspend fun signIn(server: String, email: String, password: String, secretKey: String): A
        suspend fun unlockOnDevice(record: TrustedDevice, password: String): A
        suspend fun resume(record: TrustedDevice): A

        /** This device trusted with [account] after a sign-in; never fails the sign-in. */
        suspend fun trust(account: A, password: String, remember: Boolean)
        fun defaultTenant(account: A): TenantRef?
        fun trustedMethod(account: A): TrustedDevice.Method?
        suspend fun open(account: A, tenant: TenantRef): T
        suspend fun spaces(session: T): List<SpaceRef>
        suspend fun signOut(account: A)
        suspend fun forgetDevice(account: A)
    }

    /** Where the way in stands; serializable, since the native bridge sends it as it is (CR core-as-a-native-library D6). */
    @Serializable
    sealed class Gate {
        /** The remembered accounts resuming at start. */
        @Serializable @SerialName("Starting")
        object Starting : Gate()

        /** The share extension with no account it can open: the app signs in (ADR 0126). */
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
         * shown, the switch to another under way.
         */
        @Serializable @SerialName("Signed")
        data class Signed(val connectionKey: String, val tenantId: String, val switching: Boolean = false) : Gate()
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

    /** The servers signed in to. */
    val accounts: List<A> get() = connections.all

    /** The account and tenant the shell is over, if any. */
    val signedTo: Opened<A, T>? get() = opened

    private var opened: Opened<A, T>? = null
    private var started = false
    private var pendingUnlock: String? = null

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
     * The full sign-in, then this device trusted with the account: staying signed in when [remember] (which asks the
     * person to authenticate; declined, the password trust instead), else the password trust. A trust that cannot be
     * enrolled leaves the sign-in standing: the next start then asks for the Secret Key again.
     */
    suspend fun signIn(server: String, email: String, password: String, secretKey: String, remember: Boolean): Opened<A, T>? {
        started = true // a sign-in (the dev login among them) decides where the app is; the start does not run after it
        val adding = (current as? Gate.SignIn)?.adding == true
        _gate.value = Gate.SignIn(working = true, adding = adding, server = server, email = email)
        return try {
            val account = door.signIn(origin(server), email, password, secretKey)
            door.trust(account, password, remember)
            add(account)
            val (held, tenant) = connections.current!!
            openTenant(held, tenant)
        } catch (e: Exception) {
            _gate.value = Gate.SignIn(error = words(reasonOf(e)), adding = adding, server = server, email = email)
            null
        }
    }

    /** The trusted account's unlock: its password (DEVICE), or the person's authentication again (DEVICE_KEY). */
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
        _gate.value = Gate.Signed(o.account.key, o.tenant.tenantId)
    }

    /**
     * The switcher: another tenant of the same server is the same session at another domain; another server is its own.
     * The shell shown stays, [Gate.Signed.switching], until the other opened; one that does not open leaves it where it was.
     */
    suspend fun switchTo(account: A, tenant: TenantRef): Opened<A, T>? {
        val was = opened
        val before = connections.current
        connections.switchTo(account, tenant)
        if (was != null) _gate.value = Gate.Signed(was.account.key, was.tenant.tenantId, switching = true)
        return try {
            openTenant(account, tenant)
        } catch (e: Exception) {
            before?.let { connections.switchTo(it.first, it.second) }
            if (was != null) _gate.value = Gate.Signed(was.account.key, was.tenant.tenantId)
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

    /** Ends this device's trust in [account]: the next sign-in on this device asks for the Secret Key again. */
    suspend fun forgetDevice(account: A) {
        runCatching { door.forgetDevice(account) }
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

    /** A server on "Manage servers": its account, its tenants, how this device trusts it, if it does. */
    class ManagedServer<A : Any>(val account: A, val label: String, val tenants: List<ManagedTenant>, val trusted: TrustedDevice.Method?)

    /** Key of a tenant's spaces in [managed]'s `loaded`. */
    fun spacesKey(account: A, tenant: TenantRef): String = account.key + "#" + tenant.tenantId

    /** Every tenant's spaces [loadSpaces] read so far, by [spacesKey]. */
    var loadedSpaces: Map<String, List<SpaceRef>> = emptyMap()
        private set

    /** "Manage servers": every server, each tenant with what this device hides, and its spaces as far as they read. */
    fun managed(): List<ManagedServer<A>> = managed(loadedSpaces)

    internal fun managed(loaded: Map<String, List<SpaceRef>>): List<ManagedServer<A>> {
        val now = connections.current
        return connections.all.map { c ->
            ManagedServer(c, c.label, trusted = door.trustedMethod(c), tenants = c.tenants.map { t ->
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
        opened = o
        _gate.value = Gate.Signed(account.key, tenant.tenantId)
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

    override suspend fun trust(account: VeyqAccount, password: String, remember: Boolean) {
        runCatching {
            if (!remember) account.trustThisDevice(password, stayingSignedIn = false)
            else try {
                account.trustThisDevice(null, stayingSignedIn = true)
            } catch (e: DeviceAuthFailed) {
                account.trustThisDevice(password, stayingSignedIn = false)
            }
        }.onFailure { println("veyq: this device could not be trusted with the account: $it") }
    }

    override fun defaultTenant(account: VeyqAccount): TenantRef? = account.defaultTenant
    override fun trustedMethod(account: VeyqAccount): TrustedDevice.Method? = account.trusted?.method
    override suspend fun open(account: VeyqAccount, tenant: TenantRef): TenantSession = account.open(tenant)
    override suspend fun spaces(session: TenantSession): List<SpaceRef> = session.spaces()
    override suspend fun signOut(account: VeyqAccount) = account.signOut()
    override suspend fun forgetDevice(account: VeyqAccount) = account.forgetThisDevice()
}
