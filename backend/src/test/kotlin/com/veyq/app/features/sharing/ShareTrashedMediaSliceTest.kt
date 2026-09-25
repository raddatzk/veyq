package com.veyq.app.features.sharing

import com.veyq.app.features.account.login.SessionService
import com.veyq.app.features.media.TestBlob
import com.veyq.app.shared.membership.MembershipEntity
import com.veyq.app.shared.membership.TenantRoots
import com.veyq.app.shared.tenant.TenantEntity
import com.veyq.app.features.content.NodeEntity
import com.veyq.app.features.media.BlobEntity
import com.veyq.app.features.media.MediaEntity
import com.veyq.app.shared.scope.ScopeEntity
import com.veyq.app.shared.identity.VeyqUser
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.hasItem
import org.hamcrest.CoreMatchers.not
import org.hamcrest.CoreMatchers.notNullValue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Slice tests for the **trash ∩ share** interaction (uc.memory.delete-media cr-0110/0111 meets
 * uc.sharing.share-public-viewer cr-0100/ADR 0029): a medium the curator moved to the trash must leave the
 * guest's view on **both** guest paths — the anonymous resolve listing and the gated blob GET. Soft-delete is a
 * `deleted_at` stamp, so a share path that does not filter it keeps serving the medium for the whole retention
 * window; the live path (`MediaStore`) filters `deletedAt is null` on every read.
 *
 * As in [ShareSliceTest], the in-memory store answers `503 store_unavailable` when a blob **is** authorized (the
 * subtree gate passed, no transport) vs `404 share_not_found` when it is not — so the two codes tell "authorized"
 * apart from "not reachable through this share".
 */
@QuarkusTest
class ShareTrashedMediaSliceTest {

    @Inject
    lateinit var sessions: SessionService

    private fun token(userId: String) = "Bearer ${sessions.issue(userId).accessToken}"

    private val wrap = "d3JhcC1rLW1lbS11bmRlci1zaGFyZQ=="
    private val sealedName = "c2VhbGVkLW5hbWU="

    @Test
    fun trashedMediaLeavesTheAnonymousResolve() {
        val (userId, scopeId) = seedMemberScope(ScopeEntity.PERSONAL)
        val memory = seedNode(scopeId, NodeEntity.MEMORY, parent = scopeId)
        val (liveMedia, _) = seedMedia(scopeId, memory)
        val (trashedMedia, _) = seedMedia(scopeId, memory)

        val shareId = createShare(userId, memory)

        // the curator trashes one medium through the real delete path (soft-delete → per-Memory trash)
        given().header("Authorization", token(userId))
            .`when`().delete("/api/scopes/$scopeId/media/$trashedMedia")
            .then().statusCode(204)

        given().`when`().get("/api/shares/$shareId")
            .then().statusCode(200)
            .body("media.mediaId", hasItem(liveMedia))
            .body("media.mediaId", not(hasItem(trashedMedia)))
    }

    @Test
    fun trashedMediaBlobIsNoLongerAuthorizedThroughTheShare() {
        val (userId, scopeId) = seedMemberScope(ScopeEntity.PERSONAL)
        val memory = seedNode(scopeId, NodeEntity.MEMORY, parent = scopeId)
        val (_, liveBlob) = seedMedia(scopeId, memory)
        val (trashedMedia, trashedBlob) = seedMedia(scopeId, memory)

        val shareId = createShare(userId, memory)

        given().header("Authorization", token(userId))
            .`when`().delete("/api/scopes/$scopeId/media/$trashedMedia")
            .then().statusCode(204)

        // the surviving medium's blob stays authorized — the filter must not over-reach
        given().`when`().get("/api/shares/$shareId/blobs/$liveBlob")
            .then().statusCode(503).body("code", equalTo("store_unavailable"))

        // the trashed medium's blob is no longer reachable through the share
        given().`when`().get("/api/shares/$shareId/blobs/$trashedBlob")
            .then().statusCode(404).body("code", equalTo("share_not_found"))
    }

    @Test
    fun restoredMediaComesBackIntoTheShare() {
        val (userId, scopeId) = seedMemberScope(ScopeEntity.PERSONAL)
        val memory = seedNode(scopeId, NodeEntity.MEMORY, parent = scopeId)
        val (mediaId, blobId) = seedMedia(scopeId, memory)

        val shareId = createShare(userId, memory)

        given().header("Authorization", token(userId))
            .`when`().delete("/api/scopes/$scopeId/media/$mediaId").then().statusCode(204)
        given().header("Authorization", token(userId))
            .`when`().post("/api/scopes/$scopeId/media/$mediaId/restore").then().statusCode(204)

        given().`when`().get("/api/shares/$shareId")
            .then().statusCode(200)
            .body("media.mediaId", hasItem(mediaId))
        given().`when`().get("/api/shares/$shareId/blobs/$blobId")
            .then().statusCode(503).body("code", equalTo("store_unavailable"))
    }

    // --- helpers ---

    private fun createShare(userId: String, nodeId: String): String =
        given().header("Authorization", token(userId)).contentType(ContentType.JSON)
            .body("""{"nodeId":"$nodeId","role":"VIEWER","wrap":"$wrap","sealedName":"$sealedName"}""")
            .`when`().post("/api/shares")
            .then().statusCode(201).body("shareId", notNullValue())
            .extract().path("shareId")

    private fun seedNode(scopeId: String, kind: String, parent: String): String {
        val id = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            NodeEntity().apply {
                this.id = id; this.scopeId = scopeId; parentId = parent; this.kind = kind
                encryptedName = "bg=="; slugToken = "c2x1Zy0$id".take(120); orderIndex = 0
                wrappedContentKey = if (kind != NodeEntity.FOLDER) "a21lbQ==" else null
                createdAt = Instant.now()
            }.persist()
        }
        return id
    }

    /** Seed one media (blob + row) attached to a node. Returns (mediaId, blobId). */
    private fun seedMedia(scopeId: String, nodeId: String): Pair<String, String> {
        val mediaId = UUID.randomUUID().toString()
        val blobId = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            BlobEntity().apply {
                id = blobId; this.scopeId = scopeId; storageKey = UUID.randomUUID().toString()
                chunkCount = TestBlob.SMALL.chunkCount; byteSize = TestBlob.SMALL.byteSize; wrappedBlobKey = "Ymxvyi13cmFw"; createdAt = Instant.now()
            }.persist()
            MediaEntity().apply {
                id = mediaId; this.scopeId = scopeId; this.nodeId = nodeId; this.blobId = blobId
                createdAt = Instant.now()
            }.persist()
        }
        return mediaId to blobId
    }

    private fun seedMemberScope(scopeKind: String): Pair<String, String> {
        val userId = seedUser()
        val tenantId = UUID.randomUUID().toString()
        val scopeId = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            TenantEntity().apply {
                id = tenantId; name = "Fam"; domain = "t-$tenantId.example.com"
                ownerEmail = "o-$tenantId@example.com"; status = TenantEntity.ACTIVE; createdAt = Instant.now()
            }.persist()
            ScopeEntity().apply {
                id = scopeId; this.tenantId = tenantId; kind = scopeKind; createdAt = Instant.now()
            }.persist()
            MembershipEntity().apply {
                id = UUID.randomUUID().toString(); this.userId = userId; this.tenantId = tenantId
                this.scopeId = scopeId; role = MembershipEntity.OWNER; wrappedGrant = "Z3JhbnQ="; createdAt = Instant.now()
            }.persist()
            TenantRoots.join(userId, tenantId)
        }
        return userId to scopeId
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
