package com.veyq.app.features.content

import com.veyq.app.features.content.itemfavorites.ItemFavoritesEntity
import com.veyq.app.features.media.BlobEntity
import com.veyq.app.features.media.BucketEntity
import com.veyq.app.features.media.MediaEntity
import com.veyq.app.features.media.MediaStore
import com.veyq.app.features.media.OwnerKey
import com.veyq.app.features.sharing.SharingStore
import com.veyq.app.shared.blobstore.StoreObjectRef
import com.veyq.app.shared.blobstore.StoreReclaimStore
import com.veyq.app.shared.config.VeyqConfig
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import java.time.Duration
import java.time.Instant

/**
 * Scheduled hard-purge of soft-deleted node subtrees (uc.memory.delete-node, ADR 0061 Decision 6). A node the
 * user deleted enters the space's trash; once its `deletedAt` is older than the retention window this erases it
 * for good — the subtree's media, derivative, bucket and blob rows and the store objects behind them, then the
 * node rows. Irreversible, and **not** a member action: deleting is reversible so any member may do it, purging
 * belongs to the system (security-model § *Deletion & erasure*).
 *
 * This is where inbox #131 closes. The hard delete this replaced removed the node rows and the cover blobs and
 * left `veyq_media`, `veyq_media_derivative` and `veyq_bucket` pointing at a node id that no longer existed:
 * unreachable by every read (they all key on `scopeId + nodeId`) *and* unreclaimable by every purge (they carry
 * `deleted_at is null`, so the media trash purge never saw them, and their blobs stayed legitimately referenced).
 * The purge finally owns the whole subtree instead of just its covers.
 *
 * Two shape decisions, both deliberate:
 * - **Rows through [MediaStore.purgeMediaRows]**, the shared per-media body — so the FK order (derivative →
 *   media → blob) and the chunk-key enumeration stay in one place rather than becoming a fourth copy. Not
 *   `ScopePurge.eraseScope`'s set-based shape: that erases by *scope* and lets the scope-row delete cascade the
 *   rest, and its own docstring says a per-media copy is the wrong shape **there**. A node subtree is not a
 *   scope — neighbouring nodes, buckets and the scope row all survive it.
 * - **One transaction covers the rows AND the reclaim entries** (ADR 0062). `QuarkusTransaction`, not
 *   `@Transactional` on [run]: a self-invoked `@Transactional` method would not be intercepted at all. This job
 *   deletes no store objects itself — it records the key prefixes and `StoreReclaimSweep` drains them. The old
 *   shape reclaimed best-effort after the commit, which is unobservable by construction: the row that named the
 *   key was already gone when the delete failed.
 *
 * And it **ends every link on the erased Memories** (ADR 0029 §7). Trashing a Memory leaves its links resolving to
 * nothing and bringing it back on restore; once the Memory is erased there is nothing to bring back, and a link left
 * standing kept answering `200` with its sealed title, listed nowhere a member could revoke it. With the links go the
 * guest identities nothing can use or open any more (inbox #229).
 */
@ApplicationScoped
class NodeSubtreePurge(
    private val mediaStore: MediaStore,
    private val sharing: SharingStore,
    private val reclaim: StoreReclaimStore,
    config: VeyqConfig,
) {
    private val retention: Duration = config.node().trash().retention()

    @Scheduled(every = "{veyq.node.trash.purge.every}")
    fun run() {
        QuarkusTransaction.requiringNew().run { eraseExpired(Instant.now().minus(retention)) }
    }

    /**
     * Erase every node whose marker is older than [cutoff], recording every store object it drops. The whole
     * subtree shares one `deletedAt` — `NodesStore.deleteSubtree` re-stamps even already-trashed descendants
     * precisely so it ages out together — so selecting on the marker alone reaches a complete subtree, never
     * half of one.
     */
    private fun eraseExpired(cutoff: Instant) {
        val expired = NodeEntity.find("deletedAt is not null and deletedAt < ?1", cutoff).list()
        if (expired.isEmpty()) return
        val nodeIds = expired.map { it.id }

        // 0. the guest identities whose media or buckets this erases — read while the rows still name them; step 6
        //    drops the ones nothing references any more.
        val media = MediaEntity.find("nodeId in ?1", nodeIds).list()
        val buckets = BucketEntity.find("nodeId in ?1", nodeIds).list()
        val guests = media.mapNotNull { it.shareIdentityId } +
            buckets.mapNotNull { (OwnerKey.parse(it.ownerKey) as? OwnerKey.Share)?.shareIdentityId }

        // 1. media + derivatives + their blobs, one media at a time through the shared FK-ordered body.
        media.forEach { mediaStore.purgeMediaRows(it) }

        // 2. the subtree's buckets — rows only; every medium that lived in them went above. No revision bump:
        //    a trashed node is already out of every listing, so no open gallery can be watching it.
        BucketEntity.delete("nodeId in ?1", nodeIds)

        // 3. the standalone COVER blobs. This reclaim used to sit in `NodesStore.deleteSubtree` (cr-0073) and
        //    moved here: under a soft-delete, reclaiming a cover when the user deletes hands a later restore
        //    back a Memory whose cover is already gone. `cover_blob_id` is the only reference to these rows.
        val coverBlobIds = expired.mapNotNull { it.coverBlobId }
        if (coverBlobIds.isNotEmpty()) {
            val covers = BlobEntity.find("id in ?1", coverBlobIds).list()
            reclaim.record(covers.map { StoreObjectRef(it.storageKey, it.chunkCount) })
            BlobEntity.delete("id in ?1", coverBlobIds)
        }

        // 4. every member's item-favorite blob for these nodes. Keyed on the node, not the scope: the scope
        //    survives this purge. They are dropped only HERE and not at soft-delete, for the same reason as
        //    the covers above — a restore inside the window must hand back the Memory with its marks intact.
        ItemFavoritesEntity.delete("nodeId in ?1", nodeIds)

        // 5. every link on these nodes is deleted — a tombstone (ADR 0029 §7), not a row delete: its id stays held,
        //    and the identities of guests with uploads elsewhere still hang off it.
        sharing.tombstoneAllOn(nodeIds)

        // 6. the guests of ANY deleted link whose last media or bucket went above — a link deleted earlier loses its
        //    guests here, with the last Memory that held their uploads. After steps 1, 2 and 5, which it reads.
        sharing.dropDeadIdentities(guests)

        expired.forEach { NodeEntity.deleteById(it.id) }
    }
}
