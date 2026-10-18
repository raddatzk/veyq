package me.raddatz.veyq.core.moderation.cases

import me.raddatz.veyq.core.content.NodesStore
import me.raddatz.veyq.core.http.FailureStatus
import me.raddatz.veyq.core.http.HttpFailure
import me.raddatz.veyq.core.http.httpFailure
import me.raddatz.veyq.core.moderation.ModerationEpochEntity
import me.raddatz.veyq.core.moderation.ModerationEvent
import me.raddatz.veyq.core.moderation.ModerationLog
import me.raddatz.veyq.core.moderation.block.BlockKind
import me.raddatz.veyq.core.moderation.block.ModerationBlockEntity
import me.raddatz.veyq.core.moderation.block.ModerationProposalEntity
import me.raddatz.veyq.core.moderation.block.ProposalOutcome
import me.raddatz.veyq.core.moderation.block.ProposalStep
import me.raddatz.veyq.core.moderation.hold.HoldTrigger
import me.raddatz.veyq.core.moderation.hold.Holds
import me.raddatz.veyq.core.moderation.report.CaseState
import me.raddatz.veyq.core.moderation.report.ModerationCaseEntity
import me.raddatz.veyq.core.moderation.report.ModerationReportEntity
import me.raddatz.veyq.core.moderation.report.ReportItemKind
import me.raddatz.veyq.core.moderation.report.ReportShareEntity
import me.raddatz.veyq.core.moderation.report.ReportableItems
import me.raddatz.veyq.core.persistence.createOrConflict
import me.raddatz.veyq.core.scope.ScopeEntity
import jakarta.transaction.Transactional
import io.quarkus.arc.All
import io.quarkus.narayana.jta.QuarkusTransaction
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.LockModeType
import java.time.Instant
import java.util.UUID

/** A free text the browser sealed to the moderation key's [epoch] (`moderation-note` [epoch, caseId]). */
data class SealedNote(val epoch: Int, val sealed: String)

/** A moderator's step as the wire names it (`ModerationMeasureRequest.kind`). */
enum class MeasureStep { SHARE, CONTENT, GUEST_IDENTITY }

/** A decision's refusals (codes per `moderation.openapi.yaml`). */
object CaseFailures {
    val caseNotFound = httpFailure("case_not_found", FailureStatus.NOT_FOUND)
    val caseNotOpen = httpFailure("case_not_open", FailureStatus.CONFLICT)
    val caseClosed = httpFailure("case_closed", FailureStatus.CONFLICT)
    val targetUnknown = httpFailure("moderation_target_unknown", FailureStatus.NOT_FOUND)
    val measureExists = httpFailure("moderation_measure_exists", FailureStatus.CONFLICT)
    val measureNotFound = httpFailure("measure_not_found", FailureStatus.NOT_FOUND)
    val proposalExists = httpFailure("moderation_proposal_exists", FailureStatus.CONFLICT)
    val reportNotFound = httpFailure("report_not_found", FailureStatus.NOT_FOUND)
    val extensionOutOfBounds = httpFailure("extension_out_of_bounds", FailureStatus.BAD_REQUEST)
    val nothingHeld = httpFailure("nothing_held", FailureStatus.CONFLICT)
    val nothingHeldRead = httpFailure("nothing_held", FailureStatus.NOT_FOUND)
    val epochChanged = httpFailure("moderation_epoch_changed", FailureStatus.CONFLICT)
    val invalid = httpFailure("invalid_request", FailureStatus.BAD_REQUEST)
}

/** What a decision answers: done, or the refusal it ended with (nothing written then). */
sealed interface DecisionOutcome {
    object Done : DecisionOutcome
    data class Refused(val failure: HttpFailure) : DecisionOutcome, HttpFailure by failure
}

/**
 * A moderator's decisions on a case (ADR 0093 §§ 4-6, ADR 0099 § 6; uc.moderation.review-a-case,
 * uc.moderation.take-a-measure). Every decision is a log event written in its own transaction, and every measure a row
 * that deletes nothing.
 *
 * **Lock: the case row**, taken first by every decision (PESSIMISTIC_WRITE). A report that attaches to the case takes the
 * same row after its space's lock (`ReportHandler.caseFor`), so a dismissal and an attach run one after the other: the
 * attach either lands before (and the dismissal ends the hold it may have taken) or finds the case closed and opens a new
 * one — never a hold alive on a closed case. One measure per target and kind is the database's (`active_key`), so two
 * cases blocking one link at once collide there and the loser answers [CaseFailures.measureExists].
 *
 * **The hold's ends** (ADR 0093 § 4, decision 7 of cr-moderation-review-and-measures): a dismissal ends it at once; a
 * measure moves it to six months after the decision (until cr-statement-of-reasons-and-objection moves the start to the
 * statement's sending); lifting the case's last measure in force ends it at once and closes the case; an extension sets
 * its own end, at most six months from now. A bar of a reporter is no step against the content: it neither decides a
 * case nor keeps or ends its hold.
 */
