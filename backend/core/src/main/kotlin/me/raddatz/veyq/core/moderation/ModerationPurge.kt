package me.raddatz.veyq.core.moderation

import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant

/**
 * The moderation cleanup: key-proof challenges past their five minutes, and moderation-log events past their retention
 * date (ADR 0093 § 11 — 31 December of the third year after the moderator's removal, [ModerationLog.retentionEnd]).
 * And the retry of a rotation-due mail that could not go out. Each step in its own short transaction, as `SessionPurge` does. `@Scheduled(every=…)` fires at its first trigger, so it
 * also runs soon after startup.
 */
@ApplicationScoped
class ModerationPurge(private val log: ModerationLog, private val rotationDue: RotationDueNotice) {

    @Scheduled(every = "{veyq.moderation.purge.every}")
    fun run() = sweep(Instant.now())

    fun sweep(now: Instant) {
        QuarkusTransaction.requiringNew().run { ModerationChallengeEntity.delete("expiresAt < ?1", now) }
        QuarkusTransaction.requiringNew().run { log.purgeExpired(now) }
        // A rotation-due mail that could not go out when it became due is retried here (RotationDueNotice).
        rotationDue.mailIfDue(now)
    }
}
