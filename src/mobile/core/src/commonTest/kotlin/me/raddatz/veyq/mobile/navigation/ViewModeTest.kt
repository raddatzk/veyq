package me.raddatz.veyq.mobile.navigation

import me.raddatz.veyq.mobile.session.MemoryDeviceStore
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The view mode and the tiles (ADR 0149 decision 8), the web's `useViewMode.test.ts` and `ViewModeToggle.test.ts` twinned:
 * one choice per account on this device — two accounts each keep their own —, the first view before any choice, a level
 * without the map; the tiles' columns at a phone's widths and a desktop's.
 */
class ViewModeTest {
    @Test
    fun eachAccountKeepsItsOwnChoice_theFirstViewBeforeAny() {
        val store = MemoryDeviceStore()
        val views = ViewModes(store)
        assertEquals(ViewMode.FIRST, views.of("ana"))
        views.set("ana", ViewMode.LIST)
        assertEquals(ViewMode.LIST, views.of("ana"))
        assertEquals(ViewMode.FIRST, views.of("ben"), "another account on this device is not Ana's choice")
        views.set("ben", ViewMode.TILES)
        assertEquals(ViewMode.LIST, ViewModes(store).of("ana"), "kept on the device, across a restart")
        assertEquals(ViewMode.TILES, ViewModes(store).of("ben"))
    }

    @Test
    fun aLevelOffersNoMap_aPersonsMemoriesDo() {
        val views = ViewModes(MemoryDeviceStore())
        views.set("ana", ViewMode.MAP)
        assertEquals(ViewMode.MAP, views.of("ana", ViewMode.PERSON))
        assertEquals(ViewMode.FIRST, views.of("ana", ViewMode.LEVEL), "a level shows its first view where the map was chosen")
        assertEquals(listOf(ViewMode.LIST, ViewMode.TILES, ViewMode.MAP), ViewMode.PERSON)
        assertEquals(listOf(ViewMode.LIST, ViewMode.TILES), ViewMode.LEVEL)
    }

    @Test
    fun twoColumnsOnAPhone_moreAsTheWindowWidens() {
        assertEquals(2, TileGrid.columns(359.0))
        assertEquals(2, TileGrid.columns(360.0))
        assertEquals(2, TileGrid.columns(200.0), "never fewer than two")
        assertEquals(7, TileGrid.columns(1200.0))
        val frames = TileGrid.frames(360.0, 3, labelHeight = 40.0)
        assertEquals(TileGrid.Frame(0.0, 0.0, 174.0), frames[0])
        assertEquals(TileGrid.Frame(186.0, 0.0, 174.0), frames[1])
        assertEquals(TileGrid.Frame(0.0, 226.0, 174.0), frames[2], "the next row below the cover, its label and the gap")
    }
}
