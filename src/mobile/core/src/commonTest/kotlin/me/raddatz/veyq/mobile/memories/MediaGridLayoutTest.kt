package me.raddatz.veyq.mobile.memories

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.blob.RangeReader
import me.raddatz.veyq.mobile.blob.UploadSource
import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.navigation.NodeAt
import me.raddatz.veyq.mobile.navigation.NodeRef
import me.raddatz.veyq.mobile.composition.ShellModel
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The justified grid every app draws, held to the web's own cases (`mediaGridLayout.test.ts`, and `MediaGrid.test.ts`'s
 * geometry: 2,000 squares in a 1,000 wide grid at the 200 target, five a row, 193.6 high, 400 rows; an 800 high view
 * mounting about three views of rows), and the gallery's rows laid out with their sealed dimensions.
 */
class MediaGridLayoutTest {
    private fun squares(n: Int) = List(n) { MediaGridLayout.Source("t$it", 1.0) }
    private fun near(expected: Double, actual: Double, what: String) = assertTrue(abs(expected - actual) < 1e-6, "$what: $expected, was $actual")

    @Test
    fun anExtremeAspectIsBoundedAndAnUnknownOneFallsBack() {
        assertEquals(4.0 / 3, MediaGridLayout.displayAspect(16, 9))
        assertEquals(3.0 / 4, MediaGridLayout.displayAspect(9, 16))
        assertEquals(4.0 / 3, MediaGridLayout.displayAspect(4, 3))
        assertEquals(3.0 / 4, MediaGridLayout.displayAspect(3, 4))
        assertEquals(1.0, MediaGridLayout.displayAspect(1, 1))
        assertEquals(4.0 / 3, MediaGridLayout.displayAspect(null, 9))
        assertEquals(4.0 / 3, MediaGridLayout.displayAspect(null, null))
        assertEquals(4.0 / 3, MediaGridLayout.displayAspect(0, 9))
    }

    @Test
    fun portraitAndLandscapeKeepVisiblyDifferentShapes() {
        val layout = MediaGridLayout.justify(
            listOf(MediaGridLayout.Source("portrait", 3.0 / 4), MediaGridLayout.Source("landscape", 16.0 / 9)), 600.0, 200.0, 8.0,
        )
        val portrait = layout.tiles.first { it.id == "portrait" }
        val landscape = layout.tiles.first { it.id == "landscape" }
        assertTrue(portrait.width < portrait.height, "a portrait tile is taller than wide")
        assertTrue(landscape.width > landscape.height, "a landscape tile is wider than tall")
    }

    @Test
    fun aFullRowIsJustifiedToExactlyTheWidthAndTheWidowKeepsTheTarget() {
        val layout = MediaGridLayout.justify(squares(5), 900.0, 200.0, 8.0)
        val last = layout.tiles.last()
        assertEquals(900, (last.left + last.width).roundToInt(), "the row ends at the container's edge")
        assertTrue(layout.tiles.all { it.top == 0.0 }, "five squares are one row at 900 / 200")
        near((900.0 - 4 * 8) / 5, layout.tiles.first().height, "the row's height fills the width")
        assertTrue(layout.tiles.none { it.widow })

        // seven: five justified, two in the widow row at the target height, not stretched
        val widow = MediaGridLayout.justify(squares(7), 900.0, 200.0, 8.0)
        val tail = widow.tiles.takeLast(2)
        assertTrue(tail.all { it.widow && it.height == 200.0 && it.width == 200.0 })
        near(widow.tiles.first().height + 8.0, tail.first().top, "the widow row sits one gap below")
        near(tail.first().top + 200.0, widow.height, "the grid ends at the widow's bottom")
        assertEquals(5, MediaGridLayout.justify(squares(7), 900.0, 200.0, 8.0, showWidows = false).tiles.size, "no widows: left out")
    }

    @Test
    fun noWidthNoTiles() {
        val layout = MediaGridLayout.justify(listOf(MediaGridLayout.Source("a", 1.0)), 0.0, 200.0, 8.0)
        assertEquals(0, layout.tiles.size)
        assertEquals(0.0, layout.height)
        assertTrue(layout.window(0.0, 800.0).isEmpty())
    }

    @Test
    fun theWebsGridGeometryAndItsWindow() {
        val layout = MediaGridLayout.justify(squares(2000), 1000.0, MediaGridLayout.targetHeight(1000.0))
        assertEquals(400, layout.rows.size, "five squares a row")
        assertTrue(layout.rows.all { it.tiles.size == 5 })
        near(193.6, layout.rows.first().height, "the row's height")
        near(400 * 193.6 + 399 * 8, layout.height, "the grid keeps the whole height")
        assertEquals("t5", layout.rows[1].key)

        val view = 800.0
        val bound = ceil(3 * view / 201.6).toInt() + 2 // `MediaGrid.test.ts`' WINDOW_MAX in rows
        val atTop = layout.window(0.0, view)
        assertEquals(0, atTop.first)
        assertTrue(atTop.count() <= bound, "about three views of rows: ${atTop.count()}")

        val scrolled = layout.window(40000.0, view)
        assertTrue(scrolled.count() <= bound)
        assertTrue(scrolled.first > 179 && scrolled.last < 219, "around row 198: $scrolled")
        assertTrue(floor(40000 / 201.6).toInt() in scrolled, "the row at the view's top")
        val first = layout.nearestFirst(scrolled, 40000 + view / 2).first()
        assertEquals(floor((40000 + view / 2) / 201.6).toInt(), first.removePrefix("t").toInt() / 5, "the first asked for is mid-view")
        assertTrue(layout.window(layout.height + 5 * view, view).isEmpty(), "past the end nothing is mounted")
    }

