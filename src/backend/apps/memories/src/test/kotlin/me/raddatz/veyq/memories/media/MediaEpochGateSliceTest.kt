package me.raddatz.veyq.memories.media

import me.raddatz.veyq.core.TestIds
import me.raddatz.veyq.core.blob.UploadSessionEntity
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.scope.ScopeEntity
import me.raddatz.veyq.core.sharing.ShareWriteProof
import me.raddatz.veyq.core.testing.TestBlob
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.memories.banner.BannerKit
import me.raddatz.veyq.memories.node.MemoriesNodeKinds
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.ValidatableResponse
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.nullValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

/**
 * Layer 1 of ADR 0109 point 6 on Memories' writes (bite test 3): a write that seals under a Memory's key states the
 * Memory's epoch. Each write is tried three times on ONE predicate — the node's stored epoch against the stated one and
 * the scope's current one:
 *
 * - the scope rotated to 1 and the Memory is still at 0, stated 0 → `node_rekey_pending` (the re-key has not reached it);
 * - the Memory re-keyed to 1, stated 0 → `stale_epoch` (the writer holds the key from before);
 * - the Memory at 1, stated 1 → accepted (the control).
 *
 * A refusal writes nothing: the medium, the bucket, the banner slots and the meta read back as before, and an upload a
 * refused completion named is still open — the control lands the very same upload.
 */
@QuarkusTest
class MediaEpochGateSliceTest {

    @Test
    fun aMembersCompletionStatesTheMemorysEpochPerItem() {
        val w = world()
        val uploadId = startUpload(w)
        lag(w)
        complete(w, uploadId, nodeEpoch = 0).statusCode(200).body("results[0].error", equalTo("node_rekey_pending"))
        rekeyed(w)
        complete(w, uploadId, nodeEpoch = 0).statusCode(200).body("results[0].error", equalTo("stale_epoch"))
        assertEquals(0L, QuarkusTransaction.requiringNew().call { MediaEntity.count("nodeId", w.memory) }, "a refused item stores nothing")
        assertNotNull(QuarkusTransaction.requiringNew().call { UploadSessionEntity.findById(uploadId) }, "its upload stays open")
        complete(w, uploadId, nodeEpoch = 1).statusCode(200).body("results[0].error", nullValue()) // control: the same upload
    }

    @Test
    fun aMetaEditStatesItsMediumsNodeEpoch() {
        val w = world()
        val mediaId = complete(w, startUpload(w), nodeEpoch = 0).statusCode(200).body("results[0].error", nullValue())
            .extract().path<String>("results[0].mediaId")
        lag(w)
        putMeta(w, mediaId, nodeEpoch = 0).statusCode(409).body("code", equalTo("node_rekey_pending"))
        rekeyed(w)
        putMeta(w, mediaId, nodeEpoch = 0).statusCode(409).body("code", equalTo("stale_epoch"))
        val unchanged = QuarkusTransaction.requiringNew().call { MediaEntity.findById(mediaId)!! }
        assertEquals("bWV0YQ==" to 0L, unchanged.wrappedMeta to unchanged.metaRevision, "a refused edit writes nothing")
        putMeta(w, mediaId, nodeEpoch = 1).statusCode(200).body("wrappedMeta", equalTo("ZWRpdGVk")) // control
    }

