package me.raddatz.veyq.expenses.pot

import me.raddatz.veyq.core.blob.BlobUploadStore
import me.raddatz.veyq.core.moderation.block.BlockKind
import me.raddatz.veyq.core.moderation.block.MeasureTargets
import me.raddatz.veyq.core.moderation.guestwrite.GuestWriteRecords
import me.raddatz.veyq.core.node.NodeSubtreeEraser
import me.raddatz.veyq.expenses.receipt.PotReceiptEntity
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.LockModeType

/**
 * The revisions and the receipts of the Pots of an erased node subtree (the trash purge, ADR 0061 decision 6) — a Pot's
 * deleted entries and its receipts live inside it and go with it. Phase 150: free between Memories' media (100) and
 * core's attachments (200), before core's counters (300). The rows are locked first, as every other writer of them locks
 * them (the record rows, then the receipts, before the node — and before the blob rows, which core's purge locks for
 * every eraser at once from [blobIdsOf]). The receipts' blob rows go through core's `BlobUploadStore.deleteBlobRows`,
 * which puts their store objects on the reclaim list in this transaction (ADR 0062). Returns the link identities that
 * wrote them, for the caller to drop once nothing else names them; the guest-write records naming the rows go with
 * them, and a content block on an entry or a receipt ends with it (ADR 0093 § 6).
 */
@ApplicationScoped
class PotNodeSubtreeEraser(
    private val guestWriteRecords: GuestWriteRecords,
    private val measures: MeasureTargets,
    private val blobs: BlobUploadStore,
) : NodeSubtreeEraser {
    override val phase = 150

    override fun erase(scopeId: String, nodeIds: List<String>): Set<String> {
        val rows = lockedRevisions(nodeIds)
        val receipts = lockedReceipts(nodeIds)
        if (rows.isEmpty() && receipts.isEmpty()) return emptySet()
        val guests = (rows.mapNotNull { it.writerIdentityId } + receipts.mapNotNull { it.writerIdentityId }).toSet()
        guestWriteRecords.forget(rows.map { it.id } + receipts.map { it.id })
        PotReceiptEntity.delete("nodeId in ?1", nodeIds)
        blobs.deleteBlobRows(scopeId, receipts.map { it.blobId }) // the receipt rows first: fk_expenses_receipt_blob
        PotRevisionEntity.delete("nodeId in ?1", nodeIds)
        measures.erased(listOf(BlockKind.CONTENT_ITEM), rows.map { it.entryId }.distinct() + receipts.map { it.id })
        return guests
    }

    /** The revision rows and the receipt rows locked first, as [erase] locks them (item rows before blob rows), and every
     *  receipt's blob. */
    override fun blobIdsOf(scopeId: String, nodeIds: List<String>): Collection<String> {
        lockedRevisions(nodeIds)
        return lockedReceipts(nodeIds).map { it.blobId }
    }

    private fun lockedRevisions(nodeIds: List<String>): List<PotRevisionEntity> =
        PotRevisionEntity.find("nodeId in ?1 order by entryId, revision", nodeIds).withLock(LockModeType.PESSIMISTIC_WRITE).list()

    private fun lockedReceipts(nodeIds: List<String>): List<PotReceiptEntity> =
        PotReceiptEntity.find("nodeId in ?1 order by id", nodeIds).withLock(LockModeType.PESSIMISTIC_WRITE).list()
}
