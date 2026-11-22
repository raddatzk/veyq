package me.raddatz.veyq.mobile.memories

import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.api.memories.models.ScopeMediaNode
import me.raddatz.veyq.mobile.api.memories.models.ScopeMediaPage
import me.raddatz.veyq.mobile.api.memories.models.ScopeMedium
import me.raddatz.veyq.mobile.api.memories.models.UploaderKey
import me.raddatz.veyq.mobile.crypto.BlobMeta
import me.raddatz.veyq.mobile.crypto.Context
import me.raddatz.veyq.mobile.crypto.Primitives
import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.crypto.b64
import me.raddatz.veyq.mobile.search.ScopeDocument
import me.raddatz.veyq.mobile.session.OpenNode
import me.raddatz.veyq.mobile.session.testPrimitives
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Memories' walk over one page of a space's listing (ADR 0146 decisions 4 and 5; the web's `scopeMediaDocuments`): what
 * each medium becomes, and **every key the walk unwraps itself zeroed before it returns** — each original's `K_blob`, a
 * drop box's private seed and a guest's `K_up` —, a thrown walk included. The suite records every symmetric plaintext it
 * hands out (`aesGcmOpen`), so the test sees the very arrays the walk held; the node keys are the shell's and stay.
 */
class MemoriesSearchWalkTest {
    private val scopeId = "00000000-0000-4000-8000-0000000000a1"
    private val memoryId = "00000000-0000-4000-8000-0000000000b1"
    private val boxId = "00000000-0000-4000-8000-0000000000b2"
    private val identity = "00000000-0000-4000-8000-0000000000c1"

    /** Every plaintext the suite opened, as the array handed out and a copy taken then. */
    private class Recorded(val live: ByteArray, val then: ByteArray)

    private val recorded = ArrayList<Recorded>()
    private val recording = object : Primitives by testPrimitives {
        override fun aesGcmOpen(key: ByteArray, nonce: ByteArray, ciphertextAndTag: ByteArray, aad: ByteArray): ByteArray =
            testPrimitives.aesGcmOpen(key, nonce, ciphertextAndTag, aad).also { recorded += Recorded(it, it.copyOf()) }
    }
    private val suite = V1(recording)
    private val plain = V1(testPrimitives)

    private val memoryKey = testPrimitives.randomBytes(32)
    private val boxKey = testPrimitives.randomBytes(32)
    private val dropSeed = testPrimitives.randomBytes(32)
    private val upKey = testPrimitives.randomBytes(32)
    private val blobKeys = ArrayList<ByteArray>()

    private fun node(id: String, key: ByteArray, kind: String) = OpenNode(scopeId, id, null, kind, "n-$id", key)

    private fun medium(mediaId: String, nodeId: String, key: ByteArray, type: String, name: String?, meta: String?, owner: String? = null): ScopeMedium {
        val blobId = "00000000-0000-4000-9000-" + mediaId.takeLast(12)
        val kBlob = testPrimitives.randomBytes(32).also { blobKeys += it.copyOf() }
        return ScopeMedium(
            blockedByOperator = false, nodeId = nodeId, mediaId = mediaId, blobId = blobId, chunkCount = 1, byteSize = 100,
            wrappedBlobKey = b64(plain.seal(Context("blob-key", "scopeId" to scopeId, "ownerId" to mediaId, "blobId" to blobId, "role" to "ORIGINAL"), key, kBlob)),
            metaRevision = 1, wrappedBlobMeta = b64(BlobMeta.seal(plain, kBlob, type, name, null)),
            wrappedMeta = meta?.let { b64(plain.sealField(Context("media-meta", "scopeId" to scopeId, "mediaId" to mediaId), key, it.encodeToByteArray())) },
            ownerKey = owner,
        )
    }

    private fun page(): ScopeMediaPage {
        val drop = plain.xwingKey(dropSeed)
        val box = ScopeMediaNode(
            nodeId = boxId,
            sealedDropPrivateKey = b64(plain.seal(Context("drop-private-key", "scopeId" to scopeId, "nodeId" to boxId), boxKey, plain.serializeDropKey(dropSeed))),
            uploaderKeys = listOf(UploaderKey(identity, b64(plain.sealTo(Context("upload-identity-key", "scopeId" to scopeId, "nodeId" to boxId, "identityId" to identity), drop.publicKey, upKey)))),
        )
        return ScopeMediaPage(
            media = listOf(
                medium("00000000-0000-4000-8000-0000000000d1", memoryId, memoryKey, "image/jpeg", "IMG_1.jpg", """{"v":1,"capturedAt":1626350400000,"placeName":"Roma"}"""),
                medium("00000000-0000-4000-8000-0000000000d2", memoryId, memoryKey, "video/mp4", null, null),
                medium("00000000-0000-4000-8000-0000000000d3", memoryId, memoryKey, "audio/mpeg", "song.mp3", null),
                medium("00000000-0000-4000-8000-0000000000d4", boxId, upKey, "image/png", "guest.png", null, owner = "share:$identity"),
            ),
            more = false, nodes = listOf(box),
        )
    }

    private val nodes get() = mapOf(memoryId to node(memoryId, memoryKey, "MEMORY"), boxId to node(boxId, boxKey, "MEMORY"))

    /** The secrets the walk unwraps itself: each K_blob, the drop box's seed (in its serialized form too), the guest's K_up. */
    private fun unwrapped(): List<Recorded> = recorded.filter { r ->
        blobKeys.any { it.contentEquals(r.then) } || r.then.contentEquals(upKey) || r.then.contentEquals(plain.serializeDropKey(dropSeed))
    }

    private fun assertZeroed() {
        val keys = unwrapped()
        assertTrue(keys.isNotEmpty(), "the walk unwrapped no key: nothing was checked")
        for (k in keys) assertTrue(k.live.all { it == 0.toByte() }, "a key the walk unwrapped outlived it")
    }

    @Test
    fun eachMediumIsADocumentAndEveryKeyTheWalkUnwrappedIsZeroed() = runTest {
        val docs = MemoriesSearch.pageDocuments(suite, scopeId, page(), nodes, emptyMap()) { video -> if (video) "Video" else "Photo" }
        assertEquals(listOf("00000000-0000-4000-8000-0000000000d1" to "IMG_1.jpg", "00000000-0000-4000-8000-0000000000d2" to "Video", "00000000-0000-4000-8000-0000000000d4" to "guest.png"), docs.map { it.id to it.name })
        assertEquals(listOf(MemoriesSearch.PHOTO, MemoriesSearch.VIDEO, MemoriesSearch.PHOTO), docs.map { it.kind })
        assertEquals(listOf("Roma"), docs.first().facets.places)
        // four K_blob (the music track's too: its blob meta is read to leave it out), the drop box's seed and K_up
        assertEquals(6, unwrapped().size)
        assertZeroed()
        // the shell's node keys are read in place, never zeroed by the search
        assertTrue(nodes.values.all { n -> !n.key.all { it == 0.toByte() } })
    }

    @Test
    fun aThrownWalkZeroesWhatItUnwrappedToo() = runTest {
        var named = 0
        assertFailsWith<IllegalStateException> {
            MemoriesSearch.pageDocuments(suite, scopeId, page(), nodes, emptyMap()) { if (++named == 1) throw IllegalStateException("thrown") else "x" }
        }
        assertZeroed()
    }

    @Test
    fun aMediumAtItsRevisionIsKeptUnopened() = runTest {
        val before = ScopeDocument(MemoriesSearch.PHOTO, "00000000-0000-4000-8000-0000000000d1", memoryId, MemoriesSearch.revisionOf(1, "00000000-0000-4000-9000-0000000000d1"), "kept", me.raddatz.veyq.mobile.search.SearchFacets.NONE)
        val docs = MemoriesSearch.pageDocuments(suite, scopeId, page(), nodes, mapOf("00000000-0000-4000-8000-0000000000d1" to before)) { "x" }
        assertEquals("kept", docs.first { it.id == "00000000-0000-4000-8000-0000000000d1" }.name)
        // its blob key was never opened
        val m1Key = blobKeys.first()
        assertTrue(recorded.none { it.then.contentEquals(m1Key) })
    }
}
