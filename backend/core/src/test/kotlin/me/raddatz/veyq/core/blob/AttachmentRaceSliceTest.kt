package me.raddatz.veyq.core.blob

import me.raddatz.veyq.core.blobstore.StoreReclaimEntity
import me.raddatz.veyq.core.content.NodesStore
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.spaces.scope.ScopePurge
import me.raddatz.veyq.core.testing.RACE_ROUNDS
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.core.testing.whileUncommitted
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * [AttachmentStore.attach] overlapping the writes it must serialize with (cr-files-application P3-S4), in the shape of
 * `NodeCoverSliceTest`'s cover races (inbox #308/#310): [whileUncommitted] holds one writer's commit back after its
 * locks are taken and runs the other inside that window, so the order is forced and one round already shows a defect.
 * `scripts/run_backend_races_on_mariadb.py` runs this class on the pinned MariaDB too (ADR 0078: H2 alone proved
 * nothing about overlap).
 *
 * What holds each race — the lever that turns it red, measured when it was written:
 * - two first attaches into one empty slot: the scope lock (without it the loser's insert dies at `ux_attachment_node_slot`);
 * - two replaces of one expected blob: the compare-and-set's `blob_id = expected` (without it the loser overwrites the
 *   winner's blob and leaves it attached to nothing);
 * - one upload attached twice: the spend as the first write (after the swap, the loser dies at the blob's primary key);
 * - an attach overlapping a trash of its node: the scope lock and the liveness read by query — under a caller's
 *   transaction that resolved the node before the lock, a `findById` hands back that live copy;
 * - an attach overlapping the group's purge: the spend (the purge deletes the scope's sessions first).
 */
@QuarkusTest
class AttachmentRaceSliceTest {

    @Inject lateinit var store: AttachmentStore
    @Inject lateinit var blobs: BlobUploadStore
    @Inject lateinit var uploads: BlobUploadHandler
    @Inject lateinit var nodes: NodesStore
    @Inject lateinit var scopePurge: ScopePurge

    @Test
    fun twoFirstAttachesIntoOneEmptySlotHaveOneWinnerAndAConflict() {
        repeat(RACE_ROUNDS) { round ->
            val (userId, scopeId) = seedMemberScope()
            val node = seedNode(scopeId, EXPENSE, wrappedContentKey = CONTENT_KEY)
            val peer = AttachmentUploads.open(blobs, userId, scopeId, node, SLOT)
            val mine = AttachmentUploads.open(blobs, userId, scopeId, node, SLOT)

            val outcome = whileUncommitted(
                change = { assertInstanceOf(AttachOutcome.Attached::class.java, attach(scopeId, node, peer, null)) },
                overlapping = { attach(scopeId, node, mine, null) },
            )

            assertEquals(AttachOutcome.Conflict(peer.blobId), outcome, "round $round")
            assertOneAttachment(node, winner = peer, loser = mine, round = round)
        }
    }

    @Test
    fun twoReplacesOfOneExpectedBlobHaveOneWinnerAndAConflict() {
        repeat(RACE_ROUNDS) { round ->
            val (userId, scopeId) = seedMemberScope()
            val node = seedNode(scopeId, EXPENSE, wrappedContentKey = CONTENT_KEY)
            val old = AttachmentUploads.open(blobs, userId, scopeId, node, SLOT)
            attach(scopeId, node, old, null)
            val peer = AttachmentUploads.open(blobs, userId, scopeId, node, SLOT)
            val mine = AttachmentUploads.open(blobs, userId, scopeId, node, SLOT)

            val outcome = whileUncommitted(
                change = { assertEquals(AttachOutcome.Attached(peer.blobId, old.blobId), attach(scopeId, node, peer, old.blobId)) },
                overlapping = { attach(scopeId, node, mine, old.blobId) },
            )

            assertEquals(AttachOutcome.Conflict(peer.blobId), outcome, "round $round")
            assertOneAttachment(node, winner = peer, loser = mine, round = round)
            QuarkusTransaction.requiringNew().run {
                assertNull(BlobEntity.findById(old.blobId), "round $round: the replaced blob went")
                assertEquals(1L, StoreReclaimEntity.count("storageKey", old.storageKey), "round $round: and is reclaimed once")
            }
        }
    }

    @Test
    fun oneUploadAttachedTwiceAtOnceAttachesOnceAndTheOtherIsUploadNotFound() {
        repeat(RACE_ROUNDS) { round ->
            val (userId, scopeId) = seedMemberScope()
            val node = seedNode(scopeId, EXPENSE, wrappedContentKey = CONTENT_KEY)
            val upload = AttachmentUploads.open(blobs, userId, scopeId, node, SLOT)

            val outcome = whileUncommitted(
                change = { assertInstanceOf(AttachOutcome.Attached::class.java, attach(scopeId, node, upload, null)) },
                overlapping = { attach(scopeId, node, upload, null) },
            )

            assertEquals(AttachOutcome.UploadNotFound, outcome, "round $round: answered as a replay, not a 500")
            QuarkusTransaction.requiringNew().run {
                assertEquals(upload.blobId, NodeAttachmentEntity.find("nodeId", node).singleResult().blobId, "round $round")
                assertEquals(0L, StoreReclaimEntity.count("storageKey", upload.storageKey), "round $round")
            }
        }
    }

    /**
     * The completion through the dispatcher, under a caller's transaction: the dispatcher resolves the node live while
     * the trash is uncommitted, so that copy sits in the transaction's persistence context when the attach reaches its
     * lock. The trash commits, the attach must see it and attach nothing: no attachment on a trashed node, no blob row
     * the trash's later purge would not find through an attachment.
     */
    @Test
    fun anAttachOverlappingATrashOfItsNodeAttachesNothing() {
        repeat(RACE_ROUNDS) { round ->
            val (userId, scopeId) = seedMemberScope()
            val node = seedNode(scopeId, EXPENSE, wrappedContentKey = CONTENT_KEY)
            val upload = AttachmentUploads.open(blobs, userId, scopeId, node, SLOT)
            val dispatcher = UploadCompletions(nodes, mutableListOf(ExpenseAttachmentCompletion(blobs, uploads, store)))

            val result = whileUncommitted(
                change = { assertEquals(true, nodes.deleteSubtree(scopeId, node)) },
                overlapping = {
                    inCallersTransaction {
                        dispatcher.complete(scopeId, listOf(ExpenseAttachmentCompletion.item(node, upload.session.id, null)), UploadOpener.Member(userId))
                    }.single()
                },
            )

            assertEquals(CompleteItemResult(null, null, "node_not_found"), result, "round $round")
            QuarkusTransaction.requiringNew().run {
                assertEquals(0L, NodeAttachmentEntity.count("nodeId", node), "round $round: nothing attached to the trashed node")
                assertNull(BlobEntity.findById(upload.blobId), "round $round: no blob row")
                assertNotNull(UploadSessionEntity.findById(upload.session.id), "round $round: the session expires as any unfinished one")
            }
        }
    }

    @Test
    fun anAttachOverlappingTheGroupsPurgeWritesNothing() {
        repeat(RACE_ROUNDS) { round ->
            val (userId, scopeId) = seedMemberScope()
            val node = seedNode(scopeId, EXPENSE, wrappedContentKey = CONTENT_KEY)
            val upload = AttachmentUploads.open(blobs, userId, scopeId, node, SLOT)

            val outcome = whileUncommitted(
                change = { scopePurge.purge(scopeId) },
                overlapping = { attach(scopeId, node, upload, null) },
            )

            assertEquals(AttachOutcome.UploadNotFound, outcome, "round $round: the purge spent the session first")
            QuarkusTransaction.requiringNew().run {
                assertNull(NodeEntity.findById(node), "round $round")
                assertEquals(0L, NodeAttachmentEntity.count("scopeId", scopeId), "round $round")
                assertNull(BlobEntity.findById(upload.blobId), "round $round: no orphan blob")
                assertEquals(1L, StoreReclaimEntity.count("storageKey", upload.storageKey), "round $round: the purge reclaimed the upload")
            }
        }
    }

    /** [body] in a transaction a caller holds: committed, or rolled back when the attach it joined refused (rollback-only). */
    private fun <T> inCallersTransaction(body: () -> T): T {
        QuarkusTransaction.begin()
        try {
            return body()
        } finally {
            if (QuarkusTransaction.isRollbackOnly()) QuarkusTransaction.rollback() else QuarkusTransaction.commit()
        }
    }

    private fun attach(scopeId: String, node: String, upload: AttachmentUploads.Opened, expected: String?): AttachOutcome =
        store.attach(scopeId, node, setOf(EXPENSE), SLOT, upload.session, null, expected)

    /** Exactly one attachment, holding [winner]'s blob; [loser]'s upload is no blob and its objects are reclaimed once. */
    private fun assertOneAttachment(node: String, winner: AttachmentUploads.Opened, loser: AttachmentUploads.Opened, round: Int) =
        QuarkusTransaction.requiringNew().run {
            assertEquals(winner.blobId, NodeAttachmentEntity.find("nodeId", node).singleResult().blobId, "round $round: one row, the winner's")
            assertNotNull(BlobEntity.findById(winner.blobId), "round $round")
            assertEquals(0L, StoreReclaimEntity.count("storageKey", winner.storageKey), "round $round")
            assertNull(BlobEntity.findById(loser.blobId), "round $round: the loser's blob is no row")
            assertEquals(1L, StoreReclaimEntity.count("storageKey", loser.storageKey), "round $round: its objects are reclaimed once")
        }

    private companion object {
        const val EXPENSE = ExpenseAttachmentCompletion.EXPENSE
        const val SLOT = ExpenseAttachmentCompletion.SLOT
    }
}
