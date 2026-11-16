package me.raddatz.veyq.mobile.map

import kotlin.math.hypot

/** Pins that fall within the grouping radius of each other on screen; [center] is their centroid. */
data class Cluster<T>(val members: List<T>, val center: LatLng)

/**
 * Groups pins as the web's `clusterPins` does (`spaceMap.ts`, ADR 0143 D6): greedily, each pin joining the first
 * cluster whose anchor — its first member's projected position — lies within [distancePx] of its own, so a cluster stays
 * stable as members join (never transitive); a cluster is shown at its members' centroid. [project] maps a position to
 * screen pixels; a lone pin is a one-member cluster.
 */
fun <T> clusterPins(pins: List<T>, at: (T) -> LatLng, project: (LatLng) -> Pair<Double, Double>, distancePx: Double): List<Cluster<T>> {
    class Group(val anchor: Pair<Double, Double>) {
        val pins = mutableListOf<T>()
    }
    val groups = mutableListOf<Group>()
    for (pin in pins) {
        val p = project(at(pin))
        val hit = groups.firstOrNull { hypot(p.first - it.anchor.first, p.second - it.anchor.second) <= distancePx }
        (hit ?: Group(p).also { groups += it }).pins += pin
    }
    return groups.map { g ->
        Cluster(g.pins.toList(), LatLng(g.pins.sumOf { at(it).lat } / g.pins.size, g.pins.sumOf { at(it).lng } / g.pins.size))
    }
}

/** The web's grouping radius in screen pixels (`SpaceMap.vue`'s `CLUSTER_PX`). */
const val CLUSTER_PX = 38.0

/**
 * [clusterPins] at a map's [zoom]: positions in Mercator pixels of that zoom, which is all the grouping needs — so Apple's
 * MapKit hands its camera's zoom and gets the same clusters as the core's own map.
 */
fun <T> clusterAtZoom(pins: List<T>, at: (T) -> LatLng, zoom: Double, distancePx: Double = CLUSTER_PX): List<Cluster<T>> {
    val world = Mercator.worldPx(zoom)
    return clusterPins(pins, at, { p -> Mercator.project(p).let { it.x * world to it.y * world } }, distancePx)
}
