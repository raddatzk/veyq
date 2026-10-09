package me.raddatz.veyq.core.sharing

import me.raddatz.veyq.core.blob.BlobEntity
import me.raddatz.veyq.memories.media.MediaEntity
import me.raddatz.veyq.core.testing.TestBlob
import me.raddatz.veyq.core.scope.ScopeEntity
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.memories.node.MemoriesNodeKinds
import me.raddatz.veyq.memories.media.ShareMediaRead
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import java.time.Instant
import java.util.UUID
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.hasItem
import org.hamcrest.CoreMatchers.not
import org.hamcrest.CoreMatchers.notNullValue
import org.junit.jupiter.api.Test

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

    private val wrap = "d3JhcC1rLW1lbS11bmRlci1zaGFyZQ=="
    private val sealedName = "c2VhbGVkLW5hbWU="

    @Test
    fun trashedMediaLeavesTheAnonymousResolve() {
        val (userId, scopeId) = seedMemberScope(ScopeEntity.PERSONAL)
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, parentId = scopeId, wrappedContentKey = CONTENT_KEY)
        val (liveMedia, _) = seedMedia(scopeId, memory)
        val (trashedMedia, _) = seedMedia(scopeId, memory)

        val shareId = createShare(userId, memory)

        // the curator trashes one medium through the real delete path (soft-delete → per-Memory trash)
        given().header("Authorization", bearer(userId))
            .`when`().delete("/api/scopes/$scopeId/media/$trashedMedia")
            .then().statusCode(204)

        ShareMediaRead.of(shareId)
            .then().statusCode(200)
            .body("media.mediaId", hasItem(liveMedia))
            .body("media.mediaId", not(hasItem(trashedMedia)))
    }

    @Test
    fun trashedMediaBlobIsNoLongerAuthorizedThroughTheShare() {
        val (userId, scopeId) = seedMemberScope(ScopeEntity.PERSONAL)
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, parentId = scopeId, wrappedContentKey = CONTENT_KEY)
        val (_, liveBlob) = seedMedia(scopeId, memory)
        val (trashedMedia, trashedBlob) = seedMedia(scopeId, memory)

        val shareId = createShare(userId, memory)

        given().header("Authorization", bearer(userId))
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
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, parentId = scopeId, wrappedContentKey = CONTENT_KEY)
        val (mediaId, blobId) = seedMedia(scopeId, memory)

        val shareId = createShare(userId, memory)

        given().header("Authorization", bearer(userId))
            .`when`().delete("/api/scopes/$scopeId/media/$mediaId").then().statusCode(204)
        given().header("Authorization", bearer(userId))
            .`when`().post("/api/scopes/$scopeId/media/$mediaId/restore").then().statusCode(204)

        ShareMediaRead.of(shareId)
            .then().statusCode(200)
            .body("media.mediaId", hasItem(mediaId))
        given().`when`().get("/api/shares/$shareId/blobs/$blobId")
            .then().statusCode(503).body("code", equalTo("store_unavailable"))
    }

    // --- helpers ---

    private fun createShare(userId: String, nodeId: String): String =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{${ShareWriteProof.newShare()},"nodeId":"$nodeId","role":"READ","wrap":"$wrap","sealedName":"$sealedName",${ShareWriteProof.recovery()}}""")
            .`when`().post("/api/shares")
            .then().statusCode(201).body("shareId", notNullValue())
            .extract().path("shareId")

    /** Seed one media (blob + row) attached to a node. Returns (mediaId, blobId). */
    private fun seedMedia(scopeId: String, nodeId: String): Pair<String, String> {
        val mediaId = UUID.randomUUID().toString()
        val blobId = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            BlobEntity().apply {
                id = blobId; this.scopeId = scopeId; storageKey = UUID.randomUUID().toString()
                chunkCount = TestBlob.SMALL.chunkCount; byteSize = TestBlob.SMALL.byteSize; wrappedBlobKey = "Ymxvyi13cmFw"
            }.persist()
            MediaEntity().apply {
                id = mediaId; this.scopeId = scopeId; this.nodeId = nodeId; this.blobId = blobId
                createdAt = Instant.now()
            }.persist()
        }
        return mediaId to blobId
    }
}
