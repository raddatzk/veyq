package me.raddatz.veyq.mobile.memories.people

import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.map.LatLng
import me.raddatz.veyq.mobile.memories.place.MemoriesMap
import me.raddatz.veyq.mobile.memories.place.MemoryDate
import me.raddatz.veyq.mobile.memories.place.MemoryLocation
import me.raddatz.veyq.mobile.navigation.NodeRef
import me.raddatz.veyq.mobile.navigation.ObjectRef
import me.raddatz.veyq.mobile.navigation.SpaceRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Memories' section of a person's profile (ADR 0144 decision 13; the web's `PersonMemories.test.ts` ported where the apps
 * share its rules): the person's Memories newest start day first, undated last, then by name; the group's name on a
 * group Memory's row; loading, failed with *Try again*, empty and partial; List | Map once there is something to list,
 * not stored; the map's pins exactly the person's located Memories; a row and a pin open their Memory.
 */
class PersonMemoriesTest {
    private val tenant = SpaceRef("t", "TENANT", "Family")
    private val group = SpaceRef("g", "GROUP", "Group")

    private fun memory(id: String, name: String, from: String? = null, to: String? = from, space: SpaceRef = tenant, place: MemoryLocation? = null, vararg ids: String = arrayOf("ada")): ParticipantIndex.Memory {
        val ref = NodeRef(space.scopeId, id, null, "MEMORY", name)
        val pin = place?.derived?.let { MemoriesMap.Pin(space.scopeId, id, name, it, false) }
        return ParticipantIndex.Memory(ref, space, from?.let { MemoryDate(MemoryDate.Mode.RANGE, it, to) }, place, pin, ids.toList())
    }

    private fun index(partial: Boolean = false, vararg memories: ParticipantIndex.Memory) = ParticipantIndex.Index(
        memories.flatMap { m -> m.userIds.map { it to m.node } }.groupBy({ it.first }, { it.second }).mapValues { it.value.toSet() },
        memories.associateBy { it.node }, partial,
    )

    private class Door(var answer: () -> ParticipantIndex.Index) : PersonMemories.Door {
        var reads = 0
        val opened = mutableListOf<ObjectRef>()
        override suspend fun index(): ParticipantIndex.Index { reads++; return answer() }
        override fun open(ref: ObjectRef) { opened += ref }
        /** The account's view choice as the device keeps it (`ViewModes`, ADR 0149 decision 8). */
        var kept = PersonMemories.View.LIST
        override val chosen get() = kept
        override fun chose(view: PersonMemories.View) { kept = view }
    }

    private val lisboa = MemoryLocation("Lisboa", MemoryLocation.NameSource.MANUAL, LatLng(38.7, -9.1), MemoryLocation.DerivedMode.FIXED)
    private val onlyAName = MemoryLocation("Somewhere", MemoryLocation.NameSource.MANUAL, null, MemoryLocation.DerivedMode.AUTO)

    @Test
    fun saysItIsLoadingWhileTheIndexIsBuilt() {
        val s = PersonMemories("ada", Door { index() }).current
        assertTrue(s.loading)
        assertFalse(s.offersViews, "no switch while loading")
        assertFalse(s.empty)
    }

    /** The web's order, not the apps' last use (ADR 0144 decision 13): a person's Memories are read in time. */
    @Test
    fun listsThePersonsMemoriesNewestStartDayFirst_undatedLast_thenByName() = runTest {
        val door = Door {
            index(
                false,
                memory("old", "Ostsee", "2019-07-01", "2019-07-14"),
                memory("undated-b", "beach"),
                memory("new", "Harz", "2026-05-01"),
                memory("undated-a", "Alpen"),
                memory("same-z", "Zingst", "2024-03-01"),
                memory("same-a", "Amrum", "2024-03-01", "2024-03-09"),
                memory("other", "Not hers", "2025-01-01", ids = arrayOf("ben")),
            )
        }
        val section = PersonMemories("ada", door)
        section.load()
        assertEquals(listOf("Harz", "Amrum", "Zingst", "Ostsee", "Alpen", "beach"), section.current.rows.map { it.ref.name })
        assertEquals(MemoryDate(MemoryDate.Mode.RANGE, "2019-07-01", "2019-07-14"), section.current.rows[3].date, "the row's date range")
        assertFalse(section.current.loading)
        assertFalse(section.current.partial)
    }

