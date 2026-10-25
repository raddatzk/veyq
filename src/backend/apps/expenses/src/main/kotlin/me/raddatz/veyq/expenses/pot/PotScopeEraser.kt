package me.raddatz.veyq.expenses.pot

import me.raddatz.veyq.core.moderation.block.BlockKind
import me.raddatz.veyq.core.moderation.block.MeasureTargets
import me.raddatz.veyq.core.scope.ScopeEraser
import me.raddatz.veyq.expenses.receipt.PotReceiptEntity
import jakarta.enterprise.context.ApplicationScoped

/**
 * The Pots' revisions and receipts of an erased group (uc.spaces.delete-group). Phase 150: below
 * [ScopeEraser.BLOB_ROWS_PHASE], because `fk_expenses_receipt_blob` references `veyq_blob`, whose rows core deletes
 * there (`ScopePurge` records every blob's store objects for reclaim before any eraser runs, so this erases rows only),
 * and below core's share erasure (800), because both tables' identity FKs reference `veyq_share_identity`, whose rows
 * that one deletes. The guest-write records naming the rows go with the space's identities (`ShareScopeEraser`,
 * cascading); a content block on an entry or a receipt ends with it (ADR 0093 § 6).
 */
@ApplicationScoped
class PotScopeEraser(private val measures: MeasureTargets) : ScopeEraser {
    override val phase = 150

    override fun eraseScope(scopeId: String) {
        val entryIds = PotRevisionEntity.getEntityManager()
            .createQuery("select distinct r.entryId from PotRevisionEntity r where r.scopeId = ?1", String::class.java)
            .setParameter(1, scopeId).resultList
        val receiptIds = PotReceiptEntity.getEntityManager()
            .createQuery("select r.id from PotReceiptEntity r where r.scopeId = ?1", String::class.java)
            .setParameter(1, scopeId).resultList
        PotReceiptEntity.delete("scopeId", scopeId)
        PotRevisionEntity.delete("scopeId", scopeId)
        measures.erased(listOf(BlockKind.CONTENT_ITEM), entryIds + receiptIds)
    }
}
