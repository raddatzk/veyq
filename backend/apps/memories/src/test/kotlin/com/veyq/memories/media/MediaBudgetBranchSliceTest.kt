package com.veyq.memories.media

import com.veyq.core.testing.FakePresignProfile
import com.veyq.core.blob.UploadSessionPurge
import com.veyq.core.testing.TestBlob
import com.veyq.core.blob.UploadSessionEntity
import com.veyq.core.blobstore.BlobStore.Companion.chunkKey
import com.veyq.core.blobstore.BlobStore.Companion.chunkKeys
import com.veyq.core.blobstore.FakePresigningBlobStore
import com.veyq.core.blobstore.StoreReclaimEntity
import com.veyq.core.testing.TestFixtures.CONTENT_KEY
import com.veyq.core.testing.TestFixtures.bearer
import com.veyq.core.testing.TestFixtures.seedMemberScope
import com.veyq.core.testing.TestFixtures.seedNode
import com.veyq.memories.node.MemoriesNodeKinds
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import java.util.UUID
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.notNullValue
import org.hamcrest.CoreMatchers.nullValue
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

/**
 * Reaches `BlobUploadHandler`'s `verifyBlob`/`BlobVerify.Budget`/`purge` path (cr-media-budget-branch-coverage) —
 * entered by zero tests before this class, hermetic or E2E, because `verifyBlob` short-circuits to `Ok`
 * whenever `!blobStore.canPresign` (`BlobUploadHandler.verifyBlob`), true for every other hermetic test's
 * [com.veyq.core.blobstore.InMemoryBlobStore]. [FakePresigningBlobStore] answers `canPresign = true` with
 * a scriptable `stat()`, active only in this class via `getEnabledAlternatives()` — every other hermetic test's
 * `BlobStore` wiring (`RecordingBlobStore` → `InMemoryBlobStore`) is unaffected.
 *
 * Not a leak-prevention test: `purge` deliberately leaves the session row standing rather than deleting it
 * (`BlobUploadHandler.purge`) — `UploadSessionPurge`'s own age-based sweep, a different and already-covered call
 * site, reclaims the same objects even if this class did not exist. What this class actually proves is that
 * `verifyBlob`'s real-vs-declared comparison and the two callers' atomic purge-the-whole-item behavior are
 * correct — neither was exercised by anything before.
 */
@QuarkusTest
@TestProfile(FakePresignProfile::class)
class MediaBudgetBranchSliceTest {

    @Inject
    lateinit var fakeStore: FakePresigningBlobStore

    /** Read a started session's storage key + chunk count, then script every chunk's [stat] via [scriptChunk]. */
    private fun scriptSession(uploadId: String, chunkStat: (index: Int, declaredSize: Long) -> Long?) {
        QuarkusTransaction.requiringNew().run {
            val session = UploadSessionEntity.findById(uploadId)!!
            chunkKeys(session.storageKey, session.chunkCount).forEachIndexed { i, key ->
                fakeStore.statOf(key, chunkStat(i, session.byteSize))
            }
        }
    }

    @Test
    fun completeWithOneSessionOverBudgetPurgesTheWholeItemAtomically() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        val original = startOne(userId, scopeId, TestBlob.SMALL, wrap = "b3JpZw==")
        val thumb = startOne(userId, scopeId, TestBlob.SMALL, wrap = "dGh1bWI=", ownerId = TestBlob.ownerOf(original), role = "THUMBNAIL")

        // original: the store never got the declared byte — stat comes back short of nothing at all (missing)
        scriptSession(original) { _, _ -> null }
        // thumb: correctly sized on its own — proves it is purged too, not because IT failed, but because its
        // sibling in the same item did (completeOne's own doc comment: "the item is atomic")
        scriptSession(thumb) { _, declared -> declared }

        val (originalKey, thumbKey) = QuarkusTransaction.requiringNew().call {
            UploadSessionEntity.findById(original)!!.storageKey to UploadSessionEntity.findById(thumb)!!.storageKey
        }

        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body(
                """{"items":[{"nodeId":"$memory","mediaId":"${TestBlob.ownerOf(original)}","originalUploadId":"$original",""" +
                    """"derivatives":[{"kind":"THUMBNAIL","uploadId":"$thumb"}]}]}""",
            )
            .`when`().post("/api/scopes/$scopeId/media-batch")
            .then().statusCode(200)
            .body("results[0].blobId", nullValue())
            .body("results[0].mediaId", nullValue())
            .body("results[0].error", equalTo("budget_exceeded"))

