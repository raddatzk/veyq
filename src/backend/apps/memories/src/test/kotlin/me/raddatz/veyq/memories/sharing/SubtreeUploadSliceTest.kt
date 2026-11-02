package me.raddatz.veyq.memories.sharing

import me.raddatz.veyq.core.blob.BlobEntity
import me.raddatz.veyq.core.blob.UploadSessionEntity
import me.raddatz.veyq.core.blobstore.FakePresigningBlobStore
import me.raddatz.veyq.core.content.DeleteNodeOutcome
import me.raddatz.veyq.core.content.NodesHandler
import me.raddatz.veyq.core.content.UpdateNodeOutcome
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.tenant.TenantEntity
import me.raddatz.veyq.core.testing.FakePresignProfile
import me.raddatz.veyq.core.testing.TestBlob
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.placementTo
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.memories.media.MediaEntity
import me.raddatz.veyq.memories.media.ShareMediaRead
import me.raddatz.veyq.memories.node.MemoriesNodeKinds
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.ValidatableResponse
import jakarta.inject.Inject
import java.time.Instant
import java.util.UUID
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.notNullValue
import org.hamcrest.CoreMatchers.nullValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import me.raddatz.veyq.core.sharing.GuestTreeShare
import me.raddatz.veyq.core.sharing.ShareEntity
import me.raddatz.veyq.core.sharing.ShareIdentityEntity
import me.raddatz.veyq.memories.testing.MediaBlobRows

/**
 * A link's role applies to every node below its root as the application allows it (ADR 0080 decision 6;
 * uc.sharing.share-contributor:rights-inherit-below-a-folder): a guest's upload names an optional target `nodeId` — on
 * the start, every window and the completion — and the writer, the per-link measure and the completion are picked by
 * THAT node's kind.
 * A `READ_WRITE` folder link's guest uploads into a Memory or chapter below the folder (never into a folder), a
 * `READ_WRITE` Memory link's guest into a chapter below the Memory; a `WRITE_ONLY` guest holds no key below its root and
 * names nothing but the root. No `nodeId` is the root, as before (the existing share-media suites, unchanged).
 *
 * [FakePresignProfile]: a guest complete verifies chunks the test stages at their exact lengths.
 */
@QuarkusTest
@TestProfile(FakePresignProfile::class)
class SubtreeUploadSliceTest {

    @Inject lateinit var fakeStore: FakePresigningBlobStore
    @Inject lateinit var nodes: NodesHandler

    private val s = TestBlob.SMALL

    /** `folder` (the share root) holds `memory` (with `chapter`), `sub` (a folder) and `odd` (a kind nobody takes
     *  uploads into); `outside` is a Memory beside the folder at the scope root. */
    private data class Tree(
        val userId: String, val scopeId: String, val tenantId: String, val folder: String, val memory: String,
        val chapter: String, val sub: String, val odd: String, val outside: String,
    )

