package me.raddatz.veyq.expenses.receipt

import me.raddatz.veyq.core.blob.BlobEntity
import me.raddatz.veyq.core.blob.BlobUploadStore
import me.raddatz.veyq.core.blob.ChunkFrames
import me.raddatz.veyq.core.blob.UploadSessionEntity
import me.raddatz.veyq.core.identity.PrincipalKey
import me.raddatz.veyq.core.items.NodeItemsRevisions
import me.raddatz.veyq.core.moderation.block.ContentView
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.node.rekey.NodeEpochGate
import me.raddatz.veyq.expenses.ExpensesNodeKinds
import me.raddatz.veyq.expenses.pot.PotRevisionEntity
import io.quarkus.narayana.jta.QuarkusTransaction
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.LockModeType
import jakarta.transaction.Transactional
import java.time.Duration
import java.time.Instant

/** Expenses' blob role — its value of the contract's open `BlobRole` token (16 characters, the column's limit). */
object ExpensesBlobRoles {
    /**
     * A receipt's bytes (ADR 0108 decision 7). The upload's `ownerId` is the Pot's node, so its `K_blob` wrap is sealed
     * bound to (blob, Pot, `EXPENSES_RECEIPT`) under the Pot's key — the shape of Depot's file content, with no new blob
     * purpose. Which entry holds it is the receipt row's, and the entry's sealed revisions list it by its receipt id.
     */
    const val RECEIPT = "EXPENSES_RECEIPT"

    /** At most 10 MiB of plaintext (uc.expenses.attach-receipts E1) … */
    const val RECEIPT_MAX_PLAINTEXT_BYTES = 10L * 1024 * 1024

    /** … which is this many sealed bytes: 40 full frames, each with its nonce and tag. The server sees the sealed size,
     *  so this is the cap it holds (`BlobUploadHandler.admissibility`, through [ExpensesMemberBlobRoles.maxBytes]). */
    val RECEIPT_MAX_SEALED_BYTES: Long =
        ((RECEIPT_MAX_PLAINTEXT_BYTES + ChunkFrames.PLAINTEXT_BYTES - 1) / ChunkFrames.PLAINTEXT_BYTES) * ChunkFrames.OVERHEAD +
            RECEIPT_MAX_PLAINTEXT_BYTES
}

/** What an attach of a receipt did. Nothing is stored on any answer but [Attached]. */
sealed interface ReceiptAttach {
    data class Attached(val receipt: PotReceiptEntity) : ReceiptAttach
    /** The caller's gate refused under its locks, or the session was spent by a concurrent completion. */
    object NotAdmitted : ReceiptAttach
    /** The receipt id is taken, or the entry is another Pot's record. */
    object IdTaken : ReceiptAttach
    /** No live Pot of that id in the scope. */
    object NotFound : ReceiptAttach
    /** The Pot's epoch refused the stated one: `stale_epoch` or `node_rekey_pending`; the session stays open. */
    data class EpochRefused(val code: String) : ReceiptAttach
}

/** A receipt as a read serves it: its row, its blob row, when the purge erases it, and whether a block reaches it. */
data class ReceiptRow(
    val receipt: PotReceiptEntity, val blob: BlobEntity, val manifest: List<Long>?, val purgeAfter: Instant?, val blocked: Boolean,
)

/**
 * A Pot's receipts (ADR 0108 decision 7, docs/apps/expenses.md § Receipts): attached by their completion
 * ([ReceiptUploadCompletion] → [attach]), dropped by the revision that names them ([me.raddatz.veyq.expenses.pot.PotRevisionStore.append]),
 * erased after the trash retention ([PotReceiptPurge]) or with the Pot and its space. Every writer calls core's
 * `items.bump` after the rows it changed, so an open Pot learns of the change by its `ledgerRevision`.
 *
 * **Lock order, every writer of a Pot:** (1) a guest's re-check under the scope and share locks (the completion's
 * `admitted`), or the scope row (the purge); (2) the named records' revision rows, `FOR UPDATE`; (3) the receipt rows;
 * (4) the Pot's node row ([NodeEpochGate.check]); (5) the blob rows (`StoredObjectLocks`, item rows before blob rows);
 * (6) the item-revision row ([NodeItemsRevisions.bump]). The append (`PotRevisionStore`) and the re-key
 * (`PotNodeRekeyParticipant`) take the same order.
 */
