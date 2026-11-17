package me.raddatz.veyq.mobile.memories.banner

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import me.raddatz.veyq.mobile.blob.ApplicationBlobs
import me.raddatz.veyq.mobile.blob.SharedBlob
import me.raddatz.veyq.mobile.blob.UploadSource
import me.raddatz.veyq.mobile.crypto.BlobMeta
import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.crypto.b64
import me.raddatz.veyq.mobile.crypto.unb64
import me.raddatz.veyq.mobile.memories.MemoryMedia
import me.raddatz.veyq.mobile.memories.place.PlayedMeta
import me.raddatz.veyq.mobile.session.NodeMetaWrite
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The banner's write (ADR 0144 decision 5), the web's `bannerWrite.test.ts`, `useBannerEditor.test.ts` and
 * `storedMatch.test.ts` ported: a pick refused before a byte is read where it must be, the parts an equal gallery medium
 * shares (an image its PREVIEW, a video its ORIGINAL and PREVIEW), the uploads in one start, poster first, and the one
 * request that writes the slots with the meta — a stale revision re-applied with the SAME uploads, a peer's banner change
 * refused, every other field of the meta kept.
 */
class BannerWriteTest {
    private val m = PlayedMeta()
    private val suite = m.suite
    private val digest = "ungWv48Bz-pBQUDeXa4iI7ADYaOWF3qctBD_YfIAFa0"
    private val other = "47DEQpj8HBSa-_TImW-5JCeuQeRkm5NMpJWZG3hSuFU"

    /** A picked file of [size] bytes whose reads are counted. */
    private class Source(name: String, size: Long, type: String?) : UploadSource(name, size, type, null) {
        var reads = 0
        override suspend fun read(from: Long, length: Int): ByteArray {
            reads++
            return ByteArray(minOf(length.toLong(), size - from).toInt()) { 7 }
        }
    }

    private class Pictures(val image: ByteArray? = byteArrayOf(-1, -40, 1), val poster: ByteArray? = byteArrayOf(-1, -40, 2)) : BannerWrite.Pictures {
        var asked = 0
        override suspend fun image(source: UploadSource): ByteArray? { asked++; return image }
        override suspend fun poster(source: UploadSource): ByteArray? { asked++; return poster }
    }

    // ---- the pick ----

    @Test
    fun anImageAbove40MegabytesIsRefusedBeforeAByteOfItIsReadOrDecoded() = runTest {
        val big = Source("big.jpg", BannerWrite.MAX_IMAGE_BYTES + 1, "image/jpeg")
        val pictures = Pictures()
        assertEquals(BannerWrite.TOO_LARGE, assertFailsWith<BannerWrite.Refused> { BannerWrite.pick(big, pictures) }.code)
        assertEquals(0, big.reads, "a byte of it was read")
        assertEquals(0, pictures.asked, "it was decoded")
        // exactly 40 MB is taken; a video of any size too
        assertEquals(Banner.Medium.IMAGE, BannerWrite.pick(Source("ok.jpg", BannerWrite.MAX_IMAGE_BYTES, "image/jpeg"), pictures).medium)
        assertEquals(Banner.Medium.VIDEO, BannerWrite.pick(Source("long.mp4", 4L * BannerWrite.MAX_IMAGE_BYTES, "video/mp4"), pictures).medium)
    }

    @Test
    fun aPictureThatDoesNotDecodeIsRefusedAndAVideoWithoutAFirstFrameToo() = runTest {
        assertEquals(BannerWrite.UNREADABLE, assertFailsWith<BannerWrite.Refused> { BannerWrite.pick(Source("a.png", 10, "image/png"), Pictures(image = null)) }.code)
        assertEquals(BannerWrite.UNREADABLE, assertFailsWith<BannerWrite.Refused> { BannerWrite.pick(Source("a.png", 10, "image/png"), Pictures(image = ByteArray(0))) }.code)
        assertEquals(BannerWrite.VIDEO_UNREADABLE, assertFailsWith<BannerWrite.Refused> { BannerWrite.pick(Source("a.mov", 10, "video/quicktime"), Pictures(poster = null)) }.code)
        assertEquals(BannerWrite.NOT_MEDIA, assertFailsWith<BannerWrite.Refused> { BannerWrite.pick(Source("a.pdf", 10, "application/pdf"), Pictures()) }.code)
        val head = byteArrayOf(0, 0, 0, 0x18) + "ftypqt  ".encodeToByteArray()
        val unnamed = UploadSource.of("clip", head.size.toLong(), null, null) { _, _ -> head }
        val video = BannerWrite.pick(unnamed, Pictures())
        assertEquals("video/quicktime", video.type, "no type and no extension: the bytes say")
        assertContentEquals(byteArrayOf(-1, -40, 2), video.picture, "a video's previews draw its poster")
    }

