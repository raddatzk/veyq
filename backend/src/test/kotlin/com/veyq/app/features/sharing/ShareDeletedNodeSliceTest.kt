package com.veyq.app.features.sharing

import com.veyq.app.core.blob.BlobEntity
import com.veyq.app.core.sharing.ShareEntity
import com.veyq.app.core.sharing.ShareIdentityEntity
import com.veyq.app.features.content.NodeEntity
import com.veyq.app.features.content.NodeSubtreePurge
import com.veyq.app.features.media.BucketEntity
import com.veyq.app.features.media.MediaEntity
import com.veyq.app.features.media.TestBlob
import com.veyq.app.shared.TestIds
import com.veyq.app.shared.config.VeyqConfig
import com.veyq.app.testing.TestFixtures.CONTENT_KEY
import com.veyq.app.testing.TestFixtures.WRAPPED_UPLOADER_KEY
import com.veyq.app.testing.TestFixtures.bearer
import com.veyq.app.testing.TestFixtures.identityKeyJson
import com.veyq.app.testing.TestFixtures.seedMemberScope
import com.veyq.app.testing.TestFixtures.seedNode
import com.veyq.app.testing.TestFixtures.shareKeysJson
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import java.time.Instant
import java.util.UUID
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.hasItem
import org.hamcrest.CoreMatchers.not
import org.hamcrest.CoreMatchers.notNullValue
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

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
    lateinit var purge: NodeSubtreePurge

    @Inject
    lateinit var config: VeyqConfig

    private val wrap = "d3JhcC1rLW1lbS11bmRlci1zaGFyZQ=="
    private val sealedName = "c2VhbGVkLW5hbWU="
    private val sealedNote = "c2VhbGVkLW5vdGU="
    private val shareKey = "cmVjb3ZlcmFibGUtc2hhcmUta2V5"
    private val guestName = "c2VhbGVkLWd1ZXN0LW5hbWU="

    @Test
    fun deletedSubtreeLeavesTheAnonymousResolve() {
        val (userId, scopeId) = seedMemberScope()
        val deleted = seedNode(scopeId, NodeEntity.MEMORY, parentId = scopeId, wrappedContentKey = CONTENT_KEY)
        val survivor = seedNode(scopeId, NodeEntity.MEMORY, parentId = scopeId, wrappedContentKey = CONTENT_KEY)
        val (deletedMedia, _) = seedMedia(scopeId, deleted)
        val (survivingMedia, _) = seedMedia(scopeId, survivor)

        val deletedShare = createShare(userId, deleted)
        val survivingShare = createShare(userId, survivor)

        given().header("Authorization", bearer(userId))
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
        val deleted = seedNode(scopeId, NodeEntity.MEMORY, parentId = scopeId, wrappedContentKey = CONTENT_KEY)
        val survivor = seedNode(scopeId, NodeEntity.MEMORY, parentId = scopeId, wrappedContentKey = CONTENT_KEY)
        val (_, deletedBlob) = seedMedia(scopeId, deleted)
        val (_, survivingBlob) = seedMedia(scopeId, survivor)

        val deletedShare = createShare(userId, deleted)
        val survivingShare = createShare(userId, survivor)

        given().header("Authorization", bearer(userId))
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
        val memory = seedNode(scopeId, NodeEntity.MEMORY, parentId = scopeId, wrappedContentKey = CONTENT_KEY)
        val chapter = seedNode(scopeId, NodeEntity.CHAPTER, parentId = memory, wrappedContentKey = CONTENT_KEY)
        val (memoryMedia, memoryBlob) = seedMedia(scopeId, memory)
        val (chapterMedia, chapterBlob) = seedMedia(scopeId, chapter)

        val shareId = createShare(userId, memory)

        given().header("Authorization", bearer(userId))
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
        val memory = seedNode(scopeId, NodeEntity.MEMORY, parentId = scopeId, wrappedContentKey = CONTENT_KEY)
        val (mediaId, blobId) = seedMedia(scopeId, memory)

        val shareId = createShare(userId, memory)

        given().header("Authorization", bearer(userId))
            .`when`().delete("/api/scopes/$scopeId/nodes/$memory").then().statusCode(204)
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"slugToken":"cmVzdG9yZWQtc2x1Zw=="}""")
            .`when`().post("/api/scopes/$scopeId/nodes/$memory/restore").then().statusCode(204)

        given().`when`().get("/api/shares/$shareId")
            .then().statusCode(200)
            .body("nodes.id", hasItem(memory))
            .body("media.mediaId", hasItem(mediaId))
        given().`when`().get("/api/shares/$shareId/blobs/$blobId")
            .then().statusCode(503).body("code", equalTo("store_unavailable"))
    }

    // --- the purge (ADR 0029 §7): erasing a Memory deletes its links ---

    /**
     * B1 — a trashed Memory's link answers `200` with nothing in it and comes back on restore (above); once the purge
     * ERASES the Memory there is nothing to come back to, and the link must end. Before the tombstone it kept
     * answering `200` with the Memory's sealed title forever, listed nowhere, so nobody could revoke it.
     */
    @Test
    fun erasingAMemoryEndsItsLink() {
        val (userId, scopeId) = seedMemberScope()
        val erased = seedNode(scopeId, NodeEntity.MEMORY, parentId = scopeId, wrappedContentKey = CONTENT_KEY)
        val sibling = seedNode(scopeId, NodeEntity.MEMORY, parentId = scopeId, wrappedContentKey = CONTENT_KEY)
        val withCopy = ""","sealedNote":"$sealedNote","sealedShareKey":"$shareKey","sealedShareKeyEpoch":0"""
        val erasedShare = createShare(userId, erased, extra = withCopy)
        val siblingShare = createShare(userId, sibling, extra = withCopy)

        trashAndPurge(userId, scopeId, erased)

        // control, asserted first so a bite on the purge shows it still green: a link on a Memory the same purge
        // did not erase keeps serving
        given().`when`().get("/api/shares/$siblingShare").then().statusCode(200)

        given().`when`().get("/api/shares/$erasedShare")
            .then().statusCode(404).body("code", equalTo("share_not_found"))
        val row = QuarkusTransaction.requiringNew().call { ShareEntity.findById(erasedShare) }
        assertNotNull(row) { "a tombstone, not a row delete: the id stays held" }
        assertAll(
            { assertNull(row!!.sealedName) },
            { assertNull(row!!.sealedShareKey) },
            { assertNull(row!!.sealedNote) },
            { assertNotNull(row!!.deletedAt) },
        )
    }

    /**
     * B6 (inbox #229) — a guest's identity row (sealed name, wrapped key, live token) goes once its link is deleted
     * AND no media row and no bucket references it; never earlier, since the wrapped key is the only way anyone
     * opens an uploader guest's photos and the bucket shows their name.
     */
    @Test
    fun aDeadGuestIdentityGoesAndALiveOneStays() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, NodeEntity.MEMORY, parentId = scopeId, wrappedContentKey = CONTENT_KEY)
        val uploader = createShare(userId, memory, role = "UPLOADER")
        val (g1, _) = createIdentity(uploader) // names themself, never uploads
        val (g2, g2Token) = createIdentity(uploader)
        val m = upload(uploader, g2Token)
        val (g3, g3Token) = createIdentity(uploader)
        val m3 = upload(uploader, g3Token)
        given().header("X-Contributor-Token", g3Token).`when`().delete("/api/shares/$uploader/media/$m3").then().statusCode(204)
        // G5: a media row with no bucket naming them, seeded — no upload makes one, since every upload through the
        // link is born with its primary bucket, which would hold G2 on its own; this row is what asserts that the
        // MEDIA reference holds an identity by itself.
        val (g5, _) = createIdentity(uploader)
        seedMedia(scopeId, memory, identityId = g5)

        // the control for the share condition: a LIVE link whose guest's uploads are all erased keeps the guest —
        // V is live and G4's token is still a session. A guest upload always lands on the share's root, so the
        // chapter's media and bucket are seeded.
        val other = seedNode(scopeId, NodeEntity.MEMORY, parentId = scopeId, wrappedContentKey = CONTENT_KEY)
        val chapter = seedNode(scopeId, NodeEntity.CHAPTER, parentId = other, wrappedContentKey = CONTENT_KEY)
        val live = createShare(userId, other, role = "UPLOADER")
        val (g4, _) = createIdentity(live)
        val (m4, _) = seedMedia(scopeId, chapter, identityId = g4)
        seedBucket(scopeId, chapter, "share:$g4")

        // Action 1: delete U
        given().header("Authorization", bearer(userId)).`when`().delete("/api/shares/$uploader").then().statusCode(204)
        QuarkusTransaction.requiringNew().run {
            assertNull(ShareIdentityEntity.findById(g1), "G1 named themself and left nothing: gone with the link")
            assertEquals(WRAPPED_UPLOADER_KEY, ShareIdentityEntity.findById(g2)?.wrappedIdentityKey, "G2's photo opens only through it")
            assertNotNull(ShareIdentityEntity.findById(g3), "G3's empty bucket still shows their name")
            assertNotNull(ShareIdentityEntity.findById(g5), "G5's photo, with no bucket naming them, still needs their key")
        }
        given().header("Authorization", bearer(userId)).`when`().get("/api/scopes/$scopeId/nodes/$memory/media")
            .then().statusCode(200).body("uploaderKeys.find { it.identityId == '$g2' }.wrappedIdentityKey", equalTo(WRAPPED_UPLOADER_KEY))

        // Action 2: erase the Memory that held their uploads
        trashAndPurge(userId, scopeId, memory)
        QuarkusTransaction.requiringNew().run {
            assertNull(MediaEntity.findById(m), "the purge erased G2's photo")
            assertNull(ShareIdentityEntity.findById(g2), "nothing of G2's remains: gone")
            assertNull(ShareIdentityEntity.findById(g3), "G3's bucket went with the Memory: gone")
            assertNull(ShareIdentityEntity.findById(g5), "G5's photo went with the Memory: gone")
        }

        // the control: erase the chapter (not the Memory) — G4's media and bucket go, G4 stays
        trashAndPurge(userId, scopeId, chapter)
        QuarkusTransaction.requiringNew().run {
            assertNull(MediaEntity.findById(m4), "the control's purge ran")
            assertEquals(0L, BucketEntity.count("ownerKey", "share:$g4"), "the control's purge ran")
            assertNotNull(ShareIdentityEntity.findById(g4), "a live link keeps its guest")
        }
    }

    // --- helpers (mirror ShareTrashedMediaSliceTest) ---

    private fun createShare(userId: String, nodeId: String, role: String = "VIEWER", extra: String = ""): String =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{${ShareWriteProof.newShare()},"nodeId":"$nodeId","role":"$role",${shareKeysJson(role, nodeId, wrap)},"sealedName":"$sealedName"$extra}""")
            .`when`().post("/api/shares")
            .then().statusCode(201).body("shareId", notNullValue())
            .extract().path("shareId")

    /** Delete [nodeId], age every trashed node of the scope past the retention window, run the purge (as
     *  `NodeTrashSliceTest` drives it). */
    private fun trashAndPurge(userId: String, scopeId: String, nodeId: String) {
        given().header("Authorization", bearer(userId)).`when`().delete("/api/scopes/$scopeId/nodes/$nodeId").then().statusCode(204)
        QuarkusTransaction.requiringNew().run {
            val stale = Instant.now().minus(config.node().trash().retention()).minusSeconds(60)
            NodeEntity.find("scopeId = ?1 and deletedAt is not null", scopeId).list().forEach { it.deletedAt = stale }
        }
        purge.run()
    }

    /** A guest identity on an UPLOADER share → (identityId, contributorToken). */
    private fun createIdentity(shareId: String): Pair<String, String> {
        val r = given().contentType(ContentType.JSON)
            .body("""{"identityId":"${TestIds.uuid()}","sealedDisplayName":"$guestName"${identityKeyJson("UPLOADER")}}""")
            .header(ShareWriteProof.HEADER, ShareWriteProof.token(shareId)).`when`().post("/api/shares/$shareId/identities")
            .then().statusCode(201).extract()
        return r.path<String>("identityId") to r.path<String>("contributorToken")
    }

    /** One guest upload through the link (start + complete) → the media id; the guest's primary bucket is born with it. */
    private fun upload(shareId: String, token: String): String {
        val uploadId = given().contentType(ContentType.JSON)
            .body("""{"contributorToken":"$token","items":[${TestBlob.SMALL.declare("Ymxvyi13cmFw")}]}""")
            .`when`().post("/api/shares/$shareId/blob-uploads")
            .then().statusCode(200).extract().path<String>("results[0].uploadId")
        return given().contentType(ContentType.JSON)
            .body("""{"contributorToken":"$token","items":[{"mediaId":"${TestBlob.ownerOf(uploadId)}","originalUploadId":"$uploadId"}]}""")
            .`when`().post("/api/shares/$shareId/media")
            .then().statusCode(200).extract().path<String>("results[0].mediaId")
    }

    private fun seedBucket(scopeId: String, nodeId: String, ownerKey: String) {
        QuarkusTransaction.requiringNew().run {
            BucketEntity().apply {
                id = UUID.randomUUID().toString(); this.scopeId = scopeId; this.nodeId = nodeId; this.ownerKey = ownerKey
                isPrimary = true; createdAt = Instant.now()
            }.persist()
        }
    }

    private fun seedMedia(scopeId: String, nodeId: String, identityId: String? = null): Pair<String, String> {
        val mediaId = UUID.randomUUID().toString()
        val blobId = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            BlobEntity().apply {
                id = blobId; this.scopeId = scopeId; storageKey = UUID.randomUUID().toString()
                chunkCount = TestBlob.SMALL.chunkCount; byteSize = TestBlob.SMALL.byteSize; wrappedBlobKey = "Ymxvyi13cmFw"; createdAt = Instant.now()
            }.persist()
            MediaEntity().apply {
                id = mediaId; this.scopeId = scopeId; this.nodeId = nodeId; this.blobId = blobId
                shareIdentityId = identityId; createdAt = Instant.now()
            }.persist()
        }
        return mediaId to blobId
    }
}
