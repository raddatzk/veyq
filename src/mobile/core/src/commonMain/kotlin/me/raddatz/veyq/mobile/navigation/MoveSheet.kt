package me.raddatz.veyq.mobile.navigation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.raddatz.veyq.mobile.AppKinds
import me.raddatz.veyq.mobile.export.SubtreeCopy
import me.raddatz.veyq.mobile.session.NodeMove
import me.raddatz.veyq.mobile.words.Bytes
import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.Notice
import me.raddatz.veyq.mobile.words.Notices
import me.raddatz.veyq.mobile.words.Piece

/**
 * The move and copy sheet of one node, for every kind (the web's `MoveNodeDialog` + `MoveBrowser` and `CopyNodesDialog`,
 * ADR 0127 decision 2): the browser over [NodeMove]'s rules — it opens at the node's place, shows one place at a time under
 * a trail up to the space's root, lists the places one may go into or place into and says why the place shown takes
 * nothing —, the guest folder's confirm before a move ends it, and the write:
 *
 * - **move**: where it went, and its **undo** offered for [ShellModel.TRASH_UNDO_MS], which this model runs (the move's
 *   window is a trash's, the header CR's D4) — the node moved back the same way, to where it was; none for a guest's
 *   folder moved out, which that move ended (nothing brings it back into its drop box);
 * - **copy**: by the kind's way ([AppKinds.copiesByOpenModel]: a Pot by its open model, everything else with what is under
 *   it, `SubtreeCopy`), with what it leaves behind and out said first, and a copy the tenant has no room for said with its
 *   size (the platform formats it) before anything is written.
 *
 * Each app draws [state] (or [watch]); the sheet closes once [State.done] is set, and the app says it — with an Undo while
 * [State.undo] is.
 */
