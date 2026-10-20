package me.raddatz.veyq.memories.media

import me.raddatz.veyq.core.identity.PrincipalKey
import me.raddatz.veyq.core.node.NodeSubtreeEraser
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.LockModeType

/**
 * The media and buckets of an erased node subtree. Phase 100 — before core's attachments (200), as
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
 *   is already out of every listing, so no open gallery can be watching it; the node's item-list counters are core's
 *   and go with it (`NodeItemsRevisionNodeSubtreeEraser`, phase 300).
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
            media.flatMap { listOfNotNull(it.shareIdentityId, it.metaEditorIdentityId) } +
                buckets.flatMap { listOfNotNull((PrincipalKey.parse(it.ownerKey) as? PrincipalKey.Share)?.identityId, it.nameEditorIdentityId) }
            ).toSet()
        media.forEach { mediaStore.purgeMediaRows(it) }
        BucketEntity.delete("nodeId in ?1", nodeIds)
        return guests
    }

    /** Its media rows locked first, as [erase] locks them (item rows before blob rows), and every blob they name. */
    override fun blobIdsOf(scopeId: String, nodeIds: List<String>): Collection<String> =
        mediaStore.blobIdsOf(MediaEntity.find("nodeId in ?1 order by id", nodeIds).withLock(LockModeType.PESSIMISTIC_WRITE).list())
}
