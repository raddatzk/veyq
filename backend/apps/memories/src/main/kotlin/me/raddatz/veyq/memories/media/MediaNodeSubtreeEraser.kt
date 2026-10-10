package me.raddatz.veyq.memories.media

import me.raddatz.veyq.core.node.NodeSubtreeEraser
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.LockModeType

/**
 * The media, buckets and gallery counters of an erased node subtree. Phase 100 — before core's attachments (200), as
 * the purge always ran it — and, through the caller, before `dropDeadIdentities`, which re-counts the media and
 * buckets this deletes.
 *
 * - The guest identities are collected FIRST, while the rows still name them; the caller drops the ones nothing
 *   references any more.
 * - The selection is the media rows' lock (`PESSIMISTIC_WRITE`, `order by id`), as every other erase of a media row
 *   takes it (`purgeMedia`, the retention purge, a guest's delete): one that holds a row finishes first and the row
 *   is no longer selected. Selected plainly, this then deleted a row already gone and rolled the whole erase back.
 * - Media, derivatives and their blobs go one media at a time through the shared FK-ordered
 *   [MediaStore.purgeMediaRows] (derivative -> media -> blob; chunk-key reclaim in the same transaction).
 * - The buckets go after, rows only: every medium that lived in them went above. No revision bump — a trashed node
 *   is already out of every listing, so no open gallery can be watching it — and the counters' own rows
 *   ([MediaRevisionEntity]) go last, before `NodeSubtreePurge` deletes the nodes, which `fk_media_revision_node`
 *   refuses while one remains.
 */
@ApplicationScoped
class MediaNodeSubtreeEraser(
    private val mediaStore: MediaStore,
) : NodeSubtreeEraser {
    override val phase = 100

    override fun erase(scopeId: String, nodeIds: List<String>): Set<String> {
        val media = MediaEntity.find("nodeId in ?1 order by id", nodeIds).withLock(LockModeType.PESSIMISTIC_WRITE).list()
        val buckets = BucketEntity.find("nodeId in ?1", nodeIds).list()
        val guests = (
            media.mapNotNull { it.shareIdentityId } +
                buckets.mapNotNull { (OwnerKey.parse(it.ownerKey) as? OwnerKey.Share)?.shareIdentityId }
            ).toSet()
        media.forEach { mediaStore.purgeMediaRows(it) }
        BucketEntity.delete("nodeId in ?1", nodeIds)
        MediaRevisionEntity.delete("nodeId in ?1", nodeIds) // before the node rows: fk_media_revision_node
        return guests
    }
}
