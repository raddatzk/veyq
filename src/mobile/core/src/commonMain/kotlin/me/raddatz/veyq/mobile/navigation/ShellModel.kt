package me.raddatz.veyq.mobile.navigation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import me.raddatz.veyq.mobile.AppKinds
import me.raddatz.veyq.mobile.export.NodeExport
import me.raddatz.veyq.mobile.export.SubtreeCopy
import me.raddatz.veyq.mobile.session.FolderCreate
import me.raddatz.veyq.mobile.session.NodeCover
import me.raddatz.veyq.mobile.session.NodeCreate
import me.raddatz.veyq.mobile.session.NodeMetaWrite
import me.raddatz.veyq.mobile.session.NodeMove
import me.raddatz.veyq.mobile.session.NodeRename
import me.raddatz.veyq.mobile.session.NodeReport
import me.raddatz.veyq.mobile.session.NodeTrash
import me.raddatz.veyq.mobile.session.OpenNode
import me.raddatz.veyq.mobile.session.Reach
import me.raddatz.veyq.mobile.session.ShareLinkChanges
import me.raddatz.veyq.mobile.session.ShareLinks
import me.raddatz.veyq.mobile.session.StorageFit
import me.raddatz.veyq.mobile.session.VeyqAccount
import me.raddatz.veyq.mobile.session.TenantSession
import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.Notice
import me.raddatz.veyq.mobile.words.Notices

/**
 * The shell over one tenant (ADR 0122, ADR 0136 decision 2): what it lists — its spaces, nodes, objects and favorites as
 * this device shows them ([Hidden]), the drawer's short lists ([RecentUses]), the applications and Home's filters — and
 * where the person is ([Shell]), with every action on a node that changes what is listed: a favorite, the trash and its
 * undo, a rename, a move, a new folder, a copy. Each reads the lists anew and keeps the shell on what is still there: a
 * page whose node went leaves for its parent, a folder on Home's path that went or moved shows where it is now.
 *
 * A UI observes [state] (or [watch]) and calls the actions; what it draws and the device's words stay its own.
 */
