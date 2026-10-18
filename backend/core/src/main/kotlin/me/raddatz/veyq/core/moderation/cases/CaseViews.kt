package me.raddatz.veyq.core.moderation.cases

import me.raddatz.veyq.core.admin.operator.OperatingModeHandler
import me.raddatz.veyq.core.generated.model.ModerationCase
import me.raddatz.veyq.core.generated.model.ModerationCaseEvent
import me.raddatz.veyq.core.generated.model.ModerationCaseOrigin
import me.raddatz.veyq.core.moderation.order.AuthorityOrderEntity
import me.raddatz.veyq.core.moderation.order.AuthorityOrderHandler
import me.raddatz.veyq.core.moderation.statement.StatementDeferralEntity
import me.raddatz.veyq.core.generated.model.ModerationCaseState
import me.raddatz.veyq.core.generated.model.ModerationCaseSummary
import me.raddatz.veyq.core.generated.model.ModerationHold
import me.raddatz.veyq.core.generated.model.ModerationItemKind
import me.raddatz.veyq.core.generated.model.ModerationMeasure
import me.raddatz.veyq.core.generated.model.ModerationMeasureKind
import me.raddatz.veyq.core.generated.model.ModerationMeasureStatement
import me.raddatz.veyq.core.generated.model.ModerationObjection
import me.raddatz.veyq.core.generated.model.ModerationGround
import me.raddatz.veyq.core.generated.model.ObjectionOutcome
import me.raddatz.veyq.core.generated.model.StatementBasis
import me.raddatz.veyq.core.generated.model.StatementForm
import me.raddatz.veyq.core.generated.model.ModerationProposal
import me.raddatz.veyq.core.generated.model.ModerationProposalStep
import me.raddatz.veyq.core.generated.model.ModerationReport
import me.raddatz.veyq.core.generated.model.ModerationTargetLink
import me.raddatz.veyq.core.generated.model.ModerationTargetUploader
import me.raddatz.veyq.core.generated.model.ModerationTargets
import me.raddatz.veyq.core.moderation.ModerationEvent
import me.raddatz.veyq.core.moderation.ModerationEventEntity
import me.raddatz.veyq.core.moderation.block.BlockKind
import me.raddatz.veyq.core.moderation.block.ModerationBlockEntity
import me.raddatz.veyq.core.moderation.block.ModerationBlocks
import me.raddatz.veyq.core.moderation.block.ModerationProposalEntity
import me.raddatz.veyq.core.moderation.hold.Holds
import me.raddatz.veyq.core.moderation.report.CaseState
import me.raddatz.veyq.core.moderation.report.ModerationCaseEntity
import me.raddatz.veyq.core.moderation.report.ModerationReportEntity
import me.raddatz.veyq.core.moderation.report.ReportCategory
import me.raddatz.veyq.core.moderation.report.ReportShareEntity
import me.raddatz.veyq.core.moderation.statement.ModerationObjectionEntity
import me.raddatz.veyq.core.moderation.statement.ModerationStatementEntity
import me.raddatz.veyq.core.moderation.statement.RecipientKind
import me.raddatz.veyq.core.moderation.statement.StatementMail
import me.raddatz.veyq.core.moderation.statement.StatementRecipientEntity
import me.raddatz.veyq.core.scope.ScopeEntity
import me.raddatz.veyq.core.tenant.TenantEntity
import io.quarkus.narayana.jta.QuarkusTransaction
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * The moderation surface's reads (uc.moderation.review-a-case steps 1-2): the instance-wide queue in clear fields, and one
 * case with its sealed reports, measures and their statements, objections, proposals, hold, targets and log. Nothing here
 * opens anything: the reports, the notes, the facts and the objections stay sealed, the browser opens them with the
 * epoch's key. The statement's form is the operating mode's now (ADR 0093 § 14), and whether the caller may decide an
 * objection is the separation of duties' answer for that caller (ADR 0099 § 5).
 */
