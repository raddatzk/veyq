package me.raddatz.veyq.memories.media

import me.raddatz.veyq.core.blob.AttachOutcome
import me.raddatz.veyq.core.blob.AttachmentStore
import me.raddatz.veyq.core.blob.BlobUploadStore
import me.raddatz.veyq.core.blob.NodeAttachmentEntity
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.testing.TestBlob
import me.raddatz.veyq.core.blob.UploadOpener
import me.raddatz.veyq.core.blob.BlobEntity
import me.raddatz.veyq.core.blobstore.StoreReclaimEntity
import me.raddatz.veyq.core.testing.RACE_ROUNDS
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.core.testing.TestFixtures.seedUser
import me.raddatz.veyq.core.testing.TestFixtures.shareKeysJson
import me.raddatz.veyq.core.sharing.ShareWriteProof
import me.raddatz.veyq.core.testing.whileUncommitted
import me.raddatz.veyq.memories.node.MemoriesNodeKinds
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import java.util.UUID
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.notNullValue
import org.hamcrest.CoreMatchers.nullValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Slice tests for the **standalone cover blob** (uc.memory.cover, cr-0073) against the **in-memory store**
 * (dev/test metadata only — no byte transfer; the byte path is the opt-in [BlobBatchE2ETest]). A cover blob is
 * a `veyq_blob` attached to a node as its `COVER` attachment (core's `veyq_node_attachment`, inbox #341) with
 * **no `veyq_media` row** (never in the gallery).
 * Covers: commit → the blob is download-authorized (proving the row + authz) with no media row; replace
 * reclaims the prior blob; `deleteCoverBlob` reclaims it while `deleteNode` deliberately does NOT (ADR 0061
 * moved that to the purge, so a restore gets its cover back); a FOLDER is `illegal_target`; unknown
 * node/upload + non-member are 404 and a cross-scope upload is 404; operator-blindness (the attachment row holds
 * only the slot and the blob id — the wrap stays on the blob, the crop never reaches the server).
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
    lateinit var blobs: BlobUploadStore

    @Inject
    lateinit var handler: MediaHandler

    @Test
    fun commitAttachesCoverBlobWithNoMediaRow() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        val blob = TestBlob.ofPlaintext(4096)
        val uploadId = startOne(userId, scopeId, blob, wrap = "Y292ZXItd3JhcA==", ownerId = memory, role = "COVER", meta = "Y292ZXItbWV0YQ==")

        // commit the upload as the node's standalone cover — the ref echoes the declared shape + wrap verbatim
        val blobId = given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"uploadId":"$uploadId"}""")
            .`when`().post("/api/scopes/$scopeId/nodes/$memory/cover-blob")
            .then().statusCode(200)
            .body("blobId", notNullValue())
            .body("chunkCount", equalTo(1))
            .body("byteSize", equalTo(blob.byteSize.toInt()))
            .body("wrappedBlobKey", equalTo("Y292ZXItd3JhcA==")) // operator-blind: the K_blob wrap round-trips
            .body("wrappedBlobMeta", equalTo("Y292ZXItbWV0YQ==")) // and so does the sealed blob meta the upload declared
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

        // operator-blind: the attachment row holds only the slot + blob id — never the wrap (it lives on the blob) or the
        // crop; it is a member's (no guest identity), and the size is the blob row's
        QuarkusTransaction.requiringNew().run {
            val cover = NodeAttachmentEntity.find("nodeId = ?1 and slot = ?2", memory, MemoryBlobRoles.COVER).singleResult()
            assertEquals(blobId, cover.blobId)
            assertEquals(scopeId, cover.scopeId)
            assertNull(cover.shareIdentityId)
            assertEquals(blob.byteSize, BlobEntity.findById(blobId)!!.byteSize)
        }

        // a cover upload that declared no meta answers null in the same slot
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"uploadId":"${startOne(userId, scopeId, TestBlob.SMALL, wrap = "c2Vjb25k", ownerId = memory, role = "COVER")}"}""")
            .`when`().post("/api/scopes/$scopeId/nodes/$memory/cover-blob")
            .then().statusCode(200).body("wrappedBlobMeta", nullValue())
    }

    @Test
    fun replaceReclaimsPriorCoverBlob() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        val first = commitCover(userId, scopeId, memory, startOne(userId, scopeId, TestBlob.SMALL, "Zmlyc3Q=", memory, "COVER"))

        // the key the replace must not lose — read while the row that holds it still exists
        val firstStorageKey = QuarkusTransaction.requiringNew().call { BlobEntity.findById(first)!!.storageKey }

        val second = commitCover(userId, scopeId, memory, startOne(userId, scopeId, TestBlob.ofChunks(2), "c2Vjb25k", memory, "COVER"))

        // the superseded first blob is reclaimed (gone); the new one is authorized
        assertEquals("upload_not_found", downloadError(userId, scopeId, first))
        assertEquals("store_unavailable", downloadError(userId, scopeId, second))
        QuarkusTransaction.requiringNew().run {
            assertEquals(second, coverOf(memory))
            assertEquals(1L, NodeAttachmentEntity.count("nodeId", memory), "one slot, one row: the replace swapped it")
        }
        assert(QuarkusTransaction.requiringNew().call { StoreReclaimEntity.count("storageKey", firstStorageKey) } == 1L) {
            "commitCover must leave a durable reclaim row for the cover blob it superseded"
        }
    }

    @Test
    fun deleteCoverBlobDetachesAndReclaims() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        val blobId = commitCover(userId, scopeId, memory, startOne(userId, scopeId, TestBlob.SMALL, "ZGVs", memory, "COVER"))

        // the key the delete must not lose — read while the row that holds it still exists
        val storageKey = QuarkusTransaction.requiringNew().call { BlobEntity.findById(blobId)!!.storageKey }

        given().header("Authorization", bearer(userId))
            .`when`().delete("/api/scopes/$scopeId/nodes/$memory/cover-blob?blobId=$blobId")
            .then().statusCode(204)

        assertEquals("upload_not_found", downloadError(userId, scopeId, blobId)) // reclaimed
        QuarkusTransaction.requiringNew().run {
            assertNull(coverOf(memory))
            assertEquals(0L, NodeAttachmentEntity.count("nodeId", memory), "the attachment row went with the blob")
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
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        val mine = commitCover(userId, scopeId, memory, startOne(userId, scopeId, TestBlob.SMALL, "bWluZQ==", memory, "COVER"))
        val peers = commitCover(userId, scopeId, memory, startOne(userId, scopeId, TestBlob.SMALL, "cGVlcg==", memory, "COVER"))

        given().header("Authorization", bearer(userId))
            .`when`().delete("/api/scopes/$scopeId/nodes/$memory/cover-blob?blobId=$mine")
            .then().statusCode(409).body("code", equalTo("cover_replaced"))

        assertEquals("store_unavailable", downloadError(userId, scopeId, peers), "the peer's upload was reclaimed")
        QuarkusTransaction.requiringNew().run {
            assertEquals(peers, coverOf(memory), "the delete cleared the peer's cover from the slot")
            assertNotNull(BlobEntity.findById(peers))
        }

        // Control: naming the blob the slot holds detaches and reclaims it.
        given().header("Authorization", bearer(userId))
            .`when`().delete("/api/scopes/$scopeId/nodes/$memory/cover-blob?blobId=$peers")
            .then().statusCode(204)
        assertEquals("upload_not_found", downloadError(userId, scopeId, peers))
        QuarkusTransaction.requiringNew().run { assertNull(coverOf(memory)) }
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
            val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
            val mine = commitCover(userId, scopeId, memory, startOne(userId, scopeId, TestBlob.SMALL, "bWluZQ==", memory, "COVER"))
            val upload = startOne(userId, scopeId, TestBlob.SMALL, "cGVlcg==", memory, "COVER")
            var peers: String? = null
            val outcome = whileUncommitted(
                change = {
                    val session = blobs.findSession(scopeId, upload, UploadOpener.Member(userId))!!
                    peers = (store.commitCover(scopeId, memory, session, null) as AttachOutcome.Attached).blobId
                },
                overlapping = { handler.deleteCover(userId, scopeId, memory, mine) },
            )
            assertEquals(CoverDeleteOutcome.Replaced, outcome, "round $round")
            assertEquals("store_unavailable", downloadError(userId, scopeId, peers!!), "round $round: the peer's upload was reclaimed")
            QuarkusTransaction.requiringNew().run {
                assertEquals(peers, coverOf(memory), "round $round: the delete cleared the peer's cover")
            }
        }
    }

    /**
     * A commit reclaims the blob the slot holds when it writes, not the one it saw before: a peer's commit in flight
     * holds the scope row (the lock every attachment writer takes first, inbox #341; the node row before it) and
     * reclaims the old cover itself. Read before the wait, the commit deleted that old row a
     * second time, found none, and answered 500 (inbox #310). Locked first, it reclaims the peer's blob and takes the
     * slot; each blob is reclaimed once.
     */
    @Test
    fun aCommitOverlappingAPeersCommitReclaimsThePeersCover() {
        repeat(RACE_ROUNDS) { round ->
            val (userId, scopeId) = seedMemberScope()
            val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
            val old = commitCover(userId, scopeId, memory, startOne(userId, scopeId, TestBlob.SMALL, "b2xk", memory, "COVER"))
            val peerUpload = startOne(userId, scopeId, TestBlob.SMALL, "cGVlcg==", memory, "COVER")
            val mineUpload = startOne(userId, scopeId, TestBlob.SMALL, "bWluZQ==", memory, "COVER")
            var peers: String? = null
            val outcome = whileUncommitted(
                change = {
                    val session = blobs.findSession(scopeId, peerUpload, UploadOpener.Member(userId))!!
                    peers = (store.commitCover(scopeId, memory, session, null) as AttachOutcome.Attached).blobId
                },
                overlapping = { handler.commitCover(userId, scopeId, memory, mineUpload) },
            )
            val mine = assertInstanceOf(CoverCommitOutcome.Ok::class.java, outcome, "round $round").ref.blobId
            QuarkusTransaction.requiringNew().run {
                assertEquals(mine, coverOf(memory), "round $round: the later commit holds the slot")
            }
            assertEquals("store_unavailable", downloadError(userId, scopeId, mine), "round $round")
            assertEquals("upload_not_found", downloadError(userId, scopeId, peers!!), "round $round: the peer's blob leaked")
            assertEquals("upload_not_found", downloadError(userId, scopeId, old), "round $round")
        }
    }

    /**
     * The same read-before-lock against a delete: the delete in flight holds the scope row and reclaims the cover, and
     * the commit, having read that cover before the wait, deleted its row again and answered 500 (inbox #310). Locked
     * first, it finds the slot empty and only attaches its own.
     */
    @Test
    fun aCommitOverlappingADeleteFindsTheSlotEmpty() {
        repeat(RACE_ROUNDS) { round ->
            val (userId, scopeId) = seedMemberScope()
            val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
            val old = commitCover(userId, scopeId, memory, startOne(userId, scopeId, TestBlob.SMALL, "b2xk", memory, "COVER"))
            val oldStorageKey = QuarkusTransaction.requiringNew().call { BlobEntity.findById(old)!!.storageKey }
            val mineUpload = startOne(userId, scopeId, TestBlob.SMALL, "bWluZQ==", memory, "COVER")
            val outcome = whileUncommitted(
                change = { assertEquals(AttachmentStore.DetachOutcome.Detached, store.clearCover(scopeId, memory, old)) },
                overlapping = { handler.commitCover(userId, scopeId, memory, mineUpload) },
            )
            val mine = assertInstanceOf(CoverCommitOutcome.Ok::class.java, outcome, "round $round").ref.blobId
            QuarkusTransaction.requiringNew().run {
                assertEquals(mine, coverOf(memory), "round $round")
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
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        val blobId = commitCover(userId, scopeId, memory, startOne(userId, scopeId, TestBlob.SMALL, "bm9kZQ==", memory, "COVER"))

        given().header("Authorization", bearer(userId))
            .`when`().delete("/api/scopes/$scopeId/nodes/$memory")
            .then().statusCode(204)

        // `store_unavailable` = the blob row is still there and authorization passed (only the transport is
        // absent on the hermetic store); `upload_not_found` would mean the row was reclaimed.
        assertEquals("store_unavailable", downloadError(userId, scopeId, blobId))
    }

    /**
     * A share's guest reads the shared Memory's cover — through core's attachment arm of the blob gate since inbox
     * #341, where `SharingStore` read `veyq_node.cover_blob_id` itself before — and never a cover beside the shared
     * subtree; a detached cover is no longer served. `503 store_unavailable` is "authorized" on the hermetic store.
     */
    @Test
    fun aShareGuestReadsTheSharedMemorysCoverOnly() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        val beside = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        val cover = commitCover(userId, scopeId, memory, startOne(userId, scopeId, TestBlob.SMALL, "c2hhcmVk", memory, "COVER"))
        val besideCover = commitCover(userId, scopeId, beside, startOne(userId, scopeId, TestBlob.SMALL, "YmVzaWRl", beside, "COVER"))
        val shareId = given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{${ShareWriteProof.newShare()},"nodeId":"$memory","role":"READ",${shareKeysJson("READ", memory, "d3JhcA==")},"sealedName":"c2VhbGVk",${ShareWriteProof.recovery()}}""")
            .`when`().post("/api/shares")
            .then().statusCode(201).extract().path<String>("shareId")

        given().`when`().get("/api/shares/$shareId/blobs/$cover")
            .then().statusCode(503).body("code", equalTo("store_unavailable"))
        given().`when`().get("/api/shares/$shareId/blobs/$besideCover")
            .then().statusCode(404).body("code", equalTo("share_not_found"))

        given().header("Authorization", bearer(userId))
            .`when`().delete("/api/scopes/$scopeId/nodes/$memory/cover-blob?blobId=$cover")
            .then().statusCode(204)
        given().`when`().get("/api/shares/$shareId/blobs/$cover")
            .then().statusCode(404).body("code", equalTo("share_not_found"))
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
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        val other = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
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
            assertNull(coverOf(memory), "nothing was attached as the cover")
        }
    }

    @Test
    fun coverOnFolderIsIllegalTarget() {
        val (userId, scopeId) = seedMemberScope()
        val folder = seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
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
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
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
        val memoryB = seedNode(scopeB, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
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
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
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
        ownerId: String = UUID.randomUUID().toString(), role: String = "ORIGINAL", meta: String? = null,
    ): String =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"items":[${blob.declare(wrap, ownerId = ownerId, role = role, meta = meta)}]}""")
            .`when`().post("/api/scopes/$scopeId/blob-uploads-batch")
            .then().statusCode(200).body("results[0].uploadId", notNullValue())
            .extract().path("results[0].uploadId")

    /** Commit an upload as the node's cover and return the committed blob id. */
    /** The node's cover blob as committed now — its `COVER` attachment's blob, or null. */
    private fun coverOf(nodeId: String): String? = store.coverBlobId(nodeId)

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
