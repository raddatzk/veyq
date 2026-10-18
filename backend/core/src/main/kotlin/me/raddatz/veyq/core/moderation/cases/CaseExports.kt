package me.raddatz.veyq.core.moderation.cases

import me.raddatz.veyq.core.generated.model.ModerationCaseExport
import me.raddatz.veyq.core.generated.model.ModerationCaseOrigin
import me.raddatz.veyq.core.generated.model.ModerationExportGuestWrite
import me.raddatz.veyq.core.generated.model.ModerationExportMeasure
import me.raddatz.veyq.core.generated.model.ModerationExportUploader
import me.raddatz.veyq.core.generated.model.ModerationMeasureKind
import me.raddatz.veyq.core.moderation.block.ModerationBlockEntity
import me.raddatz.veyq.core.moderation.hold.CaseSnapshots
import me.raddatz.veyq.core.moderation.hold.HeldRow
import me.raddatz.veyq.core.moderation.report.ModerationCaseEntity
import me.raddatz.veyq.core.moderation.report.ModerationReportEntity
import me.raddatz.veyq.core.moderation.report.ReportShareEntity
import io.quarkus.narayana.jta.QuarkusTransaction
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * The clear fields an Art. 18 report asks for (ADR 0093 § 13, uc.moderation.report-to-an-authority): the case, its
 * category, every time in UTC, its measures, the uploaders the held rows name and every held guest-write record — what
 * the server already holds in the clear, read from the case's rows and its hold, **never content and nothing opened**
 * (operator-blind: whatever needs plaintext stays in the moderator's browser). A hold copied each record's columns as
 * the database returned them, its times as the instants they are (`HoldSnapshot`), which is how they are
 * read back here.
 */
@ApplicationScoped
class CaseExports(private val snapshots: CaseSnapshots, private val targets: CaseTargets) {

    /** [caseId]'s export, or null for an unknown case. */
    fun export(caseId: String, now: Instant = Instant.now()): ModerationCaseExport? = QuarkusTransaction.requiringNew().call {
        val case = ModerationCaseEntity.findById(caseId) ?: return@call null
        val held = snapshots.rowsOf(case.id, now)
        val known = targets.of(case, held)
        val reportIds = ReportShareEntity.list("caseId", case.id).map { it.reportId }
        val reported = if (reportIds.isEmpty()) emptyList()
        else ModerationReportEntity.list("id in ?1", reportIds).map { it.receivedAt }.sorted()
        ModerationCaseExport(
            caseId = UUID.fromString(case.id),
            category = ModerationCaseExport.Category.valueOf(case.category),
            origin = ModerationCaseOrigin.valueOf(case.origin),
            timeZone = ModerationCaseExport.TimeZone.UTC,
            reportedAt = reported.map { it.utc() },
            measures = ModerationBlockEntity.list("caseId = ?1 order by takenAt", case.id).mapNotNull { m ->
                if (m.blockKind.isBar) return@mapNotNull null
                val kind = m.blockKind.wire ?: return@mapNotNull null
                ModerationExportMeasure(ModerationMeasureKind.valueOf(kind), m.targetId, m.takenAt.utc(), m.liftedAt?.utc())
            },
            uploaders = known.uploaders.map {
                ModerationExportUploader(
                    kind = if (it.isGuest) ModerationExportUploader.Kind.GUEST else ModerationExportUploader.Kind.ACCOUNT,
                    id = UUID.fromString(it.id),
                    shareId = it.guestShareId,
                )
            },
            guestWrites = held.orEmpty().filter { it.table == GUEST_WRITE_TABLE }.mapNotNull(::guestWrite).sortedBy { it.observedAt },
            held = held != null,
        )
    }

    private fun guestWrite(row: HeldRow): ModerationExportGuestWrite? {
        val identity = row["share_identity_id"] ?: return null
        val objectId = row["object_id"] ?: return null
        val address = row["client_ip"] ?: return null
        val observed = row["observed_at"]?.let(::heldTime) ?: return null
        return ModerationExportGuestWrite(
            identityId = UUID.fromString(identity),
            objectKind = row["object_kind"] ?: "",
            objectId = UUID.fromString(objectId),
            address = address,
            port = row["client_port"]?.toIntOrNull(),
            observedAt = observed,
        )
    }

    private fun Instant.utc(): OffsetDateTime = atOffset(ZoneOffset.UTC)

    companion object {
        const val GUEST_WRITE_TABLE = "veyq_guest_write_record"

        /** A held row's time — the snapshot keeps it as an ISO-8601 instant (`HoldSnapshot`) — or null. */
        fun heldTime(text: String): OffsetDateTime? = runCatching { Instant.parse(text).atOffset(ZoneOffset.UTC) }.getOrNull()
    }
}
