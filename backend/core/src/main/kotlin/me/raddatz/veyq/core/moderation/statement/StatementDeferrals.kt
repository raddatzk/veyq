package me.raddatz.veyq.core.moderation.statement

import me.raddatz.veyq.core.moderation.ModerationEvent
import me.raddatz.veyq.core.moderation.ModerationLog
import me.raddatz.veyq.core.moderation.hold.HoldTrigger
import me.raddatz.veyq.core.moderation.hold.Holds
import me.raddatz.veyq.core.moderation.report.ModerationCaseEntity
import io.quarkus.narayana.jta.QuarkusTransaction
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.LockModeType
import jakarta.transaction.Transactional
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Who entered a deferral and why: the reason sealed to the moderation key ([epoch], [sealedReason]), or an order's reference ([orderId]). */
data class DeferralReason(val enteredBy: String, val orderId: String?, val epoch: Int?, val sealedReason: String?)

/**
 * The authority's switch over a statement of reasons (ADR 0093 § 7, TCO Art. 11(3); uc.moderation.authority-order step
 * 6): never the moderator's discretion — it needs the authority's request, sealed, or the order it came with. A deferral
 * holds the statement back until its end, at most [SIX_WEEKS] after it was entered; one renewal, at most six weeks again,
 * entered before the end (the database holds "one renewal": unique on statement and `renewal`). At the end
 * [releaseDue] — the hourly cleanup's — sends it, and the measure's hold counts from that sending (ADR 0093 § 4: "a
 * deferred one counts from its sending"); until then the hold reaches six months past the deferral's end.
 */
@ApplicationScoped
class StatementDeferrals(private val holds: Holds, private val log: ModerationLog) {

    /** Whether [until] is a deferral's end the server takes now: in the future, at most six weeks from [now]. */
    fun inBounds(until: Instant, now: Instant): Boolean = until.isAfter(now) && !until.isAfter(now.plus(SIX_WEEKS))

    /**
     * Hold [statement] of [caseId] back until [until], **in the caller's transaction** under the case's lock: the deferral's
     * row, the statement's end, the hold reaching six months past it, and the `STATEMENT_DEFERRED` event.
     */
    @Transactional(Transactional.TxType.MANDATORY)
    fun defer(statement: ModerationStatementEntity, caseId: String, until: Instant, reason: DeferralReason, renewal: Boolean, now: Instant) {
        StatementDeferralEntity().apply {
            id = UUID.randomUUID().toString()
            statementId = statement.id
            this.caseId = caseId
            orderId = reason.orderId
            epoch = reason.epoch
            sealedReason = reason.sealedReason
            enteredBy = reason.enteredBy
            this.renewal = renewal
        }.persist()
        statement.sentAt = null
        statement.deferredUntil = until
        holds.aliveOf(caseId, now)?.let { hold ->
            val end = until.plus(Holds.SIX_MONTHS)
            if (end.isAfter(hold.expiresAt)) holds.moveEnd(hold, end, HoldTrigger.MEASURE)
        }
        log.appendCase(
            ModerationEvent.STATEMENT_DEFERRED, now, caseId, moderatorId = reason.enteredBy, targetId = statement.id,
            epoch = reason.epoch, sealedText = reason.sealedReason, holdUntil = until,
        )
    }

    /** Whether [statementId]'s deferral was renewed once — no further renewal is taken. */
    fun renewed(statementId: String): Boolean = StatementDeferralEntity.count("statementId = ?1 and renewal = true", statementId) > 0

    /**
     * The cleanup's half: every statement whose deferral ended by [now] goes out — sent at [now] where a member is named
     * (its hold then counts from now), its end cleared either way. Answers the statements sent, for their mails after the
     * commit. Each in its own transaction under its case's lock.
     */
    fun releaseDue(now: Instant): List<String> {
        val due = QuarkusTransaction.requiringNew().call {
            ModerationStatementEntity.list("sentAt is null and deferredUntil <= ?1", now).map { it.id }
        }
        return due.mapNotNull { id ->
            QuarkusTransaction.requiringNew().call {
                val statement = ModerationStatementEntity.findById(id) ?: return@call null
                // Only a case's statement is ever deferred (an order's, a moderator's measure's).
                val caseId = statement.caseId ?: return@call null
                ModerationCaseEntity.findById(caseId, LockModeType.PESSIMISTIC_WRITE) ?: return@call null
                // Re-read under the lock: a renewal committed meanwhile moved the end.
                ModerationStatementEntity.getEntityManager().refresh(statement)
                val until = statement.deferredUntil ?: return@call null
                if (statement.sentAt != null || until.isAfter(now)) return@call null
                statement.deferredUntil = null
                val members = StatementRecipientEntity.count(
                    "statementId = ?1 and recipientKind = ?2", statement.id, RecipientKind.ACCOUNT.name,
                ) > 0
                if (!members) return@call null
                statement.sentAt = now
                holds.aliveOf(caseId, now)?.let { hold ->
                    val end = now.plus(Holds.SIX_MONTHS)
                    if (end.isAfter(hold.expiresAt)) holds.moveEnd(hold, end, HoldTrigger.MEASURE)
                }
                log.appendCase(ModerationEvent.STATEMENT_SENT, now, caseId, reason = statement.ground, targetId = statement.id)
                statement.id
            }
        }
    }

    companion object {
        /** A deferral's longest step (TCO Art. 11(3): six weeks, renewable once by six). */
        val SIX_WEEKS: Duration = Duration.ofDays(42)
    }
}
