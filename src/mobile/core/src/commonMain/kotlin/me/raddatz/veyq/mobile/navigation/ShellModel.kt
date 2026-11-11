package me.raddatz.veyq.mobile.navigation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
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
import me.raddatz.veyq.mobile.session.ShareLinkChanges
import me.raddatz.veyq.mobile.session.ShareLinks
import me.raddatz.veyq.mobile.session.StorageFit
import me.raddatz.veyq.mobile.session.VeyqAccount
import me.raddatz.veyq.mobile.session.TenantSession
import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.Notice
import me.raddatz.veyq.mobile.words.Notices

/**
 * The shell over one tenant (ADR 0122, ADR 0127 decision 2): what it lists — its spaces, nodes, objects and favorites as
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

    /** Serializable, since the native bridge sends it as it is (ADR 0123's Consequences, the bridge). */
    @Serializable
    class State(val shell: Shell, val lists: Lists)

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
    }

    private var rekeys: kotlinx.coroutines.Job? = null

    /** Reads the lists anew (after a write, or what another device wrote); a read that fails keeps what was listed. */
    suspend fun refresh() {
        reading.withLock { runCatching { read() }.getOrNull()?.let { raw = it } }
        rebuild(start = false, openLastUsed = false)
    }

    private suspend fun read() = Raw(tenant.spaces(), tenant.nodes(), tenant.favorites(), tenant.applicationsOff())

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
            State(next(prior?.shell.takeUnless { start }, spaces, if (openLastUsed) recent.lastUsed(objects) else null), lists)
        }
    }

    private fun drawer(objects: List<ObjectRef>, favorites: List<ObjectRef>) =
        arrange(favorites, recent.resolve(objects), favoritesLimit = DRAWER_FAVORITES, recentLimit = DRAWER_RECENT)

    // ---- where the person is ----

    /** Any move of the shell (its own transitions are `Shell`'s). */
    fun update(change: (Shell) -> Shell) {
        _state.update { s -> s?.let { State(change(it.shell), it.lists) } }
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

    private fun opened(ref: ObjectRef, move: (Shell) -> Shell) {
        recent.record(ref, now())
        _state.update { s -> s?.let { State(move(it.shell), Lists(it.lists.spaces, it.lists.nodes, it.lists.objects, it.lists.favorites, it.lists.applications, it.lists.filters, drawer(it.lists.objects, it.lists.favorites), recent.resolve(it.lists.objects))) } }
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

    /** Whether "New" is offered where the shell is: in a space, never in a guest's folder (the web's `folderRights`). */
    val canCreateHere: Boolean
        get() {
            val s = shell ?: return false
            val space = s.space ?: return false
            return s.folderAt(space)?.let { !inIdentityFolder(it) } ?: true
        }

    /** Pins or unpins [ref], then the lists are read anew; false when it was not stored (the heart goes back). */
    suspend fun toggleFavorite(ref: NodeAt): Boolean {
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
        val token = AppKinds.tokenInput(row.kind, newName?.trim() ?: row.name) ?: return NodeTrash.Restored.REFUSED
        val r = runCatching { NodeTrash.restoreFromTrash(tenant, row, token, newName) }.getOrDefault(NodeTrash.Restored.REFUSED)
        refresh()
        return r
    }

    /** Erases [row] and everything under it now: irreversible. */
    suspend fun purge(row: NodeTrash.Trashed): NodeTrash.Purged =
        runCatching { NodeTrash.purge(tenant, row.scopeId, row.id) }.getOrDefault(NodeTrash.Purged.FAILED)

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
            override suspend fun cover(ref: NodeAt): NodeCover.Crop? {
                val n = tenant.node(ref.scopeId, ref.nodeId) ?: return null
                return NodeCover.read(NodeMetaWrite.openForWrite(tenant.account.suite, n.scopeId, n.id, n.key, n.wrappedMeta))?.crop
            }
            override suspend fun coverPicture(ref: NodeAt) = NodeCover.openNode(tenant, ref.nodeId)
            override suspend fun setCover(ref: NodeAt, jpeg: ByteArray, crop: NodeCover.Crop) = NodeCover.set(tenant, ref.scopeId, ref.nodeId, jpeg, crop)
            override suspend fun recropCover(ref: NodeAt, crop: NodeCover.Crop) = NodeCover.recrop(tenant, ref.scopeId, ref.nodeId, crop)
            override suspend fun removeCover(ref: NodeAt) = NodeCover.remove(tenant, ref.scopeId, ref.nodeId)
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
        val from = runCatching { tenant.node(ref.scopeId, ref.nodeId)?.parentId }.getOrNull()
        val r = runCatching { NodeMove.move(tenant, ref.scopeId, ref.nodeId, to, AppKinds::tokenInput, AppKinds.placement) }
            .getOrDefault(NodeMove.Result(NodeMove.Outcome.FAILED))
        refresh()
        if (r.outcome == NodeMove.Outcome.MOVED) relocate(ref)
        return Moved(r, Notices.moveRefused(r.outcome), from)
    }

    /** A new folder [name] in [scopeId] under [parentId] (null: the space's root); why not, or null once made. */
    suspend fun createFolder(scopeId: String, parentId: String?, name: String): Notice? {
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
    fun shareSheet(node: NodeAt, changed: () -> Unit): ShareSheet = ShareSheet(node, object : ShareSheet.Door {
        val uuid = { NodeCreate.uuid(tenant.account) }
        val writeOnly = node.kind == me.raddatz.veyq.mobile.depot.DepotFiles.DROPBOX
        override val rules get() = tenant.linkRules
        override suspend fun list() = ShareLinks.list(tenant, node.scopeId, node.nodeId, writeOnly)
        override suspend fun mint(role: String, label: String, password: String, expiresAt: String?) =
            ShareLinks.mint(tenant, node.scopeId, node.nodeId, role, label, password, expiresAt)
        override suspend fun createDropBox(name: String, password: String, expiresAt: String?) =
            me.raddatz.veyq.mobile.depot.DropBoxes.create(tenant, node.scopeId, node.nodeId, name, password, expiresAt)
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
    }, now)

    /** The report of [node] to the tenant's moderators over this tenant ([ReportSheet], core's [NodeReport]). */
    fun reportSheet(node: NodeAt): ReportSheet = ReportSheet(node, object : ReportSheet.Door {
        override val origin get() = tenant.origin
        override suspend fun send(scope: NodeReport.Scope, category: String, reason: String, contact: String) =
            NodeReport.report(tenant, node.scopeId, node.nodeId, scope, category, reason, contact)
    })

    /**
     * Node [ref] with everything under it as the web's ZIP into [sink] (`NodeExport.walk`, `write`; the kinds the build
     * exports, a copied Pot's erased people named [deletedName]): what it left out, or null when there is nothing to export.
     * Natively the sink is a file by its path (`FileSink`, which removes a download that did not finish).
     */
    suspend fun download(ref: NodeAt, deletedName: String, nowMs: Long, sink: (ByteArray) -> Unit): List<NodeExport.Skipped>? {
        val walked = NodeExport.walk(tenant, ref.scopeId, ref.nodeId, AppKinds.exports(deletedName)) ?: return null
        NodeExport.write(walked.root, VeyqAccount.MAJOR.toString(), nowMs, sink)
        return walked.skipped
    }

    companion object {
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
