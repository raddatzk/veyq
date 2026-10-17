package me.raddatz.veyq.core.moderation

import me.raddatz.veyq.core.moderation.hold.Holds
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant

/**
 * The moderation cleanup: key-proof challenges past their five minutes, moderation-log events past their retention
 * date (ADR 0093 § 11 — 31 December of the third year after the moderator's removal, [ModerationLog.retentionEnd]), and
 * holds past their end with their snapshot and pins (ADR 0093 § 4, [Holds.purgeExpired]).
 * And the retry of a rotation-due mail that could not go out. Each step in its own short transaction, as `SessionPurge` does. `@Scheduled(every=…)` fires at its first trigger, so it
 * also runs soon after startup.
 */
@ApplicationScoped
class ModerationPurge(
    private val log: ModerationLog,
    private val rotationDue: RotationDueNotice,
    private val holds: Holds,
) {

    @Scheduled(every = "{veyq.moderation.purge.every}")
    fun run() = sweep(Instant.now())

    fun sweep(now: Instant) {
        QuarkusTransaction.requiringNew().run { ModerationChallengeEntity.delete("expiresAt < ?1", now) }
        QuarkusTransaction.requiringNew().run { log.purgeExpired(now) }
        holds.purgeExpired(now)
        // A rotation-due mail that could not go out when it became due is retried here (RotationDueNotice).
        rotationDue.mailIfDue(now)
    }
}
