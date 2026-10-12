package me.raddatz.veyq.core.content

import me.raddatz.veyq.core.blob.BlobEntity
import me.raddatz.veyq.core.blob.NodeAttachmentEntity
import me.raddatz.veyq.core.blob.UploadSessionEntity
import me.raddatz.veyq.core.blobstore.BlobStore
import me.raddatz.veyq.core.blobstore.BlobStore.Companion.chunkKeys
import me.raddatz.veyq.core.blobstore.RecordingBlobStore
import me.raddatz.veyq.core.blobstore.StoreReclaimEntity
import me.raddatz.veyq.core.blobstore.StoreReclaimSweep
import me.raddatz.veyq.core.blobstore.ageReclaimRowsPastThePresignBound
import me.raddatz.veyq.core.config.VeyqConfig
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.quota.StorageUsage
import me.raddatz.veyq.core.scope.ScopeEntity
import me.raddatz.veyq.core.testing.CoverKit
import me.raddatz.veyq.core.testing.RACE_ROUNDS
import me.raddatz.veyq.core.testing.TestBlob
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.core.testing.TestFixtures.seedUser
import me.raddatz.veyq.core.testing.race
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import java.time.Instant
import java.util.UUID
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.nullValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A node's cover with core alone (inbox #411): a `FOLDER` takes one through `setNodeCover` / `removeNodeCover`, the two
 * writes that put the blob in the node's `COVER` slot and the sealed meta that names it in ONE transaction. The
 * module-spanning kinds (a Memory, a drop box, a file) are pinned beside their applications.
 *
 * The owner's requirement (2026-10-12) is the first test: a cover set, replaced and removed leaves **nothing** - no
 * attachment, no blob, no session, the quota where it started, and a reclaim row for every blob's storage key that the
 * sweep, the only deleter (ADR 0062), then hands to the store. The hermetic store has no transport, so
 * `store_unavailable` is "the blob row exists" and `upload_not_found` "it is gone".
 */
@QuarkusTest
class NodeCoverCoreSliceTest {

    @Inject
    lateinit var usage: StorageUsage

    @Inject
    lateinit var nodes: NodesStore

    @Inject
    lateinit var sweep: StoreReclaimSweep

    @Inject
    lateinit var blobStore: BlobStore

    @Inject
    lateinit var config: VeyqConfig

    @Inject
    lateinit var purge: NodeSubtreePurge

    private val recorder: RecordingBlobStore get() = blobStore as RecordingBlobStore

