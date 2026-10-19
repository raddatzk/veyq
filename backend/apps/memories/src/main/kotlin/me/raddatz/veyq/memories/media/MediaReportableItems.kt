package me.raddatz.veyq.memories.media

import me.raddatz.veyq.core.items.NodeItemsRevisions
import me.raddatz.veyq.core.moderation.report.ReportableItem
import me.raddatz.veyq.core.moderation.report.ReportableItems
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.LockModeType
import jakarta.transaction.Transactional
import java.time.Instant

/**
 * A Memories medium as a reportable item (uc.moderation.report): its space and the Memory or chapter it sits on, for
 * core's reach check. Only a live medium — a trashed one is in no member's gallery and no guest's listing
 * ([MediaSharedSubtreeContributor] leaves it out the same way), so it is not "what the reporter sees". And as a blockable
 * one (uc.moderation.take-a-measure): its blobs, and the live-list nudge a block or a lift owes an open gallery.
 */
@ApplicationScoped
class MediaReportableItems(private val revisions: NodeItemsRevisions) : ReportableItems {
    override fun locate(itemId: String): ReportableItem? =
        MediaEntity.find("id = ?1 and deletedAt is null", itemId).firstResult()?.let { ReportableItem(it.scopeId, it.nodeId) }

    /** A medium's original and every derivative, trashed or not (a content block refuses them all). */
    override fun blobIdsOf(itemId: String): Set<String> {
        val medium = MediaEntity.findById(itemId) ?: return emptySet()
        return setOf(medium.blobId) + MediaDerivativeEntity.find("mediaId = ?1", medium.id).list().map { it.blobId }
    }

    /**
     * A block or a lift changed what the medium's row is served as (`blockedByOperator`): it moves to its list's end — the
     * revision its node's bump wrote is its new position — so the gallery's keyset delta carries it, and the conditional
     * poll answers with that delta (ADR 0033) — the order every gallery writer takes (item row, then the node's revision).
     */
    @Transactional
    override fun blockChanged(itemId: String) {
        val medium = MediaEntity.findById(itemId) ?: return
        medium.updatedAt = Instant.now()
        medium.position = revisions.bump(medium.scopeId, medium.nodeId) // its new place: the list's end (ADR 0033 decision 3)
    }

    /** The medium's row, locked as every purge of it locks it (`MediaStore.purgeMedia`, the retention purge, a guest's delete). */
    override fun lockExisting(itemId: String): Boolean =
        MediaEntity.find("id", itemId).withLock(LockModeType.PESSIMISTIC_WRITE).firstResult() != null
}
