package me.raddatz.veyq.mobile.memories

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.blob.RangeReader
import me.raddatz.veyq.mobile.blob.UploadSource
import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.depot.FileSave
import me.raddatz.veyq.mobile.navigation.NodeAt
import me.raddatz.veyq.mobile.navigation.NodeRef
import me.raddatz.veyq.mobile.words.Msg
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A Memory's page and its lightbox both phones drew (ADR 0130 decision 2; the CR's phase 1 moved them into the core) — over
 * a gallery the test plays: the rows and the tiers they read, when the gallery reads anew, a Memory's chapters, the lightbox's
 * index across a re-read, a video's reader and when it closes, a blocked medium, the uploaders read once, the original saved.
 */
class GalleryModelTest {
    private val memory = NodeAt("s", "m", "MEMORY", "Ostsee 2026")
    private val chapter = NodeAt("s", "c1", "CHAPTER", "Day one")

    private fun blob(id: String, size: Int = 29) = MemoryMedia.Blob(id, 1, size.toLong() + V1.FRAME_OVERHEAD, "", null)

    private fun item(
        id: String, at: Long? = null, type: String = "image/jpeg", thumbnail: Boolean = true, preview: Boolean = true,
        owner: String? = "user:u1", blocked: Boolean = false, originalSize: Int = 29, name: String? = "$id.jpg",
    ) = MemoryMedia.Item(
        "s", "m", id, blob("o-$id", originalSize), if (thumbnail) blob("t-$id") else null, if (preview) blob("p-$id") else null,
        at?.let { MemoryMedia.Meta(4000, 3000, it, null, "exif", null, null, null, null) }, type, name, owner, blocked, ByteArray(0),
    )

    /** A played gallery: its media, what each read asked for, the space's nodes. */
    private class Door(var items: List<MemoryMedia.Item>?) : GalleryModel.Door {
        val lists = MutableStateFlow<Any?>(Any())
        override val revisions = lists
        override val uploaded = MutableSharedFlow<String>(extraBufferCapacity = 8)
        var listings = 0
        var nodeReads = 0
        val reads = mutableListOf<String>()
        val readers = mutableListOf<RangeReader>()
        var exifReads = 0
        val uploaderReads = mutableListOf<Set<String>>()
        var nodes = listOf(
            NodeRef("s", "c1", "m", "CHAPTER", "Day one"),
            NodeRef("s", "c2", "m", "CHAPTER", "Day two"),
            NodeRef("s", "f", "m", "FOLDER", "Not a chapter"),
            NodeRef("s", "x", "other", "CHAPTER", "Another Memory's"),
        )
        val original = ByteArray(3 * V1.FRAME_PLAINTEXT + 17) { (it % 251).toByte() }
        var uploads = 0

        override suspend fun gallery(): MemoryMedia.Gallery? {
            listings++
            return items?.let { MemoryMedia.Gallery(MemoryMedia.order(it), listings, 0) }
        }
        override suspend fun scopeNodes(): List<NodeRef> {
            nodeReads++
            return nodes
        }
        override suspend fun read(item: MemoryMedia.Item, blob: MemoryMedia.Blob): ByteArray? {
            reads += blob.blobId
            return blob.blobId.encodeToByteArray()
        }
        override fun reader(item: MemoryMedia.Item): RangeReader =
            RangeReader(original.size.toLong(), 4) { first, until, sink ->
                for (i in first until until) sink(original.copyOfRange(i * V1.FRAME_PLAINTEXT, minOf(original.size, (i + 1) * V1.FRAME_PLAINTEXT)))
            }.also { readers += it }
        override suspend fun stream(item: MemoryMedia.Item, sink: suspend (ByteArray) -> Unit): Boolean {
            original.toList().chunked(V1.FRAME_PLAINTEXT).forEach {
                sink(it.toByteArray())
                kotlinx.coroutines.yield() // a frame fetched: a watcher sees each step
            }
            return true
        }
        override suspend fun exif(item: MemoryMedia.Item): MediaDetails.Exif? {
            exifReads++
            return null
        }
        override suspend fun uploaders(items: List<MemoryMedia.Item>): Map<String, MediaDetails.Uploader> {
            val keys = items.mapNotNull { it.ownerKey }.toSet()
            uploaderReads += keys
            return keys.associateWith { if (it.startsWith("share:")) MediaDetails.Uploader(MediaDetails.UploaderKind.GUEST) else MediaDetails.Uploader(MediaDetails.UploaderKind.MEMBER, "Anna") }
        }
        override fun upload(sources: List<UploadSource>): Int {
            uploads += sources.size
            return sources.size
        }
    }