    // ---- what an equal gallery medium shares ----

    private fun blob(id: String, size: Long) = MemoryMedia.Blob(id, V1.chunkCount(size), SharedBlob.sealedByteSize(size), "", null)

    private fun medium(id: String, size: Long, sha256: String?, preview: Boolean = true, blocked: Boolean = false, type: String = "image/jpeg") =
        MemoryMedia.Item(
            m.scopeId, m.nodeId, id, blob("o-$id", size), blob("t-$id", 9), if (preview) blob("p-$id", 99) else null, null, type, "$id.jpg",
            "user:u1", blocked, m.key, sha256 = sha256,
        )

    private suspend fun picked(size: Long, type: String) = BannerWrite.pick(Source("f", size, type), Pictures())

    @Test
    fun aPickedImageEqualToAGalleryOriginalSharesThatMediumsPreviewNothingUploaded() = runTest {
        val p = BannerWrite.prepare(picked(500, "image/jpeg"), digest, listOf(medium("x", 500, other), medium("eq", 500, digest)))
        val share = assertIs<BannerWrite.Part.Share>(p.main)
        assertEquals("p-eq", share.stored.blobId, "an image banner shares the medium's PREVIEW: the same spec of the same picture")
        assertEquals(MemoryMedia.PREVIEW, share.stored.role)
        assertNull(p.poster)
        assertEquals(digest, p.sha256)
    }

    @Test
    fun aPickedImageOfTheSameSizeAndAnotherDigestOrAMediumWithoutADigestIsUploadedAsItsJpeg() = runTest {
        for (items in listOf(listOf(medium("x", 500, other)), listOf(medium("x", 500, null)), listOf(medium("x", 501, digest)), listOf(medium("x", 500, digest, blocked = true)))) {
            val p = BannerWrite.prepare(picked(500, "image/jpeg"), digest, items)
            val up = assertIs<BannerWrite.Part.Upload>(p.main)
            assertEquals(BannerWrite.JPEG, up.type)
            assertContentEquals(byteArrayOf(-1, -40, 1), up.read(0, 3), "the derived JPEG")
        }
        // an equal medium without a PREVIEW: the image is uploaded (never its ORIGINAL: the slot holds a derived JPEG)
        assertIs<BannerWrite.Part.Upload>(BannerWrite.prepare(picked(500, "image/jpeg"), digest, listOf(medium("eq", 500, digest, preview = false))).main)
    }

    @Test
    fun aPickedVideoEqualToAGalleryOriginalSharesItsOriginalAndAsThePosterItsPreview() = runTest {
        val p = BannerWrite.prepare(picked(5000, "video/mp4"), digest, listOf(medium("eq", 5000, digest, type = "video/mp4")))
        assertEquals("o-eq", assertIs<BannerWrite.Part.Share>(p.main).stored.blobId)
        assertEquals(MemoryMedia.ORIGINAL, assertIs<BannerWrite.Part.Share>(p.main).stored.role)
        assertEquals("p-eq", assertIs<BannerWrite.Part.Share>(p.poster).stored.blobId)
        assertEquals("video/mp4", p.type)
        // matched without a PREVIEW: the ORIGINAL shared, the poster the dialog decoded uploaded
        val q = BannerWrite.prepare(picked(5000, "video/mp4"), digest, listOf(medium("eq", 5000, digest, preview = false, type = "video/mp4")))
        assertIs<BannerWrite.Part.Share>(q.main)
        assertContentEquals(byteArrayOf(-1, -40, 2), assertIs<BannerWrite.Part.Upload>(q.poster).read(0, 3))
    }

