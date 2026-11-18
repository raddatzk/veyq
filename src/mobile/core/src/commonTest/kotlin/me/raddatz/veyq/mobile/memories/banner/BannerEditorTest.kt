package me.raddatz.veyq.mobile.memories.banner

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.blob.RangeReader
import me.raddatz.veyq.mobile.blob.UploadSource
import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.memories.GalleryModel
import me.raddatz.veyq.mobile.memories.MediaDetails
import me.raddatz.veyq.mobile.memories.MemoryMedia
import me.raddatz.veyq.mobile.memories.MemoryRights
import me.raddatz.veyq.mobile.memories.place.MemoryFacts
import me.raddatz.veyq.mobile.navigation.NodeAt
import me.raddatz.veyq.mobile.navigation.NodeRef
import me.raddatz.veyq.mobile.session.OfflineAsOf
import me.raddatz.veyq.mobile.session.ShareLinks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The banner on a Memory's page (ADR 0144 decisions 4–7): what the page shows of it, its dialog — a pick refused or
 * decoded, **Save banner** once a file is picked, its progress, a cancel that leaves the old banner, a peer's change said —
 * and the trash of the banner's picture asked first (A2: the banner stays, *Remove the banner too* offered; declined,
 * nothing is deleted). Offered only to whoever may set it, online.
 */
class BannerEditorTest {
    private val digest = "ungWv48Bz-pBQUDeXa4iI7ADYaOWF3qctBD_YfIAFa0"
    private val other = "47DEQpj8HBSa-_TImW-5JCeuQeRkm5NMpJWZG3hSuFU"

    private fun blob(id: String) = MemoryMedia.Blob(id, 1, 29L + V1.FRAME_OVERHEAD, "", null)
    private fun item(id: String, at: Long, sha256: String?) = MemoryMedia.Item(
        "s", "m", id, blob("o-$id"), blob("t-$id"), blob("p-$id"),
        MemoryMedia.Meta(4000, 3000, at, null, "exif", null, null, null, null), "image/jpeg", "$id.jpg", "user:u1", false, ByteArray(0), sha256 = sha256,
    )

    private fun banner(sha256: String?) = Banner.Read(Banner.Medium.IMAGE, Banner.Ref("bb", 1, 100, "w"), null, null, sha256, Banner.Frame.FIT, Banner.CENTRE)

    private class Door(val items: List<MemoryMedia.Item>, var banner: Banner.Read?) : GalleryModel.Door {
        override val revisions = MutableStateFlow<Any?>(Any())
        override val uploaded = MutableSharedFlow<String>(extraBufferCapacity = 8)
        var offline: OfflineAsOf? = null
        val sent = mutableListOf<String>()
        var setAnswer: BannerWrite.Outcome = BannerWrite.Outcome.Done
        var setGate: CompletableDeferred<Unit>? = null
        override suspend fun gallery() = MemoryMedia.Gallery(MemoryMedia.order(items.filter { "trash:${it.mediaId}" !in sent }), 1, 0, offline)
        override suspend fun scopeNodes() = emptyList<NodeRef>()
        override suspend fun read(item: MemoryMedia.Item, blob: MemoryMedia.Blob): ByteArray? = null
        override fun reader(item: MemoryMedia.Item): RangeReader? = null
        override suspend fun stream(item: MemoryMedia.Item, sink: suspend (ByteArray) -> Unit) = false
        override suspend fun exif(item: MemoryMedia.Item): MediaDetails.Exif? = null
        override suspend fun uploaders(items: List<MemoryMedia.Item>) = emptyMap<String, MediaDetails.Uploader>()
        override fun upload(sources: List<UploadSource>) = 0
        override suspend fun facts() = MemoryFacts.Facts(null, null, banner = banner, bannerSlots = Banner.Slots(banner?.blob?.blobId, banner?.poster?.blobId))
        override suspend fun trash(mediaId: String): GalleryModel.Removal {
            sent += "trash:$mediaId"
            return GalleryModel.Removal.DONE
        }
        override suspend fun bannerPicture() = "picture".encodeToByteArray()
        override suspend fun setBanner(openedWith: Banner.Slots, picked: BannerWrite.Picked, items: List<MemoryMedia.Item>, progress: (Double) -> Unit): BannerWrite.Outcome {
            sent += "set:${openedWith.blobId}:${picked.medium}:${items.size}"
            progress(0.5)
            setGate?.await()
            return setAnswer
        }
        override suspend fun removeBanner(openedWith: Banner.Slots): BannerWrite.Outcome {
            sent += "remove:${openedWith.blobId}"
            banner = null
            return BannerWrite.Outcome.Done
        }
    }