    private fun tree(): Tree {
        val (userId, scopeId, tenantId) = seedMemberScope()
        val folder = seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, parentId = folder, wrappedContentKey = CONTENT_KEY)
        val chapter = seedNode(scopeId, MemoriesNodeKinds.CHAPTER, parentId = memory, wrappedContentKey = CONTENT_KEY)
        val sub = seedNode(scopeId, NodeEntity.FOLDER, parentId = folder, wrappedContentKey = CONTENT_KEY)
        val odd = seedNode(scopeId, "EXPENSE", parentId = folder, wrappedContentKey = CONTENT_KEY)
        val outside = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        return Tree(userId, scopeId, tenantId, folder, memory, chapter, sub, odd, outside)
    }

    /**
     * A folder contributor uploads into a Memory below the folder and into a chapter below that Memory — start, window
     * and complete each naming the target — and each medium lands on its target, stamped with the guest's identity. A
     * member opens it on that node's media page and buckets; a guest read of the folder serves both rows without the
     * contributor's identity (ADR 0030 point 8).
     */
    @Test
    fun aFolderContributorUploadsIntoAMemoryAndAChapterBelow() {
        val t = tree()
        val link = GuestTreeShare.seed(t.userId, t.scopeId, t.folder)
        val identityId = identityOf(link)

        val intoMemory = upload(link, t.memory)
        val intoChapter = upload(link, t.chapter)
        QuarkusTransaction.requiringNew().run {
            assertEquals(t.memory, MediaEntity.findById(intoMemory)!!.nodeId, "the medium lands on the Memory it names")
            assertEquals(t.chapter, MediaEntity.findById(intoChapter)!!.nodeId, "and on the chapter it names")
            assertEquals(identityId, MediaEntity.findById(intoChapter)!!.shareIdentityId)
        }
        val auth = bearer(t.userId)
        given().header("Authorization", auth).`when`().get("/api/scopes/${t.scopeId}/nodes/${t.memory}/media").then().statusCode(200)
            .body("media.size()", equalTo(1)).body("media[0].mediaId", equalTo(intoMemory))
        given().header("Authorization", auth).`when`().get("/api/scopes/${t.scopeId}/nodes/${t.chapter}/media").then().statusCode(200)
            .body("media.size()", equalTo(1)).body("media[0].mediaId", equalTo(intoChapter))
        given().header("Authorization", auth).`when`().get("/api/scopes/${t.scopeId}/nodes/${t.chapter}/buckets").then().statusCode(200)
            .body("buckets.find { it.ownerKey == 'share:$identityId' }", notNullValue())

        ShareMediaRead.of(link.shareId).then().statusCode(200)
            .body("media.size()", equalTo(2))
            .body("media.find { it.mediaId == '$intoChapter' }.nodeId", equalTo(t.chapter))
            .body("media.find { it.mediaId == '$intoChapter' }.identityId", nullValue())
            .body("media.find { it.mediaId == '$intoMemory' }.identityId", nullValue())
            .body("identities.size()", equalTo(0))
    }

    /**
     * The folder guest deletes its own medium below the folder and never a member's (the existing ownership 404); and
     * once a member moved the Memory out of the folder, its own medium there is no longer the link's to delete.
     */
    @Test
    fun aFolderContributorDeletesOnlyItsOwnAndOnlyInsideTheSubtree() {
        val t = tree()
        val link = GuestTreeShare.seed(t.userId, t.scopeId, t.folder)
        val members = seedMedia(t.scopeId, t.memory)
        val own = upload(link, t.memory)
        val movedAway = upload(link, t.chapter)

        deleteOwn(link, members).statusCode(404).body("code", equalTo("share_media_not_found"))
        deleteOwn(link, own).statusCode(204)

        val away = seedNode(t.scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        assertEquals(UpdateNodeOutcome.Updated, nodes.update(t.userId, t.scopeId, t.memory, placementTo(away), null, null, null, null))
        deleteOwn(link, movedAway).statusCode(404).body("code", equalTo("share_media_not_found"))
        QuarkusTransaction.requiringNew().run {
            assertNull(MediaEntity.findById(own), "the guest's own medium is gone")
            assertNotNull(MediaEntity.findById(members), "a member's stays")
            assertNotNull(MediaEntity.findById(movedAway), "one moved out of the subtree stays")
        }
    }

    /**
     * A target the link does not reach: a node outside the subtree, an unknown id and a trashed Memory are the same
     * `node_not_found` (nothing about what lies outside); a folder — the root itself included — and a kind no
     * application takes uploads into are `share_not_contributable`. Every one refused on the start, the window and the
     * completion, and no medium lands; the control is the Memory inside.
     */
    @Test
    fun aTargetTheLinkDoesNotReachIsRefused() {
        val t = tree()
        val link = GuestTreeShare.seed(t.userId, t.scopeId, t.folder)
        val uploadId = start(link, t.memory).statusCode(200).body("results[0].error", nullValue()).extract().path<String>("results[0].uploadId")
        stage(uploadId)
        val trashed = seedNode(t.scopeId, MemoriesNodeKinds.MEMORY, parentId = t.folder, wrappedContentKey = CONTENT_KEY)
        assertEquals(DeleteNodeOutcome.Deleted, nodes.delete(t.userId, t.scopeId, trashed))

        for (target in listOf(t.outside, UUID.randomUUID().toString(), trashed)) {
            start(link, target).statusCode(404).body("code", equalTo("node_not_found"))
            window(link, target, uploadId).statusCode(404).body("code", equalTo("node_not_found"))
            complete(link, target, uploadId).statusCode(404).body("code", equalTo("node_not_found"))
        }
        for (target in listOf(t.folder, t.sub, t.odd, null)) {
            start(link, target).statusCode(403).body("code", equalTo("share_not_contributable"))
            window(link, target, uploadId).statusCode(403).body("code", equalTo("share_not_contributable"))
            complete(link, target, uploadId).statusCode(403).body("code", equalTo("share_not_contributable"))
        }
        QuarkusTransaction.requiringNew().run { assertEquals(0L, MediaEntity.count("scopeId", t.scopeId), "nothing landed") }
        complete(link, t.memory, uploadId).statusCode(200).body("results[0].error", nullValue()) // control
    }

    /** A `READ` link takes no upload anywhere below its root: the gate refuses it before any target is read. */
    @Test
    fun aReadGuestIsRefusedForEveryTarget() {
        val t = tree()
        val link = GuestTreeShare.seed(t.userId, t.scopeId, t.folder, role = ShareEntity.READ)
        for (target in listOf(t.memory, t.chapter, null)) {
            start(link, target).statusCode(403).body("code", equalTo("share_not_contributable"))
        }
    }

    /**
     * A `WRITE_ONLY` guest holds no key below its root, so a target other than the root is refused — the chapter of
     * its Memory, and a node outside answering the same (nothing about where it lies); the root named explicitly is
     * accepted as no `nodeId` is.
     */
    @Test
    fun anUploaderNamesNothingButItsRoot() {
        val t = tree()
        val link = GuestTreeShare.seed(t.userId, t.scopeId, t.memory, role = ShareEntity.WRITE_ONLY)
        for (target in listOf(t.chapter, t.outside)) {
            start(link, target).statusCode(403).body("code", equalTo("share_not_contributable"))
            window(link, target, UUID.randomUUID().toString()).statusCode(403).body("code", equalTo("share_not_contributable"))
            complete(link, target, UUID.randomUUID().toString()).statusCode(403).body("code", equalTo("share_not_contributable"))
        }
        start(link, t.memory).statusCode(200).body("results[0].error", nullValue())
        start(link, null).statusCode(200).body("results[0].error", nullValue())
    }

    /** A Memory link's contributor uploads into a chapter below the Memory; without a target, into the Memory. */
    @Test
    fun aMemoryContributorUploadsIntoAChapterBelow() {
        val t = tree()
        val link = GuestTreeShare.seed(t.userId, t.scopeId, t.memory)
        val intoChapter = upload(link, t.chapter)
        val intoRoot = upload(link, null)
        QuarkusTransaction.requiringNew().run {
            assertEquals(t.chapter, MediaEntity.findById(intoChapter)!!.nodeId)
            assertEquals(t.memory, MediaEntity.findById(intoRoot)!!.nodeId)
        }
        start(link, t.folder).statusCode(404).body("code", equalTo("node_not_found")) // above the root: outside
    }

    /**
     * The per-link limit counts the link's media wherever in its subtree they lie: a medium in the Memory below the
     * folder fills a limit of exactly its size, so a start into the chapter is refused — and admitted once the limit
     * has room for both (the pair pins the sum, not only a refusal).
     */
    @Test
    fun theLinkLimitCountsMediaInEveryNodeOfTheSubtree() {
        val t = tree()
        val link = GuestTreeShare.seed(t.userId, t.scopeId, t.folder)
        upload(link, t.memory)
        setLimit(t.tenantId, 2 * s.byteSize - 1)
        start(link, t.chapter).statusCode(200).body("results[0].error", equalTo("quota_exceeded"))
        setLimit(t.tenantId, 2 * s.byteSize)
        start(link, t.chapter).statusCode(200).body("results[0].error", nullValue())
    }

    // --- helpers ---

    private fun identityOf(link: GuestTreeShare.Link): String =
        QuarkusTransaction.requiringNew().call { ShareIdentityEntity.find("shareId", link.shareId).firstResult()!!.id }

    private fun nodeField(target: String?) = target?.let { ""","nodeId":"$it"""" } ?: ""

    private fun start(link: GuestTreeShare.Link, target: String?): ValidatableResponse =
        given().contentType(ContentType.JSON)
            .body("""{"contributorToken":"${link.token}"${nodeField(target)},"items":[${s.declare("Ymxvyi13cmFw")}]}""")
            .`when`().post("/api/shares/${link.shareId}/blob-uploads").then()

    private fun window(link: GuestTreeShare.Link, target: String?, uploadId: String): ValidatableResponse =
        given().contentType(ContentType.JSON)
            .body("""{"contributorToken":"${link.token}"${nodeField(target)},"items":[{"uploadId":"$uploadId","fromChunk":0}]}""")
            .`when`().post("/api/shares/${link.shareId}/blob-upload-windows").then()

    private fun complete(link: GuestTreeShare.Link, target: String?, uploadId: String): ValidatableResponse =
        given().contentType(ContentType.JSON)
            .body("""{"nodeEpoch":0,"contributorToken":"${link.token}"${nodeField(target)},"items":[{"mediaId":"${TestBlob.ownerOf(uploadId)}","originalUploadId":"$uploadId"}]}""")
            .`when`().post("/api/shares/${link.shareId}/media").then()

    private fun stage(uploadId: String) =
        s.stage(fakeStore, QuarkusTransaction.requiringNew().call { UploadSessionEntity.findById(uploadId)!!.storageKey })

    /** Start, window and complete one upload into [target]; the medium's id. */
    private fun upload(link: GuestTreeShare.Link, target: String?): String {
        val uploadId = start(link, target).statusCode(200).body("results[0].error", nullValue()).extract().path<String>("results[0].uploadId")
        stage(uploadId)
        window(link, target, uploadId).statusCode(200).body("results[0].error", nullValue())
        return complete(link, target, uploadId).statusCode(200).body("results[0].error", nullValue()).extract().path("results[0].mediaId")
    }

    private fun deleteOwn(link: GuestTreeShare.Link, mediaId: String): ValidatableResponse =
        given().header("X-Contributor-Token", link.token).`when`().delete("/api/shares/${link.shareId}/media/$mediaId").then()

    private fun setLimit(tenantId: String, bytes: Long) =
        QuarkusTransaction.requiringNew().run { TenantEntity.findById(tenantId)!!.shareLinkLimitBytes = bytes }

    /** A member's medium on [nodeId] with one blob. */
    private fun seedMedia(scopeId: String, nodeId: String): String {
        val mediaId = UUID.randomUUID().toString()
        val blobId = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            BlobEntity().apply {
                id = blobId; this.scopeId = scopeId; storageKey = UUID.randomUUID().toString()
                chunkCount = s.chunkCount; byteSize = s.byteSize; wrappedBlobKey = "Ymxvyi13cmFw"
            }.persist()
            MediaEntity().apply {
                id = mediaId; this.scopeId = scopeId; this.nodeId = nodeId; updatedAt = Instant.now()
            }.also { it.persist(); MediaBlobRows.holdFor(it, blobId) }
        }
        return mediaId
    }
}
