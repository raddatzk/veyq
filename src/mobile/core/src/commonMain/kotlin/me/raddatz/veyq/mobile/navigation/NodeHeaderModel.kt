package me.raddatz.veyq.mobile.navigation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.raddatz.veyq.mobile.AppKinds
import me.raddatz.veyq.mobile.session.OpenNode
import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.Notice
import me.raddatz.veyq.mobile.words.Notices

/**
 * The header of one open node, for every kind (the web's `NodePage` + `NodeHeader`, ADR 0127 decision 2): its rights
 * composed here — the heart once the pin set is known, a link where the kind shares and the tenant's links are on, the
 * report where the server takes reports (read once per tenant), a guest's folder narrowed, the kind's doors from
 * [AppKinds.doors] over what its page knows ([update]) —, the plan and the words over [NodeHeader], and the actions core
 * owns on it:
 *
 * - **pin**: the heart turns at once and turns back when the write did not land, with its notice;
 * - **trash**: the undo is offered for [ShellModel.TRASH_UNDO_MS], which this model runs, and an open object of the node
 *   closes once it is gone;
 * - **rename**: why it did not land, or the notice that a link kept the old name (the rename stands).
 *
 * Each app draws [state] (or [watch]) and the dialogs; what it says is a [Notice] the actions return. The move, the copy,
 * the download, the share, the report and the cover stay the apps' doors for now (the header CR's later phases).
 */
