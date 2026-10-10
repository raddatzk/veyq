package me.raddatz.veyq.memories.sharing

import me.raddatz.veyq.core.TestIds
import me.raddatz.veyq.core.blob.BlobEntity
import me.raddatz.veyq.core.blob.UploadSessionEntity
import me.raddatz.veyq.core.blobstore.FakePresigningBlobStore
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.sharing.GuestTreeShare.create
import me.raddatz.veyq.core.sharing.GuestTreeShare.place
import me.raddatz.veyq.core.testing.FakePresignProfile
import me.raddatz.veyq.core.testing.TestBlob
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.DROP_PRIVATE_KEY
import me.raddatz.veyq.core.testing.TestFixtures.WRAPPED_UPLOADER_KEY
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.identityKeyJson
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.core.testing.TestFixtures.shareKeysJson
import me.raddatz.veyq.memories.media.MediaEntity
import me.raddatz.veyq.memories.media.ShareMediaRead
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
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import me.raddatz.veyq.core.sharing.GuestNodeRules
import me.raddatz.veyq.core.sharing.GuestTreeShare
import me.raddatz.veyq.core.sharing.ShareWriteProof

/**
 * A `CHAPTER` as a share root (ADR 0080 decisions 9 and 13, bite tests 12 and 21; cr-node-key-hierarchy 5a.4): a
 * chapter link is a smaller Memory link. Its guest is served the chapter and what hangs under it and nothing of the
 * Memory above or of a sibling chapter; a contributor's and an uploader's media land in the chapter (the guest complete
 * attaches to the share's root), an uploader seals to the chapter's own drop key, and a member reads what opens it on
 * the chapter's media page; the guest's name stays the members' (ADR 0030 point 8). Inside a chapter a guest does no
 * tree operation: Memories declares no [GuestNodeRules], and the root is never a guest's to move.
 *
 * [FakePresignProfile]: the blob GET answers presigned URLs (200), so "opens" is a real answer, not the in-memory
 * store's 503; a guest complete verifies chunks the test stages at their exact lengths.
 */
@QuarkusTest
@TestProfile(FakePresignProfile::class)
class ChapterShareSliceTest {

    @Inject lateinit var fakeStore: FakePresigningBlobStore

    private val shareWrap = "d3JhcC1jaGFwdGVyLWtleQ=="
    private val sealedName = "c2VhbGVkLW5hbWU="
    private val uploaderName = "c2VhbGVkLXVwbG9hZGVyLW5hbWU="

    // Distinct opaque values per node, so the raw resolve body can be searched for each one.
    private val memoryName = "bWVtb3J5LW5hbWU="
    private val memoryWrap = "bWVtb3J5LXdyYXA="
    private val chapterName = "Y2hhcHRlci1uYW1l"
    private val chapterWrap = "Y2hhcHRlci13cmFw"
    private val siblingName = "c2libGluZy1uYW1l"
    private val siblingWrap = "c2libGluZy13cmFw"

    private data class Tree(
        val userId: String, val scopeId: String, val memory: String, val chapter: String, val sibling: String,
    )