    private fun folder(scopeId: String) = seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)

    private fun tenantOf(scopeId: String): String = QuarkusTransaction.requiringNew().call { ScopeEntity.findById(scopeId)!!.tenantId }

    private fun residue(scopeId: String): Triple<Long, Long, Long> = QuarkusTransaction.requiringNew().call {
        Triple(NodeAttachmentEntity.count("scopeId", scopeId), BlobEntity.count("scopeId", scopeId), UploadSessionEntity.count("scopeId", scopeId))
    }

    private fun reclaimRows(storageKey: String): Long = QuarkusTransaction.requiringNew().call { StoreReclaimEntity.count("storageKey", storageKey) }

    // --- the requirement: set -> replace -> remove leaves nothing behind ---

    @Test
    fun setThenReplaceThenRemoveLeavesNoRowNoBlobNoSessionAndTheQuotaWhereItStarted() {
        val (userId, scopeId) = seedMemberScope()
        val folder = folder(scopeId)
        val baseline = usage.tenantUsage(tenantOf(scopeId))
        val revisionBefore = nodes.treeRevision(scopeId)

        val first = CoverKit.start(userId, scopeId, folder, wrap = "Zmlyc3Q=", meta = "Zmlyc3QtbWV0YQ==")
        CoverKit.set(userId, scopeId, folder, first.uploadId, expectedBlobId = null, wrappedMeta = "bTE=", metaVersion = 0).statusCode(204)
        assertEquals(first.blobId, CoverKit.slot(folder))
        assertEquals("bTE=" to 1L, CoverKit.meta(folder), "the meta that names the cover landed in the same write")
        assertEquals("store_unavailable", CoverKit.downloadError(userId, scopeId, first.blobId))
        assertTrue(usage.tenantUsage(tenantOf(scopeId)) > baseline, "a cover counts against the quota like any attachment")
        assertEquals(Triple(1L, 1L, 0L), residue(scopeId), "one attachment, one blob, the session spent")

        val second = CoverKit.start(userId, scopeId, folder, blob = TestBlob.ofChunks(2), wrap = "c2Vjb25k")
        CoverKit.set(userId, scopeId, folder, second.uploadId, expectedBlobId = first.blobId, wrappedMeta = "bTI=", metaVersion = 1).statusCode(204)
        assertEquals(second.blobId, CoverKit.slot(folder))
        assertEquals("upload_not_found", CoverKit.downloadError(userId, scopeId, first.blobId), "the replaced blob's row is gone")
        assertEquals(1L, reclaimRows(first.storageKey), "a reclaim row for the replaced blob's storage key")
        assertEquals(Triple(1L, 1L, 0L), residue(scopeId), "still one slot, one blob")

        CoverKit.remove(userId, scopeId, folder, second.blobId, wrappedMeta = "bTM=", metaVersion = 2).statusCode(204)
        assertNull(CoverKit.slot(folder))
        assertEquals("bTM=" to 3L, CoverKit.meta(folder))
        assertEquals("upload_not_found", CoverKit.downloadError(userId, scopeId, second.blobId))
        assertEquals(1L, reclaimRows(second.storageKey), "a reclaim row for the removed blob's storage key")

        assertEquals(Triple(0L, 0L, 0L), residue(scopeId), "no attachment, no blob, no upload session")
        assertEquals(baseline, usage.tenantUsage(tenantOf(scopeId)), "the quota reads back to its starting value")
        assertTrue(nodes.treeRevision(scopeId) > revisionBefore, "every write moved the scope's tree revision (ADR 0081)")

        // The sweep is the only deleter: aged past the presign bound it hands EVERY chunk of both blobs to the store.
        ageReclaimRowsPastThePresignBound(config)
        recorder.reset()
        sweep.run()
        val handed = recorder.deletedKeys.toSet()
        assertTrue(handed.containsAll(chunkKeys(first.storageKey, TestBlob.SMALL.chunkCount)), "the replaced blob's objects reach the store: $handed")
        assertTrue(handed.containsAll(chunkKeys(second.storageKey, TestBlob.ofChunks(2).chunkCount)), "the removed blob's objects reach the store: $handed")
        assertEquals(0L, reclaimRows(first.storageKey) + reclaimRows(second.storageKey), "the settled rows are gone")
    }

    // --- admission ---

    @Test
    fun aKindNoRuleOptedInTakesNoCover() {
        val (userId, scopeId) = seedMemberScope()
        val alien = seedNode(scopeId, "ALIEN", wrappedContentKey = CONTENT_KEY)
        val started = CoverKit.start(userId, scopeId, alien)

        CoverKit.set(userId, scopeId, alien, started.uploadId, null).statusCode(422).body("code", equalTo("illegal_target"))
        assertEquals(Triple(0L, 0L, 1L), residue(scopeId), "nothing attached; the upload stays open")
        assertEquals(null to 0L, CoverKit.meta(alien), "the meta was not written either")
    }

    @Test
    fun anUploadNotStartedAsThisNodesCoverIsRefusedAndNothingIsWritten() {
        val (userId, scopeId) = seedMemberScope()
        val folder = folder(scopeId)
        val other = folder(scopeId)
        val forOther = CoverKit.start(userId, scopeId, folder, ownerId = other)

        CoverKit.set(userId, scopeId, folder, forOther.uploadId, null).statusCode(422).body("code", equalTo("upload_mismatch"))
        assertNull(CoverKit.slot(folder))
        assertEquals(null to 0L, CoverKit.meta(folder))
    }

    @Test
    fun aStrangerAnAnonymousCallerAndAnotherTenantsMemberReachNothing() {
        val (userId, scopeId) = seedMemberScope()
        val folder = folder(scopeId)
        val started = CoverKit.start(userId, scopeId, folder)
        val outsider = seedUser()
        val (otherMember, _) = seedMemberScope() // a member of ANOTHER tenant's scope

        for (stranger in listOf(outsider, otherMember)) {
            CoverKit.set(stranger, scopeId, folder, started.uploadId, null).statusCode(404).body("code", equalTo("node_not_found"))
            CoverKit.remove(stranger, scopeId, folder, started.blobId).statusCode(404).body("code", equalTo("node_not_found"))
        }
        given().contentType(ContentType.JSON)
            .body("""{"uploadId":"${started.uploadId}","wrappedMeta":"bQ==","metaVersion":0}""")
            .`when`().put("/api/scopes/$scopeId/nodes/$folder/cover").then().statusCode(401)
        assertNull(CoverKit.slot(folder))
    }

    @Test
    fun anotherMembersOpenUploadIsNotFound() {
        val (userId, scopeId) = seedMemberScope()
        val folder = folder(scopeId)
        val started = CoverKit.start(userId, scopeId, folder)
        val (otherMember, otherScope) = seedMemberScope()
        // the upload is in another scope for another member: no reach across scopes
        CoverKit.set(otherMember, otherScope, seedNode(otherScope, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY), started.uploadId, null)
            .statusCode(404)
        assertNull(CoverKit.slot(folder))
    }

    @Test
    fun aSpacePendingDeletionIsReadOnlyForACover() {
        val (userId, scopeId) = seedMemberScope()
        val folder = folder(scopeId)
        val started = CoverKit.start(userId, scopeId, folder)
        QuarkusTransaction.requiringNew().run { ScopeEntity.findById(scopeId)!!.deletedAt = Instant.now() }

        CoverKit.set(userId, scopeId, folder, started.uploadId, null).statusCode(409).body("code", equalTo("scope_deleting"))
        CoverKit.remove(userId, scopeId, folder, started.blobId).statusCode(409).body("code", equalTo("scope_deleting"))
        assertNull(CoverKit.slot(folder))
    }

    // --- compare-and-set, all or nothing ---

    @Test
    fun aSlotAnotherWriterFilledIsCoverStaleAndTheLoserIsFreed() {
        val (userId, scopeId) = seedMemberScope()
        val folder = folder(scopeId)
        val mine = CoverKit.start(userId, scopeId, folder)
        CoverKit.set(userId, scopeId, folder, mine.uploadId, null, "bTE=", 0).statusCode(204)
        val loser = CoverKit.start(userId, scopeId, folder)

        // the loser believes the slot is empty
        CoverKit.set(userId, scopeId, folder, loser.uploadId, expectedBlobId = null, wrappedMeta = "bG9zZXI=", metaVersion = 1)
            .statusCode(409).body("code", equalTo("cover_stale")).body("currentBlobId", equalTo(mine.blobId))
        assertEquals(mine.blobId, CoverKit.slot(folder), "the old cover is intact")
        assertEquals("bTE=" to 1L, CoverKit.meta(folder), "and so is the meta that names it")
        assertEquals(Triple(1L, 1L, 0L), residue(scopeId), "the loser's upload is spent and its blob was never kept")
        assertEquals(1L, reclaimRows(loser.storageKey), "the loser's objects are on the reclaim list - no orphan")
    }

    @Test
    fun aMetaThatMovedWritesNothingAndKeepsTheUploadOpenForTheRetry() {
        val (userId, scopeId) = seedMemberScope()
        val folder = folder(scopeId)
        val old = CoverKit.start(userId, scopeId, folder)
        CoverKit.set(userId, scopeId, folder, old.uploadId, null, "bTE=", 0).statusCode(204)
        val next = CoverKit.start(userId, scopeId, folder)

        // built on version 0 while the node is at 1: the WHOLE write is refused, the slot swap included
        CoverKit.set(userId, scopeId, folder, next.uploadId, expectedBlobId = old.blobId, wrappedMeta = "bTI=", metaVersion = 0)
            .statusCode(409).body("code", equalTo("stale_version")).body("wrappedMeta", equalTo("bTE=")).body("metaVersion", equalTo(1))
        assertEquals(old.blobId, CoverKit.slot(folder), "a failed new cover leaves the OLD one intact (no half state)")
        assertEquals("bTE=" to 1L, CoverKit.meta(folder))
        assertEquals("store_unavailable", CoverKit.downloadError(userId, scopeId, old.blobId), "the old blob was not freed")
        assertEquals(0L, reclaimRows(old.storageKey), "and nothing of it is on the reclaim list")
        assertEquals(Triple(1L, 1L, 1L), residue(scopeId), "the new upload is still open")

        // the retry on the version the 409 handed back lands
        CoverKit.set(userId, scopeId, folder, next.uploadId, expectedBlobId = old.blobId, wrappedMeta = "bTI=", metaVersion = 1).statusCode(204)
        assertEquals(next.blobId, CoverKit.slot(folder))
        assertEquals(Triple(1L, 1L, 0L), residue(scopeId))
    }

    @Test
    fun aRemovalNamingAReplacedCoverIsRefusedAndTheReplacementKept() {
        val (userId, scopeId) = seedMemberScope()
        val folder = folder(scopeId)
        val mine = CoverKit.start(userId, scopeId, folder)
        CoverKit.set(userId, scopeId, folder, mine.uploadId, null, "bTE=", 0).statusCode(204)
        val peers = CoverKit.start(userId, scopeId, folder)
        CoverKit.set(userId, scopeId, folder, peers.uploadId, mine.blobId, "bTI=", 1).statusCode(204)

        CoverKit.remove(userId, scopeId, folder, blobId = mine.blobId, wrappedMeta = "bTM=", metaVersion = 2)
            .statusCode(409).body("code", equalTo("cover_stale")).body("currentBlobId", equalTo(peers.blobId))
        assertEquals(peers.blobId, CoverKit.slot(folder))
        assertEquals("bTI=" to 2L, CoverKit.meta(folder))
    }

    @Test
    fun aRemovalWithAStaleMetaUndoesTheDetach() {
        val (userId, scopeId) = seedMemberScope()
        val folder = folder(scopeId)
        val mine = CoverKit.start(userId, scopeId, folder)
        CoverKit.set(userId, scopeId, folder, mine.uploadId, null, "bTE=", 0).statusCode(204)

        CoverKit.remove(userId, scopeId, folder, mine.blobId, wrappedMeta = "bTI=", metaVersion = 0)
            .statusCode(409).body("code", equalTo("stale_version"))
        assertEquals(mine.blobId, CoverKit.slot(folder), "the detach rolled back with the refused meta")
        assertEquals("store_unavailable", CoverKit.downloadError(userId, scopeId, mine.blobId))
        assertEquals(0L, reclaimRows(mine.storageKey))
    }

    @Test
    fun aRemovalIsIdempotentAndHealsAMetaThatNamedAGoneBlob() {
        val (userId, scopeId) = seedMemberScope()
        val folder = folder(scopeId)
        val mine = CoverKit.start(userId, scopeId, folder)
        CoverKit.set(userId, scopeId, folder, mine.uploadId, null, "bTE=", 0).statusCode(204)
        CoverKit.remove(userId, scopeId, folder, mine.blobId, "bTI=", 1).statusCode(204)
        // a replay: the slot is empty, the meta is written again at the version the client now holds
        CoverKit.remove(userId, scopeId, folder, mine.blobId, "bTM=", 2).statusCode(204)
        assertEquals("bTM=" to 3L, CoverKit.meta(folder))
        assertEquals(Triple(0L, 0L, 0L), residue(scopeId))
    }

    @Test
    fun twoWritersRacingForAnEmptySlotLeaveExactlyOneCover() {
        repeat(RACE_ROUNDS) { round ->
            val (userId, scopeId) = seedMemberScope()
            val folder = folder(scopeId)
            val a = CoverKit.start(userId, scopeId, folder)
            val b = CoverKit.start(userId, scopeId, folder)
            val statuses = race(
                { CoverKit.set(userId, scopeId, folder, a.uploadId, null, "YQ==", 0).extract().statusCode() },
                { CoverKit.set(userId, scopeId, folder, b.uploadId, null, "Yg==", 0).extract().statusCode() },
            )
            assertEquals(listOf(204, 409), statuses.sorted(), "round $round: one wins, the other is told")
            val winner = CoverKit.slot(folder)
            assertTrue(winner == a.blobId || winner == b.blobId, "round $round")
            assertEquals(Triple(1L, 1L, 0L), residue(scopeId), "round $round: one slot, one blob; the loser's was freed")
            assertEquals(1L, CoverKit.meta(folder).second, "round $round: the meta moved once")
        }
    }

    // --- trash and purge ---

    @Test
    fun aTrashedNodeKeepsItsCoverForTheRestoreAndThePurgeFreesIt() {
        val (userId, scopeId) = seedMemberScope()
        val folder = folder(scopeId)
        val started = CoverKit.start(userId, scopeId, folder)
        CoverKit.set(userId, scopeId, folder, started.uploadId, null, "bTE=", 0).statusCode(204)

        given().header("Authorization", bearer(userId)).`when`().delete("/api/scopes/$scopeId/nodes/$folder").then().statusCode(204)
        assertEquals("store_unavailable", CoverKit.downloadError(userId, scopeId, started.blobId), "the cover survives the soft delete")
        assertEquals(started.blobId, CoverKit.slot(folder))
        assertEquals(0L, reclaimRows(started.storageKey))

        // a set on a trashed node is a 404: it is not a live node
        val late = CoverKit.start(userId, scopeId, folder)
        CoverKit.set(userId, scopeId, folder, late.uploadId, started.blobId, "bTI=", 1).statusCode(404)

        // age the trash past the retention and run the purge: the attachment, the blob and the objects go (AttachmentNodeSubtreeEraser)
        val stale = Instant.now().minus(config.node().trash().retention()).minusSeconds(60)
        QuarkusTransaction.requiringNew().run { NodeEntity.findById(folder)!!.deletedAt = stale }
        purge.run()
        assertEquals("upload_not_found", CoverKit.downloadError(userId, scopeId, started.blobId))
        assertNull(CoverKit.slot(folder))
        assertEquals(1L, reclaimRows(started.storageKey), "the purge records the cover's objects for the sweep")
    }

    @Test
    fun theCoverIsServedToNobodyAfterRemovalEvenByBlobId() {
        val (userId, scopeId) = seedMemberScope()
        val folder = folder(scopeId)
        val started = CoverKit.start(userId, scopeId, folder)
        CoverKit.set(userId, scopeId, folder, started.uploadId, null, "bTE=", 0).statusCode(204)
        CoverKit.remove(userId, scopeId, folder, started.blobId, "bTI=", 1).statusCode(204)

        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"items":[{"blobId":"${started.blobId}"}]}""")
            .`when`().post("/api/scopes/$scopeId/blob-downloads-batch")
            .then().statusCode(200).body("results[0].presignedGets", nullValue()).body("results[0].error", equalTo("upload_not_found"))
    }

    @Test
    fun anUnknownNodeOrUploadIs404() {
        val (userId, scopeId) = seedMemberScope()
        val folder = folder(scopeId)
        val started = CoverKit.start(userId, scopeId, folder)
        CoverKit.set(userId, scopeId, UUID.randomUUID().toString(), started.uploadId, null).statusCode(404).body("code", equalTo("node_not_found"))
        CoverKit.set(userId, scopeId, folder, UUID.randomUUID().toString(), null).statusCode(404).body("code", equalTo("node_not_found"))
        CoverKit.remove(userId, scopeId, UUID.randomUUID().toString(), started.blobId).statusCode(404).body("code", equalTo("node_not_found"))
    }
}
