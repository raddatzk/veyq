package me.raddatz.veyq.core.moderation

import me.raddatz.veyq.core.generated.api.ModerationApi
import me.raddatz.veyq.core.generated.model.ModerationCaseState
import me.raddatz.veyq.core.generated.model.ModerationChallenge
import me.raddatz.veyq.core.generated.model.ModerationDecisionNote
import me.raddatz.veyq.core.generated.model.ModerationDismissal
import me.raddatz.veyq.core.generated.model.ModerationObjectionDecision
import me.raddatz.veyq.core.generated.model.ModerationStatementRecipients
import me.raddatz.veyq.core.generated.model.ReporterNotice as WireReporterNotice
import me.raddatz.veyq.core.generated.model.StatementRecipient
import me.raddatz.veyq.core.generated.model.ModerationHoldExtension
import me.raddatz.veyq.core.generated.model.ModerationMeasureRequest
import me.raddatz.veyq.core.generated.model.ModerationProposalRequest
import me.raddatz.veyq.core.generated.model.ModerationQueue
import me.raddatz.veyq.core.generated.model.SealedModerationNote
import me.raddatz.veyq.core.moderation.block.ProposalStep
import me.raddatz.veyq.core.generated.model.AuthorityOrderRequest
import me.raddatz.veyq.core.generated.model.AuthorityOrders
import me.raddatz.veyq.core.generated.model.ModerationHandover
import me.raddatz.veyq.core.generated.model.ModerationStatementDeferral
import me.raddatz.veyq.core.moderation.cases.CaseDecisions
import me.raddatz.veyq.core.moderation.cases.CaseExports
import me.raddatz.veyq.core.moderation.cases.DeferralInput
import me.raddatz.veyq.core.moderation.cases.HandoverKind
import me.raddatz.veyq.core.moderation.order.AuthorityOrderHandler
import me.raddatz.veyq.core.moderation.order.ordered
import me.raddatz.veyq.core.moderation.order.toOrderIn
import me.raddatz.veyq.core.moderation.cases.CaseFailures
import me.raddatz.veyq.core.moderation.cases.CaseViews
import me.raddatz.veyq.core.moderation.cases.DecisionOutcome
import me.raddatz.veyq.core.moderation.cases.MeasureStep
import me.raddatz.veyq.core.moderation.cases.SealedNote
import me.raddatz.veyq.core.moderation.cases.StatementInput
import me.raddatz.veyq.core.moderation.statement.RecipientKind
import me.raddatz.veyq.core.moderation.statement.ReporterNotice
import me.raddatz.veyq.core.moderation.statement.StatementGround
import me.raddatz.veyq.core.moderation.report.CaseState
import me.raddatz.veyq.core.generated.model.ModerationDeliveries
import me.raddatz.veyq.core.generated.model.ModerationDelivery
import me.raddatz.veyq.core.generated.model.ModerationEpochGrant
import me.raddatz.veyq.core.generated.model.ModerationEpochKey
import me.raddatz.veyq.core.generated.model.ModerationGrants
import me.raddatz.veyq.core.generated.model.ModerationPendingDelivery
import me.raddatz.veyq.core.generated.model.ModeratorAddressConfirmation
import me.raddatz.veyq.core.generated.model.ModeratorAppointment
import me.raddatz.veyq.core.http.HttpFailure
import me.raddatz.veyq.core.http.httpError
import me.raddatz.veyq.core.identity.VeyqUser
import me.raddatz.veyq.core.ratelimit.RateLimit
import me.raddatz.veyq.core.ratelimit.RateLimitExempt
import me.raddatz.veyq.core.ratelimit.RateLimitKey
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.security.Authenticated
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.security.PermitAll
import jakarta.ws.rs.core.Response
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * The moderator's operations on the public listener (ADR 0099 § 6, uc.moderation.serve-as-moderator). Every one passes
 * [ModerationProofGate] first, which requires the `MODERATOR` role and — for the operations that declare the proof
 * headers — the key proof; the resource sees only callers the gate let through, so the headers are not read here.
 * `@Authenticated` rather than `@RolesAllowed`: the role is the gate's (it answers `not_moderator`, where a role
 * annotation would answer `not_server_owner`).
 */
