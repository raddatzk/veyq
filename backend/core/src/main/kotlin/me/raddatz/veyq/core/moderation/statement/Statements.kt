package me.raddatz.veyq.core.moderation.statement

import me.raddatz.veyq.core.admin.operator.OperatingModeHandler
import me.raddatz.veyq.core.admin.operator.OperatorDetailsHandler
import me.raddatz.veyq.core.generated.model.ModerationGround
import me.raddatz.veyq.core.generated.model.ModerationStatement
import me.raddatz.veyq.core.generated.model.StatementBasis as WireBasis
import me.raddatz.veyq.core.generated.model.StatementForm as WireForm
import me.raddatz.veyq.core.generated.model.StatementObjection
import me.raddatz.veyq.core.http.FailureStatus
import me.raddatz.veyq.core.http.HttpFailure
import me.raddatz.veyq.core.http.httpFailure
import me.raddatz.veyq.core.moderation.ModerationEpochEntity
import me.raddatz.veyq.core.moderation.ModerationEvent
import me.raddatz.veyq.core.moderation.ModerationLog
import me.raddatz.veyq.core.moderation.block.ModerationBlockEntity
import me.raddatz.veyq.core.moderation.report.CaseState
import me.raddatz.veyq.core.moderation.report.ModerationCaseEntity
import me.raddatz.veyq.core.persistence.createOrConflict
import io.quarkus.narayana.jta.QuarkusTransaction
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.LockModeType
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** The affected member's refusals (codes per `moderation.openapi.yaml`, tag `statements`). */
object StatementFailures {
    val statementNotFound = httpFailure("statement_not_found", FailureStatus.NOT_FOUND)
    val objectionExists = httpFailure("objection_exists", FailureStatus.CONFLICT)
    val measureNotInForce = httpFailure("measure_not_in_force", FailureStatus.CONFLICT)
    val epochChanged = httpFailure("moderation_epoch_changed", FailureStatus.CONFLICT)
}

/** An objection's outcome: received, or the refusal it ended with (nothing written then). */
sealed interface ObjectionResult {
    data class Received(val statement: ModerationStatement) : ObjectionResult
    data class Refused(val failure: HttpFailure) : ObjectionResult, HttpFailure by failure
}

/**
 * The affected member's side of a measure (ADR 0093 § 7, uc.moderation.object-to-a-measure steps 5 and 8): the
 * statements that name them, and their objection. A member sees only a statement whose recipient row is their account;
 * everything else answers as an unknown statement. Nothing names the moderator.
 *
 * **An objection** is one per measure — the statement's unique row in `veyq_moderation_objection`, so two at once
 * collide in the database and the loser answers [StatementFailures.objectionExists] — sealed to the current epoch, and
 * only while the measure stands. It takes the case row's lock (as every decision does) and **reopens** the case: a
 * closed one is decided again, back in the queue, its events undated (ADR 0093 § 11: a case closes only with no objection
 * open); the moderators are mailed after the commit.
 */
