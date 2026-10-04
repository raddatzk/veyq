package com.veyq.app.features.content

import com.veyq.app.core.blob.BlobEntity
import com.veyq.app.core.node.NodeEntity
import com.veyq.app.core.node.NodeSubtreeEraser
import com.veyq.app.features.sharing.SharingStore
import com.veyq.app.shared.blobstore.StoreObjectRef
import com.veyq.app.shared.blobstore.StoreReclaimStore
import com.veyq.app.shared.config.VeyqConfig
import com.veyq.app.shared.scope.ScopeEntity
import io.quarkus.arc.All
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import java.time.Duration
import java.time.Instant
import org.jboss.logging.Logger

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
 * - **Rows through `MediaStore.purgeMediaRows`** (the media eraser), the shared per-media body — so the FK order (derivative →
 *   media → blob) and the chunk-key enumeration stay in one place rather than becoming a fourth copy. Not
 *   `ScopePurge.eraseScope`'s set-based shape: that erases by *scope* and lets the scope-row delete cascade the
 *   rest, and its own docstring says a per-media copy is the wrong shape **there**. A node subtree is not a
 *   scope — neighbouring nodes, buckets and the scope row all survive it.
 * - **One transaction per scope covers its rows AND their reclaim entries** (ADR 0062). `QuarkusTransaction`, not
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
    private val sharing: SharingStore,
    private val reclaim: StoreReclaimStore,
    config: VeyqConfig,
    // MutableList, not List: ArC rejects kotlin List with @All (see ScopePurge).
    @All erasers: MutableList<NodeSubtreeEraser>,
) {
    // Sorted by the explicit phase, never by bean order; split once at core's own cover-blob erase (NodeSubtreeEraser).
    private val sorted = erasers.sortedBy { it.phase }
    private val beforeCovers = sorted.filter { it.phase < NodeSubtreeEraser.COVER_BLOBS_PHASE }
    private val afterCovers = sorted.filter { it.phase > NodeSubtreeEraser.COVER_BLOBS_PHASE }

    private val log = Logger.getLogger(NodeSubtreePurge::class.java)
    private val retention: Duration = config.node().trash().retention()

    /** One transaction per scope, a failure logged and stepped over — `ScopePurge.run`'s shape, for its reason: one
     *  scope that cannot be erased must not hold back every other scope's on every later tick. */
    @Scheduled(every = "{veyq.node.trash.purge.every}")
    fun run() {
        val cutoff = Instant.now().minus(retention)
        val scopes = QuarkusTransaction.requiringNew().call {
            NodeEntity.getEntityManager().createQuery(
                "select distinct n.scopeId from NodeEntity n where n.deletedAt is not null and n.deletedAt < :cutoff",
                String::class.java,
            ).setParameter("cutoff", cutoff).resultList
        }
        scopes.forEach { scopeId ->
            try {
                QuarkusTransaction.requiringNew().run { eraseExpiredIn(scopeId, cutoff) }
            } catch (e: Exception) {
                log.error("purge of the expired trash in scope $scopeId failed; it stays due and the sweep continues", e)
            }
        }
    }

    /**
     * Erase every node of [scopeId] whose marker is older than [cutoff], recording every store object it drops. The
     * whole subtree shares one `deletedAt` — `NodesStore.deleteSubtree` re-stamps even already-trashed descendants
     * precisely so it ages out together — so selecting on the marker alone reaches a complete subtree, never half of
     * one. Joins its caller's transaction ([run] opens one per scope).
     *
     * The scope row's lock first, and the selection under it: a restore takes the same lock
     * (`NodesStore.restoreSubtree`), so it either commits before this selects — and its subtree is no longer due — or
     * finds the rows gone. Selected before the lock, a subtree restored in between was erased anyway.
     */
    fun eraseExpiredIn(scopeId: String, cutoff: Instant) {
        ScopeEntity.lockRow(scopeId) ?: return // the scope's own purge erased its nodes with it
        val expired = NodeEntity.find("scopeId = ?1 and deletedAt is not null and deletedAt < ?2", scopeId, cutoff).list()
        if (expired.isEmpty()) return
        val nodeIds = expired.map { it.id }

        // Phases below COVER_BLOBS_PHASE: the media application's rows (its locks, its buckets, its blobs through the
        // FK-ordered body). Each returns the guest identities its rows named, read while they still did; the last
        // step drops the ones nothing references any more.
        val guests = HashSet<String>()
        beforeCovers.forEach { guests += it.erase(scopeId, nodeIds) }

        // The standalone COVER blobs. This reclaim used to sit in `NodesStore.deleteSubtree` (cr-0073) and
        //    moved here: under a soft-delete, reclaiming a cover when the user deletes hands a later restore
        //    back a Memory whose cover is already gone. `cover_blob_id` is the only reference to these rows.
        val coverBlobIds = expired.mapNotNull { it.coverBlobId }
        if (coverBlobIds.isNotEmpty()) {
            val covers = BlobEntity.find("id in ?1", coverBlobIds).list()
            reclaim.record(covers.map { StoreObjectRef(it.storageKey, it.chunkCount) })
            BlobEntity.delete("id in ?1", coverBlobIds)
        }

        // Phases above it: every member's item-favorite blob for these nodes.
        afterCovers.forEach { guests += it.erase(scopeId, nodeIds) }

        // Every link on these nodes is deleted — a tombstone (ADR 0029 §7), not a row delete: its id stays held,
        //    and the identities of guests with uploads elsewhere still hang off it.
        sharing.tombstoneAllOn(nodeIds)

        // The guests of ANY deleted link whose last media or bucket went above — a link deleted earlier loses its
        //    guests here, with the last Memory that held their uploads. After every eraser above and the tombstones,
        //    which it reads (GuestIdentityUsage).
        sharing.dropDeadIdentities(guests)

        expired.forEach { NodeEntity.deleteById(it.id) }
    }
}