    /** A Memory holding two chapters; the link is rooted at `chapter`, `sibling` is the other one. */
    private fun tree(): Tree {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = memoryWrap, encryptedName = memoryName)
        val chapter = seedNode(scopeId, MemoriesNodeKinds.CHAPTER, parentId = memory, wrappedContentKey = chapterWrap, encryptedName = chapterName)
        val sibling = seedNode(scopeId, MemoriesNodeKinds.CHAPTER, parentId = memory, wrappedContentKey = siblingWrap, encryptedName = siblingName)
        return Tree(userId, scopeId, memory, chapter, sibling)
    }

    /**
     * Bite tests 21 and 12: a chapter's guest is served the chapter (its root entry without the parent's placement),
     * the chapter's media and its blobs — and of the parent Memory and the sibling chapter nothing: no id, no name, no
     * wrap, no medium, no blob. The control is a Memory link on the same tree, which serves all of it, so every
     * absence above is one the subtree rule makes, not one of missing rows.
     */
    @Test
    fun aChapterGuestReadsTheChapterAndNothingOfItsMemory() {
        val t = tree()
        val (memoryMedia, memoryBlob) = seedMedia(t.scopeId, t.memory)
        val (chapterMedia, chapterBlob) = seedMedia(t.scopeId, t.chapter)
        val (siblingMedia, siblingBlob) = seedMedia(t.scopeId, t.sibling)
        val shareId = createShare(t.userId, t.chapter, "READ_WRITE")

        val body = given().`when`().get("/api/shares/$shareId").then().statusCode(200)
            .body("nodeId", equalTo(t.chapter))
            .body("wrap", equalTo(shareWrap))
            .body("rootKind", equalTo(MemoriesNodeKinds.CHAPTER))
            .body("nodes.size()", equalTo(1))
            .body("nodes[0].id", equalTo(t.chapter))
            .body("nodes[0].parentId", nullValue())
            .body("nodes[0].wrappedContentKey", nullValue())
            .extract().asString()
        assertAll(
            { assertFalse(body.contains(t.memory), "no parent id: nothing above the root") },
            { assertFalse(body.contains(memoryName) || body.contains(memoryWrap), "no entry of the parent Memory") },
            { assertFalse(body.contains(chapterName) || body.contains(chapterWrap), "the root's own entry is under the Memory's key") },
            { assertFalse(body.contains(t.sibling) || body.contains(siblingName) || body.contains(siblingWrap), "no sibling chapter") },
        )

        val media = ShareMediaRead.of(shareId).then().statusCode(200)
            .body("media.size()", equalTo(1))
            .body("media[0].mediaId", equalTo(chapterMedia))
            .body("media[0].nodeId", equalTo(t.chapter))
            .extract().asString()
        assertFalse(media.contains(memoryMedia) || media.contains(siblingMedia), "no medium of the Memory or the sibling")

        blob(shareId, chapterBlob).statusCode(200).body("presignedGets.size()", equalTo(TestBlob.SMALL.chunkCount))
        blob(shareId, memoryBlob).statusCode(404)
        blob(shareId, siblingBlob).statusCode(404)

        // control: a Memory link on the same tree serves the two chapters, the three media and the Memory's blob
        val memoryShare = createShare(t.userId, t.memory, "READ")
        given().`when`().get("/api/shares/$memoryShare").then().statusCode(200)
            .body("nodes.size()", equalTo(3))
            .body("nodes.find { it.id == '${t.chapter}' }.encryptedName", equalTo(chapterName))
            .body("nodes.find { it.id == '${t.sibling}' }.wrappedContentKey", equalTo(siblingWrap))
        ShareMediaRead.of(memoryShare).then().statusCode(200).body("media.size()", equalTo(3))
        blob(memoryShare, memoryBlob).statusCode(200)
    }

    /**
     * An uploader link on a chapter: the first one stores its drop private key on the chapter (not on the Memory), the
     * guest's upload lands in the chapter stamped with its identity, the uploader is served nothing back, and a member
     * reads on the chapter's media page and buckets what opens it (the chapter's drop key, the identity's wrapped key).
     * A viewer link on the chapter serves the medium with its identity (the key it opens through) and never the
     * guest's sealed name, which the member's buckets carry (ADR 0030 point 8, as on a Memory).
     */
    @Test
    fun anUploaderOnAChapterLandsInTheChapterAndAMemberOpensIt() {
        val t = tree()
        val shareId = createShare(t.userId, t.chapter, "WRITE_ONLY")
        QuarkusTransaction.requiringNew().run {
            assertEquals(DROP_PRIVATE_KEY, NodeEntity.findById(t.chapter)!!.sealedDropPrivateKey, "the chapter's own drop key")
            assertNull(NodeEntity.findById(t.memory)!!.sealedDropPrivateKey, "the Memory's untouched")
        }
        val (identityId, token) = identity(shareId, identityKeyJson("WRITE_ONLY"))
        val mediaId = upload(shareId, token)

        QuarkusTransaction.requiringNew().run {
            val m = MediaEntity.findById(mediaId)!!
            assertEquals(t.chapter, m.nodeId, "a guest's upload lands in the shared chapter")
            assertEquals(identityId, m.shareIdentityId)
        }
        ShareMediaRead.of(shareId).then().statusCode(200).body("media.size()", equalTo(0)).body("identities.size()", equalTo(0))

        val auth = bearer(t.userId)
        given().header("Authorization", auth).`when`().get("/api/scopes/${t.scopeId}/nodes/${t.chapter}/media").then().statusCode(200)
            .body("media.size()", equalTo(1))
            .body("media[0].mediaId", equalTo(mediaId))
            .body("sealedDropPrivateKey", equalTo(DROP_PRIVATE_KEY))
            .body("uploaderKeys.find { it.identityId == '$identityId' }.wrappedIdentityKey", equalTo(WRAPPED_UPLOADER_KEY))
        given().header("Authorization", auth).`when`().get("/api/scopes/${t.scopeId}/nodes/${t.chapter}/buckets").then().statusCode(200)
            .body("sealedDropPrivateKey", equalTo(DROP_PRIVATE_KEY))
            .body("buckets.find { it.ownerKey == 'share:$identityId' }.sealedName", equalTo(uploaderName))
        given().header("Authorization", auth).`when`().get("/api/scopes/${t.scopeId}/nodes/${t.memory}/media").then().statusCode(200)
            .body("media.size()", equalTo(0))

        val viewer = createShare(t.userId, t.chapter, "READ")
        given().`when`().get("/api/shares/$viewer").then().statusCode(200)
            .body("nodes[0].sealedDropPrivateKey", equalTo(DROP_PRIVATE_KEY))
        val read = ShareMediaRead.of(viewer).then().statusCode(200)
            .body("media.find { it.mediaId == '$mediaId' }.identityId", equalTo(identityId))
            .body("identities.size()", equalTo(1))
            .body("identities[0].identityId", equalTo(identityId))
            .body("identities[0].wrappedIdentityKey", equalTo(WRAPPED_UPLOADER_KEY))
            .body("identities[0].containsKey('sealedDisplayName')", equalTo(false))
            .extract().asString()
        assertFalse(read.contains(uploaderName), "a guest's name is for the members only")
    }

    /**
     * A contributor link on a chapter: a guest starts, windows and completes into the chapter, its row served to a
     * guest without its identity (ADR 0030 point 8), and deletes its own medium — not a member's in the same chapter.
     */
    @Test
    fun aContributorOnAChapterUploadsIntoItAndDeletesOnlyItsOwn() {
        val t = tree()
        val (membersMedia, _) = seedMedia(t.scopeId, t.chapter)
        val shareId = createShare(t.userId, t.chapter, "READ_WRITE")
        val (_, token) = identity(shareId, "")

        val uploadId = start(shareId, token)
        given().contentType(ContentType.JSON)
            .body("""{"contributorToken":"$token","items":[{"uploadId":"$uploadId","fromChunk":0}]}""")
            .`when`().post("/api/shares/$shareId/blob-upload-windows")
            .then().statusCode(200).body("results[0].error", nullValue())
        val mediaId = complete(shareId, token, uploadId)
        QuarkusTransaction.requiringNew().run { assertEquals(t.chapter, MediaEntity.findById(mediaId)!!.nodeId) }
        ShareMediaRead.of(shareId).then().statusCode(200)
            .body("media.size()", equalTo(2))
            .body("media.find { it.mediaId == '$mediaId' }.nodeId", equalTo(t.chapter))
            .body("media.find { it.mediaId == '$mediaId' }.identityId", nullValue())
            .body("identities.size()", equalTo(0))

        deleteOwn(shareId, token, membersMedia).statusCode(404).body("code", equalTo("share_media_not_found"))
        deleteOwn(shareId, token, mediaId).statusCode(204)
        QuarkusTransaction.requiringNew().run {
            assertNull(MediaEntity.findById(mediaId), "the guest's own medium is gone")
            assertEquals(membersMedia, MediaEntity.findById(membersMedia)?.id, "a member's stays")
        }
    }

    /**
     * Inside a chapter a guest does no tree operation (ADR 0080 decision 6): Memories declares no [GuestNodeRules], so
     * a create under the chapter is `illegal_scope` — refused by the container's owner before `NodeKindRules` is asked
     * — and the root is never a guest's to move or rename. The read of the same link is served.
     */
    @Test
    fun aChapterGuestDoesNoTreeOperation() {
        val t = tree()
        val shareId = createShare(t.userId, t.chapter, "READ_WRITE")
        val link = GuestTreeShare.Link(shareId, identity(shareId, "").second)
        for (kind in listOf(NodeEntity.FOLDER, MemoriesNodeKinds.MEMORY, MemoriesNodeKinds.CHAPTER)) {
            create(link, t.chapter, kind).statusCode(422).body("code", equalTo("illegal_scope"))
        }
        place(link, t.chapter, t.chapter).statusCode(422).body("code", equalTo("illegal_structure")) // a rename of the root
        place(link, t.chapter, t.sibling).statusCode(422).body("code", equalTo("illegal_structure")) // the root moved out
        place(link, t.sibling, t.chapter).statusCode(404).body("code", equalTo("node_not_found")) // a node outside, moved in
        QuarkusTransaction.requiringNew().run {
            assertEquals(0L, NodeEntity.count("parentId", t.chapter), "nothing created under the chapter")
            assertEquals(t.memory, NodeEntity.findById(t.chapter)!!.parentId)
            assertEquals(t.memory, NodeEntity.findById(t.sibling)!!.parentId)
        }
        given().`when`().get("/api/shares/$shareId").then().statusCode(200).body("nodes[0].id", equalTo(t.chapter))
    }

    // --- helpers ---

    private fun createShare(userId: String, nodeId: String, role: String): String =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{${ShareWriteProof.newShare()},"nodeId":"$nodeId","role":"$role",${shareKeysJson(role, nodeId, shareWrap)},"sealedName":"$sealedName",${ShareWriteProof.recovery()}}""")
            .`when`().post("/api/shares")
            .then().statusCode(201).body("shareId", notNullValue())
            .extract().path("shareId")

    /** A named guest on [shareId] with the key member [key] (`identityKeyJson`): (identityId, contributorToken). */
    private fun identity(shareId: String, key: String): Pair<String, String> {
        val r = given().contentType(ContentType.JSON)
            .body("""{"identityId":"${TestIds.uuid()}","sealedDisplayName":"$uploaderName"$key}""")
            .header(ShareWriteProof.HEADER, ShareWriteProof.token(shareId)).`when`().post("/api/shares/$shareId/identities")
            .then().statusCode(201).extract()
        return r.path<String>("identityId") to r.path<String>("contributorToken")
    }

    /** Start one upload and stage its chunks at their exact lengths, so its verify passes. */
    private fun start(shareId: String, token: String): String {
        val uploadId: String = given().contentType(ContentType.JSON)
            .body("""{"contributorToken":"$token","items":[${TestBlob.SMALL.declare("Ymxvyi13cmFw")}]}""")
            .`when`().post("/api/shares/$shareId/blob-uploads")
            .then().statusCode(200).body("results[0].error", nullValue()).extract().path("results[0].uploadId")
        TestBlob.SMALL.stage(fakeStore, QuarkusTransaction.requiringNew().call { UploadSessionEntity.findById(uploadId)!!.storageKey })
        return uploadId
    }

    private fun complete(shareId: String, token: String, uploadId: String): String =
        given().contentType(ContentType.JSON)
            .body("""{"contributorToken":"$token","items":[{"mediaId":"${TestBlob.ownerOf(uploadId)}","originalUploadId":"$uploadId"}]}""")
            .`when`().post("/api/shares/$shareId/media")
            .then().statusCode(200).body("results[0].error", nullValue()).extract().path("results[0].mediaId")

    private fun upload(shareId: String, token: String): String = complete(shareId, token, start(shareId, token))

    private fun deleteOwn(shareId: String, token: String, mediaId: String) =
        given().header("X-Contributor-Token", token).`when`().delete("/api/shares/$shareId/media/$mediaId").then()

    private fun blob(shareId: String, blobId: String) = given().`when`().get("/api/shares/$shareId/blobs/$blobId").then()

    /** A member's medium on [nodeId] with one blob: (mediaId, blobId). */
    private fun seedMedia(scopeId: String, nodeId: String): Pair<String, String> {
        val mediaId = UUID.randomUUID().toString()
        val blobId = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            BlobEntity().apply {
                id = blobId; this.scopeId = scopeId; storageKey = UUID.randomUUID().toString()
                chunkCount = TestBlob.SMALL.chunkCount; byteSize = TestBlob.SMALL.byteSize; wrappedBlobKey = "Ymxvyi13cmFw"
            }.persist()
            MediaEntity().apply {
                id = mediaId; this.scopeId = scopeId; this.nodeId = nodeId; this.blobId = blobId; createdAt = Instant.now()
            }.persist()
        }
        return mediaId to blobId
    }
}
