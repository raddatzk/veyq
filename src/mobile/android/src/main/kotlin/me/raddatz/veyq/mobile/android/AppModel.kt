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
import me.raddatz.veyq.mobile.session.LivePoll
import me.raddatz.veyq.mobile.session.ThisDevice
import me.raddatz.veyq.mobile.session.TrustedDevice
import me.raddatz.veyq.mobile.session.VeyqAccount

/** One server in the switcher: its account and the tenants the switcher lists there. */
data class ServerEntry(val account: VeyqAccount, val label: String, val tenants: List<TenantRef>)

/** "Manage servers"' rows are core's (`SessionModel.managed`). */
typealias ManagedSpace = me.raddatz.veyq.mobile.session.SessionModel.ManagedSpace
typealias ManagedTenant = me.raddatz.veyq.mobile.session.SessionModel.ManagedTenant
typealias ManagedServer = me.raddatz.veyq.mobile.session.SessionModel.ManagedServer<VeyqAccount>

/** What the screen shows: the sign-in (first, or to add a server), or the shell over the current tenant. */
sealed interface Screen {
    /** The remembered accounts resuming at start. */
    data object Starting : Screen

    /** The full sign-in, with the Secret Key; [server] and [email] filled in where a trusted device's record knew them. */
    data class SignIn(
        val working: Boolean = false,
        val error: me.raddatz.veyq.mobile.words.Msg? = null,
        val adding: Boolean = false,
        val server: String? = null,
        val email: String? = null,
    ) : Screen

    /** An account this device trusts: the password alone (DEVICE), or the person's authentication (DEVICE_KEY). */
    data class Unlock(val record: TrustedDevice, val working: Boolean = false, val error: me.raddatz.veyq.mobile.words.Msg? = null) : Screen

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
    private var host: java.lang.ref.WeakReference<android.app.Activity>? = null
    private val device = ThisDevice(
        PreferencesStore(app),
        KeystoreDeviceKeys(app) { host?.get() },
        android.os.Build.MODEL,
        BouncyCastlePrimitives,
    )

    /** The way in (core's `SessionModel`): the start, the sign-in, the unlock, the switcher, the sign-out. */
    private val session = me.raddatz.veyq.mobile.session.SessionModel.accounts(
        BouncyCastlePrimitives, device, { block -> Network.client(block) }, hidden, recent,
    )

    /** The shell over the current tenant (core's `ShellModel`): its lists, where the person is, the actions on a node. */
    private var shellModel: me.raddatz.veyq.mobile.navigation.ShellModel? = null
    private var shellWatch: kotlinx.coroutines.Job? = null

    private val _screen = MutableStateFlow<Screen>(Screen.Starting)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    init {
        viewModelScope.launch { session.gate.collect(::gateChanged) }
    }

