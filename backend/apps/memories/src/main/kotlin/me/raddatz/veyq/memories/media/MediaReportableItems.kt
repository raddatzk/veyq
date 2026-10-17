package me.raddatz.veyq.memories.media

import me.raddatz.veyq.core.moderation.report.ReportableItem
import me.raddatz.veyq.core.moderation.report.ReportableItems
import jakarta.enterprise.context.ApplicationScoped

/**
 * A Memories medium as a reportable item (uc.moderation.report): its space and the Memory or chapter it sits on, for
 * core's reach check. Only a live medium — a trashed one is in no member's gallery and no guest's listing
 * ([MediaSharedSubtreeContributor] leaves it out the same way), so it is not "what the reporter sees".
 */
@ApplicationScoped
class MediaReportableItems : ReportableItems {
    override fun locate(itemId: String): ReportableItem? =
        MediaEntity.find("id = ?1 and deletedAt is null", itemId).firstResult()?.let { ReportableItem(it.scopeId, it.nodeId) }
}
