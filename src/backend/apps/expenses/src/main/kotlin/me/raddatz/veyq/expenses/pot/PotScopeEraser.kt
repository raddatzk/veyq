package me.raddatz.veyq.expenses.pot

import me.raddatz.veyq.core.moderation.block.BlockKind
import me.raddatz.veyq.core.moderation.block.MeasureTargets
import me.raddatz.veyq.core.scope.ScopeEraser
import jakarta.enterprise.context.ApplicationScoped

/**
 * The Pots' revisions of an erased group (uc.spaces.delete-group). Phase 150, below core's share erasure (800), because
 * `fk_expenses_revision_identity` references `veyq_share_identity`, whose rows that one deletes; the table names no blob,
 * so it does not care for [ScopeEraser.BLOB_ROWS_PHASE]. The guest-write records naming the rows go with the space's
 * identities (`ShareScopeEraser`, cascading); a content block on an entry ends with it (ADR 0093 § 6).
 */
@ApplicationScoped
class PotScopeEraser(private val measures: MeasureTargets) : ScopeEraser {
    override val phase = 150

    override fun eraseScope(scopeId: String) {
        val entryIds = PotRevisionEntity.getEntityManager()
            .createQuery("select distinct r.entryId from PotRevisionEntity r where r.scopeId = ?1", String::class.java)
            .setParameter(1, scopeId).resultList
        PotRevisionEntity.delete("scopeId", scopeId)
        measures.erased(listOf(BlockKind.CONTENT_ITEM), entryIds)
    }
}