    @Test
    fun anUnmatchedVideoIsUploadedAsItIsWithItsDigest() = runTest {
        val p = BannerWrite.prepare(picked(5000, "video/webm"), digest, emptyList())
        val up = assertIs<BannerWrite.Part.Upload>(p.main)
        assertEquals(5000, up.size)
        assertEquals("video/webm", up.type)
        assertEquals(digest, up.sha256)
        assertIs<BannerWrite.Part.Upload>(p.poster)
    }

    // ---- the uploads ----

    private var minted = 0
    private val newId = { "00000000-0000-4000-8000-" + (minted++).toString().padStart(12, '0') }

    /** A pump that plans each blob as `reserveAndPumpAll` would, and records what it was handed. */
    private class Pump {
        val starts = mutableListOf<List<ApplicationBlobs.Outgoing>>()
        suspend fun pump(out: List<ApplicationBlobs.Outgoing>): List<Pair<String, ApplicationBlobs.Plan>> {
            starts += out
            return out.mapIndexed { i, o ->
                "up-${starts.size}-$i" to ApplicationBlobs.Plan("b-${o.role}", V1.chunkCount(o.size), SharedBlob.sealedByteSize(o.size), "w-${o.role}", "node")
            }
        }
    }

    @Test
    fun aVideoGoesInOneStartThePosterFirstEachInItsRoleTheVideosTypeAndDigestItsOwn() = runTest {
        val pump = Pump()
        val u = BannerWrite.upload(suite, m.scopeId, m.nodeId, m.key, BannerWrite.prepare(picked(5000, "video/mp4"), digest, emptyList()), newId, pump::pump)
        val start = pump.starts.single()
        assertEquals(listOf(Banner.POSTER_ROLE, Banner.ROLE), start.map { it.role })
        assertEquals(listOf(BannerWrite.JPEG, "video/mp4"), start.map { it.type })
        assertEquals(listOf(null, digest), start.map { it.sha256 })
        assertEquals("up-1-1" to "up-1-0", u.uploadId to u.posterUploadId)
        assertEquals(listOf("b-BANNER", "b-BANNER_POSTER"), listOf(u.blob.blobId, u.poster?.blobId))
        assertEquals(
            Banner.sealed(Banner.Medium.VIDEO, u.blob, u.poster, "video/mp4", digest), u.banner,
            "the meta names the slots, the type and the file's digest, the whole picture",
        )
    }

    private fun storedTier(id: String, mediaId: String, role: String, blobKey: ByteArray) = MemoryMedia.Blob(
        id, 1, SharedBlob.sealedByteSize(5000), b64(suite.seal(SharedBlob.keyContext(m.scopeId, mediaId, id, role), m.key, blobKey)), null,
    )

    @Test
    fun aSharedPartIsANewRowOnTheStoredBlobItsKeyWrappedAgainForTheSlotNothingUploadedForIt() = runTest {
        val k1 = ByteArray(32) { 1 }
        val k2 = ByteArray(32) { 2 }
        val eqId = "e0000000-0000-4000-8000-000000000001"
        val o = "e0000000-0000-4000-8000-0000000000a1"
        val p = "e0000000-0000-4000-8000-0000000000b1"
        val eq = MemoryMedia.Item(
            m.scopeId, m.nodeId, eqId, storedTier(o, eqId, MemoryMedia.ORIGINAL, k1), null, storedTier(p, eqId, MemoryMedia.PREVIEW, k2),
            null, "video/mp4", "eq.mp4", "user:u1", false, m.key, sha256 = digest,
        )
        val pump = Pump()
        val u = BannerWrite.upload(suite, m.scopeId, m.nodeId, m.key, BannerWrite.prepare(picked(5000, "video/mp4"), digest, listOf(eq)), newId, pump::pump)
        assertEquals(0, pump.starts.size, "nothing uploaded")
        assertNull(u.uploadId)
        assertEquals(o, u.shared?.sourceBlobId)
        assertEquals(p, u.posterShared?.sourceBlobId)
        assertContentEquals(k1, suite.open(SharedBlob.keyContext(m.scopeId, m.nodeId, u.blob.blobId, Banner.ROLE), m.key, unb64(u.blob.wrappedBlobKey)))
        assertContentEquals(k2, suite.open(SharedBlob.keyContext(m.scopeId, m.nodeId, u.poster!!.blobId, Banner.POSTER_ROLE), m.key, unb64(u.poster!!.wrappedBlobKey)))
        assertEquals(BlobMeta.Opened(1, "video/mp4", null, null, digest), BlobMeta.open(suite, k1, unb64(u.shared!!.wrappedBlobMeta!!)), "a shared video's meta carries its digest")
    }

