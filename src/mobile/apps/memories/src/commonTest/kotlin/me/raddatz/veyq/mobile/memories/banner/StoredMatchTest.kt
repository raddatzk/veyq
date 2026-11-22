package me.raddatz.veyq.mobile.memories.banner

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import me.raddatz.veyq.mobile.blob.SharedBlob
import me.raddatz.veyq.mobile.crypto.BlobMeta
import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.crypto.b64
import me.raddatz.veyq.mobile.crypto.unb64
import me.raddatz.veyq.mobile.media.ByteSource
import me.raddatz.veyq.mobile.memories.MediaUpload
import me.raddatz.veyq.mobile.memories.MemoryMedia
import me.raddatz.veyq.mobile.memories.place.PlayedMeta
import me.raddatz.veyq.mobile.session.OpenNode
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What of a picked file the Memory already stores (the web's `storedMatch.test.ts` ported): the live, unblocked medium of the
 * same size and digest and the tiers a slot shares; the video banner's `BANNER` blob as a gallery upload's original (A1 of
 * ADR 0144 decision 5, `MediaUpload.sharedOriginal`); and which medium is the banner's picture (A2).
 */
class StoredMatchTest {
    private val m = PlayedMeta()
    private val digest = "ungWv48Bz-pBQUDeXa4iI7ADYaOWF3qctBD_YfIAFa0"
    private val other = "47DEQpj8HBSa-_TImW-5JCeuQeRkm5NMpJWZG3hSuFU"

    private fun blob(id: String, size: Long) = MemoryMedia.Blob(id, V1.chunkCount(size), SharedBlob.sealedByteSize(size), "w-$id", null)
    private fun medium(id: String, size: Long, sha256: String?, blocked: Boolean = false) =
        MemoryMedia.Item(m.scopeId, m.nodeId, id, blob("o-$id", size), blob("t-$id", 9), blob("p-$id", 99), null, "image/jpeg", null, "user:u1", blocked, m.key, sha256 = sha256)

    @Test
    fun findsTheLiveUnblockedMediumOfTheSameSizeAndDigestAndItsTiersAreWhatASlotShares() {
        val hit = medium("eq", 500, digest)
        val items = listOf(medium("blocked", 500, digest, blocked = true), medium("size", 501, digest), medium("digest", 500, other), medium("none", 500, null), hit)
        assertEquals(hit, StoredMatch.equalMedium(items, 500, digest))
        assertNull(StoredMatch.equalMedium(items, 500, "x"))
        assertNull(StoredMatch.equalMedium(items, 499, digest))
        val original = assertNotNull(StoredMatch.tier(hit, MemoryMedia.ORIGINAL))
        assertEquals(listOf("o-eq", "eq", MemoryMedia.ORIGINAL), listOf(original.blobId, original.ownerId, original.role), "a tier's wrap is bound to its medium and role")
        assertEquals("p-eq", StoredMatch.tier(hit, MemoryMedia.PREVIEW)?.blobId)
        assertNull(StoredMatch.tier(hit, MemoryMedia.THUMBNAIL), "a slot shares no thumbnail")
    }

    private fun meta(json: String) = Json.parseToJsonElement(json) as JsonObject
    private fun ref(id: String, byteSize: Long) = """{"blobId":"$id","chunkCount":1,"byteSize":$byteSize,"wrappedBlobKey":"d3JhcA=="}"""

    @Test
    fun namesTheVideoBannersBannerBlobForTheSameDigestAndSizeUnderTheNodesKey() {
        val size = SharedBlob.sealedByteSize(5000)
        val video = meta("""{"v":1,"banner":{"medium":"video","blob":${ref("vb", size)},"poster":${ref("pb", 99)},"type":"video/mp4","sha256":"$digest"}}""")
        val s = assertNotNull(StoredMatch.bannerOriginal(video, m.nodeId, m.key, digest, size))
        assertEquals(listOf("vb", m.nodeId, Banner.ROLE), listOf(s.blobId, s.ownerId, s.role))
        assertNull(StoredMatch.bannerOriginal(video, m.nodeId, m.key, other, size), "another file")
        assertNull(StoredMatch.bannerOriginal(video, m.nodeId, m.key, digest, size + 1), "another size")
        // an image banner holds a derived JPEG, never the file; nor a node without a banner or a meta
        val image = meta("""{"v":1,"banner":{"medium":"image","blob":${ref("ib", size)},"sha256":"$digest"}}""")
        assertNull(StoredMatch.bannerOriginal(image, m.nodeId, m.key, digest, size))
        assertNull(StoredMatch.bannerOriginal(meta("""{"v":1}"""), m.nodeId, m.key, digest, size))
        assertNull(StoredMatch.bannerOriginal(null, m.nodeId, m.key, digest, size))
    }

    /** A1: a gallery upload of the video banner's very file is a new row on the banner's object, its meta the upload's own. */
    @Test
    fun aGalleryUploadEqualToTheVideoBannersFileSharesItsObject() {
        val suite = m.suite
        val blobKey = ByteArray(32) { 9 }
        val size = 5000L
        val bannerId = "0b1c2d3e-4f50-4617-8899-aabbccddeeff"
        val wrap = b64(suite.seal(SharedBlob.keyContext(m.scopeId, m.nodeId, bannerId, Banner.ROLE), m.key, blobKey))
        val sealed = m.seal("""{"v":1,"banner":{"medium":"video","blob":{"blobId":"$bannerId","chunkCount":1,"byteSize":${SharedBlob.sealedByteSize(size)},"wrappedBlobKey":"$wrap"},"type":"video/mp4","sha256":"$digest"}}""")
        val node = OpenNode(m.scopeId, m.nodeId, null, "MEMORY", "M", m.key, wrappedMeta = sealed)
        val file = MediaUpload.Original(ByteSource(size) { _, n -> ByteArray(n) }, "video/mp4", "clip.mp4", 1234)
        val mediaId = "9a8b7c6d-5e4f-4a3b-9c2d-1e0f9a8b7c6d"
        val newId = "77777777-7777-4777-8777-777777777777"
        val shared = assertNotNull(MediaUpload.sharedOriginal(suite, m.scopeId, node, mediaId, newId, file, "video/mp4", digest), "the original was not shared")
        assertEquals(bannerId, shared.request.sourceBlobId)
        assertContentEquals(blobKey, suite.open(SharedBlob.keyContext(m.scopeId, mediaId, newId, MemoryMedia.ORIGINAL), m.key, unb64(shared.wrappedBlobKey)))
        assertEquals(BlobMeta.Opened(1, "video/mp4", "clip.mp4", 1234, digest), BlobMeta.open(suite, blobKey, unb64(shared.request.wrappedBlobMeta!!)))
        assertNull(MediaUpload.sharedOriginal(suite, m.scopeId, node, mediaId, newId, file, "video/mp4", other), "another file is uploaded")
        val bigger = MediaUpload.Original(ByteSource(size + 1) { _, n -> ByteArray(n) }, "video/mp4", "clip.mp4", 1234)
        assertNull(MediaUpload.sharedOriginal(suite, m.scopeId, node, mediaId, newId, bigger, "video/mp4", digest), "another size is uploaded")
        assertNull(MediaUpload.sharedOriginal(suite, m.scopeId, OpenNode(m.scopeId, m.nodeId, null, "MEMORY", "M", m.key), mediaId, newId, file, "video/mp4", digest))
    }

    @Test
    fun theBannersPictureIsTheMediumWhoseOriginalHasTheBannersDigest() {
        val banner = Banner.Read(Banner.Medium.IMAGE, Banner.Ref("b", 1, 1, "w"), null, null, digest, Banner.Frame.FIT, Banner.CENTRE)
        assertTrue(StoredMatch.isBannerPicture(banner, medium("eq", 500, digest)))
        assertFalse(StoredMatch.isBannerPicture(banner, medium("x", 500, other)))
        assertFalse(StoredMatch.isBannerPicture(banner, medium("none", 500, null)), "a medium without a digest")
        assertFalse(StoredMatch.isBannerPicture(banner.copy(sha256 = null), medium("eq", 500, digest)), "a banner without a digest asks nothing")
        assertFalse(StoredMatch.isBannerPicture(null, medium("eq", 500, digest)))
    }
}
