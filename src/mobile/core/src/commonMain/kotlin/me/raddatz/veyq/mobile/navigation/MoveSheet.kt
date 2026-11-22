package me.raddatz.veyq.mobile.navigation


import kotlinx.coroutines.CoroutineDispatcher
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
 * The move and copy sheet of one node or of a level's selection, for every kind (the web's `MoveNodeDialog`,
 * `MoveNodesDialog` + `MoveBrowser` and `CopyNodesDialog`, ADR 0136 decision 2): the browser over [NodeMove]'s rules — it
 * opens at the nodes' place, shows one place at a time under a trail up to the space's root, lists the places one may go
 * into or place into (a place takes a selection only where it takes every node of it) and says why the place shown takes
 * nothing, naming the node that blocks it —, the guest folders' confirm before a move ends them, and the write:
 *
 * - **move**: where it went, and its **undo** offered for [NodeHeaderModel.TRASH_UNDO_MS], which this model runs (the move's
 *   window is a trash's, the header CR's D4) — the node moved back the same way, to where it was; none for a guest's
 *   folder moved out, which that move ended (nothing brings it back into its drop box);
 * - **copy**: by the kind's way ([AppKinds.copiesByOpenModel]: a Pot by its open model, everything else with what is under
 *   it, `SubtreeCopy`), with what it leaves behind and out said first, and a copy the tenant has no room for said with its
 *   size (the platform formats it) before anything is written.
 *
 * Several [nodes] (a level's selection, inbox #1049) go one after the other, a refusal stopping nothing: a node already at
 * the place is left out, the others' refusals are said by name after what went ([State.done], the web's summary as one
 * notice), one Undo moves back every node that went; a copy stops at its first failure, what it copied staying.
 *
 * Each app draws [state] (or [watch]); the sheet closes once [State.done] is set, and the app says it — with an Undo while
 * [State.undo] is.
 */
