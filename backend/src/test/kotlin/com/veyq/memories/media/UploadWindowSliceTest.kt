package com.veyq.memories.media

import com.veyq.core.blob.UploadSessionEntity
import com.veyq.core.blobstore.FakePresigningBlobStore
import com.veyq.core.membership.MembershipEntity
import com.veyq.core.membership.TenantRoots
import com.veyq.core.scope.ScopeEntity
import com.veyq.core.testing.TestFixtures.CONTENT_KEY
import com.veyq.core.testing.TestFixtures.bearer
import com.veyq.core.testing.TestFixtures.seedMemberScope
import com.veyq.core.testing.TestFixtures.seedNode
import com.veyq.core.testing.TestFixtures.seedUser
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import java.time.Duration
import java.time.Instant
import java.util.UUID
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.notNullValue
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
 */
@QuarkusTest
@TestProfile(MediaBudgetBranchSliceTest.FakePresignProfile::class)
class UploadWindowSliceTest {

    @Inject
    lateinit var fakeStore: FakePresigningBlobStore

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
    fun aSecondMemberCannotCompleteAnotherMembersSession() {
        val scope = seedScope()
        val other = seedMember(scope)
        val memory = seedNode(scope.scopeId, wrappedContentKey = CONTENT_KEY)
        val uploadId = startStaged(scope.owner, scope.scopeId, TestBlob.SMALL)

        complete(other, scope.scopeId, memory, uploadId)
            .body("results[0].error", equalTo("upload_not_found"))
            .body("results[0].mediaId", nullValue())
        // control: the opener completes the very session the other member could not
        complete(scope.owner, scope.scopeId, memory, uploadId)
            .body("results[0].error", nullValue())
            .body("results[0].mediaId", notNullValue())
    }

    @Test
    fun aSecondMemberCannotCommitAnotherMembersSessionAsACover() {
        val scope = seedScope()
        val other = seedMember(scope)
        val memory = seedNode(scope.scopeId, wrappedContentKey = CONTENT_KEY)
        val uploadId = startStaged(scope.owner, scope.scopeId, TestBlob.SMALL, ownerId = memory, role = "COVER")

        commitCover(other, scope.scopeId, memory, uploadId).statusCode(404).body("code", equalTo("node_not_found"))
        commitCover(scope.owner, scope.scopeId, memory, uploadId).statusCode(200)
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

    @Test
    fun aCompletedSessionHasNoWindow() {
        val scope = seedScope()
        val memory = seedNode(scope.scopeId, wrappedContentKey = CONTENT_KEY)
        val uploadId = startStaged(scope.owner, scope.scopeId, TestBlob.SMALL)
        complete(scope.owner, scope.scopeId, memory, uploadId).body("results[0].mediaId", notNullValue())

        window(scope.owner, scope.scopeId, uploadId, fromChunk = 0).statusCode(200)
            .body("results[0].error", equalTo("upload_not_found"))
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

    /** Start [blob] as [userId] and stage every chunk at its derived length — an honest, finished upload. */
    private fun startStaged(
        userId: String, scopeId: String, blob: TestBlob,
        ownerId: String = UUID.randomUUID().toString(), role: String = "ORIGINAL",
    ): String {
        val uploadId = startPuts(userId, scopeId, blob, ownerId, role).extract().path<String>("results[0].uploadId")
        val storageKey = QuarkusTransaction.requiringNew().call { UploadSessionEntity.findById(uploadId)!!.storageKey }
        blob.stage(fakeStore, storageKey)
        return uploadId
    }

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

    private fun commitCover(userId: String, scopeId: String, nodeId: String, uploadId: String) =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"uploadId":"$uploadId"}""")
            .`when`().post("/api/scopes/$scopeId/nodes/$nodeId/cover-blob")
            .then()

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
