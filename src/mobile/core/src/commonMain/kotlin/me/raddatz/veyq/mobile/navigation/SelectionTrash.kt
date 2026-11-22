package me.raddatz.veyq.mobile.navigation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.raddatz.veyq.mobile.AppKinds
import me.raddatz.veyq.mobile.session.NodeMove
import me.raddatz.veyq.mobile.session.NodeTrash
import me.raddatz.veyq.mobile.session.OpenNode
import me.raddatz.veyq.mobile.words.Joined
import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.Notice
import me.raddatz.veyq.mobile.words.NoticeKind
import me.raddatz.veyq.mobile.words.Notices

/**
 * The delete of a level's selection (the web's `DeleteNodesDialog.vue` and `deleteSelection.ts`, inbox #1049): ONE
 * question for every node it reaches ([title], [question] — one node is asked that node's own question, its kind's words),
 * then each node into the space's trash one after the other through core's own delete (`NodeTrash.delete`), a failure
 * stopping nothing; ONE notice says what went and names each node that did not with its reason, and ONE Undo
 * ([State.undo], for [NodeHeaderModel.TRASH_UNDO_MS]) restores exactly the nodes that went (`NodeTrash.restore`, as the
 * trash's own undo). No batch request: a node's delete already is one soft delete of its whole subtree (ADR 0061), so the
 * server sees what N single deletes send. Each app asks the question, calls [trash], says its answer with the Undo while
 * [State.undo] holds it, and calls [undo] from it.
 */
class SelectionTrash(
    /** What is deleted: the selected rows the delete reaches, in the level's order (never empty). */
    val nodes: List<NodeAt>,
    private val door: Door,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    /** The writes (`ShellModel.selectionTrash` over a tenant, a test over its own). */
    interface Door {
        /** One node into the trash, as a row's delete sends it. */
        suspend fun trash(ref: NodeAt): NodeHeaderModel.Trashed
        /** [node] back from the trash; why not, or null. */
        suspend fun undoTrash(node: OpenNode): Notice?
        /** After the writes, for what the app keeps around the lists. */
        fun changed()
    }

    init {
        require(nodes.isNotEmpty()) { "a delete deletes something" }
    }

    data class State(
        /** The deletes run: the question holds still. */
        val busy: Boolean = false,
        /** What came of it, once it ran (the notice an app says). */
        val said: Notice? = null,
        /** While the undo is offered, what it says; null once its window passed. */
        val undo: Notice? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    val current: State get() = _state.value

    class Watch internal constructor(private val job: Job) {
        fun stop() = job.cancel()
    }

    /** Calls [listener] with the state now and with each change after (on a background thread). */
    fun watch(listener: (State) -> Unit): Watch = Watch(scope.launch { state.collect { listener(it) } })

    private val one: NodeAt? get() = nodes.singleOrNull()

    /** The question's title: one node's own (its kind's `deleteTitle`), else how many (the web's `node.deleteMany.title`). */
    val title: Notice get() = one?.let { Notice(AppKinds.header(it.kind)?.deleteTitle ?: Msg.FOLDER_DELETE) } ?: Notice(Msg.DELETE_MANY_TITLE, nodes.size)

    /**
     * The question: one node's own (its kind's `deleteQuestion`), else how many go and of which kinds — each kind's noun
     * with its article once, in the selection's order (`AppKinds.noun`, a folder's `node.aFolder`, else "an item").
     */
    val question: Notice get() = one?.let { AppKinds.header(it.kind)?.deleteQuestion(it.name) ?: Notice(Msg.FOLDER_DELETE_CONFIRM, it.name) }
        ?: Notice(Msg.DELETE_MANY_COPY, nodes.size, kinds(nodes.map { it.kind }))

    /** What goes back while the undo is offered. */
    private var gone: List<OpenNode> = emptyList()
    private var undoWindow: Job? = null

    /**
     * Each node into the trash, in order; what it came to ([State.said]), its undo offered where something went that can
     * come back. Once only: a second call answers what the first said.
     */
    suspend fun trash(): Notice {
        current.said?.let { return it }
        if (current.busy) return current.said ?: Notice(Msg.DELETE_REFUSED_FAILED)
        _state.value = current.copy(busy = true)
        val results = withContext(Dispatchers.Default) { nodes.map { it to runCatching { door.trash(it) }.getOrNull() } }
        door.changed()
        val one = one
        if (one != null) {
            val r = results.single().second
            val said = r?.notice ?: Notices.trashed(NodeTrash.Deleted.FAILED, one.name)
            val before = r?.before
            if (r != null && r.undoable && before != null) offer(said, listOf(before)) else _state.value = State(said = said)
            return said
        }
        val lines = ArrayList<Notice>()
        val back = ArrayList<OpenNode>()
        var went = 0
        for ((n, r) in results) {
            if (r != null && r.outcome == NodeTrash.Deleted.TRASHED) {
                went++
                r.before?.let { back += it }
            } else {
                lines += Notice(Msg.SELECT_REASON, n.name, Notices.trashed(r?.outcome ?: NodeTrash.Deleted.FAILED, n.name))
            }
        }
        val deleted = if (went > 0) Notice(Msg.DELETE_MANY_MOVED, went) else Notice(Msg.DELETE_MANY_NONE)
        val said = if (lines.isEmpty()) deleted else Notice(Msg.SELECT_SUMMARY, deleted, Notice(Msg.DELETE_MANY_NOT_DELETED, Joined(lines, " ")))
        if (back.isNotEmpty()) offer(said, back) else _state.value = State(said = said.living(if (went > 0 && lines.isEmpty()) NoticeKind.SAID else said.kind))
        return current.said ?: said
    }

    private fun offer(said: Notice, nodes: List<OpenNode>) {
        gone = nodes
        _state.value = State(said = said, undo = said.living(NoticeKind.UNDO))
        undoWindow?.cancel()
        undoWindow = scope.launch {
            delay(NodeHeaderModel.TRASH_UNDO_MS)
            endUndo()
        }
    }

    private fun endUndo() {
        gone = emptyList()
        _state.value = current.copy(undo = null)
    }

    /** The undo while it is offered: every node that went back under its name, as it was. Why not, or null. */
    suspend fun undo(): Notice? {
        val back = gone.takeIf { it.isNotEmpty() } ?: return null
        undoWindow?.cancel()
        endUndo()
        val failed = withContext(Dispatchers.Default) {
            back.filter { n -> runCatching { door.undoTrash(n) }.getOrElse { Notices.trashUndoFailed(n.name) } != null }
        }
        door.changed()
        return when {
            failed.isEmpty() -> null
            back.size == 1 -> Notices.trashUndoFailed(failed.single().name)
            else -> Notice(Msg.DELETE_MANY_UNDO_FAILED, Joined(failed.map { Notice(Msg.SELECT_QUOTED, it.name) }, ", "))
        }
    }

    companion object {
        /** The kinds of [kinds], each once in their order, as nouns with their article (the web's `kinds`). */
        fun kinds(kinds: List<String>): Joined =
            Joined(kinds.map { k -> AppKinds.noun(k) ?: if (k == NodeMove.FOLDER) Msg.NODE_A_FOLDER else Msg.NODE_AN_ITEM }.distinct().map { Notice(it) }, ", ")
    }
}
