package me.raddatz.veyq.core.blob

import me.raddatz.veyq.core.testing.FakePresignProfile
import me.raddatz.veyq.core.testing.TestBlob
import me.raddatz.veyq.core.blob.UploadSessionEntity
import me.raddatz.veyq.core.blobstore.FakePresigningBlobStore
import me.raddatz.veyq.core.membership.MembershipEntity
import me.raddatz.veyq.core.membership.TenantRoots
import me.raddatz.veyq.core.scope.ScopeEntity
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedUser
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import java.time.Duration
import java.time.Instant
import java.util.UUID
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.nullValue
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The member half of windowed presigning (uc.memory.upload, ADR 0013 §2): `start` signs only a blob's first window,
 * and a session is bound to the account that opened it — so only its opener can complete it, commit it as a cover,
 * or ask for its next window, and only while still a member.
 *
 * Runs on [FakePresigningBlobStore] (the same profile as [MediaBudgetBranchSliceTest], so the two share one app
 * boot): the in-memory store cannot presign, and a window is nothing but presigned URLs.
 *
 * **Split** (`src/backend/CLAUDE.md`: a test lives where what it exercises runs): the methods that need Memories in the application at runtime are `UploadWindowMemoriesSliceTest` in `:apps:memories`.
 */
@QuarkusTest
@TestProfile(FakePresignProfile::class)
class UploadWindowSliceTest {

    @Test
    fun startSignsOnlyTheFirstWindowOfABlobLargerThanOne() {
        val (userId, scopeId) = seedScope().let { it.owner to it.scopeId }

        // control first: a blob of exactly one window gets every one of its URLs
        startPuts(userId, scopeId, TestBlob.ofChunks(WINDOW))
            .body("results[0].presignedPuts.size()", equalTo(WINDOW))
        startPuts(userId, scopeId, TestBlob.ofChunks(WINDOW + 1))
            .body("results[0].presignedPuts.size()", equalTo(WINDOW))
            .body("results[0].presignedPuts[${WINDOW - 1}].index", equalTo(WINDOW - 1))
    }

    @Test
    fun aWindowSignsTheNextChunksAndMarksTheSessionLive() {
        val scope = seedScope()
        val blob = TestBlob.ofChunks(WINDOW + 2)
        val uploadId = startPuts(scope.owner, scope.scopeId, blob).extract().path<String>("results[0].uploadId")
        val stale = Instant.now().minus(Duration.ofMinutes(30))
        QuarkusTransaction.requiringNew().run { UploadSessionEntity.findById(uploadId)!!.apply { lastWindowAt = stale }.persist() }

        window(scope.owner, scope.scopeId, uploadId, fromChunk = WINDOW).statusCode(200)
            .body("results[0].error", nullValue())
            .body("results[0].presignedPuts.size()", equalTo(2))
            .body("results[0].presignedPuts[0].index", equalTo(WINDOW))
            .body("results[0].presignedPuts[1].index", equalTo(WINDOW + 1))
        val touched = QuarkusTransaction.requiringNew().call { UploadSessionEntity.findById(uploadId)!!.lastWindowAt }
        assertTrue(touched.isAfter(stale)) { "a window marks the session live for the stale-session purge" }
    }

    @Test
    fun aWindowOfAnotherMembersSessionIsUploadNotFound() {
        val scope = seedScope()
        val other = seedMember(scope)
        val uploadId = startPuts(scope.owner, scope.scopeId, TestBlob.ofChunks(2)).extract().path<String>("results[0].uploadId")

        window(other, scope.scopeId, uploadId, fromChunk = 1).statusCode(200)
            .body("results[0].error", equalTo("upload_not_found"))
            .body("results[0].presignedPuts", nullValue())
        window(scope.owner, scope.scopeId, uploadId, fromChunk = 1).statusCode(200)
            .body("results[0].error", nullValue())
            .body("results[0].presignedPuts.size()", equalTo(1))
    }

