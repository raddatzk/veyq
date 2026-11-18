package me.raddatz.veyq.mobile.bridge

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import me.raddatz.veyq.mobile.map.LatLng
import me.raddatz.veyq.mobile.map.MapPoint
import me.raddatz.veyq.mobile.map.MapServices
import me.raddatz.veyq.mobile.map.MapState
import me.raddatz.veyq.mobile.map.Mercator
import me.raddatz.veyq.mobile.map.PlacedTile
import me.raddatz.veyq.mobile.map.TileKey
import me.raddatz.veyq.mobile.map.Viewport
import me.raddatz.veyq.mobile.memories.place.MemoriesMap
import me.raddatz.veyq.mobile.memories.place.PlaceEditor
import me.raddatz.veyq.mobile.navigation.NodeAt

/**
 * What a map draws beside its tiles, as the core hands it over (ADR 0143 decision 1): the Map view's track lines (already
 * in normalised space), the photos' points, a photo's point and its direction.
 */
internal class MapOverlays(
    val tracks: List<TrackLineShown> = emptyList(),
    val dots: List<LatLng> = emptyList(),
    val photo: LatLng? = null,
    val direction: Int? = null,
)

/**
 * A map the host opened (ADR 0143 decision 11): the core's raster map over the tenant's [services] — the view the core
 * holds, the tiles that cover it ([MapServices.tiles]' window, `TileView`: only those in view may be asked for), and what
 * it draws over them ([overlays]; a Memories map's [pins], clustered at the zoom shown). It closes with the page it belongs
 * to ([gallery], [lightbox]; both null: the Memories tab's, the tenant's) and with the tenant; closed, its window asks for
 * nothing more. Nothing here decides: the view's math is `Viewport`'s, the clusters `MemoriesMap`'s, the place's frame
 * `PlaceEditor`'s.
 */
internal class HeldMap(
    val services: MapServices,
    /**
     * The view it opens at for a size: the core's (`PlaceEditor.initialView`, `MemoriesMap.framing`, a photo at zoom 15,
     * `MemoryLocation.opening`) — null while what it frames is still read: no view and no tile then, and the opening is
     * tried again as [overlays] move (inbox #945).
     */
    private val opening: (width: Double, height: Double) -> Viewport?,
    val overlays: Flow<MapOverlays>,
    val gallery: Long? = null,
    val lightbox: Long? = null,
    val pins: MemoriesMap? = null,
    /** The place editor whose place a member's pan frames (`PlaceEditor.frame`), or none. */
    val place: PlaceEditor? = null,
    /** The person's page whose Memories it shows ([Call.PersonMap]): it closes with that page. */
    val person: Long? = null,
) {
    /** The view shown, the tiles that cover it, and how often the core moved it itself. */
    class Frame(val view: Viewport, val tiles: List<PlacedTile>, val moved: Long)

    private val window = services.tiles?.view()
    val frame = MutableStateFlow<Frame?>(null)
    val open = MutableStateFlow(true)

    /**
     * The host's [Call.MapView]: a centre ([x], [y] normalised) and [zoom] from the member's gesture, or none — the opening
     * view for the size, or the view held resized. A centre the member moved is the place's frame on a place's map.
     */
    fun view(x: Double?, y: Double?, zoom: Double?, width: Double, height: Double) {
        val before = frame.value
        if (x == null || y == null || zoom == null) {
            if (before == null) {
                waiting = width to height
                opening(width, height)?.let { show(it, 1) }
            } else show(before.view.copy(width = width, height = height), before.moved)
            return
        }
        // the world repeats sideways: a centre past either edge is the same place
        val centre = Mercator.unproject(MapPoint(x - kotlin.math.floor(x), y.coerceIn(0.0, 1.0)))
        val v = Viewport(centre, zoom.coerceIn(0.0, Mercator.MAX_ZOOM.toDouble()), width, height)
        show(v, before?.moved ?: 1)
        // a resize or a zoom about the centre keeps the place; only a moved centre is the member's pan
        if (place != null && before != null && v.center != before.view.center) place.frame(v.center, user = true)
    }

    /** The size a map waiting for its framing was given ([opening] null), none once it shows a view. */
    private var waiting: Pair<Double, Double>? = null

    /** What the map frames may have been read: a map still waiting for it opens now, once. */
    private fun retryOpening() {
        val (w, h) = waiting ?: return
        if (frame.value != null) return
        opening(w, h)?.let { show(it, 1) }
    }

    /** The core moves the view itself (a cluster framed, *Follow the photos*, a track selected): the host adopts it. */
    fun moveTo(view: Viewport) = show(view, (frame.value?.moved ?: 0) + 1)

    private fun show(v: Viewport, moved: Long) {
        waiting = null
        frame.value = Frame(v, window?.show(v).orEmpty(), moved)
    }

    /** Tile [id]'s bytes where it is in view and allowed (`TileView.tile`), else null. */
    suspend fun tile(id: String): ByteArray? {
        if (!open.value) return null
        val key = TileKey.parse(id) ?: return null
        return window?.tile(key)
    }

    fun close() {
        open.value = false
        window?.close()
    }

    /** What the host draws now ([MapShown]): each field the core's answer, none of it computed here. */
    fun shown(): Flow<MapShown?> = combine(frame, services.state, overlays.onEach { retryOpening() }, open) { f, state, o, isOpen ->
        if (!isOpen) return@combine null
        MapShown(
            state = mapState(state),
            view = f?.let { fr ->
                val c = Mercator.project(fr.view.center)
                MapViewShown(fr.view.center.lat, fr.view.center.lng, c.x, c.y, fr.view.zoom, fr.view.width, fr.view.height, fr.moved)
            },
            tiles = if (state == MapState.Shown) f?.tiles?.map { TileShown(it.key.id, it.x, it.y, it.size) }.orEmpty() else emptyList(),
            attribution = services.attribution,
            tracks = o.tracks,
            dots = o.dots.map(::point),
            clusters = f?.let { fr -> pins?.clusters(fr.view.zoom)?.map { c -> ClusterShown(point(c.center), c.members.size, pin(c.members.first())) } }.orEmpty(),
            photo = o.photo?.let { PhotoShown(point(it), o.direction) },
            centrePin = place != null,
        )
    }

    companion object {
        fun point(p: LatLng): MapPointShown = Mercator.project(p).let { MapPointShown(it.x, it.y) }

        fun pin(p: MemoriesMap.Pin): PinShown {
            val node = NodeAt(p.scopeId, p.nodeId, me.raddatz.veyq.mobile.memories.GalleryModel.MEMORY, p.name)
            return PinShown(node, me.raddatz.veyq.mobile.memories.GalleryModel.page(node), p.nodeId.takeIf { p.hasCover })
        }

        fun mapState(s: MapState): MapStatus = when (s) {
            MapState.NotOffered -> MapStatus.NotOffered
            MapState.Offline -> MapStatus.Offline
            MapState.Shown -> MapStatus.Shown
            is MapState.Consent -> MapStatus.Consent(s.hosts.tiles, s.hosts.places, s.renewal)
        }
    }
}
