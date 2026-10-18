package me.raddatz.veyq.core.account.erasure

import me.raddatz.veyq.core.moderation.ModerationLog
import me.raddatz.veyq.core.moderation.block.ModerationBlockEntity
import me.raddatz.veyq.core.moderation.report.ModerationCaseEntity
import me.raddatz.veyq.core.moderation.statement.ModerationStatementEntity
import me.raddatz.veyq.core.moderation.statement.RecipientKind
import me.raddatz.veyq.core.moderation.statement.StatementRecipientEntity
import java.time.Instant

/**
 * Whether a moderation case names an account, so that its terms acceptance rows outlive its erasure as the evidence
 * of which terms a breach was measured against (ADR 0101 decision 6, ADR 0035 decision 4, GDPR Art. 17(3)(e)). A case
 * names an account through a measure on it (a block whose target is the account) or through a statement over its
 * content (the account is one of the statement's recipients). No new period: the case's log retention date.
 */
object TermsEvidence {

    /** At the erasure: a measure on the account in force, or a case about its content not closed. */
    fun namesAtErasure(accountId: String): Boolean =
        ModerationBlockEntity.count("targetId = ?1 and activeKey is not null", accountId) > 0 ||
            casesAbout(accountId).any { it.closedAt == null }

    /**
     * Later, for a row an erasure kept: a measure still in force, or a case naming the account that is open or inside its
     * log retention ([ModerationLog.retentionEnd] of its close).
     */
    fun namesUntil(accountId: String, now: Instant): Boolean =
        ModerationBlockEntity.count("targetId = ?1 and activeKey is not null", accountId) > 0 ||
            (blockCases(accountId) + casesAbout(accountId)).any { c ->
                val closed = c.closedAt
                closed == null || ModerationLog.retentionEnd(closed).isAfter(now)
            }

    private fun casesAbout(accountId: String): List<ModerationCaseEntity> {
        val statements = StatementRecipientEntity
            .list("recipientKind = ?1 and recipientId = ?2", RecipientKind.ACCOUNT.name, accountId)
            .map { it.statementId }
        if (statements.isEmpty()) return emptyList()
        val caseIds = ModerationStatementEntity.list("id in ?1", statements).map { it.caseId }.distinct()
        return ModerationCaseEntity.list("id in ?1", caseIds)
    }

    private fun blockCases(accountId: String): List<ModerationCaseEntity> {
        val caseIds = ModerationBlockEntity.list("targetId = ?1 and caseId is not null", accountId).mapNotNull { it.caseId }
        return if (caseIds.isEmpty()) emptyList() else ModerationCaseEntity.list("id in ?1", caseIds.distinct())
    }
}
