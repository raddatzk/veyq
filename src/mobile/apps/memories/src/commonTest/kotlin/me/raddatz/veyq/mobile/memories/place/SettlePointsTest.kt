package me.raddatz.veyq.mobile.memories.place

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.blob.RangeReader
import me.raddatz.veyq.mobile.blob.UploadSource
import me.raddatz.veyq.mobile.memories.GalleryModel
import me.raddatz.veyq.mobile.memories.MediaDetails
import me.raddatz.veyq.mobile.memories.MemoryMedia
import me.raddatz.veyq.mobile.memories.MemoryRights
import me.raddatz.veyq.mobile.navigation.NodeAt
import me.raddatz.veyq.mobile.navigation.NodeRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The page's settle points (uc.memory.date § How auto is derived, uc.memory.place A2; ADR 0143 D5): its opening (once,
 * on the first listing the server answered) and a medium's trash settle the node's `auto` range and place over the media
 * listed then — only where whoever looks may edit the meta and online —; the facts line reads anew where it wrote. And
 * a GPX track's original is kept like a video's (D9): its Map view lists it offline.
 */
class SettlePointsTest {
    private val memory = NodeAt("s", "m", "MEMORY", "Ostsee")

    private fun item(id: String) = MemoryMedia.Item(
        "s", "m", id, MemoryMedia.Blob("o-$id", 1, 100, "", null), null, null,
        MemoryMedia.Meta(10, 10, 1_700_000_000_000, null, "exif", 54.1, 12.1, null, null), "image/jpeg", "$id.jpg", "user:u1", false, ByteArray(0),
    )

    private class Door(val items: List<MemoryMedia.Item>) : GalleryModel.Door {
        override val revisions = MutableStateFlow<Any?>(Any())
        override val uploaded = MutableSharedFlow<String>(extraBufferCapacity = 8)
        val reach = MutableStateFlow(true)
        override val online get() = reach
        val settles = mutableListOf<List<String>>()
        var facts = MemoryFacts.Facts.NONE
        var offline: me.raddatz.veyq.mobile.session.OfflineAsOf? = null
        override suspend fun gallery() = MemoryMedia.Gallery(items, 1, 0, offline)
        override suspend fun scopeNodes() = emptyList<NodeRef>()
        override suspend fun read(item: MemoryMedia.Item, blob: MemoryMedia.Blob): ByteArray? = null
        override fun reader(item: MemoryMedia.Item): RangeReader? = null
        override suspend fun stream(item: MemoryMedia.Item, sink: suspend (ByteArray) -> Unit) = false
        override suspend fun exif(item: MemoryMedia.Item): MediaDetails.Exif? = null
        override suspend fun uploaders(items: List<MemoryMedia.Item>) = emptyMap<String, MediaDetails.Uploader>()
        override fun upload(sources: List<UploadSource>) = 0
        override suspend fun trash(mediaId: String) = GalleryModel.Removal.DONE
        override suspend fun facts() = facts
        override suspend fun settle(items: List<MemoryMedia.Item>): Boolean {
            settles += items.map { it.mediaId }
            facts = MemoryFacts.Facts(MemoryDate(MemoryDate.Mode.AUTO, "2023-11-14", "2023-11-14"), null)
            return true
        }
    }

    private fun TestScope.open(door: Door, access: MemoryRights.Access = MemoryRights.Access.Member()) =
        GalleryModel(memory, door, backgroundScope, backgroundScope, access).also { runCurrent() }

    @Test
    fun theOpeningAndATrashSettleOverTheMediaListedThenAndTheFactsLineFollows() = runTest {
        val door = Door(listOf(item("a"), item("b")))
        val g = open(door)
        assertEquals(listOf(listOf("a", "b")), door.settles, "the opening settles once")
        assertEquals("2023-11-14", g.current.facts?.date?.from, "the facts line read anew after it wrote")
        g.reload()
        runCurrent()
        assertEquals(1, door.settles.size, "a re-read is no settle point")
        g.trash("a")
        runCurrent()
        assertEquals(listOf("b"), door.settles.last(), "a trash settles over the media left")
        advanceTimeBy(60_000)
    }

    @Test
    fun noSettleWhereTheMetaMayNotBeEditedOrOffline() = runTest {
        val guest = Door(listOf(item("a")))
        open(guest, MemoryRights.Access.Guest(me.raddatz.veyq.mobile.session.ShareLinks.READ))
        assertEquals(emptyList(), guest.settles, "a reading guest writes no meta")
        val offline = Door(listOf(item("a"))).also { it.reach.value = false; it.offline = me.raddatz.veyq.mobile.session.OfflineAsOf(1L) }
        open(offline)
        assertTrue(offline.settles.isEmpty(), "offline nothing is written")
    }

    @Test
    fun aGpxTracksOriginalIsKeptLikeAVideosAndAPhotosIsNot() {
        val gpx = MemoryMedia.Item(
            "s", "m", "t", MemoryMedia.Blob("o-t", 1, 100, "", null), null, null, null, "application/gpx+xml", "Ride.gpx", null, false, ByteArray(0),
        )
        assertTrue(MemoryMedia.playsOriginal(gpx) && MemoryMedia.keeps(gpx, gpx.original), "a track's original is kept")
        val photo = item("p")
        assertEquals(false, MemoryMedia.keeps(photo, photo.original), "a photo's original never is")
    }
}
