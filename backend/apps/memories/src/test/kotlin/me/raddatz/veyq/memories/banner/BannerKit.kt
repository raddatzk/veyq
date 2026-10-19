package me.raddatz.veyq.memories.banner

import me.raddatz.veyq.core.blob.AttachmentStore
import me.raddatz.veyq.core.testing.CoverKit
import me.raddatz.veyq.core.testing.TestBlob
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import io.quarkus.arc.Arc
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.ValidatableResponse

/**
 * The HTTP shape of a Memory's banner (uc.memory.banner) for the tests: start the `BANNER` / `BANNER_POSTER` uploads the
 * way the web client does (owner = the node, role = the slot), then `setMemoryBanner` / `removeMemoryBanner`. The start
 * is core's cover kit's with the banner's role: one upload path for every attachment.
 */
object BannerKit {
    fun startBanner(userId: String, scopeId: String, nodeId: String, blob: TestBlob = TestBlob.SMALL): CoverKit.Started =
        CoverKit.start(userId, scopeId, nodeId, blob = blob, wrap = "YmFubmVyLXdyYXA=", meta = "YmFubmVyLW1ldGE=", role = MemoryBanner.SLOT)

    fun startPoster(userId: String, scopeId: String, nodeId: String, blob: TestBlob = TestBlob.SMALL): CoverKit.Started =
        CoverKit.start(userId, scopeId, nodeId, blob = blob, wrap = "cG9zdGVyLXdyYXA=", meta = "cG9zdGVyLW1ldGE=", role = MemoryBanner.POSTER_SLOT)

    fun set(
        userId: String?, scopeId: String, nodeId: String, uploadId: String, posterUploadId: String? = null,
        expectedBlobId: String? = null, expectedPosterBlobId: String? = null, wrappedMeta: String = "bWV0YQ==", metaRevision: Long = 0,
    ): ValidatableResponse =
        given().apply { if (userId != null) header("Authorization", bearer(userId)) }.contentType(ContentType.JSON)
            .body(
                """{"uploadId":"$uploadId","posterUploadId":${q(posterUploadId)},"expectedBlobId":${q(expectedBlobId)},""" +
                    """"expectedPosterBlobId":${q(expectedPosterBlobId)},"wrappedMeta":"$wrappedMeta","metaRevision":$metaRevision}""",
            )
            .`when`().put("/api/scopes/$scopeId/nodes/$nodeId/banner")
            .then()

    fun remove(
        userId: String?, scopeId: String, nodeId: String, blobId: String, posterBlobId: String? = null,
        wrappedMeta: String = "bWV0YQ==", metaRevision: Long = 0,
    ): ValidatableResponse =
        given().apply { if (userId != null) header("Authorization", bearer(userId)) }.contentType(ContentType.JSON)
            .body("""{"blobId":"$blobId","posterBlobId":${q(posterBlobId)},"wrappedMeta":"$wrappedMeta","metaRevision":$metaRevision}""")
            .`when`().post("/api/scopes/$scopeId/nodes/$nodeId/banner/removal")
            .then()

    /** What the two banner slots hold now (null: empty). */
    fun slots(nodeId: String): Pair<String?, String?> =
        Arc.container().instance(AttachmentStore::class.java).get().let {
            it.slotBlobId(nodeId, MemoryBanner.SLOT) to it.slotBlobId(nodeId, MemoryBanner.POSTER_SLOT)
        }

    private fun q(v: String?): String = v?.let { "\"$it\"" } ?: "null"
}