@Authenticated
@RateLimitExempt(
    reason = "Authenticated; each operation reads or writes the caller's own appointment, grants or a delivery the key " +
        "proof gates, one row per epoch. The operations with something to guess or to mail carry their own USER bound.",
)
class ModerationResource(
    private val moderation: ModerationHandler,
    private val address: ModeratorAddressHandler,
    private val rotationDue: RotationDueNotice,
    private val cases: CaseViews,
    private val decisions: CaseDecisions,
    private val orders: AuthorityOrderHandler,
    private val exports: CaseExports,
    private val identity: SecurityIdentity,
) : ModerationApi {

    @PermitAll
    @RateLimit(key = RateLimitKey.IP) // unauthenticated; the one read every reporter makes
    override fun getModerationKey(): Response {
        val current = moderation.currentKey() ?: return httpError(ModerationFailures.moderationOffKey)
        return Response.ok(ModerationEpochKey(current.epoch, current.kemPublicKey, current.signPublicKey)).build()
    }

    override fun getMyModeratorAppointment(): Response = Response.ok(appointment()).build()

    @RateLimit(key = RateLimitKey.USER) // each accept mails a code
    override fun acceptModeratorAppointment(): Response = when (val outcome = moderation.accept(caller())) {
        is ModerationOutcome.Ok -> {
            address.sendCode(caller())
            Response.ok(appointment()).build()
        }
        is HttpFailure -> httpError(outcome)
    }

    @RateLimit(key = RateLimitKey.USER) // each request mails a code
    override fun sendModeratorAddressCode(): Response {
        address.sendCode(caller())
        return Response.ok(appointment()).build()
    }

    @RateLimit(key = RateLimitKey.USER) // a code to guess, on top of the per-code attempt limit
    override fun confirmModeratorAddress(moderatorAddressConfirmation: ModeratorAddressConfirmation): Response =
        if (address.confirm(caller(), moderatorAddressConfirmation.code)) {
            Response.ok(appointment()).build()
        } else {
            httpError(ModeratorAddressHandler.codeInvalid)
        }

    override fun stepDownAsModerator(xVeyqModerationChallenge: String?, xVeyqModerationSignature: String?): Response =
        when (val outcome = moderation.stepDown(caller())) {
            is ModerationOutcome.Ok -> {
                rotationDue.mailIfDue()
                Response.noContent().build()
            }
            is HttpFailure -> httpError(outcome)
        }

    override fun listMyModerationGrants(): Response {
        val (current, epochs) = moderation.grantsOf(caller())
        return Response.ok(
            ModerationGrants(
                currentEpoch = current,
                epochs = epochs.map { ModerationEpochGrant(it.epoch, it.kemPublicKey, it.signPublicKey, it.sealedGrant) },
            ),
        ).build()
    }

    @RateLimit(key = RateLimitKey.USER) // every key-proved call spends one; bound how many rows a session can mint
    override fun createModerationChallenge(): Response =
        Response.ok(ModerationChallenge(moderation.challenge(caller()))).build()

    override fun listPendingModerationDeliveries(xVeyqModerationChallenge: String?, xVeyqModerationSignature: String?): Response =
        Response.ok(
            ModerationDeliveries(
                moderation.pendingDeliveries(caller()).map {
                    ModerationPendingDelivery(UUID.fromString(it.accountId), it.identityPublicKey, it.epochs)
                },
            ),
        ).build()

    override fun putModerationDelivery(
        accountId: UUID,
        moderationDelivery: ModerationDelivery,
        xVeyqModerationChallenge: String?,
        xVeyqModerationSignature: String?,
    ): Response {
        val grants = moderationDelivery.grants.map {
            GrantIn(accountId.toString(), moderationDelivery.identityPublicKey, it.epoch, it.sealedGrant)
        }
        return when (val outcome = moderation.deliver(caller(), accountId.toString(), moderationDelivery.identityPublicKey, grants)) {
            is ModerationOutcome.Ok -> Response.noContent().build()
            is HttpFailure -> httpError(outcome)
        }
    }

    override fun listModerationCases(
        state: ModerationCaseState?, xVeyqModerationChallenge: String?, xVeyqModerationSignature: String?,
    ): Response = Response.ok(ModerationQueue(cases.queue(state?.let { CaseState.valueOf(it.name) }))).build()

    override fun getModerationCase(caseId: UUID, xVeyqModerationChallenge: String?, xVeyqModerationSignature: String?): Response =
        caseResponse(caseId)

    override fun dismissModerationCase(
        caseId: UUID, moderationDismissal: ModerationDismissal, xVeyqModerationChallenge: String?, xVeyqModerationSignature: String?,
    ): Response = decided(
        caseId,
        decisions.dismiss(caller(), caseId.toString(), moderationDismissal.note.sealed(), moderationDismissal.reporterNotices.notices()),
    )

    override fun takeModerationMeasure(
        caseId: UUID, moderationMeasureRequest: ModerationMeasureRequest, xVeyqModerationChallenge: String?, xVeyqModerationSignature: String?,
    ): Response = with(moderationMeasureRequest) {
        val factsFor = statement.factsFor.orEmpty()
        if (factsFor.map { it.accountId }.toSet().size != factsFor.size) return httpError(CaseFailures.invalid)
        val input = StatementInput(
            ground = StatementGround.valueOf(statement.ground.name),
            facts = statement.facts.sealed(),
            factsFor = factsFor.associate { it.accountId.toString() to it.sealedFacts },
            deferral = statement.deferral?.let { DeferralInput(it.until.toInstant(), SealedNote(it.reason.epoch, it.reason.sealedNote)) },
            provision = statement.provision,
        )
        decided(
            caseId,
            decisions.take(
                caller(), caseId.toString(), MeasureStep.valueOf(kind.name), shareId, targetId?.toString(), note.sealed(), input,
                reporterNotices.notices(),
            ),
            Response.Status.CREATED,
        )
    }

    override fun getModerationStatementRecipients(
        caseId: UUID, kind: String, targetId: UUID?, shareId: String?, xVeyqModerationChallenge: String?, xVeyqModerationSignature: String?,
    ): Response {
        val step = MeasureStep.entries.firstOrNull { it.name == kind } ?: return httpError(CaseFailures.invalid)
        val (found, refusal) = decisions.recipientsOf(caseId.toString(), step, shareId, targetId?.toString())
        if (found == null) return httpError(refusal!!)
        return Response.ok(
            ModerationStatementRecipients(
                accounts = found.mapNotNull { (r, key) ->
                    if (r.kind != RecipientKind.ACCOUNT || key == null) null else StatementRecipient(UUID.fromString(r.id), key)
                },
                guests = found.count { (r, _) -> r.kind == RecipientKind.GUEST },
            ),
        ).build()
    }

    override fun decideModerationObjection(
        caseId: UUID, objectionId: UUID, moderationObjectionDecision: ModerationObjectionDecision, xVeyqModerationChallenge: String?,
        xVeyqModerationSignature: String?,
    ): Response = with(moderationObjectionDecision) {
        decided(
            caseId,
            decisions.decideObjection(
                caller(), caseId.toString(), objectionId.toString(), outcome == ModerationObjectionDecision.Outcome.LIFT, note.sealed(),
            ),
        )
    }

    override fun liftModerationMeasure(
        caseId: UUID, measureId: UUID, moderationDecisionNote: ModerationDecisionNote, xVeyqModerationChallenge: String?,
        xVeyqModerationSignature: String?,
    ): Response = decided(caseId, decisions.lift(caller(), caseId.toString(), measureId.toString(), moderationDecisionNote.note.sealed()))

    override fun proposeModerationStep(
        caseId: UUID, moderationProposalRequest: ModerationProposalRequest, xVeyqModerationChallenge: String?, xVeyqModerationSignature: String?,
    ): Response = with(moderationProposalRequest) {
        decided(
            caseId,
            decisions.propose(caller(), caseId.toString(), ProposalStep.valueOf(step.name), targetId?.toString(), note.sealed()),
            Response.Status.CREATED,
        )
    }

    override fun extendModerationHold(
        caseId: UUID, moderationHoldExtension: ModerationHoldExtension, xVeyqModerationChallenge: String?, xVeyqModerationSignature: String?,
    ): Response = with(moderationHoldExtension) {
        decided(caseId, decisions.extend(caller(), caseId.toString(), until.toInstant(), SealedNote(reason.epoch, reason.sealedNote)))
    }

    override fun barModerationReporter(
        caseId: UUID, reportId: UUID, moderationDecisionNote: ModerationDecisionNote, xVeyqModerationChallenge: String?,
        xVeyqModerationSignature: String?,
    ): Response = decided(
        caseId, decisions.bar(caller(), caseId.toString(), reportId.toString(), moderationDecisionNote.note.sealed()), Response.Status.CREATED,
    )

    @RateLimit(key = RateLimitKey.USER) // each order mails its statements and the new-case notice
    override fun enterModerationOrder(
        authorityOrderRequest: AuthorityOrderRequest, xVeyqModerationChallenge: String?, xVeyqModerationSignature: String?,
    ): Response = ordered(orders.enter(caller(), authorityOrderRequest.toOrderIn()))

    override fun listModerationOrders(xVeyqModerationChallenge: String?, xVeyqModerationSignature: String?): Response =
        Response.ok(AuthorityOrders(orders.list())).build()

    override fun deferModerationStatement(
        caseId: UUID, statementId: UUID, moderationStatementDeferral: ModerationStatementDeferral, xVeyqModerationChallenge: String?,
        xVeyqModerationSignature: String?,
    ): Response = with(moderationStatementDeferral) {
        decided(
            caseId,
            decisions.renewDeferral(caller(), caseId.toString(), statementId.toString(), until.toInstant(), SealedNote(reason.epoch, reason.sealedNote)),
        )
    }

    override fun recordModerationHandover(
        caseId: UUID, moderationHandover: ModerationHandover, xVeyqModerationChallenge: String?, xVeyqModerationSignature: String?,
    ): Response = with(moderationHandover) {
        decided(
            caseId,
            decisions.handover(caller(), caseId.toString(), HandoverKind.valueOf(kind.name), SealedNote(note.epoch, note.sealedNote)),
            Response.Status.CREATED,
        )
    }

    override fun getModerationCaseExport(caseId: UUID, xVeyqModerationChallenge: String?, xVeyqModerationSignature: String?): Response =
        exports.export(caseId.toString())?.let { Response.ok(it).build() } ?: httpError(CaseFailures.caseNotFound)

    private fun SealedModerationNote?.sealed(): SealedNote? = this?.let { SealedNote(it.epoch, it.sealedNote) }

    private fun List<WireReporterNotice>?.notices(): List<ReporterNotice> =
        orEmpty().map { ReporterNotice(it.reportId.toString(), it.address) }

    /** A decision's answer: the case as it now stands (with [status]), or its refusal. */
    private fun decided(caseId: UUID, outcome: DecisionOutcome, status: Response.Status = Response.Status.OK): Response = when (outcome) {
        DecisionOutcome.Done -> cases.case(caseId.toString(), caller())?.let { Response.status(status).entity(it).build() }
            ?: httpError(CaseFailures.caseNotFound)
        is DecisionOutcome.Refused -> httpError(outcome)
    }

    private fun caseResponse(caseId: UUID): Response =
        cases.case(caseId.toString(), caller())?.let { Response.ok(it).build() } ?: httpError(CaseFailures.caseNotFound)

    private fun caller(): String = identity.principal.name

    private fun appointment(): ModeratorAppointment {
        val id = caller()
        val now = Instant.now()
        val row = moderation.appointmentOf(id)
        val email = QuarkusTransaction.joiningExisting().call { VeyqUser.findById(id)?.email } ?: ""
        val state = when {
            row == null -> ModeratorAppointment.State.NONE
            row.acceptedAt == null -> ModeratorAppointment.State.PENDING
            else -> ModeratorAppointment.State.ACCEPTED
        }
        return ModeratorAppointment(
            state = state,
            appointedAt = row?.appointedAt?.atOffset(ZoneOffset.UTC),
            acceptedAt = row?.acceptedAt?.atOffset(ZoneOffset.UTC),
            address = email,
            confirmation = ModeratorAppointment.Confirmation.valueOf(ModeratorAddress.state(row, email, now).name),
            awaitingKey = state == ModeratorAppointment.State.ACCEPTED && moderation.awaitingKey(id),
        )
    }
}
