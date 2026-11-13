package me.raddatz.veyq.mobile.memories

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The justified gallery grid's geometry, the web's `features/memories/mediaGridLayout.ts` ported once for every app (ADR
 * 0130 decision 1: a layout rule is logic, drawing it the platform's). Rows of aspect-kept tiles are justified to the
 * container's width (Google Photos' and Flickr's look); the last, widow row keeps the target height instead of stretching.
 * A medium's aspect comes from its sealed dimensions ([GalleryModel.Row.aspect]), bounded by [displayAspect] so one extreme
 * photo cannot take a row. Pure arithmetic in the platform's own unit (CSS px, dp, pt — one each, never pixels).
 *
 * **Only what is near the viewport is drawn** (ADR 0105 decision 1): the layout is computed for the whole list, so the
 * scroll extent keeps its truth, and [Layout.window] names the rows a view mounts — the viewport's plus [OVERSCAN] viewports
 * each way (`MediaGrid.vue`'s `windowRows`). A platform whose lazy list mounts only what it shows (Compose's `LazyColumn`,
 * SwiftUI's `LazyVStack`) draws [Layout.rows] through it and has the same windowing for free; a desktop that places tiles
 * itself asks [Layout.window].
 *
 * `MediaGridLayoutTest` holds it to the web's own cases (`mediaGridLayout.test.ts` and `MediaGrid.test.ts`'s geometry).
 */
object MediaGridLayout {
    /** The gap between tiles and rows (`MediaGrid.vue`'s default, which every web gallery uses). */
    const val GAP = 8.0

    /** Rows of the viewport's height mounted beyond it each way (`MediaGrid.vue`'s `OVERSCAN`). */
    const val OVERSCAN = 1.0

    /** The aspect of a medium whose dimensions are unknown. */
    const val FALLBACK_ASPECT = 4.0 / 3.0
    private const val MIN_ASPECT = 3.0 / 4.0
    private const val MAX_ASPECT = 4.0 / 3.0

    /** Up to this width a row aims at [NARROW_HEIGHT], above it at [WIDE_HEIGHT] (`MediaGrid.vue`'s default target). */
    const val NARROW_WIDTH = 720.0
    const val NARROW_HEIGHT = 150.0
    const val WIDE_HEIGHT = 200.0

    /**
     * The row height a grid [width] wide aims at: [NARROW_HEIGHT] up to [NARROW_WIDTH] — a phone, two to four tiles a row —,
     * [WIDE_HEIGHT] above.
     */
    fun targetHeight(width: Double): Double = if (width <= NARROW_WIDTH) NARROW_HEIGHT else WIDE_HEIGHT

    /** One medium to place: its id and its display aspect (width / height; null or not positive: [FALLBACK_ASPECT]). */
    class Source(val id: String, val aspect: Double?)

    /** One placed tile: its frame in the grid ([left], [top] from the grid's own origin). */
    data class Tile(val id: String, val left: Double, val top: Double, val width: Double, val height: Double, val widow: Boolean)

    /** One row of the grid: its tiles, which share its [top] and [height]. */
    data class Row(val index: Int, val top: Double, val height: Double, val tiles: List<Tile>) {
        val bottom: Double get() = top + height
        /** The row's key for a lazy list: its first tile's id (`MediaGrid.vue`'s). */
        val key: String get() = tiles.first().id
    }

    /** The grid laid out: every tile in the list's order, and the grid's whole [height]. */
    class Layout(val tiles: List<Tile>, val height: Double) {
        /** The tiles grouped in their rows (tiles sharing a top), top to bottom. */
        val rows: List<Row> by lazy {
            val out = ArrayList<Row>()
            var start = 0
            while (start < tiles.size) {
                val top = tiles[start].top
                var end = start + 1
                while (end < tiles.size && tiles[end].top == top) end++
                val slice = tiles.subList(start, end)
                out += Row(out.size, top, slice.maxOf { it.height }, slice.toList())
                start = end
            }
            out
        }

        /** The first row whose bottom lies below [y] (the rows' count where none does). */
        fun rowAt(y: Double): Int {
            val rs = rows
            var lo = 0
            var hi = rs.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (rs[mid].bottom <= y) lo = mid + 1 else hi = mid
            }
            return lo
        }

        /**
         * The rows a view mounts while the grid's part from [top] down [viewHeight] is on screen: those, and [OVERSCAN] views
         * each way; empty where the grid holds none or the view lies past its end.
         */
        fun window(top: Double, viewHeight: Double): IntRange {
            val rs = rows
            if (rs.isEmpty()) return IntRange.EMPTY
            val from = rowAt(top - OVERSCAN * viewHeight)
            val to = min(rs.size - 1, max(from, rowAt(top + viewHeight + OVERSCAN * viewHeight)))
            return from..to
        }

        /** The ids of the tiles in rows [range], nearest to the view's centre at [centre] first (what to read first). */
        fun nearestFirst(range: IntRange, centre: Double): List<String> =
            range.flatMap { rows[it].tiles }.sortedBy { abs(it.top + it.height / 2 - centre) }.map { it.id }
    }

    /**
     * [items] laid out in rows [containerWidth] wide, each aiming at [targetHeight] (`calculateJustifiedMediaLayout`): a row
     * closes once its aspects reach the container's (width / target height) and is justified to fill the width exactly;
     * the rest, the widow row, keeps the target height — or is left out where [showWidows] is false.
     */
    fun justify(
        items: List<Source>, containerWidth: Double, targetHeight: Double, gap: Double = GAP, showWidows: Boolean = true,
    ): Layout {
        val width = max(0.0, floor(containerWidth))
        val target = max(0.0, targetHeight)
        val g = max(0.0, gap)
        if (items.isEmpty() || width <= 0.0 || target <= 0.0) return Layout(emptyList(), 0.0)

        val targetAspect = width / target
        val tiles = ArrayList<Tile>(items.size)
        val row = ArrayList<Pair<String, Double>>()
        var rowAspect = 0.0
        var top = 0.0
        for (item in items) {
            val aspect = safe(item.aspect)
            row += item.id to aspect
            rowAspect += aspect
            if (rowAspect >= targetAspect) {
                val height = layoutRow(row, top, width, target, g, justify = true, into = tiles)
                top += height + g
                row.clear()
                rowAspect = 0.0
            }
        }
        if (row.isNotEmpty() && showWidows) {
            top += layoutRow(row, top, width, target, g, justify = false, into = tiles)
        } else if (row.isEmpty() && tiles.isNotEmpty()) {
            top -= g
        }
        return Layout(tiles, max(top, 0.0))
    }

    private fun layoutRow(
        row: List<Pair<String, Double>>, top: Double, width: Double, target: Double, gap: Double, justify: Boolean, into: MutableList<Tile>,
    ): Double {
        val available = max(width - gap * max(row.size - 1, 0), 0.0)
        val aspects = row.sumOf { it.second }
        val height = if (justify && aspects > 0) available / aspects else target
        var left = 0.0
        row.forEachIndexed { i, (id, aspect) ->
            val raw = aspect * height
            // the last tile of a justified row ends exactly at the container's edge
            val w = if (justify && i == row.size - 1) max(width - left, 0.0) else raw
            into += Tile(id, left, top, w, height, widow = !justify)
            left += w + gap
        }
        return height
    }

    /**
     * A medium [width] × [height]'s aspect as the grid shows it (`galleryDisplayAspectRatio`): bounded to 3:4 … 4:3, so one
     * panorama cannot take a row; [FALLBACK_ASPECT] where a dimension is unknown.
     */
    fun displayAspect(width: Int?, height: Int?): Double {
        if (width == null || height == null || width <= 0 || height <= 0) return FALLBACK_ASPECT
        val aspect = width.toDouble() / height
        if (!aspect.isFinite() || aspect <= 0) return FALLBACK_ASPECT
        return min(MAX_ASPECT, max(MIN_ASPECT, aspect))
    }

    private fun safe(value: Double?): Double = if (value != null && value.isFinite() && value > 0) value else FALLBACK_ASPECT
}
