package me.raddatz.veyq.core.moderation.order

import me.raddatz.veyq.core.admin.operator.OperatorDetailsHandler
import me.raddatz.veyq.core.content.NodesStore
import me.raddatz.veyq.core.generated.model.AuthorityOrder
import me.raddatz.veyq.core.http.FailureStatus
import me.raddatz.veyq.core.http.HttpFailure
import me.raddatz.veyq.core.http.httpFailure
import me.raddatz.veyq.core.moderation.ModerationEpochEntity
import me.raddatz.veyq.core.moderation.ModerationEvent
import me.raddatz.veyq.core.moderation.ModerationFailures
import me.raddatz.veyq.core.moderation.ModerationLog
import me.raddatz.veyq.core.moderation.block.BlockKind
import me.raddatz.veyq.core.moderation.block.ModerationBlockEntity
import me.raddatz.veyq.core.moderation.cases.CaseTargets
import me.raddatz.veyq.core.moderation.cases.ResolvedStep
import me.raddatz.veyq.core.moderation.hold.CaseSnapshots
import me.raddatz.veyq.core.moderation.hold.HoldTarget
import me.raddatz.veyq.core.moderation.hold.HoldTrigger
import me.raddatz.veyq.core.moderation.hold.Holds
import me.raddatz.veyq.core.moderation.report.CaseNotices
import me.raddatz.veyq.core.moderation.report.CaseOrigin
import me.raddatz.veyq.core.moderation.report.CaseState
import me.raddatz.veyq.core.moderation.report.ModerationCaseEntity
import me.raddatz.veyq.core.moderation.report.ReportCategory
import me.raddatz.veyq.core.moderation.report.ReportItemKind
import me.raddatz.veyq.core.moderation.statement.DeferralReason
import me.raddatz.veyq.core.moderation.statement.ModerationStatementEntity
import me.raddatz.veyq.core.moderation.statement.RecipientKind
import me.raddatz.veyq.core.moderation.statement.StatementBasis
import me.raddatz.veyq.core.moderation.statement.StatementDeferrals
import me.raddatz.veyq.core.moderation.statement.StatementGround
import me.raddatz.veyq.core.moderation.statement.StatementMail
import me.raddatz.veyq.core.moderation.statement.StatementRecipientEntity
import me.raddatz.veyq.core.moderation.statement.StatementRecipients
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.persistence.createOrConflict
import me.raddatz.veyq.core.sharing.ShareEntity
import me.raddatz.veyq.core.sharing.SharingStore
import io.quarkus.narayana.jta.QuarkusTransaction
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

/** An order as either door hands it over, its shape already bounded by the contract. */
data class OrderIn(
    val id: String,
    val kind: OrderKind,
    val shareId: String,
    val receivedAt: Instant,
    val epoch: Int,
    val sealedReference: String,
    val holdDays: Int?,
    val deferStatementUntil: Instant?,
)

/** What entering an order answers. */
sealed interface OrderOutcome {
    data class Entered(val order: AuthorityOrder) : OrderOutcome
    data class Refused(val failure: HttpFailure) : OrderOutcome, HttpFailure by failure
}

/** An order's refusals (codes per `moderation.openapi.yaml` / `instance.openapi.yaml`). */
object OrderFailures {
    val invalid = httpFailure("invalid_request", FailureStatus.BAD_REQUEST)
    val deferralOutOfBounds = httpFailure("deferral_out_of_bounds", FailureStatus.BAD_REQUEST)
    val orderExists = httpFailure("order_exists", FailureStatus.CONFLICT)
    val epochChanged = httpFailure("moderation_epoch_changed", FailureStatus.CONFLICT)
}

