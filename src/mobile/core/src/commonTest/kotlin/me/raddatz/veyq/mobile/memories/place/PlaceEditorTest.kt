package me.raddatz.veyq.mobile.memories.place

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.map.ConsentHosts
import me.raddatz.veyq.mobile.map.LatLng
import me.raddatz.veyq.mobile.map.MapState
import me.raddatz.veyq.mobile.map.PlayedMapServices
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The place editor (the web's `MemoryLocationEditor.vue`; uc.memory.place; ADR 0143 D5) over map services the test plays:
 * a member's pan takes an `auto` place over, a frame the app made never makes one, the name looked up once the map settles
 * (700 ms) at the chosen or default level, a typed name frozen, *Follow the photos*, and without the consent the name field
 * alone with not one request sent.
 */
class PlaceEditorTest {
    private val nominatim = """{"address":{"road":"Marienplatz","city":"München","state":"Bayern","country":"Deutschland"}}"""

    private class Maps(val played: PlayedMapServices, val services: me.raddatz.veyq.mobile.map.MapServices) {
        val geocodes: Int get() = played.geocodes.size
        val requests get() = played.sent
    }

    private fun TestScope.maps(consented: Boolean): Maps {
        val played = PlayedMapServices(this)
        played.geocode = { 200 to nominatim }
        return Maps(played, played.services().also { if (consented) it.turnOn() })
    }

    private fun TestScope.editor(
        played: Maps, stored: MemoryLocation? = null, medoid: LatLng? = null, saved: MutableList<MemoryLocation?> = mutableListOf(),
    ): PlaceEditor {
        val names = PlaceNames(played.services, { "de" })
        return PlaceEditor(stored, medoid, listOfNotNull(medoid), names, save = { saved += it; MemoryFacts.Outcome.Written }, scope = backgroundScope)
    }

    @Test
    fun aMembersPanTakesAnAutoPlaceOverAndItsNameIsLookedUpOnceTheMapSettles() = runTest {
        val played = maps(consented = true)
        val medoid = LatLng(48.1, 11.5)
        val e = editor(played, medoid = medoid)
        assertTrue(e.current.withMap)
        assertEquals(MemoryLocation.DerivedMode.AUTO, e.current.derivedMode)
        e.frame(LatLng(1.0, 1.0), user = false) // the app's own framing: no takeover
        assertEquals(MemoryLocation.DerivedMode.AUTO, e.current.derivedMode)
        e.frame(LatLng(48.137, 11.575), user = true)
        assertEquals(MemoryLocation.DerivedMode.FIXED, e.current.derivedMode)
        e.frame(LatLng(48.1371, 11.5751), user = true)
        advanceTimeBy(PlaceEditor.RESOLVE_DELAY_MS - 1)
        runCurrent()
        assertEquals(0, played.geocodes, "nothing before the map settles")
        advanceTimeBy(2)
        runCurrent()
        assertEquals(1, played.geocodes, "one lookup for the settled frame, not one per frame")
        assertEquals("München", e.current.name)
        assertTrue(e.current.showLevelPicker)
        e.pickLevel(me.raddatz.veyq.mobile.map.GeoLevel.Key.ROAD)
        assertEquals("Marienplatz", e.current.name)
        val place = e.build()!!
        assertEquals(LatLng(48.1371, 11.5751), place.derived)
        assertEquals(me.raddatz.veyq.mobile.map.GeoLevel.Key.ROAD, place.nameAnchor?.level)
        // follow the photos again: auto at the medoid
        e.followAgain()
        runCurrent()
        assertEquals(MemoryLocation.DerivedMode.AUTO, e.current.derivedMode)
        assertEquals(medoid, e.build()?.derived)
    }

    // bite: the geocoder's queue sent every superseded pan's lookup first, so the last pan's name came seconds late — red
    @Test
    fun afterQuickPansTheLastPositionIsNamedOnceTheGeocodersWindowOpens() = runTest {
        val played = maps(consented = true)
        played.played.geocode = { s -> 200 to """{"address":{"city":"at ${io.ktor.http.Url(s.url).parameters["lat"]}"}}""" }
        val e = editor(played, medoid = LatLng(48.0, 11.5))
        val step = 750L // past the settle delay, inside the geocoder's one second: each pan's lookup is started
        val pans = (0 until 8).map { LatLng(48.0 + it * 0.01, 11.5) }
        for ((i, p) in pans.withIndex()) {
            e.frame(p, user = true)
            if (i < pans.lastIndex) advanceTimeBy(step)
        }
        // the last pan settles, then waits at most one window for the request before it
        advanceTimeBy(PlaceEditor.RESOLVE_DELAY_MS + me.raddatz.veyq.mobile.map.Geocoder.MIN_INTERVAL_MS)
        runCurrent()
        assertEquals("at ${pans.last().lat}", e.current.name, "the last position is named, not left behind a queue")
        assertTrue(played.geocodes < pans.size, "a lookup no pan waits for any more is dropped, never sent")
        val sent = played.played.geocodes.map { it.at }
        assertTrue(sent.zipWithNext().all { (a, b) -> b - a >= me.raddatz.veyq.mobile.map.Geocoder.MIN_INTERVAL_MS }, "still at most one per second: $sent")
    }

    @Test
    fun theOpeningViewOfAMemoryWithoutAPointIsNoPlace() = runTest {
        val played = maps(consented = true)
        val e = editor(played)
        assertEquals(MemoryLocation.DerivedMode.FIXED, e.current.derivedMode)
        assertEquals(MemoryLocation.WORLD_CENTER, e.initialView(400.0, 300.0).center)
        e.frame(MemoryLocation.WORLD_CENTER, user = false)
        assertNull(e.current.derived, "an app's frame of the world view makes no position")
        advanceTimeBy(1000)
        assertEquals(0, played.geocodes)
    }

    @Test
    fun aTypedNameIsTheMembersAndFrozen() = runTest {
        val played = maps(consented = true)
        val saved = mutableListOf<MemoryLocation?>()
        val e = editor(played, medoid = LatLng(48.1, 11.5), saved = saved)
        e.typeName("Our flat")
        e.frame(LatLng(48.2, 11.6), user = true)
        advanceTimeBy(1000)
        assertEquals("Our flat", e.current.name, "a lookup never overwrites a typed name")
        e.save()
        assertEquals(MemoryLocation("Our flat", MemoryLocation.NameSource.MANUAL, LatLng(48.2, 11.6), MemoryLocation.DerivedMode.FIXED), saved.single())
        e.remove()
        assertEquals(null, saved.last(), "remove: the place dropped")
    }

    @Test
    fun withoutTheConsentItIsTheNameFieldAloneAndNoRequestLeaves() = runTest {
        val played = maps(consented = false)
        assertTrue(played.services.state.value is MapState.Consent)
        val saved = mutableListOf<MemoryLocation?>()
        val e = editor(played, medoid = LatLng(48.1, 11.5), saved = saved)
        assertEquals(false, e.current.withMap)
        assertEquals(MemoryLocation.NameSource.MANUAL, e.current.nameSource)
        e.open()
        e.typeName("Munich")
        e.save()
        advanceTimeBy(5000)
        assertEquals(0, played.requests.size, "no request left the device")
        assertEquals(MemoryLocation("Munich", MemoryLocation.NameSource.MANUAL, LatLng(48.1, 11.5), MemoryLocation.DerivedMode.AUTO), saved.single())
        // the consent given in the editor: the map stands where the offer stood
        played.services.turnOn()
        e.mapsTurnedOn()
        runCurrent()
        assertTrue(e.current.withMap)
        assertEquals(ConsentHosts("tiles.example", "nominatim.example"), played.services.consentGivenFor)
    }
}