    private fun TestScope.open(door: Door, access: MemoryRights.Access = MemoryRights.Access.Member()) =
        GalleryModel(NodeAt("s", "m", "MEMORY", "Ostsee"), door, backgroundScope, backgroundScope, access).also { runCurrent() }

    private object Pictures : BannerWrite.Pictures {
        override suspend fun image(source: UploadSource) = byteArrayOf(1)
        override suspend fun poster(source: UploadSource) = byteArrayOf(2)
    }

    private fun file(name: String, type: String, size: Long = 10) = UploadSource.of(name, size, type, null) { _, n -> ByteArray(n) }

    @Test
    fun thePageShowsTheBannersPictureAndOffersItsDialogToWhoeverMaySetItOnline() = runTest {
        val door = Door(emptyList(), banner(null))
        val g = open(door)
        assertEquals(Banner.Shown(Banner.Medium.IMAGE, "bb", null), g.current.banner)
        assertEquals("picture", g.bannerPicture()?.decodeToString())
        assertNull(g.bannerReader(), "an image plays nothing")
        assertNotNull(g.bannerEditor(Pictures))
        // a guest sees it as a member does, and edits nothing (decision 6)
        val guest = open(Door(emptyList(), banner(null)), MemoryRights.Access.Guest(ShareLinks.READ))
        assertEquals(g.current.banner, guest.current.banner)
        assertNull(guest.bannerEditor(Pictures))
        // offline: shown, not edited (decision 7)
        val off = Door(emptyList(), banner(null)).also { it.offline = OfflineAsOf(1) }
        val o = open(off)
        assertEquals(g.current.banner, o.current.banner)
        assertNull(o.bannerEditor(Pictures))
    }

    @Test
    fun aPickRefusedIsSaidSaveAppearsOnceAFileIsPickedAndTheSaveRunsWithItsProgress() = runTest {
        val door = Door(listOf(item("a", 1, null)), banner(null))
        val g = open(door)
        val e = assertNotNull(g.bannerEditor(Pictures))
        assertTrue(e.current.hasBanner)
        assertFalse(e.current.canSave)
        e.pick(file("huge.jpg", "image/jpeg", BannerWrite.MAX_IMAGE_BYTES + 1))
        assertEquals(BannerEditor.Said.TOO_LARGE, e.current.said)
        assertFalse(e.current.canSave)
        e.pick(file("clip.mp4", "video/mp4"))
        assertNull(e.current.said)
        assertTrue(e.current.canSave)
        door.setGate = CompletableDeferred()
        val run = launch { e.save() }
        runCurrent()
        assertTrue(e.current.saving)
        assertEquals(0.5, e.current.progress)
        door.setGate!!.complete(Unit)
        run.join()
        assertTrue(e.current.done)
        assertEquals(listOf("set:bb:VIDEO:1"), door.sent, "opened on the banner the page held, the gallery's media to match")
    }

    @Test
    fun aCancelLeavesTheOldBannerAndSaysNothingAPeersChangeIsSaid() = runTest {
        val door = Door(emptyList(), null)
        val g = open(door)
        val e = assertNotNull(g.bannerEditor(Pictures))
        assertFalse(e.current.canRemove, "no banner: nothing to remove")
        e.pick(file("a.jpg", "image/jpeg"))
        door.setGate = CompletableDeferred()
        val run = launch { e.save() }
        runCurrent()
        run.cancel()
        runCurrent()
        assertFalse(e.current.saving)
        assertNull(e.current.said)
        assertFalse(e.current.done)
        assertTrue(e.current.canSave, "the dialog can save again")
        door.setGate = null
        door.setAnswer = BannerWrite.Outcome.Stale
        assertEquals(BannerWrite.Outcome.Stale, e.save())
        assertEquals(BannerEditor.Said.STALE, e.current.said)
        door.setAnswer = BannerWrite.Outcome.Refused("quota_exceeded")
        e.save()
        assertEquals(BannerEditor.Said.FAILED, e.current.said)
    }

