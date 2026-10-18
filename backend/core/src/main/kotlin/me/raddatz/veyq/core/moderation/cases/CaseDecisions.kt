package me.raddatz.veyq.core.moderation.cases

import me.raddatz.veyq.core.admin.operator.OperatingMode
import me.raddatz.veyq.core.admin.operator.OperatingModeHandler
import me.raddatz.veyq.core.content.NodesStore
import me.raddatz.veyq.core.http.FailureStatus
import me.raddatz.veyq.core.http.HttpFailure
import me.raddatz.veyq.core.http.httpFailure
import me.raddatz.veyq.core.identity.ModeratorEntity
import me.raddatz.veyq.core.identity.VeyqUser
import me.raddatz.veyq.core.moderation.ModerationEpochEntity
import me.raddatz.veyq.core.moderation.ModerationGrantEntity
import me.raddatz.veyq.core.moderation.ModerationEvent
import me.raddatz.veyq.core.moderation.ModerationLog
import me.raddatz.veyq.core.moderation.block.BlockKind
import me.raddatz.veyq.core.moderation.block.ModerationBlockEntity
import me.raddatz.veyq.core.moderation.block.ModerationProposalEntity
import me.raddatz.veyq.core.moderation.block.ProposalOutcome
import me.raddatz.veyq.core.moderation.block.ProposalStep
import me.raddatz.veyq.core.moderation.hold.CaseSnapshots
import me.raddatz.veyq.core.moderation.hold.HoldTrigger
import me.raddatz.veyq.core.moderation.hold.Holds
import me.raddatz.veyq.core.moderation.report.CaseState
import me.raddatz.veyq.core.moderation.report.ModerationCaseEntity
import me.raddatz.veyq.core.moderation.report.ModerationReportEntity
import me.raddatz.veyq.core.moderation.report.ReportCategory
import me.raddatz.veyq.core.moderation.report.ReportItemKind
import me.raddatz.veyq.core.moderation.report.ReportShareEntity
import me.raddatz.veyq.core.moderation.report.ReportableItems
import me.raddatz.veyq.core.moderation.statement.ModerationObjectionEntity
import me.raddatz.veyq.core.moderation.statement.ModerationStatementEntity
import me.raddatz.veyq.core.moderation.statement.ObjectionOutcome
import me.raddatz.veyq.core.moderation.statement.Recipient
import me.raddatz.veyq.core.moderation.statement.RecipientKind
import me.raddatz.veyq.core.moderation.statement.ReporterNotice
import me.raddatz.veyq.core.moderation.statement.DeferralReason
import me.raddatz.veyq.core.moderation.statement.StatementBasis
import me.raddatz.veyq.core.moderation.statement.StatementDeferrals
import me.raddatz.veyq.core.moderation.statement.StatementGround
import me.raddatz.veyq.core.moderation.statement.StatementMail
import me.raddatz.veyq.core.moderation.statement.StatementRecipientEntity
import me.raddatz.veyq.core.moderation.statement.StatementRecipients
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

/** A step resolved against what its case knows: the block's kind, its target, and (a blocked guest's) link. */
data class ResolvedStep(val kind: BlockKind, val targetId: String, val shareId: String?)

/**
 * A measure's statement of reasons as the moderator gives it (ADR 0093 § 7): the [ground], the [provision] of the law or
 * clause of the terms it relies on (in the clear, DSA Art. 17(3)(d), (e)), the [facts] sealed to the moderation key, and
 * [factsFor] — the facts sealed to each affected member's identity, by account id.
 */
data class StatementInput(
    val ground: StatementGround,
    val facts: SealedNote?,
    val factsFor: Map<String, String>,
    val deferral: DeferralInput? = null,
    val provision: String? = null,
)

/** An authority's deferral as the moderator enters it (ADR 0093 § 7): its end, and the authority's request sealed. */
data class DeferralInput(val until: Instant, val reason: SealedNote)

