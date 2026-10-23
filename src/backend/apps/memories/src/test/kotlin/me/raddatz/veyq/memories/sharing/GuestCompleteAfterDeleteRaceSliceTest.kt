package me.raddatz.veyq.memories.sharing

import me.raddatz.veyq.core.TestIds
import me.raddatz.veyq.core.blob.UploadSessionEntity
import me.raddatz.veyq.core.blobstore.FakePresigningBlobStore
import me.raddatz.veyq.core.testing.FakePresignProfile
import me.raddatz.veyq.core.testing.RACE_ROUNDS
import me.raddatz.veyq.core.testing.TestBlob
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.identityKeyJson
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.core.testing.TestFixtures.shareKeysJson
import me.raddatz.veyq.memories.media.MediaEntity
import me.raddatz.veyq.memories.node.MemoriesNodeKinds
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import me.raddatz.veyq.core.sharing.ShareIdentityEntity
import me.raddatz.veyq.core.sharing.ShareWriteGate
import me.raddatz.veyq.core.sharing.ShareWriteProof
import me.raddatz.veyq.core.sharing.SharingStore

/**
 * A guest complete whose link is deleted **after its gate passed and before its attach began** is refused — the
 * window `GuestWriteRaceSliceTest` does not open. That test holds the delete uncommitted while the complete runs, so
 * any check of the link, early or late, waits on the delete's row lock and sees it; it stays green when the re-check
 * (`ShareWriteGate.Admitted.admitted`) is asked in the gate. Here the delete has **committed** before the attach
 * transaction starts, so only a re-check asked inside that transaction still sees it: asked anywhere earlier — in the
 * gate, or by the caller before it hands the closure on — it answered "admitted", and the upload landed on a deleted
 * link (a photo whose identity the delete dropped).
 *
 * The overlap is forced, not likely: the complete stats its chunks (`BlobUploadHandler.verifyBlob`) after its gate and
 * before its attach, and [FakePresigningBlobStore.onStat] holds it there on a latch until the delete has committed.
 * Each round has a control: the same held complete on a link nobody deletes lands.
 */
@QuarkusTest
@TestProfile(FakePresignProfile::class)
class GuestCompleteAfterDeleteRaceSliceTest {

    @Inject lateinit var sharing: SharingStore
    @Inject lateinit var fakeStore: FakePresigningBlobStore

    private val sealedName = "c2VhbGVkLW5hbWU="
    private val sealedUploaderName = "c2VhbGVkLXVwbG9hZGVyLW5hbWU="

    @Test
    fun anUploadWhoseLinkIsDeletedBetweenItsGateAndItsAttachIsRefused() {
        repeat(RACE_ROUNDS) { round ->
            val (userId, scopeId) = seedMemberScope()
            val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, parentId = scopeId, wrappedContentKey = CONTENT_KEY)
            val shareId = uploaderShare(userId, memory)
            val (identityId, token) = identity(shareId)
            val uploadId = startStaged(shareId, token)
            val answer = heldBetweenGateAndAttach(shareId, token, uploadId) {
                assertEquals(true, tx { sharing.delete(shareId) }, "round $round: the delete commits")
            }
            assertEquals(200 to "upload_not_found", answer, "round $round: an upload landed on a link deleted after its gate")
            tx {
                assertEquals(0L, MediaEntity.count("shareIdentityId", identityId), "round $round: a photo without its identity")
                assertNull(ShareIdentityEntity.findById(identityId), "round $round: the delete drops the unused identity")
            }
            // Control: the same held complete on a link nobody deletes lands, stamped with a live identity.
            val kept = uploaderShare(userId, memory)
            val (keptIdentity, keptToken) = identity(kept)
            assertEquals(200 to null, heldBetweenGateAndAttach(kept, keptToken, startStaged(kept, keptToken)) {}, "round $round: control")
            tx {
                assertEquals(1L, MediaEntity.count("shareIdentityId", keptIdentity), "round $round: control")
                assertNotNull(ShareIdentityEntity.findById(keptIdentity), "round $round: control")
            }
        }
    }

    /**
     * Complete [uploadId] on another thread, hold it at its first chunk stat (past the gate, before the attach), run
     * [between] — committed before the complete goes on — and return the complete's answer as (status, item error).
     */
    private fun heldBetweenGateAndAttach(shareId: String, token: String, uploadId: String, between: () -> Unit): Pair<Int, String?> {
        val gated = CountDownLatch(1)
        val released = CountDownLatch(1)
        fakeStore.onStat = { gated.countDown(); released.await(10, TimeUnit.SECONDS) }
        val pool = Executors.newSingleThreadExecutor()
        try {
            val answer = pool.submit(Callable { complete(shareId, token, uploadId) })
            assertTrue(gated.await(10, TimeUnit.SECONDS), "the complete passed its gate and reached its verify")
            between()
            released.countDown()
            return answer.get(10, TimeUnit.SECONDS)
        } finally {
            released.countDown()
            fakeStore.onStat = null
            pool.shutdownNow()
        }
    }

    // --- helpers ---

    private fun uploaderShare(userId: String, nodeId: String): String {
        val shareId = TestIds.shareId()
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"shareId":"$shareId",${ShareWriteProof.field(shareId)},"nodeId":"$nodeId","role":"WRITE_ONLY",${shareKeysJson("WRITE_ONLY", nodeId)},"sealedName":"$sealedName",${ShareWriteProof.recovery()}}""")
            .`when`().post("/api/shares").then().statusCode(201)
        return shareId
    }

    /** A named guest on [shareId]: (identityId, contributorToken). */
    private fun identity(shareId: String): Pair<String, String> {
        val r = given().contentType(ContentType.JSON)
            .body("""{"nodeEpoch":0,"identityId":"${TestIds.uuid()}","sealedDisplayName":"$sealedUploaderName"${identityKeyJson("WRITE_ONLY")}}""")
            .header(ShareWriteProof.HEADER, ShareWriteProof.token(shareId)).`when`().post("/api/shares/$shareId/identities")
            .then().extract()
        assertEquals(201, r.statusCode())
        return r.path<String>("identityId") to r.path<String>("contributorToken")
    }

    /** Start one upload and stage its chunks at their exact lengths, so its verify passes. */
    private fun startStaged(shareId: String, token: String): String {
        val uploadId: String = given().contentType(ContentType.JSON)
            .body("""{"contributorToken":"$token","items":[${TestBlob.SMALL.declare("Ymxvyi13cmFw")}]}""")
            .`when`().post("/api/shares/$shareId/blob-uploads")
            .then().statusCode(200).extract().path("results[0].uploadId")
        TestBlob.SMALL.stage(fakeStore, tx { UploadSessionEntity.findById(uploadId)!!.storageKey })
        return uploadId
    }

    /** A guest complete's answer as (status, the item's error). */
    private fun complete(shareId: String, token: String, uploadId: String): Pair<Int, String?> =
        given().contentType(ContentType.JSON)
            .body("""{"nodeEpoch":0,"contributorToken":"$token","items":[{"mediaId":"${TestBlob.ownerOf(uploadId)}","originalUploadId":"$uploadId"}]}""")
            .`when`().post("/api/shares/$shareId/media").then().extract()
            .let { r -> r.statusCode() to runCatching { r.path<String?>("results[0].error") }.getOrNull() }

    private fun <T> tx(body: () -> T): T = QuarkusTransaction.requiringNew().call(body)
}