/**
 * An authority's order (ADR 0093 §§ 4, 7, 12; ADR 0099 § 6; uc.moderation.authority-order): **one handler behind two
 * doors** — the moderator's on the public listener (role + key proof) and the Server Owner's on the admin channel (no
 * key). An order acts on ids: it needs no key, reads no content, and its reference arrives sealed by the entering client.
 *
 * **In one transaction**, with no review of its own (the authority has reviewed): the order's row; for a share the server
 * knows, a case of its own (origin `ORDER`, decided from the start, keyed `ORDER:<id>` so it never takes a report's
 * place) with a hold on the subtree the share is rooted at, whose end is also its **floor** (ADR 0093 § 4: no extension,
 * lift or decision ends it sooner) — six months after the block for a removal order, 60 days after receipt for a
 * preservation order, the named days (else six months) after receipt for a request for information. A **removal order**
 * blocks the share and the node it is rooted at (with everything below it, `CONTENT_NODE`); a block already in force on
 * either is kept and counts with its own time. Every block it takes carries its statement of reasons (basis `ORDER`, the
 * ground terrorist content, the order being its facts), held back by the authority's deferral when the order brings one.
 * A share the server does not know is recorded with no case and no block — the Annex III draft's "not found".
 *
 * **Lock: the share's space row** ([NodesStore.lockScope]), as a report takes it, before the hold copies the subtree.
 * After the commit: the live views learn the content block, the statements' mails and the urgent new-case mail go out.
 */
