package me.raddatz.veyq.core.blob

import me.raddatz.veyq.core.blob.ChunkFrames
import me.raddatz.veyq.core.testing.TestBlob
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedUser
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import java.util.UUID
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.notNullValue
import org.hamcrest.CoreMatchers.nullValue
import org.junit.jupiter.api.Test

/**
 * Slice tests for media blobs (en.memory.media, cr-0028) against the **in-memory store** (dev/test metadata
 * only — no byte transfer; the byte path is the opt-in [BlobBatchE2ETest]). Covers: start→complete→attach→list
 * metadata round-trip; partial-batch per-item result; illegal_target (422-equivalent per-item) on a FOLDER;
 * budget_exceeded on an over-cap declaration; non-member 404; unauth 401; operator-blind (the K_blob wrap
 * round-trips verbatim). UUID-unique seeds so the JVM-lived H2 mem DB can't bleed across tests.
 *
 * **Split from `MediaSliceTest`** (cr-platform-gradle-modules step 8a): these methods run with core alone, so they live in
 * `:core`; the rest of the class is `MediaSliceTest` in `:apps:memories`. The bodies moved verbatim.
 */
@QuarkusTest
class MediaCoreSliceTest {

    @Test
    fun overCapDeclarationIsBudgetExceeded() {
        val (userId, scopeId) = seedMemberScope()
        // a well-formed declaration beyond the per-blob cap (`veyq.blob.max-bytes`) — declare-then-verify rejects at start
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"items":[${TestBlob.ofChunks(100_000).declare("aw==")}]}""")
            .`when`().post("/api/scopes/$scopeId/blob-uploads-batch")
            .then().statusCode(200)
            .body("results[0].error", equalTo("budget_exceeded"))
            .body("results[0].uploadId", nullValue())
    }

    @Test
    fun aDeclarationWhoseShapeTheSizeDoesNotFixIsShapeInvalid() {
        // ADR 0013 §3: the frame size fixes chunkCount from byteSize, so any other count is a client bug — and a
        // byteSize no frame sequence has (here: a last frame of two carrying no plaintext) is one too. Per item,
        // beside a derived control in the SAME batch, so the refusals are the shape and not the endpoint.
        val (userId, scopeId) = seedMemberScope()
        val blob = TestBlob.ofChunks(2)
        val impossible = ChunkFrames.SEALED_BYTES + ChunkFrames.OVERHEAD
        val items = listOf(
            blob.declare("aw=="),
            """{"blobId":"${UUID.randomUUID()}","ownerId":"${UUID.randomUUID()}","role":"ORIGINAL","chunkCount":${blob.chunkCount + 1},"byteSize":${blob.byteSize},"wrappedBlobKey":"aw=="}""",
            """{"blobId":"${UUID.randomUUID()}","ownerId":"${UUID.randomUUID()}","role":"ORIGINAL","chunkCount":${blob.chunkCount - 1},"byteSize":${blob.byteSize},"wrappedBlobKey":"aw=="}""",
            """{"blobId":"${UUID.randomUUID()}","ownerId":"${UUID.randomUUID()}","role":"ORIGINAL","chunkCount":2,"byteSize":$impossible,"wrappedBlobKey":"aw=="}""",
        )
        startBatch(userId, scopeId, items.joinToString(",", "[", "]")).then().statusCode(200)
            .body("results[0].uploadId", notNullValue())
            .body("results[0].error", nullValue())
            .body("results[1].error", equalTo("shape_invalid"))
            .body("results[1].uploadId", nullValue())
            .body("results[2].error", equalTo("shape_invalid"))
            .body("results[2].uploadId", nullValue())
            .body("results[3].error", equalTo("shape_invalid"))
            .body("results[3].uploadId", nullValue())
    }

    @Test
    fun nonMemberIs404() {
        val (_, scopeId) = seedMemberScope()
        val outsider = seedUser()
        given().header("Authorization", bearer(outsider)).contentType(ContentType.JSON)
            .body("""{"items":[${TestBlob.SMALL.declare("aw==")}]}""")
            .`when`().post("/api/scopes/$scopeId/blob-uploads-batch")
            .then().statusCode(404).body("code", equalTo("scope_not_found"))
    }

    @Test
    fun unauthenticatedIs401() {
        given().contentType(ContentType.JSON)
            .body("""{"items":[${TestBlob.SMALL.declare("aw==")}]}""")
            .`when`().post("/api/scopes/${UUID.randomUUID()}/blob-uploads-batch")
            .then().statusCode(401).body("code", equalTo("authentication_failed"))
    }

    @Test
    fun startBlobUploadsOverItsItemBoundIs400() {
        val (userId, scopeId) = seedMemberScope()
        // control: exactly `maxItems` (256) is accepted — so the negative below is the bound, not the endpoint
        startBatch(userId, scopeId, uploadItems(256)).then().statusCode(200).body("results.size()", equalTo(256))
        startBatch(userId, scopeId, uploadItems(257)).then().statusCode(400)
            .body("code", equalTo("invalid_request"))
    }

    @Test
    fun startBlobUploadsValidatesInsideEachItem() {
        val (userId, scopeId) = seedMemberScope()
        // control: the same one-item batch with a well-formed wrap is accepted
        startBatch(userId, scopeId, uploadItems(1)).then().statusCode(200)
        startBatch(userId, scopeId, """[${TestBlob.SMALL.declare("")}]""").then().statusCode(400)
            .body("code", equalTo("invalid_request"))
    }

    // --- live media updates (uc.memory.live-updates, cr-0107, ADR 0033) ---

    // --- helpers ---

    /** Start one blob upload (in-memory store: presignedPuts come back null) and return its uploadId. */
    // The request-body bounds are enforced at the boundary, not in the handler: a violation answers core's
    // `ConstraintViolationExceptionMapper` (400 `invalid_request`, nothing of the failing field — inbox #355), so each
    // refusal is paired with a control that differs only in the bound. `validate_request_bounds.py` proves each
    // annotation is PRESENT; these prove the two mechanisms it relies on have an EFFECT — a top-level bound, and the
    // `@Valid` cascade.

    private fun uploadItems(n: Int) = (1..n).joinToString(",", "[", "]") { TestBlob.SMALL.declare("d3JhcA==") }

    private fun startBatch(userId: String, scopeId: String, items: String) =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"items":$items}""")
            .`when`().post("/api/scopes/$scopeId/blob-uploads-batch")
}
