package me.raddatz.veyq.core.moderation.report

import me.raddatz.veyq.core.admin.instance.InstanceNoticeMailer
import me.raddatz.veyq.core.generated.model.DeliveryResult
import me.raddatz.veyq.core.moderation.ModerationEvent
import me.raddatz.veyq.core.moderation.ModerationLog
import io.quarkus.narayana.jta.QuarkusTransaction
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * What follows a received report, after its commit and outside its transaction (uc.moderation.report): the urgent
 * new-case mail at once ([CaseNotices]; the others wait for the day's bundle), and the reporter's **receipt** to the
 * address they gave (owner decision Q6, ADR 0097 decision 3, DSA Art. 16(4)) — sent once from memory and stored nowhere
 * (the report keeps it only sealed), bounded per address ([ReportRateLimiter.allowReceipt]) so the route mails no
 * address on request. A mail that fails costs the reporter nothing: the report is stored, and the dialog shows the
 * reference and the time either way. A receipt that went out is a `RECEIPT_SENT` event on each case of the report —
 * the proof that stays once the sealed contact went (ADR 0093 § 11). Nothing here logs an address (ADR 0092 § 1).
 */
@ApplicationScoped
class ReportReceived(
    private val notices: CaseNotices,
    private val mailer: InstanceNoticeMailer,
    private val limiter: ReportRateLimiter,
    private val moderationLog: ModerationLog,
) {
    private val log = Logger.getLogger(ReportReceived::class.java)

    fun after(received: ReportOutcome.Received, receiptAddress: String?) {
        if (received.cases.any { it.category.urgent }) {
            runCatching { notices.mailUrgent() }.onFailure { log.warn("the urgent new-case mail failed; the hourly cleanup retries it") }
        }
        if (receiptAddress != null && limiter.allowReceipt(receiptAddress)) {
            runCatching {
                val sent = mailer.mailTo(receiptAddress, RECEIPT_SUBJECT, receiptBody(received.reportId, received.receivedAt), "a report's receipt")
                if (sent == DeliveryResult.SENT) {
                    QuarkusTransaction.requiringNew().run {
                        received.cases.forEach { moderationLog.appendCase(ModerationEvent.RECEIPT_SENT, Instant.now(), it.caseId, targetId = received.reportId) }
                    }
                }
            }.onFailure { log.warn("a report's receipt could not be mailed") }
        }
    }

    companion object {
        const val RECEIPT_SUBJECT = "veyq: your report was received / Deine Meldung ist eingegangen"
        private val TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC)

        /** The reference the dialog shows too: the report id's first eight characters. */
        fun reference(reportId: String) = reportId.take(8)

        fun receiptBody(reportId: String, receivedAt: Instant): String = """
            |Your report to the people who run this veyq instance was received on ${TIME.format(receivedAt)}.
            |Reference: ${reference(reportId)}
            |
            |Only its moderators can read it. They decide what happens with it, and tell you their decision at this address.
            |
            |—
            |
            |Deine Meldung an die Betreiber dieser veyq-Instanz ist am ${TIME.format(receivedAt)} eingegangen.
            |Aktenzeichen: ${reference(reportId)}
            |
            |Nur die Moderation kann sie lesen. Sie entscheidet, was damit geschieht, und teilt dir die Entscheidung an diese Adresse mit.
        """.trimMargin()
    }
}