@ApplicationScoped
class Statements(
    private val log: ModerationLog,
    private val operatingMode: OperatingModeHandler,
    private val operator: OperatorDetailsHandler,
    private val mail: StatementMail,
) {

    /** The statements naming [accountId], newest first. */
    fun mine(accountId: String): List<ModerationStatement> = QuarkusTransaction.requiringNew().call {
        val rows = StatementRecipientEntity.list("recipientKind = ?1 and recipientId = ?2", RecipientKind.ACCOUNT.name, accountId)
        if (rows.isEmpty()) return@call emptyList()
        val form = StatementMail.formNow(operatingMode)
        val contact = StatementMail.contactOf(operator)
        rows.mapNotNull { view(it, accountId, form, contact) }.sortedByDescending { it.sentAt }
    }

    fun object_(accountId: String, statementId: String, epoch: Int, sealed: String, now: Instant = Instant.now()): ObjectionResult {
        val outcome = createOrConflict(conflict = { ObjectionResult.Refused(StatementFailures.objectionExists) }) {
            // Locking reads first (the statement, then its case — a decision takes the case alone), so every plain read
            // below sees what was committed before the lock: at MariaDB's REPEATABLE READ the first plain read fixes the
            // snapshot (ADR 0078), and a lift that committed meanwhile must refuse the objection.
            val statement = ModerationStatementEntity.findById(statementId, LockModeType.PESSIMISTIC_WRITE)
                ?: return@createOrConflict refused(StatementFailures.statementNotFound)
            val case = ModerationCaseEntity.findById(statement.caseId, LockModeType.PESSIMISTIC_WRITE)
                ?: return@createOrConflict refused(StatementFailures.statementNotFound)
            val recipient = recipientRow(statementId, accountId) ?: return@createOrConflict refused(StatementFailures.statementNotFound)
            if (ModerationObjectionEntity.count("statementId", statementId) > 0) return@createOrConflict refused(StatementFailures.objectionExists)
            val measure = ModerationBlockEntity.findById(statement.measureId)
            if (measure?.activeKey == null) return@createOrConflict refused(StatementFailures.measureNotInForce)
            val current = ModerationEpochEntity.find("order by epoch desc").firstResult()?.epoch
            if (current != epoch) return@createOrConflict refused(StatementFailures.epochChanged)
            val objection = ModerationObjectionEntity().apply {
                id = UUID.randomUUID().toString()
                this.statementId = statementId
                caseId = case.id
                objectorAccount = accountId
                this.epoch = epoch
                sealedObjection = sealed
                receivedAt = now
            }
            objection.persist()
            if (case.state == CaseState.CLOSED.name) {
                case.state = CaseState.DECIDED.name
                case.closedAt = null
                ModerationCaseEntity.getEntityManager().flush()
                log.reopenCase(case.id)
            }
            log.appendCase(ModerationEvent.OBJECTION_RECEIVED, now, case.id, actorId = accountId, reason = measure.kind, targetId = objection.id)
            ModerationObjectionEntity.getEntityManager().flush()
            val view = view(recipient, accountId, StatementMail.formNow(operatingMode), StatementMail.contactOf(operator))
                ?: return@createOrConflict refused(StatementFailures.statementNotFound)
            ObjectionResult.Received(view)
        }
        if (outcome is ObjectionResult.Received) mail.objectionReceived()
        return outcome
    }

    private fun refused(failure: HttpFailure) = ObjectionResult.Refused(failure)

    private fun recipientRow(statementId: String, accountId: String): StatementRecipientEntity? =
        StatementRecipientEntity.find(
            "statementId = ?1 and recipientKind = ?2 and recipientId = ?3", statementId, RecipientKind.ACCOUNT.name, accountId,
        ).firstResult()

    private fun view(r: StatementRecipientEntity, accountId: String, form: StatementForm, contact: String?): ModerationStatement? {
        val s = ModerationStatementEntity.findById(r.statementId) ?: return null
        val sentAt = s.sentAt ?: return null
        val m = ModerationBlockEntity.findById(s.measureId) ?: return null
        val kind = m.blockKind.wire ?: return null
        val objection = ModerationObjectionEntity.find("statementId", s.id).firstResult()
        val inForce = m.activeKey != null
        return ModerationStatement(
            id = UUID.fromString(s.id),
            caseId = UUID.fromString(s.caseId),
            measureKind = ModerationStatement.MeasureKind.valueOf(kind),
            targetId = m.targetId,
            shareId = when (kind) {
                "SHARE" -> m.targetId
                "GUEST_IDENTITY" -> m.shareId
                else -> null
            },
            scopeId = m.scopeId?.let(UUID::fromString),
            ground = ModerationGround.valueOf(s.ground),
            basis = WireBasis.valueOf(s.basis),
            form = WireForm.valueOf(form.name),
            sentAt = sentAt.utc(),
            roles = r.roleSet.map { ModerationStatement.Roles.valueOf(it.name) },
            sealedFacts = r.sealedFacts,
            inForce = inForce,
            liftedAt = m.liftedAt?.utc(),
            contactAddress = contact,
            objection = objection?.let { o ->
                StatementObjection(
                    receivedAt = o.receivedAt.utc(),
                    mine = o.objectorAccount == accountId,
                    outcome = o.outcome?.let { me.raddatz.veyq.core.generated.model.ObjectionOutcome.valueOf(it) },
                    ownReview = o.ownReview,
                    decidedAt = o.decidedAt?.utc(),
                )
            },
            canObject = inForce && objection == null,
        )
    }

    private fun Instant.utc(): OffsetDateTime = atOffset(ZoneOffset.UTC)
}