    @Test
    fun aPhoneAimsLowerThanAWideWindowSoTwoToFourTilesFitARow() {
        assertEquals(150.0, MediaGridLayout.targetHeight(390.0))
        assertEquals(150.0, MediaGridLayout.targetHeight(720.0))
        assertEquals(200.0, MediaGridLayout.targetHeight(721.0))
        fun perRow(aspect: Double) = MediaGridLayout.justify(List(12) { MediaGridLayout.Source("$it", aspect) }, 390.0, MediaGridLayout.targetHeight(390.0)).rows.first().tiles.size
        assertEquals(2, perRow(4.0 / 3), "landscape")
        assertEquals(3, perRow(1.0), "square")
        assertEquals(4, perRow(3.0 / 4), "portrait")
    }

    // ---- the gallery's rows, laid out ----

    private fun item(id: String, at: Long, w: Int?, h: Int?) = MemoryMedia.Item(
        "s", "m", id, blob("o-$id"), blob("t-$id"), blob("p-$id"),
        MemoryMedia.Meta(w, h, at, null, "exif", null, null, null, null), "image/jpeg", "$id.jpg", "user:u1", false, ByteArray(0),
    )

    private fun blob(id: String) = MemoryMedia.Blob(id, 1, 29L + V1.FRAME_OVERHEAD, "", null)

    private class Door(val items: List<MemoryMedia.Item>) : GalleryModel.Door {
        override val revisions = MutableStateFlow<Any?>(Any())
        override val uploaded = MutableSharedFlow<String>()
        override suspend fun gallery() = MemoryMedia.Gallery(MemoryMedia.order(items), 1, 0)
        override suspend fun scopeNodes(): List<NodeRef> = emptyList()
        override suspend fun read(item: MemoryMedia.Item, blob: MemoryMedia.Blob): ByteArray? = null
        override fun reader(item: MemoryMedia.Item): RangeReader? = null
        override suspend fun stream(item: MemoryMedia.Item, sink: suspend (ByteArray) -> Unit) = false
        override suspend fun exif(item: MemoryMedia.Item): MediaDetails.Exif? = null
        override suspend fun uploaders(items: List<MemoryMedia.Item>) = emptyMap<String, MediaDetails.Uploader>()
        override fun upload(sources: List<UploadSource>) = 0
    }

    @Test
    fun theGallerysRowsAreLaidOutByTheirSealedDimensionsInTheGridsOrder() = runTest {
        val door = Door(listOf(item("wide", 30, 4000, 3000), item("tall", 20, 3000, 4000), item("pano", 10, 8000, 1000), item("none", 5, null, null)))
        val g = GalleryModel(NodeAt("s", "m", "MEMORY", "Ostsee"), door, backgroundScope, backgroundScope).also { runCurrent() }
        val rows = g.current.rows!!
        assertEquals(listOf(4.0 / 3, 3.0 / 4, 4.0 / 3, 4.0 / 3), rows.map { it.aspect }, "the sealed dimensions, bounded")
        val layout = GalleryModel.layout(rows, 390.0)
        assertEquals(rows.map { it.mediaId }, layout.tiles.map { it.id }, "the grid's order")
        val first = layout.rows.first()
        assertEquals(listOf("wide", "tall", "pano"), first.tiles.map { it.id }, "4/3 + 3/4 + 4/3 (bounded) reach 390 / 150")
        assertEquals(390, (first.tiles.last().left + first.tiles.last().width).roundToInt())
        assertTrue(first.tiles[0].width > first.tiles[1].width, "the landscape tile is the wider")
        assertEquals(listOf("none"), layout.rows[1].tiles.map { it.id }, "the widow row")
        g.close()
    }

    @Test
    fun aLightboxOpenedOnAMediumBeforeTheFirstReadShowsThatMedium() = runTest {
        val door = Door(listOf(item("a", 30, 1, 1), item("b", 20, 1, 1), item("c", 10, 1, 1)))
        val g = GalleryModel(NodeAt("s", "m", "MEMORY", "Ostsee"), door, backgroundScope, backgroundScope)
        val box = g.lightboxOn("b") // before the first read
        runCurrent()
        assertEquals("b", box.current.row?.mediaId)
        assertEquals(1, box.current.index)
        val later = g.lightboxOn("c") // after it
        assertEquals("c", later.current.row?.mediaId)
        assertTrue(box.current.closed, "one at a time")
        val missing = g.lightboxOn("gone")
        assertEquals("a", missing.current.row?.mediaId, "a medium not there: the first")
        g.close()
    }

    @Test
    fun aGalleryIsOpenedByItsIdsOnlyForAMemoryOrAChapterListed() {
        val nodes = listOf(
            NodeRef("s", "m", null, "MEMORY", "Ostsee"),
            NodeRef("s", "c", "m", "CHAPTER", "Day one"),
            NodeRef("s", "f", null, "FOLDER", "Papers"),
        )
        assertEquals(NodeAt("s", "m", "MEMORY", "Ostsee"), ShellModel.galleryAt(nodes, "s", "m"))
        assertEquals(NodeAt("s", "c", "CHAPTER", "Day one"), ShellModel.galleryAt(nodes, "s", "c"))
        assertNull(ShellModel.galleryAt(nodes, "s", "f"), "a folder is no gallery")
        assertNull(ShellModel.galleryAt(nodes, "other", "m"), "another space's")
        assertNull(ShellModel.galleryAt(nodes, "s", "x"), "not listed")
    }
}
