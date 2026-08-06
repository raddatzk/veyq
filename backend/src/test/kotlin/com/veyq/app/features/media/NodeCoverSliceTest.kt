package com.veyq.app.features.media

import com.veyq.app.features.account.login.SessionService
import com.veyq.app.shared.membership.MembershipEntity
import com.veyq.app.features.admin.tenant.TenantEntity
import com.veyq.app.features.content.NodeEntity
import com.veyq.app.features.spaces.scope.ScopeEntity
import com.veyq.app.shared.identity.VeyqUser
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.notNullValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Slice tests for the **standalone cover blob** (uc.memory.cover, cr-0073) against the **in-memory store**
 * (dev/test metadata only — no byte transfer; the byte path is the opt-in [BlobBatchE2ETest]). A cover blob is
 * a `veyq_blob` attached to a node via `cover_blob_id` with **no `veyq_media` row** (never in the gallery).
 * Covers: commit → the blob is download-authorized (proving the row + authz) with no media row; replace
 * reclaims the prior blob; `deleteCoverBlob` + `deleteNode` reclaim it; a FOLDER is `illegal_target`; unknown
 * node/upload + non-member are 404 and a cross-scope upload is 404; operator-blindness (the node row holds only
 * `cover_blob_id` + size — the wrap stays on the blob, the crop never reaches the server).
 *
 * With the in-memory store a committed blob's download presign answers the per-item code `store_unavailable`
 * (the store has no transport) while an unknown/reclaimed blob answers `upload_not_found` — so the two codes
 * distinguish "blob exists + authz passes" from "blob is gone", which is what these hermetic tests assert.
 */
@QuarkusTest
class NodeCoverSliceTest {

    @Inject
    lateinit var sessions: SessionService

    private fun token(userId: String) = "Bearer ${sessions.issue(userId).accessToken}"

