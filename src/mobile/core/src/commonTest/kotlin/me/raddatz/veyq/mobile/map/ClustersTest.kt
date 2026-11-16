package me.raddatz.veyq.mobile.map

import kotlin.test.Test
import kotlin.test.assertEquals

/** The pins' grouping (ADR 0143 D6): the web's `spaceMap.test.ts` `clusterPins` cases, and the same at a map's zoom. */
class ClustersTest {
    private class Pin(val id: String, val lat: Double, val lng: Double)

    /** 1 degree = 1 pixel: the pixel distance is the degree distance (the web test's projection). */
    private val linear: (LatLng) -> Pair<Double, Double> = { it.lng to it.lat }
    private val at: (Pin) -> LatLng = { LatLng(it.lat, it.lng) }

    private fun keys(cs: List<Cluster<Pin>>) = cs.map { c -> c.members.map { it.id }.sorted().joinToString("|") }.sorted()

    @Test
    fun keepsFarApartPinsAsSeparateOneMemberClusters() {
        assertEquals(listOf("a", "b"), keys(clusterPins(listOf(Pin("a", 0.0, 0.0), Pin("b", 50.0, 50.0)), at, linear, 10.0)))
    }

    @Test
    fun groupsPinsWithinThePixelRadiusAndLeavesDistantOnesOut() {
        val cs = clusterPins(listOf(Pin("a", 0.0, 0.0), Pin("b", 2.0, 2.0), Pin("c", 40.0, 40.0)), at, linear, 10.0)
        assertEquals(listOf("a|b", "c"), keys(cs))
    }

    @Test
    fun placesAClusterAtTheCentroidOfItsMembers() {
        val c = clusterPins(listOf(Pin("a", 0.0, 0.0), Pin("b", 2.0, 4.0)), at, linear, 10.0).single()
        assertEquals(LatLng(1.0, 2.0), c.center)
        assertEquals(2, c.members.size)
    }

    @Test
    fun groupsAgainstTheFirstSeenAnchorNotTransitively() {
        val cs = clusterPins(listOf(Pin("a", 0.0, 0.0), Pin("b", 0.0, 8.0), Pin("c", 0.0, 16.0)), at, linear, 10.0)
        assertEquals(listOf("a|b", "c"), keys(cs))
    }

    @Test
    fun atAMapsZoomTwoPinsAStreetApartMergeWhenZoomedOutAndPartWhenZoomedIn() {
        val pins = listOf(Pin("a", 52.5200, 13.4050), Pin("b", 52.5200, 13.4150)) // ≈ 680 m apart
        assertEquals(listOf("a|b"), keys(clusterAtZoom(pins, at, 10.0)))
        assertEquals(listOf("a", "b"), keys(clusterAtZoom(pins, at, 16.0)))
    }
}
