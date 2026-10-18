package me.raddatz.veyq.core.moderation.statement

import me.raddatz.veyq.core.admin.operator.OperatingMode
import me.raddatz.veyq.core.admin.operator.OperatingModeHandler
import me.raddatz.veyq.core.http.FailureStatus
import me.raddatz.veyq.core.http.HttpFailure
import me.raddatz.veyq.core.http.httpFailure
import me.raddatz.veyq.core.identity.VeyqUser
import me.raddatz.veyq.core.membership.MembershipEntity
import me.raddatz.veyq.core.moderation.ModerationEpochEntity
import me.raddatz.veyq.core.moderation.ModerationEvent
import me.raddatz.veyq.core.moderation.ModerationFailures
import me.raddatz.veyq.core.moderation.ModerationLog
import me.raddatz.veyq.core.moderation.block.BlockKind
import me.raddatz.veyq.core.moderation.block.ModerationBlockEntity
import me.raddatz.veyq.core.moderation.cases.CaseFailures
import me.raddatz.veyq.core.moderation.cases.SealedNote
import me.raddatz.veyq.core.moderation.report.CaseOrigin
import me.raddatz.veyq.core.moderation.report.ModerationCaseEntity
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.time.Instant
import java.util.UUID

/**
 * A Server Owner's step's statement of reasons as entered with the step (ADR 0093 § 7): the client-minted
 * [statementId] the sealed copy is bound to, the [ground], the [provision], the [facts] in the clear for the mail, and
 * [factsForLog] — the same facts sealed to the moderation key (`moderation-step-facts` [epoch, statementId]).
 */
data class StepStatementInput(
    val statementId: String,
    val ground: StatementGround,
    val provision: String?,
    val facts: String?,
    val factsForLog: SealedNote?,
)

/** A Server Owner's step's statement refusals (codes per `instance.openapi.yaml` / `tenants.openapi.yaml`). */
object StepStatementFailures {
    val required = httpFailure("statement_required", FailureStatus.BAD_REQUEST)
    val invalid = httpFailure("invalid_request", FailureStatus.BAD_REQUEST)
    val idTaken = httpFailure("id_taken", FailureStatus.CONFLICT)
}

/**
 * The statement a Server Owner's own step carries when it is taken over content (ADR 0093 § 7; DSA Art. 17(1)(c), (d);
 * uc.moderation.object-to-a-measure A7): a member's sign-in block ([BlockKind.MEMBER_SIGN_IN]) or a tenant taken offline
 * ([BlockKind.TENANT_OFFLINE]). **Over content** is a step from a case or a moderator's proposal, always, and a step
 * without either when the Server Owner says so; a suspension for any other reason carries none.
 *
 * Written **in the step's transaction**, as a moderator's measure writes its own: the statement row on the step's measure
 * row (its case, where it has one), a recipient row per account told — the blocked member, or every owner of the tenant
 * (owner decision Q25) — and the `STATEMENT_SENT` event. The facts are stored **only sealed** ([StepStatementInput.factsForLog]);
 * the clear copy goes to [StatementMail.statementSent] after the commit, by the step's own caller, and nowhere else.
 */
@ApplicationScoped
class StepStatements(private val operatingMode: OperatingModeHandler, private val log: ModerationLog) {

    /**
     * Whether the step may go ahead with [input], [overContent] being whether it is over content: null, or the refusal.
     * In the caller's transaction, before any write.
     */
    fun refusal(overContent: Boolean, input: StepStatementInput?): HttpFailure? {
        if (input == null) return if (overContent) StepStatementFailures.required else null
        val facts = input.facts?.takeIf { it.isNotBlank() }
        if ((facts == null) != (input.factsForLog == null)) return StepStatementFailures.invalid
        // The form follows the operating mode, read now (ADR 0093 § 14), as a moderator's measure's does.
        val full = operatingMode.read().mode == OperatingMode.ORGANISATION
        if (full && facts == null) return CaseFailures.factsRequired
        if (full && input.provision.isNullOrBlank()) return CaseFailures.provisionRequired
        input.factsForLog?.let { sealed ->
            val current = ModerationEpochEntity.find("order by epoch desc").firstResult()?.epoch ?: return ModerationFailures.moderationOff
            if (sealed.epoch != current) return CaseFailures.epochChanged
        }
        if (ModerationStatementEntity.findById(input.statementId) != null) return StepStatementFailures.idTaken
        return null
    }

    /** Who [measure] tells: the blocked account, or every owner of the tenant taken offline — accounts that still exist. */
    fun recipientsOf(measure: ModerationBlockEntity): List<Recipient> = when (measure.blockKind) {
        BlockKind.MEMBER_SIGN_IN -> listOf(Recipient(RecipientKind.ACCOUNT, measure.targetId, setOf(StatementRole.ACCOUNT_HOLDER)))
        BlockKind.TENANT_OFFLINE, BlockKind.TENANT_DELETED -> MembershipEntity.list("scopeId = ?1 and role = ?2", measure.targetId, MembershipEntity.OWNER)
            .map { Recipient(RecipientKind.ACCOUNT, it.userId, setOf(StatementRole.TENANT_OWNER)) }
        else -> error("not a Server Owner's step: ${measure.kind}")
    }.let { found ->
        val living = if (found.isEmpty()) emptySet() else VeyqUser.list("id in ?1", found.map { it.id }).map { it.id }.toSet()
        found.filter { it.id in living }.distinctBy { it.id }
    }

    /**
     * Write [input]'s statement on [measure], **in the caller's transaction** (the step's), with [by] the Server Owner: the
     * row, its recipients, and `STATEMENT_SENT` where anybody is told. The basis is the case's origin, or their own finding.
     */
    @Transactional(Transactional.TxType.MANDATORY)
    fun write(measure: ModerationBlockEntity, input: StepStatementInput, by: String, now: Instant) {
        val told = recipientsOf(measure)
        val case = measure.caseId?.let { ModerationCaseEntity.findById(it) }
        val sentAt = if (told.isNotEmpty()) now else null
        val written = ModerationStatementEntity().apply {
            id = input.statementId
            measureId = measure.id
            caseId = case?.id
            ground = input.ground.name
            provision = input.provision?.trim()?.takeIf { it.isNotEmpty() }
            basis = when (case?.origin) {
                null -> StatementBasis.OWN_FINDING.name
                CaseOrigin.ORDER.name -> StatementBasis.ORDER.name
                else -> StatementBasis.REPORT.name
            }
            epoch = input.factsForLog?.epoch
            sealedFacts = input.factsForLog?.sealed
            this.sentAt = sentAt
        }
        written.persist()
        told.forEach { r ->
            StatementRecipientEntity().apply {
                id = UUID.randomUUID().toString()
                statementId = written.id
                recipientKind = r.kind.name
                recipientId = r.id
                roles = r.roles.joinToString(",") { it.name }
            }.persist()
        }
        if (sentAt == null) return
        if (case != null) {
            log.appendCase(ModerationEvent.STATEMENT_SENT, now, case.id, moderatorId = by, reason = written.ground, targetId = written.id)
        } else {
            log.appendCaselessStep(ModerationEvent.STATEMENT_SENT, now, by, written.id, written.ground)
        }
    }
}
