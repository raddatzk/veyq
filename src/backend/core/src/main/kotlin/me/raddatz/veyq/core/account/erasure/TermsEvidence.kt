package me.raddatz.veyq.core.account.erasure

import me.raddatz.veyq.core.moderation.ModerationLog
import me.raddatz.veyq.core.moderation.block.BlockKind
import me.raddatz.veyq.core.moderation.block.ModerationBlockEntity
import me.raddatz.veyq.core.moderation.report.ModerationCaseEntity
import me.raddatz.veyq.core.moderation.statement.ModerationStatementEntity
import me.raddatz.veyq.core.moderation.statement.RecipientKind
import me.raddatz.veyq.core.moderation.statement.StatementRecipientEntity
import java.time.Instant

/**
 * Whether a moderation case names an account, so that its terms acceptance rows outlive its erasure as the evidence
 * of which terms a breach was measured against (ADR 0101 decision 6, ADR 0035 decision 4, GDPR Art. 17(3)(e)). A case
 * names an account through a measure on it (a block whose target is the account), through a statement over its
 * content (the account is one of the statement's recipients), or through a tenant-level step — a tenant taken offline
 * or deleted over content — whose statement told it: such a step targets the tenant and names the tenant's owners at
 * the time of the step, which are exactly its statement's recipients (`StepStatements.recipientsOf`), and then counts as
 * a measure on each of them. No new period: the case's log retention date — or, for the Server Owner's step on no case,
 * the step's own (31 December of the third year after its lift): the account's purge ends a measure on it and the
 * tenant's purge a step on the tenant ([me.raddatz.veyq.core.moderation.cases.SignInBlocks.targetErased]), so the
 * record, not its standing, is what keeps the rows.
 */
object TermsEvidence {

    /** The steps whose statement tells their tenant's owners (ADR 0093 § 7, owner decision Q25). */
    private val TENANT_STEPS = listOf(BlockKind.TENANT_OFFLINE.name, BlockKind.TENANT_DELETED.name)

    /** At the erasure: a measure naming the account in force, or a case about its content not closed. */
    fun namesAtErasure(accountId: String): Boolean {
        val told = statementsTo(accountId)
        return measuresNaming(accountId, told).any { it.activeKey != null } || casesOf(told).any { it.closedAt == null }
    }

    /**
     * Later, for a row an erasure kept: a measure naming the account still in force, a step on no case inside its own
     * retention ([ModerationLog.retentionEnd] of its lift), or a case naming the account that is open or inside its log
     * retention ([ModerationLog.retentionEnd] of its close).
     */
    fun namesUntil(accountId: String, now: Instant): Boolean {
        val told = statementsTo(accountId)
        val measures = measuresNaming(accountId, told)
        val measureCaseIds = measures.mapNotNull { it.caseId }.distinct()
        val measureCases = if (measureCaseIds.isEmpty()) emptyList() else ModerationCaseEntity.list("id in ?1", measureCaseIds)
        return measures.any { it.activeKey != null } ||
            measures.filter { it.caseId == null }
                .any { step -> step.liftedAt?.let { ModerationLog.retentionEnd(it).isAfter(now) } ?: true } ||
            (measureCases + casesOf(told)).any { c ->
                val closed = c.closedAt
                closed == null || ModerationLog.retentionEnd(closed).isAfter(now)
            }
    }

    /** Every statement that told the account. */
    private fun statementsTo(accountId: String): List<ModerationStatementEntity> {
        val ids = StatementRecipientEntity
            .list("recipientKind = ?1 and recipientId = ?2", RecipientKind.ACCOUNT.name, accountId)
            .map { it.statementId }
        return if (ids.isEmpty()) emptyList() else ModerationStatementEntity.list("id in ?1", ids.distinct())
    }

    /** The measures on the account, and the tenant-level steps whose statement — one of [told] — told it. */
    private fun measuresNaming(accountId: String, told: List<ModerationStatementEntity>): List<ModerationBlockEntity> {
        val on = ModerationBlockEntity.list("targetId", accountId)
        val stepIds = told.map { it.measureId }.distinct()
        val steps = if (stepIds.isEmpty()) emptyList() else ModerationBlockEntity.list("id in ?1 and kind in ?2", stepIds, TENANT_STEPS)
        return (on + steps).distinctBy { it.id }
    }

    /** The cases [told]'s statements belong to; a statement of a step on no case has none. */
    private fun casesOf(told: List<ModerationStatementEntity>): List<ModerationCaseEntity> {
        val caseIds = told.mapNotNull { it.caseId }.distinct()
        return if (caseIds.isEmpty()) emptyList() else ModerationCaseEntity.list("id in ?1", caseIds)
    }
}
