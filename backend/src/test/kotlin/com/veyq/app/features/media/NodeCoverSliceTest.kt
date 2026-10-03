package com.veyq.app.features.media

import com.veyq.app.features.content.NodeEntity
import com.veyq.app.shared.blobstore.StoreReclaimEntity
import com.veyq.app.testing.TestFixtures.CONTENT_KEY
import com.veyq.app.testing.TestFixtures.bearer
import com.veyq.app.testing.TestFixtures.seedMemberScope
import com.veyq.app.testing.TestFixtures.seedNode
import com.veyq.app.testing.TestFixtures.seedUser
import com.veyq.app.testing.RACE_ROUNDS
import com.veyq.app.testing.whileUncommitted
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.notNullValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Slice tests for the **standalone cover blob** (uc.memory.cover, cr-0073) against the **in-memory store**
 * (dev/test metadata only — no byte transfer; the byte path is the opt-in [BlobBatchE2ETest]). A cover blob is
 * a `veyq_blob` attached to a node via `cover_blob_id` with **no `veyq_media` row** (never in the gallery).
 * Covers: commit → the blob is download-authorized (proving the row + authz) with no media row; replace
 * reclaims the prior blob; `deleteCoverBlob` reclaims it while `deleteNode` deliberately does NOT (ADR 0061
 * moved that to the purge, so a restore gets its cover back); a FOLDER is `illegal_target`; unknown
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
    lateinit var store: MediaStore

    @Inject
    lateinit var handler: MediaHandler

    @Test
    fun commitAttachesCoverBlobWithNoMediaRow() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, NodeEntity.MEMORY, wrappedContentKey = CONTENT_KEY)
        val blob = TestBlob.ofPlaintext(4096)
        val uploadId = startOne(userId, scopeId, blob, wrap = "Y292ZXItd3JhcA==", ownerId = memory, role = "COVER")

        // commit the upload as the node's standalone cover — the ref echoes the declared shape + wrap verbatim
        val blobId = given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"uploadId":"$uploadId"}""")
            .`when`().post("/api/scopes/$scopeId/nodes/$memory/cover-blob")
            .then().statusCode(200)
            .body("blobId", notNullValue())
            .body("chunkCount", equalTo(1))
            .body("byteSize", equalTo(blob.byteSize.toInt()))
            .body("wrappedBlobKey", equalTo("Y292ZXItd3JhcA==")) // operator-blind: the K_blob wrap round-trips
            .extract().path<String>("blobId")

        // NO media row — a cover blob never appears in the gallery list; and cr-0107: a cover change bumps
        // NEITHER counter (it writes no veyq_media row + never lists), so a cover swap must not force a re-list.
        given().header("Authorization", bearer(userId))
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
            assertEquals(blob.byteSize, node.coverByteSize)
        }
    }

    @Test
    fun replaceReclaimsPriorCoverBlob() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, NodeEntity.MEMORY, wrappedContentKey = CONTENT_KEY)
        val first = commitCover(userId, scopeId, memory, startOne(userId, scopeId, TestBlob.SMALL, "Zmlyc3Q=", memory, "COVER"))

        // the key the replace must not lose — read while the row that holds it still exists
        val firstStorageKey = QuarkusTransaction.requiringNew().call { BlobEntity.findById(first)!!.storageKey }

        val second = commitCover(userId, scopeId, memory, startOne(userId, scopeId, TestBlob.ofChunks(2), "c2Vjb25k", memory, "COVER"))

        // the superseded first blob is reclaimed (gone); the new one is authorized
        assertEquals("upload_not_found", downloadError(userId, scopeId, first))
        assertEquals("store_unavailable", downloadError(userId, scopeId, second))
        QuarkusTransaction.requiringNew().run {
            assertEquals(second, NodeEntity.findById(memory)!!.coverBlobId)
        }
        assert(QuarkusTransaction.requiringNew().call { StoreReclaimEntity.count("storageKey", firstStorageKey) } == 1L) {
            "commitCover must leave a durable reclaim row for the cover blob it superseded"
        }
    }

    @Test
    fun deleteCoverBlobDetachesAndReclaims() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, NodeEntity.MEMORY, wrappedContentKey = CONTENT_KEY)
        val blobId = commitCover(userId, scopeId, memory, startOne(userId, scopeId, TestBlob.SMALL, "ZGVs", memory, "COVER"))

        // the key the delete must not lose — read while the row that holds it still exists
        val storageKey = QuarkusTransaction.requiringNew().call { BlobEntity.findById(blobId)!!.storageKey }

        given().header("Authorization", bearer(userId))
            .`when`().delete("/api/scopes/$scopeId/nodes/$memory/cover-blob?blobId=$blobId")
            .then().statusCode(204)

        assertEquals("upload_not_found", downloadError(userId, scopeId, blobId)) // reclaimed
        QuarkusTransaction.requiringNew().run {
            val node = NodeEntity.findById(memory)!!
            assertNull(node.coverBlobId)
            assertNull(node.coverByteSize)
        }
        assert(QuarkusTransaction.requiringNew().call { StoreReclaimEntity.count("storageKey", storageKey) } == 1L) {
            "clearCover must leave a durable reclaim row for the cover blob it deleted"
        }

        // idempotent — deleting again (no cover blob) still answers 204
        given().header("Authorization", bearer(userId))
            .`when`().delete("/api/scopes/$scopeId/nodes/$memory/cover-blob?blobId=$blobId")
            .then().statusCode(204)
    }

    /**
     * The node has ONE cover-blob slot, and the client deletes the upload its meta write replaced only after that
     * write landed (ADR 0077 point 3). A peer's upload committed in between sits in the slot by then: a delete that
     * took whatever the slot held reclaimed the peer's new cover, and the peer's sealed meta named a blob that was
     * gone (inbox #308). The delete names the blob it expects and is refused when the slot holds another one; the
     * expected blob is already gone — the peer's commit reclaimed it. Control: the same delete naming the blob the
     * slot does hold lands.
     */
    @Test
    fun aDeleteNamingAReplacedCoverIsRefusedAndKeepsThePeersUpload() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, NodeEntity.MEMORY, wrappedContentKey = CONTENT_KEY)
        val mine = commitCover(userId, scopeId, memory, startOne(userId, scopeId, TestBlob.SMALL, "bWluZQ==", memory, "COVER"))
        val peers = commitCover(userId, scopeId, memory, startOne(userId, scopeId, TestBlob.SMALL, "cGVlcg==", memory, "COVER"))

        given().header("Authorization", bearer(userId))
            .`when`().delete("/api/scopes/$scopeId/nodes/$memory/cover-blob?blobId=$mine")
            .then().statusCode(409).body("code", equalTo("cover_replaced"))

        assertEquals("store_unavailable", downloadError(userId, scopeId, peers), "the peer's upload was reclaimed")
        QuarkusTransaction.requiringNew().run {
            val node = NodeEntity.findById(memory)!!
            assertEquals(peers, node.coverBlobId, "the delete cleared the peer's cover from the slot")
            assertNotNull(node.coverByteSize)
        }

        // Control: naming the blob the slot holds detaches and reclaims it.
        given().header("Authorization", bearer(userId))
            .`when`().delete("/api/scopes/$scopeId/nodes/$memory/cover-blob?blobId=$peers")
            .then().statusCode(204)
        assertEquals("upload_not_found", downloadError(userId, scopeId, peers))
        QuarkusTransaction.requiringNew().run { assertNull(NodeEntity.findById(memory)!!.coverBlobId) }
    }

    /**
     * The compare must be the clearing write's own predicate, not a read before it (developer-styleguide §12): a
     * peer's commit in flight when the delete reads the slot still holds the expected blob, and commits the new one
     * before the delete writes. The delete waits for it and must then see the peer's blob, refuse, and leave both
     * the slot and the new blob row alone.
     */
    @Test
    fun aDeleteOverlappingAPeersCommitDoesNotTakeItsUpload() {
        repeat(RACE_ROUNDS) { round ->
            val (userId, scopeId) = seedMemberScope()
            val memory = seedNode(scopeId, NodeEntity.MEMORY, wrappedContentKey = CONTENT_KEY)
            val mine = commitCover(userId, scopeId, memory, startOne(userId, scopeId, TestBlob.SMALL, "bWluZQ==", memory, "COVER"))
            val upload = startOne(userId, scopeId, TestBlob.SMALL, "cGVlcg==", memory, "COVER")
            var peers: String? = null
            val outcome = whileUncommitted(
                change = {
                    val session = store.findSession(scopeId, upload, UploadOpener.Member(userId))!!
                    peers = store.commitCover(memory, session, null)!!.blobId
                },
                overlapping = { handler.deleteCover(userId, scopeId, memory, mine) },
            )
            assertEquals(CoverDeleteOutcome.Replaced, outcome, "round $round")
            assertEquals("store_unavailable", downloadError(userId, scopeId, peers!!), "round $round: the peer's upload was reclaimed")
            QuarkusTransaction.requiringNew().run {
                assertEquals(peers, NodeEntity.findById(memory)!!.coverBlobId, "round $round: the delete cleared the peer's cover")
            }
        }
    }

    /**
     * A commit reclaims the blob the slot holds when it writes, not the one it saw before: a peer's commit in flight
     * holds the node row and reclaims the old cover itself. Read before the wait, the commit deleted that old row a
     * second time, found none, and answered 500 (inbox #310). Locked first, it reclaims the peer's blob and takes the
     * slot; each blob is reclaimed once.
     */
    @Test
    fun aCommitOverlappingAPeersCommitReclaimsThePeersCover() {
        repeat(RACE_ROUNDS) { round ->
            val (userId, scopeId) = seedMemberScope()
            val memory = seedNode(scopeId, NodeEntity.MEMORY, wrappedContentKey = CONTENT_KEY)
            val old = commitCover(userId, scopeId, memory, startOne(userId, scopeId, TestBlob.SMALL, "b2xk", memory, "COVER"))
            val peerUpload = startOne(userId, scopeId, TestBlob.SMALL, "cGVlcg==", memory, "COVER")
            val mineUpload = startOne(userId, scopeId, TestBlob.SMALL, "bWluZQ==", memory, "COVER")
            var peers: String? = null
            val outcome = whileUncommitted(
                change = {
                    val session = store.findSession(scopeId, peerUpload, UploadOpener.Member(userId))!!
                    peers = store.commitCover(memory, session, null)!!.blobId
                },
                overlapping = { handler.commitCover(userId, scopeId, memory, mineUpload) },
            )
            val mine = assertInstanceOf(CoverCommitOutcome.Ok::class.java, outcome, "round $round").ref.blobId
            QuarkusTransaction.requiringNew().run {
                assertEquals(mine, NodeEntity.findById(memory)!!.coverBlobId, "round $round: the later commit holds the slot")
            }
            assertEquals("store_unavailable", downloadError(userId, scopeId, mine), "round $round")
            assertEquals("upload_not_found", downloadError(userId, scopeId, peers!!), "round $round: the peer's blob leaked")
            assertEquals("upload_not_found", downloadError(userId, scopeId, old), "round $round")
        }
    }

    /**
     * The same read-before-lock against a delete: the delete in flight holds the node row and reclaims the cover, and
     * the commit, having read that cover before the wait, deleted its row again and answered 500 (inbox #310). Locked
     * first, it finds the slot empty and only attaches its own.
     */
    @Test
    fun aCommitOverlappingADeleteFindsTheSlotEmpty() {
        repeat(RACE_ROUNDS) { round ->
            val (userId, scopeId) = seedMemberScope()
            val memory = seedNode(scopeId, NodeEntity.MEMORY, wrappedContentKey = CONTENT_KEY)
            val old = commitCover(userId, scopeId, memory, startOne(userId, scopeId, TestBlob.SMALL, "b2xk", memory, "COVER"))
            val oldStorageKey = QuarkusTransaction.requiringNew().call { BlobEntity.findById(old)!!.storageKey }
            val mineUpload = startOne(userId, scopeId, TestBlob.SMALL, "bWluZQ==", memory, "COVER")
            val outcome = whileUncommitted(
                change = { assertEquals(MediaStore.ClearCoverResult.CLEARED, store.clearCover(scopeId, memory, old)) },
                overlapping = { handler.commitCover(userId, scopeId, memory, mineUpload) },
            )
            val mine = assertInstanceOf(CoverCommitOutcome.Ok::class.java, outcome, "round $round").ref.blobId
            QuarkusTransaction.requiringNew().run {
                assertEquals(mine, NodeEntity.findById(memory)!!.coverBlobId, "round $round")
            }
            assertEquals(1L, QuarkusTransaction.requiringNew().call { StoreReclaimEntity.count("storageKey", oldStorageKey) },
                "round $round: the deleted cover is reclaimed exactly once")
        }
    }

    /**
     * cr-0073 reclaimed the cover blob at delete time; ADR 0061 moved that into `NodeSubtreePurge`, and this
     * test asserts the *reversal*, not a weakening. Under a soft-delete the cover MUST survive the delete —
     * reclaiming it there would hand a restore back a Memory whose cover is already gone, which is exactly the
     * "nothing is destroyed by the user's action" property that makes the restore complete. The reclaim itself
     * is still asserted, one step later, in `NodeTrashSliceTest.purgeReclaimsMediaDerivativesBucketsCoverAndTheStoreObjects`.
     */
    @Test
    fun deleteNodeKeepsTheCoverBlobForTheRestore() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, NodeEntity.MEMORY, wrappedContentKey = CONTENT_KEY)
        val blobId = commitCover(userId, scopeId, memory, startOne(userId, scopeId, TestBlob.SMALL, "bm9kZQ==", memory, "COVER"))

        given().header("Authorization", bearer(userId))
            .`when`().delete("/api/scopes/$scopeId/nodes/$memory")
            .then().statusCode(204)

        // `store_unavailable` = the blob row is still there and authorization passed (only the transport is
        // absent on the hermetic store); `upload_not_found` would mean the row was reclaimed.
        assertEquals("store_unavailable", downloadError(userId, scopeId, blobId))
    }

    /**
     * The cover's `K_blob` wrap is sealed bound to (this node, COVER) (ADR 0072), so an upload started for anything
     * else — here a media ORIGINAL, and a cover of another node — would attach a blob its readers cannot open.
     * Refused, and nothing attached; the control is [commitAttachesCoverBlobWithNoMediaRow], the same commit of an
     * upload started as this node's cover.
     */
    @Test
    fun anUploadNotStartedAsThisNodesCoverIsRefused() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, NodeEntity.MEMORY, wrappedContentKey = CONTENT_KEY)
        val other = seedNode(scopeId, NodeEntity.MEMORY, wrappedContentKey = CONTENT_KEY)
        for (upload in listOf(
            startOne(userId, scopeId, TestBlob.SMALL, "b3JpZw==", ownerId = memory, role = "ORIGINAL"),
            startOne(userId, scopeId, TestBlob.SMALL, "b3RoZXI=", ownerId = other, role = "COVER"),
        )) {
            given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
                .body("""{"uploadId":"$upload"}""")
                .`when`().post("/api/scopes/$scopeId/nodes/$memory/cover-blob")
                .then().statusCode(422).body("code", equalTo("upload_mismatch"))
        }
        QuarkusTransaction.requiringNew().run {
            assertNull(NodeEntity.findById(memory)!!.coverBlobId, "nothing was attached as the cover")
        }
    }

    @Test
    fun coverOnFolderIsIllegalTarget() {
        val (userId, scopeId) = seedMemberScope()
        val folder = seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = null)
        val uploadId = startOne(userId, scopeId, TestBlob.SMALL, "Zm9sZA==")

        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"uploadId":"$uploadId"}""")
            .`when`().post("/api/scopes/$scopeId/nodes/$folder/cover-blob")
            .then().statusCode(422).body("code", equalTo("illegal_target"))
    }

    @Test
    fun coverOnAnUnknownKindIsIllegalTarget() {
        // The allow-list bites on a kind that is none of FOLDER/MEMORY/CHAPTER (a deny-list of FOLDER would let it through).
        val (userId, scopeId) = seedMemberScope()
        val alien = seedNode(scopeId, "ALIEN", wrappedContentKey = CONTENT_KEY)
        val uploadId = startOne(userId, scopeId, TestBlob.SMALL, "Zm9sZA==")

        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"uploadId":"$uploadId"}""")
            .`when`().post("/api/scopes/$scopeId/nodes/$alien/cover-blob")
            .then().statusCode(422).body("code", equalTo("illegal_target"))
    }

    @Test
    fun commitUnknownUploadIs404() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, NodeEntity.MEMORY, wrappedContentKey = CONTENT_KEY)
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"uploadId":"${UUID.randomUUID()}"}""")
            .`when`().post("/api/scopes/$scopeId/nodes/$memory/cover-blob")
            .then().statusCode(404).body("code", equalTo("node_not_found"))
    }

    @Test
    fun commitUnknownNodeIs404() {
        val (userId, scopeId) = seedMemberScope()
        val uploadId = startOne(userId, scopeId, TestBlob.SMALL, "bm9wZQ==")
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"uploadId":"$uploadId"}""")
            .`when`().post("/api/scopes/$scopeId/nodes/${UUID.randomUUID()}/cover-blob")
            .then().statusCode(404).body("code", equalTo("node_not_found"))
    }

    @Test
    fun crossScopeUploadIs404() {
        val (userId, scopeA) = seedMemberScope()
        val scopeB = seedMemberScope(userId = userId).scopeId // the same user is a member of a second scope
        val memoryB = seedNode(scopeB, NodeEntity.MEMORY, wrappedContentKey = CONTENT_KEY)
        val uploadInA = startOne(userId, scopeA, TestBlob.SMALL, "eA==")

        // committing scope A's upload against a scope B node — the session isn't in B → 404 (no cross-scope reach)
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"uploadId":"$uploadInA"}""")
            .`when`().post("/api/scopes/$scopeB/nodes/$memoryB/cover-blob")
            .then().statusCode(404).body("code", equalTo("node_not_found"))
    }

    @Test
    fun nonMemberIs404() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, NodeEntity.MEMORY, wrappedContentKey = CONTENT_KEY)
        val uploadId = startOne(userId, scopeId, TestBlob.SMALL, "b3V0")
        val outsider = seedUser()

        given().header("Authorization", bearer(outsider)).contentType(ContentType.JSON)
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

    private fun startOne(
        userId: String, scopeId: String, blob: TestBlob, wrap: String,
        ownerId: String = UUID.randomUUID().toString(), role: String = "ORIGINAL",
    ): String =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"items":[${blob.declare(wrap, ownerId = ownerId, role = role)}]}""")
            .`when`().post("/api/scopes/$scopeId/blob-uploads-batch")
            .then().statusCode(200).body("results[0].uploadId", notNullValue())
            .extract().path("results[0].uploadId")

    /** Commit an upload as the node's cover and return the committed blob id. */
    private fun commitCover(userId: String, scopeId: String, nodeId: String, uploadId: String): String =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"uploadId":"$uploadId"}""")
            .`when`().post("/api/scopes/$scopeId/nodes/$nodeId/cover-blob")
            .then().statusCode(200).body("blobId", notNullValue())
            .extract().path("blobId")

    /** The per-item presign-download error for a blob: `store_unavailable` (exists) vs `upload_not_found` (gone). */
    private fun downloadError(userId: String, scopeId: String, blobId: String): String? =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"items":[{"blobId":"$blobId"}]}""")
            .`when`().post("/api/scopes/$scopeId/blob-downloads-batch")
            .then().statusCode(200)
            .extract().path("results[0].error")
}
