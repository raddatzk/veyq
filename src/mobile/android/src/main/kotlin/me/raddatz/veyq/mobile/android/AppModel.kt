package me.raddatz.veyq.mobile.android

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.raddatz.veyq.mobile.crypto.BouncyCastlePrimitives
import me.raddatz.veyq.mobile.expenses.Amounts
import me.raddatz.veyq.mobile.expenses.Ledger
import me.raddatz.veyq.mobile.expenses.PotCreate
import me.raddatz.veyq.mobile.expenses.PotSession
import me.raddatz.veyq.mobile.expenses.PotView
import me.raddatz.veyq.mobile.navigation.ApplicationId
import me.raddatz.veyq.mobile.navigation.ArrangedList
import me.raddatz.veyq.mobile.navigation.BarTab
import me.raddatz.veyq.mobile.navigation.Connections
import me.raddatz.veyq.mobile.navigation.FolderRef
import me.raddatz.veyq.mobile.navigation.Hidden
import me.raddatz.veyq.mobile.navigation.Home
import me.raddatz.veyq.mobile.navigation.NodeRef
import me.raddatz.veyq.mobile.navigation.ObjectRef
import me.raddatz.veyq.mobile.navigation.RecentUses
import me.raddatz.veyq.mobile.navigation.Shell
import me.raddatz.veyq.mobile.navigation.SpaceRef
import me.raddatz.veyq.mobile.navigation.TenantRef
import me.raddatz.veyq.mobile.navigation.UseKey
import me.raddatz.veyq.mobile.navigation.arrange
import me.raddatz.veyq.mobile.navigation.drawerApplications
import me.raddatz.veyq.mobile.session.DeviceAuthFailed
import me.raddatz.veyq.mobile.session.SignInFailure
import me.raddatz.veyq.mobile.session.ThisDevice
import me.raddatz.veyq.mobile.session.TrustedDevice
import me.raddatz.veyq.mobile.session.VeyqAccount

/** One server in the switcher: its account and the tenants the switcher lists there. */
data class ServerEntry(val account: VeyqAccount, val label: String, val tenants: List<TenantRef>)

/** A space on "Manage servers": shown or hidden on this device, and whether it may be hidden. */
data class ManagedSpace(val space: SpaceRef, val hidden: Boolean, val canHide: Boolean)

/** A tenant on "Manage servers". [spaces] is null while they load. */
data class ManagedTenant(val tenant: TenantRef, val hidden: Boolean, val current: Boolean, val spaces: List<ManagedSpace>?)

data class ManagedServer(val account: VeyqAccount, val label: String, val tenants: List<ManagedTenant>, val trusted: TrustedDevice.Method?)

/** What the screen shows: the sign-in (first, or to add a server), or the shell over the current tenant. */
sealed interface Screen {
    /** The remembered accounts resuming at start. */
    data object Starting : Screen

    /** The full sign-in, with the Secret Key; [server] and [email] filled in where a trusted device's record knew them. */
    data class SignIn(
        val working: Boolean = false,
        val error: String? = null,
        val adding: Boolean = false,
        val server: String? = null,
        val email: String? = null,
    ) : Screen

    /** An account this device trusts: the password alone (DEVICE), or the person's authentication (DEVICE_KEY). */
    data class Unlock(val record: TrustedDevice, val working: Boolean = false, val error: String? = null) : Screen

    data class Signed(
        val shell: Shell,
        val tenant: TenantRef,
        val server: String,
        val servers: List<ServerEntry>,
        val applications: List<ApplicationId>,
        val filters: List<ApplicationId?>,
        val drawer: ArrangedList,
        val spaces: List<SpaceRef>,
        val nodes: List<NodeRef>,
        val objects: List<ObjectRef>,
        val favorites: List<ObjectRef>,
        val switching: Boolean = false,
        /** "Manage servers" is open over the shell. */
        val managing: List<ManagedServer>? = null,
    ) : Screen {
        /** The chosen application's own list, by the one rule (ADR 0122 decision 5). */
        fun applicationList(app: ApplicationId): ArrangedList =
            arrange(favorites.filter { it.application == app }, emptyList(), objects.filter { it.application == app })

        val space: SpaceRef? get() = spaces.firstOrNull { it.scopeId == shell.space }

        /** Home's level: the shown folder's children in the shown space, under the shown filter. */
        val level: List<NodeRef> get() = shell.space?.let { Home.level(nodes, it, shell.folder?.nodeId, shell.filter) }.orEmpty()
    }
}

class AppModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("veyq-recent", Context.MODE_PRIVATE)
    private val recent = RecentUses(persist = ::persistRecent, initial = loadRecent())
    private val hidden = Hidden(persist = { prefs.edit().putStringSet("hidden", it).apply() }, initial = prefs.getStringSet("hidden", emptySet()).orEmpty())
    private val connections = Connections<VeyqAccount>()
    private var host: java.lang.ref.WeakReference<android.app.Activity>? = null
    private val device = ThisDevice(
        PreferencesStore(app),
        KeystoreDeviceKeys(app) { host?.get() },
        android.os.Build.MODEL,
        BouncyCastlePrimitives,
    )
    private var started = false

    /** The current tenant as read, before what this device hides is taken out. */
    private class Raw(val spaces: List<SpaceRef>, val nodes: List<NodeRef>, val favorites: List<ObjectRef>, val off: Set<String>)
    private var raw: Raw? = null

    private val _screen = MutableStateFlow<Screen>(Screen.Starting)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    /** The activity a prompt for the unlock key is shown over, while one is resumed. */
    fun attach(activity: android.app.Activity?) {
        host = activity?.let { java.lang.ref.WeakReference(it) }
    }

    /**
     * Where the app opens (ADR 0123 decision 8): every account the person stays signed in to resumes after one
     * authentication; with none, the most recent password-trusted account asks for its password; with no trusted account,
     * the sign-in. Once per process, from the first resumed activity (the prompt needs one).
     */
    fun start() {
        if (started) return
        started = true
        val records = device.trusted()
        if (records.isEmpty()) {
            _screen.value = Screen.SignIn()
            return
        }
        viewModelScope.launch {
            var stopped: TrustedDevice? = null
            for (r in records.filter { it.method == TrustedDevice.Method.DEVICE_KEY }) {
                try {
                    add(VeyqAccount.resume(BouncyCastlePrimitives, r, device) { block -> Network.client(block) })
                } catch (e: SignInFailure) {
                    // The person did not confirm: nothing more is asked of them this start.
                    if (e.reason == SignInFailure.Reason.DEVICE_AUTH_CANCELLED) { stopped = r; break }
                }
            }
            val current = connections.current
            if (current != null) show(current.first, current.second) else showLocked(stopped)
        }
    }

    /** The unlock for [preferred], else the most recent account this device trusts, else the sign-in. */
    private fun showLocked(preferred: TrustedDevice? = null, error: String? = null) {
        val record = preferred ?: device.trusted().firstOrNull()
        _screen.value = if (record != null) Screen.Unlock(record, error = error) else Screen.SignIn(error = error)
        pendingUnlock?.let { pendingUnlock = null; if (record != null) unlock(it) }
    }

    /** The debug build's unlock (`devlogin.py --unlock`): the password for the unlock screen, now or once it shows. */
    private var pendingUnlock: String? = null

    fun devUnlock(password: String) {
        if (_screen.value is Screen.Unlock) unlock(password) else pendingUnlock = password
    }

    private suspend fun add(account: VeyqAccount) {
        val tenant = account.defaultTenant ?: throw SignInFailure(SignInFailure.Reason.NO_TENANT)
        connections.add(account, tenant).duplicate?.signOut()
    }

    /**
     * The full sign-in, then this device trusted with the account: staying signed in when [remember] (which asks the
     * person to authenticate; declined, the password trust instead), else the password trust. A trust that cannot be
     * enrolled leaves the sign-in standing: the next start then asks for the Secret Key again.
     */
    fun signIn(server: String, email: String, password: String, secretKey: String, remember: Boolean = false) {
        started = true // a sign-in (the dev login among them) decides where the app is; the start does not run after it
        val adding = (_screen.value as? Screen.SignIn)?.adding == true
        _screen.value = Screen.SignIn(working = true, adding = adding, server = server, email = email)
        viewModelScope.launch {
            try {
                val account = VeyqAccount.signIn(BouncyCastlePrimitives, origin(server), email, password, secretKey, device) { block -> Network.client(block) }
                runCatching {
                    if (!remember) account.trustThisDevice(password, stayingSignedIn = false)
                    else try {
                        account.trustThisDevice(null, stayingSignedIn = true)
                    } catch (e: DeviceAuthFailed) {
                        account.trustThisDevice(password, stayingSignedIn = false)
                    }
                }.onFailure { android.util.Log.w("veyq", "this device could not be trusted with the account", it) }
                add(account)
                show(account, connections.current!!.second)
            } catch (e: SignInFailure) {
                _screen.value = Screen.SignIn(error = e.reason.name, adding = adding, server = server, email = email)
            }
        }
    }

    /** The trusted account's unlock: its password (DEVICE), or the person's authentication again (DEVICE_KEY). */
    fun unlock(password: String) {
        val state = _screen.value as? Screen.Unlock ?: return
        val record = state.record
        _screen.value = state.copy(working = true, error = null)
        viewModelScope.launch {
            try {
                val account = when (record.method) {
                    TrustedDevice.Method.DEVICE -> VeyqAccount.unlockOnDevice(BouncyCastlePrimitives, record, password, device) { block -> Network.client(block) }
                    TrustedDevice.Method.DEVICE_KEY -> VeyqAccount.resume(BouncyCastlePrimitives, record, device) { block -> Network.client(block) }
                }
                add(account)
                show(account, connections.current!!.second)
            } catch (e: SignInFailure) {
                _screen.value = when (e.reason) {
                    SignInFailure.Reason.DEVICE_NOT_TRUSTED, SignInFailure.Reason.SESSION_ENDED ->
                        Screen.SignIn(error = e.reason.name, server = record.server, email = record.email)
                    SignInFailure.Reason.DEVICE_AUTH_CANCELLED -> state.copy(working = false)
                    else -> state.copy(working = false, error = e.reason.name)
                }
            }
        }
    }

    /** From the unlock to the full sign-in, for another account. */
    fun otherAccount() {
        _screen.value = Screen.SignIn()
    }

    /** The switcher: another tenant of the same server is the same session at another domain; another server is its own. */
    fun switchTo(account: VeyqAccount, tenant: TenantRef) {
        connections.switchTo(account, tenant)
        _screen.update { s -> if (s is Screen.Signed) s.copy(switching = true, managing = null) else s }
        viewModelScope.launch {
            try {
                show(account, tenant)
            } catch (e: SignInFailure) {
                _screen.update { s -> if (s is Screen.Signed) s.copy(switching = false) else s }
            }
        }
    }

    /** "Add server": the sign-in again, which can be left back to the shell. */
    fun addServer() {
        _screen.value = Screen.SignIn(adding = true)
    }

    fun cancelAdd() {
        val (account, tenant) = connections.current ?: return
        viewModelScope.launch { show(account, tenant) }
    }

    // ---- Manage servers ----

    fun openManage() {
        _screen.update { s -> if (s is Screen.Signed) s.copy(managing = managed(emptyMap())) else s }
        viewModelScope.launch {
            val loaded = HashMap<String, List<SpaceRef>>()
            for (c in connections.all) for (t in c.tenants) {
                runCatching { c.open(t).spaces() }.getOrNull()?.let { loaded[c.key + "#" + t.tenantId] = it }
                _screen.update { s -> if (s is Screen.Signed && s.managing != null) s.copy(managing = managed(loaded)) else s }
            }
        }
    }

    fun closeManage() = _screen.update { s -> if (s is Screen.Signed) s.copy(managing = null) else s }

    fun setTenantHidden(account: VeyqAccount, tenant: TenantRef, value: Boolean) {
        hidden.setTenantHidden(account.key, tenant.tenantId, value)
        refreshManaged()
        rebuild()
    }

    fun setSpaceHidden(scopeId: String, value: Boolean) {
        hidden.setSpaceHidden(scopeId, value)
        refreshManaged()
        rebuild()
    }

    /**
     * Signs out of one server; if it was current, another one held becomes current, else the unlock of an account this
     * device still trusts with the password, or the sign-in. Staying signed in ends with the sign-out; the password trust
     * stays (as on the web).
     */
    fun signOut(account: VeyqAccount) {
        val wasCurrent = connections.current?.first == account
        connections.remove(account)
        val next = connections.current
        viewModelScope.launch {
            account.signOut()
            when {
                next == null -> {
                    recent.clear() // the device forgets what was opened once no account is left on it
                    hidden.clear()
                    raw = null
                    showLocked()
                }
                wasCurrent -> show(next.first, next.second)
                else -> refreshManaged()
            }
        }
    }

    /** Ends this device's trust in [account]: the next sign-in on this device asks for the Secret Key again. */
    fun forgetDevice(account: VeyqAccount) {
        viewModelScope.launch {
            account.forgetThisDevice()
            refreshManaged()
        }
    }

    private var managedSpaces: Map<String, List<SpaceRef>> = emptyMap()

    private fun managed(loaded: Map<String, List<SpaceRef>>): List<ManagedServer> {
        managedSpaces = loaded
        val current = connections.current
        return connections.all.map { c ->
            ManagedServer(c, c.label, trusted = c.trusted?.method, tenants = c.tenants.map { t ->
                val spaces = loaded[c.key + "#" + t.tenantId]
                ManagedTenant(
                    tenant = t,
                    hidden = hidden.isTenantHidden(c.key, t.tenantId),
                    current = current?.first == c && current.second == t,
                    spaces = spaces?.map { ManagedSpace(it, hidden.isSpaceHidden(it.scopeId), hidden.canHideSpace(it.scopeId, spaces)) },
                )
            })
        }
    }

    private fun refreshManaged() = _screen.update { s -> if (s is Screen.Signed && s.managing != null) s.copy(managing = managed(managedSpaces)) else s }

    // ---- the current tenant ----

    private suspend fun show(account: VeyqAccount, tenant: TenantRef) {
        val t = account.open(tenant)
        tenantSession = t
        _pot.value = null
        raw = Raw(t.spaces(), t.nodes(), t.favorites(), t.applicationsOff())
        rebuild(start = true)
    }

    // ---- an open Pot (Expenses) ----

    /** The tenant the shell shows, opened: where an object's own view reads from. */
    private var tenantSession: me.raddatz.veyq.mobile.session.TenantSession? = null
    private var potSession: PotSession? = null

    /** An open Pot's views: [view] null while it loads, [failed] when it did not open; [message] the last add's refusal. */
    data class PotState(val ref: ObjectRef, val view: PotView? = null, val failed: Boolean = false, val saving: Boolean = false, val message: String? = null)

    private val _pot = MutableStateFlow<PotState?>(null)
    val pot: StateFlow<PotState?> = _pot.asStateFlow()

    /** Opens and reads the Pot [ref] unless it is the one shown. */
    fun loadPot(ref: ObjectRef) {
        if (_pot.value?.ref == ref && !_pot.value!!.failed) return
        _pot.value = PotState(ref)
        viewModelScope.launch {
            val session = tenantSession?.let { runCatching { PotSession.open(it, ref.scopeId, ref.nodeId) }.getOrNull() }
            potSession = session
            if (_pot.value?.ref == ref) _pot.value = PotState(ref, session?.let(::PotView), failed = session == null)
        }
    }

    /**
     * One write to the open Pot ([op] on its session): the state shows it saving, then the Pot as read after it, and a
     * refusal as its message (a [PotSession.WriteOutcome] name); [done] runs once it was stored.
     */
    fun potWrite(op: suspend (PotSession) -> PotSession.WriteOutcome, done: () -> Unit = {}) {
        val state = _pot.value ?: return
        val session = potSession ?: return
        _pot.value = state.copy(saving = true, message = null)
        viewModelScope.launch {
            val outcome = runCatching { op(session) }.getOrDefault(PotSession.WriteOutcome.REFUSED)
            _pot.value = state.copy(view = PotView(session), saving = false, message = outcome.takeIf { it != PotSession.WriteOutcome.WRITTEN }?.name)
            if (outcome == PotSession.WriteOutcome.WRITTEN) done()
        }
    }

    /** The "New pot" sheet: saving while it creates, and its refusal ([PotCreate.Outcome] name). */
    data class CreatingPot(val saving: Boolean = false, val message: String? = null)

    private val _creating = MutableStateFlow(CreatingPot())
    val creating: StateFlow<CreatingPot> = _creating.asStateFlow()

    /** The current members of [scopeId], for the "New pot" sheet's people; null when they do not read. */
    suspend fun membersOf(scopeId: String): List<me.raddatz.veyq.mobile.session.Person>? =
        tenantSession?.let { runCatching { it.scopeMembers(scopeId) }.getOrNull() }

    /**
     * Creates a Pot named [name] at the root of [scopeId] with [currency] ([minorUnits] its digits) and [people], reads the
     * tenant's lists again and opens it; [done] runs once it exists (its people may still have failed: then the Pot opens
     * and says so on its Settings, where they can be added).
     */
    fun createPot(scopeId: String, name: String, currency: String, minorUnits: Int, people: List<PotCreate.NewPerson>, done: () -> Unit) {
        val t = tenantSession ?: return
        _creating.value = CreatingPot(saving = true)
        viewModelScope.launch {
            val r = runCatching { PotCreate.create(t, scopeId, null, name, Ledger.BaseCurrency(currency, minorUnits), people) }
                .getOrDefault(PotCreate.Result(PotCreate.Outcome.REFUSED))
            val nodeId = r.nodeId
            if (nodeId == null) {
                _creating.value = CreatingPot(message = r.outcome.name)
                return@launch
            }
            t.forgetTree()
            raw = Raw(t.spaces(), t.nodes(), t.favorites(), t.applicationsOff())
            rebuild()
            _creating.value = CreatingPot(message = r.outcome.takeIf { it != PotCreate.Outcome.CREATED }?.name)
            done()
            t.objects().firstOrNull { it.nodeId == nodeId }?.let(::open)
        }
    }

    fun clearCreating() {
        _creating.value = CreatingPot()
    }

    /** Forgets the open Pot's last refusal (a dialog closed). */
    fun clearPotMessage() {
        _pot.value = _pot.value?.copy(message = null)
    }

    /** The shell's lists from what was read, less what this device hides; on [start], the shell where the app opens. */
    private fun rebuild(start: Boolean = false) {
        val r = raw ?: return
        val (account, tenant) = connections.current ?: return
        val spaces = hidden.visibleSpaces(r.spaces)
        val nodes = hidden.visibleNodes(r.nodes, r.spaces)
        val objects = nodes.mapNotNull { it.asObject() }
        val favorites = hidden.visibleObjects(r.favorites, r.spaces)
        val prior = _screen.value as? Screen.Signed
        val defaultSpace = Home.defaultSpace(spaces)?.scopeId
        val shell = when {
            start || prior == null -> Shell.start(recent.lastUsed(objects), defaultSpace)
            prior.shell.space != null && spaces.none { it.scopeId == prior.shell.space } -> prior.shell.switchSpace(defaultSpace ?: "")
            else -> prior.shell
        }
        _screen.value = Screen.Signed(
            shell = shell,
            tenant = tenant,
            server = account.label,
            servers = connections.all.map { c -> ServerEntry(c, c.label, hidden.visibleTenants(c.key, c.tenants, if (c == account) tenant else null)) },
            applications = drawerApplications(r.off).map { it.id },
            filters = Home.filters(r.off),
            drawer = drawerList(objects, favorites),
            spaces = spaces,
            nodes = nodes,
            objects = objects,
            favorites = favorites,
            managing = if (start) null else prior?.managing,
        )
    }

    fun choose(app: ApplicationId?) = updateShell { it.choose(app) }
    fun switchSpace(scopeId: String) = updateShell { it.switchSpace(scopeId) }
    fun enter(folder: FolderRef) = updateShell { it.enter(folder) }
    fun filter(app: ApplicationId?) = updateShell { it.filter(app) }
    fun goBack() = updateShell { it.goBack() }

    fun open(ref: ObjectRef) {
        recent.record(ref, System.currentTimeMillis())
        _screen.update { s -> if (s is Screen.Signed) s.copy(shell = s.shell.open(ref), drawer = drawerList(s.objects, s.favorites)) else s }
    }

    fun select(tab: BarTab) = updateShell { it.select(tab) }
    fun search(on: Boolean) = updateShell { it.search(on) }

    /** A typed server: a bare host gets https, except a dev `*.localhost` host, which the dev backend serves on http. */
    private fun origin(typed: String): String {
        val t = typed.trim().trimEnd('/')
        if ("://" in t) return t
        val host = t.substringBefore(':').substringBefore('/')
        return if (host == "localhost" || host.endsWith(".localhost")) "http://$t" else "https://$t"
    }

    private fun updateShell(f: (Shell) -> Shell) = _screen.update { s -> if (s is Screen.Signed) s.copy(shell = f(s.shell)) else s }

    private fun drawerList(objects: List<ObjectRef>, favorites: List<ObjectRef>) =
        arrange(favorites, recent.resolve(objects), favoritesLimit = 6, recentLimit = 8)

    private fun persistRecent(keys: List<UseKey>) {
        prefs.edit().putString("uses", keys.joinToString("\n") { "${it.scopeId}\t${it.nodeId}\t${it.lastUsedEpochMs}" }).apply()
    }

    private fun loadRecent(): List<UseKey> = prefs.getString("uses", "").orEmpty().lines().mapNotNull { line ->
        val p = line.split('\t')
        if (p.size == 3) p[2].toLongOrNull()?.let { UseKey(p[0], p[1], it) } else null
    }
}
