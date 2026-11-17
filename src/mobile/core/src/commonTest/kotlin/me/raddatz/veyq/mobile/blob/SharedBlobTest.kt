package me.raddatz.veyq.mobile.blob

import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.crypto.BlobMeta
import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.crypto.b64
import me.raddatz.veyq.mobile.crypto.unb64
import me.raddatz.veyq.mobile.crypto.unhex
import me.raddatz.veyq.mobile.session.testPrimitives
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A blob that shares a stored object (ADR 0013 § 5, the web's `sharedBlob.test.ts` ported): the sealed size a match compares
 * first, the digest it reads only when a size matches, and the new row's `K_blob` wrapped again bound to its own owner and
 * role — held to a row the web's own `shareBlob` produced (the vector below), so an app-made shared row and a web-made one
 * are the same rows (ADR 0144's Consequences).
 */
class SharedBlobTest {
    private val suite = V1(testPrimitives)

    @Test
    fun theSealedSizeIsWhatAnUploadOfTheSamePlaintextDeclaresAnEmptySourceOneFrame() {
        for (n in listOf(0L, 1L, V1.FRAME_PLAINTEXT - 1L, V1.FRAME_PLAINTEXT.toLong(), V1.FRAME_PLAINTEXT + 1L, 3L * V1.FRAME_PLAINTEXT + 7)) {
            assertEquals(n + V1.FRAME_OVERHEAD.toLong() * V1.chunkCount(n), SharedBlob.sealedByteSize(n), "$n")
        }
        assertEquals(V1.FRAME_OVERHEAD.toLong(), SharedBlob.sealedByteSize(0))
    }

    private class C(val byteSize: Long, val sha256: String?)

    @Test
    fun theSizeIsComparedFirstAndTheFileReadOnlyWhenOneMatchesThenTheDigestDecides() = runTest {
        var reads = 0
        val digest = suspend { reads++; "d1" }
        val size = 100L
        val sealed = SharedBlob.sealedByteSize(size)
        assertNull(SharedBlob.findEqual(size, listOf(C(sealed + 1, "d1")), C::byteSize, C::sha256, digest))
        assertEquals(0, reads, "no candidate of the size: the file is not read")
        val hit = C(sealed, "d1")
        assertEquals(hit, SharedBlob.findEqual(size, listOf(C(sealed, "d2"), hit), C::byteSize, C::sha256, digest))
        assertEquals(1, reads, "read once for both candidates")
        assertNull(SharedBlob.findEqual(size, listOf(C(sealed, "d2")), C::byteSize, C::sha256, digest))
    }

    @Test
    fun aCandidateWithoutADigestNeverMatchesAndTheFileIsThenNotRead() = runTest {
        var reads = 0
        assertNull(SharedBlob.findEqual(5, listOf(C(SharedBlob.sealedByteSize(5), null)), C::byteSize, C::sha256) { reads++; "d" })
        assertEquals(0, reads)
    }

    private val scope = "11111111-1111-4111-8111-111111111111"
    private val node = "22222222-2222-4222-8222-222222222222"
    private val medium = "33333333-3333-4333-8333-333333333333"

    @Test
    fun wrapsTheSourcesKeyAgainBoundToTheNewRowsOwnerAndRoleWithBlobMetaOfItsOwn() {
        val key = testPrimitives.randomBytes(32)
        val blobKey = testPrimitives.randomBytes(32)
        val sourceId = "44444444-4444-4444-8444-444444444444"
        val wrapped = b64(suite.seal(SharedBlob.keyContext(scope, medium, sourceId, "PREVIEW"), key, blobKey))
        val source = SharedBlob.Stored(key, medium, "PREVIEW", sourceId, 1, 40 + V1.FRAME_OVERHEAD.toLong(), wrapped)
        val nodeKey = testPrimitives.randomBytes(32)
        val newId = "55555555-5555-4555-8555-555555555555"
        val shared = SharedBlob.share(suite, scope, source, nodeKey, node, "COVER", newId, SharedBlob.Meta("image/jpeg"))
        assertEquals(SharedBlob.Request(sourceId, newId, shared.wrappedBlobKey, shared.request.wrappedBlobMeta), shared.request)
        assertNotEquals(sourceId, shared.blobId)
        assertEquals(listOf(1L, source.byteSize), listOf(shared.chunkCount.toLong(), shared.byteSize))
        assertContentEquals(blobKey, suite.open(SharedBlob.keyContext(scope, node, newId, "COVER"), nodeKey, unb64(shared.wrappedBlobKey)))
        assertFailsWith<Exception>("bound to its role") { suite.open(SharedBlob.keyContext(scope, node, newId, "BANNER"), nodeKey, unb64(shared.wrappedBlobKey)) }
        assertFailsWith<Exception>("wrapped under the new owner's key") { suite.open(SharedBlob.keyContext(scope, node, newId, "COVER"), key, unb64(shared.wrappedBlobKey)) }
        assertEquals(BlobMeta.Opened(1, "image/jpeg", null, null), BlobMeta.open(suite, blobKey, unb64(shared.request.wrappedBlobMeta!!)))
        assertNull(SharedBlob.share(suite, scope, source, nodeKey, node, "COVER", newId, null).request.wrappedBlobMeta)
    }

    @Test
    fun refusesASourceWhoseWrapDoesNotOpenWhereItIsSaidToLive() {
        val key = testPrimitives.randomBytes(32)
        val sourceId = "44444444-4444-4444-8444-444444444444"
        val wrapped = b64(suite.seal(SharedBlob.keyContext(scope, medium, sourceId, "ORIGINAL"), key, testPrimitives.randomBytes(32)))
        val moved = SharedBlob.Stored(key, medium, "PREVIEW", sourceId, 1, 100, wrapped)
        assertFailsWith<SharedBlob.OpenRefused> { SharedBlob.share(suite, scope, moved, key, node, "BANNER", "55555555-5555-4555-8555-555555555555", null) }
    }

    /**
     * A row the web's `shareBlob` produced (`src/web/src/core/blob/sharedBlob.ts`, run over these inputs on 2026-11-17 in
     * a throwaway vitest file; its blob id and nonces are its own): a video banner sharing a gallery medium's ORIGINAL. The
     * core opens the web's wrap bound to the new row and the web's `blob-meta` under the key it carries, and its own row
     * over the same inputs opens to the same key under the same binding — one wrap, two implementations.
     */
    @Test
    fun theWebsSharedRowOpensHereAndTheCoresRowIsTheSameBinding() {
        val scopeId = "c0ffee00-0000-4000-8000-00000000000a"
        val nodeId = "c0ffee00-0000-4000-8000-00000000000e"
        val mediaId = "c0ffee00-0000-4000-8000-0000000000a1"
        val mediumKey = unhex("5555555555555555555555555555555555555555555555555555555555555555")
        val nodeKey = unhex("6666666666666666666666666666666666666666666666666666666666666666")
        val blobKey = unhex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f")
        val source = SharedBlob.Stored(
            mediumKey, mediaId, "ORIGINAL", "c0ffee00-0000-4000-8000-0000000000b1", 3, 9437232,
            "AstwBTbQcT6zttvcKsHDKKn4lAju9V9JK2x8w8nm1ADhPufcnvquPJqZOD0dXu6FQ75aeu9R8hFmH2ZU+A==",
        )
        val web = SharedBlob.Request(
            sourceBlobId = "c0ffee00-0000-4000-8000-0000000000b1",
            blobId = "7739c237-194d-4d72-a828-a4a7b95d4942",
            wrappedBlobKey = "Ar7fF01r6P1Hn4lnY2xR5DJyPq2s+/op6hkw0pf0R53nbYL4h/vZ/d9HkAzfKGrrlFXFo6M7TSn2Bru4Aw==",
            wrappedBlobMeta = "AjvtTz8jBUKex2wRuJGEPffKXwZxIULzwdYoZxC8mAb4dNF64WpPwlFhdaMpf/cchdNE8hXj+7T5ca6Fh1t7G+pa6mm4D4rTwKckcH9cZPSM35xvNoLOMhmMNVWlgFdd" +
                "/hezNFjZOWhvXKy1XWfwCiPx/bqkEDrUjRHxL3bbPwh+WJPN1f2cRoqcxO8bUlDt8bFM5v49b5WBblwBGxa23zrsS3d+2h24S1rOm8bZz/ffwI7e/yN2GjXvKZOF6u5czJfKFeaFQdyx" +
                "D+0S88RWgVNW91u9tQQdVKGjdn6IY3si+basel2UM9F9pG/Q07KRlg9KN1eU/8CVkGrok/4BHfxe3BJiVdYpyiGMki5Bu3rfjYAbK/EBU+Xk03LE",
        )
        val digest = "ungWv48Bz-pBQUDeXa4iI7ADYaOWF3qctBD_YfIAFa0"
        // the source opens here as the web sealed it
        assertContentEquals(blobKey, suite.open(SharedBlob.keyContext(scopeId, mediaId, source.blobId, "ORIGINAL"), mediumKey, unb64(source.wrappedBlobKey)))
        // the web's new row opens bound to (scope, node, its blob id, BANNER), under the node's key, to the source's K_blob
        val ctx = SharedBlob.keyContext(scopeId, nodeId, web.blobId, "BANNER")
        assertContentEquals(blobKey, suite.open(ctx, nodeKey, unb64(web.wrappedBlobKey)))
        assertEquals(BlobMeta.Opened(1, "video/mp4", null, null, digest), BlobMeta.open(suite, blobKey, unb64(web.wrappedBlobMeta!!)))
        // the core's row over the same inputs: the same binding, the same key, the same meta
        val core = SharedBlob.share(suite, scopeId, source, nodeKey, nodeId, "BANNER", web.blobId, SharedBlob.Meta("video/mp4", sha256 = digest))
        assertEquals(web.sourceBlobId, core.request.sourceBlobId)
        assertEquals(listOf(3L, 9437232L), listOf(core.chunkCount.toLong(), core.byteSize), "the source's shape")
        assertContentEquals(blobKey, suite.open(ctx, nodeKey, unb64(core.wrappedBlobKey)))
        assertEquals(unb64(web.wrappedBlobMeta).size, unb64(core.request.wrappedBlobMeta!!).size, "the meta padded to the same bucket")
        assertEquals(BlobMeta.open(suite, blobKey, unb64(web.wrappedBlobMeta)), BlobMeta.open(suite, blobKey, unb64(core.request.wrappedBlobMeta)))
        assertTrue(core.wrappedBlobKey != web.wrappedBlobKey, "a fresh nonce each")
    }
}
