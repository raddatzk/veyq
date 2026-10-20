package me.raddatz.veyq.core.moderation.cases

import me.raddatz.veyq.core.moderation.block.ModerationBlocks
import me.raddatz.veyq.core.moderation.block.UploadedItems
import me.raddatz.veyq.core.moderation.block.Uploader
import me.raddatz.veyq.core.moderation.block.UploaderContent
import me.raddatz.veyq.core.moderation.hold.CaseSnapshots
import me.raddatz.veyq.core.moderation.hold.HeldRow
import me.raddatz.veyq.core.moderation.report.ModerationCaseEntity
import me.raddatz.veyq.core.moderation.report.ModerationReportEntity
import me.raddatz.veyq.core.moderation.report.ReportItemKind
import me.raddatz.veyq.core.moderation.report.ReportShareEntity
import me.raddatz.veyq.core.moderation.report.ReportableItems
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.sharing.ShareEntity
import io.quarkus.arc.All
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant

/** How a link stands to a case's item. */
enum class LinkRelation { REPORTED, REPORTED_THROUGH, REACHES }

data class TargetLink(val shareId: String, val role: String, val relation: LinkRelation, val blocked: Boolean)

/**
 * An uploader the held rows name: a member's account ([guestShareId] null) or a guest identity of a link — with how many
 * items they uploaded to the case's space ([items], what an `UPLOADER_CONTENT` step would block now) and whether one is in
 * force on them ([contentBlocked]).
 */
data class TargetUploader(
    val id: String,
    val guestShareId: String?,
    val blocked: Boolean,
    val items: UploadedItems = UploadedItems.NONE,
    val contentBlocked: Boolean = false,
) {
    val isGuest: Boolean get() = guestShareId != null
    val uploader: Uploader get() = Uploader(id, isGuest)
}

data class Targets(val links: List<TargetLink>, val uploaders: List<TargetUploader>)

/**
 * What a decision on a case may name (uc.moderation.take-a-measure step 1) — the server's answer, so a measure can only
 * name what the case knows: the reported link, the links its reports came through, the live links rooted at the item's
 * node or above it, and the uploaders its held rows name. Clear metadata only.
 */
@ApplicationScoped
class CaseTargets(
    private val snapshots: CaseSnapshots,
    private val blocks: ModerationBlocks,
    // MutableList, not List: ArC rejects kotlin List with @All (see ScopePurge).
    @All items: MutableList<ReportableItems>,
    @All uploaded: MutableList<UploaderContent>,
) {
    private val items: List<ReportableItems> = items
    private val uploaded: List<UploaderContent> = uploaded

    /** Everything [uploader] uploaded to [scopeId], every application asked (`UPLOADER_CONTENT`'s reach). */
    fun uploadsOf(scopeId: String, uploader: Uploader): UploadedItems =
        uploaded.fold(UploadedItems.NONE) { all, app -> all + app.of(scopeId, uploader) }

    // No default argument reading a field: a client proxy runs a Kotlin `$default` bridge with its own (empty) fields.
    fun of(case: ModerationCaseEntity, now: Instant): Targets = of(case, snapshots.rowsOf(case.id, now))

    fun of(case: ModerationCaseEntity, held: List<HeldRow>?): Targets {
        val links = LinkedHashMap<String, LinkRelation>()
        if (case.itemKind == ReportItemKind.LINK.name) links[case.itemId] = LinkRelation.REPORTED
        val reportIds = ReportShareEntity.list("caseId", case.id).map { it.reportId }
        if (reportIds.isNotEmpty()) {
            ModerationReportEntity.list("id in ?1", reportIds).mapNotNull { it.reporterShare }
                .forEach { links.putIfAbsent(it, LinkRelation.REPORTED_THROUGH) }
        }
        nodeOf(case, held)?.let { node ->
            val chain = ancestry(node)
            ShareEntity.list("scopeId = ?1 and nodeId in ?2 and deleted = false", case.scopeId, chain)
                .forEach { links.putIfAbsent(it.id, LinkRelation.REACHES) }
        }
        val shares = if (links.isEmpty()) emptyMap() else ShareEntity.list("id in ?1", links.keys.toList()).associateBy { it.id }
        val heldShares = held.orEmpty().filter { it.table == "veyq_share" }.associateBy { it.sourceId }
        val linkTargets = links.map { (id, relation) ->
            val role = shares[id]?.role ?: heldShares[id]?.get("role") ?: "READ"
            TargetLink(id, role, relation, blocks.shareBlocked(id))
        }
        val rows = held.orEmpty()
        // A reported name's uploader is who last wrote it (ADR 0093 § 8): a guest's identity is copied with the row, a
        // member's account is the row's own column.
        val nameEditor = if (case.itemKind == ReportItemKind.NAME.name) {
            rows.firstOrNull { it.table == "veyq_node" && it.sourceId == case.itemId }?.get("name_editor_user_id")
        } else {
            null
        }
        val members = (snapshots.membersAmong(rows) + listOfNotNull(nameEditor)).distinct().map { TargetUploader(it, null, false) }
        val guests = snapshots.guestsAmong(rows).map { (identity, share) -> TargetUploader(identity, share, blocks.identityBlocked(identity)) }
        val counted = (members + guests).map { u ->
            u.copy(items = uploadsOf(case.scopeId, u.uploader), contentBlocked = blocks.uploaderContentBlocked(case.scopeId, u.id))
        }
        return Targets(linkTargets, counted)
    }

    /** The node the reported item sits on: a node itself; an item's node, live or held; none for a link. */
    private fun nodeOf(case: ModerationCaseEntity, held: List<HeldRow>?): String? = when (case.itemKind) {
        ReportItemKind.NODE.name, ReportItemKind.NAME.name -> case.itemId
        ReportItemKind.ITEM.name -> items.firstNotNullOfOrNull { it.locate(case.itemId) }?.nodeId
            ?: held.orEmpty().firstOrNull { it.sourceId == case.itemId }?.get("node_id")
        else -> null
    }

    /** [nodeId] and every node above it, as stored (trashed ones included). */
    private fun ancestry(nodeId: String): List<String> {
        val out = mutableListOf<String>()
        var current: String? = nodeId
        while (current != null && out.size < MAX_DEPTH && current !in out) {
            out += current
            current = NodeEntity.findById(current)?.parentId
        }
        return out
    }

    private companion object {
        const val MAX_DEPTH = 1000
    }
}
