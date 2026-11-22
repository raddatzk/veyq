package me.raddatz.veyq.mobile.memories.place

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import me.raddatz.veyq.mobile.map.LatLng
import me.raddatz.veyq.mobile.map.Viewport
import me.raddatz.veyq.mobile.navigation.NodeRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Memories bar's Map tab (uc.memory.place A3; ADR 0143 D6): a pin for every Memory whose place has a position (a
 * pending one is none), its cover where its row has one; the pins clustered at the zoom shown, a cluster's tap framing
 * its pins or — where they share a spot — listing them, a lone pin opening its Memory.
 */
class MemoriesMapTest {
    private fun ref(id: String) = NodeRef("s", id, null, "MEMORY", "Memory $id")
    private fun meta(json: String) = Json.parseToJsonElement(json) as JsonObject

    @Test
    fun aMemoryIsAPinWhereItsPlaceHasAPosition() {
        val placed = MemoriesMap.pinOf(ref("a"), meta("""{"v":1,"location":{"name":"Lisboa","nameSource":"manual","derived":{"lat":38.7,"lng":-9.1},"derivedMode":"fixed"},"cover":{"source":{"kind":"upload","ref":{"blobId":"b","chunkCount":1,"byteSize":9,"wrappedBlobKey":"k"}}}}"""))
        assertEquals(MemoriesMap.Pin("s", "a", "Memory a", LatLng(38.7, -9.1), hasCover = true), placed)
        assertNull(MemoriesMap.pinOf(ref("b"), meta("""{"v":1,"location":{"nameSource":"geocoded","derivedMode":"auto"}}""")), "a pending place")
        assertNull(MemoriesMap.pinOf(ref("c"), meta("""{"v":1,"location":{"name":"Only a name","nameSource":"manual","derivedMode":"auto"}}""")))
        assertNull(MemoriesMap.pinOf(ref("d"), meta("""{"v":1,"date":{"mode":"auto"}}""")))
    }

    private fun pin(id: String, lat: Double, lng: Double) = MemoriesMap.Pin("s", id, id, LatLng(lat, lng), false)

    @Test
    fun pinsAreClusteredAtTheZoomShownAndATapFramesOrListsThem() {
        val map = MemoriesMap(listOf(pin("a", 48.137, 11.575), pin("b", 48.138, 11.576), pin("c", 38.7, -9.1)))
        val far = map.clusters(3.0)
        assertEquals(listOf(2, 1), far.map { it.members.size })
        val view = Viewport(LatLng(45.0, 0.0), 3.0, 400.0, 300.0)
        assertEquals(MemoriesMap.Tap.Open(map.pins[2]), map.tap(far[1], view))
        val framed = assertIs<MemoriesMap.Tap.Frame>(map.tap(far[0], view))
        assertTrue(framed.view.zoom > 3.0, "framing its pins zooms in")
        // two Memories on one spot: no zoom frames them apart, they are listed
        val same = MemoriesMap(listOf(pin("x", 1.0, 1.0), pin("y", 1.0, 1.0)))
        val cluster = same.clusters(19.0).single()
        assertEquals(MemoriesMap.Tap.List(same.pins), same.tap(cluster, Viewport(LatLng(1.0, 1.0), 19.0, 400.0, 300.0)))
        assertEquals(MemoriesMap.Tap.List(same.pins), same.tap(cluster, Viewport(LatLng(1.0, 1.0), 10.0, 400.0, 300.0), maxZoom = 10.0))
        assertNull(MemoriesMap(emptyList()).framing(400.0, 300.0))
    }
}
