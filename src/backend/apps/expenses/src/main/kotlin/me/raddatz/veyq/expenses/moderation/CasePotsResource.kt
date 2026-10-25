package me.raddatz.veyq.expenses.moderation

import me.raddatz.veyq.core.http.httpError
import me.raddatz.veyq.core.moderation.cases.CaseFailures
import me.raddatz.veyq.core.moderation.hold.CaseSnapshots
import me.raddatz.veyq.core.moderation.hold.HeldRow
import me.raddatz.veyq.core.moderation.report.ModerationCaseEntity
import me.raddatz.veyq.core.ratelimit.RateLimitExempt
import me.raddatz.veyq.expenses.generated.api.CaseExpensesApi
import me.raddatz.veyq.expenses.generated.model.CasePotReceipt
import me.raddatz.veyq.expenses.generated.model.CasePotRevision
import me.raddatz.veyq.expenses.generated.model.CasePotRevisionListing
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.security.Authenticated
import jakarta.ws.rs.core.Response
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * A moderator's review of a case's Pot revisions and receipts (uc.moderation.review-a-case, CR decision 7): every
 * revision and every receipt (with its held blob row) the case's hold copied, read from the snapshot only — so it answers after the revisions, the Pot or the space were deleted
 * (a participant's erasure included: what a hold copied stays until the hold ends, ADR 0093 § 4). The guest's shape plus
 * the Pot, never a writer. Behind core's `ModerationProofGate`, found by this resource's path under `/api/moderation`.
 */
@Authenticated
@RateLimitExempt(
    reason = "Key-proved and MODERATOR-only: every call spends a single-use challenge of a bounded set of accounts the " +
        "Server Owner appointed, and serves only rows a hold already copied.",
)
class CasePotsResource(private val snapshots: CaseSnapshots) : CaseExpensesApi {

    override fun listCasePotRevisions(caseId: UUID, xVeyqModerationChallenge: String?, xVeyqModerationSignature: String?): Response {
        val held = QuarkusTransaction.requiringNew().call {
            if (ModerationCaseEntity.findById(caseId.toString()) == null) return@call null
            snapshots.rowsOf(caseId.toString(), Instant.now()) ?: emptyList()
        } ?: return httpError(CaseFailures.caseNotFound)
        if (held.isEmpty()) return httpError(CaseFailures.nothingHeldRead)
        val revisions = held.filter { it.table == HeldRevisions.TABLE }
            .sortedWith(compareBy({ it["node_id"] }, { it["entry_id"] }, { it["revision"]?.toLong() }))
            .map(::toWire)
        // A held receipt with its held blob row (core copies the row of every blob a contributor names, and pins it).
        val blobRows = held.filter { it.table == "veyq_blob" }.associateBy { it.sourceId }
        val receipts = held.filter { it.table == HeldRevisions.RECEIPTS }.sortedWith(compareBy({ it["node_id"] }, { it.sourceId }))
            .mapNotNull { r -> blobRows[r["blob_id"]]?.let { toWire(r, it) } }
        return Response.ok(CasePotRevisionListing(revisions = revisions, receipts = receipts)).build()
    }

    private fun toWire(receipt: HeldRow, blob: HeldRow) = CasePotReceipt(
        nodeId = UUID.fromString(receipt["node_id"]),
        receiptId = UUID.fromString(receipt.sourceId),
        entryId = UUID.fromString(receipt["entry_id"]),
        blobId = UUID.fromString(blob.sourceId),
        chunkCount = blob["chunk_count"]!!.toInt(),
        byteSize = blob["byte_size"]!!.toLong(),
        wrappedBlobKey = blob["wrapped_blob_key"]!!,
        wrappedBlobMeta = blob["wrapped_meta"],
        createdAt = Instant.parse(receipt["created_at"]!!).atOffset(ZoneOffset.UTC),
        droppedAt = receipt["dropped_at"]?.let { Instant.parse(it).atOffset(ZoneOffset.UTC) },
    )

    private fun toWire(row: HeldRow) = CasePotRevision(
        nodeId = UUID.fromString(row["node_id"]),
        entryId = UUID.fromString(row["entry_id"]),
        revision = row["revision"]!!.toLong(),
        tombstone = flag(row["tombstone"]),
        erasure = flag(row["erasure"]),
        sealedBody = row["sealed_body"]!!,
        wrappedRevisionKey = row["wrapped_revision_key"]!!,
        createdAt = Instant.parse(row["created_at"]!!).atOffset(ZoneOffset.UTC),
    )

    /** A boolean column as the snapshot keeps its text: `TRUE`/`FALSE` on H2, `1`/`0` on MariaDB's `TINYINT(1)`. */
    private fun flag(text: String?): Boolean = text.equals("true", ignoreCase = true) || text == "1"
}
