package me.raddatz.veyq.mobile.navigation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.raddatz.veyq.mobile.AppKinds
import me.raddatz.veyq.mobile.export.SubtreeCopy
import me.raddatz.veyq.mobile.session.FolderCreate
import me.raddatz.veyq.mobile.session.NodeCreate
import me.raddatz.veyq.mobile.session.NodeMove
import me.raddatz.veyq.mobile.session.NodeRename
import me.raddatz.veyq.mobile.session.NodeTrash
import me.raddatz.veyq.mobile.session.OpenNode
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
    class Lists(
        val spaces: List<SpaceRef>,
        val nodes: List<NodeRef>,
        val objects: List<ObjectRef>,
        val favorites: List<ObjectRef>,
        val applications: List<ApplicationId>,
        val filters: List<ApplicationId?>,
        val drawer: ArrangedList,
    ) {
        /** The chosen application's own list, by the one rule (ADR 0122 decision 5). */
        fun applicationList(app: ApplicationId): ArrangedList =
            arrange(favorites.filter { it.application == app }, emptyList(), objects.filter { it.application == app })

        /** Home's level: the shown folder's children in the shown space, under the shown filter. */
        fun level(shell: Shell): List<NodeRef> = shell.space?.let { Home.level(nodes, it, shell.folder?.nodeId, shell.filter) }.orEmpty()

        fun space(shell: Shell): SpaceRef? = spaces.firstOrNull { it.scopeId == shell.space }
    }

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
    }

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
    fun open(ref: ObjectRef) {
        recent.record(ref, now())
        _state.update { s -> s?.let { State(it.shell.open(ref), Lists(it.lists.spaces, it.lists.nodes, it.lists.objects, it.lists.favorites, it.lists.applications, it.lists.filters, drawer(it.lists.objects, it.lists.favorites))) } }
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
        if (r.gone && shell?.openObject?.nodeId == ref.nodeId) goBack()
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
            if (renamed != null && shell?.openObject?.nodeId == ref.nodeId) update { it.goBack().open(renamed) }
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

    /**
     * Copies [ref] with everything under it (named `duplicate`: a name starting `copy` reaches Swift as `doCopy`) to [to] (null: the space's root) in its space; [deletedName] is how a copied
     * Pot names an erased person; the lists are read anew. Why not, or null once copied.
     */
    suspend fun duplicate(ref: NodeAt, to: String?, deletedName: String, uuid: () -> String): Notice? {
        val r = runCatching { SubtreeCopy.duplicate(tenant, ref.scopeId, ref.nodeId, to, AppKinds.copies(deletedName, uuid)) }.getOrNull()
        refresh()
        return Notices.subtreeCopyRefused(r?.outcome)
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

        /** The drawer's short lists (ADR 0122): six favorites, eight recent. */
        const val DRAWER_FAVORITES = 6
        const val DRAWER_RECENT = 8
    }
}