class MoveSheet(
    /** What is moved or copied: one node, or a level's selection in the level's order (never empty). */
    val nodes: List<NodeAt>,
    /** Copy rather than move. */
    val copying: Boolean,
    private val door: Door,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    /**
     * Where the reads and writes run: off the caller's thread. A test passes its own scheduler's dispatcher — on a real
     * thread, the undo window launched from inside [send] lets a test scheduler skip its delay before [send] has returned
     * (inbox #930): the window had closed by the time the test looked.
     */
    private val work: CoroutineDispatcher = Dispatchers.Default,
) {
    /** The sheet of one node. */
    constructor(node: NodeAt, copying: Boolean, door: Door, scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default), work: CoroutineDispatcher = Dispatchers.Default) :
        this(listOf(node), copying, door, scope, work)

    init {
        require(nodes.isNotEmpty()) { "a move sheet moves something" }
    }

    /** The first node (the one node of a single move): where the sheet's space is read. */
    val node: NodeAt get() = nodes.first()

    /** Whether it moves or copies a selection of more than one node: the sheet's words are the selection's. */
    val many: Boolean get() = nodes.size > 1

    /** What the sheet reads and writes (`ShellModel.moveSheet` over a tenant, a test over its own). */
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

        /**
         * The top of what the sheet walks (inbox #1063): null for a member — the space's root —, a link's root for its
         * READ_WRITE guest, who places into the link's folders only ([NodeMove]'s `top`; the guest's door,
         * `sharing/GuestMove`). [rootName] then names that root.
         */
        val top: String? get() = null
    }

    /** A place on the trail: the top ([nodeId] null: the space's root; a guest's link root by its id) or a node, with its name. */
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
        /** The node of a selection that blocks [at] (null for one node, and where all of them are there already). */
        val blockedBy: String? = null,
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
    private var moved: List<NodeRef> = emptyList()
    /** Where a move's undo puts each node that went back, while it is offered. */
    private class Back(val node: NodeAt, val from: String?)
    private var back: List<Back> = emptyList()
    private var undoWindow: Job? = null

    private val _state = MutableStateFlow(State(losses = if (copying) listOf(Msg.COPY_LOSS_ALWAYS) else emptyList()))
    val state: StateFlow<State> = _state.asStateFlow()
    val current: State get() = _state.value

    class Watch internal constructor(private val job: Job) {
        fun stop() = job.cancel()
    }

    /** Calls [listener] with the state now and with each change after (on a background thread). */
    fun watch(listener: (State) -> Unit): Watch = Watch(scope.launch { state.collect { listener(it) } })

    /** The sheet's title, naming the node (a selection: how many). */
    val title: Notice get() = when {
        many -> Notice(if (copying) Msg.COPY_MANY_TITLE else Msg.MOVE_MANY_TITLE, nodes.size)
        else -> Notice(if (copying) Msg.COPY_TITLE else Msg.MOVE_TITLE, node.name)
    }

    /** The line under the title. */
    val hint: Msg get() = when {
        many -> if (copying) Msg.COPY_MANY_HINT else Msg.MOVE_MANY_HINT
        else -> if (copying) Msg.COPY_HINT else Msg.MOVE_HINT
    }

    /** The button's words. */
    val submit: Msg get() = if (copying) Msg.COPY_SUBMIT else Msg.MOVE_SUBMIT

    /** What a place without children says. */
    val empty: Notice get() = if (many) Notice(Msg.MOVE_MANY_EMPTY) else Notice(Msg.MOVE_EMPTY, node.name)

    /** What the guest folders' confirm asks, naming each folder it ends. */
    val confirmQuestion: Notice get() {
        val ending = moved.filter { NodeMove.endsIdentity(it) && it.parentId != current.at }
        return if (ending.size > 1 || (many && ending.isNotEmpty())) Notice(Msg.MOVE_MANY_IDENTITY_CONFIRM, quoted(ending.map { it.name }))
        else Notice(Msg.MOVE_IDENTITY_CONFIRM, ending.firstOrNull()?.name ?: node.name)
    }

    /**
     * Why the place shown takes nothing, in the sheet's words (null: it takes them): one node's sentence, or a selection's
     * naming the node that blocks it (the web's `node.move.many.here.*`).
     */
    fun here(state: State): Notice? {
        val b = state.block ?: return null
        val name = state.blockedBy
        if (!many || (name == null && b != NodeMove.Block.CURRENT)) return Notice(NodeMove.hereWord(b, copying))
        return when (b) {
            NodeMove.Block.CURRENT -> Notice(Msg.MOVE_MANY_HERE_CURRENT)
            NodeMove.Block.ITSELF -> Notice(if (copying) Msg.COPY_MANY_HERE_SELF else Msg.MOVE_MANY_HERE_SELF, name.orEmpty())
            NodeMove.Block.INSIDE -> Notice(if (copying) Msg.COPY_MANY_HERE_INSIDE else Msg.MOVE_MANY_HERE_INSIDE, name.orEmpty())
            NodeMove.Block.KIND -> Notice(if (copying) Msg.COPY_MANY_HERE_KIND else Msg.MOVE_MANY_HERE_KIND, name.orEmpty())
            NodeMove.Block.IDENTITY_FOLDER -> Notice(if (copying) Msg.COPY_MANY_HERE_IDENTITY else Msg.MOVE_MANY_HERE_IDENTITY)
        }
    }

    /**
     * Reads the space's tree and opens at the node's place; a copy reads what it would leave behind and out, and whether the
     * tenant has room for it, meanwhile. The reads and writes run off the caller's thread (a UI's main one: they decrypt).
     */
    suspend fun load() = withContext(work) {
        if (copying) {
            // a selection's copy: what each node leaves behind and out, together; the first that has no room is said
            // (each is checked alone — the server refuses a copy that fits alone but not with the others, `quota_exceeded`)
            launch {
                val previews = nodes.mapNotNull { n -> runCatching { door.preview(n) }.getOrNull() }
                if (previews.isEmpty()) return@launch
                val kinds = previews.flatMap { it.kinds }.distinct()
                _state.update { it.copy(losses = listOf(Msg.COPY_LOSS_ALWAYS) + AppKinds.lossesOfCopy(kinds), leftOut = previews.flatMap { p -> p.leftOut.map(Notices::leftOut) }) }
            }
            launch {
                val bytes = nodes.firstNotNullOfOrNull { n -> runCatching { door.noRoom(n) }.getOrNull() } ?: return@launch
                _state.update { it.copy(noRoom = Notice(Msg.COPY_NO_ROOM, Bytes(bytes))) }
            }
        }
        val read = runCatching { door.tree(node.scopeId) }.getOrDefault(emptyList())
        val byId = read.associateBy { it.nodeId }
        val m = nodes.mapNotNull { byId[it.nodeId] }
        if (m.size != nodes.size) return@withContext
        tree = read
        moved = m
        show(NodeMove.start(read, m, door.top), loaded = true)
    }

    private fun show(at: String?, loaded: Boolean = current.loaded) {
        if (moved.isEmpty()) return
        val top = door.top
        val root = Crumb(top, door.rootName(node.scopeId))
        val trail = listOf(root) + NodeMove.trail(tree, at, top).drop(1).filterNotNull().map { Crumb(it.nodeId, Piece.Plain(it.name)) }
        val blocked = if (many) NodeMove.hereBlockOf(tree, moved, at, door.placement, copy = copying, top = top) else null
        _state.update {
            it.copy(
                loaded = loaded, at = at, trail = trail, problem = null,
                places = if (many) NodeMove.places(tree, moved, at, door.placement, top) else NodeMove.places(tree, moved.single(), at, door.placement, top),
                block = if (many) blocked?.block else NodeMove.hereBlock(tree, moved.single(), at, door.placement, copy = copying, top = top),
                blockedBy = blocked?.node?.name,
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
        if (moved.isEmpty()) return current
        if (!current.canChoose) return current
        if (!copying && moved.any { NodeMove.endsIdentity(it) && it.parentId != current.at }) {
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

    private suspend fun send(): State = withContext(work) { sending() }

    private suspend fun sending(): State {
        if (moved.isEmpty()) return current
        val to = current.at
        val toName = placeName(to)
        _state.update { it.copy(busy = true, problem = null) }
        if (copying) return copied(to, toName)
        if (!many) return movedOne(moved.single(), to, toName)
        // a selection: each node one after the other, a refusal stopping nothing; a node already there is left out
        val went = ArrayList<Back>()
        val lines = ArrayList<Notice>()
        for ((i, n) in moved.withIndex()) {
            if (n.parentId == to) continue
            val outcome = runCatching { door.move(nodes[i], to) }.getOrDefault(NodeMove.Outcome.FAILED)
            // a guest who declined to name itself sends nothing more (the web's `moveMany`: named once, for all of them)
            if (outcome == NodeMove.Outcome.DECLINED && went.isEmpty() && lines.isEmpty()) return declined()
            if (outcome == NodeMove.Outcome.MOVED) {
                if (!NodeMove.endsIdentity(n)) went += Back(nodes[i], n.parentId)
            } else {
                lines += Notice(Msg.SELECT_REASON, n.name, Notices.moveRefused(outcome) ?: Notice(Msg.MOVE_REFUSED_FAILED))
            }
        }
        door.changed()
        val count = moved.count { it.parentId != to } - lines.size
        if (count == 0) {
            _state.update { it.copy(busy = false, problem = summary(Notice(Msg.MOVE_MANY_NONE_MOVED), Msg.MOVE_MANY_NOT_MOVED, lines)) }
            return current
        }
        val said = summary(Notice(Msg.MOVE_MANY_MOVED, count, toName), Msg.MOVE_MANY_NOT_MOVED, lines)
        offerBack(went, said)
        return current
    }

    private suspend fun movedOne(m: NodeRef, to: String?, toName: Any): State {
        val outcome = runCatching { door.move(node, to) }.getOrDefault(NodeMove.Outcome.FAILED)
        if (outcome == NodeMove.Outcome.DECLINED) return declined()
        door.changed()
        if (outcome != NodeMove.Outcome.MOVED) {
            _state.update { it.copy(busy = false, problem = Notices.moveRefused(outcome) ?: Notice(Msg.MOVE_REFUSED_FAILED)) }
            return current
        }
        val said = Notice(Msg.MOVE_MOVED, node.name, toName)
        // a guest's folder moved out has ended: nothing brings it back into its drop box, so no undo is offered
        offerBack(if (NodeMove.endsIdentity(m)) emptyList() else listOf(Back(node, m.parentId)), said)
        return current
    }

    /** A guest declined to name itself: nothing was sent, nothing is said, the sheet stays where it was. */
    private fun declined(): State {
        _state.update { it.copy(busy = false) }
        return current
    }

    /** Done, saying [said]; where something went that can go back, its undo offered for the trash's window. */
    private fun offerBack(went: List<Back>, said: Notice) {
        if (went.isEmpty()) {
            _state.update { it.copy(busy = false, done = said.living(me.raddatz.veyq.mobile.words.NoticeKind.SAID)) }
            return
        }
        back = went
        undoWindow?.cancel()
        _state.update { it.copy(busy = false, done = said, undo = said.living(me.raddatz.veyq.mobile.words.NoticeKind.UNDO)) }
        undoWindow = scope.launch {
            delay(NodeHeaderModel.TRASH_UNDO_MS)
            endUndo()
        }
    }

    /** The copy: each node by its kind's way, in order; the first that fails stops it, what was copied stays and is said. */
    private suspend fun copied(to: String?, toName: Any): State {
        var count = 0
        for (n in nodes) {
            val why = runCatching {
                if (AppKinds.copiesByOpenModel(n.kind)) door.duplicateOpen(n, to) else door.duplicateSubtree(n, to)
            }.getOrElse { Notice(Msg.COPY_FAILED) }
            if (why != null) {
                door.changed()
                val problem = if (count == 0) why else Notice(Msg.SELECT_SUMMARY, Notice(Msg.COPY_MANY_COPIED, count, toName), why)
                _state.update { it.copy(busy = false, problem = problem) }
                return current
            }
            count++
        }
        door.changed()
        val done = if (many) Notice(Msg.COPY_MANY_COPIED, count, toName) else Notice(Msg.COPY_DONE, node.name, toName)
        _state.update { it.copy(busy = false, done = done.living(me.raddatz.veyq.mobile.words.NoticeKind.SAID)) }
        return current
    }

    private fun endUndo() {
        back = emptyList()
        _state.update { it.copy(undo = null) }
    }

    /** The move's undo while it is offered: every node that went moved back to where it was. Why not, or null. */
    suspend fun undo(): Notice? {
        val b = back.takeIf { it.isNotEmpty() } ?: return null
        undoWindow?.cancel()
        endUndo()
        val failed = withContext(work) {
            b.filter { runCatching { door.move(it.node, it.from) }.getOrDefault(NodeMove.Outcome.FAILED) != NodeMove.Outcome.MOVED }
        }
        door.changed()
        return when {
            failed.isEmpty() -> null
            b.size == 1 -> Notice(Msg.MOVE_UNDO_FAILED, failed.single().node.name)
            else -> Notice(Msg.MOVE_MANY_UNDO_FAILED, quoted(failed.map { it.node.name }))
        }
    }

    /** The name of place [at] as a notice takes it: the node's (a guest's link root: as [Door.rootName] names it), or the space's root's words. */
    private fun placeName(at: String?): Any = at?.takeIf { it != door.top }?.let { a -> tree.firstOrNull { it.nodeId == a }?.name }
        ?: when (val root = door.rootName(node.scopeId)) {
            is Piece.Said -> root.notice
            is Piece.Plain -> root.text
            else -> ""
        }

    private companion object {
        /** Several names as the selection's sentences list them: each quoted, one after another. */
        fun quoted(names: List<String>): me.raddatz.veyq.mobile.words.Joined =
            me.raddatz.veyq.mobile.words.Joined(names.map { Notice(Msg.SELECT_QUOTED, it) }, ", ")

        /** [said] followed by what was not done ([heading] over [lines]), as the web's summary says it — held until closed, a
         *  refusal among it; [said] alone without lines. */
        fun summary(said: Notice, heading: Msg, lines: List<Notice>): Notice =
            if (lines.isEmpty()) said
            else Notice(Msg.SELECT_SUMMARY, said, Notice(heading, me.raddatz.veyq.mobile.words.Joined(lines, " ")))
    }
}