@ApplicationScoped
class PotReceiptStore(
    private val epochs: NodeEpochGate,
    private val items: NodeItemsRevisions,
    private val blobs: BlobUploadStore,
) {

    /**
     * Attach the verified [session] as the receipt [receiptId] of the record [entryId] of the Pot [nodeId], by [writer],
     * all or none: the Pot live and at [nodeEpoch], the record no other Pot's, the receipt id free, the session spent and
     * its blob row written, the receipt row inserted, the Pot's list moved. [admitted], a guest's re-check of its link and
     * target, runs first; [attached] is told the receipt id last, in this transaction (the guest-write record).
     */
    @Transactional
    fun attach(
        scopeId: String, nodeId: String, nodeEpoch: Int, receiptId: String, entryId: String, session: UploadSessionEntity,
        manifest: List<Long>?, writer: PrincipalKey, admitted: () -> Boolean, attached: (String) -> Unit,
    ): ReceiptAttach {
        if (!admitted()) return ReceiptAttach.NotAdmitted
        // (2) The record's revisions, so an append of its first revision into another Pot that holds them is seen.
        PotRevisionEntity.find("entryId = ?1 order by revision", entryId).withLock(LockModeType.PESSIMISTIC_WRITE).list()
        when (epochs.check(scopeId, nodeId, nodeEpoch)) {
            NodeEpochGate.Verdict.MISSING -> return ReceiptAttach.NotFound
            NodeEpochGate.Verdict.STALE -> return ReceiptAttach.EpochRefused(STALE_EPOCH)
            NodeEpochGate.Verdict.PENDING -> return ReceiptAttach.EpochRefused(NODE_REKEY_PENDING)
            NodeEpochGate.Verdict.OK -> Unit
        }
        val node = NodeEntity.findById(nodeId) ?: return ReceiptAttach.NotFound
        if (node.kind != ExpensesNodeKinds.POT || node.deletedAt != null) return ReceiptAttach.NotFound
        // Read under the node's lock, which every inserter of a revision or a receipt of this Pot holds: what it returns
        // is every row committed before. A record of another Pot is taken; so is an id that names a record or a receipt
        // already — a receipt id is a reportable item's id beside a record's (`PotReportableItems.locate`).
        val record = PotRevisionEntity.list("entryId", entryId)
        if (record.any { it.nodeId != nodeId }) return ReceiptAttach.IdTaken
        if (PotReceiptEntity.count("id", receiptId) > 0 || PotRevisionEntity.count("entryId", receiptId) > 0) return ReceiptAttach.IdTaken
        if (UploadSessionEntity.delete("id = ?1", session.id) != 1L) {
            QuarkusTransaction.setRollbackOnly() // spent by a concurrent completion that committed first
            return ReceiptAttach.NotAdmitted
        }
        val blobId = blobs.persistBlob(session, manifest)
        val row = PotReceiptEntity().apply {
            id = receiptId
            this.scopeId = scopeId
            this.nodeId = nodeId
            this.entryId = entryId
            this.blobId = blobId
            writerUserId = writer.userId
            writerIdentityId = writer.identityId
            createdAt = Instant.now()
        }
        row.persist()
        items.bump(scopeId, nodeId) // after the receipt row: record rows -> node row -> revision row
        attached(receiptId)
        return ReceiptAttach.Attached(row)
    }

    // --- reads ---------------------------------------------------------------------------------------------------

    /** Every receipt of the Pot [nodeId], in id order as text (a UUID column does not sort as its text). */
    fun allOf(nodeId: String): List<PotReceiptEntity> = PotReceiptEntity.list("nodeId", nodeId).sortedBy { it.id }

    /**
     * [receipts] as a read serves them: each with its blob row, its derived purge time under [retention], and whether a
     * content block in [view] reaches it — itself, its entry or its Pot (ADR 0093 § 6, ADR 0108 decision 7).
     * A receipt whose blob row is gone is left out. A constant number of queries per call.
     */
    fun rows(receipts: List<PotReceiptEntity>, retention: Duration, view: ContentView): List<ReceiptRow> {
        if (receipts.isEmpty()) return emptyList()
        val blobRows = receipts.map { it.blobId }.chunked(CHUNK).flatMap { BlobEntity.list("id in ?1", it) }.associateBy { it.id }
        val latest = latestRevisions(receipts.map { it.entryId })
        return receipts.mapNotNull { r ->
            val blob = blobRows[r.blobId] ?: return@mapNotNull null
            val blocked = view.itemBlocked(r.id, r.nodeId) || r.entryId in view.itemIds
            ReceiptRow(r, blob, blobs.decodeManifest(blob.manifest), purgeAfter(r, latest[r.entryId]?.takeIf { it.nodeId == r.nodeId }, retention), blocked)
        }
    }

    /** The latest revision of each record of [entryIds] (none for a record without one). */
    fun latestRevisions(entryIds: Collection<String>): Map<String, PotRevisionEntity> =
        entryIds.distinct().chunked(CHUNK).flatMap { PotRevisionEntity.list("entryId in ?1", it) }
            .groupBy { it.entryId }.mapValues { (_, rows) -> rows.maxBy { it.revision } }

    /** The receipts of the Pot [nodeId] past [after] in id order, at most [limit] — the re-key's listing. */
    fun pageById(nodeId: String, after: String?, limit: Int): List<PotReceiptEntity> =
        allOf(nodeId).let { all -> if (after == null) all else all.dropWhile { it.id <= after } }.take(limit)

    companion object {
        private const val CHUNK = 500

        /** The receipt's error when the Pot's stored epoch is not the stated one (`409 stale_epoch`). */
        const val STALE_EPOCH = "stale_epoch"

        /** The receipt's error when the Pot is not re-keyed since the scope's last rotation (`409 node_rekey_pending`). */
        const val NODE_REKEY_PENDING = "node_rekey_pending"

        /**
         * When the purge erases [receipt] (docs/apps/expenses.md § Receipts): its drop plus the [retention]; else,
         * while its record's [latest] revision is a tombstone, that tombstone's time plus it; else, while the record has
         * no revision, the receipt's own time plus it; null while a live record holds it. Derived, never stored, so it
         * follows a reconfigured retention (ADR 0061 decision 6) — and the purge decides by the same rule ([PotReceiptPurge]).
         */
        fun purgeAfter(receipt: PotReceiptEntity, latest: PotRevisionEntity?, retention: Duration): Instant? = when {
            receipt.droppedAt != null -> receipt.droppedAt!!.plus(retention)
            latest == null -> receipt.createdAt.plus(retention)
            latest.tombstone -> latest.createdAt.plus(retention)
            else -> null
        }
    }
}
