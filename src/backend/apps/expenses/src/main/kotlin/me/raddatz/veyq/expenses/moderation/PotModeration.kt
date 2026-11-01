package me.raddatz.veyq.expenses.moderation

import me.raddatz.veyq.core.applicationblob.ApplicationBlobs
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
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.LockModeType
import jakarta.transaction.Transactional

/** The table a hold copies of a Pot, as [HoldSnapshot] names it (lower case, as stored). */
internal object HeldRevisions {
    const val TABLE = "veyq_expenses_revision"
}

/**
 * An Expenses entry as a reportable item (uc.moderation.report, ADR 0108 decision 6): the record — every revision of
 * it, live or a tombstone — by its entry id, on its Pot. A report reaches the whole entry: the report share carries every
 * revision's `K_rev` and every receipt's `K_blob`, and the hold copies every revision of it ([PotHoldSnapshot]) and every
 * receipt it holds (core's blob service, whose hold copies the blobs an owner holds). A tombstoned entry is still in its
 * Pot's deleted list, so it is what the reporter sees. A content block on it refuses its receipts' bytes ([blobIdsOf]:
 * the service's blobs of the entry, ADR 0112 decision 2). A receipt on its own is the service's item
 * (`ApplicationBlobReportableItems`) — never the same id as an entry's: the service refuses a record's id for a receipt,
 * an append a receipt's for a record.
 *
 * A block or a lift moves the Pot's list once ([blockChanged]), so an open Pot re-reads its receipts with their new
 * `blockedByOperator`; a block on an entry ends with its last revision.
 */
@ApplicationScoped
class PotReportableItems(private val items: NodeItemsRevisions, private val receipts: ApplicationBlobs) : ReportableItems {
    override fun locate(itemId: String): ReportableItem? =
        PotRevisionEntity.find("entryId", itemId).firstResult()?.let { ReportableItem(it.scopeId, it.nodeId) }

    /** An entry's receipts' blobs — released or not (a content block refuses them all); none for an id that is no entry. */
    override fun blobIdsOf(itemId: String): Set<String> =
        if (PotRevisionEntity.count("entryId", itemId) == 0L) emptySet() else receipts.blobIdsOfOwner(itemId)

    /** The receipts' list carries the block mark: the Pot's list moves, so an open Pot re-reads it (ADR 0033). */
    @Transactional
    override fun blockChanged(itemId: String) {
        val revision = PotRevisionEntity.find("entryId", itemId).firstResult() ?: return
        items.bump(revision.scopeId, revision.nodeId)
    }

    /** The item's rows, locked as its erasers lock them: an entry's revision rows (`FOR UPDATE`). */
    override fun lockExisting(itemId: String): Boolean =
        PotRevisionEntity.find("entryId = ?1 order by revision", itemId).withLock(LockModeType.PESSIMISTIC_WRITE).list().isNotEmpty()

    /** The held entry's receipts' stored objects, through their held blob rows — none for an id the hold holds no
     *  revision of. */
    override fun heldObjectsOf(itemId: String, held: List<HeldRow>): Set<String> =
        if (held.none { it.table == HeldRevisions.TABLE && it["entry_id"] == itemId }) emptySet() else receipts.heldObjectsOfOwner(itemId, held)
}

/**
 * Expenses' rows of a hold (en.moderation.hold): every revision of every Pot of a held subtree (a trashed Pot's too —
 * still the space's until purged), every revision of a reported entry, or every revision of the entries an uploader wrote
 * a revision of (a moderator's `UPLOADER_CONTENT` step). A reported link or name holds none. The receipts are core's blob
 * service's, which copies the blobs of the same targets (a reported entry's as their owner's). Every guest identity a
 * copied row names as its writer is copied by core. Phase 150: free between Memories' media (100) and the service's
 * (160); it reads and copies only.
 */
@ApplicationScoped
class PotHoldSnapshot : HoldSnapshotContributor {
    override val phase = 150

    override fun contribute(target: HoldTarget, snapshot: HoldSnapshot) {
        val revisions = when (target) {
            is HoldTarget.Subtree -> snapshot.copy(HeldRevisions.TABLE, "node_id", target.nodeIds)
            is HoldTarget.Item -> snapshot.copy(HeldRevisions.TABLE, "entry_id", listOf(target.itemId))
            is HoldTarget.Uploads -> snapshot.copy(HeldRevisions.TABLE, "entry_id", target.itemIds)
            is HoldTarget.Link, is HoldTarget.Name -> return
        }
        revisions.forEach { snapshot.identity(it["writer_identity_id"]) }
    }
}

/**
 * Expenses' half of [UploaderContent]: every entry of the space the uploader wrote a revision of — a member by its
 * account, a guest by its link identity — live, tombstoned and erased-into alike. A revision is the content a writer put
 * there; the entry is the item a block or a hold reaches. The receipts it completed are core's blob service's half.
 */
@ApplicationScoped
class PotUploaderContent : UploaderContent {
    override fun of(scopeId: String, uploader: Uploader): UploadedItems {
        val column = if (uploader.guest) "writerIdentityId" else "writerUserId"
        val entries = PotRevisionEntity.list("scopeId = ?1 and $column = ?2", scopeId, uploader.id).mapTo(LinkedHashSet()) { it.entryId }
        return UploadedItems(emptySet(), entries)
    }
}

/** Expenses' half of [HeldUploaders]: the members among the writers of the revisions a hold copied. */
@ApplicationScoped
class PotHeldUploaders : HeldUploaders {
    override fun membersAmong(rows: List<HeldRow>): Set<String> =
        rows.filter { it.table == HeldRevisions.TABLE }.mapNotNullTo(LinkedHashSet()) { it["writer_user_id"] }
}
