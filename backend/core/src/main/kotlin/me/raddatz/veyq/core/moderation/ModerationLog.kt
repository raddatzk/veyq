package me.raddatz.veyq.core.moderation

import me.raddatz.veyq.core.identity.ModeratorEntity
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * The events of the moderation log: the instance events (ADR 0099 § 4), and a case's (ADR 0093 § 11) — [RECEIVED] when a
 * report opens it, [REPORT_ATTACHED] when a further report attaches to it.
 */
enum class ModerationEvent {
    APPOINTED, ACCEPTED, DELIVERED, REMOVED, STEPPED_DOWN, IDENTITY_ROTATED, EPOCH_MINTED,
    RECEIVED, REPORT_ATTACHED,
}

/**
 * The moderation log (ADR 0093 § 11, en.moderation.log): append-only, written by its caller's transaction so an event
 * exists exactly when its cause does. Structured fields in the clear; free text only ever as [append]'s `sealedText`,
 * sealed by the client to the moderation key. **Never in a log line** (ADR 0092 § 1): nothing here logs, and no caller
 * logs what it appends — `ModerationLogTest` captures every record while events are written.
 *
 * **Retention.** An event is kept to 31 December of the third year after the later of its own time and the removal of
 * the moderator it concerns. While that moderator is on the roster the date is open (`expires_at` NULL); [closeFor]
 * sets it on their removal, and an event about an account that is not on the roster gets it as it is written.
 * [ModerationPurge] deletes what has expired.
 */
@ApplicationScoped
class ModerationLog {

    fun append(
        event: ModerationEvent,
        at: Instant,
        moderatorId: String?,
        actorId: String?,
        epoch: Int? = null,
        reason: String? = null,
        sealedText: String? = null,
    ) {
        ModerationEventEntity().apply {
            id = UUID.randomUUID().toString()
            this.event = event.name
            occurredAt = at
            this.moderatorId = moderatorId
            this.actorId = actorId
            this.epoch = epoch
            this.reason = reason
            this.sealedText = sealedText
            val onRoster = moderatorId?.let { ModeratorEntity.findById(it)?.onRoster } == true
            expiresAt = if (onRoster) null else retentionEnd(at)
        }.persist()
    }

    /**
     * A case's event (ADR 0093 § 11): [actorId] is the reporting member, null for a guest. Its date stays open (NULL)
     * while the case is: a case's events are kept to 31 December of the third year after it closes, which the review
     * (cr-moderation-review-and-measures) sets when it closes one.
     */
    fun appendCase(event: ModerationEvent, at: Instant, caseId: String, actorId: String?) {
        ModerationEventEntity().apply {
            id = UUID.randomUUID().toString()
            this.event = event.name
            occurredAt = at
            this.caseId = caseId
            this.actorId = actorId
            expiresAt = null
        }.persist()
    }

    /** The moderator [moderatorId] left the roster at [removedAt]: every event about them still open gets its date. */
    fun closeFor(moderatorId: String, removedAt: Instant) {
        ModerationEventEntity.list("moderatorId = ?1 and expiresAt is null and caseId is null", moderatorId).forEach { e ->
            e.expiresAt = retentionEnd(maxOf(e.occurredAt, removedAt))
        }
    }

    /** Delete every event whose date has passed. */
    fun purgeExpired(now: Instant): Long = ModerationEventEntity.delete("expiresAt < ?1", now)

    companion object {
        /**
         * The end of 31 December of the third year after [from]'s year, in UTC: the civil limitation period from year's
         * end, the one ADR 0093 § 11 keeps a case for.
         */
        fun retentionEnd(from: Instant): Instant =
            LocalDate.of(from.atZone(ZoneOffset.UTC).year + 4, 1, 1).atStartOfDay().toInstant(ZoneOffset.UTC)
    }
}
