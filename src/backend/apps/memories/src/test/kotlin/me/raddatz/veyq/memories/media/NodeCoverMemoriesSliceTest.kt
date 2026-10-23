package me.raddatz.veyq.memories.media

import me.raddatz.veyq.core.blob.BlobEntity
import me.raddatz.veyq.core.blob.NodeAttachmentEntity
import me.raddatz.veyq.core.blobstore.StoreReclaimEntity
import me.raddatz.veyq.core.sharing.ShareWriteProof
import me.raddatz.veyq.core.testing.CoverKit
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.core.testing.TestFixtures.shareKeysJson
import me.raddatz.veyq.memories.node.MemoriesNodeKinds
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.hamcrest.CoreMatchers.equalTo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * A Memory's and a Chapter's cover through core's `setNodeCover` / `removeNodeCover` (inbox #411) - the same two writes
 * a folder's use, with Memories' kinds opted in by `MemoryNodeKindRules.takesCover`. Memories keeps what is its own:
 * the cover never touches the gallery or its counters, the shared Memory's guest reads the cover and no other, and
 * moving a cover from an uploaded image to one of the Memory's own photos is a removal that writes the new meta.
 * The zero-residue and compare-and-set cases are core's (`NodeCoverCoreSliceTest`); the in-memory store answers a
 * committed blob's download with `store_unavailable` and a gone one with `upload_not_found`.
 */
@QuarkusTest
class NodeCoverMemoriesSliceTest {

    @Test
    fun aMemoryAndAChapterTakeACoverWithoutTouchingTheGallery() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        val chapter = seedNode(scopeId, MemoriesNodeKinds.CHAPTER, parentId = memory, wrappedContentKey = CONTENT_KEY)

        for (node in listOf(memory, chapter)) {
            val started = CoverKit.start(userId, scopeId, node, wrap = "Y292ZXItd3JhcA==", meta = "Y292ZXItbWV0YQ==")
            CoverKit.set(userId, scopeId, node, started.uploadId, null, "bTE=", 0).statusCode(204)
            assertEquals(started.blobId, CoverKit.slot(node))
            assertEquals("store_unavailable", CoverKit.downloadError(userId, scopeId, started.blobId))
            QuarkusTransaction.requiringNew().run {
                val row = NodeAttachmentEntity.find("nodeId = ?1 and slot = ?2", node, "COVER").singleResult()
                assertEquals(scopeId, row.scopeId)
                assertNull(row.shareIdentityId, "a member's cover has no guest identity")
                assertEquals(started.blobId, row.blobId)
                assertEquals(1L, BlobEntity.count("id", started.blobId))
            }
            // a cover is no gallery entry and moves neither counter (cr-0107)
            given().header("Authorization", bearer(userId)).`when`().get("/api/scopes/$scopeId/nodes/$node/media")
                .then().statusCode(200).body("media.size()", equalTo(0)).body("mediaRevision", equalTo(0)).body("deleteRevision", equalTo(0))
        }
    }

    /** Moving a Memory's cover to one of its own photos: the new meta names the photo, the uploaded blob goes (one write). */
    @Test
    fun switchingToAPhotoCoverIsARemovalThatWritesTheNewMetaAndFreesTheUpload() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        val started = CoverKit.start(userId, scopeId, memory)
        CoverKit.set(userId, scopeId, memory, started.uploadId, null, "dXBsb2Fk", 0).statusCode(204)

        CoverKit.remove(userId, scopeId, memory, started.blobId, wrappedMeta = "cGhvdG8=", metaRevision = 1).statusCode(204)
        assertNull(CoverKit.slot(memory))
        assertEquals("cGhvdG8=" to 2L, CoverKit.meta(memory))
        assertEquals("upload_not_found", CoverKit.downloadError(userId, scopeId, started.blobId))
        assertEquals(1L, QuarkusTransaction.requiringNew().call { StoreReclaimEntity.count("storageKey", started.storageKey) })
    }

    /**
     * A share's guest reads the shared Memory's cover - through core's attachment arm of the blob gate - and never a
     * cover beside the shared subtree; a removed cover is no longer served. `503 store_unavailable` is "authorized".
     */
    @Test
    fun aShareGuestReadsTheSharedMemorysCoverOnlyAndNothingAfterItsRemoval() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        val beside = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        val cover = CoverKit.start(userId, scopeId, memory)
        CoverKit.set(userId, scopeId, memory, cover.uploadId, null, "bTE=", 0).statusCode(204)
        val besideCover = CoverKit.start(userId, scopeId, beside)
        CoverKit.set(userId, scopeId, beside, besideCover.uploadId, null, "bTE=", 0).statusCode(204)
        val shareId = given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{${ShareWriteProof.newShare()},"nodeId":"$memory","role":"READ",${shareKeysJson("READ", memory, "d3JhcA==")},"sealedName":"c2VhbGVk",${ShareWriteProof.recovery()}}""")
            .`when`().post("/api/shares")
            .then().statusCode(201).extract().path<String>("shareId")

        given().`when`().get("/api/shares/$shareId/blobs/${cover.blobId}")
            .then().statusCode(503).body("code", equalTo("store_unavailable"))
        given().`when`().get("/api/shares/$shareId/blobs/${besideCover.blobId}")
            .then().statusCode(404).body("code", equalTo("share_not_found"))

        CoverKit.remove(userId, scopeId, memory, cover.blobId, "bTI=", 1).statusCode(204)
        given().`when`().get("/api/shares/$shareId/blobs/${cover.blobId}")
            .then().statusCode(404).body("code", equalTo("share_not_found"))
    }

    /** A guest of a share has no cover endpoint at all: the operation needs a member's bearer token and write access. */
    @Test
    fun noGuestRouteSetsOrRemovesACover() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        val started = CoverKit.start(userId, scopeId, memory)
        given().contentType(ContentType.JSON)
            .body("""{"uploadId":"${started.uploadId}","wrappedMeta":"bQ==","nodeEpoch":0,"metaRevision":0}""")
            .`when`().put("/api/scopes/$scopeId/nodes/$memory/cover").then().statusCode(401)
        given().contentType(ContentType.JSON)
            .body("""{"blobId":"${started.blobId}","wrappedMeta":"bQ==","nodeEpoch":0,"metaRevision":0}""")
            .`when`().post("/api/scopes/$scopeId/nodes/$memory/cover/removal").then().statusCode(401)
        assertNull(CoverKit.slot(memory))
    }
}
