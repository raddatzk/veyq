package me.raddatz.veyq.mobile.navigation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.raddatz.veyq.mobile.session.NodeTrash
import me.raddatz.veyq.mobile.words.Msg

/**
 * A space's trash as both apps' sheet shows it (the web's `SpaceTrashDialog.vue`, ADR 0127): the root of each deleted
 * subtree; Restore brings it back complete — where something took its name meanwhile, the row asks for another and
 * restores under it —, "Delete forever" erases it after a question that names it. A row someone else restored or purged
 * meanwhile leaves through a re-read. [changed] runs after each write that went through, for the lists around.
 */
class TrashSheet(private val door: Door, private val changed: () -> Unit) {
    /** The trash's reads and writes ([ShellModel.trashSheet] over a tenant, a test over its own). */
    interface Door {
        suspend fun list(): List<NodeTrash.Trashed>?
        fun canRestore(row: NodeTrash.Trashed): Boolean
        suspend fun restore(row: NodeTrash.Trashed, newName: String?): NodeTrash.Restored
        suspend fun purge(row: NodeTrash.Trashed): NodeTrash.Purged
    }

    data class State(
        /** The rows; null while the first read runs. */
        val rows: List<NodeTrash.Trashed>? = null,
        val failed: Boolean = false,
        /** The row a write runs on. */
        val busy: String? = null,
        /** The row an error stands under, and the error. */
        val errorRow: String? = null,
        val error: Msg? = null,
        /** The row that asks for another name, and the name typed. */
        val renaming: String? = null,
        val retryName: String = "",
        /** The row whose "Delete forever" asks first. */
        val purging: NodeTrash.Trashed? = null,
    ) {
        /** Restoring under the typed name is offered: a name typed and no write running on the row. */
        fun retryable(row: NodeTrash.Trashed): Boolean = busy != row.id && retryName.isNotBlank()
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    val current: State get() = _state.value

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    class Watch internal constructor(private val job: kotlinx.coroutines.Job) {
        fun stop() = job.cancel()
    }

    fun watch(listener: (State) -> Unit): Watch = Watch(scope.launch { state.collect { listener(it) } })

    /** Whether the app can bring [row] back: its kind's fold of a name is known here. */
    fun canRestore(row: NodeTrash.Trashed): Boolean = door.canRestore(row)

    suspend fun load() {
        val read = door.list()
        _state.update { if (read == null) it.copy(failed = true) else it.copy(rows = read, failed = false) }
    }

    /** Brings [row] back, under [newName] where its own was taken meanwhile. */
    suspend fun restore(row: NodeTrash.Trashed, newName: String? = null) {
        _state.update { it.copy(errorRow = null, error = null, busy = row.id) }
        when (door.restore(row, newName)) {
            NodeTrash.Restored.RESTORED -> {
                _state.update { s -> s.copy(renaming = null, rows = s.rows?.filterNot { it.id == row.id }) }
                changed()
                load() // a child whose own name an earlier delete freed stays: a root of its own now
            }
            NodeTrash.Restored.NAME_TAKEN ->
                _state.update { it.copy(errorRow = row.id, error = Msg.NODE_SLUG_TAKEN, renaming = row.id, retryName = newName ?: row.name) }
            else -> _state.update { it.copy(errorRow = row.id, error = Msg.TRASH_RESTORE_FAILED) }
        }
        _state.update { it.copy(busy = null) }
    }

    /** Restores the row asking for another name under the name typed. */
    suspend fun retry(row: NodeTrash.Trashed) = restore(row, current.retryName)

    fun type(name: String) = _state.update { it.copy(retryName = name) }
    fun cancelRename() = _state.update { it.copy(renaming = null) }
    fun askPurge(row: NodeTrash.Trashed) = _state.update { it.copy(purging = row) }
    fun cancelPurge() = _state.update { it.copy(purging = null) }

    /**
     * Erases [row] for good, the one the person confirmed: the row is handed in, never read back from [State.purging],
     * since a dialog may clear its question before its button's action runs (Apple's `confirmationDialog` did, so its
     * "Delete forever" sent nothing; header CR Phase 1). The question is over either way.
     */
    suspend fun purge(row: NodeTrash.Trashed) {
        _state.update { it.copy(purging = null, errorRow = null, error = null, busy = row.id) }
        when (door.purge(row)) {
            NodeTrash.Purged.PURGED -> {
                _state.update { s -> s.copy(rows = s.rows?.filterNot { it.id == row.id }) }
                changed()
            }
            NodeTrash.Purged.GONE -> load()
            NodeTrash.Purged.FAILED -> _state.update { it.copy(errorRow = row.id, error = Msg.TRASH_PURGE_FAILED) }
        }
        _state.update { it.copy(busy = null) }
    }

    /** The row listed under [id], for a host that names rows by id (the bridge); null once it left the list. */
    fun row(id: String): NodeTrash.Trashed? = current.rows?.firstOrNull { it.id == id }
}
