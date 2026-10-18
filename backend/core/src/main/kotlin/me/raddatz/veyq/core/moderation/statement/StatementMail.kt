package me.raddatz.veyq.core.moderation.statement

import me.raddatz.veyq.core.admin.instance.InstanceNoticeMailer
import me.raddatz.veyq.core.admin.operator.OperatingModeHandler
import me.raddatz.veyq.core.admin.operator.OperatorDetailsHandler
import me.raddatz.veyq.core.generated.model.DeliveryResult
import me.raddatz.veyq.core.identity.VeyqUser
import me.raddatz.veyq.core.moderation.ModerationEvent
import me.raddatz.veyq.core.moderation.ModerationEventEntity
import me.raddatz.veyq.core.moderation.ModerationLog
import me.raddatz.veyq.core.moderation.ModeratorNotices
import me.raddatz.veyq.core.moderation.block.BlockKind
import me.raddatz.veyq.core.moderation.block.ModerationBlockEntity
import me.raddatz.veyq.core.moderation.report.ReportRateLimiter
import me.raddatz.veyq.core.moderation.report.ReportReceived
import me.raddatz.veyq.core.moderation.report.ReportShareEntity
import me.raddatz.veyq.core.moderation.report.ModerationCaseEntity
import me.raddatz.veyq.core.scope.ScopeEntity
import me.raddatz.veyq.core.tenant.TenantEntity
import io.quarkus.narayana.jta.QuarkusTransaction
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** One reporter to tell a decision: a report of the case and the address its sealed part carried, opened by the moderator. */
data class ReporterNotice(val reportId: String, val address: String)

/**
 * The mails of the statement of reasons and the objection (ADR 0093 §§ 2, 7; ADR 0097 decision 3 as owner decision Q11
 * widened it; uc.moderation.object-to-a-measure), all through the instance mail and **after** the decision committed —
 * a mail that fails costs the decision nothing:
 *  - [statementSent]: each member a statement names, at their account address — the template's clear parts in the
 *    current form ([StatementTemplate.mailBody]), **never the facts** of a moderator's measure: their passage says they
 *    are in veyq (ADR 0049). A recipient whose mail did not go out is retried by the hourly cleanup ([retryDue]) for
 *    seven days. **A Server Owner's own step** (a sign-in block, a tenant taken offline) is mailed with its facts, the
 *    Server Owner's own words handed in with the step and held only in memory (owner decision Q24 of
 *    cr-statement-legal-gaps; ADR 0049 decision 2: a consented disclosure by their author) — once: the server keeps no
 *    clear copy to retry from, so [retryDue] leaves those statements alone.
 *  - [tellReporters]: a reporter who left a contact, the dismissal or the decision and their redress (DSA Art. 16(5),
 *    [StatementTemplate.renderRedress] in the current form) — once per report (a `DECISION_SENT` event is the record),
 *    from the address the deciding moderator's browser opened and sent, mailed from memory and stored nowhere, bounded
 *    per address as a receipt is ([ReportRateLimiter.allowReceipt]).
 *  - [objectionReceived]: every confirmed moderator and the contact's notification address, that an objection arrived.
 *  - [objectionAnswered]: the objector, that the answer is in veyq.
 * Nothing here logs an address (ADR 0092 § 1).
 */
