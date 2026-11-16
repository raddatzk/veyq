package me.raddatz.veyq.mobile.map

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** A point on the earth in degrees (WGS 84, as a photo's GPS and a sealed place carry it). */
data class LatLng(val lat: Double, val lng: Double)

/** A point in normalised Web Mercator space: `x` 0 at 180° W to 1 at 180° E, `y` 0 at the north edge to 1 at the south. */
data class MapPoint(val x: Double, val y: Double)

/**
 * Web Mercator (EPSG:3857) as the tile pyramid uses it: the world a square of 256 px tiles, `2^z` per side at level `z`
 * (ADR 0143 D1). Every overlay point is handed to a platform in these normalised coordinates, and every tile's place.
 */
object Mercator {
    /** The latitude where the square ends: the pyramid shows nothing nearer the poles. */
    const val MAX_LAT = 85.0511287798066

    /** A tile's edge in pixels at its own level. */
    const val TILE_PX = 256.0

    /** The highest level the operator's tiles are drawn at (the web's `DEFAULT_TILE_MAX_ZOOM`). */
    const val MAX_ZOOM = 19

    fun project(p: LatLng): MapPoint {
        val lat = p.lat.coerceIn(-MAX_LAT, MAX_LAT) * PI / 180
        val s = sin(lat)
        return MapPoint((p.lng + 180) / 360, 0.5 - ln((1 + s) / (1 - s)) / (4 * PI))
    }

    fun unproject(p: MapPoint): LatLng {
        val lat = (2 * atan(exp(PI * (1 - 2 * p.y))) - PI / 2) * 180 / PI
        return LatLng(lat, p.x * 360 - 180)
    }

    /** The world's edge in pixels at a (fractional) [zoom]. */
    fun worldPx(zoom: Double): Double = TILE_PX * 2.0.pow(zoom)
}

/** One tile of the pyramid: level [z], column [x] (wrapped into `0 until 2^z`), row [y]. */
data class TileKey(val z: Int, val x: Int, val y: Int) {
    /** How the tile is named across the bridge and in a template: `z/x/y`. */
    val id: String get() = "$z/$x/$y"

    companion object {
        fun parse(id: String): TileKey? {
            val p = id.split('/')
            if (p.size != 3) return null
            val (z, x, y) = p.map { it.toIntOrNull() ?: return null }
            val n = 1 shl z.coerceIn(0, 30)
            return if (z in 0..Mercator.MAX_ZOOM && x in 0 until n && y in 0 until n) TileKey(z, x, y) else null
        }
    }
}

/**
 * A tile placed in normalised Mercator space: its top-left corner at ([x], [y]) and its edge [size]. [x] may lie outside
 * `0..1` where the world repeats sideways; [key] is then the wrapped tile it shows.
 */
data class PlacedTile(val key: TileKey, val x: Double, val y: Double, val size: Double)

/**
 * What a map shows (ADR 0143 D1): its [center], its fractional [zoom], and its size in the platform's layout pixels. The
 * tiles are drawn from the floor level, scaled by `2^(zoom − level)`; the one affine formula from normalised coordinates
 * to the view's pixels is [toScreen], which a platform's drawing repeats and nothing else.
 */
data class Viewport(val center: LatLng, val zoom: Double, val width: Double, val height: Double) {
    /** The tile level drawn: the floor of [zoom] within the pyramid. */
    fun level(maxZoom: Int = Mercator.MAX_ZOOM): Int = floor(zoom).toInt().coerceIn(0, maxZoom)

    /**
     * The tiles that cover this view and none beyond it (no ring ahead: the tile policy forbids pre-emptive fetching,
     * ADR 0143 D2): every column and row any part of which is inside the view, rows only within the world.
     */
    fun tiles(maxZoom: Int = Mercator.MAX_ZOOM): List<PlacedTile> {
        if (width <= 0 || height <= 0) return emptyList()
        val z = level(maxZoom)
        val n = 1 shl z
        val tilePx = Mercator.TILE_PX * 2.0.pow(zoom - z)
        val c = Mercator.project(center)
        val cx = c.x * n
        val cy = c.y * n
        val hx = width / 2 / tilePx
        val hy = height / 2 / tilePx
        val x0 = floor(cx - hx).toInt()
        val x1 = ceil(cx + hx).toInt() - 1
        val y0 = max(0, floor(cy - hy).toInt())
        val y1 = min(n - 1, ceil(cy + hy).toInt() - 1)
        val out = ArrayList<PlacedTile>()
        for (ty in y0..y1) for (tx in x0..x1) {
            val wrapped = ((tx % n) + n) % n
            out += PlacedTile(TileKey(z, wrapped, ty), tx.toDouble() / n, ty.toDouble() / n, 1.0 / n)
        }
        return out
    }

    /** Where normalised point [p] lies in this view, in its pixels from the top-left corner. */
    fun toScreen(p: MapPoint): Pair<Double, Double> {
        val world = Mercator.worldPx(zoom)
        val c = Mercator.project(center)
        return (p.x - c.x) * world + width / 2 to (p.y - c.y) * world + height / 2
    }

    /** The view moved by a drag of ([dx], [dy]) pixels: the content follows the finger. */
    fun panBy(dx: Double, dy: Double): Viewport {
        val world = Mercator.worldPx(zoom)
        val c = Mercator.project(center)
        val y = (c.y - dy / world).coerceIn(0.0, 1.0)
        return copy(center = Mercator.unproject(MapPoint(c.x - dx / world, y)).let { it.copy(lng = wrapLng(it.lng)) })
    }

    /** The view zoomed by [delta] levels about the pixel ([px], [py]), which stays over the same place. */
    fun zoomAbout(delta: Double, px: Double, py: Double, maxZoom: Int = Mercator.MAX_ZOOM): Viewport {
        val z = (zoom + delta).coerceIn(0.0, maxZoom.toDouble())
        val c = Mercator.project(center)
        val before = Mercator.worldPx(zoom)
        val after = Mercator.worldPx(z)
        val ax = c.x + (px - width / 2) / before
        val ay = c.y + (py - height / 2) / before
        val nc = MapPoint(ax - (px - width / 2) / after, (ay - (py - height / 2) / after).coerceIn(0.0, 1.0))
        return copy(center = Mercator.unproject(nc).let { it.copy(lng = wrapLng(it.lng)) }, zoom = z)
    }

    companion object {
        /** The web's `fitBounds` padding and its highest framing level (`SpaceMap.vue`). */
        const val FIT_PADDING_PX = 50.0
        const val FIT_MAX_ZOOM = 16.0

        /**
         * The view of [width] × [height] that frames [points] with [padding] pixels to spare, at most [maxZoom] (one
         * point: at [maxZoom]). Null for no point.
         */
        fun fit(
            points: List<LatLng>,
            width: Double,
            height: Double,
            padding: Double = FIT_PADDING_PX,
            maxZoom: Double = FIT_MAX_ZOOM,
        ): Viewport? {
            if (points.isEmpty()) return null
            val ps = points.map { Mercator.project(it) }
            val minX = ps.minOf { it.x }
            val maxX = ps.maxOf { it.x }
            val minY = ps.minOf { it.y }
            val maxY = ps.maxOf { it.y }
            val c = Mercator.unproject(MapPoint((minX + maxX) / 2, (minY + maxY) / 2))
            val w = max(1.0, width - 2 * padding)
            val h = max(1.0, height - 2 * padding)
            val dx = maxX - minX
            val dy = maxY - minY
            val zoom = if (dx == 0.0 && dy == 0.0) maxZoom
            else {
                val zx = if (dx > 0) log2(w / (dx * Mercator.TILE_PX)) else maxZoom
                val zy = if (dy > 0) log2(h / (dy * Mercator.TILE_PX)) else maxZoom
                min(min(zx, zy), maxZoom).coerceAtLeast(0.0)
            }
            return Viewport(c, zoom, width, height)
        }

        private fun wrapLng(lng: Double): Double = ((lng + 180) % 360 + 360) % 360 - 180
    }
}