    @Test
    fun aRowNamesItsPlaceAndItsGroup_andOpensItsMemory() = runTest {
        val door = Door { index(false, memory("g1", "Harz", "2026-05-01", space = group, place = onlyAName), memory("t1", "Ostsee", "2025-05-01", place = lisboa)) }
        val section = PersonMemories("ada", door)
        section.load()
        val (harz, ostsee) = section.current.rows
        assertEquals("Somewhere", harz.placeName, "a place that is a name only is still the row's place")
        assertEquals(group, harz.group)
        assertNull(ostsee.group, "the tenant's space is not named on a row")
        section.open(harz)
        assertEquals(listOf(ObjectRef("g", "g1", "MEMORY", me.raddatz.veyq.mobile.navigation.ApplicationId.MEMORIES, "Harz")), door.opened)
    }

    @Test
    fun saysSoWhenNobodyTheMemberCanOpenListsThePerson() = runTest {
        val section = PersonMemories("ada", Door { index(false, memory("x", "Not hers", ids = arrayOf("ben"))) })
        section.load()
        assertTrue(section.current.empty)
        assertFalse(section.current.offersViews, "no switch with nothing to list")
        section.view(PersonMemories.View.MAP)
        assertEquals(PersonMemories.View.LIST, section.current.view, "no map where nothing is listed")
    }

    @Test
    fun saysTheListMayBeIncompleteWhenTheWalkWasCappedOrAScopeFailed_withTheRowsItHas() = runTest {
        val section = PersonMemories("ada", Door { index(true, memory("a", "Harz")) })
        section.load()
        assertTrue(section.current.partial)
        assertEquals(listOf("Harz"), section.current.rows.map { it.ref.name })
    }

    @Test
    fun anErrorOffersTryAgain_whichReadsAgain() = runTest {
        var fail = true
        val door = Door { if (fail) throw IllegalStateException("no tree read") else index(false, memory("a", "Harz")) }
        val section = PersonMemories("ada", door)
        section.load()
        assertTrue(section.current.failed)
        assertFalse(section.current.loading)
        assertFalse(section.current.offersViews, "no switch on an error")
        fail = false
        section.load()
        assertFalse(section.current.failed)
        assertEquals(2, door.reads)
        assertEquals(listOf("Harz"), section.current.rows.map { it.ref.name })
    }

    @Test
    fun theSwitchShowsTilesAndTheMapInPlaceOfTheListAndBack_theChoiceKeptAsTheAccounts() = runTest {
        val door = Door { index(false, memory("a", "Harz", "2026-05-01")) }
        val section = PersonMemories("ada", door)
        section.load()
        assertTrue(section.current.offersViews)
        assertEquals(PersonMemories.View.LIST, section.current.view, "the section opens on the list")
        section.view(PersonMemories.View.MAP)
        assertEquals(PersonMemories.View.MAP, section.current.view)
        section.load()
        assertEquals(PersonMemories.View.MAP, section.current.view, "a retry keeps the view shown")
        section.view(PersonMemories.View.TILES)
        assertEquals(PersonMemories.View.TILES, section.current.view)
        // a profile opened later opens on the account's choice (ADR 0149 decision 8)
        section.view(PersonMemories.View.MAP)
        val again = PersonMemories("ada", door)
        again.load()
        assertEquals(PersonMemories.View.MAP, again.current.view)
    }

    @Test
    fun theMapPinsExactlyThePersonsMemoriesThatHaveAPosition_andAPinOpensItsMemory() = runTest {
        val door = Door {
            index(
                false,
                memory("a", "Ostsee", "2025-01-01", place = lisboa),
                memory("b", "Harz", "2024-01-01", place = onlyAName),
                memory("c", "Nothing"),
                memory("d", "Hers not", place = lisboa, ids = arrayOf("ben")),
            )
        }
        val section = PersonMemories("ada", door)
        section.load()
        assertEquals(listOf("a"), section.current.map.pins.map { it.nodeId }, "a name-only place, no place and another's Memory are no pins")
        // none of the person's Memories located: the map is offered still, with no pins (its "No places yet")
        val unplaced = PersonMemories("ada", Door { index(false, memory("c", "Nothing")) })
        unplaced.load()
        assertTrue(unplaced.current.offersViews)
        assertTrue(unplaced.current.map.pins.isEmpty())
        section.open(section.current.map.pins.single())
        assertEquals(listOf("a"), door.opened.map { it.nodeId })
        section.open(MemoriesMap.Pin("t", "d", "Hers not", LatLng(1.0, 1.0), false))
        assertEquals(1, door.opened.size, "a pin of none of the rows opens nothing")
    }
}