    private fun gateChanged(g: me.raddatz.veyq.mobile.session.SessionModel.Gate) {
        when (g) {
            is me.raddatz.veyq.mobile.session.SessionModel.Gate.SignIn -> _screen.value = Screen.SignIn(g.working, g.error, g.adding, g.server, g.email)
            is me.raddatz.veyq.mobile.session.SessionModel.Gate.Unlock -> _screen.value = Screen.Unlock(g.record, g.working, g.error)
            is me.raddatz.veyq.mobile.session.SessionModel.Gate.Signed -> shellModel?.state?.value?.let(::publish)
            else -> _screen.value = Screen.Starting
        }
    }

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
        viewModelScope.launch { session.start()?.let { show(it) } }
    }

    /** The debug build's unlock (`devlogin.py --unlock`): the password for the unlock screen, now or once it shows. */
    fun devUnlock(password: String) {
        viewModelScope.launch { session.devUnlock(password)?.let { show(it) } }
    }

    /**
     * The full sign-in, then this device trusted with the account: staying signed in when [remember] (which asks the
     * person to authenticate; declined, the password trust instead), else the password trust.
     */
    fun signIn(server: String, email: String, password: String, secretKey: String, remember: Boolean = false) {
        viewModelScope.launch { session.signIn(server, email, password, secretKey, remember)?.let { show(it) } }
    }

    /** The trusted account's unlock: its password (DEVICE), or the person's authentication again (DEVICE_KEY). */
    fun unlock(password: String) {
        viewModelScope.launch { session.unlock(password)?.let { show(it) } }
    }

    /** From the unlock to the full sign-in, for another account. */
    fun otherAccount() = session.otherAccount()

    /** The switcher: another tenant of the same server is the same session at another domain; another server is its own. */
    fun switchTo(account: VeyqAccount, tenant: TenantRef) {
        _screen.update { s -> if (s is Screen.Signed) s.copy(managing = null) else s }
        viewModelScope.launch { session.switchTo(account, tenant)?.let { show(it) } }
    }

    /** "Add server": the sign-in again, which can be left back to the shell. */
    fun addServer() = session.addServer()

    fun cancelAdd() = session.cancelAdd()

    // ---- Manage servers ----

    fun openManage() {
        viewModelScope.launch { session.loadSpaces { refreshManaged() } }
        _screen.update { s -> if (s is Screen.Signed) s.copy(managing = session.managed()) else s }
    }

    fun closeManage() = _screen.update { s -> if (s is Screen.Signed) s.copy(managing = null) else s }

    fun setTenantHidden(account: VeyqAccount, tenant: TenantRef, value: Boolean) {
        hidden.setTenantHidden(account.key, tenant.tenantId, value)
        refreshManaged()
        shellModel?.state?.value?.let(::publish)
    }

    fun setSpaceHidden(scopeId: String, value: Boolean) {
        hidden.setSpaceHidden(scopeId, value)
        refreshManaged()
        shellModel?.rebuild()
    }

    /**
     * Signs out of one server; if it was current, another one held becomes current, else the unlock of an account this
     * device still trusts with the password, or the sign-in. Staying signed in ends with the sign-out; the password trust
     * stays (as on the web).
     */
    fun signOut(account: VeyqAccount) {
        if (session.signedTo?.account == account) uploads.clear()
        viewModelScope.launch {
            val out = session.signOut(account)
            when {
                out.next != null -> show(out.next!!)
                out.wasCurrent -> {
                    shellWatch?.cancel()
                    shellModel = null
                }
                else -> refreshManaged()
            }
        }
    }

    /** Ends this device's trust in [account]: the next sign-in on this device asks for the Secret Key again. */
    fun forgetDevice(account: VeyqAccount) {
        viewModelScope.launch {
            session.forgetDevice(account)
            refreshManaged()
        }
    }

    private fun refreshManaged() = _screen.update { s -> if (s is Screen.Signed && s.managing != null) s.copy(managing = session.managed()) else s }

    // ---- the current tenant ----

    private suspend fun show(opened: me.raddatz.veyq.mobile.session.SessionModel.Opened<VeyqAccount, me.raddatz.veyq.mobile.session.TenantSession>) {
        val account = opened.account
        val tenant = opened.tenant
        val t = opened.session
        // a member's write pulls its path forward, and the scopes served as re-keying are finished in the background
        t.rekeyRules = me.raddatz.veyq.mobile.AppKinds.rules
        tenantSession?.let { was -> if (was.account !== account || was.tenant.tenantId != tenant.tenantId) uploads.clear() }
        tenantSession = t
        resumeRekeys(t)
        val pots = me.raddatz.veyq.mobile.expenses.PotModel(t, System::currentTimeMillis)
        potWatch?.cancel()
        potModel = pots
        potWatch = viewModelScope.launch { pots.state.collect { _pot.value = it } }
        creatingWatch?.cancel()
        creatingWatch = viewModelScope.launch { pots.creating.collect { _creating.value = it } }
        readReportOffered()
        val model = me.raddatz.veyq.mobile.navigation.ShellModel(t, hidden, recent, System::currentTimeMillis)
        shellWatch?.cancel()
        shellModel = model
        startPending = true
        model.load(openLastUsed = shareInbox.waiting.value == null)
        shellWatch = viewModelScope.launch { model.state.collect { it?.let(::publish) } }
        readRowDetails(t)
        syncAvatars(t)
    }

    // ---- what another app shared (ADR 0126) ----

    /** Files shared to veyq, from their arrival to where they went (core's `ShareInbox`). */
    val shareInbox = me.raddatz.veyq.mobile.expenses.ShareInbox()

    /** Files waiting for the Pot they go to (the pick mode); null when none waits. */
    val incoming: StateFlow<me.raddatz.veyq.mobile.expenses.ShareInbox.Incoming?> = shareInbox.waiting

    /**
     * Files arrive: the pick mode starts at the space's Home, never in the object last open, so the person chooses where
     * they go (ADR 0126 decision 1) — the start does the same while the session is still opening.
     */
    fun receiveShare(incoming: me.raddatz.veyq.mobile.expenses.ShareInbox.Incoming) {
        updateShell { Shell.start(null, it.space) }
        shareInbox.receive(incoming)
    }

    fun cancelShare() = shareInbox.cancel()

    /** The waiting files, taken by the Pot opened as their place: its new expense's receipts. */
    fun takeShare(): List<PotSession.PendingReceipt>? = shareInbox.take()

    /** The new expense's editor closed unsaved: the files wait again, for this Pot or another (from Home). */
    fun returnShare() {
        if (shareInbox.giveBack() != null) updateShell { Shell.start(null, it.space) }
    }

    /** The new expense the shared files went into was saved: the share is done. */
    fun shareSaved() = shareInbox.saved()

    // ---- core's actions on a node ----

    /** Whether [ref] is a guest's identity folder or lies in one (core's answer over the tree last read). */
    fun inIdentityFolder(ref: me.raddatz.veyq.mobile.navigation.NodeAt): Boolean = shellModel?.inIdentityFolder(ref) ?: false

    /** Whether [ref] is pinned; null while its space's pin set is not loaded (no heart is offered then). */
    fun isFavorite(ref: me.raddatz.veyq.mobile.navigation.NodeAt): Boolean? = shellModel?.isFavorite(ref)

    /** Pins or unpins [ref], then the lists are read anew; false when it was not stored (the heart goes back). */
    suspend fun toggleFavorite(ref: me.raddatz.veyq.mobile.navigation.NodeAt): Boolean {
        val m = shellModel ?: return false
        return m.toggleFavorite(ref).also { readRowDetails(m.tenant) }
    }

    /** The lists read anew (core's shell), with each row's facts. */
    private suspend fun refreshLists(t: me.raddatz.veyq.mobile.session.TenantSession) {
        readRowDetails(t)
        shellModel?.refresh()
    }

    /**
     * Moves [ref] with everything under it to the space's trash (core's shell: a page whose node went leaves for its
     * parent); an open Pot of it closes. What came of it, and the node as it was before (what an undo restores).
     */
    suspend fun trash(ref: me.raddatz.veyq.mobile.navigation.NodeAt): Pair<me.raddatz.veyq.mobile.session.NodeTrash.Deleted, me.raddatz.veyq.mobile.session.OpenNode?> {
        val m = shellModel ?: return me.raddatz.veyq.mobile.session.NodeTrash.Deleted.FAILED to null
        val r = m.trash(ref)
        if (r.gone) _pot.value?.ref?.takeIf { it.nodeId == ref.nodeId }?.let { potModel?.close(it) }
        readRowDetails(m.tenant)
        return r.outcome to r.before
    }

    /** Brings [node] back from the trash under its name (the undo of [trash]); false when it could not be. */
    suspend fun restore(node: me.raddatz.veyq.mobile.session.OpenNode): Boolean {
        val m = shellModel ?: return false
        return (m.undoTrash(node) == null).also { readRowDetails(m.tenant) }
    }

    /** Renames [ref] to [name] (core's shell: an open page follows it under its new name). What came of it. */
    suspend fun rename(ref: me.raddatz.veyq.mobile.navigation.NodeAt, name: String): me.raddatz.veyq.mobile.session.NodeRename.Result {
        val m = shellModel ?: return me.raddatz.veyq.mobile.session.NodeRename.Result(me.raddatz.veyq.mobile.session.NodeRename.Outcome.FAILED)
        return m.rename(ref, name).result.also { readRowDetails(m.tenant) }
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
        potModel?.rekeyed()
    }

    // ---- a move (core's NodeMove) ----

    /** Where a node of a kind may be placed: the build's kinds' placement (`AppKinds.placement`). */
    val placement = me.raddatz.veyq.mobile.AppKinds.placement

    /** Every node of [scopeId] this device opens: what the move browser walks. */
    suspend fun scopeNodes(scopeId: String): List<me.raddatz.veyq.mobile.navigation.NodeRef> = shellModel?.scopeNodes(scopeId).orEmpty()

    /** Moves [ref] under [to] (null: the space's root), its name kept (core's shell). */
    suspend fun move(ref: me.raddatz.veyq.mobile.navigation.NodeAt, to: String?): me.raddatz.veyq.mobile.session.NodeMove.Result {
        val m = shellModel ?: return me.raddatz.veyq.mobile.session.NodeMove.Result(me.raddatz.veyq.mobile.session.NodeMove.Outcome.FAILED)
        return m.move(ref, to).result.also { readRowDetails(m.tenant) }
    }

    /**
     * Copies the open Pot [ref] to [to] (null: the space's root) in its space (uc.platform.copy-node): a new Pot under fresh
     * keys with its people, live entries and receipts; the lists are read anew. A failed copy leaves nothing behind.
     */
    suspend fun copyPot(ref: ObjectRef, to: String?): me.raddatz.veyq.mobile.expenses.PotCopy.Copied {
        val failed = me.raddatz.veyq.mobile.expenses.PotCopy.Copied(me.raddatz.veyq.mobile.expenses.PotCopy.Outcome.FAILED)
        val pots = potModel ?: return failed
        val shell = shellModel ?: return failed
        val deleted = getApplication<Application>().getString(R.string.pot_deleted_person)
        return pots.duplicate(shell, ref, to, deleted) { java.util.UUID.randomUUID().toString() }.also { readRowDetails(shell.tenant) }
    }

    // ---- the space's trash (core's NodeTrash) ----

    /** The trash of [scopeId] (the root of each deleted subtree); null when it could not be read. */
    suspend fun trash(scopeId: String): List<me.raddatz.veyq.mobile.session.NodeTrash.Trashed>? = shellModel?.trashOf(scopeId)

    /** Whether the app can bring [row] back: its kind's fold of a name is known here. */
    fun canRestore(row: me.raddatz.veyq.mobile.session.NodeTrash.Trashed): Boolean = shellModel?.canRestore(row) ?: false

    /** Brings [row] back from the trash, under [newName] after its own was taken; the lists are read anew. */
    suspend fun restoreFromTrash(row: me.raddatz.veyq.mobile.session.NodeTrash.Trashed, newName: String? = null): me.raddatz.veyq.mobile.session.NodeTrash.Restored {
        val m = shellModel ?: return me.raddatz.veyq.mobile.session.NodeTrash.Restored.REFUSED
        return m.restoreFromTrash(row, newName).also { readRowDetails(m.tenant) }
    }

    /** Erases [row] and everything under it now: irreversible. */
    suspend fun purge(row: me.raddatz.veyq.mobile.session.NodeTrash.Trashed): me.raddatz.veyq.mobile.session.NodeTrash.Purged =
        shellModel?.purge(row) ?: me.raddatz.veyq.mobile.session.NodeTrash.Purged.FAILED

    // ---- an open Pot (Expenses) ----

    /** The tenant the shell shows, opened: where an object's own view reads from. */
    private var tenantSession: me.raddatz.veyq.mobile.session.TenantSession? = null
    /** The Pot open in the app (core's `PotModel`): its views, its live poll, its writes. */
    private var potModel: me.raddatz.veyq.mobile.expenses.PotModel? = null
    private var potWatch: kotlinx.coroutines.Job? = null
    private var creatingWatch: kotlinx.coroutines.Job? = null
    private val potSession: PotSession? get() = potModel?.session

    private val _pot = MutableStateFlow<me.raddatz.veyq.mobile.expenses.PotModel.State?>(null)

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
        val boxes = me.raddatz.veyq.mobile.depot.DropBoxes.people(runCatching { t.nodes() }.getOrDefault(emptyList())).mapValues { listOf(app.text(it.value)) }
        _rowDetails.value = pots + files.mapValues { (_, f) -> me.raddatz.veyq.mobile.depot.DepotFiles.rowDetails(f).map { app.piece(it) } } + boxes
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
            val offer = me.raddatz.veyq.mobile.depot.DepotFiles.offerFor(item) { _files.value[it]?.blobId } ?: return@launch
            uploads.remove(item.id)
            notify(Notice(app.text(offer.notice), app.words(offer.action)) { replace(offer.nodeId, offer.source, offer.expectedBlobId) })
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
            val said = me.raddatz.veyq.mobile.words.Notices.folderUpload(r)
            if (said.isNotEmpty()) notify(Notice(said.joinToString("\n") { app.text(it) }))
            if (r.outcome == me.raddatz.veyq.mobile.depot.FolderUpload.Outcome.READY) uploads.enqueue(r.jobs, r.name, r.rootId, ::depotSettled, null)
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

    /** What [file]'s page shows (node [nodeName]), read as far as it needs (core's `FileView.open`); null when it did not read. */
    suspend fun openFileView(file: me.raddatz.veyq.mobile.depot.DepotFiles.File, nodeName: String): me.raddatz.veyq.mobile.depot.FileView.Opened? {
        val t = tenantSession ?: return null
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { me.raddatz.veyq.mobile.depot.FileView.open(t, file, nodeName) }.getOrNull()
        }
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
    fun uploadProblem(item: me.raddatz.veyq.mobile.blob.UploadQueue.Item): String? =
        me.raddatz.veyq.mobile.words.Notices.uploadProblem(item)?.let { getApplication<Application>().text(it) }

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
        val app = getApplication<Application>()
        val m = shellModel ?: return app.text(me.raddatz.veyq.mobile.words.Notices.folderRefused(null))
        return m.createFolder(scopeId, parentId, name)?.let { app.text(it) }.also { readRowDetails(m.tenant) }
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
    val pot: StateFlow<me.raddatz.veyq.mobile.expenses.PotModel.State?> = _pot.asStateFlow()

    /** Opens and reads the Pot [ref] unless it is the one shown. */
    fun loadPot(ref: ObjectRef) {
        val pots = potModel ?: return
        viewModelScope.launch { pots.open(ref) }
    }

    /** The open Pot's live poll, for as long as the caller runs it (the Pot's screen while it is started). */
    suspend fun livePot() {
        potModel?.live()
    }

    /** Reads the open Pot again now (pull to refresh); [done] when it settled, moved or not. */
    fun refreshPot(done: () -> Unit) {
        val pots = potModel ?: return done()
        viewModelScope.launch {
            pots.refresh()
            done()
        }
    }

    /**
     * One write to the open Pot ([op] on its session): saving, then the Pot as read after it and a refusal as its message;
     * [done] runs once it was stored.
     */
    fun potWrite(op: suspend (PotSession) -> PotSession.WriteOutcome, done: () -> Unit = {}) {
        val pots = potModel ?: return
        val ref = _pot.value?.ref ?: return
        viewModelScope.launch {
            val stored = pots.write(op)
            // A settings write may have set the base currency: the Pot's row says it at once.
            pots.rowDetails()?.let { facts -> _rowDetails.update { it + (ref.nodeId to facts) } }
            if (stored) done()
        }
    }

    private val _creating = MutableStateFlow(me.raddatz.veyq.mobile.expenses.PotModel.Creating())
    val creating: StateFlow<me.raddatz.veyq.mobile.expenses.PotModel.Creating> = _creating.asStateFlow()

    /** The current members of [scopeId], for the "New pot" sheet's people; null when they do not read. */
    suspend fun membersOf(scopeId: String): List<me.raddatz.veyq.mobile.session.Person>? =
        tenantSession?.let { runCatching { it.scopeMembers(scopeId) }.getOrNull() }

    /**
     * Creates a Pot named [name] at the root of [scopeId] with [currency] ([minorUnits] its digits) and [people], reads the
     * tenant's lists again and opens it; [done] runs once it exists (its people may still have failed: then the Pot opens
     * and says so on its Settings, where they can be added).
     */
    fun createPot(scopeId: String, name: String, currency: String, minorUnits: Int, people: List<PotCreate.NewPerson>, parentId: String? = null, done: () -> Unit) {
        val pots = potModel ?: return
        val shell = shellModel ?: return
        viewModelScope.launch {
            if (pots.create(shell, scopeId, parentId, name, Ledger.BaseCurrency(currency, minorUnits), people)) {
                readRowDetails(shell.tenant)
                done()
            }
        }
    }

    fun clearCreating() {
        potModel?.clearCreating()
    }

    /** One unit of [currency] in the open Pot's base from today's table, or null: the rate is typed then. */
    suspend fun rateFor(currency: String): me.raddatz.veyq.mobile.expenses.ExchangeRates.Filled? =
        potModel?.rateFor(currency, java.time.LocalDate.now().toString())

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
        val m = shellModel ?: return app.getString(R.string.copy_failed)
        return m.duplicate(ref, to, app.getString(R.string.pot_deleted_person)) { java.util.UUID.randomUUID().toString() }
            ?.let { app.text(it) }.also { readRowDetails(m.tenant) }
    }

    suspend fun openReceipt(receiptId: String): ByteArray? = potModel?.openReceipt(receiptId)

    /** The receipts the last save left out for want of room (the entry was saved). */
    fun skippedReceipts(): List<String> = potModel?.skippedReceipts.orEmpty()

    /** Forgets the open Pot's last refusal (a dialog closed). */
    fun clearPotMessage() {
        potModel?.clearMessage()
    }

    /** The shell's lists from what was read, less what this device hides; on [start], the shell where the app opens. */
    /** Whether the next publish is the shell's first over a tenant ("Manage servers" closes then). */
    private var startPending = false

    /** The screen over [state], the core shell's, with this device's servers and the tenant shown. */
    private fun publish(state: me.raddatz.veyq.mobile.navigation.ShellModel.State) {
        val gate = session.current as? me.raddatz.veyq.mobile.session.SessionModel.Gate.Signed ?: return
        val signed = session.signedTo ?: return
        if (shellModel?.tenant !== signed.session) return // a switch under way: the shell shown stays until the other one read
        val account = signed.account
        val tenant = signed.tenant
        val prior = _screen.value as? Screen.Signed
        val start = startPending
        startPending = false
        val l = state.lists
        _screen.value = Screen.Signed(
            shell = state.shell,
            tenant = tenant,
            server = account.label,
            servers = session.accounts.map { c -> ServerEntry(c, c.label, hidden.visibleTenants(c.key, c.tenants, if (c == account) tenant else null)) },
            applications = l.applications,
            filters = l.filters,
            drawer = l.drawer,
            spaces = l.spaces,
            nodes = l.nodes,
            objects = l.objects,
            favorites = l.favorites,
            switching = gate.switching,
            managing = if (start) null else prior?.managing,
        )
    }

    fun choose(app: ApplicationId?) = updateShell { it.choose(app) }
    fun switchSpace(scopeId: String) = updateShell { it.switchSpace(scopeId) }
    fun enter(folder: FolderRef) = updateShell { it.enter(folder) }
    fun filter(app: ApplicationId?) = updateShell { it.filter(app) }
    fun goBack() = updateShell { it.goBack() }

    fun open(ref: ObjectRef) {
        shellModel?.open(ref)
    }

    fun select(tab: BarTab) = updateShell { it.select(tab) }
    fun search(on: Boolean) = updateShell { it.search(on) }

    private fun updateShell(f: (Shell) -> Shell) {
        shellModel?.update(f)
    }

    private fun persistRecent(keys: List<UseKey>) {
        prefs.edit().putString("uses", keys.joinToString("\n") { "${it.scopeId}\t${it.nodeId}\t${it.lastUsedEpochMs}" }).apply()
    }

    private fun loadRecent(): List<UseKey> = prefs.getString("uses", "").orEmpty().lines().mapNotNull { line ->
        val p = line.split('\t')
        if (p.size == 3) p[2].toLongOrNull()?.let { UseKey(p[0], p[1], it) } else null
    }
}