    /**
     * After a peer's change is said, the dialog stands on the peer's banner: Save again writes over it, as the web's dialog
     * does (its save takes the page's node, which the conflict updated). Measured 2026-11-18 in phase 9's Linux pass: every
     * Save of the same dialog answered Stale, opened on a banner that was gone.
     */
    @Test
    fun afterAPeersChangeIsSaidSaveAgainWritesOverThePeersBanner() = runTest {
        val door = Door(emptyList(), banner(null))
        val g = open(door)
        val e = assertNotNull(g.bannerEditor(Pictures))
        e.pick(file("a.jpg", "image/jpeg"))
        door.banner = Banner.Read(Banner.Medium.IMAGE, Banner.Ref("peer", 1, 100, "w"), null, null, null, Banner.Frame.FIT, Banner.CENTRE)
        door.setAnswer = BannerWrite.Outcome.Stale
        assertEquals(BannerWrite.Outcome.Stale, e.save())
        assertEquals(BannerEditor.Said.STALE, e.current.said)
        runCurrent()
        assertEquals("peer", g.current.facts?.bannerSlots?.blobId, "the page shows the peer's banner")
        door.setAnswer = BannerWrite.Outcome.Done
        assertEquals(BannerWrite.Outcome.Done, e.save())
        assertEquals(listOf("set:bb:IMAGE:0", "set:peer:IMAGE:0"), door.sent, "the second save stands on the peer's banner")
        assertTrue(e.current.done)
    }

    /** A2: the banner's picture is asked about first; declined, nothing is deleted. Bite (e): the delete without asking. */
    @Test
    fun trashingTheBannersPictureAsksFirstAndDeclinedNothingIsDeleted() = runTest {
        val door = Door(listOf(item("pic", 2, digest), item("x", 1, other)), banner(digest))
        val g = open(door)
        assertNull(g.trash("pic"))
        assertEquals("pic", g.current.bannerPicture, "the banner's picture was trashed without asking")
        assertEquals(emptyList(), door.sent, "nothing deleted while it is asked")
        g.cancelAsk()
        assertNull(g.current.bannerPicture)
        assertEquals(emptyList(), door.sent, "declined: nothing deleted")
        // another medium: no question
        assertNotNull(g.trash("x"))
        assertEquals(listOf("trash:x"), door.sent)
    }

    @Test
    fun theQuestionAnsweredTrashesTheMediumAndKeepsTheBannerOrRemovesItToo() = runTest {
        val door = Door(listOf(item("pic", 2, digest)), banner(digest))
        val g = open(door)
        g.trash("pic")
        assertNotNull(g.trashBannerPicture(removeBanner = false))
        assertEquals(listOf("trash:pic"), door.sent, "the banner stays")
        val again = Door(listOf(item("pic", 2, digest)), banner(digest))
        val h = open(again)
        h.trash("pic")
        h.trashBannerPicture(removeBanner = true)
        runCurrent()
        assertEquals(listOf("trash:pic", "remove:bb"), again.sent)
        assertNull(h.current.banner, "the page's banner follows")
        // a banner without a digest asks nothing; nor does it ask whoever may not set it
        val plain = Door(listOf(item("pic", 2, digest)), banner(null))
        assertNotNull(open(plain).trash("pic"))
    }

    @Test
    fun aMediumUnderAnotherDigestOrBeforeTheDigestNeverAsks() = runTest {
        val door = Door(listOf(item("old", 2, null)), banner(digest))
        assertNotNull(open(door).trash("old"))
        assertEquals(listOf("trash:old"), door.sent)
    }
}
