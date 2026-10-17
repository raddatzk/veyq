package me.raddatz.veyq.core.moderation.hold

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import io.quarkus.arc.All
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant

/** One row a hold copied, read back: its source table and id, and its columns as stored (lower-case names, as text). */
data class HeldRow(val table: String, val sourceId: String, val columns: Map<String, String?>) {
    operator fun get(column: String): String? = columns[column]
}

/**
 * Who uploaded what a hold copied, read from an owner's own held rows (ADR 0079: core names no application table): the
 * member accounts among the uploaders — what a moderator may propose to block the sign-in of (ADR 0093 § 6). A guest
 * uploader is core's own (`veyq_share_identity`, copied for every identity a contributor names), so no implementation
 * answers for guests. Core answers for its attachments; an application for its rows.
 */
interface HeldUploaders {
    fun membersAmong(rows: List<HeldRow>): Set<String>
}

/**
 * The snapshot a case's hold holds, as the review and the decision panel read it (uc.moderation.review-a-case): the rows
 * of the case's hold alive at the time — none once it ended, which is what "nothing held" means. Read only from
 * `veyq_hold_row`, never from the live tables, so it answers after the share, the node or the space were deleted.
 */
@ApplicationScoped
class CaseSnapshots(
    private val holds: Holds,
    // MutableList, not List: ArC rejects kotlin List with @All (see ScopePurge).
    @All uploaders: MutableList<HeldUploaders>,
) {
    private val uploaders: List<HeldUploaders> = uploaders

    /** The rows of [caseId]'s hold alive at [now], or null when it holds none. */
    fun rowsOf(caseId: String, now: Instant): List<HeldRow>? {
        val hold = holds.aliveOf(caseId, now) ?: return null
        return HoldRowEntity.list("holdId", hold.id).map { HeldRow(it.sourceTable, it.sourceId, json.readValue(it.payload, COLUMNS)) }
    }

    /** The member accounts among the uploaders of [rows]. */
    fun membersAmong(rows: List<HeldRow>): Set<String> = uploaders.flatMapTo(LinkedHashSet()) { it.membersAmong(rows) }

    /** The guest identities [rows] hold, each with the link it belongs to (core's own copied identity rows). */
    fun guestsAmong(rows: List<HeldRow>): Map<String, String> =
        rows.filter { it.table == "veyq_share_identity" }.associate { it.sourceId to (it["share_id"] ?: "") }

    private companion object {
        val json = ObjectMapper()
        val COLUMNS = object : TypeReference<Map<String, String?>>() {}
    }
}

/** Core's half of [HeldUploaders]: an attachment's member uploader (a Depot file's content, a cover). */
@ApplicationScoped
class AttachmentHeldUploaders : HeldUploaders {
    override fun membersAmong(rows: List<HeldRow>): Set<String> =
        rows.filter { it.table == "veyq_node_attachment" }.mapNotNullTo(LinkedHashSet()) { it["uploader_user_id"] }
}