    private fun TestScope.open(door: Door, at: NodeAt = memory): GalleryModel =
        GalleryModel(at, door, backgroundScope, backgroundScope).also { runCurrent() }

    @Test
    fun theRowsAreTheGridsOrderEachReadingItsOwnTier() = runTest {
        val door = Door(
            MemoryMedia.order(
                listOf(
                    item("a", at = 10),
                    item("b", at = 30),
                    item("small", at = 20, thumbnail = false, preview = false), // a small original is its own tile and picture
                    item("big", at = 5, thumbnail = false, preview = false, originalSize = MemoryMedia.GRID_ORIGINAL_MAX.toInt() + 1),
                    item("clip", type = "video/mp4", thumbnail = false, preview = false), // a video without a poster
                ),
            ),
        )
        val g = open(door)
        val rows = assertNotNull(g.current.rows)
        assertEquals(listOf("b", "small", "a", "big", "clip"), rows.map { it.mediaId }, "the grid's order, an undated row last")
        assertEquals(5, g.current.count)
        assertContentEquals("t-b".encodeToByteArray(), g.tile("b"), "a tile is the thumbnail")
        assertContentEquals("o-small".encodeToByteArray(), g.tile("small"), "a small original without a thumbnail is its own tile")
        assertNull(g.tile("big"), "a large original is no tile: the icon")
        assertNull(g.tile("clip"), "a video without a poster has no tile")
        assertEquals(listOf("t-b", "o-small"), door.reads)
        val box = g.lightbox(0)
        assertContentEquals("p-b".encodeToByteArray(), box.picture("b"), "the lightbox shows the preview")
        assertContentEquals("o-big".encodeToByteArray(), box.picture("big"), "else the original")
        assertNull(box.picture("clip"), "a video without a poster shows no picture")
        assertTrue(rows.last().isVideo)
        assertNull(rows.last().picture)
        assertEquals("p-b", rows.first().picture)
        assertEquals("t-b", rows.first().tile)
    }

    @Test
    fun itReadsAnewOnTheListsRevisionAndOnAnOwnUploadIntoItNotOtherwise() = runTest {
        val door = Door(listOf(item("a", at = 1)))
        val g = open(door)
        assertEquals(1, door.listings)
        // the shell's lists read anew: the gallery with them
        door.items = listOf(item("a", at = 1), item("b", at = 2))
        door.lists.value = Any()
        runCurrent()
        assertEquals(2, door.listings)
        assertEquals(listOf("b", "a"), g.current.rows?.map { it.mediaId })
        // a medium this device uploaded into this node is in
        door.uploaded.tryEmit("m")
        runCurrent()
        assertEquals(3, door.listings)
        // one uploaded into another node is not this gallery's
        door.uploaded.tryEmit("other")
        runCurrent()
        assertEquals(3, door.listings, "another node's upload reads nothing")
        // a read that fails keeps what is shown and says so
        door.items = null
        door.lists.value = Any()
        runCurrent()
        assertEquals(listOf("b", "a"), g.current.rows?.map { it.mediaId })
        assertTrue(g.current.failed)
        g.close()
        door.lists.value = Any()
        runCurrent()
        assertEquals(4, door.listings, "a closed page reads nothing")
    }

    @Test
    fun aMemoryShowsItsChaptersAChapterNone() = runTest {
        val door = Door(emptyList())
        val g = open(door)
        assertEquals(listOf("Day one", "Day two"), g.current.chapters.map { it.name })
        assertEquals(1, door.nodeReads)
        val c = Door(emptyList())
        val inChapter = open(c, chapter)
        assertEquals(emptyList(), inChapter.current.chapters)
        assertEquals(0, c.nodeReads, "a chapter reads no nodes for chapters it cannot hold")
        assertEquals(emptyList(), inChapter.current.rows?.map { it.mediaId })
    }

