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
 * Slice tests for the **node trash ∩ share** interaction (uc.memory.delete-node / ADR 0061 meets
 * uc.sharing.share-public-viewer cr-0100): a subtree the curator deleted must leave the guest's view on **both**
 * guest paths — the anonymous resolve listing and the gated blob GET — and come back on both on restore.
 *
 * This is the sharpest hazard in ADR 0061 and the reason the column is dangerous rather than merely additive.
 * Before the soft-delete the node ROW was gone, so a share on a deleted Memory resolved to nothing by accident.
 * With `deleted_at` set and these two reads unfiltered, the same code serves the **full sealed subtree and
 * presigns every blob under it**, unauthenticated, for the whole retention window — a confidentiality regression
 * introduced by the very column meant to make deletion safer.
 *
 * The two paths are asserted **separately, each with a live control**, because one predicate silently covering
 * for the other is the failure mode this pair exists to prevent: removing either predicate must redden exactly
 * its own case and leave the other's control green. The same cross-feature obligation already shipped broken
 * once for trashed media ([ShareTrashedMediaSliceTest]), which is why it stays a named slice test.
 *
 * As in [ShareSliceTest], the in-memory store answers `503 store_unavailable` when a blob **is** authorized (the
 * subtree gate passed, no transport) vs `404 share_not_found` when it is not.
 */
@QuarkusTest
class ShareDeletedNodeSliceTest {

    @Inject
    lateinit var sessions: SessionService

    private fun token(userId: String) = "Bearer ${sessions.issue(userId).accessToken}"

    private val wrap = "d3JhcC1rLW1lbS11bmRlci1zaGFyZQ=="
    private val sealedName = "c2VhbGVkLW5hbWU="

    @Test
    fun deletedSubtreeLeavesTheAnonymousResolve() {
        val (userId, scopeId) = seedMemberScope()
        val deleted = seedNode(scopeId, NodeEntity.MEMORY, parent = scopeId)
        val survivor = seedNode(scopeId, NodeEntity.MEMORY, parent = scopeId)
        val (deletedMedia, _) = seedMedia(scopeId, deleted)
        val (survivingMedia, _) = seedMedia(scopeId, survivor)

        val deletedShare = createShare(userId, deleted)
        val survivingShare = createShare(userId, survivor)

        given().header("Authorization", token(userId))
            .`when`().delete("/api/scopes/$scopeId/nodes/$deleted")
            .then().statusCode(204)

        // the share on the deleted Memory resolves to NOTHING — no node of the subtree, no media under it
        given().`when`().get("/api/shares/$deletedShare")
            .then().statusCode(200)
            .body("nodes.id", not(hasItem(deleted)))
            .body("media.mediaId", not(hasItem(deletedMedia)))

        // control: the untouched share still serves its subtree — the filter must not over-reach
        given().`when`().get("/api/shares/$survivingShare")
            .then().statusCode(200)
            .body("nodes.id", hasItem(survivor))
            .body("media.mediaId", hasItem(survivingMedia))
    }

    @Test
    fun deletedSubtreesBlobsAreNoLongerAuthorizedThroughTheShare() {
        val (userId, scopeId) = seedMemberScope()
        val deleted = seedNode(scopeId, NodeEntity.MEMORY, parent = scopeId)
        val survivor = seedNode(scopeId, NodeEntity.MEMORY, parent = scopeId)
        val (_, deletedBlob) = seedMedia(scopeId, deleted)
        val (_, survivingBlob) = seedMedia(scopeId, survivor)

        val deletedShare = createShare(userId, deleted)
        val survivingShare = createShare(userId, survivor)

        given().header("Authorization", token(userId))
            .`when`().delete("/api/scopes/$scopeId/nodes/$deleted")
            .then().statusCode(204)

        // the deleted subtree's blob is no longer presignable through its share
        given().`when`().get("/api/shares/$deletedShare/blobs/$deletedBlob")
            .then().statusCode(404).body("code", equalTo("share_not_found"))

        // control: the untouched share still authorizes its own blob
        given().`when`().get("/api/shares/$survivingShare/blobs/$survivingBlob")
            .then().statusCode(503).body("code", equalTo("store_unavailable"))
    }

    /** A deleted CHAPTER must leave a share rooted at its parent Memory, without taking the parent's own media
     *  with it — the in-share control for "hides the subtree" against "hides the share". */
    @Test
    fun deletedChapterLeavesItsParentsShareWithoutTakingTheParentWithIt() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, NodeEntity.MEMORY, parent = scopeId)
        val chapter = seedNode(scopeId, NodeEntity.CHAPTER, parent = memory)
        val (memoryMedia, memoryBlob) = seedMedia(scopeId, memory)
        val (chapterMedia, chapterBlob) = seedMedia(scopeId, chapter)

        val shareId = createShare(userId, memory)

        given().header("Authorization", token(userId))
            .`when`().delete("/api/scopes/$scopeId/nodes/$chapter")
            .then().statusCode(204)

        given().`when`().get("/api/shares/$shareId")
            .then().statusCode(200)
            .body("nodes.id", hasItem(memory))
            .body("nodes.id", not(hasItem(chapter)))
            .body("media.mediaId", hasItem(memoryMedia))
            .body("media.mediaId", not(hasItem(chapterMedia)))

        given().`when`().get("/api/shares/$shareId/blobs/$memoryBlob")
            .then().statusCode(503).body("code", equalTo("store_unavailable"))
        given().`when`().get("/api/shares/$shareId/blobs/$chapterBlob")
            .then().statusCode(404).body("code", equalTo("share_not_found"))
    }

    @Test
    fun restoredSubtreeComesBackOnBothSharePaths() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, NodeEntity.MEMORY, parent = scopeId)
        val (mediaId, blobId) = seedMedia(scopeId, memory)

        val shareId = createShare(userId, memory)

        given().header("Authorization", token(userId))
            .`when`().delete("/api/scopes/$scopeId/nodes/$memory").then().statusCode(204)
        given().header("Authorization", token(userId)).contentType(ContentType.JSON)
            .body("""{"slugToken":"cmVzdG9yZWQtc2x1Zw=="}""")
            .`when`().post("/api/scopes/$scopeId/nodes/$memory/restore").then().statusCode(204)

        given().`when`().get("/api/shares/$shareId")
            .then().statusCode(200)
            .body("nodes.id", hasItem(memory))
            .body("media.mediaId", hasItem(mediaId))
        given().`when`().get("/api/shares/$shareId/blobs/$blobId")
            .then().statusCode(503).body("code", equalTo("store_unavailable"))
    }

    // --- helpers (mirror ShareTrashedMediaSliceTest) ---

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

    private fun seedMemberScope(): Pair<String, String> {
        val userId = seedUser()
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
