package me.raddatz.veyq.memories.media

import me.raddatz.veyq.core.testing.CoverKit
import me.raddatz.veyq.core.testing.FakePresignProfile
import me.raddatz.veyq.core.testing.TestBlob
import me.raddatz.veyq.core.blob.UploadSessionEntity
import me.raddatz.veyq.core.blobstore.FakePresigningBlobStore
import me.raddatz.veyq.core.membership.MembershipEntity
import me.raddatz.veyq.core.membership.TenantRoots
import me.raddatz.veyq.core.scope.ScopeEntity
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.core.testing.TestFixtures.seedUser
import me.raddatz.veyq.memories.node.MemoriesNodeKinds
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import java.time.Instant
import java.util.UUID
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.notNullValue
import org.hamcrest.CoreMatchers.nullValue
import org.junit.jupiter.api.Test

/**
 * The member half of windowed presigning (uc.memory.upload, ADR 0013 §2): `start` signs only a blob's first window,
 * and a session is bound to the account that opened it — so only its opener can complete it, commit it as a cover,
 * or ask for its next window, and only while still a member.
 *
 * Runs on [FakePresigningBlobStore] (the same profile as [MediaBudgetBranchSliceTest], so the two share one app
 * boot): the in-memory store cannot presign, and a window is nothing but presigned URLs.
 *
 * **Split from `UploadWindowSliceTest`** (`src/backend/CLAUDE.md`: a test lives where what it exercises runs): these methods need Memories in the application at runtime, so they live in
 * `:apps:memories`; the rest of the class is `UploadWindowSliceTest` in `:core`. The bodies moved verbatim.
 */
@QuarkusTest
@TestProfile(FakePresignProfile::class)
class UploadWindowMemoriesSliceTest {

    @Inject
    lateinit var fakeStore: FakePresigningBlobStore

    private data class Scope(val owner: String, val tenantId: String, val scopeId: String)

    private companion object {
        /** `veyq.blob.presign-window`'s default — the tests run on it. */
        const val WINDOW = 64
    }

    @Test
    fun aSecondMemberCannotCompleteAnotherMembersSession() {
        val scope = seedScope()
        val other = seedMember(scope)
        val memory = seedNode(scope.scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
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
        val memory = seedNode(scope.scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        val uploadId = startStaged(scope.owner, scope.scopeId, TestBlob.SMALL, ownerId = memory, role = "COVER")

        commitCover(other, scope.scopeId, memory, uploadId).statusCode(404).body("code", equalTo("node_not_found"))
        commitCover(scope.owner, scope.scopeId, memory, uploadId).statusCode(204)
    }

    @Test
    fun aCompletedSessionHasNoWindow() {
        val scope = seedScope()
        val memory = seedNode(scope.scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
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
            .body("""{"items":[{"nodeId":"$nodeId","mediaId":"${TestBlob.ownerOf(uploadId)}","nodeEpoch":0,"originalUploadId":"$uploadId"}]}""")
            .`when`().post("/api/scopes/$scopeId/media-batch")
            .then().statusCode(200)

    private fun commitCover(userId: String, scopeId: String, nodeId: String, uploadId: String) =
        CoverKit.set(userId, scopeId, nodeId, uploadId, expectedBlobId = null)

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
}