    @Test
    fun theLightboxKeepsItsMediumByIdAcrossARereadAndGivesWayToItsNeighbour() = runTest {
        val door = Door(listOf(item("c", at = 3), item("b", at = 2), item("a", at = 1)))
        val g = open(door)
        val box = g.lightbox(1)
        assertEquals("b", box.current.row?.mediaId)
        // a newer medium came in before it: the index moves, the medium stays
        door.items = listOf(item("d", at = 4), item("c", at = 3), item("b", at = 2), item("a", at = 1))
        door.lists.value = Any()
        runCurrent()
        assertEquals(2, box.current.index)
        assertEquals("b", box.current.row?.mediaId)
        assertEquals(4, box.current.count)
        // it went: the one now at its place shows
        door.items = listOf(item("d", at = 4), item("c", at = 3), item("a", at = 1))
        door.lists.value = Any()
        runCurrent()
        assertEquals("a", box.current.row?.mediaId)
        // the last went too: the one before it
        door.items = listOf(item("d", at = 4), item("c", at = 3))
        door.lists.value = Any()
        runCurrent()
        assertEquals("c", box.current.row?.mediaId)
        door.items = emptyList()
        door.lists.value = Any()
        runCurrent()
        assertNull(box.current.row)
        assertEquals(-1, box.current.index)
    }

    @Test
    fun aVideosReaderOpensOnlyForAVideoAndClosesWhenPagedAwayOrTheLightboxCloses() = runTest {
        val door = Door(listOf(item("v1", at = 3, type = "video/mp4"), item("p", at = 2), item("v2", at = 1, type = "video/mp4")))
        val g = open(door)
        val box = g.lightbox(1)
        assertNull(box.play(), "a picture opens no reader")
        assertTrue(door.readers.isEmpty())
        box.go(0)
        val reader = assertNotNull(box.play())
        assertSame(reader, box.current.reader)
        assertTrue(box.current.playing)
        assertSame(reader, box.play(), "playing already: the same reader")
        assertEquals(1, door.readers.size)
        assertContentEquals(door.original.copyOfRange(10, 30), reader.read(10, 20))
        box.go(1)
        assertTrue(reader.isClosed, "paged away: its reader closed")
        assertFalse(box.current.playing)
        assertFailsWith<IllegalStateException> { reader.read(0, 1) }
        box.go(2)
        val second = assertNotNull(box.play())
        box.stop()
        assertTrue(second.isClosed, "the player went: closed")
        val third = assertNotNull(box.play())
        box.close()
        assertTrue(third.isClosed, "the lightbox closed: closed")
        assertNull(box.play(), "a closed lightbox opens nothing")
        val again = g.lightbox(0)
        val fourth = assertNotNull(again.play())
        g.close()
        assertTrue(fourth.isClosed, "the gallery closed (its tenant with it): closed")
    }

    @Test
    fun aRereadThatTakesThePlayingVideoAwayClosesItsReader() = runTest {
        val door = Door(listOf(item("v", at = 2, type = "video/mp4"), item("p", at = 1)))
        val g = open(door)
        val box = g.lightbox(0)
        val reader = assertNotNull(box.play())
        door.items = listOf(item("v", at = 2, type = "video/mp4"), item("p", at = 1), item("q", at = 0))
        door.lists.value = Any()
        runCurrent()
        assertFalse(reader.isClosed, "still on the page: it plays on")
        door.items = listOf(item("p", at = 1))
        door.lists.value = Any()
        runCurrent()
        assertTrue(reader.isClosed, "the video went: its reader closed")
        assertEquals("p", box.current.row?.mediaId)
    }

