package me.raddatz.veyq.core.moderation.report

import me.raddatz.veyq.core.generated.api.ReportsApi
import me.raddatz.veyq.core.generated.model.ReportReceipt
import me.raddatz.veyq.core.generated.model.ReportSubmission
import me.raddatz.veyq.core.http.httpError
import me.raddatz.veyq.core.ratelimit.RateLimit
import me.raddatz.veyq.core.ratelimit.RateLimitKey
import io.quarkus.security.Authenticated
import io.quarkus.security.identity.SecurityIdentity
import jakarta.ws.rs.core.Response
import java.time.ZoneOffset
import java.util.UUID

/**
 * A member's report (uc.moderation.report): thin over [ReportHandler], then [ReportReceived] after the commit. A guest's
 * report is `SharingResource.reportInShare`, the same handler behind the link's own gate.
 */
@Authenticated
class ReportsResource(
    private val reports: ReportHandler,
    private val received: ReportReceived,
    private val identity: SecurityIdentity,
) : ReportsApi {

    @RateLimit(key = RateLimitKey.USER) // the open-report cap (owner decision Q8) is the handler's, a database count
    override fun reportInScope(scopeId: UUID, reportSubmission: ReportSubmission): Response =
        reportSubmission.toReport().let { report ->
            answer(reports.report(Reporter.Member(identity.principal.name, scopeId.toString()), report), received, report.receiptAddress)
        }

    companion object {
        /** The wire's report as the handler takes it. */
        fun ReportSubmission.toReport() = ReportIn(
            category = ReportCategory.valueOf(category.name),
            epoch = epoch,
            sealedReport = sealedReport,
            sealedContact = sealedContact?.takeIf { it.isNotBlank() },
            items =items.map { ReportItemIn(ReportItemKind.valueOf(it.kind.name), it.id?.toString(), it.sealedShare) },
            receiptAddress = receiptAddress?.takeIf { it.isNotBlank() },
        )

        /** `201` with the reference and the time once the report is stored and [received] ran; else the refusal. */
        fun answer(outcome: ReportOutcome, received: ReportReceived, receiptAddress: String?): Response = when (outcome) {
            is ReportOutcome.Received -> {
                received.after(outcome, receiptAddress)
                Response.status(Response.Status.CREATED)
                    .entity(ReportReceipt(UUID.fromString(outcome.reportId), outcome.receivedAt.atOffset(ZoneOffset.UTC)))
                    .build()
            }
            is ReportOutcome.Refused -> httpError(outcome)
        }
    }
}
