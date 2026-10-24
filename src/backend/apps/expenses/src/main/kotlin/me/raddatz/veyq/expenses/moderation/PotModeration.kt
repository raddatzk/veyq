package me.raddatz.veyq.expenses.moderation

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

/** The table and columns a hold copies of a Pot's revisions, as [HoldSnapshot] names them (lower case, as stored). */
internal object HeldRevisions {
    const val TABLE = "veyq_expenses_revision"
}

/**
 * An Expenses **entry** as a reportable item (uc.moderation.report, CR decision 7): the record — every revision of it,
 * live or a tombstone — by its entry id, on its Pot. A report reaches the whole entry (ADR 0108 decision 6): the report
 * share carries every revision's `K_rev`, and the hold copies every revision ([PotHoldSnapshot]). A tombstoned entry is
 * still in its Pot's deleted list, so it is what the reporter sees. No blob: its receipts join with
 * `cr-expenses-receipts`, which also gives a content block on an entry something to refuse; until then a block stands on
 * the entry as a record and ends with its last revision ([me.raddatz.veyq.expenses.pot.PotNodeSubtreeEraser]).
 */
@ApplicationScoped
class PotReportableItems : ReportableItems {
    override fun locate(itemId: String): ReportableItem? =
        PotRevisionEntity.find("entryId", itemId).firstResult()?.let { ReportableItem(it.scopeId, it.nodeId) }

    override fun blobIdsOf(itemId: String): Set<String> = emptySet()

    /** The Pot's list carries no block mark, so a block or a lift changes nothing it serves. */
    override fun blockChanged(itemId: String) {}

    /** The entry's rows, locked as its erasers lock them (the record rows, `FOR UPDATE`). */
    override fun lockExisting(itemId: String): Boolean =
        PotRevisionEntity.find("entryId = ?1 order by revision", itemId).withLock(LockModeType.PESSIMISTIC_WRITE).list().isNotEmpty()

    override fun heldObjectsOf(itemId: String, held: List<HeldRow>): Set<String> = emptySet()
}

/**
 * Expenses' rows of a hold (en.moderation.hold): every revision of every Pot of a held subtree (a trashed Pot's too —
 * still the space's until purged), every revision of a reported entry, or every revision of the entries an uploader
 * wrote a revision of (a moderator's `UPLOADER_CONTENT` step). A reported link or name holds none. Every guest identity
 * a copied revision names as its writer is copied by core. Phase 150: free between Memories' media (100) and core's
 * own (200); it reads and copies only.
 */
@ApplicationScoped
class PotHoldSnapshot : HoldSnapshotContributor {
    override val phase = 150

    override fun contribute(target: HoldTarget, snapshot: HoldSnapshot) {
        val rows = when (target) {
            is HoldTarget.Subtree -> snapshot.copy(HeldRevisions.TABLE, "node_id", target.nodeIds)
            is HoldTarget.Item -> snapshot.copy(HeldRevisions.TABLE, "entry_id", listOf(target.itemId))
            is HoldTarget.Uploads -> snapshot.copy(HeldRevisions.TABLE, "entry_id", target.itemIds)
            is HoldTarget.Link, is HoldTarget.Name -> return
        }
        rows.forEach { snapshot.identity(it["writer_identity_id"]) }
    }
}

/**
 * Expenses' half of [UploaderContent]: every entry of the space the uploader wrote a revision of — a member by its
 * account, a guest by its link identity — live and tombstoned alike. A revision is the content a writer put there; the
 * entry is the item a block or a hold reaches (the whole entry, ADR 0108 decision 6).
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
