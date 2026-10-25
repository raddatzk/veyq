package me.raddatz.veyq.expenses.moderation

import me.raddatz.veyq.core.items.NodeItemsRevisions
import me.raddatz.veyq.core.moderation.block.UploadedItems
import me.raddatz.veyq.core.moderation.block.Uploader
import me.raddatz.veyq.core.moderation.block.UploaderContent
import me.raddatz.veyq.core.moderation.hold.HeldRow
import me.raddatz.veyq.core.moderation.hold.HeldUploaders
import me.raddatz.veyq.core.moderation.hold.HoldSnapshot
import me.raddatz.veyq.core.moderation.hold.HoldSnapshotContributor
import me.raddatz.veyq.core.moderation.hold.HoldTarget
import me.raddatz.veyq.core.moderation.report.ReportableItem
import me.raddatz.veyq.core.moderation.report.ReportableItems
import me.raddatz.veyq.expenses.pot.PotRevisionEntity
import me.raddatz.veyq.expenses.receipt.PotReceiptEntity
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.LockModeType
import jakarta.transaction.Transactional

/** The tables a hold copies of a Pot, as [HoldSnapshot] names them (lower case, as stored). */
internal object HeldRevisions {
    const val TABLE = "veyq_expenses_revision"

    /** A Pot's receipts (ADR 0108 decision 7); each copied row's blob is copied and pinned by core. */
    const val RECEIPTS = "veyq_expenses_receipt"
}

/**
 * An Expenses item as a reportable one (uc.moderation.report, ADR 0108 decision 6) — two kinds of id, never the same id
 * (a receipt's completion refuses a record's id, an append a receipt's):
 *
 * - an **entry**: the record — every revision of it, live or a tombstone — by its entry id, on its Pot. A report reaches
 *   the whole entry: the report share carries every revision's `K_rev` and every receipt's `K_blob`, and the hold copies
 *   every revision and every receipt of it ([PotHoldSnapshot]). A tombstoned entry is still in its Pot's deleted list,
 *   so it is what the reporter sees. A content block on it refuses its receipts' bytes ([blobIdsOf]).
 * - a **receipt**, on its own (owner 2026-10-23): its row, by its receipt id; a block refuses its one blob.
 *
 * A block or a lift moves the Pot's list once ([blockChanged]), so an open Pot re-reads its receipts with their new
 * `blockedByOperator`; a block on an entry ends with its last revision, one on a receipt with the receipt's purge.
 */
@ApplicationScoped
class PotReportableItems(private val items: NodeItemsRevisions) : ReportableItems {
    override fun locate(itemId: String): ReportableItem? =
        PotRevisionEntity.find("entryId", itemId).firstResult()?.let { ReportableItem(it.scopeId, it.nodeId) }
            ?: PotReceiptEntity.findById(itemId)?.let { ReportableItem(it.scopeId, it.nodeId) }

    /** An entry's receipts' blobs, or a receipt's own — trashed, dropped or not (a content block refuses them all). */
    override fun blobIdsOf(itemId: String): Set<String> =
        PotReceiptEntity.list("entryId = ?1 or id = ?1", itemId).mapTo(LinkedHashSet()) { it.blobId }

    /** The receipts' list carries the block mark: the Pot's list moves, so an open Pot re-reads it (ADR 0033). */
    @Transactional
    override fun blockChanged(itemId: String) {
        val node = PotRevisionEntity.find("entryId", itemId).firstResult()?.let { it.scopeId to it.nodeId }
            ?: PotReceiptEntity.findById(itemId)?.let { it.scopeId to it.nodeId }
            ?: return
        items.bump(node.first, node.second)
    }

    /** The item's rows, locked as its erasers lock them: an entry's revision rows, a receipt's row (`FOR UPDATE`). */
    override fun lockExisting(itemId: String): Boolean =
        PotRevisionEntity.find("entryId = ?1 order by revision", itemId).withLock(LockModeType.PESSIMISTIC_WRITE).list().isNotEmpty() ||
            PotReceiptEntity.find("id", itemId).withLock(LockModeType.PESSIMISTIC_WRITE).firstResult() != null

