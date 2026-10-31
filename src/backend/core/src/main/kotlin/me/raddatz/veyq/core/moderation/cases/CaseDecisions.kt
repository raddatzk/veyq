package me.raddatz.veyq.core.moderation.cases

import me.raddatz.veyq.core.backup.BlockBasis
import me.raddatz.veyq.core.backup.ErasureLedger
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
import me.raddatz.veyq.core.moderation.block.MeasureTargets
import me.raddatz.veyq.core.moderation.block.ModerationBlockEntity
import me.raddatz.veyq.core.moderation.block.ModerationBlockReachEntity
import me.raddatz.veyq.core.moderation.block.UploadedItems
import me.raddatz.veyq.core.moderation.block.ModerationProposalEntity
import me.raddatz.veyq.core.moderation.block.ProposalOutcome
import me.raddatz.veyq.core.moderation.block.ProposalStep
import me.raddatz.veyq.core.moderation.hold.CaseSnapshots
import me.raddatz.veyq.core.moderation.hold.HoldTarget
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
enum class MeasureStep { SHARE, CONTENT, UPLOADER_CONTENT, GUEST_IDENTITY }

/**
 * A step resolved against what its case knows: the block's kind, its target, (a blocked guest's) link, and — everything
 * from one uploader — the items it covers, counted now.
 */
data class ResolvedStep(val kind: BlockKind, val targetId: String, val shareId: String?, val reach: UploadedItems? = null)

/**
 * A measure's statement of reasons as the moderator gives it (ADR 0093 § 7): its client-minted [statementId], the
 * [ground], the [provision] of the law or clause of the terms it relies on (in the clear, DSA Art. 17(3)(d), (e)), the
 * [facts] sealed to the moderation key for the log, the facts sealed once for the affected under the statement's
 * `K_facts` ([sealedFacts]), and [factsKeyFor] — `K_facts` wrapped to each affected member's identity, by account id.
 */
data class StatementInput(
    val statementId: String,
    val ground: StatementGround,
    val facts: SealedNote?,
    val sealedFacts: String?,
    val factsKeyFor: Map<String, String>,
    val deferral: DeferralInput? = null,
    val provision: String? = null,
)

/** An authority's deferral as the moderator enters it (ADR 0093 § 7): its end, and the authority's request sealed. */
data class DeferralInput(val until: Instant, val reason: SealedNote)

/** A handover to an authority (ADR 0093 §§ 11-13): reported to one, or information given to one. */
enum class HandoverKind(val event: ModerationEvent) { REPORTED(ModerationEvent.HANDED_OVER), INFORMATION_GIVEN(ModerationEvent.INFORMATION_GIVEN) }

