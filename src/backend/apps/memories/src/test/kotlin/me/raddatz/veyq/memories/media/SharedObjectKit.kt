package me.raddatz.veyq.memories.media

import me.raddatz.veyq.core.testing.CoverKit
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.ValidatableResponse
import org.hamcrest.CoreMatchers.nullValue
import java.util.UUID

/**
 * Rows on one stored object for the tests (ADR 0013 § 5): a gallery medium as the upload completes it, a banner (and
 * its poster) made as rows on its objects through the contract's `SharedBlob`, and the medium's erasure.
 */
object SharedObjectKit {
    /** One medium: its original and its PREVIEW tier, as the gallery's upload completes them. */
    data class Medium(val mediaId: String, val original: CoverKit.Started, val preview: CoverKit.Started)

    fun medium(userId: String, scopeId: String, nodeId: String): Medium {
        val mediaId = UUID.randomUUID().toString()
        val original = CoverKit.start(userId, scopeId, nodeId, role = "ORIGINAL", ownerId = mediaId)
        val preview = CoverKit.start(userId, scopeId, nodeId, role = "PREVIEW", ownerId = mediaId)
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body(
                """{"items":[{"nodeId":"$nodeId","mediaId":"$mediaId","nodeEpoch":0,"originalUploadId":"${original.uploadId}",""" +
                    """"derivatives":[{"kind":"PREVIEW","uploadId":"${preview.uploadId}"}]}]}""",
            )
            .`when`().post("/api/scopes/$scopeId/media-batch").then().statusCode(200).body("results[0].error", nullValue())
        return Medium(mediaId, original, preview)
    }

    /** A `SharedBlob` body: a new row [blobId] on [source]'s object. */
    fun shared(source: String, blobId: String = UUID.randomUUID().toString()): String =
        """{"sourceBlobId":"$source","blobId":"$blobId","wrappedBlobKey":"c2hhcmVk","wrappedBlobMeta":null}"""

    fun setBanner(userId: String, scopeId: String, nodeId: String, shared: String, posterShared: String?): ValidatableResponse =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"shared":$shared,"posterShared":${posterShared ?: "null"},"wrappedMeta":"bWV0YQ==","nodeEpoch":0,"metaRevision":0}""")
            .`when`().put("/api/scopes/$scopeId/nodes/$nodeId/banner").then()

    /** The medium trashed and purged at once (the member-expedited purge). */
    fun trashAndPurge(userId: String, scopeId: String, mediaId: String) {
        given().header("Authorization", bearer(userId)).`when`().delete("/api/scopes/$scopeId/media/$mediaId").then().statusCode(204)
        given().header("Authorization", bearer(userId)).`when`().post("/api/scopes/$scopeId/media/$mediaId/purge").then().statusCode(204)
    }
}