    @Test
    fun aBucketsCreateAndRenameStateTheMemorysEpoch() {
        val w = world()
        val renamed = createBucket(w, TestIds.uuid(), nodeEpoch = 0).statusCode(200).extract().path<String>("bucketId")
        lag(w)
        val bucketId = TestIds.uuid()
        createBucket(w, bucketId, nodeEpoch = 0).statusCode(409).body("code", equalTo("node_rekey_pending"))
        renameBucket(w, renamed, nodeEpoch = 0).statusCode(409).body("code", equalTo("node_rekey_pending"))
        rekeyed(w)
        createBucket(w, bucketId, nodeEpoch = 0).statusCode(409).body("code", equalTo("stale_epoch"))
        renameBucket(w, renamed, nodeEpoch = 0).statusCode(409).body("code", equalTo("stale_epoch"))
        QuarkusTransaction.requiringNew().run {
            assertEquals(null, BucketEntity.findById(bucketId), "a refused create stores nothing")
            assertEquals("YnVja2V0", BucketEntity.findById(renamed)!!.sealedName, "a refused rename writes nothing")
        }
        createBucket(w, bucketId, nodeEpoch = 1).statusCode(200) // control
        renameBucket(w, renamed, nodeEpoch = 1).statusCode(200).body("sealedName", equalTo("cmVuYW1lZA=="))
    }

    @Test
    fun aBannerStatesTheMemorysEpoch() {
        val w = world()
        val banner = BannerKit.startBanner(w.userId, w.scopeId, w.memory)
        lag(w)
        BannerKit.set(w.userId, w.scopeId, w.memory, banner.uploadId, nodeEpoch = 0).statusCode(409).body("code", equalTo("node_rekey_pending"))
        rekeyed(w)
        BannerKit.set(w.userId, w.scopeId, w.memory, banner.uploadId, nodeEpoch = 0).statusCode(409).body("code", equalTo("stale_epoch"))
        assertEquals(null to null, BannerKit.slots(w.memory), "a refused banner attaches nothing")
        assertNotNull(QuarkusTransaction.requiringNew().call { UploadSessionEntity.findById(banner.uploadId) }, "its upload stays open")
        BannerKit.set(w.userId, w.scopeId, w.memory, banner.uploadId, nodeEpoch = 1).statusCode(204) // control
        BannerKit.remove(w.userId, w.scopeId, w.memory, banner.blobId, wrappedMeta = "bTI=", metaRevision = 1, nodeEpoch = 0)
            .statusCode(409).body("code", equalTo("stale_epoch"))
        BannerKit.remove(w.userId, w.scopeId, w.memory, banner.blobId, wrappedMeta = "bTI=", metaRevision = 1, nodeEpoch = 1).statusCode(204)
    }

    @Test
    fun aGuestsCompletionStatesTheTargetsEpochForTheBatch() {
        val w = world()
        val (shareId, token) = contributor(w)
        val uploadId = given().contentType(ContentType.JSON)
            .body("""{"contributorToken":"$token","items":[${TestBlob.SMALL.declare("Ymxvyi13cmFw")}]}""")
            .`when`().post("/api/shares/$shareId/blob-uploads")
            .then().statusCode(200).extract().path<String>("results[0].uploadId")
        lag(w)
        guestComplete(shareId, token, uploadId, nodeEpoch = 0).statusCode(409).body("code", equalTo("node_rekey_pending"))
        rekeyed(w)
        guestComplete(shareId, token, uploadId, nodeEpoch = 0).statusCode(409).body("code", equalTo("stale_epoch"))
        assertEquals(0L, QuarkusTransaction.requiringNew().call { MediaEntity.count("nodeId", w.memory) }, "a refused batch attaches nothing")
        assertNotNull(QuarkusTransaction.requiringNew().call { UploadSessionEntity.findById(uploadId) }, "its upload stays open")
        guestComplete(shareId, token, uploadId, nodeEpoch = 1).statusCode(200).body("results[0].error", nullValue()) // control
    }

    // --- the world and its epochs ----------------------------------------------------------------------------------

    private data class World(val userId: String, val scopeId: String, val memory: String)