/** A handover to an authority (ADR 0093 §§ 11-13): reported to one, or information given to one. */
enum class HandoverKind(val event: ModerationEvent) { REPORTED(ModerationEvent.HANDED_OVER), INFORMATION_GIVEN(ModerationEvent.INFORMATION_GIVEN) }

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
    val factsRequired = httpFailure("statement_facts_required", FailureStatus.BAD_REQUEST)
    val provisionRequired = httpFailure("statement_provision_required", FailureStatus.BAD_REQUEST)
    val recipientsChanged = httpFailure("statement_recipients_changed", FailureStatus.CONFLICT)
    val objectionNotFound = httpFailure("objection_not_found", FailureStatus.NOT_FOUND)
    val objectionDecided = httpFailure("objection_decided", FailureStatus.CONFLICT)
    val needsAnotherModerator = httpFailure("objection_needs_another_moderator", FailureStatus.CONFLICT)
    val deferralOutOfBounds = httpFailure("deferral_out_of_bounds", FailureStatus.BAD_REQUEST)
    val statementNotFound = httpFailure("statement_not_found", FailureStatus.NOT_FOUND)
    val statementNotDeferred = httpFailure("statement_not_deferred", FailureStatus.CONFLICT)
    val deferralRenewed = httpFailure("deferral_renewed", FailureStatus.CONFLICT)
}

/**
 * The accepted, unremoved moderators holding a grant of the current epoch — who "holds the key" (ADR 0099 § 5): the
 * account that took a measure may decide its objection only while it is the only one. In the caller's transaction.
 */