    // ---- the write: the slots and the meta that names them, one request ----

    private fun sealMeta(json: String) = m.seal(json)
    private fun ref(id: String) = Banner.Ref(id, 1, 300, "d3JhcA==")
    private fun image(id: String) = Banner.sealed(Banner.Medium.IMAGE, ref(id), null, null, null)
    private fun video(id: String, poster: String) = Banner.sealed(Banner.Medium.VIDEO, ref(id), ref(poster), "video/mp4", null)

    private fun uploaded(medium: Banner.Medium = Banner.Medium.VIDEO, upload: String? = "u", poster: String? = "pu", shared: SharedBlob.Request? = null) =
        BannerWrite.Uploaded(medium, upload, poster, shared, null, ref("new"), if (medium == Banner.Medium.VIDEO) ref("newp") else null, if (medium == Banner.Medium.VIDEO) "video/mp4" else null, digest)

    private class Wire(vararg answers: NodeMetaWrite.Answer) {
        val sent = mutableListOf<BannerWrite.Send>()
        private val queue = ArrayDeque(answers.toList())
        suspend fun send(s: BannerWrite.Send): NodeMetaWrite.Answer {
            sent += s
            return queue.removeFirstOrNull() ?: NodeMetaWrite.Answer.Taken
        }
    }

    private suspend fun write(base: String?, revision: Long, openedWith: Banner.Slots, up: BannerWrite.Uploaded?, wire: Wire, uploads: IntArray = IntArray(1)) =
        BannerWrite.write(NodeMetaWrite.State(base, revision), m.open, m.sealMeta, openedWith, up?.let { u -> { uploads[0]++; u } }, wire::send)

    @Test
    fun aFirstVideoBannerIsOneSetWithBothUploadsNoBlobToReplaceTheMetaAtItsRevisionSiblingsKept() = runTest {
        val wire = Wire()
        val up = uploaded()
        assertEquals(BannerWrite.Outcome.Done, write(sealMeta("""{"v":1,"date":{"mode":"single","from":"2026-01-01"}}"""), 3, Banner.Slots.NONE, up, wire))
        val s = assertIs<BannerWrite.Send.Set>(wire.sent.single())
        assertSame(up, s.uploaded)
        assertEquals(listOf(null, null, 3L), listOf(s.expectedBlobId, s.expectedPosterBlobId, s.metaRevision))
        val sent = m.opened(s.wrappedMeta)
        assertEquals(up.banner, sent["banner"])
        assertEquals("""{"mode":"single","from":"2026-01-01"}""", sent["date"].toString())
    }

    @Test
    fun anImageReplacingAVideoStatesBothSlotsItRead() = runTest {
        val wire = Wire()
        write(sealMeta("""{"v":1,"banner":${video("v", "p")}}"""), 3, Banner.Slots("v", "p"), uploaded(Banner.Medium.IMAGE, poster = null), wire)
        val s = assertIs<BannerWrite.Send.Set>(wire.sent.single())
        assertEquals(listOf("v", "p"), listOf(s.expectedBlobId, s.expectedPosterBlobId))
        assertNull(s.uploaded.posterUploadId, "the server frees the poster with the video")
    }

    @Test
    fun aRemovalFreesBothSlotsTheMetaNamedAndWritesTheMetaWithoutTheBanner() = runTest {
        val wire = Wire()
        write(sealMeta("""{"v":1,"banner":${video("v", "p")},"cover":{"x":1}}"""), 3, Banner.Slots("v", "p"), null, wire)
        val s = assertIs<BannerWrite.Send.Remove>(wire.sent.single())
        assertEquals(listOf("v", "p"), listOf(s.blobId, s.posterBlobId))
        assertNull(m.opened(s.wrappedMeta)["banner"])
        assertEquals("""{"x":1}""", m.opened(s.wrappedMeta)["cover"].toString())
        // nothing to remove: nothing sent
        val none = Wire()
        assertEquals(BannerWrite.Outcome.Done, write(sealMeta("""{"v":1}"""), 3, Banner.Slots.NONE, null, none))
        assertEquals(0, none.sent.size)
    }