class ShellModel(
    val tenant: TenantSession,
    private val hidden: Hidden,
    private val recent: RecentUses,
    private val now: () -> Long,
) {
    /** What the shell lists, this device's hidden spaces taken out. */
    @Serializable
    class Lists(
        val spaces: List<SpaceRef>,
        val nodes: List<NodeRef>,
        val objects: List<ObjectRef>,
        val favorites: List<ObjectRef>,
        val applications: List<ApplicationId>,
        val filters: List<ApplicationId?>,
        val drawer: ArrangedList,
        /** What this device opened, of [objects] (Recent): an application's own list arranges by it too. */
        val uses: List<Use> = emptyList(),
    ) {
        /**
         * The chosen application's own list, by the one rule (ADR 0122 decision 5): its Favorites, its Recent, the rest —
         * each by last use. Its Recent was once left empty here, so on every app the list showed no Recent at all.
         */
        fun applicationList(app: ApplicationId): ArrangedList =
            arrange(favorites.filter { it.application == app }, uses.filter { it.ref.application == app }, objects.filter { it.application == app })

        /** Home's level: the shown folder's children in the shown space, under the shown filter. */
        fun level(shell: Shell): List<NodeRef> = shell.space?.let { Home.level(nodes, it, shell.folder?.nodeId, shell.filter) }.orEmpty()

        /** Home's level in its groups, folders apart ([Home.groups]). */
        fun groups(shell: Shell): List<Home.Group> = Home.groups(level(shell))

        fun space(shell: Shell): SpaceRef? = spaces.firstOrNull { it.scopeId == shell.space }
    }

    /**
     * Serializable, since the native bridge sends it as it is (ADR 0123's Consequences, the bridge). [offline]: the lists
     * are the offline store's, read-only, as of when (ADR 0140 decision 5, the shell's banner: [banner]); null online.
     * [gone]: each item made available offline that was removed from this device since it is no longer available (ADR
     * 0140 decision 6), named where the shell held its name ([me.raddatz.veyq.mobile.offline.OfflineShown.gone]) — shown
     * until [goneSeen].
     */
    @Serializable
    class State(
        val shell: Shell,
        val lists: Lists,
        val offline: me.raddatz.veyq.mobile.session.OfflineAsOf? = null,
        val gone: List<Notice> = emptyList(),
    ) {
        /** The banner offline: "Offline — content as of {time}"; null online. */
        val banner: Notice? get() = me.raddatz.veyq.mobile.offline.OfflineShown.banner(offline)

        internal fun moved(shell: Shell, lists: Lists = this.lists) = State(shell, lists, offline, gone)
    }

    /** The tenant as read, before what this device hides is taken out. */
    private class Raw(val spaces: List<SpaceRef>, val nodes: List<NodeRef>, val favorites: List<ObjectRef>, val off: Set<String>)

    private var raw: Raw? = null
    private val reading = Mutex()

    private val _state = MutableStateFlow<State?>(null)
    val state: StateFlow<State?> = _state.asStateFlow()

    /** A watch of [state] for a platform without flows: stopped with [stop]. */
    class Watch internal constructor(private val job: kotlinx.coroutines.Job) {
        fun stop() = job.cancel()
    }

    /** Calls [listener] with the state now and with each change after (on a background thread). */
    fun watch(listener: (State) -> Unit): Watch =
        Watch(CoroutineScope(Dispatchers.Default).launch { state.collect { it?.let(listener) } })

    val shell: Shell? get() = _state.value?.shell

    /** The state now (what [watch] delivers next), for a platform that reads it at once after an action. */
    val current: State? get() = _state.value

    // ---- reading ----

    /**
     * Reads the tenant and opens the shell as the app starts on it: on the object last used ([openLastUsed]; not while
     * something shared waits for its place), else Home's default space.
     */
    suspend fun load(openLastUsed: Boolean) {
        reading.withLock { raw = read() }
        rebuild(start = true, openLastUsed = openLastUsed)
        // a re-key the tenant ran (its background resume, a link closed) changed keys the lists were read under
        if (rekeys == null) rekeys = CoroutineScope(Dispatchers.Default).launch { tenant.rekeyed.drop(1).collect { refresh() } }
        // offline the lists are the store's and say so; back online they are read anew (ADR 0140 decision 5)
        if (reaches == null) reaches = CoroutineScope(Dispatchers.Default).launch {
            tenant.account.reach.drop(1).collect { if (it == Reach.Online) refresh() else rebuild() }
        }
        watchGone()
    }

    private var reaches: kotlinx.coroutines.Job? = null
    private var goneWatch: kotlinx.coroutines.Job? = null

    /**
     * Every node name this shell read in this run, by node id: what names an item made available offline once its node
     * is gone from the lists (ADR 0140 decision 6) — the store keeps no name, so a node this shell never held goes nameless.
     */
    private val heldNames = HashMap<String, String>()

    /** The pins dropped since their node is no longer available, as the shell says them ([State.gone]). */
    private fun watchGone() {
        val pins = tenant.account.offlinePins ?: return
        if (goneWatch != null) return
        goneWatch = CoroutineScope(Dispatchers.Default).launch {
            pins.gone.collect { gone ->
                val said = gone.filter { it.tenant == tenant.tenant.tenantId }.map { me.raddatz.veyq.mobile.offline.OfflineShown.gone(heldNames[it.node]) }
                _state.update { s -> s?.let { State(it.shell, it.lists, it.offline, said) } }
            }
        }
    }

    /** The [State.gone] notices said: they are not said again (on any screen of the account). */
    suspend fun goneSeen() {
        tenant.account.offlinePins?.offline?.seenGone()
        _state.update { s -> s?.let { State(it.shell, it.lists, it.offline) } }
    }

    /** Whether the account reaches its server: offline every write here is refused ([readOnly], ADR 0140 decision 7). */
    val online: Boolean get() = tenant.account.reach.value == Reach.Online

    /** The notice a write offline is refused with. */
    private val readOnly get() = Notice(Msg.OFFLINE_READ_ONLY)

    /** What the shell shows offline: as of when its lists were last confirmed by the server; null online. */
    private fun offlineNow(): me.raddatz.veyq.mobile.session.OfflineAsOf? = when (val r = tenant.account.reach.value) {
        Reach.Online -> null
        is Reach.Offline -> me.raddatz.veyq.mobile.session.OfflineAsOf(tenant.offline?.asOf ?: r.asOf)
        is Reach.Ended -> me.raddatz.veyq.mobile.session.OfflineAsOf(tenant.offline?.asOf)
    }

    private var rekeys: kotlinx.coroutines.Job? = null

    /**
     * Reads the lists anew from the server (after a write, or what another device or a guest wrote); a read that fails
     * keeps what was listed. The session's tree is forgotten first: a session caches it ([TenantSession.trees]), and a
     * refresh that read the cache showed a guest's new folder only after a sign-in (found 2026-11-12; ADR 0136's Consequences).
     */
    suspend fun refresh(people: Boolean = false) {
        reading.withLock {
            tenant.forgetTree()
            runCatching { read() }.getOrNull()?.let { raw = it }
        }
        rebuild(start = false, openLastUsed = false)
        // a person's refresh (the desktops' Refresh, the phones' pull): the members too, and a picture replaced since
        if (people) runCatching { tenant.readPeople() }.getOrNull()?.let { tenant.pictures.peopleRead(it.values) }
    }

    private suspend fun read() = Raw(tenant.spaces(), tenant.nodes(), tenant.favorites(), tenant.applicationsOff())
        .also { r -> r.nodes.forEach { heldNames[it.nodeId] = it.name } }

    /** The lists anew from what was read (this device's hidden spaces changed). */
    fun rebuild() = rebuild(start = false, openLastUsed = false)

    private fun rebuild(start: Boolean, openLastUsed: Boolean) {
        val r = raw ?: return
        val spaces = hidden.visibleSpaces(r.spaces)
        val nodes = hidden.visibleNodes(r.nodes, r.spaces)
        val objects = nodes.mapNotNull { it.asObject() }
        val favorites = hidden.visibleObjects(r.favorites, r.spaces)
        val lists = Lists(
            spaces, nodes, objects, favorites,
            applications = drawerApplications(r.off).map { it.id },
            filters = Home.filters(r.off),
            drawer = drawer(objects, favorites),
            uses = recent.resolve(objects),
        )
        _state.update { prior ->
            State(next(prior?.shell.takeUnless { start }, spaces, if (openLastUsed) recent.lastUsed(objects) else null), lists, offlineNow(), prior?.gone.orEmpty())
        }
    }

    private fun drawer(objects: List<ObjectRef>, favorites: List<ObjectRef>) =
        arrange(favorites, recent.resolve(objects), favoritesLimit = DRAWER_FAVORITES, recentLimit = DRAWER_RECENT)

    // ---- where the person is ----

    /** Any move of the shell (its own transitions are `Shell`'s). */
    fun update(change: (Shell) -> Shell) {
        _state.update { s -> s?.moved(change(s.shell)) }
    }

    fun choose(app: ApplicationId?) = update { it.choose(app) }
    fun switchSpace(scopeId: String) = update { it.switchSpace(scopeId) }
    fun enter(folder: FolderRef) = update { it.enter(folder) }
    fun filter(app: ApplicationId?) = update { it.filter(app) }
    fun goBack() = update { it.goBack() }
    fun select(tab: BarTab) = update { it.select(tab) }
    fun search(on: Boolean) = update { it.search(on) }

    /** Opens [ref], its use recorded (the drawer's Recent). */
    fun open(ref: ObjectRef) = opened(ref) { it.open(ref) }

    /** [ref] chosen from the sidebar a wide window keeps open ([Shell.openAtTop]); recent as any open. */
    fun openAtTop(ref: ObjectRef) = opened(ref) { it.openAtTop(ref) }

    /** [ref] chosen from the list beside the open object ([Shell.openBeside]); recent as any open. */
    fun openBeside(ref: ObjectRef) = opened(ref) { it.openBeside(ref) }

    /**
     * Person [userId]'s profile opened over what is on screen ([Shell.openPerson], ADR 0144 decision 11): from a current
     * member in a Memory's participants (`Participants.Entry.opensProfile`) or a photo's uploader
     * (`MediaDetails.Uploader.profile`). The shell is a member's; a share's view offers no way in.
     */
    fun openPerson(userId: String) = update { it.openPerson(userId) }

    /** Person [userId]'s frame: named by the tenant's directory as read, with the sections of the applications on. */
    fun profile(userId: String): PersonProfile = PersonProfile.of(userId, tenant.people, tenant.applicationsOff())

    /**
     * Memories' section of person [userId]'s profile (ADR 0144 decisions 12–13): over the tenant's participants index, a
     * row or a pin opening its Memory with its use recorded ([open]).
     */
    fun personMemories(userId: String): me.raddatz.veyq.mobile.memories.people.PersonMemories =
        me.raddatz.veyq.mobile.memories.people.PersonMemories(userId, object : me.raddatz.veyq.mobile.memories.people.PersonMemories.Door {
            override val chosen get() = me.raddatz.veyq.mobile.memories.people.PersonMemories.View.valueOf(viewModes.of(tenant.account.userId, ViewMode.PERSON).name)
            override fun chose(view: me.raddatz.veyq.mobile.memories.people.PersonMemories.View) = viewModes.set(tenant.account.userId, ViewMode.valueOf(view.name))
            override suspend fun index() = me.raddatz.veyq.mobile.memories.people.ParticipantIndex.of(tenant)
            override fun open(ref: ObjectRef) = this@ShellModel.open(ref)
        })

    private fun opened(ref: ObjectRef, move: (Shell) -> Shell) {
        recent.record(ref, now())
        _state.update { s -> s?.let { it.moved(move(it.shell), Lists(it.lists.spaces, it.lists.nodes, it.lists.objects, it.lists.favorites, it.lists.applications, it.lists.filters, drawer(it.lists.objects, it.lists.favorites), recent.resolve(it.lists.objects))) } }
    }

    /** Every node of [scopeId] this device opens: what the move browser walks. */
    suspend fun scopeNodes(scopeId: String): List<NodeRef> = runCatching { tenant.nodes() }.getOrDefault(emptyList()).filter { it.scopeId == scopeId }

    /** Home's path read anew where [ref] is a folder on it (renamed, moved, trashed): the folder shown is where it is now. */
    private suspend fun relocate(ref: NodeAt) {
        val s = shell ?: return
        if (s.path.none { it.nodeId == ref.nodeId }) return
        val tree = scopeNodes(ref.scopeId)
        update { relocated(it, tree, ref.nodeId) }
    }

    // ---- actions on a node ----

    /** Whether [ref] is pinned; null while its space's pin set is not loaded (no heart is offered then). */
    fun isFavorite(ref: NodeAt): Boolean? = tenant.isFavorite(ref.scopeId, ref.nodeId)

    /** Whether [ref] is a guest's identity folder or lies in one. */
    fun inIdentityFolder(ref: NodeAt): Boolean = tenant.inIdentityFolder(ref.scopeId, ref.nodeId)

    /**
     * Whether [shell] shows a space's top level on Home, where its share offers "Add files only" ([topLevelShareSheet], the
     * web's level share): no folder, object, application, person's page or Search open, and the tenant's links and drop
     * boxes on. Every platform asks this (the phones through their `AppModel`, the desktops through `ShellShown.topLevelShare`).
     */
    fun offersTopLevelShare(shell: Shell): Boolean {
        val space = shell.space ?: return false
        val rules = tenant.linkRules
        return topLevelShared(shell, space) && rules.on && rules.dropBox
    }

    /** Whether "New" is offered where the shell is: in a space, never in a guest's folder (the web's `folderRights`). */
    val canCreateHere: Boolean
        get() {
            val s = shell ?: return false
            val space = s.space ?: return false
            if (!online) return false
            return s.folderAt(space)?.let { !inIdentityFolder(it) } ?: true
        }

    /** Pins or unpins [ref], then the lists are read anew; false when it was not stored (the heart goes back). */
    suspend fun toggleFavorite(ref: NodeAt): Boolean {
        if (!online) return false
        val now = tenant.isFavorite(ref.scopeId, ref.nodeId) ?: return false
        val stored = runCatching { tenant.setFavorite(ref.scopeId, ref.nodeId, !now) }.getOrDefault(false)
        refresh()
        return stored
    }

    /** What a trash came to: the outcome, its notice (moved, with its undo; or why not) and the node as it was. */
    class Trashed(val outcome: NodeTrash.Deleted, val notice: Notice, val before: OpenNode?) {
        /** Whether the node is gone from where it was (an open page of it left, an open Pot of it closed). */
        val gone: Boolean get() = outcome == NodeTrash.Deleted.TRASHED || outcome == NodeTrash.Deleted.GONE
        /** Whether its undo is offered. */
        val undoable: Boolean get() = outcome == NodeTrash.Deleted.TRASHED && before != null
    }

    /** Moves [ref] with everything under it to its space's trash; a page of it leaves for its parent. */
    suspend fun trash(ref: NodeAt): Trashed {
        if (!online) return Trashed(NodeTrash.Deleted.FAILED, readOnly, null)
        val before = runCatching { tenant.node(ref.scopeId, ref.nodeId) }.getOrNull()
        val outcome = runCatching { NodeTrash.delete(tenant, ref.scopeId, ref.nodeId) }.getOrDefault(NodeTrash.Deleted.FAILED)
        val r = Trashed(outcome, Notices.trashed(outcome, ref.name), before)
        if (r.gone) update { afterTrash(it, ref.nodeId) }
        refresh()
        if (r.gone) relocate(ref)
        return r
    }

    /** The undo of a trash: [node] back under its name, as it was; the notice of its failure, or null. */
    suspend fun undoTrash(node: OpenNode): Notice? {
        if (!online) return readOnly
        val token = AppKinds.tokenInput(node.kind, node.name) ?: return Notices.trashUndoFailed(node.name)
        val restored = runCatching { NodeTrash.restore(tenant, node, token) }.getOrNull()
        refresh()
        return if (restored == NodeTrash.Restored.RESTORED) null else Notices.trashUndoFailed(node.name)
    }

    /** The trash of [scopeId] (each deleted subtree's root); null when it could not be read. */
    suspend fun trashOf(scopeId: String): List<NodeTrash.Trashed>? = runCatching { NodeTrash.list(tenant, scopeId) }.getOrNull()

    /** Whether the app can bring [row] back: its kind's fold of a name is known here. */
    fun canRestore(row: NodeTrash.Trashed): Boolean = AppKinds.tokenInput(row.kind, row.name) != null

    /** Brings [row] back from the trash, under [newName] after its own was taken; the lists are read anew. */
    suspend fun restoreFromTrash(row: NodeTrash.Trashed, newName: String?): NodeTrash.Restored {
        if (!online) return NodeTrash.Restored.REFUSED
        val token = AppKinds.tokenInput(row.kind, newName?.trim() ?: row.name) ?: return NodeTrash.Restored.REFUSED
        val r = runCatching { NodeTrash.restoreFromTrash(tenant, row, token, newName) }.getOrDefault(NodeTrash.Restored.REFUSED)
        refresh()
        return r
    }

    /** Erases [row] and everything under it now: irreversible. */
    suspend fun purge(row: NodeTrash.Trashed): NodeTrash.Purged =
        if (!online) NodeTrash.Purged.FAILED else runCatching { NodeTrash.purge(tenant, row.scopeId, row.id) }.getOrDefault(NodeTrash.Purged.FAILED)

    /**
     * The header of [node] over this shell ([NodeHeaderModel]), its kind's page knowing [facts] (null: not yet); [closeOpen]
     * closes an open object of a node gone to the trash, [changed] runs after each write that went through.
     */
    fun header(node: NodeAt, facts: me.raddatz.veyq.mobile.AppKinds.Facts?, closeOpen: (NodeAt) -> Unit, changed: () -> Unit): NodeHeaderModel =
        NodeHeaderModel(node, facts, object : NodeHeaderModel.Door {
            override fun isFavorite(ref: NodeAt) = this@ShellModel.isFavorite(ref)
            override fun inIdentityFolder(ref: NodeAt) = this@ShellModel.inIdentityFolder(ref)
            override fun linksOn() = tenant.linkRules.on
            override suspend fun reportOffered() = this@ShellModel.reportOffered()
            override suspend fun toggleFavorite(ref: NodeAt) = this@ShellModel.toggleFavorite(ref)
            override suspend fun trash(ref: NodeAt) = this@ShellModel.trash(ref)
            override suspend fun undoTrash(node: OpenNode) = this@ShellModel.undoTrash(node)
            override suspend fun rename(ref: NodeAt, name: String) = this@ShellModel.rename(ref, name)
            override fun closeOpen(ref: NodeAt) = closeOpen(ref)
            override fun changed() = changed()
            override suspend fun downloadName(ref: NodeAt) = AppKinds.downloadName(tenant, ref)
            override suspend fun cover(ref: NodeAt): NodeCover.Crop? {
                val n = tenant.node(ref.scopeId, ref.nodeId) ?: return null
                return NodeCover.read(NodeMetaWrite.openForWrite(tenant.account.suite, n.scopeId, n.id, n.key, n.wrappedMeta))?.crop
            }
            override suspend fun coverPicture(ref: NodeAt) = NodeCover.openNode(tenant, ref.nodeId)
            override suspend fun setCover(ref: NodeAt, jpeg: ByteArray, crop: NodeCover.Crop) = NodeCover.set(tenant, ref.scopeId, ref.nodeId, jpeg, crop)
            override suspend fun recropCover(ref: NodeAt, crop: NodeCover.Crop) = NodeCover.recrop(tenant, ref.scopeId, ref.nodeId, crop)
            override suspend fun removeCover(ref: NodeAt) = NodeCover.remove(tenant, ref.scopeId, ref.nodeId)
            override fun offline() = tenant.account.offlinePins?.let { me.raddatz.veyq.mobile.offline.OfflineNodes.of(it, tenant) }
            override val online = tenant.account.reach.map { it == Reach.Online }.distinctUntilChanged()
        })

    private var reportKnown: Boolean? = null
    private val reportReading = Mutex()

    /**
     * Whether the tenant's server takes reports (it publishes a moderation key, ADR 0093): asked once per tenant, Report
     * offered only then. A read that failed is asked again by the next header (the duty is the law's).
     */
    suspend fun reportOffered(): Boolean = reportReading.withLock {
        reportKnown ?: runCatching { NodeReport.published(tenant) != null }.getOrNull()?.also { reportKnown = it } ?: false
    }

    /** The trash sheet of [scopeId] over this shell; [changed] after each write that went through. */
    fun trashSheet(scopeId: String, changed: () -> Unit): TrashSheet = TrashSheet(object : TrashSheet.Door {
        override suspend fun list() = trashOf(scopeId)
        override fun canRestore(row: NodeTrash.Trashed) = this@ShellModel.canRestore(row)
        override suspend fun restore(row: NodeTrash.Trashed, newName: String?) = restoreFromTrash(row, newName)
        override suspend fun purge(row: NodeTrash.Trashed) = this@ShellModel.purge(row)
    }, changed)

    /** What a rename came to, and its notice where it did not land. */
    class Renamed(val result: NodeRename.Result, val notice: Notice?) {
        /** Renamed, but a link to it still shows its old name to its guests. */
        val linksFailed: Boolean get() = result.outcome == NodeRename.Outcome.RENAMED && !(result.links?.failed.isNullOrEmpty())
    }

    /** Renames [ref] to [name] (its placement, then its open links' names); an open page follows it under its new name. */
    suspend fun rename(ref: NodeAt, name: String): Renamed {
        if (!online) return Renamed(NodeRename.Result(NodeRename.Outcome.FAILED), readOnly)
        val r = runCatching { NodeRename.rename(tenant, ref.scopeId, ref.nodeId, name, AppKinds::tokenInput, now()) }
            .getOrDefault(NodeRename.Result(NodeRename.Outcome.FAILED))
        if (r.outcome == NodeRename.Outcome.RENAMED) {
            refresh()
            val renamed = _state.value?.lists?.objects?.firstOrNull { it.scopeId == ref.scopeId && it.nodeId == ref.nodeId }
            if (renamed != null) update { afterRename(it, renamed) }
            relocate(ref)
        }
        return Renamed(r, Notices.renameRefused(r))
    }

    /** Where a node of a kind may be placed: the build's kinds' placement. */
    val placement get() = AppKinds.placement

    /** What a move came to, its notice where it did not go, and where it came from (its undo moves it back). */
    class Moved(val result: NodeMove.Result, val notice: Notice?, val from: String?)

    /** Moves [ref] under [to] (null: the space's root), its name kept; the lists are read anew. */
    suspend fun move(ref: NodeAt, to: String?): Moved {
        if (!online) return Moved(NodeMove.Result(NodeMove.Outcome.FAILED), readOnly, null)
        val from = runCatching { tenant.node(ref.scopeId, ref.nodeId)?.parentId }.getOrNull()
        val r = runCatching { NodeMove.move(tenant, ref.scopeId, ref.nodeId, to, AppKinds::tokenInput, AppKinds.placement) }
            .getOrDefault(NodeMove.Result(NodeMove.Outcome.FAILED))
        refresh()
        if (r.outcome == NodeMove.Outcome.MOVED) relocate(ref)
        return Moved(r, Notices.moveRefused(r.outcome), from)
    }

    /** A new folder [name] in [scopeId] under [parentId] (null: the space's root); why not, or null once made. */
    suspend fun createFolder(scopeId: String, parentId: String?, name: String): Notice? {
        if (!online) return readOnly
        val n = name.trim()
        if (n.isEmpty()) return Notice(Msg.FOLDER_NAME_EMPTY)
        val r = runCatching { FolderCreate.create(tenant, scopeId, parentId, n) }.getOrNull()
        refresh()
        return when (r) {
            is NodeCreate.Result.Done -> null
            is NodeCreate.Result.Refused -> Notices.folderRefused(r.reason)
            null -> Notices.folderRefused(null)
        }
    }

    /** A new folder [name] where the shell is: in its space, under the folder Home shows; why not, or null once made. */
    suspend fun createFolderHere(name: String): Notice? {
        val s = shell
        val space = s?.space ?: return Notices.folderRefused(null)
        return createFolder(space, s.folder?.nodeId, name)
    }

    /**
     * Copies [ref] with everything under it (named `duplicate`: a name starting `copy` reaches Swift as `doCopy`) to [to] (null: the space's root) in its space; [deletedName] is how a copied
     * Pot names an erased person; the lists are read anew. Why not, or null once copied.
     */
    suspend fun duplicate(ref: NodeAt, to: String?, deletedName: String, uuid: () -> String): Notice? {
        if (!online) return readOnly
        val r = runCatching { SubtreeCopy.duplicate(tenant, ref.scopeId, ref.nodeId, to, AppKinds.copies(deletedName, uuid)) }.getOrNull()
        refresh()
        return Notices.subtreeCopyRefused(r?.outcome)
    }

    /**
     * The move or copy sheet of [node] over this shell ([MoveSheet]): its copy by the kind's way — an open Pot by [pot] (the
     * Pot model its page holds; none: the copy fails), everything else with what is under it —, [deletedName] how a copied
     * Pot names an erased person (each new id a UUID from the suite's randomness); [changed] after each write.
     */
    fun moveSheet(
        node: NodeAt, copying: Boolean, pot: me.raddatz.veyq.mobile.expenses.PotModel?, deletedName: String, changed: () -> Unit,
    ): MoveSheet = MoveSheet(node, copying, object : MoveSheet.Door {
        val uuid = { NodeCreate.uuid(tenant.account) }
        override suspend fun tree(scopeId: String) = scopeNodes(scopeId)
        override val placement get() = AppKinds.placement
        override fun rootName(scopeId: String) = ShellWords.space(current?.lists?.spaces?.firstOrNull { it.scopeId == scopeId })
        override suspend fun move(ref: NodeAt, to: String?) = this@ShellModel.move(ref, to).result.outcome
        override suspend fun duplicateOpen(ref: NodeAt, to: String?): Notice? {
            val open = pot?.current?.ref?.takeIf { it.nodeId == ref.nodeId } ?: return Notice(Msg.COPY_FAILED)
            return Notices.potCopyRefused(pot.duplicate(this@ShellModel, open, to, deletedName, uuid).outcome)
        }
        override suspend fun duplicateSubtree(ref: NodeAt, to: String?) = duplicate(ref, to, deletedName, uuid)
        override suspend fun preview(ref: NodeAt) = SubtreeCopy.preview(tenant, ref.scopeId, ref.nodeId, AppKinds.copies(deletedName, uuid))
        override suspend fun noRoom(ref: NodeAt) = StorageFit.noRoomForCopy(tenant, ref.scopeId, ref.nodeId, AppKinds.exports(deletedName))
        override fun changed() = changed()
    })

    /**
     * The share sheet of [node] over this tenant ([ShareSheet]): its links (a drop box's WRITE_ONLY ones), the writes on
     * them — a link's close re-keying what is below it by the build's kinds (`AppKinds.rekeys`), each new id a UUID from the
     * suite's randomness, and the tenant told ([TenantSession.noteRekeyed]) —, a folder's "Add files only"; [changed] after a
     * node was made.
     */
    fun shareSheet(node: NodeAt, changed: () -> Unit): ShareSheet = ShareSheet(node, shareDoor(node, node.nodeId, changed), now)

    /**
     * The share of space [scopeId]'s top level over this tenant ([ShareSheet.topLevel]): "Add files only" alone, its drop
     * box made at the space's top with its first link; [changed] once it is made.
     */
    fun topLevelShareSheet(scopeId: String, changed: () -> Unit): ShareSheet {
        val top = NodeAt(scopeId, "", NodeRef.FOLDER, "")
        return ShareSheet.topLevel(scopeId, shareDoor(top, null, changed), now)
    }

    /** The share sheet's door on [node]; a drop box "Add files only" makes goes under [dropBoxParent] (null: the space's top). */
    private fun shareDoor(node: NodeAt, dropBoxParent: String?, changed: () -> Unit) = object : ShareSheet.Door {
        val uuid = { NodeCreate.uuid(tenant.account) }
        // a drop box lists its upload-only links, a Memory and a chapter every role in one list, any other kind none
        val writeOnly: Boolean? = when (node.kind) {
            me.raddatz.veyq.mobile.depot.DepotFiles.DROPBOX -> true
            me.raddatz.veyq.mobile.memories.MemoryCreate.MEMORY, me.raddatz.veyq.mobile.memories.MemoryCreate.CHAPTER -> null
            else -> false
        }
        override val rules get() = tenant.linkRules
        override suspend fun list() = ShareLinks.list(tenant, node.scopeId, node.nodeId, writeOnly)
        override suspend fun mint(role: String, label: String, password: String, expiresAt: String?) =
            ShareLinks.mint(tenant, node.scopeId, node.nodeId, role, label, password, expiresAt)
        override suspend fun createDropBox(name: String, password: String, expiresAt: String?) =
            me.raddatz.veyq.mobile.depot.DropBoxes.create(tenant, node.scopeId, dropBoxParent, name, password, expiresAt)
        override suspend fun edit(shareId: String, edit: ShareLinkChanges.Edit) =
            ShareLinkChanges.edit(tenant, node.scopeId, node.nodeId, shareId, edit, now())
        override suspend fun renew(shareId: String) =
            ShareLinkChanges.renew(tenant, node.scopeId, node.nodeId, shareId, AppKinds.rekeys, AppKinds::tokenInput, uuid, now())
        override suspend fun switchOff(shareId: String) =
            ShareLinks.switchOff(tenant, node.scopeId, node.nodeId, shareId, AppKinds.rekeys, AppKinds::tokenInput, uuid)
        override suspend fun reactivate(shareId: String) = ShareLinkChanges.reactivate(tenant, node.scopeId, node.nodeId, shareId)
        override suspend fun delete(shareId: String) =
            ShareLinkChanges.delete(tenant, node.scopeId, node.nodeId, shareId, AppKinds.rekeys, AppKinds::tokenInput, uuid, now())
        override fun rekeyed() = tenant.noteRekeyed()
        override fun changed() {
            changed()
            CoroutineScope(Dispatchers.Default).launch { refresh() }
        }
    }

    /** The report of [node] to the tenant's moderators over this tenant ([ReportSheet], core's [NodeReport]). */
    fun reportSheet(node: NodeAt): ReportSheet = ReportSheet(node, object : ReportSheet.Door {
        override val origin get() = tenant.origin
        override suspend fun send(scope: NodeReport.Scope, category: String, reason: String, contact: String) =
            NodeReport.report(tenant, node.scopeId, node.nodeId, scope, category, reason, contact)
    })

    /**
     * Node [ref]'s download into [sink]: a file the file itself ([AppKinds.ownDownload]), any other node with everything
     * under it as the web's ZIP (`NodeExport.walk`, `write`; the kinds the build exports, a copied Pot's erased people named
     * [deletedName]): what it left out, or null when there is nothing to download or it did not finish.
     * Natively the sink is a file by its path (`FileSink`, which removes a download that did not finish).
     */
    suspend fun download(ref: NodeAt, deletedName: String, nowMs: Long, sink: (ByteArray) -> Unit): List<NodeExport.Skipped>? =
        downloadAs(ref.kind, own = { AppKinds.ownDownload(tenant, ref, it) == true }, zip = zip@{ out ->
            val walked = NodeExport.walk(tenant, ref.scopeId, ref.nodeId, AppKinds.exports(deletedName)) ?: return@zip null
            NodeExport.write(walked.root, VeyqAccount.MAJOR.toString(), nowMs, out)
            walked.skipped
        }, sink = sink)

    /**
     * The file [ref]'s page shows over this tenant ([FileModel]): the file read anew on each [FileModel.reload], its content
     * in frames and ranges, its uploader, its replace as a job of the upload queue; [shares] where the platform has a
     * share sheet ("Send a copy").
     */
    fun fileModel(ref: NodeAt, shares: Boolean): me.raddatz.veyq.mobile.depot.FileModel =
        me.raddatz.veyq.mobile.depot.FileModel(ref, object : me.raddatz.veyq.mobile.depot.FileModel.Door {
            override suspend fun file(node: NodeAt) = me.raddatz.veyq.mobile.depot.DepotFiles.one(tenant, node.scopeId, node.nodeId)
            override fun inIdentityFolder(node: NodeAt) = this@ShellModel.inIdentityFolder(node)
            override suspend fun read(file: me.raddatz.veyq.mobile.depot.DepotFiles.File, first: Int, until: Int, sink: suspend (ByteArray) -> Unit) =
                me.raddatz.veyq.mobile.depot.DepotFiles.read(tenant, file, first, until, sink)
            override fun reader(file: me.raddatz.veyq.mobile.depot.DepotFiles.File) = me.raddatz.veyq.mobile.depot.DepotFiles.reader(tenant, file)
            override suspend fun uploader(file: me.raddatz.veyq.mobile.depot.DepotFiles.File) = me.raddatz.veyq.mobile.depot.Uploaders.of(tenant, file)
            override fun replaceJob(file: me.raddatz.veyq.mobile.depot.DepotFiles.File, source: me.raddatz.veyq.mobile.blob.UploadSource, expectedBlobId: String?) =
                me.raddatz.veyq.mobile.depot.DepotFiles.ReplaceJob(tenant, file, source, expectedBlobId, source.name)
            override fun online() = this@ShellModel.online
        }, shares)

    /**
     * The uploads of this tenant over the app's [queue] ([me.raddatz.veyq.mobile.depot.DepotUploads]): files and folders
     * uploaded into a level, a file's replace, the settle's offer; [changed] after each write (the app reads its lists anew).
     */
    fun depotUploads(queue: me.raddatz.veyq.mobile.blob.UploadQueue, changed: () -> Unit): me.raddatz.veyq.mobile.depot.DepotUploads =
        me.raddatz.veyq.mobile.depot.DepotUploads(queue, object : me.raddatz.veyq.mobile.depot.DepotUploads.Door {
            override fun uploadJob(scopeId: String, parentId: String?, source: me.raddatz.veyq.mobile.blob.UploadSource, name: String) =
                me.raddatz.veyq.mobile.depot.DepotFiles.UploadJob(tenant, scopeId, parentId, source, AppKinds::tokenInput, name)
            override suspend fun prepareFolder(scopeId: String, parentId: String?, rootName: String, entries: List<me.raddatz.veyq.mobile.depot.FolderUpload.Entry>) =
                me.raddatz.veyq.mobile.depot.FolderUpload.prepare(tenant, scopeId, parentId, rootName, entries, AppKinds::tokenInput)
            override suspend fun file(scopeId: String, nodeId: String) = me.raddatz.veyq.mobile.depot.DepotFiles.one(tenant, scopeId, nodeId)
            override fun replaceJob(file: me.raddatz.veyq.mobile.depot.DepotFiles.File, source: me.raddatz.veyq.mobile.blob.UploadSource, expectedBlobId: String?) =
                me.raddatz.veyq.mobile.depot.DepotFiles.ReplaceJob(tenant, file, source, expectedBlobId, source.name)
            override fun changed() = changed()
            override fun online() = this@ShellModel.online
        })

    /**
     * Memories' uploads of this tenant over the app's [queue] ([me.raddatz.veyq.mobile.memories.MediaUploads]): each picked
     * medium a job whose tiers the platform's [deriver] makes, a gallery told when its medium is in, [changed] at a batch's
     * end (the app reads its lists anew), [left] once the queue is done with a picked file.
     */
    fun mediaUploads(
        queue: me.raddatz.veyq.mobile.blob.UploadQueue, deriver: me.raddatz.veyq.mobile.memories.MediaUpload.Deriver,
        left: (me.raddatz.veyq.mobile.blob.UploadSource) -> Unit, changed: () -> Unit,
    ): me.raddatz.veyq.mobile.memories.MediaUploads =
        me.raddatz.veyq.mobile.memories.MediaUploads(queue, object : me.raddatz.veyq.mobile.memories.MediaUploads.Door {
            override fun job(scopeId: String, nodeId: String, source: me.raddatz.veyq.mobile.blob.UploadSource) =
                me.raddatz.veyq.mobile.memories.MediaUpload.Job(tenant, scopeId, nodeId, source, deriver, now)
            override fun musicJob(scopeId: String, nodeId: String, track: me.raddatz.veyq.mobile.memories.music.MusicUpload.Check.Taken) =
                me.raddatz.veyq.mobile.memories.music.MusicUpload.Job(tenant, scopeId, nodeId, track)
            override fun trackJob(
                scopeId: String, nodeId: String, track: me.raddatz.veyq.mobile.memories.tracks.TrackUpload.Check.Taken,
                label: me.raddatz.veyq.mobile.memories.music.TrackLabel.Label,
            ) = me.raddatz.veyq.mobile.memories.tracks.TrackUpload.Job(tenant, scopeId, nodeId, track, label)
            // the batch's settle (ADR 0143 D5): where this member may edit the node's meta, online
            override suspend fun settled(scopeId: String, nodeId: String) {
                if (!online) return
                val node = tenant.node(scopeId, nodeId) ?: return
                val at = NodeAt(scopeId, nodeId, node.kind, node.name)
                if (!memoryRights(at).editMeta) return
                val items = me.raddatz.veyq.mobile.memories.MemoryMedia.list(tenant, scopeId, nodeId)?.takeIf { it.offline == null }?.items ?: return
                me.raddatz.veyq.mobile.memories.place.MemoryFacts.reconcile(tenant, scopeId, nodeId, items, placeNames)
            }
            override fun changed() = changed()
            override fun left(source: me.raddatz.veyq.mobile.blob.UploadSource) = left(source)
        })

    /**
     * A Memory or a chapter ([kind], `MemoryCreate.offered` where the shell is) named [name] made in [scopeId] under
     * [parentId] (null: the space's top): the node made, to open, or why not; the lists are read anew. [wasThere]: the
     * maker listed as a Memory's participant from its birth, where [offersWasThere] (ADR 0144 decision 2); [autoPlace]: its
     * place armed to come from its photos, where [offersAutoPlace] (uc.memory.place A1).
     */
    suspend fun createMemoryNode(
        scopeId: String, parentId: String?, kind: String, name: String, wasThere: Boolean = false, autoPlace: Boolean = false,
    ): me.raddatz.veyq.mobile.memories.MemoryCreate.Made {
        if (!online) return me.raddatz.veyq.mobile.memories.MemoryCreate.Made(null, readOnly)
        val armed = autoPlace && offersAutoPlace()
        val made = me.raddatz.veyq.mobile.memories.MemoryCreate.create(tenant, scopeId, parentId, kind, name, wasThere, armed)
        refresh()
        return made
    }

    /** A Memory [name] made in [scopeId] under [parentId] (null: the space's top); the node made, to open, or why not. */
    suspend fun createMemory(scopeId: String, parentId: String?, name: String): me.raddatz.veyq.mobile.memories.MemoryCreate.Made =
        createMemory(scopeId, parentId, name, wasThere = false)

    /**
     * [createMemory] with *I was there* ticked or not ([wasThere], ADR 0144 decision 2). An overload, not a default: Swift
     * sees no Kotlin default, and the Apple app calls the three-argument form.
     */
    suspend fun createMemory(scopeId: String, parentId: String?, name: String, wasThere: Boolean): me.raddatz.veyq.mobile.memories.MemoryCreate.Made =
        createMemoryNode(scopeId, parentId, me.raddatz.veyq.mobile.memories.MemoryCreate.MEMORY, name, wasThere)

    /** [createMemory] with *Determine location automatically* ticked or not ([autoPlace], uc.memory.place A1); Swift's form, as above. */
    suspend fun createMemory(scopeId: String, parentId: String?, name: String, wasThere: Boolean, autoPlace: Boolean): me.raddatz.veyq.mobile.memories.MemoryCreate.Made =
        createMemoryNode(scopeId, parentId, me.raddatz.veyq.mobile.memories.MemoryCreate.MEMORY, name, wasThere, autoPlace)

    /**
     * Whether New Memory offers *Determine location automatically*, unticked (uc.memory.place A1): where this tenant's map services are
     * shown on this device ([mapServices] built, offered and consented) — the web's `mapsAvailable`.
     */
    fun offersAutoPlace(): Boolean = me.raddatz.veyq.mobile.memories.MemoryCreate.offersAutoPlace(
        me.raddatz.veyq.mobile.memories.MemoryCreate.MEMORY, placeNames?.maps?.available == true,
    )

    /** Whether New Memory in [scopeId] offers *I was there*, ticked (ADR 0144 decision 2: a tenant or group scope). */
    fun offersWasThere(scopeId: String): Boolean =
        me.raddatz.veyq.mobile.memories.MemoryCreate.offersWasThere(me.raddatz.veyq.mobile.memories.MemoryCreate.MEMORY, tenant.scopeKind(scopeId))

    /** Node [nodeId]'s cover as a list row draws it, the host holding the frame [held] (`NodeCover.row`); null: none. */
    suspend fun rowCover(nodeId: String, held: String?): NodeCover.Row? = runCatching { NodeCover.row(tenant, nodeId, held) }.getOrNull()

    /**
     * Memory or chapter [nodeId] of space [scopeId] as the lists hold it now — what a page opened by its ids alone opens (a
     * Mac's lightbox window, whose restorable value carries ids, never a name) —; null where it is not listed or is no
     * Memory or chapter.
     */
    fun galleryAt(scopeId: String, nodeId: String): NodeAt? = galleryAt(current?.lists?.nodes.orEmpty(), scopeId, nodeId)

    /** The account's view choice on this device (ADR 0149 decision 8): every level's and a person's Memories'. */
    val viewModes: ViewModes get() = ViewModes(tenant.account.deviceStore)

    /** How a level is drawn now: the account's choice among a level's views ([ViewMode.LEVEL]). */
    val levelView: ViewMode get() = viewModes.of(tenant.account.userId, ViewMode.LEVEL)

    /** A level's view chosen: kept for every level and a person's Memories. */
    fun chooseView(mode: ViewMode) = viewModes.set(tenant.account.userId, mode)

    /**
     * Memory or chapter [ref]'s page over this tenant ([me.raddatz.veyq.mobile.memories.GalleryModel]), opened as a member:
     * its media read anew on every revision of these lists, when a medium [uploads] sent into it is in and when its live
     * poll finds them moved; what it adds goes through [uploads]; a medium trashed and restored, a chapter made.
     */
    fun galleryModel(
        ref: NodeAt, uploads: me.raddatz.veyq.mobile.memories.MediaUploads, music: me.raddatz.veyq.mobile.memories.GalleryModel.Music? = null,
        calendar: me.raddatz.veyq.mobile.memories.GalleryFilter.Calendar = me.raddatz.veyq.mobile.memories.GalleryFilter.Calendar.UTC,
    ): me.raddatz.veyq.mobile.memories.GalleryModel =
        me.raddatz.veyq.mobile.memories.GalleryModel(ref, object : me.raddatz.veyq.mobile.memories.GalleryModel.Door {
            override suspend fun gallery() = me.raddatz.veyq.mobile.memories.MemoryMedia.list(tenant, ref.scopeId, ref.nodeId)
            override suspend fun scopeNodes() = this@ShellModel.scopeNodes(ref.scopeId)
            override suspend fun read(item: me.raddatz.veyq.mobile.memories.MemoryMedia.Item, blob: me.raddatz.veyq.mobile.memories.MemoryMedia.Blob) =
                me.raddatz.veyq.mobile.memories.MemoryMedia.read(tenant, item, blob)
            override fun reader(item: me.raddatz.veyq.mobile.memories.MemoryMedia.Item) =
                me.raddatz.veyq.mobile.memories.MemoryMedia.reader(tenant, item, item.original)
            override suspend fun stream(item: me.raddatz.veyq.mobile.memories.MemoryMedia.Item, sink: suspend (ByteArray) -> Unit) =
                me.raddatz.veyq.mobile.memories.MemoryMedia.stream(tenant, item, item.original, sink = sink)
            override suspend fun exif(item: me.raddatz.veyq.mobile.memories.MemoryMedia.Item) = me.raddatz.veyq.mobile.memories.MediaDetails.exif(tenant, item)
            override suspend fun uploaders(items: List<me.raddatz.veyq.mobile.memories.MemoryMedia.Item>) =
                me.raddatz.veyq.mobile.memories.MediaDetails.uploaders(tenant, ref.scopeId, items)
            override fun upload(sources: List<me.raddatz.veyq.mobile.blob.UploadSource>) = uploads.upload(ref.scopeId, ref.nodeId, ref.name, sources)
            // each read of the lists is a new one (a rebuild after a refresh); a move of the shell alone keeps them
            override val revisions = state.map { it?.lists }.distinctUntilChanged { a, b -> a === b }
            override val uploaded = uploads.uploaded
            override val uploading = uploads.tiles(ref.nodeId)
            override suspend fun moved(since: me.raddatz.veyq.mobile.memories.MemoryMedia.Gallery) =
                me.raddatz.veyq.mobile.memories.MemoryMedia.moved(tenant, ref.scopeId, ref.nodeId, since)
            override suspend fun treeMoved() = tenant.treeMoved(ref.scopeId).also { if (it) refresh() }
            override suspend fun trash(mediaId: String) = me.raddatz.veyq.mobile.memories.MemoryMedia.trash(tenant, ref.scopeId, mediaId)
            override suspend fun restore(mediaId: String) = me.raddatz.veyq.mobile.memories.MemoryMedia.restore(tenant, ref.scopeId, mediaId)
            override suspend fun createChapter(name: String) =
                createMemoryNode(ref.scopeId, ref.nodeId, me.raddatz.veyq.mobile.memories.MemoryCreate.CHAPTER, name)
            override suspend fun cover(nodeId: String) = NodeCover.openNode(tenant, nodeId)
            override val online = tenant.account.reach.map { it == Reach.Online }.distinctUntilChanged()
            override suspend fun facts() = me.raddatz.veyq.mobile.memories.place.MemoryFacts.of(tenant, ref.scopeId, ref.nodeId)
            override suspend fun settle(items: List<me.raddatz.veyq.mobile.memories.MemoryMedia.Item>) =
                me.raddatz.veyq.mobile.memories.place.MemoryFacts.reconcile(tenant, ref.scopeId, ref.nodeId, items, placeNames)
            override suspend fun placeName(item: me.raddatz.veyq.mobile.memories.MemoryMedia.Item, mayWrite: Boolean, slideshow: Boolean) =
                me.raddatz.veyq.mobile.memories.place.PhotoPlace.resolve(tenant, item, placeNames, mayWrite, slideshow)
            override val personal = tenant.scopeKind(ref.scopeId) == "PERSONAL"
            override fun marks() = me.raddatz.veyq.mobile.memories.ItemMarks.of(tenant, ref.scopeId, ref.nodeId)
            override suspend fun report(item: me.raddatz.veyq.mobile.memories.MemoryMedia.Item, category: String, reason: String, contact: String) =
                me.raddatz.veyq.mobile.memories.MediumReport.report(tenant, item, category, reason, contact)
            override suspend fun reportOffered() = this@ShellModel.reportOffered()
            override val origin get() = tenant.origin
            override suspend fun members() = tenant.scopeMembers(ref.scopeId)
            override suspend fun setParticipants(wanted: List<String>, openedWith: List<String>) =
                me.raddatz.veyq.mobile.memories.Participants.set(tenant, ref.scopeId, ref.nodeId, wanted, openedWith).also { runCatching { refresh() } }
            override suspend fun captureTime(item: me.raddatz.veyq.mobile.memories.MemoryMedia.Item, overrideMs: Long?) =
                if (overrideMs == null) me.raddatz.veyq.mobile.memories.CaptureTime.reset(tenant, item)
                else me.raddatz.veyq.mobile.memories.CaptureTime.set(tenant, item, overrideMs)
            override suspend fun bannerPicture() =
                tenant.node(ref.scopeId, ref.nodeId)?.let { me.raddatz.veyq.mobile.memories.banner.Banner.picture(tenant, it) }
            override suspend fun bannerReader() =
                tenant.node(ref.scopeId, ref.nodeId)?.let { me.raddatz.veyq.mobile.memories.banner.Banner.reader(tenant, it) }
            override suspend fun setBanner(
                openedWith: me.raddatz.veyq.mobile.memories.banner.Banner.Slots, picked: me.raddatz.veyq.mobile.memories.banner.BannerWrite.Picked,
                items: List<me.raddatz.veyq.mobile.memories.MemoryMedia.Item>, progress: (Double) -> Unit,
            ) = me.raddatz.veyq.mobile.memories.banner.BannerWrite.set(tenant, ref.scopeId, ref.nodeId, openedWith, picked, items, progress)
                .also { runCatching { refresh() } }
            override suspend fun removeBanner(openedWith: me.raddatz.veyq.mobile.memories.banner.Banner.Slots) =
                me.raddatz.veyq.mobile.memories.banner.BannerWrite.remove(tenant, ref.scopeId, ref.nodeId, openedWith).also { runCatching { refresh() } }
        }, access = memoryAccess(ref), now = now, music = music,
            search = me.raddatz.veyq.mobile.memories.GalleryModel.Search(openRequests, searchContributions), calendar = calendar)

    // ---- the search (ADR 0146) ----

    /** The search's one-shot requests to a node's page (a photo of another Memory opened in its lightbox). */
    val openRequests = me.raddatz.veyq.mobile.search.OpenRequests()

    /** What the page of the node on screen adds to the search (the open Memory's photos). */
    val searchContributions = me.raddatz.veyq.mobile.search.SearchContributions()

    /**
     * The search over this tenant (ADR 0146 decision 7): one per field, built by a platform beside its shell — [words] the
     * platform's catalog in the UI's language, [appearance] whether its settings page offers the appearance section (the
     * phones) — and [me.raddatz.veyq.mobile.search.SearchModel.dispose]d where the tenant closes (the bridge's
     * `closeTenant`, the phones' switch, a lock, a sign-out). It reads the shell's trees, never a tree of its own; a result
     * opens through this shell. [trace] hears how long each walk of the index took (counts and milliseconds, no name).
     */
    fun searchModel(
        words: me.raddatz.veyq.mobile.search.SearchWords, appearance: Boolean, trace: ((String) -> Unit)? = null,
    ): me.raddatz.veyq.mobile.search.SearchModel =
        me.raddatz.veyq.mobile.search.SearchModel(searchDoor(appearance), AppKinds.search, words, openRequests, searchContributions, now, trace = trace)

    private fun searchDoor(appearance: Boolean) = object : me.raddatz.veyq.mobile.search.SearchModel.Door {
        override suspend fun spaces() = current?.lists?.spaces ?: hidden.visibleSpaces(tenant.spaces())
        override suspend fun <V : Any> fromTree(key: String, derive: suspend (Map<String, List<OpenNode>>) -> V): V = tenant.fromTree(key, derive)
        override suspend fun treeMoved(scopeId: String) = tenant.treeMoved(scopeId).also { if (it) refresh() }
        override fun notRead() = tenant.unreadTrees + tenant.closed.keys
        override fun openMeta(node: OpenNode) =
            runCatching { NodeMetaWrite.openForWrite(tenant.account.suite, node.scopeId, node.id, node.key, node.wrappedMeta) }.getOrNull()
        override fun online() = online
        override fun people() = tenant.people.values
        override suspend fun settings(): List<me.raddatz.veyq.mobile.search.SettingsSection> {
            val account = tenant.account
            val linking = online && runCatching {
                account.call { me.raddatz.veyq.mobile.api.apis.SetupApi(account.server, account.http).getSetupStatus() }
                    .capabilities.any { it.id == me.raddatz.veyq.mobile.session.AccountSettings.DEVICE_LINKING }
            }.getOrDefault(false)
            // the page's own gates: the storage section where a store is kept or why none is said (AccountSettings.Over)
            val storage = account.offline != null || (account.thisDevice.offlineRoot != null && account.trusted == null)
            return me.raddatz.veyq.mobile.search.SettingsSection.offered(
                me.raddatz.veyq.mobile.search.SettingsSection.Gates(addDevice = linking, storage = storage, maps = placeNames != null, appearance = appearance),
            )
        }
        override fun where(): me.raddatz.veyq.mobile.search.Results.SearchContext {
            val s = shell
            return me.raddatz.veyq.mobile.search.Results.SearchContext(s?.openObject?.scopeId ?: s?.space, s?.openObject?.nodeId)
        }
        override fun applicationsOff() = tenant.applicationsOff()
        override suspend fun scopeDocuments(
            app: me.raddatz.veyq.mobile.search.ApplicationSearch, scopeId: String, tag: String?,
            previous: Map<String, me.raddatz.veyq.mobile.search.ScopeDocument>, nodes: Map<String, OpenNode>, unnamed: (Boolean) -> String,
        ) = app.scopeDocuments(me.raddatz.veyq.mobile.search.ScopeDocumentsContext(tenant, scopeId, tag, previous, nodes, unnamed))
        override fun openNode(scopeId: String, nodeId: String) = openFound(scopeId, nodeId)
        override fun openPerson(userId: String) = this@ShellModel.openPerson(userId)
    }

    /**
     * Node [nodeId] of [scopeId] a search found, opened where it lives (ADR 0146 decision 8): an object in its application in
     * its space (back returns to the search), a folder on Home at that folder; a node that is neither (a chapter, a kept
     * link) by the nearest object or folder above it.
     */
    fun openFound(scopeId: String, nodeId: String) {
        val nodes = current?.lists?.nodes.orEmpty().filter { it.scopeId == scopeId }
        val byId = nodes.associateBy { it.nodeId }
        val seen = HashSet<String>()
        var n = byId[nodeId]
        while (n != null && seen.add(n.nodeId)) {
            val obj = n.asObject() ?: if (n.nodeId == nodeId) me.raddatz.veyq.mobile.memories.GalleryModel.rowPage(n) else null
            if (obj != null) return opened(obj) { it.open(obj).copy(space = scopeId) }
            if (n.isFolder) {
                val folder = n.nodeId
                return update { it.switchSpace(scopeId).at(nodes, folder) }
            }
            n = n.parentId?.let { byId[it] }
        }
        update { it.switchSpace(scopeId) }
    }

    /** Who looks at Memory or chapter [ref] from this shell: a member, as the tenant's switches and the folder say. */
    private fun memoryAccess(ref: NodeAt) = me.raddatz.veyq.mobile.memories.MemoryRights.Access.Member(
        memoriesOn = "memories" !in tenant.applicationsOff(), inIdentityFolder = inIdentityFolder(ref),
    )

    /** What a member may do on Memory or chapter [ref] here. */
    private fun memoryRights(ref: NodeAt) = me.raddatz.veyq.mobile.memories.MemoryRights.of(memoryAccess(ref), ref.kind)

    /**
     * Where this shell's place names come from ([mapServices] sets it): a settle's `auto` place and a photo's name. Null
     * before the map services are built: no name is looked up then (a settle still re-medoids).
     */
    var placeNames: me.raddatz.veyq.mobile.memories.place.PlaceNames? = null
        private set

    /**
     * This tenant's map services on this device ([me.raddatz.veyq.mobile.map.MapServices.of], ADR 0143 D2): one per signed-in
     * tenant in the process, built beside [mediaUploads] by the platform ([recipient] Apple Maps on Apple; [lang] the app's
     * language, the geocoder's `accept-language`) and closed where the tenant closes (a lock, a sign-out, a switch) — its
     * tiles dropped, its geocoder's queue cleared. The shell's place names come from it from then on ([placeNames]).
     */
    fun mapServices(recipient: me.raddatz.veyq.mobile.map.TileRecipient, lang: () -> String): me.raddatz.veyq.mobile.map.MapServices =
        me.raddatz.veyq.mobile.map.MapServices.of(tenant, recipient).also { placeNames = me.raddatz.veyq.mobile.memories.place.PlaceNames(it, lang) }

    /**
     * Memory or chapter [gallery]'s date editor (ADR 0143 D5), where whoever looks may edit its meta and online; null
     * otherwise. A save re-reads the page's facts line and the lists (a row shows the range).
     */
    fun dateEditor(gallery: me.raddatz.veyq.mobile.memories.GalleryModel): me.raddatz.veyq.mobile.memories.place.DateEditor? {
        val g = gallery.current
        if (!g.editsFacts) return null
        return me.raddatz.veyq.mobile.memories.place.DateEditor(g.facts?.date, g.autoRange) { date ->
            me.raddatz.veyq.mobile.memories.place.MemoryFacts.setDate(tenant, gallery.node.scopeId, gallery.node.nodeId, date).also {
                gallery.factsChanged()
                runCatching { refresh() }
            }
        }
    }

    /**
     * Memory or chapter [gallery]'s place editor (ADR 0143 D5) over the shell's map services ([placeNames]; none: the name
     * field alone), where whoever looks may edit its meta and online; null otherwise.
     */
    fun placeEditor(gallery: me.raddatz.veyq.mobile.memories.GalleryModel): me.raddatz.veyq.mobile.memories.place.PlaceEditor? {
        val names = placeNames
        val g = gallery.current
        if (!g.editsFacts) return null
        val points = g.gpsPoints
        return me.raddatz.veyq.mobile.memories.place.PlaceEditor(
            g.facts?.location, me.raddatz.veyq.mobile.memories.place.MemoryLocation.medoid(points), points, names,
            save = { place ->
                me.raddatz.veyq.mobile.memories.place.MemoryFacts.setLocation(tenant, gallery.node.scopeId, gallery.node.nodeId, place).also {
                    gallery.factsChanged()
                    runCatching { refresh() }
                }
            },
        )
    }

    /** The Memories bar's Map tab over [memories] (its All tab's set, ADR 0143 D6): every located one a pin. */
    suspend fun memoriesMap(memories: List<NodeRef>): me.raddatz.veyq.mobile.memories.place.MemoriesMap =
        me.raddatz.veyq.mobile.memories.place.MemoriesMap.of(tenant, memories)

    /** The date of every Memory and chapter this tenant opens, by node id: a Memories row's second line (ADR 0143 D5). */
    suspend fun memoryDates(): Map<String, me.raddatz.veyq.mobile.memories.place.MemoryDate> =
        runCatching { me.raddatz.veyq.mobile.memories.place.MemoryFacts.dates(tenant) }.getOrDefault(emptyMap())

    /**
     * Memory or chapter [gallery]'s Map view's tracks over this tenant ([me.raddatz.veyq.mobile.memories.tracks.TrackList],
     * ADR 0143 D8): picked GPX files checked, queued through [uploads] with their labels, a file not taken handed to [left];
     * a track relabelled, reported as an ITEM.
     */
    fun trackList(
        gallery: me.raddatz.veyq.mobile.memories.GalleryModel, uploads: me.raddatz.veyq.mobile.memories.MediaUploads,
        left: (me.raddatz.veyq.mobile.blob.UploadSource) -> Unit,
    ): me.raddatz.veyq.mobile.memories.tracks.TrackList =
        me.raddatz.veyq.mobile.memories.tracks.TrackList(gallery, object : me.raddatz.veyq.mobile.memories.tracks.TrackList.Door {
            override suspend fun check(source: me.raddatz.veyq.mobile.blob.UploadSource, held: Int) =
                me.raddatz.veyq.mobile.memories.tracks.TrackUpload.check(source, held)
            override fun queue(tracks: List<Pair<me.raddatz.veyq.mobile.memories.tracks.TrackUpload.Check.Taken, me.raddatz.veyq.mobile.memories.music.TrackLabel.Label>>) =
                uploads.uploadTracks(gallery.node.scopeId, gallery.node.nodeId, gallery.node.name, tracks)
            override fun left(source: me.raddatz.veyq.mobile.blob.UploadSource) = left(source)
            override suspend fun relabel(item: me.raddatz.veyq.mobile.memories.MemoryMedia.Item, label: me.raddatz.veyq.mobile.memories.music.TrackLabel.Label) =
                me.raddatz.veyq.mobile.memories.music.TrackLabel.relabel(tenant, item, label)
            override suspend fun report(item: me.raddatz.veyq.mobile.memories.MemoryMedia.Item, category: String, reason: String, contact: String) =
                me.raddatz.veyq.mobile.memories.MediumReport.report(tenant, item, category, reason, contact)
            override suspend fun reportOffered() = this@ShellModel.reportOffered()
            override val origin get() = tenant.origin
        })

    /**
     * The account's music player over the platform's two decks ([newDeck], ADR 0142 decision 2) and its system controls
     * ([controls], decision 3), its order's randomness the suite's (ADR 0142 decision 1). One per signed-in account in the
     * process, built beside [mediaUploads] and stopped where the tenant closes (a lock, a sign-out, a switch).
     */
    fun musicPlayer(
        newDeck: (me.raddatz.veyq.mobile.audio.Deck.Events) -> me.raddatz.veyq.mobile.audio.Deck,
        controls: me.raddatz.veyq.mobile.audio.SystemControls?,
    ): me.raddatz.veyq.mobile.audio.MusicPlayer =
        me.raddatz.veyq.mobile.audio.MusicPlayer(newDeck, controls, me.raddatz.veyq.mobile.audio.randomIntOf { tenant.account.suite.p.randomBytes(it) })

    /**
     * Memory or chapter [gallery]'s Music view over this tenant ([me.raddatz.veyq.mobile.memories.music.MusicModel]): a
     * picked file checked with the platform's [measure] of its length, the tracks queued through [uploads], a picked file
     * not taken handed to [left]; a track renamed, reported as an ITEM.
     */
    fun musicModel(
        gallery: me.raddatz.veyq.mobile.memories.GalleryModel, uploads: me.raddatz.veyq.mobile.memories.MediaUploads,
        measure: me.raddatz.veyq.mobile.memories.music.MusicUpload.Measure, left: (me.raddatz.veyq.mobile.blob.UploadSource) -> Unit,
    ): me.raddatz.veyq.mobile.memories.music.MusicModel =
        me.raddatz.veyq.mobile.memories.music.MusicModel(gallery, object : me.raddatz.veyq.mobile.memories.music.MusicModel.Door {
            override suspend fun check(source: me.raddatz.veyq.mobile.blob.UploadSource) =
                me.raddatz.veyq.mobile.memories.music.MusicUpload.check(
                    source,
                    endsOf = { type, durationMs, limitMs ->
                        kotlinx.coroutines.suspendCancellableCoroutine { c -> measure.ends(source, type, durationMs, limitMs) { ends -> c.resume(ends) { _, _, _ -> } } }
                    },
                ) { type ->
                    kotlinx.coroutines.suspendCancellableCoroutine { c -> measure.durationMs(source, type) { ms -> c.resume(ms) { _, _, _ -> } } }
                }
            override fun queue(tracks: List<me.raddatz.veyq.mobile.memories.music.MusicUpload.Check.Taken>) =
                uploads.uploadMusic(gallery.node.scopeId, gallery.node.nodeId, gallery.node.name, tracks)
            override fun left(source: me.raddatz.veyq.mobile.blob.UploadSource) = left(source)
            override suspend fun rename(item: me.raddatz.veyq.mobile.memories.MemoryMedia.Item, name: String?) =
                me.raddatz.veyq.mobile.memories.music.TrackLabel.rename(tenant, item, name)
            override suspend fun report(item: me.raddatz.veyq.mobile.memories.MemoryMedia.Item, category: String, reason: String, contact: String) =
                me.raddatz.veyq.mobile.memories.MediumReport.report(tenant, item, category, reason, contact)
            override suspend fun reportOffered() = this@ShellModel.reportOffered()
            override val origin get() = tenant.origin
        })

    /**
     * Drop box [ref]'s page over this tenant ([me.raddatz.veyq.mobile.depot.DropBoxPage]): its guests' folders, read anew on
     * every revision of these lists.
     */
    fun dropBoxPage(ref: NodeAt): me.raddatz.veyq.mobile.depot.DropBoxPage =
        me.raddatz.veyq.mobile.depot.DropBoxPage(ref, object : me.raddatz.veyq.mobile.depot.DropBoxPage.Door {
            override suspend fun folders() = me.raddatz.veyq.mobile.depot.DropBoxes.folders(tenant, ref.scopeId, ref.nodeId)
            // each read of the lists is a new one (a rebuild after a refresh); a move of the shell alone keeps them
            override val revisions = state.map { it?.lists }.distinctUntilChanged { a, b -> a === b }
            // a guest's new folder: the scope's tree moved, the lists are read anew and the page with them
            override suspend fun treeMoved() = tenant.treeMoved(ref.scopeId).also { if (it) refresh() }
        })

    companion object {
        /** Memory or chapter [nodeId] of space [scopeId] among [nodes]; null where it is not among them or is another kind. */
        fun galleryAt(nodes: List<NodeRef>, scopeId: String, nodeId: String): NodeAt? =
            nodes.firstOrNull { it.scopeId == scopeId && it.nodeId == nodeId }
                ?.takeIf { it.kind == me.raddatz.veyq.mobile.memories.GalleryModel.MEMORY || it.kind == me.raddatz.veyq.mobile.memories.GalleryModel.CHAPTER }
                ?.let { NodeAt(it.scopeId, it.nodeId, it.kind, it.name) }

        /**
         * A node of [kind]'s download into [sink]: its [own] content where the kind downloads itself
         * ([AppKinds.downloadsItself]: a file, nothing left out) — null where it did not finish —, else the [zip] of it.
         */
        suspend fun downloadAs(
            kind: String, own: suspend ((ByteArray) -> Unit) -> Boolean, zip: suspend ((ByteArray) -> Unit) -> List<NodeExport.Skipped>?,
            sink: (ByteArray) -> Unit,
        ): List<NodeExport.Skipped>? =
            if (AppKinds.downloadsItself(kind)) (if (own(sink)) emptyList() else null) else zip(sink)

        /**
         * The shell after the lists were read: [prior] kept (null: the app starts — on [lastUsed], else Home's default
         * space), but a space no longer listed gives way to the default one.
         */
        fun next(prior: Shell?, spaces: List<SpaceRef>, lastUsed: ObjectRef?): Shell {
            val defaultSpace = Home.defaultSpace(spaces)?.scopeId
            return when {
                prior == null -> Shell.start(lastUsed, defaultSpace)
                prior.space != null && spaces.none { it.scopeId == prior.space } -> prior.switchSpace(defaultSpace ?: "")
                else -> prior
            }
        }

        /** Whether [shell] stands on [space]'s top level on Home: no folder, object, application, person's page or Search open. */
        fun topLevelShared(shell: Shell, space: String): Boolean =
            shell.openObject == null && shell.application == null && shell.person == null && !shell.searching && shell.folderAt(space) == null

        /**
         * The shell where folder [nodeId] on Home's path changed: still in [tree] (its space's nodes), the path read anew to
         * where it is now; gone (trashed), Home shows the folder above it.
         */
        fun relocated(shell: Shell, tree: List<NodeRef>, nodeId: String): Shell {
            if (shell.path.none { it.nodeId == nodeId }) return shell
            if (tree.any { it.nodeId == nodeId }) return shell.at(tree, shell.folder?.nodeId)
            val i = shell.path.indexOfFirst { it.nodeId == nodeId }
            return shell.at(tree, if (i > 0) shell.path[i - 1].nodeId else null)
        }

        /**
         * The shell once node [nodeId] went to the trash: an open object of it leaves ([Shell.leave]) — one opened at the
         * top for its application's list, since back does not leave it (its undo brings it back to that list, not open).
         */
        fun afterTrash(shell: Shell, nodeId: String): Shell = if (shell.openObject?.nodeId == nodeId) shell.leave() else shell

        /**
         * The shell once object [renamed] has its new name: an open page of it shows it under that name, where it was —
         * its back and its place among the top-level destinations ([Shell.top]) kept.
         */
        fun afterRename(shell: Shell, renamed: ObjectRef): Shell =
            if (shell.openObject?.let { it.nodeId == renamed.nodeId && it.scopeId == renamed.scopeId } == true) shell.copy(openObject = renamed) else shell

        /** How long a trash's undo is offered (the web's `TRASH_UNDO_MS`). */
        const val TRASH_UNDO_MS = 6_000L

        /** The drawer's short lists (ADR 0122): six favorites, eight recent. */
        const val DRAWER_FAVORITES = 6
        const val DRAWER_RECENT = 8
    }
}