        QuarkusTransaction.requiringNew().run {
            // both sessions purged (reclaim rows written) — the atomicity claim, not just the failing one
            assert(StoreReclaimEntity.count("storageKey", originalKey) == 1L) {
                "the failing session's chunks must be reclaimed"
            }
            assert(StoreReclaimEntity.count("storageKey", thumbKey) == 1L) {
                "the item is atomic — a sibling session that verified fine must still be purged"
            }
            // purge deliberately does NOT delete the session rows (BlobUploadHandler.purge) — the opposite
            // assertion shape from every other reclaim-record test in this suite; UploadSessionPurge takes it
            // from here on its own retention clock.
            assertNotNull(UploadSessionEntity.findById(original), "purge leaves the session row standing")
            assertNotNull(UploadSessionEntity.findById(thumb), "purge leaves the session row standing")
        }
    }

    @Test
    fun aChunkShortOfItsDerivedLengthFailsTheItemEvenWithTheTotalUnderTheDeclaration() {
        // ADR 0013 §3: complete wants every chunk at EXACTLY its derived length — "the total does not exceed
        // byteSize" lets a short chunk through, and a short frame is one no client sealed. The control is the same
        // shape staged exactly, in the same batch, so the refusal is the one byte and not the endpoint.
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        val blob = TestBlob.ofChunks(2)
        val short = startOne(userId, scopeId, blob, wrap = "c2hvcnQ=")
        val exact = startOne(userId, scopeId, blob, wrap = "ZXhhY3Q=")
        val (shortKey, exactKey) = QuarkusTransaction.requiringNew().call {
            UploadSessionEntity.findById(short)!!.storageKey to UploadSessionEntity.findById(exact)!!.storageKey
        }
        blob.stage(fakeStore, shortKey)
        fakeStore.statOf(chunkKey(shortKey, 0), blob.chunkLength(0) - 1) // every chunk present, total one byte under
        blob.stage(fakeStore, exactKey)

        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body(
                """{"items":[{"nodeId":"$memory","mediaId":"${TestBlob.ownerOf(short)}","originalUploadId":"$short"},""" +
                    """{"nodeId":"$memory","mediaId":"${TestBlob.ownerOf(exact)}","originalUploadId":"$exact"}]}""",
            )
            .`when`().post("/api/scopes/$scopeId/media-batch")
            .then().statusCode(200)
            .body("results[0].error", equalTo("budget_exceeded"))
            .body("results[0].blobId", nullValue())
            .body("results[1].error", nullValue())
            .body("results[1].blobId", notNullValue())

        QuarkusTransaction.requiringNew().run {
            assert(StoreReclaimEntity.count("storageKey", shortKey) == 1L) { "the short item's chunks must be reclaimed" }
            assert(StoreReclaimEntity.count("storageKey", exactKey) == 0L) { "the exact item attached; nothing to reclaim" }
        }
    }

    @Test
    fun commitCoverOverBudgetPurgesAndReportsBudgetExceeded() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        val uploadId = startOne(userId, scopeId, TestBlob.SMALL, wrap = "Y292ZXI=", ownerId = memory, role = "COVER")

        // the store reports more bytes than were declared — exactly the mismatch verifyBlob's Budget case checks
        scriptSession(uploadId) { _, declared -> declared + 1 }
        val storageKey = QuarkusTransaction.requiringNew().call { UploadSessionEntity.findById(uploadId)!!.storageKey }

        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"uploadId":"$uploadId"}""")
            .`when`().post("/api/scopes/$scopeId/nodes/$memory/cover-blob")
            .then().statusCode(400).body("code", equalTo("budget_exceeded"))

        QuarkusTransaction.requiringNew().run {
            assert(StoreReclaimEntity.count("storageKey", storageKey) == 1L) {
                "an over-budget cover commit must reclaim the chunks it staged"
            }
            assertNotNull(UploadSessionEntity.findById(uploadId), "purge leaves the session row standing")
        }
    }

    // --- helpers ---

    private fun startOne(
        userId: String, scopeId: String, blob: TestBlob, wrap: String,
        ownerId: String = UUID.randomUUID().toString(), role: String = "ORIGINAL",
    ): String =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"items":[${blob.declare(wrap, ownerId = ownerId, role = role)}]}""")
            .`when`().post("/api/scopes/$scopeId/blob-uploads-batch")
            .then().statusCode(200)
            .body("results[0].uploadId", notNullValue())
            .body("results[0].presignedPuts", notNullValue()) // canPresign = true here, unlike every other slice test
            .extract().path("results[0].uploadId")
}