@ApplicationScoped
class StatementMail(
    private val mailer: InstanceNoticeMailer,
    private val notices: ModeratorNotices,
    private val limiter: ReportRateLimiter,
    private val moderationLog: ModerationLog,
    private val operatingMode: OperatingModeHandler,
    private val operator: OperatorDetailsHandler,
) {
    private val log = Logger.getLogger(StatementMail::class.java)

    /** What a reporter is told. */
    enum class Decision { DISMISSED, DECIDED }

    private data class Outgoing(val recipientRowId: String, val address: String, val body: String)

    /**
     * Mail every member [statementId] names whose mail has not gone out. Answers how many went out now. [stepFacts] are a
     * Server Owner's step's facts, which only that step's own call hands in.
     */
    fun statementSent(statementId: String, stepFacts: String? = null): Int = runCatching {
        val outgoing = QuarkusTransaction.requiringNew().call { outgoingOf(statementId, stepFacts) }
        outgoing.count { o ->
            val sent = mailer.mailTo(o.address, StatementTemplate.MAIL_SUBJECT.en + " / " + StatementTemplate.MAIL_SUBJECT.de, o.body, "a statement of reasons") ==
                DeliveryResult.SENT
            if (sent) QuarkusTransaction.requiringNew().run { StatementRecipientEntity.findById(o.recipientRowId)?.mailedAt = Instant.now() }
            sent
        }
    }.getOrElse {
        log.warn("a statement of reasons could not be mailed; the hourly cleanup retries it")
        0
    }

    /** The cleanup's retry: every statement sent in the last seven days that still owes a member its mail. */
    fun retryDue(now: Instant = Instant.now()): Int {
        val due = QuarkusTransaction.requiringNew().call {
            // A Server Owner's step is not retried: its facts were held only for the one send (see the class comment).
            val recent = ModerationStatementEntity.list("sentAt > ?1", now.minus(RETRY_WINDOW))
                .filter { ModerationBlockEntity.findById(it.measureId)?.blockKind?.isServerOwnerStep != true }
                .map { it.id }
            if (recent.isEmpty()) emptyList()
            else StatementRecipientEntity.list(
                "statementId in ?1 and recipientKind = ?2 and mailedAt is null", recent, RecipientKind.ACCOUNT.name,
            ).map { it.statementId }.distinct()
        }
        return due.sumOf { statementSent(it) }
    }

    private fun outgoingOf(statementId: String, stepFacts: String?): List<Outgoing> {
        val statement = ModerationStatementEntity.findById(statementId) ?: return emptyList()
        val sentAt = statement.sentAt ?: return emptyList()
        val measure = ModerationBlockEntity.findById(statement.measureId) ?: return emptyList()
        val step = measure.blockKind.isServerOwnerStep
        val kind = measure.blockKind.wire ?: measure.kind.takeIf { step } ?: return emptyList()
        // The host a moderator's statement sends the reader to, and the tenant a suspension names; a sign-in block needs none.
        val domain = when (measure.blockKind) {
            BlockKind.MEMBER_SIGN_IN -> ""
            BlockKind.TENANT_OFFLINE, BlockKind.TENANT_DELETED -> TenantEntity.findById(measure.targetId)?.domain ?: return emptyList()
            else -> statement.caseId?.let { ModerationCaseEntity.findById(it) }
                ?.let { ScopeEntity.findById(it.scopeId) }?.tenantId?.let { TenantEntity.findById(it) }?.domain ?: return emptyList()
        }
        val form = formNow(operatingMode)
        val contact = contactOf(operator)
        return StatementRecipientEntity.list(
            "statementId = ?1 and recipientKind = ?2 and mailedAt is null", statementId, RecipientKind.ACCOUNT.name,
        ).mapNotNull { r ->
            val address = VeyqUser.findById(r.recipientId)?.email ?: return@mapNotNull null
            val fields = StatementFields(
                measureKind = kind,
                roles = r.roleSet,
                basis = StatementBasis.valueOf(statement.basis),
                ground = StatementGround.valueOf(statement.ground),
                date = TIME.format(sentAt),
                contact = contact,
                hasFacts = if (step) stepFacts != null else statement.sealedFacts != null,
                provision = statement.provision,
                channel = if (step) ObjectionChannel.BY_CONTACT else ObjectionChannel.IN_APP,
                mailFacts = stepFacts.takeIf { step },
            )
            Outgoing(r.id, address, StatementTemplate.mailBody(form, fields, domain))
        }
    }

    /** Tell each reporter of [notices] that is one of [caseId]'s and was not told yet; each mail that went out is logged. */
    fun tellReporters(caseId: String, notices: List<ReporterNotice>, decision: Decision, moderatorId: String) {
        notices.distinctBy { it.reportId }.forEach { notice ->
            runCatching {
                val reference = QuarkusTransaction.requiringNew().call {
                    val ofCase = ReportShareEntity.count("caseId = ?1 and reportId = ?2", caseId, notice.reportId) > 0
                    val told = ModerationEventEntity.count(
                        "caseId = ?1 and event = ?2 and targetId = ?3", caseId, ModerationEvent.DECISION_SENT.name, notice.reportId,
                    ) > 0
                    if (ofCase && !told) ReportReceived.reference(notice.reportId) else null
                } ?: return@runCatching
                if (!limiter.allowReceipt(notice.address)) return@runCatching
                val body = decisionBody(reference, decision, formNow(operatingMode), contactOf(operator))
                if (mailer.mailTo(notice.address, DECISION_SUBJECT, body, "a report's decision") == DeliveryResult.SENT) {
                    QuarkusTransaction.requiringNew().run {
                        moderationLog.appendCase(
                            ModerationEvent.DECISION_SENT, Instant.now(), caseId, moderatorId = moderatorId, reason = decision.name,
                            targetId = notice.reportId,
                        )
                    }
                }
            }.onFailure { log.warn("a report's decision could not be mailed") }
        }
    }

    /** Every confirmed moderator and the contact's notification address: an objection arrived. */
    fun objectionReceived() {
        runCatching { notices.mailModerationNotice(OBJECTION_SUBJECT, OBJECTION_BODY) }
            .onFailure { log.warn("the objection notice could not be mailed") }
    }

    /** The objector of [objectionId]: the answer is in veyq. */
    fun objectionAnswered(objectionId: String) {
        runCatching {
            val address = QuarkusTransaction.requiringNew().call {
                ModerationObjectionEntity.findById(objectionId)?.objectorAccount?.let { VeyqUser.findById(it)?.email }
            } ?: return@runCatching
            mailer.mailTo(address, ANSWER_SUBJECT, ANSWER_BODY, "an objection's answer")
        }.onFailure { log.warn("an objection's answer could not be mailed") }
    }

    companion object {
        private val RETRY_WINDOW: Duration = Duration.ofDays(7)
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC)

        /** The form a statement is rendered in now: the operating mode, read when it is rendered (ADR 0093 § 14). */
        fun formNow(mode: OperatingModeHandler): StatementForm =
            if (mode.read().mode == me.raddatz.veyq.core.admin.operator.OperatingMode.PRIVATE) StatementForm.REDUCED else StatementForm.FULL

        /** The address statements name: the statement address, else the contact's (ADR 0093 § 11); null where none is set. */
        fun contactOf(operator: OperatorDetailsHandler): String? = operator.publicValues().let { it.statementEmail ?: it.contactEmail }

        const val DECISION_SUBJECT = "veyq: the decision on your report / die Entscheidung über deine Meldung"
        const val OBJECTION_SUBJECT = "veyq: an objection to a moderation decision / ein Widerspruch gegen eine Moderationsentscheidung"
        const val ANSWER_SUBJECT = "veyq: your objection was decided / über deinen Widerspruch wurde entschieden"

        /**
         * A reporter's decision: their reference, the outcome and their redress in [form] (DSA Art. 16(5); the template's
         * `reporterDecision`) — nothing of the case (ADR 0049).
         */
        fun decisionBody(reference: String, decision: Decision, form: StatementForm, contact: String?): String {
            val en = if (decision == Decision.DISMISSED) {
                "The moderators of this veyq instance looked at your report and took no measure."
            } else {
                "The moderators of this veyq instance looked at your report and took a measure."
            }
            val de = if (decision == Decision.DISMISSED) {
                "Die Moderation dieser veyq-Instanz hat deine Meldung geprüft und keine Maßnahme ergriffen."
            } else {
                "Die Moderation dieser veyq-Instanz hat deine Meldung geprüft und eine Maßnahme ergriffen."
            }
            val redressEn = StatementTemplate.renderRedress(StatementLang.EN, form, contact)
            val redressDe = StatementTemplate.renderRedress(StatementLang.DE, form, contact)
            return """
                |$en
                |$redressEn
                |Reference: $reference
                |
                |—
                |
                |$de
                |$redressDe
                |Aktenzeichen: $reference
            """.trimMargin()
        }

        val OBJECTION_BODY = """
            |Someone objected to a moderation decision on this veyq instance. Open the moderation page on any space you can
            |sign in to, to see it; another moderator than the one who decided reviews it.
            |
            |—
            |
            |Gegen eine Moderationsentscheidung auf dieser veyq-Instanz wurde Widerspruch eingelegt. Öffne die Moderationsseite
            |in einem beliebigen Bereich, in dem du dich anmelden kannst, um ihn zu sehen; geprüft wird er von einer anderen
            |Person aus der Moderation als der, die entschieden hat.
        """.trimMargin()

        val ANSWER_BODY = """
            |Your objection to a moderation decision on this veyq instance was decided. Sign in to veyq and open the item it
            |concerns to read the answer.
            |
            |—
            |
            |Über deinen Widerspruch gegen eine Moderationsentscheidung auf dieser veyq-Instanz wurde entschieden. Melde dich
            |in veyq an und öffne das betroffene Element, um die Antwort zu lesen.
        """.trimMargin()
    }
}