class MoveSheet(
    val node: NodeAt,
    /** Copy rather than move. */
    val copying: Boolean,
    private val door: Door,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    /** What the sheet reads and writes ([ShellModel.moveSheet] over a tenant, a test over its own). */
    interface Door {
        /** Every node of [scopeId] this device opens: what the browser walks. */
        suspend fun tree(scopeId: String): List<NodeRef>
        /** Where a node of a kind may be placed (the build's kinds). */
        val placement: NodeMove.PlacementAllows
        /** The space's root as the trail and the notices name it. */
        fun rootName(scopeId: String): Piece
        suspend fun move(ref: NodeAt, to: String?): NodeMove.Outcome
        /** The copy by the kind's open model (an open Pot's): why not, or null once copied. */
        suspend fun duplicateOpen(ref: NodeAt, to: String?): Notice?
        /** The copy with everything under it (`SubtreeCopy`): why not, or null once copied. */
        suspend fun duplicateSubtree(ref: NodeAt, to: String?): Notice?
        /** What a copy would make and leave out, before anything is written; null when it could not be read. */
        suspend fun preview(ref: NodeAt): SubtreeCopy.Preview?
        /** The bytes a copy would store when the tenant has no room for them; null where it fits or is not known. */
        suspend fun noRoom(ref: NodeAt): Long?
        /** After each write, for what the app keeps around the lists (its rows' details). */
        fun changed()
    }

    /** A place on the trail: the space's root ([nodeId] null) or a node, with its name. */
    data class Crumb(val nodeId: String?, val name: Piece)

    data class State(
        /** Whether the space's tree is read (and holds the node): the browser shows nothing before. */
        val loaded: Boolean = false,
        /** The place shown (null: the space's root). */
        val at: String? = null,
        /** The trail from the root down to [at]: each but the last can be chosen. */
        val trail: List<Crumb> = emptyList(),
        /** What [at] lists: each child one may go into or place into, the node itself and what is below it blocked. */
        val places: List<NodeMove.Place> = emptyList(),
        /** Why [at] takes nothing (null: it does). */
        val block: NodeMove.Block? = null,
        /** Why the last write did not go through. */
        val problem: Notice? = null,
        /** A copy the tenant has no room for, with its size: nothing can be chosen then. */
        val noRoom: Notice? = null,
        /** What a copy leaves behind: core's line, then its kinds' (`AppKinds.lossesOfCopy`); empty for a move. */
        val losses: List<Msg> = emptyList(),
        /** What a copy leaves out ([Notices.leftOut]). */
        val leftOut: List<Notice> = emptyList(),
        /** A write is under way. */
        val busy: Boolean = false,
        /** The guest folder's confirm is asked ([NodeMove.endsIdentity]): [confirm] sends, [cancelConfirm] does not. */
        val confirming: Boolean = false,
        /** Moved or copied: what it says; the sheet closes. */
        val done: Notice? = null,
        /** While a move's undo is offered, what it says; null once its window passed. */
        val undo: Notice? = null,
    ) {
        /** Whether "Move here" / "Copy here" can be chosen. */
        val canChoose: Boolean get() = loaded && block == null && noRoom == null && !busy && done == null
    }

    private var tree: List<NodeRef> = emptyList()
    private var moved: NodeRef? = null
    /** Where a move's undo puts the node back, while it is offered. */
    private class Back(val from: String?)
    private var back: Back? = null
    private var undoWindow: Job? = null

    private val _state = MutableStateFlow(State(losses = if (copying) listOf(Msg.COPY_LOSS_ALWAYS) else emptyList()))
    val state: StateFlow<State> = _state.asStateFlow()
    val current: State get() = _state.value

    class Watch internal constructor(private val job: Job) {
        fun stop() = job.cancel()
    }

    /** Calls [listener] with the state now and with each change after (on a background thread). */
    fun watch(listener: (State) -> Unit): Watch = Watch(scope.launch { state.collect { listener(it) } })

    /** The sheet's title, naming the node. */
    val title: Notice get() = Notice(if (copying) Msg.COPY_TITLE else Msg.MOVE_TITLE, node.name)

    /** The line under the title. */
    val hint: Msg get() = if (copying) Msg.COPY_HINT else Msg.MOVE_HINT

    /** The button's words. */
    val submit: Msg get() = if (copying) Msg.COPY_SUBMIT else Msg.MOVE_SUBMIT

    /** What a place without children says. */
    val empty: Notice get() = Notice(Msg.MOVE_EMPTY, node.name)

    /** What the guest folder's confirm asks. */
    val confirmQuestion: Notice get() = Notice(Msg.MOVE_IDENTITY_CONFIRM, node.name)

    /** Why the place shown takes nothing, in the sheet's words (null: it takes the node). */
    fun hereWord(state: State): Msg? = state.block?.let { NodeMove.hereWord(it, copying) }

    /**
     * Reads the space's tree and opens at the node's place; a copy reads what it would leave behind and out, and whether the
     * tenant has room for it, meanwhile. The reads and writes run off the caller's thread (a UI's main one: they decrypt).
     */
    suspend fun load() = withContext(Dispatchers.Default) {
        if (copying) {
            launch {
                val p = runCatching { door.preview(node) }.getOrNull() ?: return@launch
                _state.update { it.copy(losses = listOf(Msg.COPY_LOSS_ALWAYS) + AppKinds.lossesOfCopy(p.kinds.toList()), leftOut = p.leftOut.map(Notices::leftOut)) }
            }
            launch {
                val bytes = runCatching { door.noRoom(node) }.getOrNull() ?: return@launch
                _state.update { it.copy(noRoom = Notice(Msg.COPY_NO_ROOM, Bytes(bytes))) }
            }
        }
        val read = runCatching { door.tree(node.scopeId) }.getOrDefault(emptyList())
        val m = read.firstOrNull { it.nodeId == node.nodeId } ?: return@withContext
        tree = read
        moved = m
        show(NodeMove.start(read, m), loaded = true)
    }

    private fun show(at: String?, loaded: Boolean = current.loaded) {
        val m = moved ?: return
        val root = Crumb(null, door.rootName(node.scopeId))
        val trail = listOf(root) + NodeMove.trail(tree, at).drop(1).filterNotNull().map { Crumb(it.nodeId, Piece.Plain(it.name)) }
        _state.update {
            it.copy(
                loaded = loaded, at = at, trail = trail, problem = null,
                places = NodeMove.places(tree, m, at, door.placement),
                block = NodeMove.hereBlock(tree, m, at, door.placement, copy = copying),
            )
        }
    }

    /**
     * Shows place [at] (null: the space's root): a crumb of the trail, or a place listed that can be opened — never the
     * node itself, what lies below it or a place not listed (a guest's folder).
     */
    fun go(at: String?) {
        val s = current
        if (!s.loaded || s.busy || s.done != null) return
        val reachable = s.trail.any { it.nodeId == at } || s.places.any { it.node.nodeId == at && it.blocked == null }
        if (reachable) show(at)
    }

    /** One place up the trail (at the root: nothing). */
    fun up() {
        val trail = current.trail
        if (trail.size > 1) go(trail[trail.size - 2].nodeId)
    }

    /**
     * "Move here" / "Copy here" at the place shown: a move that ends a guest's folder asks first ([State.confirming]), else
     * it is sent. The state once it answered.
     */
    suspend fun choose(): State {
        val m = moved ?: return current
        if (!current.canChoose) return current
        if (!copying && NodeMove.endsIdentity(m)) {
            _state.update { it.copy(confirming = true) }
            return current
        }
        return send()
    }

    /** The guest folder's confirm answered: the move is sent. */
    suspend fun confirm(): State {
        if (!current.confirming) return current
        _state.update { it.copy(confirming = false) }
        if (!current.canChoose) return current
        return send()
    }

    /** The guest folder's confirm declined: nothing is sent. */
    fun cancelConfirm() = _state.update { it.copy(confirming = false) }

    private suspend fun send(): State = withContext(Dispatchers.Default) { sending() }

    private suspend fun sending(): State {
        val m = moved ?: return current
        val to = current.at
        val from = m.parentId
        val toName = placeName(to)
        _state.update { it.copy(busy = true, problem = null) }
        if (copying) {
            val why = runCatching {
                if (AppKinds.copiesByOpenModel(node.kind)) door.duplicateOpen(node, to) else door.duplicateSubtree(node, to)
            }.getOrElse { Notice(Msg.COPY_FAILED) }
            door.changed()
            _state.update { if (why == null) it.copy(busy = false, done = Notice(Msg.COPY_DONE, node.name, toName)) else it.copy(busy = false, problem = why) }
            return current
        }
        val outcome = runCatching { door.move(node, to) }.getOrDefault(NodeMove.Outcome.FAILED)
        door.changed()
        if (outcome != NodeMove.Outcome.MOVED) {
            _state.update { it.copy(busy = false, problem = Notices.moveRefused(outcome) ?: Notice(Msg.MOVE_REFUSED_FAILED)) }
            return current
        }
        val said = Notice(Msg.MOVE_MOVED, node.name, toName)
        // a guest's folder moved out has ended: nothing brings it back into its drop box, so no undo is offered
        if (NodeMove.endsIdentity(m)) {
            _state.update { it.copy(busy = false, done = said) }
            return current
        }
        back = Back(from)
        undoWindow?.cancel()
        _state.update { it.copy(busy = false, done = said, undo = said) }
        undoWindow = scope.launch {
            delay(ShellModel.TRASH_UNDO_MS)
            endUndo()
        }
        return current
    }

    private fun endUndo() {
        back = null
        _state.update { it.copy(undo = null) }
    }

    /** The move's undo while it is offered: the node moved back to where it was. Why not, or null. */
    suspend fun undo(): Notice? {
        val b = back ?: return null
        undoWindow?.cancel()
        endUndo()
        val outcome = withContext(Dispatchers.Default) { runCatching { door.move(node, b.from) }.getOrDefault(NodeMove.Outcome.FAILED) }
        door.changed()
        return if (outcome == NodeMove.Outcome.MOVED) null else Notice(Msg.MOVE_UNDO_FAILED, node.name)
    }

    /** The name of place [at] as a notice takes it: the node's, or the space's root's words. */
    private fun placeName(at: String?): Any = at?.let { a -> tree.firstOrNull { it.nodeId == a }?.name }
        ?: when (val root = door.rootName(node.scopeId)) {
            is Piece.Said -> root.notice
            is Piece.Plain -> root.text
            else -> ""
        }
}
