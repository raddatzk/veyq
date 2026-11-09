package me.raddatz.veyq.mobile.android

import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
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
import me.raddatz.veyq.mobile.session.LivePoll
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
    init {
        // a copy sent to another app in an earlier run has been read by now (or never will be)
        clearSentCopies()
    }

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
        if (wasCurrent) uploads.clear()
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
        // a member's write pulls its path forward, and the scopes served as re-keying are finished in the background
        t.rekeyRules = me.raddatz.veyq.mobile.AppKinds.rules
        tenantSession?.let { was -> if (was.account !== account || was.tenant.tenantId != tenant.tenantId) uploads.clear() }
        tenantSession = t
        resumeRekeys(t)
        _pot.value = null
        readReportOffered()
        raw = Raw(t.spaces(), t.nodes(), t.favorites(), t.applicationsOff())
        readRowDetails(t)
        syncAvatars(t)
        rebuild(start = true)
    }

    // ---- what another app shared (ADR 0124) ----

    private val _incoming = MutableStateFlow<ShareIn.Incoming?>(null)
    /** Files shared to veyq, waiting for the Pot they go to (the pick mode); null when none waits. */
    val incoming: StateFlow<ShareIn.Incoming?> = _incoming.asStateFlow()

    /** What a Pot took, until its new expense is saved: its editor closed unsaved gives it back to the pick mode. */
    private var taken: ShareIn.Incoming? = null

    /**
     * Files arrive: the pick mode starts at the space's Home, never in the object last open, so the person chooses where
     * they go (ADR 0124 decision 1) — the start does the same while the session is still opening.
     */
    fun receiveShare(incoming: ShareIn.Incoming) {
        updateShell { Shell.start(null, it.space) }
        _incoming.value = incoming
    }

    fun cancelShare() {
        _incoming.value = null
    }

    /** The waiting files, taken by the Pot opened as their place: its new expense's receipts. */
    fun takeShare(): List<PotSession.PendingReceipt>? {
        val waiting = _incoming.value?.takeIf { it.files.isNotEmpty() } ?: return null
        taken = waiting
        _incoming.value = null
        return waiting.files
    }

    /** The new expense's editor closed unsaved: the files wait again, for this Pot or another. */
    fun returnShare() {
        taken?.let { taken = null; receiveShare(it) }
    }

    /** The new expense the shared files went into was saved: the share is done. */
    fun shareSaved() {
        taken = null
    }

    // ---- core's actions on a node ----

    /** Whether [ref] is pinned; null while its space's pin set is not loaded (no heart is offered then). */
    /** Whether [ref] is a guest's identity folder or lies in one (core's answer over the tree last read). */
    fun inIdentityFolder(ref: me.raddatz.veyq.mobile.navigation.NodeAt): Boolean = tenantSession?.inIdentityFolder(ref.scopeId, ref.nodeId) ?: false

    fun isFavorite(ref: me.raddatz.veyq.mobile.navigation.NodeAt): Boolean? = tenantSession?.isFavorite(ref.scopeId, ref.nodeId)

    /** Pins or unpins [ref], then the lists are read anew; false when it was not stored (the heart goes back). */
    suspend fun toggleFavorite(ref: me.raddatz.veyq.mobile.navigation.NodeAt): Boolean {
        val t = tenantSession ?: return false
        val now = t.isFavorite(ref.scopeId, ref.nodeId) ?: return false
        val stored = runCatching { t.setFavorite(ref.scopeId, ref.nodeId, !now) }.getOrDefault(false)
        refreshLists(t)
        return stored
    }

    private suspend fun refreshLists(t: me.raddatz.veyq.mobile.session.TenantSession) {
        runCatching { raw = Raw(t.spaces(), t.nodes(), t.favorites(), t.applicationsOff()) }
        readRowDetails(t)
        rebuild()
    }

    /**
     * Moves [ref] with everything under it to the space's trash; a page whose node went leaves for its parent. What came of
     * it, and the node as it was before (what an undo restores).
     */
    suspend fun trash(ref: me.raddatz.veyq.mobile.navigation.NodeAt): Pair<me.raddatz.veyq.mobile.session.NodeTrash.Deleted, me.raddatz.veyq.mobile.session.OpenNode?> {
        val t = tenantSession ?: return me.raddatz.veyq.mobile.session.NodeTrash.Deleted.FAILED to null
        val before = runCatching { t.node(ref.scopeId, ref.nodeId) }.getOrNull()
        val deleted = runCatching { me.raddatz.veyq.mobile.session.NodeTrash.delete(t, ref.scopeId, ref.nodeId) }.getOrDefault(me.raddatz.veyq.mobile.session.NodeTrash.Deleted.FAILED)
        if (deleted == me.raddatz.veyq.mobile.session.NodeTrash.Deleted.TRASHED || deleted == me.raddatz.veyq.mobile.session.NodeTrash.Deleted.GONE) {
            if ((_screen.value as? Screen.Signed)?.shell?.openObject?.nodeId == ref.nodeId) goBack()
            // a folder on Home's path went: Home shows the folder above it
            updateShell { s -> if (s.path.any { it.nodeId == ref.nodeId }) s.copy(path = s.path.takeWhile { it.nodeId != ref.nodeId }) else s }
            if (_pot.value?.ref?.nodeId == ref.nodeId) {
                _pot.value = null
                potSession = null
            }
        }
        refreshLists(t)
        return deleted to before
    }

    /** Brings [node] back from the trash under its name (the undo of [trash]); false when it could not be. */
    suspend fun restore(node: me.raddatz.veyq.mobile.session.OpenNode): Boolean {
        val t = tenantSession ?: return false
        val tokenInput = tokenInputFor(node.kind, node.name) ?: return false
        val restored = runCatching { me.raddatz.veyq.mobile.session.NodeTrash.restore(t, node, tokenInput) }.getOrNull()
        refreshLists(t)
        return restored == me.raddatz.veyq.mobile.session.NodeTrash.Restored.RESTORED
    }

    /**
     * Renames [ref] to [name] (core's `NodeRename`: the placement, then its open links' names); the lists are read anew
     * and an open page follows the node under its new name. What came of it.
     */
    suspend fun rename(ref: me.raddatz.veyq.mobile.navigation.NodeAt, name: String): me.raddatz.veyq.mobile.session.NodeRename.Result {
        val t = tenantSession ?: return me.raddatz.veyq.mobile.session.NodeRename.Result(me.raddatz.veyq.mobile.session.NodeRename.Outcome.FAILED)
        val r = runCatching { me.raddatz.veyq.mobile.session.NodeRename.rename(t, ref.scopeId, ref.nodeId, name, ::tokenInputFor, System.currentTimeMillis()) }
            .getOrDefault(me.raddatz.veyq.mobile.session.NodeRename.Result(me.raddatz.veyq.mobile.session.NodeRename.Outcome.FAILED))
        if (r.outcome == me.raddatz.veyq.mobile.session.NodeRename.Outcome.RENAMED) {
            refreshLists(t)
            val renamed = runCatching { t.objects() }.getOrDefault(emptyList()).firstOrNull { it.scopeId == ref.scopeId && it.nodeId == ref.nodeId }
            if (renamed != null && (_screen.value as? Screen.Signed)?.shell?.openObject?.nodeId == ref.nodeId) updateShell { it.goBack().open(renamed) }
            relocate(t, ref)
        }
        return r
    }

    /** Home's path read anew where [ref] is a folder on it (renamed or moved): the folder shown is where it is now. */
    private suspend fun relocate(t: me.raddatz.veyq.mobile.session.TenantSession, ref: me.raddatz.veyq.mobile.navigation.NodeAt) {
        val shell = (_screen.value as? Screen.Signed)?.shell ?: return
        if (shell.path.none { it.nodeId == ref.nodeId }) return
        val tree = runCatching { t.nodes() }.getOrDefault(emptyList()).filter { it.scopeId == ref.scopeId }
        updateShell { it.at(tree, it.folder?.nodeId) }
    }

    /**
     * How a kind folds a name to its slug token's input: the composition root's answer (`AppKinds`, the web's
     * `registry.tokenInputFor`). Null for a kind the app does not know: nothing is placed under it.
     */
    private fun tokenInputFor(kind: String, name: String): String? = me.raddatz.veyq.mobile.AppKinds.tokenInput(kind, name)

    // ---- a report (core's NodeReport) ----

    private val _reportOffered = MutableStateFlow(false)
    /** Whether the tenant's server takes reports (it publishes a moderation key): Report is offered only then. */
    val reportOffered: StateFlow<Boolean> = _reportOffered.asStateFlow()

    /** Asks the server once per tenant whether it takes reports. */
    fun readReportOffered() {
        val t = tenantSession ?: return
        viewModelScope.launch {
            _reportOffered.value = runCatching { me.raddatz.veyq.mobile.session.NodeReport.published(t) != null }.getOrDefault(false)
        }
    }

    /** Reports [ref] to the moderators: whole or by its name, in [category] for [reason], [contact] optional. */
    suspend fun report(ref: me.raddatz.veyq.mobile.navigation.NodeAt, scope: me.raddatz.veyq.mobile.session.NodeReport.Scope, category: String, reason: String, contact: String): me.raddatz.veyq.mobile.session.NodeReport.Sent {
        val failed = me.raddatz.veyq.mobile.session.NodeReport.Sent(me.raddatz.veyq.mobile.session.NodeReport.Outcome.FAILED)
        val t = tenantSession ?: return failed
        return runCatching { me.raddatz.veyq.mobile.session.NodeReport.report(t, ref.scopeId, ref.nodeId, scope, category, reason, contact) }.getOrDefault(failed)
    }

    /** The server's privacy notice at [anchor] (`privacyLink`): the page every place that takes personal data points to. */
    fun privacyLink(anchor: String): String = "${tenantSession?.origin.orEmpty()}/privacy#$anchor"

    // ---- guest links (core's ShareLinks) ----

    /** The tenant's link rules (its capability set): what the share dialog offers and requires. */
    fun linkRules(): me.raddatz.veyq.mobile.session.ShareLinks.Rules =
        tenantSession?.linkRules ?: me.raddatz.veyq.mobile.session.ShareLinks.Rules.UNKNOWN

    /** The READ and READ_WRITE links on [ref], newest first; null when they do not list. */
    suspend fun shareLinks(ref: me.raddatz.veyq.mobile.navigation.NodeAt): List<me.raddatz.veyq.mobile.session.ShareLinks.Link>? {
        val t = tenantSession ?: return null
        return runCatching { me.raddatz.veyq.mobile.session.ShareLinks.list(t, ref.scopeId, ref.nodeId, writeOnly = ref.kind == me.raddatz.veyq.mobile.depot.DepotFiles.DROPBOX) }.getOrNull()
    }

    /** Mints a [role] link on [ref]; on success its address, to hand out once. */
    suspend fun mintLink(ref: me.raddatz.veyq.mobile.navigation.NodeAt, role: String, label: String, password: String, expiresAt: String?): me.raddatz.veyq.mobile.session.ShareLinks.Minted {
        val failed = me.raddatz.veyq.mobile.session.ShareLinks.Minted(me.raddatz.veyq.mobile.session.ShareLinks.Outcome.FAILED)
        val t = tenantSession ?: return failed
        return runCatching { me.raddatz.veyq.mobile.session.ShareLinks.mint(t, ref.scopeId, ref.nodeId, role, label, password, expiresAt) }.getOrDefault(failed)
    }

    /**
     * Switches link [shareId] on [ref] off and re-keys the subtree below it (core's `ShareLinks.switchOff`, each kind's
     * content through `AppKinds`); an open Pot is read anew, since its key may have changed.
     */
    suspend fun switchOffLink(ref: me.raddatz.veyq.mobile.navigation.NodeAt, shareId: String): me.raddatz.veyq.mobile.session.ShareLinks.Closed {
        val t = tenantSession ?: return me.raddatz.veyq.mobile.session.ShareLinks.Closed.FAILED
        val r = runCatching {
            me.raddatz.veyq.mobile.session.ShareLinks.switchOff(t, ref.scopeId, ref.nodeId, shareId, me.raddatz.veyq.mobile.AppKinds.rekeys, ::tokenInputFor) { java.util.UUID.randomUUID().toString() }
        }.getOrDefault(me.raddatz.veyq.mobile.session.ShareLinks.Closed.FAILED)
        potRekeyed()
        return r
    }

    /**
     * Finishes, in the background, the re-key of every scope [t] was served as re-keying (ADR 0114 decision 9: a link's
     * close or a rotation another device began, or this one left when it closed): what this device can, top-down; the
     * lists are read anew after it. A run that cannot finish leaves the marks for the next start or another device.
     */
    private fun resumeRekeys(t: me.raddatz.veyq.mobile.session.TenantSession) {
        if (t.rekeyingScopes.isEmpty()) return
        viewModelScope.launch {
            val ran = runCatching { me.raddatz.veyq.mobile.session.NodeRekey.resume(t) }.getOrDefault(emptyMap())
            if (ran.isNotEmpty() && tenantSession === t) {
                potRekeyed()
                refreshLists(t)
            }
        }
    }

    /** Edits one of this member's open links on [ref] in place: label, password (null untouched, blank removed), end, role. */
    suspend fun editLink(ref: me.raddatz.veyq.mobile.navigation.NodeAt, shareId: String, edit: me.raddatz.veyq.mobile.session.ShareLinkChanges.Edit): me.raddatz.veyq.mobile.session.ShareLinkChanges.Changed {
        val failed = me.raddatz.veyq.mobile.session.ShareLinkChanges.Changed(me.raddatz.veyq.mobile.session.ShareLinkChanges.Outcome.FAILED)
        val t = tenantSession ?: return failed
        return runCatching { me.raddatz.veyq.mobile.session.ShareLinkChanges.edit(t, ref.scopeId, ref.nodeId, shareId, edit, System.currentTimeMillis()) }.getOrDefault(failed)
    }

    /** Renews link [shareId] on [ref]: a new address carrying its settings, the old one closed and the node re-keyed. */
    suspend fun renewLink(ref: me.raddatz.veyq.mobile.navigation.NodeAt, shareId: String): me.raddatz.veyq.mobile.session.ShareLinkChanges.Changed {
        val failed = me.raddatz.veyq.mobile.session.ShareLinkChanges.Changed(me.raddatz.veyq.mobile.session.ShareLinkChanges.Outcome.FAILED)
        val t = tenantSession ?: return failed
        val r = runCatching {
            me.raddatz.veyq.mobile.session.ShareLinkChanges.renew(t, ref.scopeId, ref.nodeId, shareId, me.raddatz.veyq.mobile.AppKinds.rekeys, ::tokenInputFor, { java.util.UUID.randomUUID().toString() }, System.currentTimeMillis())
        }.getOrDefault(failed)
        potRekeyed()
        return r
    }

    /** Switches link [shareId] on [ref] on again, its address kept (`ShareLinkChanges.reactivate`). */
    suspend fun reactivateLink(ref: me.raddatz.veyq.mobile.navigation.NodeAt, shareId: String): me.raddatz.veyq.mobile.session.ShareLinkChanges.Changed {
        val failed = me.raddatz.veyq.mobile.session.ShareLinkChanges.Changed(me.raddatz.veyq.mobile.session.ShareLinkChanges.Outcome.FAILED)
        val t = tenantSession ?: return failed
        return runCatching { me.raddatz.veyq.mobile.session.ShareLinkChanges.reactivate(t, ref.scopeId, ref.nodeId, shareId) }.getOrDefault(failed)
    }

    /** Deletes link [shareId] on [ref] for good. */
    suspend fun deleteLink(ref: me.raddatz.veyq.mobile.navigation.NodeAt, shareId: String): me.raddatz.veyq.mobile.session.ShareLinkChanges.Changed {
        val failed = me.raddatz.veyq.mobile.session.ShareLinkChanges.Changed(me.raddatz.veyq.mobile.session.ShareLinkChanges.Outcome.FAILED)
        val t = tenantSession ?: return failed
        val r = runCatching {
            me.raddatz.veyq.mobile.session.ShareLinkChanges.delete(t, ref.scopeId, ref.nodeId, shareId, me.raddatz.veyq.mobile.AppKinds.rekeys, ::tokenInputFor, { java.util.UUID.randomUUID().toString() }, System.currentTimeMillis())
        }.getOrDefault(failed)
        potRekeyed()
        return r
    }

    /**
     * The open Pot read anew under its key, after a re-key that may have reached it: a link on it or on a folder above it
     * closed, or a scope's re-key finished in the background. A Pot the re-key did not reach reads the same.
     */
    private suspend fun potRekeyed() {
        val session = potSession
        if (session != null) {
            runCatching { session.rekeyed() }
            if (potSession === session) _pot.update { it?.copy(view = PotView(session)) }
        }
    }

    // ---- a move (core's NodeMove) ----

    /** Where a node of a kind may be placed: the build's kinds' placement (`AppKinds.placement`). */
    val placement = me.raddatz.veyq.mobile.AppKinds.placement

    /** Every node of [scopeId] this device opens: what the move browser walks. */
    suspend fun scopeNodes(scopeId: String): List<me.raddatz.veyq.mobile.navigation.NodeRef> {
        val t = tenantSession ?: return emptyList()
        return runCatching { t.nodes() }.getOrDefault(emptyList()).filter { it.scopeId == scopeId }
    }

    /** Moves [ref] under [to] (null: the space's root), its name kept; the lists are read anew. */
    suspend fun move(ref: me.raddatz.veyq.mobile.navigation.NodeAt, to: String?): me.raddatz.veyq.mobile.session.NodeMove.Result {
        val t = tenantSession ?: return me.raddatz.veyq.mobile.session.NodeMove.Result(me.raddatz.veyq.mobile.session.NodeMove.Outcome.FAILED)
        val r = runCatching { me.raddatz.veyq.mobile.session.NodeMove.move(t, ref.scopeId, ref.nodeId, to, ::tokenInputFor, placement) }
            .getOrDefault(me.raddatz.veyq.mobile.session.NodeMove.Result(me.raddatz.veyq.mobile.session.NodeMove.Outcome.FAILED))
        refreshLists(t)
        if (r.outcome == me.raddatz.veyq.mobile.session.NodeMove.Outcome.MOVED) relocate(t, ref)
        return r
    }

    /**
     * Copies the open Pot [ref] to [to] (null: the space's root) in its space (uc.platform.copy-node): a new Pot under fresh
     * keys with its people, live entries and receipts; the lists are read anew. A failed copy leaves nothing behind.
     */
    suspend fun copyPot(ref: ObjectRef, to: String?): me.raddatz.veyq.mobile.expenses.PotCopy.Copied {
        val failed = me.raddatz.veyq.mobile.expenses.PotCopy.Copied(me.raddatz.veyq.mobile.expenses.PotCopy.Outcome.FAILED)
        val t = tenantSession ?: return failed
        val session = potSession?.takeIf { _pot.value?.ref == ref } ?: return failed
        val deleted = getApplication<Application>().getString(R.string.pot_deleted_person)
        val r = runCatching {
            me.raddatz.veyq.mobile.expenses.PotCopy.duplicate(session, t, ref.scopeId, to, deleted) { java.util.UUID.randomUUID().toString() }
        }.getOrDefault(failed)
        refreshLists(t)
        return r
    }

    // ---- the space's trash (core's NodeTrash) ----

    /** The trash of [scopeId] (the root of each deleted subtree); null when it could not be read. */
    suspend fun trash(scopeId: String): List<me.raddatz.veyq.mobile.session.NodeTrash.Trashed>? {
        val t = tenantSession ?: return null
        return runCatching { me.raddatz.veyq.mobile.session.NodeTrash.list(t, scopeId) }.getOrNull()
    }

    /** Whether the app can bring [row] back: its kind's fold of a name is known here. */
    fun canRestore(row: me.raddatz.veyq.mobile.session.NodeTrash.Trashed): Boolean = tokenInputFor(row.kind, row.name) != null

    /** Brings [row] back from the trash, under [newName] after its own was taken; the lists are read anew. */
    suspend fun restoreFromTrash(row: me.raddatz.veyq.mobile.session.NodeTrash.Trashed, newName: String? = null): me.raddatz.veyq.mobile.session.NodeTrash.Restored {
        val refused = me.raddatz.veyq.mobile.session.NodeTrash.Restored.REFUSED
        val t = tenantSession ?: return refused
        val token = tokenInputFor(row.kind, newName?.trim() ?: row.name) ?: return refused
        val r = runCatching { me.raddatz.veyq.mobile.session.NodeTrash.restoreFromTrash(t, row, token, newName) }.getOrDefault(refused)
        refreshLists(t)
        return r
    }

    /** Erases [row] and everything under it now: irreversible. */
    suspend fun purge(row: me.raddatz.veyq.mobile.session.NodeTrash.Trashed): me.raddatz.veyq.mobile.session.NodeTrash.Purged {
        val t = tenantSession ?: return me.raddatz.veyq.mobile.session.NodeTrash.Purged.FAILED
        return runCatching { me.raddatz.veyq.mobile.session.NodeTrash.purge(t, row.scopeId, row.id) }.getOrDefault(me.raddatz.veyq.mobile.session.NodeTrash.Purged.FAILED)
    }

    // ---- an open Pot (Expenses) ----

    /** The tenant the shell shows, opened: where an object's own view reads from. */
    private var tenantSession: me.raddatz.veyq.mobile.session.TenantSession? = null
    private var potSession: PotSession? = null

    /** An open Pot's views: [view] null while it loads, [failed] when it did not open; [message] the last add's refusal. */
    data class PotState(val ref: ObjectRef, val view: PotView? = null, val failed: Boolean = false, val saving: Boolean = false, val message: String? = null)

    private val _pot = MutableStateFlow<PotState?>(null)

    /** What each object's row says beside its name, by node id (an application's answer: Expenses' `PotRows`). */
    private val _rowDetails = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val rowDetails: StateFlow<Map<String, List<String>>> = _rowDetails.asStateFlow()

    /** The members' pictures this device opened, by user id (core's `MemberAvatars`): the letter for everyone else. */
    private val _avatars = MutableStateFlow<Map<String, ByteArray>>(emptyMap())
    val avatars: StateFlow<Map<String, ByteArray>> = _avatars.asStateFlow()

    /** Fetches the pictures the tenant's directory names a revision for, in the background; the letters show meanwhile. */
    private fun syncAvatars(t: me.raddatz.veyq.mobile.session.TenantSession) {
        _avatars.value = emptyMap()
        viewModelScope.launch {
            runCatching { t.avatars.sync(t.people.values) }
            if (tenantSession === t) _avatars.value = t.avatars.pictures()
        }
    }

    private suspend fun readRowDetails(t: me.raddatz.veyq.mobile.session.TenantSession) {
        val pots = runCatching { me.raddatz.veyq.mobile.expenses.PotRows.details(t) }.getOrDefault(emptyMap())
        val files = runCatching { me.raddatz.veyq.mobile.depot.DepotFiles.list(t) }.getOrDefault(emptyMap())
        if (tenantSession === t) _files.value = files
        val app = getApplication<Application>()
        // a drop box's row: how many guests have a folder in it (Depot's `people`)
        val boxes = runCatching { t.nodes() }.getOrDefault(emptyList()).let { nodes ->
            nodes.filter { it.kind == me.raddatz.veyq.mobile.depot.DepotFiles.DROPBOX }.associate { box ->
                val n = nodes.count { it.parentId == box.nodeId && it.kind == "FOLDER" }
                box.nodeId to listOf(if (n == 0) app.getString(R.string.dropbox_people_none) else app.resources.getQuantityString(R.plurals.dropbox_people, n, n))
            }
        }
        _rowDetails.value = pots + files.mapValues { (_, f) -> fileDetails(f) } + boxes
        readCovers(t)
    }

    // ---- what the shell says (a snackbar), and an upload under way ----

    /** A notice the shell shows once: its text and, where offered, an action (Depot's "Replace anyway"). */
    class Notice(val text: String, val action: String? = null, val run: (suspend () -> Unit)? = null)

    private val _notices = kotlinx.coroutines.channels.Channel<Notice>(kotlinx.coroutines.channels.Channel.BUFFERED)
    val notices: kotlinx.coroutines.flow.Flow<Notice> = _notices.receiveAsFlow()

    fun notify(n: Notice) {
        _notices.trySend(n)
    }

    /**
     * The app's one upload queue (core's `UploadQueue`, the web's `core/blob/queue.ts`): every application's uploads, one
     * after another, retried once; the shell's tray shows it ([UploadTray]). It outlives a screen, not the account: a
     * sign-out or a switch of tenant empties it.
     */
    val uploads = me.raddatz.veyq.mobile.blob.UploadQueue()
    val uploadState: StateFlow<me.raddatz.veyq.mobile.blob.UploadQueue.State> = uploads.state

    /** The name of a picked directory whose folders are being made before its files queue up. */
    private val _preparing = MutableStateFlow<String?>(null)
    val preparing: StateFlow<String?> = _preparing.asStateFlow()

    /**
     * Queues the picked [uris] as new files under the level shown ([label] its name; `DepotFiles.UploadJob`): a name a file
     * already holds offers its replace (Depot's `replaceConfirm`).
     */
    fun uploadPicked(scopeId: String, parentId: String?, label: String, uris: List<android.net.Uri>) {
        val t = tenantSession ?: return
        viewModelScope.launch {
            val jobs = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                uris.mapNotNull { picked(it) }.map { me.raddatz.veyq.mobile.depot.DepotFiles.UploadJob(t, scopeId, parentId, source(it), me.raddatz.veyq.mobile.AppKinds::tokenInput) }
            }
            uploads.enqueue(jobs, label, parentId ?: scopeId, ::depotSettled, null)
        }
    }

    /**
     * A Depot item settled: the lists read anew; a name a file holds offers that file's replace, content someone replaced
     * meanwhile "Replace anyway" — each said once, the item taken out of the tray (its notice says it).
     */
    private fun depotSettled(item: me.raddatz.veyq.mobile.blob.UploadQueue.Item) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            tenantSession?.let { refreshLists(it) }
            if (item.status != me.raddatz.veyq.mobile.blob.UploadQueue.Status.FAILED) return@launch
            val job = item.job
            when {
                job is me.raddatz.veyq.mobile.depot.DepotFiles.UploadJob && item.error == me.raddatz.veyq.mobile.depot.DepotFiles.Codes.EXISTS -> {
                    val existing = job.existing ?: return@launch
                    uploads.remove(item.id)
                    notify(Notice(app.getString(R.string.depot_replace_confirm, item.name), app.getString(R.string.depot_replace_title)) {
                        replace(existing, job.picked, _files.value[existing]?.blobId)
                    })
                }
                job is me.raddatz.veyq.mobile.depot.DepotFiles.ReplaceJob && item.error == me.raddatz.veyq.mobile.depot.DepotFiles.Codes.STALE -> {
                    uploads.remove(item.id)
                    notify(Notice(app.getString(R.string.depot_error_stale_file, item.name), app.getString(R.string.depot_replace_anyway)) {
                        replace(job.file.nodeId, job.picked, job.currentBlobId)
                    })
                }
            }
        }
    }

    /** Queues file [nodeId]'s content replaced with [source] over [expected]; content changed meanwhile offers "Replace anyway". */
    fun replace(nodeId: String, source: me.raddatz.veyq.mobile.blob.UploadSource, expected: String?) {
        val t = tenantSession ?: return
        val file = _files.value[nodeId] ?: return
        val name = source.name
        uploads.enqueue(listOf(me.raddatz.veyq.mobile.depot.DepotFiles.ReplaceJob(t, file, source, expected, name)), name, nodeId, ::depotSettled, null)
    }

    /**
     * Depot's "Upload folder…": the directory the system's picker granted ([tree], `OpenDocumentTree`) walked through its
     * documents provider — only its files, each read at any offset —, its folders made under [parentId] (`FolderUpload`)
     * and its files queued as one batch under its name; what could not be made is said, in the web's words.
     */
    fun uploadFolderPicked(scopeId: String, parentId: String?, tree: android.net.Uri) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val t = tenantSession ?: return@launch
            val report = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    val (rootName, entries) = treeEntries(tree)
                    _preparing.value = rootName
                    me.raddatz.veyq.mobile.depot.FolderUpload.prepare(t, scopeId, parentId, rootName, entries, me.raddatz.veyq.mobile.AppKinds::tokenInput)
                }.getOrNull()
            }
            _preparing.value = null
            refreshLists(t)
            val r = report ?: return@launch notify(Notice(app.getString(R.string.share_failed)))
            val size = android.text.format.Formatter.formatShortFileSize(app, r.bytes)
            when (r.outcome) {
                me.raddatz.veyq.mobile.depot.FolderUpload.Outcome.NO_ROOM -> notify(Notice(app.getString(R.string.depot_folder_no_room, r.name, size)))
                me.raddatz.veyq.mobile.depot.FolderUpload.Outcome.ROOT_FAILED -> notify(Notice(app.getString(R.string.depot_folder_failed, r.name)))
                me.raddatz.veyq.mobile.depot.FolderUpload.Outcome.READY -> {
                    if (r.failedFolders.isNotEmpty()) notify(Notice(r.failedFolders.joinToString("\n") { app.getString(R.string.depot_folder_part_failed, it) }))
                    uploads.enqueue(r.jobs, r.name, r.rootId, ::depotSettled, null)
                }
                me.raddatz.veyq.mobile.depot.FolderUpload.Outcome.NOTHING -> {}
            }
        }
    }

    /** The directory [tree] names: its name and every file below it, by its path there (`/`-separated). */
    private fun treeEntries(tree: android.net.Uri): Pair<String, List<me.raddatz.veyq.mobile.depot.FolderUpload.Entry>> {
        val resolver = getApplication<Application>().contentResolver
        val rootId = android.provider.DocumentsContract.getTreeDocumentId(tree)
        val rootName = resolver.query(
            android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, rootId),
            arrayOf(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null,
        )?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "Folder"
        val out = ArrayList<me.raddatz.veyq.mobile.depot.FolderUpload.Entry>()
        fun walk(docId: String, path: String) {
            val children = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            val cols = arrayOf(
                android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID, android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE, android.provider.DocumentsContract.Document.COLUMN_SIZE,
                android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            )
            val rows = ArrayList<Array<Any?>>()
            resolver.query(children, cols, null, null, null)?.use { q ->
                while (q.moveToNext()) rows += arrayOf(q.getString(0), q.getString(1), q.getString(2), if (q.isNull(3)) 0L else q.getLong(3), if (q.isNull(4)) null else q.getLong(4))
            }
            for (row in rows) {
                val id = row[0] as String
                val name = row[1] as String
                val mime = row[2] as String?
                val at = if (path.isEmpty()) name else "$path/$name"
                if (mime == android.provider.DocumentsContract.Document.MIME_TYPE_DIR) { walk(id, at); continue }
                val uri = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, id)
                out += me.raddatz.veyq.mobile.depot.FolderUpload.Entry(at, me.raddatz.veyq.mobile.blob.UploadSource.of(name, row[3] as Long, mime ?: "application/octet-stream", row[4] as Long?) { from, length ->
                    // opened per frame: the upload asks one frame after another and never holds the file
                    val pfd = resolver.openFileDescriptor(uri, "r") ?: throw java.io.IOException("unreadable")
                    pfd.use { d ->
                        java.io.FileInputStream(d.fileDescriptor).channel.use { ch ->
                            val buf = java.nio.ByteBuffer.allocate(length)
                            while (buf.hasRemaining()) if (ch.read(buf, from + buf.position()) < 0) break
                            buf.array().copyOf(buf.position())
                        }
                    }
                })
            }
        }
        walk(rootId, "")
        return rootName to out
    }

    // ---- Depot's files (the application's `depot/`) ----

    /**
     * "Add files only" on folder [folder]: a drop box named [name] in it with its first link (`DropBoxes.create`); the lists
     * are read anew.
     */
    suspend fun createDropBox(folder: me.raddatz.veyq.mobile.navigation.NodeAt, name: String, password: String, expiresAt: String?): me.raddatz.veyq.mobile.depot.DropBoxes.Created =
        createDropBox(folder.scopeId, folder.nodeId, name, password, expiresAt)

    /** Who uploaded [file]'s content (Depot's `uploaderOf`); null where nobody is to be named. */
    suspend fun uploader(file: me.raddatz.veyq.mobile.depot.DepotFiles.File): me.raddatz.veyq.mobile.depot.Uploaders.Uploader? {
        val t = tenantSession ?: return null
        return runCatching { me.raddatz.veyq.mobile.depot.Uploaders.of(t, file) }.getOrNull()
    }

    /** "Add files only" at [parentId] of [scopeId] (null: the space's top level, the web's level share): a drop box there with its first link. */
    suspend fun createDropBox(scopeId: String, parentId: String?, name: String, password: String, expiresAt: String?): me.raddatz.veyq.mobile.depot.DropBoxes.Created {
        val t = tenantSession ?: return me.raddatz.veyq.mobile.depot.DropBoxes.Created(me.raddatz.veyq.mobile.depot.DropBoxes.Outcome.FAILED)
        val r = runCatching { me.raddatz.veyq.mobile.depot.DropBoxes.create(t, scopeId, parentId, name, password, expiresAt) }
            .getOrDefault(me.raddatz.veyq.mobile.depot.DropBoxes.Created(me.raddatz.veyq.mobile.depot.DropBoxes.Outcome.FAILED))
        refreshLists(t)
        return r
    }

    /** Memory or chapter [nodeId]'s gallery (`MemoryMedia.list`); null when it could not be read. */
    suspend fun memoryGallery(scopeId: String, nodeId: String): me.raddatz.veyq.mobile.memories.MemoryMedia.Gallery? {
        val t = tenantSession ?: return null
        return runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { me.raddatz.veyq.mobile.memories.MemoryMedia.list(t, scopeId, nodeId) } }.getOrNull()
    }

    /** Decoded tiers by blob id, the most recent kept (a gallery asks for every tile as it scrolls past). */
    private val mediaPictures = object : android.util.LruCache<String, androidx.compose.ui.graphics.ImageBitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: androidx.compose.ui.graphics.ImageBitmap) = value.width * value.height * 4
    }

    /** [blob] of [item] read and decoded as a picture (a tile, a preview); null where it does not read or is no picture. */
    suspend fun mediaPicture(item: me.raddatz.veyq.mobile.memories.MemoryMedia.Item, blob: me.raddatz.veyq.mobile.memories.MemoryMedia.Blob): androidx.compose.ui.graphics.ImageBitmap? {
        mediaPictures.get(blob.blobId)?.let { return it }
        val t = tenantSession ?: return null
        val picture = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val bytes = me.raddatz.veyq.mobile.memories.MemoryMedia.read(t, item, blob) ?: return@runCatching null
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }.getOrNull()
        } ?: return null
        mediaPictures.put(blob.blobId, picture)
        return picture
    }

    /** [item]'s heavy EXIF tier (`MediaDetails.exif`); null where none was sealed or it could not be read. */
    suspend fun mediaExif(item: me.raddatz.veyq.mobile.memories.MemoryMedia.Item): me.raddatz.veyq.mobile.memories.MediaDetails.Exif? {
        val t = tenantSession ?: return null
        return runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { me.raddatz.veyq.mobile.memories.MediaDetails.exif(t, item) } }.getOrNull()
    }

    /** Who added each of [items], by owner key (`MediaDetails.uploaders`); empty where it could not be read. */
    suspend fun mediaUploaders(scopeId: String, items: List<me.raddatz.veyq.mobile.memories.MemoryMedia.Item>): Map<String, me.raddatz.veyq.mobile.memories.MediaDetails.Uploader> {
        val t = tenantSession ?: return emptyMap()
        return runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { me.raddatz.veyq.mobile.memories.MediaDetails.uploaders(t, scopeId, items) } }.getOrDefault(emptyMap())
    }

    /** [item]'s original streamed into [out] a frame at a time; false where it did not read through. */
    suspend fun downloadMedium(item: me.raddatz.veyq.mobile.memories.MemoryMedia.Item, out: java.io.OutputStream): Boolean {
        val t = tenantSession ?: return false
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { me.raddatz.veyq.mobile.memories.MemoryMedia.stream(t, item, item.original) { out.write(it) } }.getOrDefault(false)
        }
    }

    /** [item]'s original read at any offset, for a player (`MemoryMedia.reader`); null where it does not open here. */
    fun mediaReader(item: me.raddatz.veyq.mobile.memories.MemoryMedia.Item): me.raddatz.veyq.mobile.blob.RangeReader? =
        tenantSession?.let { me.raddatz.veyq.mobile.memories.MemoryMedia.reader(it, item, item.original) }

    private val _galleryRevision = MutableStateFlow(0)

    /** Moves each time a medium this device uploaded is in (a gallery on screen reads itself again). */
    val galleryRevision: StateFlow<Int> = _galleryRevision.asStateFlow()

    /**
     * Queues the photos and videos the system's picker granted ([uris]) for Memory or chapter [nodeId] ([label] its name;
     * `MediaUpload.Job`): each one's tiers derived here ([MediaDerive]) while the one before it is on the wire, its bytes
     * read at any offset; each one in moves the gallery on screen, the batch's end the lists (the Memory's date range).
     */
    fun uploadMedia(scopeId: String, nodeId: String, label: String, uris: List<android.net.Uri>) {
        val t = tenantSession ?: return
        viewModelScope.launch {
            val jobs = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                uris.mapNotNull { pickedMedium(it) }.map { me.raddatz.veyq.mobile.memories.MediaUpload.Job(t, scopeId, nodeId, source(it), deriver, System::currentTimeMillis) }
            }
            uploads.enqueue(jobs, label, nodeId, { if (it.status == me.raddatz.veyq.mobile.blob.UploadQueue.Status.DONE) _galleryRevision.value += 1 }) {
                viewModelScope.launch { refreshLists(t) }
            }
        }
    }

    /** Android's tiers of a picked medium ([MediaDerive]), for core's upload job. */
    private val deriver = object : me.raddatz.veyq.mobile.memories.MediaUpload.Deriver {
        override fun derive(source: me.raddatz.veyq.mobile.blob.UploadSource, type: String, done: (me.raddatz.veyq.mobile.memories.MediaUpload.Derived) -> Unit) {
            val uri = (source as? ContentSource)?.uri
            done(uri?.let { MediaDerive.of(getApplication(), it, type) } ?: me.raddatz.veyq.mobile.memories.MediaUpload.Derived(null, null, null, null))
        }
    }

    /**
     * A photo or video the system's picker handed over ([uri]), read as it was taken where the person gave the app full
     * media access: the media store's own item behind it — its location kept (`setRequireOriginal`), its own name — else
     * (no such access, only some photos allowed, an item the store does not hold) the picker's copy as it is.
     */
    private fun pickedMedium(uri: android.net.Uri): Picked? {
        val picked = picked(uri) ?: return null
        val app = getApplication<Application>()
        fun granted(p: String) = app.checkSelfPermission(p) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val full = if (android.os.Build.VERSION.SDK_INT >= 33) granted(android.Manifest.permission.READ_MEDIA_IMAGES) && granted(android.Manifest.permission.READ_MEDIA_VIDEO)
        else granted(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        if (!full || !granted(android.Manifest.permission.ACCESS_MEDIA_LOCATION)) return picked
        return runCatching {
            val id = uri.lastPathSegment?.toLongOrNull() ?: return picked
            val video = picked.type.startsWith("video/")
            val base = if (video) android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI else android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val item = android.content.ContentUris.withAppendedId(base, id)
            val resolver = app.contentResolver
            val cols = arrayOf(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, android.provider.MediaStore.MediaColumns.SIZE, android.provider.MediaStore.MediaColumns.DATE_MODIFIED)
            val listed = resolver.query(item, cols, null, null, null)?.use { c ->
                if (!c.moveToFirst()) null
                else Triple(c.getString(0), c.getLong(1), if (c.isNull(2)) null else c.getLong(2) * 1000)
            } ?: return picked
            val original = android.provider.MediaStore.setRequireOriginal(item)
            resolver.openFileDescriptor(original, "r")?.close() ?: return picked
            Picked(original, listed.first ?: picked.name, listed.second.takeIf { it > 0 } ?: picked.size, picked.type, listed.third ?: picked.modifiedAt)
        }.getOrDefault(picked)
    }

    /** Whether the app asked for full media access before (once: a no is the person's, and the picker works without it). */
    fun mediaAccessAsked(): Boolean {
        val prefs = getApplication<Application>().getSharedPreferences("veyq.memories", android.content.Context.MODE_PRIVATE)
        val asked = prefs.getBoolean("mediaAccessAsked", false)
        if (!asked) prefs.edit().putBoolean("mediaAccessAsked", true).apply()
        return asked
    }

    /** Drop box [nodeId]'s guests' folders with their files (`DropBoxes.folders`); null when they could not be read. */
    suspend fun dropBoxFolders(scopeId: String, nodeId: String): List<me.raddatz.veyq.mobile.depot.DropBoxes.GuestFolder>? {
        val t = tenantSession ?: return null
        return runCatching { me.raddatz.veyq.mobile.depot.DropBoxes.folders(t, scopeId, nodeId) }.getOrNull()
    }

    /** Every file of the tenant with its content's reference and opened meta, by node id (`DepotFiles.list`). */
    private val _files = MutableStateFlow<Map<String, me.raddatz.veyq.mobile.depot.DepotFiles.File>>(emptyMap())
    val files: StateFlow<Map<String, me.raddatz.veyq.mobile.depot.DepotFiles.File>> = _files.asStateFlow()

    /** A file's row facts (Depot's `details`): its size and when it was modified, or that its upload has not finished. */
    private fun fileDetails(f: me.raddatz.veyq.mobile.depot.DepotFiles.File): List<String> {
        val app = getApplication<Application>()
        if (!f.hasContent) return listOf(app.getString(R.string.depot_not_uploaded))
        return listOfNotNull(
            f.size?.let { android.text.format.Formatter.formatShortFileSize(app, it) },
            f.modifiedAt?.let { app.getString(R.string.depot_modified, java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(it))) },
        )
    }

    /** [file]'s content a frame at a time ([DepotFiles.read]). */
    suspend fun readFile(file: me.raddatz.veyq.mobile.depot.DepotFiles.File, first: Int = 0, until: Int = Int.MAX_VALUE, sink: suspend (ByteArray) -> Unit) {
        val t = tenantSession ?: throw IllegalStateException("signed out")
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { me.raddatz.veyq.mobile.depot.DepotFiles.read(t, file, first, until, sink) }
    }

    /** Writes file [ref]'s content to [out] as it is read; nothing left out, or null when it did not finish. */
    suspend fun downloadFile(ref: me.raddatz.veyq.mobile.navigation.NodeAt, out: java.io.OutputStream): List<String>? {
        val file = _files.value[ref.nodeId]?.takeIf { it.hasContent } ?: return null
        val ok = runCatching {
            out.buffered(1 shl 16).let { b ->
                readFile(file) { b.write(it) }
                b.flush()
            }
        }.isSuccess
        runCatching { out.close() }
        return if (ok) emptyList() else null
    }

    /**
     * The file a person is about to send a copy of to another app ("Send a copy…"), while the shell asks them first: the copy
     * leaves veyq decrypted. The data are theirs; the app says what happens and never forbids it.
     */
    private val _sending = MutableStateFlow<me.raddatz.veyq.mobile.navigation.NodeAt?>(null)
    val sending: StateFlow<me.raddatz.veyq.mobile.navigation.NodeAt?> = _sending.asStateFlow()

    fun askToSend(ref: me.raddatz.veyq.mobile.navigation.NodeAt?) {
        _sending.value = ref
    }

    /** Where the copies sent to another app wait to be read: cleared before the next one and when the app starts. */
    private val sharedDir get() = java.io.File(getApplication<Application>().cacheDir, "shared")

    fun clearSentCopies() {
        runCatching { sharedDir.deleteRecursively() }
    }

    /**
     * A decrypted copy of file [ref] under its own name (core's `downloadName`), in the app's shared cache, and the content
     * URI another app reads it through (the FileProvider, read permission granted to the one it is sent to); null when it
     * could not be written.
     */
    suspend fun copyForSending(ref: me.raddatz.veyq.mobile.navigation.NodeAt): Pair<android.net.Uri, String>? {
        val file = _files.value[ref.nodeId]?.takeIf { it.hasContent } ?: return null
        val app = getApplication<Application>()
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            clearSentCopies()
            val dir = java.io.File(sharedDir, java.util.UUID.randomUUID().toString()).apply { mkdirs() }
            val out = java.io.File(dir, me.raddatz.veyq.mobile.depot.DepotFiles.downloadName(file, ref.name))
            val ok = runCatching { out.outputStream().buffered().use { o -> readFile(file) { o.write(it) } } }.isSuccess
            if (!ok) {
                dir.deleteRecursively()
                return@withContext null
            }
            androidx.core.content.FileProvider.getUriForFile(app, "${app.packageName}.files", out) to (file.type ?: "application/octet-stream")
        }
    }

    /** A picked document as an upload reads it: its name, size, type and modified time from the system's listing. */
    class Picked(val uri: android.net.Uri, val name: String, val size: Long, val type: String, val modifiedAt: Long?)

    fun picked(uri: android.net.Uri): Picked? {
        val resolver = getApplication<Application>().contentResolver
        return runCatching {
            resolver.query(uri, null, null, null, null)?.use { c ->
                if (!c.moveToFirst()) return@use null
                val name = c.getString(c.getColumnIndexOrThrow(android.provider.OpenableColumns.DISPLAY_NAME))
                val size = c.getLong(c.getColumnIndexOrThrow(android.provider.OpenableColumns.SIZE))
                val modified = c.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED).takeIf { it >= 0 && !c.isNull(it) }?.let { c.getLong(it) }
                Picked(uri, name, size, resolver.getType(uri) ?: "application/octet-stream", modified)
            }
        }.getOrNull()
    }

    /**
     * A picked document read for an upload (core's `UploadSource`): its descriptor opened on the first read, read at any
     * offset through its channel, and closed when the job's run ends; a retry opens it again.
     */
    class ContentSource(private val resolver: android.content.ContentResolver, val uri: android.net.Uri, name: String, size: Long, type: String, modifiedAt: Long?) :
        me.raddatz.veyq.mobile.blob.UploadSource(name, size, type, modifiedAt) {
        private var open: Pair<android.os.ParcelFileDescriptor, java.nio.channels.FileChannel>? = null

        @Synchronized
        private fun channel(): java.nio.channels.FileChannel = (open ?: run {
            val pfd = resolver.openFileDescriptor(uri, "r") ?: throw java.io.IOException("unreadable")
            (pfd to java.io.FileInputStream(pfd.fileDescriptor).channel).also { open = it }
        }).second

        override suspend fun read(from: Long, length: Int): ByteArray {
            val ch = channel()
            val buf = java.nio.ByteBuffer.allocate(length)
            while (buf.hasRemaining()) if (ch.read(buf, from + buf.position()) < 0) break
            return buf.array().copyOf(buf.position())
        }

        @Synchronized
        override fun close() {
            open?.let { (pfd, ch) -> runCatching { ch.close() }; runCatching { pfd.close() } }
            open = null
        }
    }

    fun source(p: Picked): me.raddatz.veyq.mobile.blob.UploadSource = ContentSource(getApplication<Application>().contentResolver, p.uri, p.name, p.size, p.type, p.modifiedAt)

    /** Why [item] did not go up, in its application's words (the tray's line under it); null while it may still. */
    fun uploadProblem(item: me.raddatz.veyq.mobile.blob.UploadQueue.Item): String? {
        if (item.status != me.raddatz.veyq.mobile.blob.UploadQueue.Status.FAILED) return null
        val app = getApplication<Application>()
        return when (item.error) {
            me.raddatz.veyq.mobile.blob.UploadQueue.QUOTA_EXCEEDED -> app.getString(R.string.upload_no_space)
            me.raddatz.veyq.mobile.blob.UploadQueue.APPLICATION_OFF -> app.getString(R.string.upload_switched_off)
            me.raddatz.veyq.mobile.depot.DepotFiles.Codes.NAME_TAKEN -> app.getString(R.string.upload_name_taken)
            me.raddatz.veyq.mobile.depot.DepotFiles.Codes.BUSY -> app.getString(R.string.move_refused_rekey_pending)
            else -> app.getString(R.string.upload_status_failed)
        }
    }

    /** Each listed node's cover as a small picture of its framed part, by node id (core's covers, uc.platform.node-cover):
     *  opened once per blob and crop (the pair's key), dropped when the node names another or none. */
    private val _covers = MutableStateFlow<Map<String, Pair<String, androidx.compose.ui.graphics.ImageBitmap>>>(emptyMap())
    val covers: StateFlow<Map<String, Pair<String, androidx.compose.ui.graphics.ImageBitmap>>> = _covers.asStateFlow()

    private fun readCovers(t: me.raddatz.veyq.mobile.session.TenantSession) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val named = runCatching { me.raddatz.veyq.mobile.session.NodeCover.framed(t) }.getOrDefault(emptyMap())
            val have = _covers.value.filter { (id, c) -> named[id] == c.first }
            val fresh = named.filter { (id, key) -> have[id]?.first != key }.mapNotNull { (id, key) ->
                val opened = runCatching { me.raddatz.veyq.mobile.session.NodeCover.openFramed(t, id) }.getOrNull() ?: return@mapNotNull null
                thumbnail(opened.bytes, opened.crop)?.let { id to (key to it) }
            }
            if (tenantSession === t) _covers.value = have + fresh
        }
    }

    /** A cover's framed part decoded at a list's size (a row draws it a few dozen pixels wide, a header a few hundred). */
    private fun thumbnail(bytes: ByteArray, crop: me.raddatz.veyq.mobile.session.NodeCover.Crop): androidx.compose.ui.graphics.ImageBitmap? = runCatching {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 384) sample *= 2
        val whole = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
        val (x, y, w, h) = me.raddatz.veyq.mobile.session.CoverFrame.pixels(crop, whole.width, whole.height).toList()
        android.graphics.Bitmap.createBitmap(whole, x, y, w, h)
    }.getOrNull()?.asImageBitmap()

    /** A picture decoded for the cover sheet's frame: its long edge at most the cover's 2048 (`COVER_SPEC`). */
    private fun picture(source: android.graphics.ImageDecoder.Source): android.graphics.Bitmap =
        android.graphics.ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val long = maxOf(info.size.width, info.size.height)
            if (long > 2048) {
                val f = 2048.0 / long
                decoder.setTargetSize((info.size.width * f).toInt().coerceAtLeast(1), (info.size.height * f).toInt().coerceAtLeast(1))
            }
            decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
        }

    /** The picture at [uri], for the cover sheet to frame before it is set; null when it does not read as an image. */
    suspend fun coverPicture(uri: android.net.Uri): androidx.compose.ui.graphics.ImageBitmap? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching { picture(android.graphics.ImageDecoder.createSource(getApplication<Application>().contentResolver, uri)).asImageBitmap() }.getOrNull()
    }

    /** [ref]'s cover as it is stored — the whole picture and its crop — for the cover sheet to frame anew; null: none. */
    suspend fun coverSource(ref: me.raddatz.veyq.mobile.navigation.NodeAt): Pair<androidx.compose.ui.graphics.ImageBitmap, me.raddatz.veyq.mobile.session.NodeCover.Crop>? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val t = tenantSession ?: return@withContext null
            val opened = runCatching { me.raddatz.veyq.mobile.session.NodeCover.openFramed(t, ref.nodeId) }.getOrNull() ?: return@withContext null
            runCatching { picture(android.graphics.ImageDecoder.createSource(java.nio.ByteBuffer.wrap(opened.bytes))).asImageBitmap() }.getOrNull()?.let { it to opened.crop }
        }

    /** [ref]'s cover as its page's head shows it: the framed part of the stored picture, at up to 2048 px; null: none. */
    suspend fun coverHero(ref: me.raddatz.veyq.mobile.navigation.NodeAt): androidx.compose.ui.graphics.ImageBitmap? {
        val (image, crop) = coverSource(ref) ?: return null
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            runCatching {
                val whole = image.asAndroidBitmap()
                val (x, y, w, h) = me.raddatz.veyq.mobile.session.CoverFrame.pixels(crop, whole.width, whole.height).toList()
                android.graphics.Bitmap.createBitmap(whole, x, y, w, h).asImageBitmap()
            }.getOrNull()
        }
    }

    /** Moves the crop of [ref]'s cover, its picture kept (the web's `keep`); false when it did not land. */
    suspend fun recropCover(ref: me.raddatz.veyq.mobile.navigation.NodeAt, crop: me.raddatz.veyq.mobile.session.NodeCover.Crop): Boolean {
        val t = tenantSession ?: return false
        val done = runCatching { me.raddatz.veyq.mobile.session.NodeCover.recrop(t, ref.scopeId, ref.nodeId, crop) }
            .getOrNull() == me.raddatz.veyq.mobile.session.NodeCover.Outcome.DONE
        refreshLists(t)
        return done
    }

    /**
     * Sets the picture at [uri] as the cover of [ref], framed by [crop]: decoded on the device and made the web's cover JPEG
     * (long edge 2048, quality 0.85, `COVER_SPEC`) — the whole picture, the crop stored beside it as the web stores it —,
     * uploaded and attached (core's `NodeCover`); false when it did not land.
     */
    suspend fun setCover(
        ref: me.raddatz.veyq.mobile.navigation.NodeAt, uri: android.net.Uri,
        crop: me.raddatz.veyq.mobile.session.NodeCover.Crop = me.raddatz.veyq.mobile.session.NodeCover.Crop.FULL,
    ): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val t = tenantSession ?: return@withContext false
        val jpeg = runCatching {
            val bitmap = picture(android.graphics.ImageDecoder.createSource(getApplication<Application>().contentResolver, uri))
            java.io.ByteArrayOutputStream().also { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
        }.getOrNull() ?: return@withContext false
        val done = runCatching { me.raddatz.veyq.mobile.session.NodeCover.set(t, ref.scopeId, ref.nodeId, jpeg, crop) }
            .getOrNull() == me.raddatz.veyq.mobile.session.NodeCover.Outcome.DONE
        refreshLists(t)
        done
    }

    /**
     * Creates a folder named [name] in [scopeId] under [parentId] (null: the space's root), core's folder (the web's
     * `FolderNameDialog`): the lists are read anew. Why not, in the web's words, or null once created.
     */
    suspend fun createFolder(scopeId: String, parentId: String?, name: String): String? {
        val t = tenantSession ?: return getApplication<Application>().getString(R.string.folder_failed)
        val n = name.trim()
        if (n.isEmpty()) return getApplication<Application>().getString(R.string.folder_name_empty)
        val r = runCatching {
            me.raddatz.veyq.mobile.session.FolderCreate.create(t, scopeId, parentId, n)
        }.getOrNull()
        refreshLists(t)
        return when (r) {
            is me.raddatz.veyq.mobile.session.NodeCreate.Result.Done -> null
            is me.raddatz.veyq.mobile.session.NodeCreate.Result.Refused ->
                if (r.reason == me.raddatz.veyq.mobile.session.NodeCreate.Reason.NAME_TAKEN) getApplication<Application>().getString(R.string.folder_name_taken)
                else getApplication<Application>().getString(R.string.folder_failed)
            null -> getApplication<Application>().getString(R.string.folder_failed)
        }
    }

    /** What the drawer's "New" asked Home to create ("folder" or "pot"), until Home opens its dialog. */
    private val _newRequest = MutableStateFlow<String?>(null)
    val newRequest: StateFlow<String?> = _newRequest.asStateFlow()

    /** The drawer's "New": Home, where its dialog for [kind] opens. */
    fun requestNew(kind: String) {
        choose(null)
        _newRequest.value = kind
    }

    fun newRequestTaken() {
        _newRequest.value = null
    }

    /** Removes the cover of [ref]; false when it did not land. */
    suspend fun removeCover(ref: me.raddatz.veyq.mobile.navigation.NodeAt): Boolean {
        val t = tenantSession ?: return false
        val done = runCatching { me.raddatz.veyq.mobile.session.NodeCover.remove(t, ref.scopeId, ref.nodeId) }
            .getOrNull() == me.raddatz.veyq.mobile.session.NodeCover.Outcome.DONE
        refreshLists(t)
        return done
    }
    val pot: StateFlow<PotState?> = _pot.asStateFlow()

    /** Opens and reads the Pot [ref] unless it is the one shown. */
    fun loadPot(ref: ObjectRef) {
        if (_pot.value?.ref == ref && !_pot.value!!.failed) return
        _pot.value = PotState(ref)
        viewModelScope.launch {
            val session = tenantSession?.let { runCatching { PotSession.open(it, ref.scopeId, ref.nodeId) }.getOrNull() }
            potSession = session
            potPoll = LivePoll(now = System::currentTimeMillis)
            if (_pot.value?.ref == ref) _pot.value = PotState(ref, session?.let(::PotView), failed = session == null)
        }
    }

    private var potPoll: LivePoll? = null

    /**
     * The open Pot's live poll (core's `LivePoll`), for as long as the caller runs it — the Pot's screen while it is
     * started, so nothing is asked in the background: skipped while a write shows saving; the view read anew when a
     * tick moved what is held.
     */
    suspend fun livePot() {
        val session = potSession ?: return
        val cadence = potPoll ?: return
        session.live(cadence, busy = { _pot.value?.saving != false }) {
            if (potSession === session) _pot.update { it?.copy(view = PotView(session)) }
        }
    }

    /** Reads the open Pot again now (pull to refresh); [done] when it settled, moved or not. */
    fun refreshPot(done: () -> Unit) {
        val session = potSession ?: return done()
        viewModelScope.launch {
            runCatching {
                session.refresh()
                session.readReceipts()
            }
            if (potSession === session) _pot.update { it?.copy(view = PotView(session)) }
            done()
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
            // A settings write may have set the base currency: the Pot's row says it at once.
            _rowDetails.update { it + (state.ref.nodeId to me.raddatz.veyq.mobile.expenses.PotRows.detailsOf(session.meta)) }
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
    fun createPot(scopeId: String, name: String, currency: String, minorUnits: Int, people: List<PotCreate.NewPerson>, parentId: String? = null, done: () -> Unit) {
        val t = tenantSession ?: return
        _creating.value = CreatingPot(saving = true)
        viewModelScope.launch {
            val r = runCatching { PotCreate.create(t, scopeId, parentId, name, Ledger.BaseCurrency(currency, minorUnits), people) }
                .getOrDefault(PotCreate.Result(PotCreate.Outcome.REFUSED))
            val nodeId = r.nodeId
            if (nodeId == null) {
                _creating.value = CreatingPot(message = r.outcome.name)
                return@launch
            }
            t.forgetTree()
            raw = Raw(t.spaces(), t.nodes(), t.favorites(), t.applicationsOff())
            readRowDetails(t)
            rebuild()
            _creating.value = CreatingPot(message = r.outcome.takeIf { it != PotCreate.Outcome.CREATED }?.name)
            done()
            t.objects().firstOrNull { it.nodeId == nodeId }?.let(::open)
        }
    }

    fun clearCreating() {
        _creating.value = CreatingPot()
    }

    /** One unit of [currency] in the open Pot's base from today's table, or null: the rate is typed then. */
    suspend fun rateFor(currency: String): me.raddatz.veyq.mobile.expenses.ExchangeRates.Filled? =
        potSession?.let { runCatching { it.rateFor(currency, java.time.LocalDate.now().toString()) }.getOrNull() }

    /** A receipt of the open Pot opened to its bytes, or null (blocked, or it did not read). */
    /**
     * Downloads the open Pot [ref] into [out] as the web's ZIP (uc.platform.export-import, ADR 0082): its settings, people and
     * live entries, each receipt, and a CSV of the entries. False when it did not complete; [out] is closed either way.
     */
    suspend fun downloadPot(ref: ObjectRef, out: java.io.OutputStream): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val session = potSession?.takeIf { _pot.value?.ref == ref }
        val deleted = getApplication<Application>().getString(R.string.pot_deleted_person)
        val done = session != null && runCatching {
            out.buffered(1 shl 16).let { b ->
                me.raddatz.veyq.mobile.expenses.PotExport.download(session, deleted, System.currentTimeMillis()) { b.write(it) }.also { b.flush() }
            } != null
        }.getOrDefault(false)
        runCatching { out.close() }
        done
    }

    /** Why a node was left out of a download or a copy, in the web's words (`export.reason.*`, `node.copy.reason.*`). */
    private fun leftOutLine(s: me.raddatz.veyq.mobile.export.NodeExport.Skipped): String {
        val app = getApplication<Application>()
        val reason = app.getString(
            when (s.reason) {
                me.raddatz.veyq.mobile.export.NodeExport.NOTHING_TO_EXPORT -> R.string.left_out_nothing
                me.raddatz.veyq.mobile.export.SubtreeCopy.NOT_COPYABLE -> R.string.left_out_not_copyable
                else -> R.string.left_out_unsupported
            },
        )
        return app.getString(R.string.left_out_line, s.name, reason)
    }

    /**
     * Writes folder [ref] and everything under it as the web's ZIP into [out] (core's `NodeExport.walk` and `write`, the kinds
     * the composition root exports); what it left out, as lines to say, or null when it did not finish.
     */
    suspend fun downloadFolder(ref: me.raddatz.veyq.mobile.navigation.NodeAt, out: java.io.OutputStream): List<String>? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val t = tenantSession ?: return@withContext null
        val deleted = getApplication<Application>().getString(R.string.pot_deleted_person)
        val skipped = runCatching {
            val walked = me.raddatz.veyq.mobile.export.NodeExport.walk(t, ref.scopeId, ref.nodeId, me.raddatz.veyq.mobile.AppKinds.exports(deleted)) ?: return@runCatching null
            out.buffered(1 shl 16).let { b ->
                me.raddatz.veyq.mobile.export.NodeExport.write(
                    walked.root, me.raddatz.veyq.mobile.session.VeyqAccount.MAJOR.toString(), System.currentTimeMillis(),
                ) { b.write(it) }
                b.flush()
            }
            walked.skipped
        }.getOrNull()
        runCatching { out.close() }
        skipped?.map(::leftOutLine)
    }

    /** What a copy of folder [ref] would make (its kinds) and leave out (as lines to say), before anything is written. */
    suspend fun copyPreview(ref: me.raddatz.veyq.mobile.navigation.NodeAt): Pair<Set<String>, List<String>> {
        val t = tenantSession ?: return emptySet<String>() to emptyList()
        val p = runCatching {
            me.raddatz.veyq.mobile.export.SubtreeCopy.preview(t, ref.scopeId, ref.nodeId, me.raddatz.veyq.mobile.AppKinds.copies("") { "" })
        }.getOrNull() ?: return emptySet<String>() to emptyList()
        return p.kinds to p.leftOut.map(::leftOutLine)
    }

    /**
     * Copies folder [ref] with everything under it to [to] (null: the space's top) in its space (core's `SubtreeCopy`): a new
     * folder tree under fresh keys, each Pot by Expenses' copy; the lists are read anew. Why not, or null once copied.
     */
    /** Why a copy of [ref] cannot go: the tenant has no room for its bytes (`StorageFit.noRoomForCopy`); null where it fits. */
    suspend fun copyNoRoom(ref: me.raddatz.veyq.mobile.navigation.NodeAt): String? {
        val app = getApplication<Application>()
        val t = tenantSession ?: return null
        val bytes = runCatching {
            me.raddatz.veyq.mobile.session.StorageFit.noRoomForCopy(t, ref.scopeId, ref.nodeId, me.raddatz.veyq.mobile.AppKinds.exports(app.getString(R.string.pot_deleted_person)))
        }.getOrNull() ?: return null
        return app.getString(R.string.copy_no_room, android.text.format.Formatter.formatShortFileSize(app, bytes))
    }

    suspend fun copyFolder(ref: me.raddatz.veyq.mobile.navigation.NodeAt, to: String?): String? {
        val app = getApplication<Application>()
        val t = tenantSession ?: return app.getString(R.string.copy_failed)
        val deleted = app.getString(R.string.pot_deleted_person)
        val r = runCatching {
            me.raddatz.veyq.mobile.export.SubtreeCopy.duplicate(t, ref.scopeId, ref.nodeId, to, me.raddatz.veyq.mobile.AppKinds.copies(deleted) { java.util.UUID.randomUUID().toString() })
        }.getOrNull()
        refreshLists(t)
        return when (r?.outcome) {
            me.raddatz.veyq.mobile.export.SubtreeCopy.Outcome.COPIED -> null
            me.raddatz.veyq.mobile.export.SubtreeCopy.Outcome.NAME_TAKEN -> app.getString(R.string.copy_name_taken)
            me.raddatz.veyq.mobile.export.SubtreeCopy.Outcome.NOTHING_TO_COPY -> app.getString(R.string.copy_nothing)
            else -> app.getString(R.string.copy_failed_rolled_back)
        }
    }

    suspend fun openReceipt(receiptId: String): ByteArray? = potSession?.let { runCatching { it.openReceipt(receiptId) }.getOrNull() }

    /** The receipts the last save left out for want of room (the entry was saved). */
    fun skippedReceipts(): List<String> = potSession?.skippedReceipts.orEmpty()

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
            start || prior == null -> Shell.start(if (_incoming.value == null) recent.lastUsed(objects) else null, defaultSpace)
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