    @Test
    fun aRemovedMemberIsRefusedAtTheNextWindow() {
        // ADR 0013 §2: every window is authorized again, so removing a member stops the upload they have in flight
        // at its next window — not only their next upload. Control: the same window, before the removal, is signed.
        val scope = seedScope()
        val member = seedMember(scope)
        val uploadId = startPuts(member, scope.scopeId, TestBlob.ofChunks(3)).extract().path<String>("results[0].uploadId")
        window(member, scope.scopeId, uploadId, fromChunk = 1).statusCode(200).body("results[0].error", nullValue())

        QuarkusTransaction.requiringNew().run { MembershipEntity.delete("userId = ?1 and scopeId = ?2", member, scope.scopeId) }

        window(member, scope.scopeId, uploadId, fromChunk = 2).statusCode(404).body("code", equalTo("scope_not_found"))
    }

    @Test
    fun aWindowFromOutsideTheBlobIsRangeInvalid() {
        val scope = seedScope()
        val blob = TestBlob.ofChunks(3)
        val uploadId = startPuts(scope.owner, scope.scopeId, blob).extract().path<String>("results[0].uploadId")

        window(scope.owner, scope.scopeId, uploadId, fromChunk = blob.chunkCount).statusCode(200)
            .body("results[0].error", equalTo("range_invalid"))
            .body("results[0].presignedPuts", nullValue())
        // control: the last chunk is inside the blob, and its window is that one chunk
        window(scope.owner, scope.scopeId, uploadId, fromChunk = blob.chunkCount - 1).statusCode(200)
            .body("results[0].error", nullValue())
            .body("results[0].presignedPuts.size()", equalTo(1))
            .body("results[0].presignedPuts[0].index", equalTo(blob.chunkCount - 1))
    }

    // --- helpers ---

    private fun startPuts(
        userId: String, scopeId: String, blob: TestBlob,
        ownerId: String = UUID.randomUUID().toString(), role: String = "ORIGINAL",
    ) =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"items":[${blob.declare("d3JhcA==", ownerId = ownerId, role = role)}]}""")
            .`when`().post("/api/scopes/$scopeId/blob-uploads-batch")
            .then().statusCode(200).body("results[0].error", nullValue())

    private fun window(userId: String, scopeId: String, uploadId: String, fromChunk: Int) =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"items":[{"uploadId":"$uploadId","fromChunk":$fromChunk}]}""")
            .`when`().post("/api/scopes/$scopeId/blob-upload-windows")
            .then()

    private fun complete(userId: String, scopeId: String, nodeId: String, uploadId: String) =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"items":[{"nodeId":"$nodeId","mediaId":"${TestBlob.ownerOf(uploadId)}","originalUploadId":"$uploadId"}]}""")
            .`when`().post("/api/scopes/$scopeId/media-batch")
            .then().statusCode(200)

    private data class Scope(val owner: String, val tenantId: String, val scopeId: String)

    private fun seedScope(): Scope = seedMemberScope(ScopeEntity.GROUP).let { Scope(it.userId, it.tenantId, it.scopeId) }

    /** A second accepted member of [scope] — a full writer, just not the opener. */
    private fun seedMember(scope: Scope): String = seedUser().also { join(it, scope.tenantId, scope.scopeId, MembershipEntity.MEMBER) }

    private fun join(userId: String, tenantId: String, scopeId: String, role: String) =
        QuarkusTransaction.requiringNew().run {
            MembershipEntity().apply {
                id = UUID.randomUUID().toString(); this.userId = userId; this.tenantId = tenantId
                this.scopeId = scopeId; this.role = role; wrappedGrant = "Z3JhbnQ="; createdAt = Instant.now()
            }.persist()
            TenantRoots.join(userId, tenantId)
        }

    private companion object {
        /** `veyq.blob.presign-window`'s default — the tests run on it. */
        const val WINDOW = 64
    }
}