@ApplicationScoped
class CaseDecisions(
    private val log: ModerationLog,
    private val holds: Holds,
    private val targets: CaseTargets,
    private val nodes: NodesStore,
    // MutableList, not List: ArC rejects kotlin List with @All (see ScopePurge).
    @All items: MutableList<ReportableItems>,
) {
    private val items: List<ReportableItems> = items

    /**
     * A content block was taken or lifted on [row]: the live views learn it, **after** the decision committed and in a
     * transaction of its own — the tree's revision under the space's lock (a node block changes the level view), or the
     * item's application's list (an item block). Kept out of the decision's transaction so a decision holds the case row
     * alone, and the report's order (space, then case) and a decision's never cross.
     */
    private fun announce(kind: BlockKind, targetId: String, scopeId: String) {
        when (kind) {
            BlockKind.CONTENT_NODE -> QuarkusTransaction.requiringNew().run {
                nodes.lockScope(scopeId)
                nodes.bumpTree(scopeId)
            }
            BlockKind.CONTENT_ITEM -> QuarkusTransaction.requiringNew().run { items.forEach { it.blockChanged(targetId) } }
            else -> {}
        }
    }

    fun dismiss(moderatorId: String, caseId: String, note: SealedNote?, now: Instant = Instant.now()): DecisionOutcome =
        QuarkusTransaction.requiringNew().call { dismissIn(moderatorId, caseId, note, now) }

    /** [dismiss]'s writes, **in the caller's transaction** — the race test holds them uncommitted (`ModerationRacesSliceTest`). */
    @Transactional(Transactional.TxType.MANDATORY)
    fun dismissIn(moderatorId: String, caseId: String, note: SealedNote?, now: Instant): DecisionOutcome {
        val case = lock(caseId) ?: return refused(CaseFailures.caseNotFound)
        if (case.state != CaseState.OPEN.name) return refused(CaseFailures.caseNotOpen)
        noteRefusal(note)?.let { return refused(it) }
        close(case, now)
        log.appendCase(ModerationEvent.DISMISSED, now, case.id, moderatorId = moderatorId, epoch = note?.epoch, sealedText = note?.sealed)
        if (holds.endNow(case.id, now)) log.appendCase(ModerationEvent.HOLD_ENDED, now, case.id, moderatorId = moderatorId)
        return DecisionOutcome.Done
    }

    fun take(
        moderatorId: String, caseId: String, step: MeasureStep, shareId: String?, targetId: String?, note: SealedNote?,
        now: Instant = Instant.now(),
    ): DecisionOutcome {
        var changed: ModerationBlockEntity? = null
        val outcome = createOrConflict(
        conflict = { refused(CaseFailures.measureExists) },
    ) {
        val case = lock(caseId) ?: return@createOrConflict refused(CaseFailures.caseNotFound)
        if (case.state == CaseState.CLOSED.name) return@createOrConflict refused(CaseFailures.caseClosed)
        noteRefusal(note)?.let { return@createOrConflict refused(it) }
        val known = targets.of(case, now)
        val row = when (step) {
            MeasureStep.SHARE -> {
                if (shareId == null || targetId != null) return@createOrConflict refused(CaseFailures.invalid)
                if (known.links.none { it.shareId == shareId }) return@createOrConflict refused(CaseFailures.targetUnknown)
                block(BlockKind.SHARE, shareId, case, moderatorId, now)
            }
            MeasureStep.CONTENT -> {
                if (targetId == null || shareId != null) return@createOrConflict refused(CaseFailures.invalid)
                val kind = when (case.itemKind) {
                    ReportItemKind.NODE.name -> BlockKind.CONTENT_NODE
                    ReportItemKind.ITEM.name -> BlockKind.CONTENT_ITEM
                    else -> return@createOrConflict refused(CaseFailures.targetUnknown)
                }
                if (targetId != case.itemId) return@createOrConflict refused(CaseFailures.targetUnknown)
                block(kind, targetId, case, moderatorId, now).apply { scopeId = case.scopeId }
            }
            MeasureStep.GUEST_IDENTITY -> {
                if (targetId == null || shareId != null) return@createOrConflict refused(CaseFailures.invalid)
                val guest = known.uploaders.firstOrNull { it.isGuest && it.id == targetId }
                    ?: return@createOrConflict refused(CaseFailures.targetUnknown)
                block(BlockKind.GUEST_IDENTITY, targetId, case, moderatorId, now).apply { this.shareId = guest.guestShareId }
            }
        }
        if (ModerationBlockEntity.count("activeKey", row.activeKey!!) > 0) return@createOrConflict refused(CaseFailures.measureExists)
        row.persist()
        decide(case, now)
        log.appendCase(
            ModerationEvent.MEASURE_TAKEN, now, case.id, moderatorId = moderatorId, reason = row.kind, targetId = row.targetId,
            epoch = note?.epoch, sealedText = note?.sealed,
        )
        changed = row
        DecisionOutcome.Done
    }
        changed?.let { row -> row.scopeId?.let { announce(row.blockKind, row.targetId, it) } }
        return outcome
    }

    fun lift(moderatorId: String, caseId: String, measureId: String, note: SealedNote?, now: Instant = Instant.now()): DecisionOutcome {
        var changed: ModerationBlockEntity? = null
        val outcome = QuarkusTransaction.requiringNew().call {
            val case = lock(caseId) ?: return@call refused(CaseFailures.caseNotFound)
            val row = ModerationBlockEntity.find("id = ?1 and caseId = ?2", measureId, case.id).firstResult()
                ?: return@call refused(CaseFailures.measureNotFound)
            if (row.activeKey == null) return@call DecisionOutcome.Done
            noteRefusal(note)?.let { return@call refused(it) }
            row.liftedAt = now
            row.liftedBy = moderatorId
            row.activeKey = null
            log.appendCase(
                ModerationEvent.MEASURE_LIFTED, now, case.id, moderatorId = moderatorId, reason = row.kind, targetId = row.targetId,
                epoch = note?.epoch, sealedText = note?.sealed,
            )
            // A lifted measure ends the hold with the lift (ADR 0093 § 4) once no step of the case is in force; the case
            // then has nothing left to hold for and closes.
            val inForce = ModerationBlockEntity.list("caseId = ?1 and activeKey is not null", case.id).count { !it.blockKind.isBar }
            if (!row.blockKind.isBar && inForce == 0 && case.state == CaseState.DECIDED.name) {
                if (holds.endNow(case.id, now)) log.appendCase(ModerationEvent.HOLD_ENDED, now, case.id, moderatorId = moderatorId)
                close(case, now)
            }
            changed = row
            DecisionOutcome.Done
        }
        changed?.let { row -> row.scopeId?.let { announce(row.blockKind, row.targetId, it) } }
        return outcome
    }

    fun propose(
        moderatorId: String, caseId: String, step: ProposalStep, targetId: String?, note: SealedNote?, now: Instant = Instant.now(),
    ): DecisionOutcome = createOrConflict(
        conflict = { refused(CaseFailures.proposalExists) },
    ) {
        val case = lock(caseId) ?: return@createOrConflict refused(CaseFailures.caseNotFound)
        if (case.state == CaseState.CLOSED.name) return@createOrConflict refused(CaseFailures.caseClosed)
        noteRefusal(note)?.let { return@createOrConflict refused(it) }
        val target = when (step) {
            ProposalStep.MEMBER_SIGN_IN -> targetId?.takeIf { id ->
                targets.of(case, now).uploaders.any { !it.isGuest && it.id == id }
            } ?: return@createOrConflict refused(CaseFailures.targetUnknown)
            ProposalStep.TENANT_OFFLINE -> {
                if (targetId != null) return@createOrConflict refused(CaseFailures.invalid)
                ScopeEntity.findById(case.scopeId)?.tenantId ?: return@createOrConflict refused(CaseFailures.targetUnknown)
            }
        }
        val key = ModerationProposalEntity.openKeyOf(case.id, step, target)
        if (ModerationProposalEntity.count("openKey", key) > 0) return@createOrConflict refused(CaseFailures.proposalExists)
        ModerationProposalEntity().apply {
            id = UUID.randomUUID().toString()
            this.caseId = case.id
            this.step = step.name
            this.targetId = target
            proposedBy = moderatorId
            proposedAt = now
            outcome = ProposalOutcome.OPEN.name
            openKey = key
        }.persist()
        log.appendCase(
            ModerationEvent.PROPOSED, now, case.id, moderatorId = moderatorId, reason = step.name, targetId = target,
            epoch = note?.epoch, sealedText = note?.sealed,
        )
        DecisionOutcome.Done
    }

    fun extend(moderatorId: String, caseId: String, until: Instant, reason: SealedNote, now: Instant = Instant.now()): DecisionOutcome =
        QuarkusTransaction.requiringNew().call {
            val case = lock(caseId) ?: return@call refused(CaseFailures.caseNotFound)
            val hold = holds.aliveOf(case.id, now) ?: return@call refused(CaseFailures.nothingHeld)
            if (!until.isAfter(hold.expiresAt) || until.isAfter(now.plus(Holds.SIX_MONTHS))) {
                return@call refused(CaseFailures.extensionOutOfBounds)
            }
            noteRefusal(reason)?.let { return@call refused(it) }
            holds.moveEnd(hold, until, HoldTrigger.EXTENDED)
            log.appendCase(
                ModerationEvent.HOLD_EXTENDED, now, case.id, moderatorId = moderatorId, epoch = reason.epoch,
                sealedText = reason.sealed, holdUntil = until,
            )
            DecisionOutcome.Done
        }

    fun bar(moderatorId: String, caseId: String, reportId: String, note: SealedNote?, now: Instant = Instant.now()): DecisionOutcome =
        createOrConflict(conflict = { refused(CaseFailures.measureExists) }) {
            val case = lock(caseId) ?: return@createOrConflict refused(CaseFailures.caseNotFound)
            if (ReportShareEntity.count("caseId = ?1 and reportId = ?2", case.id, reportId) == 0L) {
                return@createOrConflict refused(CaseFailures.reportNotFound)
            }
            val report = ModerationReportEntity.findById(reportId) ?: return@createOrConflict refused(CaseFailures.reportNotFound)
            noteRefusal(note)?.let { return@createOrConflict refused(it) }
            val row = report.reporterAccount?.let { block(BlockKind.REPORTER_ACCOUNT, it, case, moderatorId, now) }
                ?: report.reporterShare?.let { block(BlockKind.REPORTER_LINK, it, case, moderatorId, now) }
                ?: return@createOrConflict refused(CaseFailures.reportNotFound)
            if (ModerationBlockEntity.count("activeKey", row.activeKey!!) > 0) return@createOrConflict refused(CaseFailures.measureExists)
            row.persist()
            log.appendCase(
                ModerationEvent.REPORTER_BARRED, now, case.id, moderatorId = moderatorId, reason = row.kind, targetId = row.targetId,
                epoch = note?.epoch, sealedText = note?.sealed,
            )
            DecisionOutcome.Done
        }

    /**
     * The cleanup's half (`ModerationPurge`): every case whose hold the purge ended gets its `HOLD_ENDED` event, and a
     * decided case left with no hold closes (ADR 0093 § 11: a case closes when its last hold has ended). An open case
     * whose 90 days ran out stays open — undecided, in the queue, with nothing held.
     */
    fun holdsEnded(caseIds: List<String>, now: Instant = Instant.now()) = caseIds.forEach { caseId ->
        QuarkusTransaction.requiringNew().run {
            val case = lock(caseId) ?: return@run
            log.appendCase(ModerationEvent.HOLD_ENDED, now, case.id)
            if (case.state == CaseState.DECIDED.name && holds.aliveOf(case.id, now) == null) close(case, now)
        }
    }

    private fun lock(caseId: String): ModerationCaseEntity? =
        ModerationCaseEntity.findById(caseId, LockModeType.PESSIMISTIC_WRITE)

    /** A note must be sealed to the epoch reports seal to now — the one every moderator holds. */
    private fun noteRefusal(note: SealedNote?): HttpFailure? {
        if (note == null) return null
        val current = ModerationEpochEntity.find("order by epoch desc").firstResult()?.epoch
        return if (current != note.epoch) CaseFailures.epochChanged else null
    }

    private fun block(kind: BlockKind, target: String, case: ModerationCaseEntity, moderatorId: String, now: Instant) =
        ModerationBlockEntity().apply {
            id = UUID.randomUUID().toString()
            this.kind = kind.name
            targetId = target
            caseId = case.id
            takenBy = moderatorId
            takenAt = now
            activeKey = ModerationBlockEntity.activeKeyOf(kind, target)
        }

    /** The case is decided; its hold now ends six months after this decision, or later if an extension said so. */
    private fun decide(case: ModerationCaseEntity, now: Instant) {
        case.state = CaseState.DECIDED.name
        if (case.decidedAt == null) case.decidedAt = now
        holds.aliveOf(case.id, now)?.let { hold ->
            val until = now.plus(Holds.SIX_MONTHS)
            if (until.isAfter(hold.expiresAt)) holds.moveEnd(hold, until, HoldTrigger.MEASURE)
        }
    }

    /** Close [case]: out of the "one open case per item" key, and its events dated from now (ADR 0093 § 11). */
    private fun close(case: ModerationCaseEntity, now: Instant) {
        case.state = CaseState.CLOSED.name
        case.closedAt = now
        case.openKey = null
        case.noticeDue = null
        ModerationCaseEntity.getEntityManager().flush()
        log.closeCase(case.id, now)
    }

    private fun refused(failure: HttpFailure) = DecisionOutcome.Refused(failure)
}
