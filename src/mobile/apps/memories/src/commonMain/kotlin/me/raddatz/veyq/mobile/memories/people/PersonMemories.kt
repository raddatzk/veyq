package me.raddatz.veyq.mobile.memories.people

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import me.raddatz.veyq.mobile.memories.place.MemoriesMap
import me.raddatz.veyq.mobile.navigation.ApplicationId
import me.raddatz.veyq.mobile.navigation.ObjectRef
import me.raddatz.veyq.mobile.navigation.SpaceRef

/**
 * Memories' section of a person's profile (ADR 0144 decision 13; the web's `PersonMemories.vue`): "Memories with {name}"
 * — the Memories [userId] takes part in that the member may open ([ParticipantIndex]), as the level's rows (cover, name,
 * date range, place, the group's name), **newest start day first, undated last, then by name** ([order]) — the one list
 * of the apps ordered by date, not by last use (ADR 0122 decision 5) —, a row opening its Memory with its use recorded.
 *
 * A switch offers **List | Tiles | Map** once there is something to list: the map is ADR 0143's Memories map fed the
 * person's located Memories (its consent, its clusters, its "No places yet", its "needs a connection" are the map's own); a
 * pin opens its Memory. The choice is the account's one view choice on this device (ADR 0149 decision 8, [Door.chosen],
 * `navigation/ViewModes`), shared with every level. Four
 * states as the web's: loading, failed (with *Try again*: [load] again), empty, the list — [State.partial] where the index
 * is.
 */
class PersonMemories(val userId: String, private val door: Door) {
    interface Door {
        /** The tenant's participants index ([ParticipantIndex.of]); throws where no tree reads. */
        suspend fun index(): ParticipantIndex.Index

        /** Memory [ref] opened in its application, its use recorded (`ShellModel.open`). */
        fun open(ref: ObjectRef)

        /** The account's view choice on this device as the section opens (`ViewModes.of`); a test's: the list. */
        val chosen: View get() = View.LIST

        /** [view] chosen: kept as the account's one choice (`ViewModes.set`). */
        fun chose(view: View) {}
    }

    enum class View { LIST, TILES, MAP }

    /** One row: the Memory, its date and place as sealed, and the group it lies in (null in the tenant's space). */
    data class Row(val ref: ObjectRef, val date: me.raddatz.veyq.mobile.memories.place.MemoryDate?, val placeName: String?, val group: SpaceRef?)

    data class State(
        val loading: Boolean = true,
        val failed: Boolean = false,
        val rows: List<Row> = emptyList(),
        /** Some Memories could not be read, or there were more than are searched at once: the list may be incomplete. */
        val partial: Boolean = false,
        val view: View = View.LIST,
        /** The person's located Memories as pins: what [View.MAP] draws. */
        val map: MemoriesMap = MemoriesMap(emptyList()),
    ) {
        /** Nobody the member can open lists the person. */
        val empty: Boolean get() = !loading && !failed && rows.isEmpty()

        /** The List | Map switch: once there is something to list (none while loading, failed or empty, as the web's). */
        val offersViews: Boolean get() = !loading && !failed && rows.isNotEmpty()
    }

    private val _state = MutableStateFlow(State(view = door.chosen))
    val state: StateFlow<State> = _state.asStateFlow()
    val current: State get() = _state.value

    private var generation = 0

    /** The section read (again: *Try again*); the view stays where it was. */
    suspend fun load() {
        val mine = ++generation
        _state.update { it.copy(loading = true, failed = false) }
        val index = try {
            door.index()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            if (mine == generation) _state.update { it.copy(loading = false, failed = true, rows = emptyList(), partial = false) }
            return
        }
        if (mine != generation) return
        val memories = order(index.of(userId))
        _state.update { s ->
            s.copy(
                loading = false, failed = false, partial = index.partial,
                rows = memories.map { m -> Row(ObjectRef(m.node.scopeId, m.node.nodeId, m.node.kind, ApplicationId.MEMORIES, m.node.name), m.date, m.placeName, m.space.takeIf { it.kind == GROUP }) },
                map = MemoriesMap(memories.mapNotNull { it.pin }),
                view = if (memories.isEmpty() && s.view == View.MAP) View.LIST else s.view,
            )
        }
    }

    /** List, Tiles or Map, where offered; kept as the account's choice. */
    fun view(view: View) {
        if (!current.offersViews) return
        _state.update { it.copy(view = view) }
        door.chose(view)
    }

    /** A row's Memory opened. */
    fun open(row: Row) = door.open(row.ref)

    /** A pin's Memory opened, as its row would be; a pin of none of the rows opens nothing. */
    fun open(pin: MemoriesMap.Pin) {
        current.rows.firstOrNull { it.ref.scopeId == pin.scopeId && it.ref.nodeId == pin.nodeId }?.let(::open)
    }

    companion object {
        private const val GROUP = "GROUP"

        /**
         * The web's order: newest start day first, undated last, then by name (case aside), then by id — the same order on
         * every device.
         */
        fun order(memories: List<ParticipantIndex.Memory>): List<ParticipantIndex.Memory> = memories.sortedWith(
            compareBy<ParticipantIndex.Memory> { it.date?.from == null }
                .thenByDescending { it.date?.from }
                .thenBy { it.node.name.lowercase() }
                .thenBy { it.node.nodeId },
        )
    }
}
