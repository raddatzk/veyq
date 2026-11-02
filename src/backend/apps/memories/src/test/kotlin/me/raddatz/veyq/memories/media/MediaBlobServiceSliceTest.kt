package me.raddatz.veyq.memories.media

import me.raddatz.veyq.core.applicationblob.ApplicationBlobEntity
import me.raddatz.veyq.core.blob.UploadSessionEntity
import me.raddatz.veyq.core.testing.TestBlob
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.memories.node.MemoriesNodeKinds
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import java.util.UUID
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.nullValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * A medium's original and derivatives are blobs of core's blob service held by it (ADR 0113 decisions 1, 2 and 4): its
 * completion writes one service row per blob, owned by the medium in its role, born kept and written by its uploader,
 * and Memories' own listing serves them as before. They are its owner's content: the service's own read and completion
 * routes serve no Memory, and a medium holds one blob per role — one naming a tier twice is refused whole.
 */
@QuarkusTest
class MediaBlobServiceSliceTest {

    @Test
    fun aMediumsBlobsAreTheServicesRowsHeldByIt_andItsListingServesThem() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        val mediaId = UUID.randomUUID().toString()
        val original = startOne(userId, scopeId, mediaId, "ORIGINAL")
        val thumb = startOne(userId, scopeId, mediaId, "THUMBNAIL")
        val preview = startOne(userId, scopeId, mediaId, "PREVIEW")
        val originalBlob = complete(userId, scopeId, memory, mediaId, original, "THUMBNAIL" to thumb, "PREVIEW" to preview)
            .body("results[0].error", nullValue()).extract().path<String>("results[0].blobId")

        val rows = QuarkusTransaction.requiringNew().call { ApplicationBlobEntity.list("ownerId", mediaId) }
        assertEquals(setOf("ORIGINAL", "THUMBNAIL", "PREVIEW"), rows.map { it.role }.toSet())
        rows.forEach { r ->
            assertEquals(listOf<Any?>(memory, r.blobId, userId, null), listOf(r.nodeId, r.id, r.writerUserId, r.releasedAt), r.role)
            assertEquals(r.createdAt, r.keptAt, "born kept: the medium is written with its blobs")
        }
        assertEquals(originalBlob, rows.single { it.role == "ORIGINAL" }.blobId)
        given().header("Authorization", bearer(userId)).`when`().get("/api/scopes/$scopeId/nodes/$memory/media").then().statusCode(200)
            .body("media[0].blobId", equalTo(originalBlob))
            .body("media[0].derivatives.kind", equalTo(listOf("THUMBNAIL", "PREVIEW")))

        // an owner's content: the service's own routes serve no Memory
        given().header("Authorization", bearer(userId)).`when`().get("/api/scopes/$scopeId/nodes/$memory/application-blobs")
            .then().statusCode(404).body("code", equalTo("node_not_found"))
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"nodeEpoch":0,"id":"${UUID.randomUUID()}","ownerId":"$mediaId","role":"ORIGINAL","uploadId":"${UUID.randomUUID()}"}""")
            .`when`().post("/api/scopes/$scopeId/nodes/$memory/application-blobs").then().statusCode(404).body("code", equalTo("node_not_found"))
    }

    /** One blob per role and medium (the service's count): a medium naming THUMBNAIL twice stores nothing, its uploads
     *  stay open; the control, the same medium with one THUMBNAIL, is stored. */
    @Test
    fun aMediumNamingOneTierTwiceIsRefusedWhole() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        val mediaId = UUID.randomUUID().toString()
        val original = startOne(userId, scopeId, mediaId, "ORIGINAL")
        val first = startOne(userId, scopeId, mediaId, "THUMBNAIL")
        val second = startOne(userId, scopeId, mediaId, "THUMBNAIL")
        complete(userId, scopeId, memory, mediaId, original, "THUMBNAIL" to first, "THUMBNAIL" to second)
            .body("results[0].error", equalTo("upload_mismatch"))
        QuarkusTransaction.requiringNew().run {
            assertNull(MediaEntity.findById(mediaId), "nothing of the medium is stored")
            assertEquals(0L, ApplicationBlobEntity.count("ownerId", mediaId))
            assertEquals(3L, UploadSessionEntity.count("id in ?1", listOf(original, first, second)), "its uploads stay open")
        }
        complete(userId, scopeId, memory, mediaId, original, "THUMBNAIL" to first).body("results[0].error", nullValue())
        assertEquals(2L, QuarkusTransaction.requiringNew().call { ApplicationBlobEntity.count("ownerId", mediaId) })
    }

    private fun complete(
        userId: String, scopeId: String, nodeId: String, mediaId: String, original: String, vararg tiers: Pair<String, String>,
    ) = given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
        .body(
            """{"items":[{"mediaId":"$mediaId","nodeEpoch":0,"nodeId":"$nodeId","originalUploadId":"$original","derivatives":[${
                tiers.joinToString(",") { (kind, upload) -> """{"kind":"$kind","uploadId":"$upload"}""" }
            }]}]}""",
        )
        .`when`().post("/api/scopes/$scopeId/media-batch").then().statusCode(200)

    private fun startOne(userId: String, scopeId: String, mediaId: String, role: String): String =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"items":[${TestBlob.SMALL.declare("d3JhcA==", ownerId = mediaId, role = role)}]}""")
            .`when`().post("/api/scopes/$scopeId/blob-uploads-batch")
            .then().statusCode(200).extract().path("results[0].uploadId")
}