@ApplicationScoped
class AuthorityOrderHandler(
    private val nodes: NodesStore,
    private val sharing: SharingStore,
    private val holds: Holds,
    private val snapshots: CaseSnapshots,
    private val targets: CaseTargets,
    private val recipients: StatementRecipients,
    private val deferrals: StatementDeferrals,
    private val log: ModerationLog,
    private val mail: StatementMail,
    private val notices: CaseNotices,
    private val operator: OperatorDetailsHandler,
) {

    /** What [enterIn] wrote: the order, the space whose live views learn a content block, the statements that went out. */
    data class Written(val order: AuthorityOrderEntity, val contentScope: String?, val statements: List<String>)

    fun enter(enteredBy: String, entered: OrderIn, at: Instant = Instant.now()): OrderOutcome {
        // Stored to the millisecond (TIMESTAMP(3)): the answer names the times exactly as a later read will.
        val now = at.truncatedTo(ChronoUnit.MILLIS)
        val order = entered.copy(
            receivedAt = entered.receivedAt.truncatedTo(ChronoUnit.MILLIS),
            deferStatementUntil = entered.deferStatementUntil?.truncatedTo(ChronoUnit.MILLIS),
        )
        shapeRefusal(order, now)?.let { return OrderOutcome.Refused(it) }
        val (written, refusal) = createOrConflict(
            // A second attempt sees what won the race: the same order (order_exists), or a block now in force, which it keeps.
            conflict = { enterIn(enteredBy, order, now) },
        ) { enterIn(enteredBy, order, now) }
        if (written == null) return OrderOutcome.Refused(refusal!!)
        val w = written
        w.contentScope?.let { scopeId ->
            QuarkusTransaction.requiringNew().run {
                nodes.lockScope(scopeId)
                nodes.bumpTree(scopeId)
            }
        }
        w.statements.forEach { mail.statementSent(it) }
        if (w.order.caseId != null) runCatching { notices.mailUrgent(now) }
        return OrderOutcome.Entered(QuarkusTransaction.requiringNew().call { view(w.order, contact()) })
    }

    /** The orders recorded, newest first (at most [LIST_LIMIT]). */
    fun list(): List<AuthorityOrder> = QuarkusTransaction.requiringNew().call {
        val contact = contact()
        AuthorityOrderEntity.find("order by enteredAt desc").page(0, LIST_LIMIT).list().map { view(it, contact) }
    }

    /** [caseId]'s orders, in the caller's transaction (the case view's). */
    fun ofCase(caseId: String): List<AuthorityOrder> {
        val rows = AuthorityOrderEntity.list("caseId = ?1 order by enteredAt", caseId)
        if (rows.isEmpty()) return emptyList()
        val contact = contact()
        return rows.map { view(it, contact) }
    }

    /** What the request alone decides, before anything is read: the kind's own fields, the receipt, the deferral's bounds. */
    private fun shapeRefusal(o: OrderIn, now: Instant): HttpFailure? = when {
        o.holdDays != null && o.kind != OrderKind.INFORMATION -> OrderFailures.invalid
        o.deferStatementUntil != null && o.kind != OrderKind.REMOVAL -> OrderFailures.invalid
        o.receivedAt.isAfter(now.plus(CLOCK_SKEW)) -> OrderFailures.invalid
        o.deferStatementUntil != null && !deferrals.inBounds(o.deferStatementUntil, now) -> OrderFailures.deferralOutOfBounds
        else -> null
    }

    /**
     * The order's writes **in the caller's transaction** — the written order, or the refusal (nothing written then); the
     * race test holds them uncommitted while a second order names the same share (`AuthorityOrderRacesSliceTest`).
     */
    @Transactional(Transactional.TxType.MANDATORY)
    fun enterIn(enteredBy: String, o: OrderIn, now: Instant): Pair<Written?, HttpFailure?> {
        val current = ModerationEpochEntity.find("order by epoch desc").firstResult()?.epoch
            ?: return null to ModerationFailures.moderationOff
        if (current != o.epoch) return null to OrderFailures.epochChanged
        if (AuthorityOrderEntity.findById(o.id) != null) return null to OrderFailures.orderExists
        val row = AuthorityOrderEntity().apply {
            id = o.id
            kind = o.kind.name
            shareId = o.shareId
            receivedAt = o.receivedAt
            enteredAt = now
            this.enteredBy = enteredBy
            epoch = o.epoch
            sealedReference = o.sealedReference
        }
        val share = ShareEntity.findById(o.shareId)
        if (share == null) {
            row.persist()
            // No case to hang it on: an instance-shaped event, dated as the one who entered it is.
            log.append(ModerationEvent.ORDER_RECEIVED, now, enteredBy, enteredBy, reason = o.kind.name, targetId = row.id)
            return Written(row, null, emptyList()) to null
        }
        nodes.lockScope(share.scopeId)
        val case = ModerationCaseEntity().apply {
            id = UUID.randomUUID().toString()
            itemKind = ReportItemKind.NODE.name
            itemId = share.nodeId
            scopeId = share.scopeId
            category = (if (o.kind == OrderKind.REMOVAL) ReportCategory.TERRORIST_CONTENT else ReportCategory.ILLEGAL_CONTENT).name
            state = CaseState.DECIDED.name
            decidedAt = now
            origin = CaseOrigin.ORDER.name
            openKey = ModerationCaseEntity.orderKeyOf(row.id)
            noticeDue = if (o.kind == OrderKind.REMOVAL) ModerationCaseEntity.NOTICE_URGENT else ModerationCaseEntity.NOTICE_BUNDLE
        }
        case.persist()
        row.caseId = case.id
        row.persist()
        log.appendCase(ModerationEvent.ORDER_RECEIVED, now, case.id, moderatorId = enteredBy, reason = o.kind.name, targetId = row.id)

        val nodeExists = NodeEntity.findById(share.nodeId) != null
        val blocked = mutableListOf<ModerationBlockEntity>()
        var blockedAt: Instant? = null
        if (o.kind == OrderKind.REMOVAL) {
            val wanted = listOfNotNull(BlockKind.SHARE to share.id, if (nodeExists) BlockKind.CONTENT_NODE to share.nodeId else null)
            val times = wanted.map { (kind, target) ->
                ModerationBlockEntity.find("activeKey", ModerationBlockEntity.activeKeyOf(kind, target)).firstResult()?.takenAt
                    ?: block(kind, target, case, share.scopeId, enteredBy, now).also { blocked += it }.takenAt
            }
            blockedAt = times.max()
        }
        val end = when (o.kind) {
            OrderKind.REMOVAL -> blockedAt!!.plus(Holds.SIX_MONTHS)
            OrderKind.PRESERVATION -> o.receivedAt.plus(Holds.PRESERVATION)
            OrderKind.INFORMATION -> o.holdDays?.let { o.receivedAt.plus(Duration.ofDays(it.toLong())) } ?: o.receivedAt.plus(Holds.SIX_MONTHS)
        }
        val subtree = (sharing.liveSubtreeIds(share.scopeId, share.nodeId) + share.nodeId).distinct()
        holds.take(case.id, HoldTarget.Subtree(share.scopeId, subtree), HoldTrigger.ORDER, end, floorUntil = end)
        row.blockedAt = blockedAt
        row.holdUntil = end

        val statements = blocked.mapNotNull { measure -> statementFor(measure, case, row, o.deferStatementUntil, now) }
        return Written(row, if (blocked.any { it.blockKind == BlockKind.CONTENT_NODE }) share.scopeId else null, statements) to null
    }

    private fun block(kind: BlockKind, target: String, case: ModerationCaseEntity, scopeId: String, by: String, now: Instant) =
        ModerationBlockEntity().apply {
            id = UUID.randomUUID().toString()
            this.kind = kind.name
            targetId = target
            caseId = case.id
            if (kind == BlockKind.CONTENT_NODE) this.scopeId = scopeId
            takenBy = by
            takenAt = now
            activeKey = ModerationBlockEntity.activeKeyOf(kind, target)
        }.also { row ->
            row.persist()
            log.appendCase(ModerationEvent.MEASURE_TAKEN, now, case.id, moderatorId = by, reason = row.kind, targetId = row.targetId)
        }

    /**
     * [measure]'s statement of reasons (ADR 0093 § 7, § 12 "the notice to the uploader follows § 7"): basis `ORDER`, the
     * ground terrorist content, no facts — the order is what led to it — to the recipients a moderator's block would
     * reach. Sent now where a member is named, unless the authority deferred it. Answers its id when it went out now.
     */
    private fun statementFor(measure: ModerationBlockEntity, case: ModerationCaseEntity, order: AuthorityOrderEntity, deferUntil: Instant?, now: Instant): String? {
        val held = snapshots.rowsOf(case.id, now)
        val known = targets.of(case, held)
        val told = recipients.of(case, ResolvedStep(measure.blockKind, measure.targetId, null), known, held)
        val members = told.any { it.kind == RecipientKind.ACCOUNT }
        val sentAt = if (members && deferUntil == null) now else null
        val statement = ModerationStatementEntity().apply {
            id = UUID.randomUUID().toString()
            measureId = measure.id
            caseId = case.id
            ground = StatementGround.TERRORIST_CONTENT.name
            basis = StatementBasis.ORDER.name
            this.sentAt = sentAt
        }
        statement.persist()
        told.forEach { r ->
            StatementRecipientEntity().apply {
                id = UUID.randomUUID().toString()
                statementId = statement.id
                recipientKind = r.kind.name
                recipientId = r.id
                roles = r.roles.joinToString(",") { it.name }
            }.persist()
        }
        if (sentAt != null) {
            log.appendCase(
                ModerationEvent.STATEMENT_SENT, now, case.id, moderatorId = order.enteredBy, reason = statement.ground, targetId = statement.id,
            )
        }
        if (deferUntil != null) {
            deferrals.defer(statement, case.id, deferUntil, DeferralReason(order.enteredBy, order.id, null, null), renewal = false, now)
        }
        return statement.id.takeIf { sentAt != null }
    }

    private fun contact(): Pair<String?, String?> = operator.publicValues().let { it.providerName to it.contactEmail }

    private fun view(o: AuthorityOrderEntity, contact: Pair<String?, String?>): AuthorityOrder {
        val deferredUntil = o.caseId?.let { caseId ->
            ModerationStatementEntity.list("caseId = ?1 and sentAt is null and deferredUntil is not null", caseId)
                .mapNotNull { it.deferredUntil }.minOrNull()
        }
        return AuthorityOrder(
            id = UUID.fromString(o.id),
            kind = AuthorityOrder.Kind.valueOf(o.kind),
            shareId = o.shareId,
            receivedAt = o.receivedAt.utc(),
            enteredAt = o.enteredAt.utc(),
            enteredBy = UUID.fromString(o.enteredBy),
            found = o.caseId != null,
            blockedAt = o.blockedAt?.utc(),
            caseId = o.caseId?.let(UUID::fromString),
            holdUntil = o.holdUntil?.utc(),
            statementDeferredUntil = deferredUntil?.utc(),
            epoch = o.epoch,
            sealedReference = o.sealedReference,
            providerName = contact.first,
            contactPoint = contact.second,
        )
    }

    private fun Instant.utc(): OffsetDateTime = atOffset(ZoneOffset.UTC)

    private companion object {
        const val LIST_LIMIT = 500

        /** How far a receipt time may lie ahead of the server's clock before it is refused as a typo. */
        val CLOCK_SKEW: Duration = Duration.ofMinutes(5)
    }
}
