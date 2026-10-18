package me.raddatz.veyq.core.moderation

import me.raddatz.veyq.core.backup.ErasureLedger
import me.raddatz.veyq.core.identity.ModeratorEntity
import me.raddatz.veyq.core.moderation.report.ModerationCaseEntity
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * The events of the moderation log: the instance events (ADR 0099 § 4), and a case's (ADR 0093 § 11) — [RECEIVED] when a
 * report opens it, [REPORT_ATTACHED] when a further report attaches to it, [OPENED] when the review served its report
 * shares, and every decision (uc.moderation.review-a-case, uc.moderation.take-a-measure). [MEASURE_TAKEN] and
 * [MEASURE_LIFTED] name the measure's kind in `reason` and its target in `target_id`; a member's sign-in block taken on no
 * case is the one of them written with no case id. The statement of reasons and the objection (ADR 0093 § 7,
 * uc.moderation.object-to-a-measure): [STATEMENT_SENT] (the ground, the statement), [RECEIPT_SENT] (a reporter's receipt
 * mailed, the report), [DECISION_SENT] (a reporter told —
 * DISMISSED or DECIDED, the report), [OBJECTION_RECEIVED] (the objector, the objection), and [OBJECTION_DECIDED] or —
 * the decider alone — [OBJECTION_SELF_REVIEWED] (the outcome, the objection). An authority (ADR 0093 §§ 12-13,
 * uc.moderation.authority-order, uc.moderation.report-to-an-authority): [ORDER_RECEIVED] (the order's kind and id — with
 * no case id when its share was not found), [STATEMENT_DEFERRED] (the statement, the deferral's end in `hold_until`, its
 * reason sealed unless it is the order's reference), [HANDED_OVER] and [INFORMATION_GIVEN] (the reference sealed).
 */
enum class ModerationEvent {
    APPOINTED, ACCEPTED, DELIVERED, REMOVED, STEPPED_DOWN, IDENTITY_ROTATED, EPOCH_MINTED,
    RECEIVED, REPORT_ATTACHED, OPENED, DISMISSED, MEASURE_TAKEN, MEASURE_LIFTED, PROPOSED, PROPOSAL_TAKEN,
    PROPOSAL_DECLINED, HOLD_EXTENDED, HOLD_ENDED, REPORTER_BARRED, STATEMENT_SENT, RECEIPT_SENT, DECISION_SENT, OBJECTION_RECEIVED,
    OBJECTION_DECIDED, OBJECTION_SELF_REVIEWED, ORDER_RECEIVED, STATEMENT_DEFERRED, HANDED_OVER, INFORMATION_GIVEN,
    /** A case the erasure ledger brought back as a stub after a restore (ADR 0056 decision 8): dated as it opened. */
    RESTORED,
}

/**
 * The moderation log (ADR 0093 § 11, en.moderation.log): append-only, written by its caller's transaction so an event
 * exists exactly when its cause does. Structured fields in the clear; free text only ever as [append]'s `sealedText`,
 * sealed by the client to the moderation key. **Never in a log line** (ADR 0092 § 1): nothing here logs, and no caller
 * logs what it appends — `ModerationLogTest` captures every record while events are written.
 *
 * **Retention.** An event is kept to 31 December of the third year after the later of its own time and the removal of
 * the moderator it concerns. While that moderator is on the roster the date is open (`expires_at` NULL); [closeFor]
 * sets it on their removal, and an event about an account that is not on the roster gets it as it is written. A case's
 * event is kept to 31 December of the third year after the later of its own time and the case's close ([closeCase]).
 * [purgeExpired] deletes the instance events that have expired; **a case's events go with the case**, all its records
 * at once, by [ModerationRecordsPurge] — one date, one path, and never while a measure of the case is in force.
 */
@ApplicationScoped
class ModerationLog(private val ledger: ErasureLedger) {

    fun append(
        event: ModerationEvent,
        at: Instant,
        moderatorId: String?,
        actorId: String?,
        epoch: Int? = null,
        reason: String? = null,
        sealedText: String? = null,
        targetId: String? = null,
        datedFrom: Instant? = null,
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
            this.targetId = targetId
            // [datedFrom]: an event that records something of its own rather than a moderator (an order that opened no
            // case) is dated from it, whoever entered it.
            val onRoster = datedFrom == null && moderatorId?.let { ModeratorEntity.findById(it)?.onRoster } == true
            expiresAt = if (onRoster) null else retentionEnd(datedFrom ?: at)
        }.persist()
    }

    /**
     * A case's event (ADR 0093 § 11): [actorId] is the reporting member (null for a guest), [moderatorId] the moderator or
     * Server Owner who acted; [reason] a measure's or proposal's kind, [targetId] the id it named, [sealedText] the free
     * text sealed in the browser to [epoch]'s key, [holdUntil] an extension's new end. Its date stays open (NULL) while the
     * case is; [closeCase] sets it, and an event about a case already closed (a later lift) is dated as it is written.
     */
    fun appendCase(
        event: ModerationEvent,
        at: Instant,
        caseId: String,
        actorId: String? = null,
        moderatorId: String? = null,
        reason: String? = null,
        targetId: String? = null,
        epoch: Int? = null,
        sealedText: String? = null,
        holdUntil: Instant? = null,
    ) {
        val closedAt = ModerationCaseEntity.findById(caseId)?.closedAt
        ModerationEventEntity().apply {
            id = UUID.randomUUID().toString()
            this.event = event.name
            occurredAt = at
            this.caseId = caseId
            this.actorId = actorId
            this.moderatorId = moderatorId
            this.reason = reason
            this.targetId = targetId
            this.epoch = epoch
            this.sealedText = sealedText
            this.holdUntil = holdUntil
            expiresAt = closedAt?.let { retentionEnd(maxOf(it, at)) }
        }.persist()
        // The case's line in the erasure ledger (ADR 0056 decision 8): every event can move its state or its date.
        ledger.caseChanged(caseId)
    }

    /** The case [caseId] was reopened by an objection: its events are undated again, as an open case's are. */
    fun reopenCase(caseId: String) {
        ModerationEventEntity.update("expiresAt = null where caseId = ?1", caseId)
        ledger.caseChanged(caseId)
    }

    /** The case [caseId] closed at [closedAt]: every event of it is kept to 31 December of the third year after. */
    fun closeCase(caseId: String, closedAt: Instant) {
        ModerationEventEntity.list("caseId", caseId).forEach { it.expiresAt = retentionEnd(maxOf(it.occurredAt, closedAt)) }
        ledger.caseChanged(caseId)
    }

    /**
     * A member's sign-in block or its lift taken on no case (an authority's order names none): an event with no case id,
     * dated as the Server Owner's other events are — open while they are on the roster.
     */
    fun appendSignInBlock(event: ModerationEvent, at: Instant, serverOwnerId: String, accountId: String) =
        appendCaselessStep(event, at, serverOwnerId, accountId, "MEMBER_SIGN_IN")

    /**
     * A Server Owner's step on no case — a sign-in block, a tenant taken offline over content, their lifts, the statement
     * such a step carries (ADR 0093 § 7): an event with no case id, [reason] the step's kind or the statement's ground,
     * [targetId] the account, the tenant or the statement, dated as the Server Owner's other events are.
     */
    fun appendCaselessStep(event: ModerationEvent, at: Instant, serverOwnerId: String, targetId: String, reason: String) =
        append(event, at, moderatorId = serverOwnerId, actorId = serverOwnerId, reason = reason, targetId = targetId)

    /** The moderator [moderatorId] left the roster at [removedAt]: every event about them still open gets its date. */
    fun closeFor(moderatorId: String, removedAt: Instant) {
        ModerationEventEntity.list("moderatorId = ?1 and expiresAt is null and caseId is null", moderatorId).forEach { e ->
            e.expiresAt = retentionEnd(maxOf(e.occurredAt, removedAt))
        }
    }

    /** Delete every instance event whose date has passed; a case's events go with their case ([ModerationRecordsPurge]). */
    fun purgeExpired(now: Instant): Long = ModerationEventEntity.delete("caseId is null and expiresAt < ?1", now)

    companion object {
        /**
         * The end of 31 December of the third year after [from]'s year, in UTC: the civil limitation period from year's
         * end, the one ADR 0093 § 11 keeps a case for.
         */
        fun retentionEnd(from: Instant): Instant =
            LocalDate.of(from.atZone(ZoneOffset.UTC).year + 4, 1, 1).atStartOfDay().toInstant(ZoneOffset.UTC)
    }
}