@ApplicationScoped
class CaseViews(
    private val holds: Holds,
    private val blocks: ModerationBlocks,
    private val targets: CaseTargets,
    private val operatingMode: OperatingModeHandler,
    private val orders: AuthorityOrderHandler,
) {

    /**
     * The queue: [state]'s cases, or the open and decided ones; the urgent categories first, then the oldest. A report's
     * case is dated by its reports, an order's by its orders' entry.
     */
    fun queue(state: CaseState?, now: Instant = Instant.now()): List<ModerationCaseSummary> = QuarkusTransaction.requiringNew().call {
        val states = state?.let { listOf(it.name) } ?: listOf(CaseState.OPEN.name, CaseState.DECIDED.name)
        val cases = ModerationCaseEntity.list("state in ?1", states)
        if (cases.isEmpty()) return@call emptyList()
        val ids = cases.map { it.id }
        val shares = ReportShareEntity.list("caseId in ?1", ids).groupBy { it.caseId }
        val reports = ModerationReportEntity.list("id in ?1", shares.values.flatten().map { it.reportId }.distinct().ifEmpty { listOf("") })
            .associateBy { it.id }
        val entered = AuthorityOrderEntity.list("caseId in ?1", ids).groupBy({ it.caseId!! }, { it.enteredAt })
        val deferred = ModerationStatementEntity.list("caseId in ?1 and sentAt is null and deferredUntil is not null", ids)
            .groupBy({ it.caseId }, { it.deferredUntil!! })
        cases.mapNotNull { case ->
            val times = shares[case.id].orEmpty().mapNotNull { reports[it.reportId]?.receivedAt } + entered[case.id].orEmpty()
            if (times.isEmpty()) return@mapNotNull null
            case to times
        }.sortedWith(compareBy({ ReportCategory.valueOf(it.first.category).ordinal }, { it.second.min() })).map { (case, times) ->
            ModerationCaseSummary(
                id = UUID.fromString(case.id),
                itemKind = ModerationItemKind.valueOf(case.itemKind),
                category = ModerationCaseSummary.Category.valueOf(case.category),
                state = ModerationCaseState.valueOf(case.state),
                firstReportedAt = times.min().utc(),
                lastReportedAt = times.max().utc(),
                reportCount = shares[case.id].orEmpty().size,
                tenantDomain = tenantOf(case.scopeId)?.takeIf { it.status == TenantEntity.ACTIVE }?.domain,
                holdEndsAt = holds.aliveOf(case.id, now)?.expiresAt?.utc(),
                objectionOpen = ModerationObjectionEntity.openOn(case.id),
                origin = ModerationCaseOrigin.valueOf(case.origin),
                statementDeferredUntil = deferred[case.id]?.minOrNull()?.utc(),
            )
        }
    }

    /** One case as [callerId] sees it, or null. */
    fun case(caseId: String, callerId: String, now: Instant = Instant.now()): ModerationCase? = QuarkusTransaction.requiringNew().call {
        val case = ModerationCaseEntity.findById(caseId) ?: return@call null
        val shares = ReportShareEntity.list("caseId", case.id)
        val reports = ModerationReportEntity.list("id in ?1", shares.map { it.reportId }.ifEmpty { listOf("") }).sortedBy { it.receivedAt }
        val tenant = tenantOf(case.scopeId)
        val hold = holds.aliveOf(case.id, now)
        val known = targets.of(case, now)
        val statements = ModerationStatementEntity.list("caseId", case.id).associateBy { it.measureId }
        val recipients = if (statements.isEmpty()) emptyMap()
        else StatementRecipientEntity.list("statementId in ?1", statements.values.map { it.id }).groupBy { it.statementId }
        val measureRows = ModerationBlockEntity.list("caseId = ?1 order by takenAt", case.id)
        val holders = currentEpochHolders()
        val renewed = if (statements.isEmpty()) emptySet()
        else StatementDeferralEntity.list("statementId in ?1 and renewal = true", statements.values.map { it.id }).map { it.statementId }.toSet()
        ModerationCase(
            origin = ModerationCaseOrigin.valueOf(case.origin),
            orders = orders.ofCase(case.id),
            statementForm = StatementForm.valueOf(StatementMail.formNow(operatingMode).name),
            objections = ModerationObjectionEntity.list("caseId = ?1 order by receivedAt", case.id).mapNotNull { o ->
                val statement = statements.values.firstOrNull { it.id == o.statementId } ?: return@mapNotNull null
                val takenBy = measureRows.firstOrNull { it.id == statement.measureId }?.takenBy
                ModerationObjection(
                    id = UUID.fromString(o.id),
                    statementId = UUID.fromString(o.statementId),
                    measureId = UUID.fromString(statement.measureId),
                    receivedAt = o.receivedAt.utc(),
                    epoch = o.epoch,
                    sealedObjection = o.sealedObjection,
                    decidedAt = o.decidedAt?.utc(),
                    decidedBy = o.decidedBy?.let(UUID::fromString),
                    outcome = o.outcome?.let { ObjectionOutcome.valueOf(it) },
                    ownReview = o.ownReview,
                    mayDecide = o.open && !(takenBy == callerId && (holders - callerId).isNotEmpty()),
                )
            },
            id = UUID.fromString(case.id),
            itemKind = ModerationItemKind.valueOf(case.itemKind),
            itemId = case.itemId,
            scopeId = UUID.fromString(case.scopeId),
            tenantId = tenant?.id?.let(UUID::fromString),
            tenantDomain = tenant?.takeIf { it.status == TenantEntity.ACTIVE }?.domain,
            category = ModerationCase.Category.valueOf(case.category),
            state = ModerationCaseState.valueOf(case.state),
            decidedAt = case.decidedAt?.utc(),
            closedAt = case.closedAt?.utc(),
            hold = hold?.let {
                ModerationHold(endsAt = it.expiresAt.utc(), trigger = ModerationHold.Trigger.valueOf(it.trigger), floorUntil = it.floorUntil?.utc())
            },
            reports = reports.map { r ->
                ModerationReport(
                    id = UUID.fromString(r.id),
                    epoch = r.epoch,
                    receivedAt = r.receivedAt.utc(),
                    category = ModerationReport.Category.valueOf(r.category),
                    sealedReport = r.sealedReport,
                    sealedContact = r.sealedContact,
                    reporter =if (r.reporterAccount != null) ModerationReport.Reporter.ACCOUNT else ModerationReport.Reporter.LINK,
                    barred = blocks.reporterBarred(r.reporterAccount, r.reporterShare),
                )
            },
            measures = measureRows.mapNotNull { m ->
                val kind = m.blockKind.wire ?: return@mapNotNull null
                val st = statements[m.id]
                val told = st?.let { recipients[it.id] }.orEmpty()
                ModerationMeasure(
                    id = UUID.fromString(m.id),
                    kind = ModerationMeasureKind.valueOf(kind),
                    targetId = m.targetId,
                    takenBy = UUID.fromString(m.takenBy),
                    takenAt = m.takenAt.utc(),
                    liftedAt = m.liftedAt?.utc(),
                    liftedBy = m.liftedBy?.let(UUID::fromString),
                    statement = st?.let {
                        ModerationMeasureStatement(
                            id = UUID.fromString(it.id),
                            ground = ModerationGround.valueOf(it.ground),
                            basis = StatementBasis.valueOf(it.basis),
                            sentAt = it.sentAt?.utc(),
                            epoch = it.epoch,
                            sealedFacts = it.sealedFacts,
                            recipients = told.count { r -> r.recipientKind == RecipientKind.ACCOUNT.name },
                            guests = told.count { r -> r.recipientKind == RecipientKind.GUEST.name },
                            deferredUntil = it.deferredUntil?.takeIf { _ -> it.sentAt == null }?.utc(),
                            renewed = it.id in renewed,
                        )
                    },
                )
            },
            proposals = ModerationProposalEntity.list("caseId = ?1 order by proposedAt", case.id).map { p ->
                ModerationProposal(
                    id = UUID.fromString(p.id),
                    step = ModerationProposalStep.valueOf(p.step),
                    targetId = UUID.fromString(p.targetId),
                    proposedBy = UUID.fromString(p.proposedBy),
                    proposedAt = p.proposedAt.utc(),
                    outcome = ModerationProposal.Outcome.valueOf(p.outcome),
                    settledAt = p.settledAt?.utc(),
                )
            },
            targets = ModerationTargets(
                links = known.links.map {
                    ModerationTargetLink(
                        shareId = it.shareId,
                        role = ModerationTargetLink.Role.valueOf(it.role),
                        relation = ModerationTargetLink.Relation.valueOf(it.relation.name),
                        blocked = it.blocked,
                    )
                },
                uploaders = known.uploaders.map {
                    ModerationTargetUploader(
                        kind = if (it.isGuest) ModerationTargetUploader.Kind.GUEST_IDENTITY else ModerationTargetUploader.Kind.ACCOUNT,
                        id = UUID.fromString(it.id),
                        shareId = it.guestShareId,
                        blocked = it.blocked,
                    )
                },
            ),
            events = ModerationEventEntity.list("caseId = ?1 order by occurredAt", case.id).map { e ->
                ModerationCaseEvent(
                    event = ModerationCaseEvent.Event.valueOf(e.event),
                    at = e.occurredAt.utc(),
                    moderatorId = e.moderatorId?.let(UUID::fromString),
                    sealedText = e.sealedText,
                    until = e.holdUntil?.utc(),
                    detail = detailOf(e),
                    epoch = e.epoch,
                )
            },
        )
    }

    /** "KIND target" for a measure's or a proposal's event, in the wire's words. */
    private fun detailOf(e: ModerationEventEntity): String? {
        val reason = e.reason ?: return null
        val kind = runCatching { BlockKind.valueOf(reason).wire }.getOrNull() ?: reason
        return listOfNotNull(kind, e.targetId).joinToString(" ").takeIf { e.event != ModerationEvent.HOLD_ENDED.name }
    }

    private fun tenantOf(scopeId: String): TenantEntity? =
        ScopeEntity.findById(scopeId)?.tenantId?.let { TenantEntity.findById(it) }

    private fun Instant.utc(): OffsetDateTime = atOffset(ZoneOffset.UTC)
}
