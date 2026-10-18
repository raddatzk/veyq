package me.raddatz.veyq.core.moderation

import me.raddatz.veyq.core.moderation.block.BlockKind
import me.raddatz.veyq.core.moderation.block.ModerationBlockEntity
import me.raddatz.veyq.core.moderation.block.ModerationProposalEntity
import me.raddatz.veyq.core.moderation.block.ProposalOutcome
import me.raddatz.veyq.core.moderation.hold.HoldEntity
import me.raddatz.veyq.core.moderation.hold.Holds
import me.raddatz.veyq.core.moderation.order.AuthorityOrderEntity
import me.raddatz.veyq.core.moderation.report.CaseState
import me.raddatz.veyq.core.moderation.report.ModerationCaseEntity
import me.raddatz.veyq.core.moderation.report.ModerationReportEntity
import me.raddatz.veyq.core.moderation.report.ReportShareEntity
import me.raddatz.veyq.core.moderation.statement.ModerationObjectionEntity
import me.raddatz.veyq.core.moderation.statement.ModerationStatementEntity
import me.raddatz.veyq.core.moderation.statement.StatementDeferralEntity
import me.raddatz.veyq.core.moderation.statement.StatementRecipientEntity
import io.quarkus.narayana.jta.QuarkusTransaction
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.LockModeType
import java.time.Duration
import java.time.Instant

/**
 * How long moderation keeps a case's records (ADR 0093 § 11, en.moderation.log) — the one path that deletes them, run by
 * [ModerationPurge]:
 *
 *  - **A case goes whole** — its reports' sealed parts and report shares, measures, proposals, statements and their
 *    recipients, deferrals, objections, the orders that opened it and its log events — once **every event of it is past
 *    its date** (31 December of the third year after the later of the event and the case's close, [ModerationLog]), so
 *    the case's date is its log's. The log's own purge leaves a case's events to this one ([ModerationLog.purgeExpired]).
 *    **Never while a measure of it is in force**: deleting the row would lift a block nobody lifted (ADR 0093 § 6), and
 *    a measure in force needs its statement of reasons for as long as it can be contested (DSA Art. 17, GDPR Art. 17(3)(e));
 *    its lift is an event dated from then, which moves the case's date. Nor while it holds anything (a hold ends first),
 *    nor while a proposal of it waits for the Server Owner.
 *  - **An order that opened no case** (its share was not found) goes with its `ORDER_RECEIVED` event at 31 December of
 *    the third year after it was entered — there was nothing to hold, so it closes as it is recorded.
 *  - **A Server Owner's step on no case** (a sign-in block, or a tenant taken offline over content, on their own
 *    finding — ADR 0093 § 7) goes with its statement of reasons and that statement's recipients at 31 December of the
 *    third year after its lift — never while it stands, for the reason a case waits for its measures. Its events are
 *    instance events, dated and purged by the log ([ModerationLog.purgeExpired]). A step on a case goes with the case.
 *  - **A reporter's sealed contact** goes on its own **six months after the decision on the report was told to them**
 *    (DSA Art. 16(5), GDPR Art. 5(1)(e)): the `DECISION_SENT` event stays as the proof. Where no decision could be told on
 *    a case of the report (no mail, no address that could be mailed), six months after that case's decision instead —
 *    the contact then serves nothing more. While a case of the report is still open, it stays.
 *
 * **Lock: the case row**, taken first in each case's own transaction (PESSIMISTIC_WRITE), as every decision takes it — so
 * a late lift, handover or extension either commits before (and its event moves the date, which the re-read under the
 * lock sees) or finds the case gone. At MariaDB's REPEATABLE READ the lock must be the transaction's first read, or a
 * plain read before it fixes a snapshot older than what the lock waited for.
 */
@ApplicationScoped
class ModerationRecordsPurge {

    /** Every case, case-less order and case-less step past its date, and every reporter's contact past its six months. */
    fun sweep(now: Instant) {
        purgeContacts(now)
        purgeCases(now)
        purgeCaselessOrders(now)
        purgeCaselessSteps(now)
    }

    /** Null each reporter's contact six months after the decision on its report was told (or, where none could be, made). */
    fun purgeContacts(now: Instant) {
        val candidates = QuarkusTransaction.requiringNew().call {
            ModerationReportEntity.list("sealedContact is not null and receivedAt <= ?1", now.minus(CONTACT)).map { it.id }
        }
        candidates.forEach { reportId ->
            QuarkusTransaction.requiringNew().run {
                val report = ModerationReportEntity.findById(reportId) ?: return@run
                val due = contactDue(reportId) ?: return@run
                if (!due.plus(CONTACT).isAfter(now)) report.sealedContact = null
            }
        }
    }

