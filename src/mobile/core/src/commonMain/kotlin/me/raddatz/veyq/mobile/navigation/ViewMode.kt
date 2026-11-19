package me.raddatz.veyq.mobile.navigation

import me.raddatz.veyq.mobile.session.DeviceStore
import me.raddatz.veyq.mobile.words.Msg

/**
 * How a level and a person's Memories are drawn (ADR 0149 decision 8, #950; the web's `levelViews.ts` with
 * `useViewMode.ts`): as rows or as tiles — a person's Memories on a map too. **One choice per account on this device**, for
 * every level and a person's Memories, as the web keeps one value per account (`veyq:view-mode`): kept in the device's own
 * store under `view-mode:<accountId>`, as the map consent keeps its key. The levels offer no map (the apps' Memories map is
 * the bar's tab, ADR 0143). The first view before any choice is [FIRST] — **pending the owner**: Tiles recommended (the
 * web's default), List what the apps showed before.
 */
enum class ViewMode(val word: Msg) {
    LIST(Msg.NODE_VIEW_LIST),
    TILES(Msg.NODE_VIEW_TILES),
    MAP(Msg.MEMORY_VIEW_MAP),
    ;

    companion object {
        /** The view before any choice (ADR 0149 owner question 2, pending: Tiles recommended). One constant to flip. */
        val FIRST: ViewMode = TILES

        /** What a level offers: rows and tiles (no map: the bar's Map tab is the Memories map). */
        val LEVEL: List<ViewMode> = listOf(LIST, TILES)

        /** What a person's Memories offer. */
        val PERSON: List<ViewMode> = listOf(LIST, TILES, MAP)
    }
}

/** The view choice of each account on this device (see [ViewMode]). */
class ViewModes(private val store: DeviceStore) {
    private fun key(accountId: String) = "$PREFIX$accountId"

    /**
     * The view [accountId] chose, among what a screen [offers]: the stored one where the screen offers it, else [ViewMode.FIRST]
     * where it does, else the screen's first (a level shown after a person's map was chosen shows the first view).
     */
    fun of(accountId: String, offers: List<ViewMode> = ViewMode.LEVEL): ViewMode {
        val stored = store.read(key(accountId))?.let { v -> ViewMode.entries.firstOrNull { it.name == v } }
        return stored?.takeIf { it in offers } ?: ViewMode.FIRST.takeIf { it in offers } ?: offers.first()
    }

    /** [mode] chosen by [accountId]: kept for every level and a person's Memories. */
    fun set(accountId: String, mode: ViewMode) = store.write(key(accountId), mode.name)

    /** What [accountId] chose is forgotten (the account removed from this device). */
    fun forget(accountId: String) = store.remove(key(accountId))

    companion object {
        const val PREFIX = "view-mode:"
    }
}

/**
 * The tiles' grid (ADR 0149 decision 8): at least [MIN_COLUMNS] columns on a phone, more as the window widens — each tile at
 * least [MIN_TILE] units wide with [GAP] between — every tile a square cover with the name and the row's second line below,
 * the platform's album grid. The minimum is **pending the owner** (two columns of square covers recommended over the web's
 * one column of 16:9 cards below 520 px): one constant to flip.
 */
object TileGrid {
    /** The fewest columns at any width (a phone's two; one would be the web's narrow column). */
    const val MIN_COLUMNS = 2

    /** A tile's least width in the platform's unit (dp, pt, effective px). */
    const val MIN_TILE = 160.0

    /** The gap between two tiles, across and down. */
    const val GAP = 12.0

    /** How many columns a [width] holds. */
    fun columns(width: Double): Int = maxOf(MIN_COLUMNS, ((width + GAP) / (MIN_TILE + GAP)).toInt())

    /** One tile's frame: its cover square of [size] at ([x], [y]). */
    data class Frame(val x: Double, val y: Double, val size: Double)

    /** The square cover of each of [count] tiles at [width], row by row ([labelHeight]: the name and the line below a cover). */
    fun frames(width: Double, count: Int, labelHeight: Double = 0.0): List<Frame> {
        val cols = columns(width)
        val size = (width - GAP * (cols - 1)) / cols
        return (0 until count).map { i -> Frame((i % cols) * (size + GAP), (i / cols) * (size + labelHeight + GAP), size) }
    }
}