    /**
     * A `409 stale_revision` re-runs the change on the meta the server handed back and compares against what IT names (a
     * peer's date write meanwhile), and the SAME uploads go again: the file is uploaded once. Bite (d): an upload per send.
     */
    @Test
    fun aStaleRevisionReAppliesTheBannerOnTheFreshMetaWithTheSameUploads() = runTest {
        val fresh = sealMeta("""{"v":1,"date":{"mode":"auto"}}""")
        val wire = Wire(NodeMetaWrite.Answer.Stale(NodeMetaWrite.State(fresh, 7)))
        val uploads = IntArray(1)
        assertEquals(BannerWrite.Outcome.Done, write(null, 3, Banner.Slots.NONE, uploaded(), wire, uploads))
        assertEquals(2, wire.sent.size)
        assertEquals(1, uploads[0], "the file was uploaded again on a stale revision")
        val (first, second) = wire.sent.map { assertIs<BannerWrite.Send.Set>(it) }
        assertSame(first.uploaded, second.uploaded, "the same uploads go again")
        assertEquals(7L, second.metaRevision)
        assertEquals("""{"mode":"auto"}""", m.opened(second.wrappedMeta)["date"].toString(), "the peer's date kept")
    }

    @Test
    fun aPeersBannerChangeIsStaleNothingSentAgainAndAStaleSlotIsStaleToo() = runTest {
        val peers = sealMeta("""{"v":1,"banner":${image("peer")}}""")
        val wire = Wire(NodeMetaWrite.Answer.Stale(NodeMetaWrite.State(peers, 7)))
        assertEquals(BannerWrite.Outcome.Stale, write(null, 3, Banner.Slots.NONE, uploaded(), wire))
        assertEquals(1, wire.sent.size, "the peer's banner is never overwritten")
        // the dialog opened on a banner a peer replaced before the save even read the meta: nothing uploaded, nothing sent
        val uploads = IntArray(1)
        val early = Wire()
        assertEquals(BannerWrite.Outcome.Stale, write(peers, 7, Banner.Slots("mine", null), uploaded(), early, uploads))
        assertEquals(listOf(0, 0), listOf(early.sent.size, uploads[0]))
        // the server's own slot compare
        assertEquals(BannerWrite.Outcome.Stale, write(null, 3, Banner.Slots.NONE, uploaded(), Wire(NodeMetaWrite.Answer.Refused(409, "banner_stale"))))
        assertEquals(BannerWrite.Outcome.Busy, write(null, 3, Banner.Slots.NONE, uploaded(), Wire(NodeMetaWrite.Answer.Refused(409, "node_rekey_pending"))))
    }

    @Test
    fun aSharedBannerSendsItsRowInPlaceOfTheUpload() = runTest {
        val wire = Wire()
        val shared = SharedBlob.Request("s", "b", "k", null)
        write(null, 0, Banner.Slots.NONE, uploaded(Banner.Medium.IMAGE, upload = null, poster = null, shared = shared), wire)
        val s = assertIs<BannerWrite.Send.Set>(wire.sent.single())
        assertEquals(shared, s.uploaded.shared)
        assertNull(s.uploaded.uploadId)
    }

    @Test
    fun theSealedBannerOpensAsTheWebReadsIt() {
        val sealed = uploaded().banner
        val read = Banner.read(Json.parseToJsonElement(sealed.toString()) as JsonObject)!!
        assertEquals(Banner.Medium.VIDEO, read.medium)
        assertEquals(digest, read.sha256)
        assertEquals(Banner.Frame.FIT, read.frame)
        assertEquals(Banner.CENTRE, read.focal)
        assertTrue(sealed.keys.toList() == listOf("medium", "blob", "poster", "type", "sha256", "frame", "focal"), "the web's key order")
    }
}