/** The reason types that extend a hold on a case of child sexual abuse material (ADR 0093 § 4, `HoldExtensionReason`). */
val CSAM_EXTENSION_REASONS: Set<String?> = setOf("AUTHORITY_REFERENCE", "ART18_REPORT_IN_PROGRESS")

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
    val recipientsTooMany = httpFailure("statement_recipients_too_many", FailureStatus.UNPROCESSABLE)
    val idTaken = httpFailure("id_taken", FailureStatus.CONFLICT)
    val objectionNotFound = httpFailure("objection_not_found", FailureStatus.NOT_FOUND)
    val objectionDecided = httpFailure("objection_decided", FailureStatus.CONFLICT)
    val needsAnotherModerator = httpFailure("objection_needs_another_moderator", FailureStatus.CONFLICT)
    val deferralOutOfBounds = httpFailure("deferral_out_of_bounds", FailureStatus.BAD_REQUEST)
    val statementNotFound = httpFailure("statement_not_found", FailureStatus.NOT_FOUND)
    val statementNotDeferred = httpFailure("statement_not_deferred", FailureStatus.CONFLICT)
    val deferralRenewed = httpFailure("deferral_renewed", FailureStatus.CONFLICT)
    val extensionReasonNotAllowed = httpFailure("extension_reason_not_allowed", FailureStatus.CONFLICT)
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
    private val mail: StatementMail,
    private val deferrals: StatementDeferrals,
    private val ledger: ErasureLedger,
    private val measureTargets: MeasureTargets,
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
    private fun announce(row: ModerationBlockEntity) {
        val scopeId = row.scopeId ?: return
        val bumpTree = { QuarkusTransaction.requiringNew().run { nodes.lockScope(scopeId); nodes.bumpTree(scopeId) } }
        val itemsChanged = { ids: List<String> -> QuarkusTransaction.requiringNew().run { ids.forEach { id -> items.forEach { it.blockChanged(id) } } } }
        when (row.blockKind) {
            BlockKind.CONTENT_NODE -> bumpTree()
            BlockKind.CONTENT_ITEM -> itemsChanged(listOf(row.targetId))
            // Everything from one uploader: its nodes change the level view, its items their lists.
            BlockKind.CONTENT_UPLOADER -> {
                val reach = QuarkusTransaction.requiringNew().call { ModerationBlockReachEntity.list("blockId", row.id) }
                if (reach.any { it.itemKind == ModerationBlockReachEntity.NODE }) bumpTree()
                reach.filter { it.itemKind == ModerationBlockReachEntity.ITEM }.map { it.targetId }.takeIf { it.isNotEmpty() }?.let(itemsChanged)
            }
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
                    // A reported name blocks its node: the server serves no node without its name.
                    ReportItemKind.NODE.name, ReportItemKind.NAME.name -> BlockKind.CONTENT_NODE
                    ReportItemKind.ITEM.name -> BlockKind.CONTENT_ITEM
                    else -> null
                }
                when {
                    targetId == null || shareId != null -> null to CaseFailures.invalid
                    kind == null || targetId != case.itemId -> null to CaseFailures.targetUnknown
                    else -> ResolvedStep(kind, targetId, null) to null
                }
            }
            MeasureStep.UPLOADER_CONTENT -> {
                val uploader = known.uploaders.firstOrNull { it.id == targetId }
                when {
                    targetId == null || shareId != null -> null to CaseFailures.invalid
                    // An uploader the case does not name, or one with nothing in the space to block: no step to take.
                    uploader == null || uploader.items.size == 0 -> null to CaseFailures.targetUnknown
                    else -> ResolvedStep(BlockKind.CONTENT_UPLOADER, targetId, uploader.guestShareId, uploader.items) to null
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
        if (members.size > StatementRecipients.MAX_SEALED) return@call null to CaseFailures.recipientsTooMany
        val keys = if (members.isEmpty()) emptyMap() else VeyqUser.list("id in ?1", members).associate { it.id to it.identityPublicKey }
        found.map { it to keys[it.id] } to null
    }

    fun take(
        moderatorId: String, caseId: String, step: MeasureStep, shareId: String?, targetId: String?, note: SealedNote?,
        statement: StatementInput, reporters: List<ReporterNotice> = emptyList(), now: Instant = Instant.now(),
    ): DecisionOutcome {
        val taken = createOrConflict(conflict = { Taken(refused(CaseFailures.measureExists)) }) {
            takeIn(moderatorId, caseId, step, shareId, targetId, note, statement, now)
        }
        // A measure that ended at once (its target was gone) changed no live view; one hung on a surviving object
        // announces an item no list holds any more — a no-op.
        taken.changed?.takeIf { it.activeKey != null }?.let { announce(it) }
        taken.statementId?.let { id ->
            mail.statementSent(id)
            mail.tellReporters(caseId, reporters, StatementMail.Decision.DECIDED, moderatorId)
        }
        return taken.outcome
    }

    /** What [takeIn] did: its outcome, the measure it wrote and its statement — announced and mailed after the commit. */
    class Taken(val outcome: DecisionOutcome, val changed: ModerationBlockEntity? = null, val statementId: String? = null)

    /**
     * [take]'s writes, **in the caller's transaction** — the race test holds them uncommitted while the target is erased
     * (`MeasureTargetErasureRacesSliceTest`). The target's row is locked before the measure is written
     * ([MeasureTargets.lockStanding]); a target already erased gets its measure ended at once, as its eraser would have —
     * or, an item whose stored object another row still holds, hung on that object ([MeasureTargets.survivingObjects]).
     */
    @Transactional(Transactional.TxType.MANDATORY)
    fun takeIn(
        moderatorId: String, caseId: String, step: MeasureStep, shareId: String?, targetId: String?, note: SealedNote?,
        statement: StatementInput, now: Instant,
    ): Taken {
        val case = lock(caseId) ?: return Taken(refused(CaseFailures.caseNotFound))
        if (case.state == CaseState.CLOSED.name) return Taken(refused(CaseFailures.caseClosed))
        noteRefusal(note)?.let { return Taken(refused(it)) }
        val held = snapshots.rowsOf(case.id, now)
        val known = targets.of(case, held)
        val (resolved, refusal) = resolve(case, step, shareId, targetId, known)
        if (resolved == null) return Taken(refused(refusal!!))
        // Every statement names the facts and the provision or clause the ground relies on (DSA Art. 17(3)(d), (e);
        // ADR 0093 § 7: one form on every instance, § 14).
        val facts = statement.facts ?: return Taken(refused(CaseFailures.factsRequired))
        if (statement.provision.isNullOrBlank()) return Taken(refused(CaseFailures.provisionRequired))
        noteRefusal(facts)?.let { return Taken(refused(it)) }
        // The log's copy and the members' copy come together (the wraps of its key are checked against the members below).
        if (statement.sealedFacts == null) return Taken(refused(CaseFailures.invalid))
        // The client minted the statement's id and sealed the facts bound to it (ADR 0072 Decision 3): never substituted.
        if (ModerationStatementEntity.findById(statement.statementId) != null) return Taken(refused(CaseFailures.idTaken))
        statement.deferral?.let { d ->
            if (!deferrals.inBounds(d.until, now)) return Taken(refused(CaseFailures.deferralOutOfBounds))
            noteRefusal(d.reason)?.let { return Taken(refused(it)) }
        }
        val told = recipients.of(case, resolved, known, held)
        val members = told.filter { it.kind == RecipientKind.ACCOUNT }.map { it.id }.toSet()
        if (members.size > StatementRecipients.MAX_SEALED) return Taken(refused(CaseFailures.recipientsTooMany))
        if (statement.factsKeyFor.keys != members) return Taken(refused(CaseFailures.recipientsChanged))
        val row = block(resolved.kind, resolved.targetId, case, moderatorId, now).apply {
            if (resolved.kind.isContent) scopeId = case.scopeId
            if (resolved.kind == BlockKind.GUEST_IDENTITY || resolved.kind == BlockKind.CONTENT_UPLOADER) this.shareId = resolved.shareId
            activeKey = ownActiveKey()
        }
        if (ModerationBlockEntity.count("activeKey", row.activeKey!!) > 0) return Taken(refused(CaseFailures.measureExists))
        // The target's row lock, the one its eraser takes: an erasure either committed before (gone, ended below) or waits.
        val standing = measureTargets.lockStanding(row.blockKind, row.targetId)
        // An item already erased whose stored object another row still holds (a cover, a banner): the measure hangs on
        // that object, as it would had the item been erased after it (ADR 0093 § 6) — read from the case's hold.
        val surviving = if (standing) emptySet() else measureTargets.survivingObjects(row.blockKind, row.targetId, held)
        row.persist()
        resolved.reach?.let { reach -> reachOf(row, reach, case, now) }
        ledger.blockTaken(row, BlockBasis.MEASURE)
        log.appendCase(
            ModerationEvent.MEASURE_TAKEN, now, case.id, moderatorId = moderatorId, reason = row.kind, targetId = row.targetId,
            epoch = note?.epoch, sealedText = note?.sealed,
        )
        // Sent when a member can be told; where only guests are named, nothing is sent — a guest reads it on its link from
        // the decision on (GuestNotices) — and the hold counts from the decision (ADR 0093 § 4). An authority's deferral holds it back until its end (ADR 0093 § 7).
        val sentAt = if (members.isNotEmpty() && statement.deferral == null) now else null
        val written = ModerationStatementEntity().apply {
            id = statement.statementId
            measureId = row.id
            this.caseId = case.id
            ground = statement.ground.name
            provision = statement.provision.trim().takeIf { it.isNotEmpty() }
            basis = StatementBasis.REPORT.name
            epoch = facts.epoch
            sealedFacts = facts.sealed
            sealedMemberFacts = statement.sealedFacts
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
                wrappedFactsKey = statement.factsKeyFor[r.id]
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
        // A measure never stands on an erased target (MeasureTargets): one that is gone ends at once, lifted by no one —
        // the decision and its statement stay, and the hold runs to its own end (ADR 0093 § 4) — unless it hangs on an
        // object that survives its item (above), and ends with that object's last row or a lift.
        if (!standing) {
            measureTargets.erased(
                listOf(row.blockKind), listOf(row.targetId), now,
                holding = if (surviving.isEmpty()) emptyMap() else mapOf(row.targetId to surviving),
            )
        }
        return Taken(DecisionOutcome.Done, row, written.id)
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
        changed?.let { announce(it) }
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
        ledger.blockLifted(row)
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
        changed?.let { announce(it) }
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

    /**
     * Extend [caseId]'s hold to [until] for [reason], whose kind [reasonType] is named in the clear (ADR 0093 § 4). On a
     * case of child sexual abuse material only [CSAM_EXTENSION_REASONS] extend it — keeping that content serves only the
     * report to an authority (§ 184b(5) StGB) — and anything else, or no type, is refused (owner 2026-10-18).
     */
    fun extend(
        moderatorId: String, caseId: String, until: Instant, reason: SealedNote, now: Instant = Instant.now(), reasonType: String? = null,
    ): DecisionOutcome =
        QuarkusTransaction.requiringNew().call {
            val case = lock(caseId) ?: return@call refused(CaseFailures.caseNotFound)
            if (case.category == ReportCategory.CHILD_SEXUAL_ABUSE.name && reasonType !in CSAM_EXTENSION_REASONS) {
                return@call refused(CaseFailures.extensionReasonNotAllowed)
            }
            val hold = holds.aliveOf(case.id, now) ?: return@call refused(CaseFailures.nothingHeld)
            if (!until.isAfter(hold.expiresAt) || until.isAfter(now.plus(Holds.SIX_MONTHS))) {
                return@call refused(CaseFailures.extensionOutOfBounds)
            }
            noteRefusal(reason)?.let { return@call refused(it) }
            holds.moveEnd(hold, until, HoldTrigger.EXTENDED)
            log.appendCase(
                ModerationEvent.HOLD_EXTENDED, now, case.id, moderatorId = moderatorId, epoch = reason.epoch,
                sealedText = reason.sealed, holdUntil = until, reason = reasonType,
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
            // The reporter's account or link row, as at a measure (MeasureTargets): a bar on one already erased ends at once.
            val standing = measureTargets.lockStanding(row.blockKind, row.targetId)
            row.persist()
            ledger.blockTaken(row, BlockBasis.MEASURE)
            log.appendCase(
                ModerationEvent.REPORTER_BARRED, now, case.id, moderatorId = moderatorId, reason = row.kind, targetId = row.targetId,
                epoch = note?.epoch, sealedText = note?.sealed,
            )
            if (!standing) measureTargets.erased(listOf(row.blockKind), listOf(row.targetId), now)
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

    /**
     * An uploader's block: the items it covers — exactly those counted now, never what the uploader adds later — and the
     * case's hold widened to them, by the hold's own rules (ADR 0093 § 4: its end, its pins; a new hold where none is
     * alive, ended as a measure's is, by [decide]).
     */
    private fun reachOf(row: ModerationBlockEntity, reach: UploadedItems, case: ModerationCaseEntity, now: Instant) {
        reach.nodeIds.forEach { reachRow(row.id, ModerationBlockReachEntity.NODE, it) }
        reach.itemIds.forEach { reachRow(row.id, ModerationBlockReachEntity.ITEM, it) }
        val target = HoldTarget.Uploads(case.scopeId, reach.nodeIds.toList(), reach.itemIds.toList())
        val alive = holds.aliveOf(case.id, now)
        if (alive != null) {
            holds.widen(alive, target)
        } else {
            holds.take(case.id, target, HoldTrigger.MEASURE, now.plus(Holds.SIX_MONTHS))
        }
    }

    private fun reachRow(blockId: String, itemKind: String, targetId: String) = ModerationBlockReachEntity().apply {
        this.blockId = blockId
        this.itemKind = itemKind
        this.targetId = targetId
    }.persist()

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
