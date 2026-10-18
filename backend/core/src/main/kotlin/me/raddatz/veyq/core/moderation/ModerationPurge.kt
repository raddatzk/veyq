package me.raddatz.veyq.core.moderation

import me.raddatz.veyq.core.moderation.cases.CaseDecisions
import me.raddatz.veyq.core.moderation.hold.Holds
import me.raddatz.veyq.core.moderation.report.CaseNotices
import me.raddatz.veyq.core.moderation.statement.StatementDeferrals
import me.raddatz.veyq.core.moderation.statement.StatementMail
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant

/**
 * The moderation cleanup: key-proof challenges past their five minutes, moderation-log events past their retention
 * date (ADR 0093 § 11 — 31 December of the third year after the moderator's removal, [ModerationLog.retentionEnd]), and
 * holds past their end with their snapshot and pins (ADR 0093 § 4, [Holds.purgeExpired]); and the new-case mails a case
 * still owes ([CaseNotices]: an urgent one that could not go out, the day's bundle).
 * And the retry of a rotation-due mail and of a statement of reasons' mail that could not go out. Each step in its own short transaction, as `SessionPurge` does. `@Scheduled(every=…)` fires at its first trigger, so it
 * also runs soon after startup.
 */
@ApplicationScoped
class ModerationPurge(
    private val log: ModerationLog,
    private val rotationDue: RotationDueNotice,
    private val holds: Holds,
    private val caseNotices: CaseNotices,
    private val decisions: CaseDecisions,
    private val statementMail: StatementMail,
    private val deferrals: StatementDeferrals,
) {

    @Scheduled(every = "{veyq.moderation.purge.every}")
    fun run() = sweep(Instant.now())

    fun sweep(now: Instant) {
        QuarkusTransaction.requiringNew().run { ModerationChallengeEntity.delete("expiresAt < ?1", now) }
        QuarkusTransaction.requiringNew().run { log.purgeExpired(now) }
        // A hold past its end goes with its snapshot and pins; its case logs the end, and a decided one then closes.
        decisions.holdsEnded(holds.purgeExpired(now), now)
        // The new-case mail (ADR 0093 § 2, owner decision Q7): an urgent one that could not go out, and the day's bundle.
        caseNotices.mailUrgent(now)
        caseNotices.mailBundle(now)
        // A rotation-due mail that could not go out when it became due is retried here (RotationDueNotice).
        rotationDue.mailIfDue(now)
        // A statement an authority deferred goes out once its deferral ended (ADR 0093 § 7, StatementDeferrals), and is mailed.
        deferrals.releaseDue(now).forEach { statementMail.statementSent(it) }
        // A statement of reasons whose mail to a member did not go out, for seven days after it was sent (StatementMail).
        statementMail.retryDue(now)
    }
}