class NodeHeaderModel(
    val node: NodeAt,
    facts: AppKinds.Facts?,
    private val door: Door,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    /** What the header reads and writes ([ShellModel.header] over a tenant, a test over its own). */
    interface Door {
        /** Whether [ref] is pinned; null while its space's pin set is not loaded. */
        fun isFavorite(ref: NodeAt): Boolean?
        fun inIdentityFolder(ref: NodeAt): Boolean
        /** Whether the tenant's links are on (its capability set). */
        fun linksOn(): Boolean
        /** Whether the tenant's server takes reports: asked of the server once per tenant. */
        suspend fun reportOffered(): Boolean
        /** Pins or unpins [ref]; false when it was not stored. */
        suspend fun toggleFavorite(ref: NodeAt): Boolean
        suspend fun trash(ref: NodeAt): ShellModel.Trashed
        /** [node] back from the trash under its name; why not, or null. */
        suspend fun undoTrash(node: OpenNode): Notice?
        suspend fun rename(ref: NodeAt, name: String): ShellModel.Renamed
        /** An open object of [ref] closes (an open Pot stops reading a node that is gone). */
        fun closeOpen(ref: NodeAt)
        /** After each write that went through, for what the app keeps around the lists (its rows' details). */
        fun changed()
    }

    data class State(
        /** Whether the node is pinned (turned at once, turned back on a failure); null while its pin set is not loaded. */
        val pinned: Boolean? = null,
        /** What is offered; null while the kind's page cannot tell yet (a Pot not read): no action then. */
        val rights: NodeHeader.Rights? = null,
        /** While a trash's undo is offered, what it says ("“x” moved to the trash."); null once its window passed. */
        val undo: Notice? = null,
        /** The node went to the trash (or was gone already): its page has left. */
        val gone: Boolean = false,
    )

    private var facts: AppKinds.Facts? = facts
    private var reportOffered = false
    /** What an undo brings back, while it is offered. */
    private var undoing: OpenNode? = null
    private var undoWindow: Job? = null

    private val _state = MutableStateFlow(State(pinned = door.isFavorite(node)).let { it.copy(rights = rights(it.pinned)) })
    val state: StateFlow<State> = _state.asStateFlow()
    val current: State get() = _state.value

    class Watch internal constructor(private val job: Job) {
        fun stop() = job.cancel()
    }

    /** Calls [listener] with the state now and with each change after (on a background thread). */
    fun watch(listener: (State) -> Unit): Watch = Watch(scope.launch { state.collect { listener(it) } })

    init {
        scope.launch {
            reportOffered = door.reportOffered()
            recompose()
        }
    }

    /** What the kind's page knows now (null: it cannot tell yet): the rights follow. */
    fun update(facts: AppKinds.Facts?) {
        this.facts = facts
        recompose()
    }

    private fun recompose() = _state.update { it.copy(rights = rights(it.pinned)) }

    private fun rights(pinned: Boolean?): NodeHeader.Rights? {
        val f = facts ?: return null
        val kind = AppKinds.header(node.kind)
        val doors = AppKinds.doors(node.kind, f)
        return NodeHeader.rights(
            node.kind, favoriteKnown = pinned != null, shareable = kind?.shareable == true, linksOn = door.linksOn(),
            rename = kind?.renames != false, takesCover = kind?.takesCover == true, coverOffered = doors.coverOffered,
            copy = doors.copies, download = doors.downloads, report = reportOffered, inGuestFolder = door.inIdentityFolder(node),
        )
    }

    /** The header's split for a bar of [maxButtons] buttons with the kind's [own] actions; null while nothing is offered yet. */
    fun plan(maxButtons: Int, own: List<NodeHeader.Action> = emptyList()): NodeHeader.Plan? =
        current.rights?.let { NodeHeader.plan(it, AppKinds.headerOrder(node.kind), maxButtons, own) }

    /** A core action's words ([NodeHeader.label]; the delete's its kind's title); null for an application's own. */
    fun label(id: String, hasCover: Boolean): Msg? =
        NodeHeader.label(id, node.kind, current.pinned, hasCover) ?: if (id == NodeHeader.DELETE) deleteTitle else null

    /** The delete's title in the kind's words (`deleteTitle`). */
    val deleteTitle: Msg get() = AppKinds.header(node.kind)?.deleteTitle ?: Msg.FOLDER_DELETE

    /** The delete's question, naming the node (`deleteQuestion.ts`). */
    fun deleteQuestion(): Notice = AppKinds.header(node.kind)?.deleteQuestion(node.name) ?: Notice(Msg.FOLDER_DELETE_CONFIRM, node.name)

    /** Pins or unpins the node: the heart turns at once and back when the write did not land; its notice then, or null. */
    suspend fun toggleFavorite(): Notice? {
        val was = current.pinned ?: return null
        _state.update { it.copy(pinned = !was).let { s -> s.copy(rights = rights(s.pinned)) } }
        val stored = door.toggleFavorite(node)
        door.changed()
        if (stored) return null
        _state.update { it.copy(pinned = door.isFavorite(node)).let { s -> s.copy(rights = rights(s.pinned)) } }
        return Notice(Msg.NODE_FAVORITE_FAILED)
    }

    /**
     * Moves the node with everything under it to its space's trash; what it says. Where it went, its undo is offered in
     * [State.undo] for [ShellModel.TRASH_UNDO_MS]; once it is gone, an open object of it closes.
     */
    suspend fun trash(): Notice {
        val r = door.trash(node)
        if (r.gone) {
            door.closeOpen(node)
            _state.update { it.copy(gone = true) }
        }
        door.changed()
        val before = r.before
        if (r.undoable && before != null) offerUndo(r.notice, before)
        return r.notice
    }

    private fun offerUndo(notice: Notice, node: OpenNode) {
        undoWindow?.cancel()
        undoing = node
        _state.update { it.copy(undo = notice) }
        undoWindow = scope.launch {
            delay(ShellModel.TRASH_UNDO_MS)
            endUndo()
        }
    }

    private fun endUndo() {
        undoing = null
        _state.update { it.copy(undo = null) }
    }

    /** The undo of the trash while it is offered: the node back under its name, as it was. Why not, or null. */
    suspend fun undoTrash(): Notice? {
        val node = undoing ?: return null
        undoWindow?.cancel()
        endUndo()
        val failed = runCatching { door.undoTrash(node) }.getOrElse { Notices.trashUndoFailed(node.name) }
        door.changed()
        if (failed == null) _state.update { it.copy(gone = false) }
        return failed
    }

    /** What a rename came to: why it did not land ([problem], the dialog asks again), or what to say once it did ([notice]). */
    class Renamed(val problem: Notice?, val notice: Notice?)

    /** Renames the node to [name] (its placement, then its open links' names); an open page follows it. */
    suspend fun rename(name: String): Renamed {
        val r = door.rename(node, name)
        door.changed()
        // renamed; a link that kept the old name is said (the rename stands)
        return Renamed(r.notice, if (r.linksFailed) Notice(Msg.SHARE_NAME_RESEAL_FAILED) else null)
    }
}