    /**
     * When the report's contact stopped being needed — the latest, over the report's cases, of the time the decision was
     * told to the reporter (`DECISION_SENT`) or, where it was not, the case's decision — or null while a case is open.
     */
    private fun contactDue(reportId: String): Instant? {
        val caseIds = ReportShareEntity.list("reportId", reportId).map { it.caseId }.distinct()
        if (caseIds.isEmpty()) return null
        return caseIds.map { caseId ->
            val told = ModerationEventEntity.find(
                "caseId = ?1 and event = ?2 and targetId = ?3 order by occurredAt", caseId, ModerationEvent.DECISION_SENT.name, reportId,
            ).firstResult()?.occurredAt
            told ?: ModerationCaseEntity.findById(caseId)?.let { c ->
                if (c.state == CaseState.OPEN.name) null else c.decidedAt ?: c.closedAt
            } ?: return null
        }.max()
    }

    /** Delete every closed case whose events are all past their date and of which nothing stands or is held. */
    fun purgeCases(now: Instant) {
        // A case's date is at least three years after its close (31 December of the third year after), so a case closed
        // more recently is never due — the cutoff only narrows what is looked at; the check is the events' own dates.
        val candidates = QuarkusTransaction.requiringNew().call {
            ModerationCaseEntity.list("state = ?1 and closedAt < ?2", CaseState.CLOSED.name, now.minus(THREE_YEARS)).map { it.id }
        }
        candidates.forEach { caseId -> QuarkusTransaction.requiringNew().run { purgeCaseIn(caseId, now) } }
    }

    /** [purgeCases]'s check and delete of one case, in the caller's transaction; the case row's lock is its first read. */
    fun purgeCaseIn(caseId: String, now: Instant): Boolean {
        val case = ModerationCaseEntity.findById(caseId, LockModeType.PESSIMISTIC_WRITE) ?: return false
        if (case.state != CaseState.CLOSED.name) return false
        if (ModerationBlockEntity.count("caseId = ?1 and activeKey is not null", caseId) > 0) return false
        if (ModerationObjectionEntity.openOn(caseId)) return false
        if (ModerationProposalEntity.count("caseId = ?1 and outcome = ?2", caseId, ProposalOutcome.OPEN.name) > 0) return false
        if (HoldEntity.count("caseId", caseId) > 0) return false
        val events = ModerationEventEntity.list("caseId", caseId)
        if (events.any { it.expiresAt?.isBefore(now) != true }) return false

        val statementIds = ModerationStatementEntity.list("caseId", caseId).map { it.id }
        StatementDeferralEntity.delete("caseId", caseId)
        ModerationObjectionEntity.delete("caseId", caseId)
        if (statementIds.isNotEmpty()) StatementRecipientEntity.delete("statementId in ?1", statementIds)
        ModerationStatementEntity.delete("caseId", caseId)
        ModerationProposalEntity.delete("caseId", caseId)
        ModerationBlockEntity.delete("caseId", caseId)
        AuthorityOrderEntity.delete("caseId", caseId)
        val reportIds = ReportShareEntity.list("caseId", caseId).map { it.reportId }.distinct()
        ReportShareEntity.delete("caseId", caseId)
        // A report spans one case per item: its sealed part goes with the last case that still names it.
        reportIds.filter { ReportShareEntity.count("reportId", it) == 0L }.takeIf { it.isNotEmpty() }
            ?.let { ModerationReportEntity.delete("id in ?1", it) }
        ModerationEventEntity.delete("caseId", caseId)
        case.delete()
        return true
    }

    /** Delete every order that opened no case, with its event, past 31 December of the third year after its entry. */
    fun purgeCaselessOrders(now: Instant) = QuarkusTransaction.requiringNew().run {
        AuthorityOrderEntity.list("caseId is null and enteredAt < ?1", now.minus(THREE_YEARS))
            .filter { ModerationLog.retentionEnd(it.enteredAt).isBefore(now) }
            .forEach { order ->
                ModerationEventEntity.delete("caseId is null and event = ?1 and targetId = ?2", ModerationEvent.ORDER_RECEIVED.name, order.id)
                order.delete()
            }
    }

    /** Delete every lifted Server Owner's step on no case, with its statement and recipients, past its date. */
    fun purgeCaselessSteps(now: Instant) = QuarkusTransaction.requiringNew().run {
        ModerationBlockEntity.list(
            "caseId is null and kind in ?1 and activeKey is null and liftedAt < ?2",
            listOf(BlockKind.MEMBER_SIGN_IN.name, BlockKind.TENANT_OFFLINE.name), now.minus(THREE_YEARS),
        ).filter { step -> step.liftedAt?.let { ModerationLog.retentionEnd(it).isBefore(now) } == true }
            .forEach { step ->
                val statementIds = ModerationStatementEntity.list("measureId", step.id).map { it.id }
                if (statementIds.isNotEmpty()) StatementRecipientEntity.delete("statementId in ?1", statementIds)
                ModerationStatementEntity.delete("measureId", step.id)
                step.delete()
            }
    }

    companion object {
        /** A reporter's contact: six months after the decision was told (ADR 0093 § 11) — the hold's six months. */
        val CONTACT: Duration = Holds.SIX_MONTHS

        /** No retention date of the log falls sooner than three years after its start; a cutoff for what is looked at. */
        private val THREE_YEARS: Duration = Duration.ofDays(3 * 365)
    }
}
