package me.raddatz.veyq.mobile.memories.place

import kotlinx.serialization.json.JsonObject
import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.map.Cluster
import me.raddatz.veyq.mobile.map.LatLng
import me.raddatz.veyq.mobile.map.Mercator
import me.raddatz.veyq.mobile.map.Viewport
import me.raddatz.veyq.mobile.map.clusterAtZoom
import me.raddatz.veyq.mobile.navigation.NodeRef
import me.raddatz.veyq.mobile.session.NodeCover
import me.raddatz.veyq.mobile.session.NodeMetaWrite
import me.raddatz.veyq.mobile.session.TenantSession

/**
 * The Memories bar's **Map** tab (uc.memory.place A3; ADR 0143 D6): every Memory of the application's list whose place has
 * a position, as a pin — unsealed on the device from the meta the tree already holds, under each node's own key; no media
 * read, no locations endpoint, nothing sent. A pending place (no position yet) is no pin. The pins are clustered in
 * Mercator pixels of the zoom shown ([clusters], the web's `clusterPins`), so Apple's MapKit, handing its camera's zoom,
 * groups them as the core's own map does. A cluster's tap frames its pins, and at the highest zoom lists them
 * ([tap]); a pin opens its Memory.
 */
class MemoriesMap(val pins: List<Pin>) {
    /** One located Memory: its node, its name, its position, and whether its row has a cover the pin shows. */
    data class Pin(val scopeId: String, val nodeId: String, val name: String, val at: LatLng, val hasCover: Boolean)

    /** What a tap on a cluster does: open its one Memory, frame its pins, or list them (they share a spot at the top zoom). */
    sealed interface Tap {
        data class Open(val pin: Pin) : Tap
        data class Frame(val view: Viewport) : Tap
        data class List(val pins: kotlin.collections.List<Pin>) : Tap
    }

    /** The pins grouped at [zoom] (`clusterAtZoom`). */
    fun clusters(zoom: Double): List<Cluster<Pin>> = clusterAtZoom(pins, { it.at }, zoom)

    /** The view every pin is framed in, for a map of [width] × [height]; null without a pin (the world view then). */
    fun framing(width: Double, height: Double): Viewport? = Viewport.fit(pins.map { it.at }, width, height)

    /**
     * A tap on [cluster] while the map shows [view] at most at [maxZoom]: one pin opens; more are framed — unless that frame
     * would not zoom in any further (they share a spot), when they are listed.
     */
    fun tap(cluster: Cluster<Pin>, view: Viewport, maxZoom: Double = Mercator.MAX_ZOOM.toDouble()): Tap {
        if (cluster.members.size == 1) return Tap.Open(cluster.members.single())
        val framed = Viewport.fit(cluster.members.map { it.at }, view.width, view.height, maxZoom = maxZoom)
        return if (framed == null || view.zoom >= maxZoom || framed.zoom <= view.zoom) Tap.List(cluster.members) else Tap.Frame(framed)
    }

    companion object {
        /** The pin a node's opened [meta] makes, or null where its place has no position. */
        fun pinOf(ref: NodeRef, meta: JsonObject): Pin? {
            val at = MemoryLocation.of(meta)?.derived ?: return null
            return Pin(ref.scopeId, ref.nodeId, ref.name, at, NodeCover.read(meta) != null)
        }

        /** The pins of [memories] (the Map tab's set: the same as the All tab's), each opened under its own key. */
        suspend fun of(tenant: TenantSession, memories: List<NodeRef>): MemoriesMap = MemoriesMap(
            memories.mapNotNull { ref ->
                val n = tenant.node(ref.scopeId, ref.nodeId) ?: return@mapNotNull null
                opened(tenant.account.suite, ref, n.key, n.wrappedMeta)?.let { pinOf(ref, it) }
            },
        )

        private fun opened(suite: V1, ref: NodeRef, key: ByteArray, wrappedMeta: String?): JsonObject? =
            runCatching { NodeMetaWrite.openForWrite(suite, ref.scopeId, ref.nodeId, key, wrappedMeta) }.getOrNull()
    }
}
