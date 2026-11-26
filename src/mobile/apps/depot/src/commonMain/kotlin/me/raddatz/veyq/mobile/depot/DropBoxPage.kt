package me.raddatz.veyq.mobile.depot

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.raddatz.veyq.mobile.AppKinds
import me.raddatz.veyq.mobile.navigation.NodeAt
import me.raddatz.veyq.mobile.session.LivePoll

/**
 * A drop box's page for a member (Depot's `DropBoxDetail.vue`, `docs/apps/depot.md` § The drop box; ADR 0161 decision 2):
 * its guests' folders ([DropBoxes.folders]) read anew on every revision of the shell's lists — one trigger for every app,
 * whatever changed them (a move out, a delete, an upload, another device) —, a folder moved out through the move sheet
 * ([at] the node it opens on) and a folder's delete asked first ([askDelete]) and then done through the header's trash
 * ([delete] hands the node; the header is built with [folderFacts], the core's). While a screen shows it, its [live] poll asks
 * whether the scope's tree moved — a guest's new folder among it — and the lists are read anew where it did, which the page
 * follows (the gallery's follow, ADR 0081).
 *
 * Each app draws [state] (or [watch]) and runs [live] while the page shows; [close] when the page goes.
 */
class DropBoxPage(
    val node: NodeAt,
    private val door: Door,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val now: () -> Long = monotonicMs(),
) {
    /** What the page reads (`ShellModel.dropBoxPage`, a test's played tree). */
    interface Door {
        /** The guests' folders as listed now; null where they did not read. */
        suspend fun folders(): List<DropBoxes.GuestFolder>?

        /** Each revision of the shell's lists (its first value: the lists as they are when the page opens). */
        val revisions: Flow<Any?>

        /**
         * Whether the scope's tree moved since it was read (a conditional `listNodes`, a `304` is no), the shell's lists read
         * anew where it did — which [revisions] brings here. A throw where the read failed. Absent: never.
         */
        suspend fun treeMoved(): Boolean = false
    }

    data class State(
        /** The guests' folders in the order they came; null while the first read runs. */
        val folders: List<DropBoxes.GuestFolder>? = null,
        /** The folders did not read (the last list read stays shown). */
        val failed: Boolean = false,
        /** The folder whose delete is asked ([delete] does it, [cancelAsk] does not). */
        val deleting: DropBoxes.GuestFolder? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    val current: State get() = _state.value

    class Watch internal constructor(private val job: Job) {
        fun stop() = job.cancel()
    }

    /** Calls [listener] with the state now and with each change after (on a background thread). */
    fun watch(listener: (State) -> Unit): Watch = Watch(scope.launch { state.collect { listener(it) } })

    private val reading = Mutex()

    init {
        scope.launch { door.revisions.collect { read() } }
    }

    private suspend fun read() = reading.withLock {
        val read = runCatching { door.folders() }.getOrNull()
        _state.update { s ->
            if (read == null) s.copy(failed = true)
            else s.copy(folders = read, failed = false, deleting = s.deleting?.takeIf { d -> read.any { it.node.id == d.node.id } })
        }
    }

    /** The header facts of a guest's folder for a member (Depot's rights matrix: nothing written into it, its ZIP downloaded). */
    val folderFacts: AppKinds.Facts get() = FOLDER_FACTS

    /** The node [folder] is, under its label: what the move sheet opens on (a move out) and the header trashes. */
    fun at(folder: DropBoxes.GuestFolder): NodeAt = NodeAt(folder.node.scopeId, folder.node.id, folder.node.kind, folder.label)

    /** "Delete {name}" on [folder]: asked first. */
    fun askDelete(folder: DropBoxes.GuestFolder) = _state.update { it.copy(deleting = folder) }

    /** The question declined: nothing is trashed. */
    fun cancelAsk() = _state.update { it.copy(deleting = null) }

    /**
     * The delete asked, answered for [folder]: the node to trash through the header ([folderFacts]), or null where this
     * folder's delete was not asked. The question is over either way; only the app's Cancel ends it before ([cancelAsk]),
     * never a dialog's own dismissal, which may run before its button's action (the share sheet's and trash's trap).
     */
    fun delete(folder: DropBoxes.GuestFolder): NodeAt? {
        val asked = current.deleting?.node?.id == folder.node.id
        _state.update { it.copy(deleting = null) }
        return if (asked) at(folder) else null
    }

    private val closed = MutableStateFlow(false)

    /**
     * The page's live poll (core's [LivePoll] at the item lists' cadence) for as long as the caller runs it — a screen while
     * it shows the page, so nothing is asked in the background: each tick asks [Door.treeMoved]. It ends when the page closes.
     */
    suspend fun live() {
        if (closed.value) return
        val poll = LivePoll(now = now)
        coroutineScope {
            val ticking = launch {
                poll.run {
                    door.treeMoved()
                    LivePoll.Tick.DONE
                }
            }
            closed.first { it }
            ticking.cancel()
        }
    }

    /** [live] in the page's own scope, for a platform that cannot run a suspend loop: stopped with [Watch.stop] or [close]. */
    fun startLive(): Watch = Watch(scope.launch { live() })

    /** The page went: nothing more is read, watched or polled. */
    fun close() {
        closed.value = true
        scope.cancel()
    }

    companion object {
        /** A guest's folder for a member: no cover or write into it (an identity folder takes none), its download whole. */
        val FOLDER_FACTS = AppKinds.Facts(canWrite = false, hasContent = true)

        private fun monotonicMs(): () -> Long {
            val start = kotlin.time.TimeSource.Monotonic.markNow()
            return { start.elapsedNow().inWholeMilliseconds }
        }
    }
}