fun currentEpochHolders(): Set<String> {
    val current = ModerationEpochEntity.find("order by epoch desc").firstResult()?.epoch ?: return emptySet()
    val accepted = ModeratorEntity.list("removedAt is null and acceptedAt is not null").map { it.accountId }.toSet()
    return ModerationGrantEntity.list("epoch", current).map { it.moderatorId }.filter { it in accepted }.toSet()
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
 * **The hold's ends** (ADR 0093 § 4): a dismissal ends it at once; a measure moves it to six months after its
 * statement of reasons is sent — after the decision where nobody can be told (decision 4 of
 * cr-statement-of-reasons-and-objection); lifting the case's last measure in force ends it at once and closes the case;
 * an extension sets its own end, at most six months from now. A case of child sexual abuse is bounded: a measure or a
 * statement moves its hold at most to 90 days after the report (`Holds.moveEnd`'s ceiling), and the handover ends it.
 * A bar of a reporter is no step against the content: it
 * neither decides a case nor keeps or ends its hold. **A case closes** only when its last hold ended and no objection
 * waits (ADR 0093 § 11).
 *
 * **The statement of reasons** (ADR 0093 § 7, uc.moderation.object-to-a-measure) is written with the measure, in its
 * transaction; its mails and a reporter's decision go out after the commit ([StatementMail]), and a mail that fails costs
 * the decision nothing. **An objection** is decided here too (ADR 0099 § 5): refused from the account that took the
 * measure while another accepted moderator holds the current epoch.
 */
@ApplicationScoped
class CaseDecisions(
    private val log: ModerationLog,
    private val holds: Holds,
    private val targets: CaseTargets,
    private val nodes: NodesStore,
    private val snapshots: CaseSnapshots,
    private val recipients: StatementRecipients,
    private val operatingMode: OperatingModeHandler,
    private val mail: StatementMail,
    private val deferrals: StatementDeferrals,
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

    fun dismiss(
        moderatorId: String, caseId: String, note: SealedNote?, reporters: List<ReporterNotice> = emptyList(), now: Instant = Instant.now(),
    ): DecisionOutcome {
        val outcome = QuarkusTransaction.requiringNew().call { dismissIn(moderatorId, caseId, note, now) }
        if (outcome == DecisionOutcome.Done) mail.tellReporters(caseId, reporters, StatementMail.Decision.DISMISSED, moderatorId)
        return outcome
    }

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

    /** What [step] on [shareId] / [targetId] names in [case], or the refusal (`moderation_target_unknown`, `invalid_request`). */
    fun resolve(case: ModerationCaseEntity, step: MeasureStep, shareId: String?, targetId: String?, known: Targets): Pair<ResolvedStep?, HttpFailure?> =
        when (step) {
            MeasureStep.SHARE -> when {
                shareId == null || targetId != null -> null to CaseFailures.invalid
                known.links.none { it.shareId == shareId } -> null to CaseFailures.targetUnknown
                else -> ResolvedStep(BlockKind.SHARE, shareId, null) to null
            }
            MeasureStep.CONTENT -> {
                val kind = when (case.itemKind) {
                    ReportItemKind.NODE.name -> BlockKind.CONTENT_NODE
                    ReportItemKind.ITEM.name -> BlockKind.CONTENT_ITEM
                    else -> null
                }
                when {
                    targetId == null || shareId != null -> null to CaseFailures.invalid
                    kind == null || targetId != case.itemId -> null to CaseFailures.targetUnknown
                    else -> ResolvedStep(kind, targetId, null) to null
                }
            }
            MeasureStep.GUEST_IDENTITY -> {
                val guest = known.uploaders.firstOrNull { it.isGuest && it.id == targetId }
                when {
                    targetId == null || shareId != null -> null to CaseFailures.invalid
                    guest == null -> null to CaseFailures.targetUnknown
                    else -> ResolvedStep(BlockKind.GUEST_IDENTITY, targetId, guest.guestShareId) to null
                }
            }
        }

    /**
     * The recipients [step] on its target would reach, each member with the identity public bundle the moderator's browser
     * seals the facts to (`getModerationStatementRecipients`); or the refusal.
     */
    fun recipientsOf(
        caseId: String, step: MeasureStep, shareId: String?, targetId: String?, now: Instant = Instant.now(),
    ): Pair<List<Pair<Recipient, String?>>?, HttpFailure?> = QuarkusTransaction.requiringNew().call {
        val case = ModerationCaseEntity.findById(caseId) ?: return@call null to CaseFailures.caseNotFound
        val held = snapshots.rowsOf(case.id, now)
        val known = targets.of(case, held)
        val (resolved, refusal) = resolve(case, step, shareId, targetId, known)
        if (resolved == null) return@call null to refusal
        val found = recipients.of(case, resolved, known, held)
        val members = found.filter { it.kind == RecipientKind.ACCOUNT }.map { it.id }
        val keys = if (members.isEmpty()) emptyMap() else VeyqUser.list("id in ?1", members).associate { it.id to it.identityPublicKey }
        found.map { it to keys[it.id] } to null
    }

    fun take(
        moderatorId: String, caseId: String, step: MeasureStep, shareId: String?, targetId: String?, note: SealedNote?,
        statement: StatementInput, reporters: List<ReporterNotice> = emptyList(), now: Instant = Instant.now(),
    ): DecisionOutcome {
        var changed: ModerationBlockEntity? = null
        var statementId: String? = null
        val outcome = createOrConflict(
        conflict = { refused(CaseFailures.measureExists) },
    ) {
        val case = lock(caseId) ?: return@createOrConflict refused(CaseFailures.caseNotFound)
        if (case.state == CaseState.CLOSED.name) return@createOrConflict refused(CaseFailures.caseClosed)
        noteRefusal(note)?.let { return@createOrConflict refused(it) }
        val held = snapshots.rowsOf(case.id, now)
        val known = targets.of(case, held)
        val (resolved, refusal) = resolve(case, step, shareId, targetId, known)
        if (resolved == null) return@createOrConflict refused(refusal!!)
        // The statement's form follows the operating mode, read now (ADR 0093 § 14): the full form requires the facts and
        // the provision or clause the ground relies on (DSA Art. 17(3)(d), (e)).
        val full = operatingMode.read().mode == OperatingMode.ORGANISATION
        if (statement.facts == null && full) return@createOrConflict refused(CaseFailures.factsRequired)
        if (statement.provision.isNullOrBlank() && full) return@createOrConflict refused(CaseFailures.provisionRequired)
        noteRefusal(statement.facts)?.let { return@createOrConflict refused(it) }
        statement.deferral?.let { d ->
            if (!deferrals.inBounds(d.until, now)) return@createOrConflict refused(CaseFailures.deferralOutOfBounds)
            noteRefusal(d.reason)?.let { return@createOrConflict refused(it) }
        }
        val told = recipients.of(case, resolved, known, held)
        val members = told.filter { it.kind == RecipientKind.ACCOUNT }.map { it.id }.toSet()
        if (statement.facts == null && statement.factsFor.isNotEmpty()) return@createOrConflict refused(CaseFailures.invalid)
        if (statement.facts != null && statement.factsFor.keys != members) return@createOrConflict refused(CaseFailures.recipientsChanged)
        val row = block(resolved.kind, resolved.targetId, case, moderatorId, now).apply {
            if (resolved.kind == BlockKind.CONTENT_NODE || resolved.kind == BlockKind.CONTENT_ITEM) scopeId = case.scopeId
            if (resolved.kind == BlockKind.GUEST_IDENTITY) this.shareId = resolved.shareId
        }
        if (ModerationBlockEntity.count("activeKey", row.activeKey!!) > 0) return@createOrConflict refused(CaseFailures.measureExists)
        row.persist()
        log.appendCase(
            ModerationEvent.MEASURE_TAKEN, now, case.id, moderatorId = moderatorId, reason = row.kind, targetId = row.targetId,
            epoch = note?.epoch, sealedText = note?.sealed,
        )
        // Sent when a member can be told; where only guests are named, nobody can be told and the hold counts from the
        // decision (ADR 0093 § 4). An authority's deferral holds it back until its end (ADR 0093 § 7).
        val sentAt = if (members.isNotEmpty() && statement.deferral == null) now else null
        val written = ModerationStatementEntity().apply {
            id = UUID.randomUUID().toString()
            measureId = row.id
            this.caseId = case.id
            ground = statement.ground.name
            provision = statement.provision?.trim()?.takeIf { it.isNotEmpty() }
            basis = StatementBasis.REPORT.name
            epoch = statement.facts?.epoch
            sealedFacts = statement.facts?.sealed
            this.sentAt = sentAt
        }
        written.persist()
        told.forEach { r ->
            StatementRecipientEntity().apply {
                id = UUID.randomUUID().toString()
                this.statementId = written.id
                recipientKind = r.kind.name
                recipientId = r.id
                roles = r.roles.joinToString(",") { it.name }
                sealedFacts = statement.factsFor[r.id]
            }.persist()
        }
        if (sentAt != null) {
            log.appendCase(
                ModerationEvent.STATEMENT_SENT, now, case.id, moderatorId = moderatorId, reason = statement.ground.name, targetId = written.id,
            )
        }
        decide(case, now, sentAt)
        statement.deferral?.let { d ->
            deferrals.defer(written, case.id, d.until, DeferralReason(moderatorId, null, d.reason.epoch, d.reason.sealed), renewal = false, now)
        }
        changed = row
        statementId = written.id
        DecisionOutcome.Done
    }
        changed?.let { row -> row.scopeId?.let { announce(row.blockKind, row.targetId, it) } }
        statementId?.let { id ->
            mail.statementSent(id)
            mail.tellReporters(caseId, reporters, StatementMail.Decision.DECIDED, moderatorId)
        }
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
            liftIn(case, row, moderatorId, note, now)
            changed = row
            DecisionOutcome.Done
        }
        changed?.let { row -> row.scopeId?.let { announce(row.blockKind, row.targetId, it) } }
        return outcome
    }

    /**
     * Lift [row] of [case], **in the caller's transaction** under the case's lock: the lift is recorded and logged, and
     * once no step of the case is in force its hold ends (ADR 0093 § 4) and — with no objection waiting — it closes.
     */
    private fun liftIn(case: ModerationCaseEntity, row: ModerationBlockEntity, moderatorId: String, note: SealedNote?, now: Instant) {
        row.liftedAt = now
        row.liftedBy = moderatorId
        row.activeKey = null
        ModerationBlockEntity.getEntityManager().flush()
        log.appendCase(
            ModerationEvent.MEASURE_LIFTED, now, case.id, moderatorId = moderatorId, reason = row.kind, targetId = row.targetId,
            epoch = note?.epoch, sealedText = note?.sealed,
        )
        // A lifted measure ends the hold with the lift (ADR 0093 § 4) once no step of the case is in force; the case
        // then has nothing left to hold for and closes, unless an objection still waits (§ 11).
        val inForce = ModerationBlockEntity.list("caseId = ?1 and activeKey is not null", case.id).count { !it.blockKind.isBar }
        if (!row.blockKind.isBar && inForce == 0 && case.state == CaseState.DECIDED.name) {
            if (holds.endNow(case.id, now)) log.appendCase(ModerationEvent.HOLD_ENDED, now, case.id, moderatorId = moderatorId)
            // An authority's order keeps its hold to the end it set (ADR 0093 § 4): the case closes when that ends.
            if (holds.aliveOf(case.id, now) == null && !ModerationObjectionEntity.openOn(case.id)) close(case, now)
        }
    }

    /**
     * Decide the objection [objectionId] of [caseId] (ADR 0093 § 7, ADR 0099 § 5): [lift] lifts the measure it answers,
     * otherwise the measure stands. Refused from the account that took the measure while another accepted moderator holds
     * the current epoch; with none, accepted and logged as that account's own review (`OBJECTION_SELF_REVIEWED`), which
     * the answer to the objector says. An objection is decided once; a decided case with no hold left then closes.
     */
    fun decideObjection(
        moderatorId: String, caseId: String, objectionId: String, lift: Boolean, note: SealedNote?, now: Instant = Instant.now(),
    ): DecisionOutcome {
        var changed: ModerationBlockEntity? = null
        val outcome = QuarkusTransaction.requiringNew().call {
            decideObjectionIn(moderatorId, caseId, objectionId, lift, note, now) { changed = it }
        }
        changed?.let { row -> row.scopeId?.let { announce(row.blockKind, row.targetId, it) } }
        if (outcome == DecisionOutcome.Done) mail.objectionAnswered(objectionId)
        return outcome
    }

    /**
     * [decideObjection]'s writes, **in the caller's transaction** under the case's lock — the race test holds them
     * uncommitted while another moderator decides the same objection (`ObjectionRacesSliceTest`). [lifted] hears the
     * measure a `LIFT` lifted, for the live views' announcement after the commit.
     */
    @Transactional(Transactional.TxType.MANDATORY)
    fun decideObjectionIn(
        moderatorId: String, caseId: String, objectionId: String, lift: Boolean, note: SealedNote?, now: Instant,
        lifted: (ModerationBlockEntity) -> Unit = {},
    ): DecisionOutcome {
        val case = lock(caseId) ?: return refused(CaseFailures.caseNotFound)
        val objection = ModerationObjectionEntity.find("id = ?1 and caseId = ?2", objectionId, case.id).firstResult()
            ?: return refused(CaseFailures.objectionNotFound)
        if (!objection.open) return refused(CaseFailures.objectionDecided)
        noteRefusal(note)?.let { return refused(it) }
        val statement = ModerationStatementEntity.findById(objection.statementId) ?: return refused(CaseFailures.objectionNotFound)
        val measure = ModerationBlockEntity.findById(statement.measureId) ?: return refused(CaseFailures.objectionNotFound)
        val own = measure.takenBy == moderatorId
        if (own && (currentEpochHolders() - moderatorId).isNotEmpty()) return refused(CaseFailures.needsAnotherModerator)
        objection.decidedAt = now
        objection.decidedBy = moderatorId
        objection.outcome = (if (lift) ObjectionOutcome.LIFTED else ObjectionOutcome.UPHELD).name
        objection.ownReview = own
        ModerationObjectionEntity.getEntityManager().flush()
        log.appendCase(
            if (own) ModerationEvent.OBJECTION_SELF_REVIEWED else ModerationEvent.OBJECTION_DECIDED, now, case.id,
            moderatorId = moderatorId, reason = objection.outcome, targetId = objection.id, epoch = note?.epoch, sealedText = note?.sealed,
        )
        if (lift && measure.activeKey != null) {
            liftIn(case, measure, moderatorId, null, now)
            lifted(measure)
        } else if (case.state == CaseState.DECIDED.name && holds.aliveOf(case.id, now) == null && !ModerationObjectionEntity.openOn(case.id)) {
            close(case, now)
        }
        return DecisionOutcome.Done
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

    /**
     * Renew the authority's deferral of [statementId] (ADR 0093 § 7, TCO Art. 11(3)): once, while the statement is still
     * held back, to an end after the current one and at most six weeks from now, the renewed request sealed as [reason].
     */
    fun renewDeferral(
        moderatorId: String, caseId: String, statementId: String, until: Instant, reason: SealedNote, now: Instant = Instant.now(),
    ): DecisionOutcome = createOrConflict(conflict = { refused(CaseFailures.deferralRenewed) }) {
        val case = lock(caseId) ?: return@createOrConflict refused(CaseFailures.caseNotFound)
        val statement = ModerationStatementEntity.find("id = ?1 and caseId = ?2", statementId, case.id).firstResult()
            ?: return@createOrConflict refused(CaseFailures.statementNotFound)
        val current = statement.deferredUntil
        if (statement.sentAt != null || current == null) return@createOrConflict refused(CaseFailures.statementNotDeferred)
        if (deferrals.renewed(statement.id)) return@createOrConflict refused(CaseFailures.deferralRenewed)
        if (!until.isAfter(current) || !deferrals.inBounds(until, now)) return@createOrConflict refused(CaseFailures.deferralOutOfBounds)
        noteRefusal(reason)?.let { return@createOrConflict refused(it) }
        deferrals.defer(statement, case.id, until, DeferralReason(moderatorId, null, reason.epoch, reason.sealed), renewal = true, now)
        DecisionOutcome.Done
    }

    /**
     * Record a handover to an authority (ADR 0093 §§ 11-13): the case was reported to one, or information was given to
     * one, with the reference sealed as [note]. **On a case of child sexual abuse it ends the hold** (ADR 0093 § 4,
     * § 184b(5) StGB: keeping the content is exempt only while it serves exclusively the report, and the handover
     * fulfils it) — except to an authority order's floor, which runs on; a decided case left with nothing held and no
     * objection waiting then closes. On every other case it changes no hold.
     */
    fun handover(moderatorId: String, caseId: String, kind: HandoverKind, note: SealedNote, now: Instant = Instant.now()): DecisionOutcome =
        QuarkusTransaction.requiringNew().call { handoverIn(moderatorId, caseId, kind, note, now) }

    /** [handover]'s writes, **in the caller's transaction** under the case's lock. */
    @Transactional(Transactional.TxType.MANDATORY)
    fun handoverIn(moderatorId: String, caseId: String, kind: HandoverKind, note: SealedNote, now: Instant): DecisionOutcome {
        val case = lock(caseId) ?: return refused(CaseFailures.caseNotFound)
        noteRefusal(note)?.let { return refused(it) }
        log.appendCase(kind.event, now, case.id, moderatorId = moderatorId, epoch = note.epoch, sealedText = note.sealed)
        if (case.category == ReportCategory.CHILD_SEXUAL_ABUSE.name) {
            if (holds.endNow(case.id, now)) log.appendCase(ModerationEvent.HOLD_ENDED, now, case.id, moderatorId = moderatorId)
            if (case.state == CaseState.DECIDED.name && holds.aliveOf(case.id, now) == null && !ModerationObjectionEntity.openOn(case.id)) {
                close(case, now)
            }
        }
        return DecisionOutcome.Done
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
     * decided case left with no hold and no objection waiting closes (ADR 0093 § 11). An open case
     * whose 90 days ran out stays open — undecided, in the queue, with nothing held.
     */
    fun holdsEnded(caseIds: List<String>, now: Instant = Instant.now()) = caseIds.forEach { caseId ->
        QuarkusTransaction.requiringNew().run {
            val case = lock(caseId) ?: return@run
            log.appendCase(ModerationEvent.HOLD_ENDED, now, case.id)
            if (case.state == CaseState.DECIDED.name && holds.aliveOf(case.id, now) == null && !ModerationObjectionEntity.openOn(case.id)) {
                close(case, now)
            }
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

    /**
     * The case is decided; its hold now ends six months after the statement was sent ([sentAt]) — after the decision
     * where nobody could be told — or later if an extension said so (ADR 0093 § 4).
     */
    private fun decide(case: ModerationCaseEntity, now: Instant, sentAt: Instant?) {
        case.state = CaseState.DECIDED.name
        if (case.decidedAt == null) case.decidedAt = now
        holds.aliveOf(case.id, now)?.let { hold ->
            val until = (sentAt ?: now).plus(Holds.SIX_MONTHS)
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