    private fun world(): World {
        val (userId, scopeId) = seedMemberScope()
        return World(userId, scopeId, seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY))
    }

    /** The scope rotated to 1; the Memory's key is still from epoch 0. */
    private fun lag(w: World) = epochs(w, scope = 1, node = 0)

    /** The Memory re-keyed to the scope's current epoch 1. */
    private fun rekeyed(w: World) = epochs(w, scope = 1, node = 1)

    private fun epochs(w: World, scope: Int, node: Int) = QuarkusTransaction.requiringNew().run {
        ScopeEntity.findById(w.scopeId)!!.currentEpoch = scope
        NodeEntity.findById(w.memory)!!.epoch = node
    }

    // --- HTTP -------------------------------------------------------------------------------------------------------

    private fun startUpload(w: World): String =
        given().header("Authorization", bearer(w.userId)).contentType(ContentType.JSON)
            .body("""{"items":[${TestBlob.SMALL.declare("YQ==")}]}""")
            .`when`().post("/api/scopes/${w.scopeId}/blob-uploads-batch")
            .then().statusCode(200).extract().path("results[0].uploadId")

    private fun complete(w: World, uploadId: String, nodeEpoch: Int): ValidatableResponse =
        given().header("Authorization", bearer(w.userId)).contentType(ContentType.JSON)
            .body(
                """{"items":[{"mediaId":"${TestBlob.ownerOf(uploadId)}","nodeEpoch":$nodeEpoch,"originalUploadId":"$uploadId",""" +
                    """"nodeId":"${w.memory}","wrappedMeta":"bWV0YQ=="}]}""",
            )
            .`when`().post("/api/scopes/${w.scopeId}/media-batch").then()

    private fun putMeta(w: World, mediaId: String, nodeEpoch: Int): ValidatableResponse =
        given().header("Authorization", bearer(w.userId)).contentType(ContentType.JSON)
            .body("""{"wrappedMeta":"ZWRpdGVk","metaRevision":0,"nodeEpoch":$nodeEpoch}""")
            .`when`().put("/api/scopes/${w.scopeId}/media/$mediaId/meta").then()

    private fun createBucket(w: World, bucketId: String, nodeEpoch: Int): ValidatableResponse =
        given().header("Authorization", bearer(w.userId)).contentType(ContentType.JSON)
            .body("""{"bucketId":"$bucketId","sealedName":"YnVja2V0","nodeEpoch":$nodeEpoch}""")
            .`when`().post("/api/scopes/${w.scopeId}/nodes/${w.memory}/buckets").then()

    private fun renameBucket(w: World, bucketId: String, nodeEpoch: Int): ValidatableResponse =
        given().header("Authorization", bearer(w.userId)).contentType(ContentType.JSON)
            .body("""{"sealedName":"cmVuYW1lZA==","nodeEpoch":$nodeEpoch}""")
            .`when`().patch("/api/scopes/${w.scopeId}/nodes/${w.memory}/buckets/$bucketId").then()

    /** A READ_WRITE link on the Memory and a guest that named itself on it, both before any rotation: (shareId, token). */
    private fun contributor(w: World): Pair<String, String> {
        val shareId = given().header("Authorization", bearer(w.userId)).contentType(ContentType.JSON)
            .body("""{${ShareWriteProof.newShare()},"nodeId":"${w.memory}","role":"READ_WRITE","wrap":"d3JhcA==","sealedName":"c2VhbGVk",${ShareWriteProof.recovery()}}""")
            .`when`().post("/api/shares").then().statusCode(201).extract().path<String>("shareId")
        val token = given().contentType(ContentType.JSON)
            .body("""{"identityId":"${TestIds.uuid()}","sealedDisplayName":"Z3Vlc3Q=","nodeEpoch":0}""")
            .header(ShareWriteProof.HEADER, ShareWriteProof.token(shareId)).`when`().post("/api/shares/$shareId/identities")
            .then().statusCode(201).extract().path<String>("contributorToken")
        return shareId to token
    }

    private fun guestComplete(shareId: String, token: String, uploadId: String, nodeEpoch: Int): ValidatableResponse =
        given().contentType(ContentType.JSON)
            .body("""{"nodeEpoch":$nodeEpoch,"contributorToken":"$token","items":[{"mediaId":"${TestBlob.ownerOf(uploadId)}","originalUploadId":"$uploadId"}]}""")
            .`when`().post("/api/shares/$shareId/media").then()
}