    @Test
    fun commitAttachesCoverBlobWithNoMediaRow() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, NodeEntity.MEMORY)
        val uploadId = startOne(userId, scopeId, chunkCount = 1, byteSize = 4096, wrap = "Y292ZXItd3JhcA==")

        // commit the upload as the node's standalone cover — the ref echoes the declared shape + wrap verbatim
        val blobId = given().header("Authorization", token(userId)).contentType(ContentType.JSON)
            .body("""{"uploadId":"$uploadId"}""")
            .`when`().post("/api/scopes/$scopeId/nodes/$memory/cover-blob")
            .then().statusCode(200)
            .body("blobId", notNullValue())
            .body("chunkCount", equalTo(1))
            .body("byteSize", equalTo(4096))
            .body("wrappedBlobKey", equalTo("Y292ZXItd3JhcA==")) // operator-blind: the K_blob wrap round-trips
            .extract().path<String>("blobId")

        // NO media row — a cover blob never appears in the gallery list; and cr-0107: a cover change bumps
        // NEITHER counter (it writes no veyq_media row + never lists), so a cover swap must not force a re-list.
        given().header("Authorization", token(userId))
            .`when`().get("/api/scopes/$scopeId/nodes/$memory/media")
            .then().statusCode(200)
            .body("media.size()", equalTo(0))
            .body("mediaRevision", equalTo(0))
            .body("deleteRevision", equalTo(0))

        // the committed blob is download-authorized (store_unavailable, not upload_not_found → the row + authz exist)
        assertEquals("store_unavailable", downloadError(userId, scopeId, blobId))

        // operator-blind: the node row holds only cover_blob_id + size — never the wrap (it lives on the blob) or the crop
        QuarkusTransaction.requiringNew().run {
            val node = NodeEntity.findById(memory)!!
            assertEquals(blobId, node.coverBlobId)
            assertEquals(4096L, node.coverByteSize)
        }
    }

    @Test
    fun replaceReclaimsPriorCoverBlob() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, NodeEntity.MEMORY)
        val first = commitCover(userId, scopeId, memory, startOne(userId, scopeId, 1, 16, "Zmlyc3Q="))
        val second = commitCover(userId, scopeId, memory, startOne(userId, scopeId, 2, 32, "c2Vjb25k"))

        // the superseded first blob is reclaimed (gone); the new one is authorized
        assertEquals("upload_not_found", downloadError(userId, scopeId, first))
        assertEquals("store_unavailable", downloadError(userId, scopeId, second))
        QuarkusTransaction.requiringNew().run {
            assertEquals(second, NodeEntity.findById(memory)!!.coverBlobId)
        }
    }

    @Test
    fun deleteCoverBlobDetachesAndReclaims() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, NodeEntity.MEMORY)
        val blobId = commitCover(userId, scopeId, memory, startOne(userId, scopeId, 1, 16, "ZGVs"))

        given().header("Authorization", token(userId))
            .`when`().delete("/api/scopes/$scopeId/nodes/$memory/cover-blob")
            .then().statusCode(204)

        assertEquals("upload_not_found", downloadError(userId, scopeId, blobId)) // reclaimed
        QuarkusTransaction.requiringNew().run {
            val node = NodeEntity.findById(memory)!!
            assertNull(node.coverBlobId)
            assertNull(node.coverByteSize)
        }

        // idempotent — deleting again (no cover blob) still answers 204
        given().header("Authorization", token(userId))
            .`when`().delete("/api/scopes/$scopeId/nodes/$memory/cover-blob")
            .then().statusCode(204)
    }

    @Test
    fun deleteNodeReclaimsCoverBlob() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, NodeEntity.MEMORY)
        val blobId = commitCover(userId, scopeId, memory, startOne(userId, scopeId, 1, 16, "bm9kZQ=="))

        // deleting the node cascades to its cover blob (the cover_blob_id column is the only reference)
        given().header("Authorization", token(userId))
            .`when`().delete("/api/scopes/$scopeId/nodes/$memory")
            .then().statusCode(204)

        assertEquals("upload_not_found", downloadError(userId, scopeId, blobId))
    }

    @Test
    fun coverOnFolderIsIllegalTarget() {
        val (userId, scopeId) = seedMemberScope()
        val folder = seedNode(scopeId, NodeEntity.FOLDER)
        val uploadId = startOne(userId, scopeId, 1, 16, "Zm9sZA==")

        given().header("Authorization", token(userId)).contentType(ContentType.JSON)
            .body("""{"uploadId":"$uploadId"}""")
            .`when`().post("/api/scopes/$scopeId/nodes/$folder/cover-blob")
            .then().statusCode(422).body("code", equalTo("illegal_target"))
    }

    @Test
    fun commitUnknownUploadIs404() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, NodeEntity.MEMORY)
        given().header("Authorization", token(userId)).contentType(ContentType.JSON)
            .body("""{"uploadId":"${UUID.randomUUID()}"}""")
            .`when`().post("/api/scopes/$scopeId/nodes/$memory/cover-blob")
            .then().statusCode(404).body("code", equalTo("node_not_found"))
    }

    @Test
    fun commitUnknownNodeIs404() {
        val (userId, scopeId) = seedMemberScope()
        val uploadId = startOne(userId, scopeId, 1, 16, "bm9wZQ==")
        given().header("Authorization", token(userId)).contentType(ContentType.JSON)
            .body("""{"uploadId":"$uploadId"}""")
            .`when`().post("/api/scopes/$scopeId/nodes/${UUID.randomUUID()}/cover-blob")
            .then().statusCode(404).body("code", equalTo("node_not_found"))
    }

    @Test
    fun crossScopeUploadIs404() {
        val (userId, scopeA) = seedMemberScope()
        val scopeB = seedScopeFor(userId) // the same user is a member of a second scope
        val memoryB = seedNode(scopeB, NodeEntity.MEMORY)
        val uploadInA = startOne(userId, scopeA, 1, 16, "eA==")

        // committing scope A's upload against a scope B node — the session isn't in B → 404 (no cross-scope reach)
        given().header("Authorization", token(userId)).contentType(ContentType.JSON)
            .body("""{"uploadId":"$uploadInA"}""")
            .`when`().post("/api/scopes/$scopeB/nodes/$memoryB/cover-blob")
            .then().statusCode(404).body("code", equalTo("node_not_found"))
    }

    @Test
    fun nonMemberIs404() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, NodeEntity.MEMORY)
        val uploadId = startOne(userId, scopeId, 1, 16, "b3V0")
        val outsider = seedUser()

        given().header("Authorization", token(outsider)).contentType(ContentType.JSON)
            .body("""{"uploadId":"$uploadId"}""")
            .`when`().post("/api/scopes/$scopeId/nodes/$memory/cover-blob")
            .then().statusCode(404).body("code", equalTo("node_not_found"))
    }

    @Test
    fun unauthenticatedIs401() {
        given().contentType(ContentType.JSON)
            .body("""{"uploadId":"${UUID.randomUUID()}"}""")
            .`when`().post("/api/scopes/${UUID.randomUUID()}/nodes/${UUID.randomUUID()}/cover-blob")
            .then().statusCode(401).body("code", equalTo("authentication_failed"))
    }

    // --- helpers ---

    private fun startOne(userId: String, scopeId: String, chunkCount: Int, byteSize: Long, wrap: String): String =
        given().header("Authorization", token(userId)).contentType(ContentType.JSON)
            .body("""{"items":[{"chunkCount":$chunkCount,"byteSize":$byteSize,"wrappedBlobKey":"$wrap"}]}""")
            .`when`().post("/api/scopes/$scopeId/blob-uploads-batch")
            .then().statusCode(200).body("results[0].uploadId", notNullValue())
            .extract().path("results[0].uploadId")

    /** Commit an upload as the node's cover and return the committed blob id. */
    private fun commitCover(userId: String, scopeId: String, nodeId: String, uploadId: String): String =
        given().header("Authorization", token(userId)).contentType(ContentType.JSON)
            .body("""{"uploadId":"$uploadId"}""")
            .`when`().post("/api/scopes/$scopeId/nodes/$nodeId/cover-blob")
            .then().statusCode(200).body("blobId", notNullValue())
            .extract().path("blobId")

    /** The per-item presign-download error for a blob: `store_unavailable` (exists) vs `upload_not_found` (gone). */
    private fun downloadError(userId: String, scopeId: String, blobId: String): String? =
        given().header("Authorization", token(userId)).contentType(ContentType.JSON)
            .body("""{"items":[{"blobId":"$blobId"}]}""")
            .`when`().post("/api/scopes/$scopeId/blob-downloads-batch")
            .then().statusCode(200)
            .extract().path("results[0].error")

    private fun seedNode(scopeId: String, kind: String): String {
        val id = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            NodeEntity().apply {
                this.id = id; this.scopeId = scopeId; parentId = scopeId; this.kind = kind
                encryptedName = "bg=="; slugToken = "c2x1Zy0$id".take(120); orderIndex = 0
                wrappedContentKey = if (kind == NodeEntity.MEMORY) "a21lbQ==" else null; createdAt = Instant.now()
            }.persist()
        }
        return id
    }

    private fun seedMemberScope(): Pair<String, String> {
        val userId = seedUser()
        return userId to seedScopeFor(userId)
    }

    /** Seed an ACTIVE tenant + a PERSONAL scope the given user is a member of. Returns the scope id. */
    private fun seedScopeFor(userId: String): String {
        val tenantId = UUID.randomUUID().toString()
        val scopeId = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            TenantEntity().apply {
                id = tenantId; name = "Fam"; domain = "t-$tenantId.example.com"
                ownerEmail = "o-$tenantId@example.com"; status = TenantEntity.ACTIVE; createdAt = Instant.now()
            }.persist()
            ScopeEntity().apply {
                id = scopeId; this.tenantId = tenantId; kind = ScopeEntity.PERSONAL; createdAt = Instant.now()
            }.persist()
            MembershipEntity().apply {
                id = UUID.randomUUID().toString(); this.userId = userId; this.tenantId = tenantId
                this.scopeId = scopeId; role = MembershipEntity.OWNER; wrappedGrant = "Z3JhbnQ="; createdAt = Instant.now()
            }.persist()
        }
        return scopeId
    }

    private fun seedUser(): String {
        val userId = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            VeyqUser().apply {
                id = userId; email = "u-$userId@example.com"; serverRole = "MEMBER"
                suiteId = "v1"; identityPublicKey = "aWQ="; wrappedIdentityPrivateKey = "aXA="
                authPublicKey = "YXA="; createdAt = Instant.now()
            }.persist()
        }
        return userId
    }
}