    /** The held receipt's — or the held entry's receipts' — stored objects, through their held blob rows. */
    override fun heldObjectsOf(itemId: String, held: List<HeldRow>): Set<String> {
        val blobIds = held.filter { it.table == HeldRevisions.RECEIPTS && (it.sourceId == itemId || it["entry_id"] == itemId) }
            .mapNotNull { it["blob_id"] }
        if (blobIds.isEmpty()) return emptySet()
        return held.filter { it.table == "veyq_blob" && it.sourceId in blobIds }.mapNotNullTo(LinkedHashSet()) { it["storage_key"] }
    }
}

/**
 * Expenses' rows of a hold (en.moderation.hold): every revision and every receipt of every Pot of a held subtree (a
 * trashed Pot's too — still the space's until purged), every revision and every receipt of a reported entry, a reported
 * receipt, or every revision and receipt of the entries an uploader wrote a revision of plus every receipt it completed
 * (a moderator's `UPLOADER_CONTENT` step). A reported link or name holds none. Every receipt's blob is named, so core
 * copies its row and pins its storage key — the hold copies, it never blocks (ADR 0093). Every guest identity a copied
 * row names as its writer is copied by core. Phase 150: free between Memories' media (100) and core's own (200); it
 * reads and copies only.
 */
@ApplicationScoped
class PotHoldSnapshot : HoldSnapshotContributor {
    override val phase = 150

    override fun contribute(target: HoldTarget, snapshot: HoldSnapshot) {
        val (revisions, receipts) = when (target) {
            is HoldTarget.Subtree ->
                snapshot.copy(HeldRevisions.TABLE, "node_id", target.nodeIds) to snapshot.copy(HeldRevisions.RECEIPTS, "node_id", target.nodeIds)
            is HoldTarget.Item ->
                snapshot.copy(HeldRevisions.TABLE, "entry_id", listOf(target.itemId)) to snapshot.copy(HeldRevisions.RECEIPTS, "id", receiptsOf(listOf(target.itemId)))
            is HoldTarget.Uploads ->
                snapshot.copy(HeldRevisions.TABLE, "entry_id", target.itemIds) to snapshot.copy(HeldRevisions.RECEIPTS, "id", receiptsOf(target.itemIds))
            is HoldTarget.Link, is HoldTarget.Name -> return
        }
        revisions.forEach { snapshot.identity(it["writer_identity_id"]) }
        receipts.forEach {
            snapshot.blob(it["blob_id"])
            snapshot.identity(it["writer_identity_id"])
        }
    }

    /** The receipts [itemIds] name: a receipt itself, or every receipt of an entry. */
    private fun receiptsOf(itemIds: Collection<String>): List<String> =
        itemIds.distinct().chunked(500).flatMap { PotReceiptEntity.list("id in ?1 or entryId in ?1", it) }.map { it.id }.distinct()
}

/**
 * Expenses' half of [UploaderContent]: every entry of the space the uploader wrote a revision of, and every receipt it
 * completed — a member by its account, a guest by its link identity — live, tombstoned and dropped alike. A revision or a
 * receipt is the content a writer put there; the entry and the receipt are the items a block or a hold reaches.
 */
@ApplicationScoped
class PotUploaderContent : UploaderContent {
    override fun of(scopeId: String, uploader: Uploader): UploadedItems {
        val column = if (uploader.guest) "writerIdentityId" else "writerUserId"
        val entries = PotRevisionEntity.list("scopeId = ?1 and $column = ?2", scopeId, uploader.id).mapTo(LinkedHashSet()) { it.entryId }
        val receipts = PotReceiptEntity.list("scopeId = ?1 and $column = ?2", scopeId, uploader.id).map { it.id }
        return UploadedItems(emptySet(), entries + receipts)
    }
}

/** Expenses' half of [HeldUploaders]: the members among the writers of the revisions and receipts a hold copied. */
@ApplicationScoped
class PotHeldUploaders : HeldUploaders {
    override fun membersAmong(rows: List<HeldRow>): Set<String> =
        rows.filter { it.table == HeldRevisions.TABLE || it.table == HeldRevisions.RECEIPTS }.mapNotNullTo(LinkedHashSet()) { it["writer_user_id"] }
}
