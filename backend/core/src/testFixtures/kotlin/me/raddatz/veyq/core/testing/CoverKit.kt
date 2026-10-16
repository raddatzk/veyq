package me.raddatz.veyq.core.testing

import me.raddatz.veyq.core.blob.AttachmentStore
import me.raddatz.veyq.core.blob.UploadSessionEntity
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import io.quarkus.arc.Arc
import io.quarkus.narayana.jta.QuarkusTransaction
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.ValidatableResponse
import org.hamcrest.CoreMatchers.notNullValue
import java.util.UUID

/**
 * The HTTP shape of a node cover (inbox #411) for the tests of every module that has a kind taking one: start the
 * `COVER` upload the way the web client does (owner = the node, role `COVER`), then `setNodeCover` /
 * `removeNodeCover`. Only the wire, no application: the kinds under test come from the module's own classpath.
 */
object CoverKit {
    /** An open cover upload: its session, the client-minted blob id and the storage key its chunks live under. */
    data class Started(val uploadId: String, val blobId: String, val storageKey: String)

    fun start(
        userId: String, scopeId: String, nodeId: String, blob: TestBlob = TestBlob.SMALL, wrap: String = "Y292ZXItd3JhcA==",
        meta: String? = null, role: String = "COVER", ownerId: String = nodeId,
    ): Started {
        val blobId = UUID.randomUUID().toString()
        val uploadId = given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"items":[${blob.declare(wrap, ownerId = ownerId, role = role, blobId = blobId, meta = meta)}]}""")
            .`when`().post("/api/scopes/$scopeId/blob-uploads-batch")
            .then().statusCode(200).body("results[0].uploadId", notNullValue())
            .extract().path<String>("results[0].uploadId")
        val storageKey = QuarkusTransaction.requiringNew().call { UploadSessionEntity.findById(uploadId)!!.storageKey }
        return Started(uploadId, blobId, storageKey)
    }

    fun set(
        userId: String, scopeId: String, nodeId: String, uploadId: String, expectedBlobId: String?,
        wrappedMeta: String = "bWV0YQ==", metaRevision: Long = 0,
    ): ValidatableResponse =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"uploadId":"$uploadId","expectedBlobId":${expectedBlobId?.let { "\"$it\"" }},"wrappedMeta":"$wrappedMeta","metaRevision":$metaRevision}""")
            .`when`().put("/api/scopes/$scopeId/nodes/$nodeId/cover")
            .then()

    fun remove(
        userId: String, scopeId: String, nodeId: String, blobId: String, wrappedMeta: String = "bWV0YQ==", metaRevision: Long = 0,
    ): ValidatableResponse =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"blobId":"$blobId","wrappedMeta":"$wrappedMeta","metaRevision":$metaRevision}""")
            .`when`().post("/api/scopes/$scopeId/nodes/$nodeId/cover/removal")
            .then()

    /** The per-item presign-download error for a blob: `store_unavailable` (the row exists) vs `upload_not_found` (gone). */
    fun downloadError(userId: String, scopeId: String, blobId: String): String? =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"items":[{"blobId":"$blobId"}]}""")
            .`when`().post("/api/scopes/$scopeId/blob-downloads-batch")
            .then().statusCode(200).extract().path("results[0].error")

    /** The blob in the node's cover slot now, or null. */
    fun slot(nodeId: String): String? = Arc.container().instance(AttachmentStore::class.java).get().slotBlobId(nodeId, "COVER")

    /** The node's sealed meta and version as stored now. */
    fun meta(nodeId: String): Pair<String?, Long> =
        QuarkusTransaction.requiringNew().call { NodeEntity.findById(nodeId)!!.let { it.wrappedMeta to it.metaRevision } }
}