    @Test
    fun aBlockedMediumIsSaidAndOffersNothing() = runTest {
        val door = Door(listOf(item("x", at = 1, blocked = true, type = "video/mp4"), item("ok", at = 0)))
        val g = open(door)
        val blocked = g.current.rows!!.first()
        assertEquals(Msg.MEMORY_BLOCKED, blocked.said)
        assertNull(g.current.rows!!.last().said)
        assertNull(blocked.tile)
        assertNull(blocked.picture)
        assertNull(g.tile("x"))
        val box = g.lightbox(0)
        assertNull(box.picture("x"))
        assertNull(box.play(), "a blocked video opens no reader")
        assertNull(box.save { error("nothing is written") }, "a blocked original is not saved")
        assertTrue(door.reads.isEmpty() && door.readers.isEmpty())
    }

    @Test
    fun theUploadersAreReadOncePerGalleryAndTheExifOncePerMedium() = runTest {
        val door = Door(listOf(item("a", at = 3), item("b", at = 2, owner = "share:g1"), item("c", at = 1, owner = "user:u2")))
        val g = open(door)
        val box = g.lightbox(0)
        val a = assertNotNull(box.details("a"))
        assertEquals(MediaDetails.UploaderKind.MEMBER, a.uploader?.kind)
        val b = assertNotNull(box.details("b"))
        assertEquals(MediaDetails.UploaderKind.GUEST, b.uploader?.kind)
        box.details("c")
        box.details("a")
        assertEquals(listOf(setOf("user:u1", "share:g1", "user:u2")), door.uploaderReads, "one request for every owner key")
        assertEquals(3, door.exifReads, "the EXIF tier once per medium")
        // a medium that came since, by someone not asked yet: asked with the next
        door.items = listOf(item("d", at = 4, owner = "user:u3")) + door.items!!
        door.lists.value = Any()
        runCurrent()
        box.details("d")
        assertEquals(setOf("user:u3"), door.uploaderReads.last())
        assertEquals(2, door.uploaderReads.size)
        assertEquals(MediaDetails.uploaderAt(a.rows), a.uploaderAt)
        assertNull(box.details("gone"))
    }

    @Test
    fun theOriginalIsSavedUnderItsNameWithItsProgressAndACancelLeavesNothing() = runTest {
        val door = Door(listOf(item("b", at = 2, name = null), item("a", at = 1, name = "beach.jpg")))
        val g = open(door)
        val box = g.lightbox(0)
        val written = mutableListOf<ByteArray>()
        assertEquals("Ostsee 2026 1.jpg", box.downloadName(), "what a save dialog proposes")
        val save = assertNotNull(box.save { produce -> produce { written += it } })
        assertEquals("Ostsee 2026 1.jpg", save.name, "no name of its own: the Memory's and its place")
        assertSame(save, box.current.save)
        val seen = mutableListOf<Long>()
        backgroundScope.launch { save.state.collect { seen += it.written } }
        runCurrent()
        assertEquals(FileSave.End.SAVED, save.current.end)
        assertContentEquals(door.original, written.fold(ByteArray(0)) { acc, b -> acc + b })
        assertEquals(door.original.size.toLong(), save.current.written)
        assertTrue(seen.size > 2, "its progress, a frame at a time: $seen")
        assertEquals(Msg.DOWNLOAD_DONE, save.current.notice.msg)
        // the second, cancelled: the sink's producer is stopped, nothing kept
        box.go(1)
        var kept: Boolean? = null
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val second = assertNotNull(
            box.save { produce ->
                try {
                    produce { gate.complete(Unit) }.also { kept = it }
                } finally {
                    if (kept == null) kept = false
                }
            },
        )
        assertEquals("beach.jpg", second.name)
        gate.await()
        assertTrue(second.cancel())
        assertEquals(FileSave.End.CANCELLED, second.current.end)
        assertEquals(false, kept, "nothing kept where the person chose")
        box.close()
        assertEquals(FileSave.End.CANCELLED, second.current.end)
    }

    @Test
    fun whatItAddsGoesThroughTheAppsUploads() = runTest {
        val door = Door(emptyList())
        val g = open(door)
        assertEquals(2, g.upload(listOf(UploadSource.of("a.jpg", 1, "image/jpeg", null) { _, n -> ByteArray(n) }, UploadSource.of("b.mp4", 1, "video/mp4", null) { _, n -> ByteArray(n) })))
        assertEquals(2, door.uploads)
    }
}
