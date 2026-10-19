package me.raddatz.veyq.core.content

import me.raddatz.veyq.core.backup.ErasureKind
import me.raddatz.veyq.core.backup.ErasureLedger
import me.raddatz.veyq.core.moderation.block.BlockKind
import me.raddatz.veyq.core.moderation.block.MeasureTargets
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.node.NodeSubtreeEraser
import me.raddatz.veyq.core.sharing.SharingStore
import me.raddatz.veyq.core.config.VeyqConfig
import me.raddatz.veyq.core.scope.ScopeEntity
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
 * for good — the applications' rows under the subtree (through each [NodeSubtreeEraser]), its blob rows and the
 * store objects behind them, then the node rows. Irreversible, and **not** a member action: deleting is reversible so any member may do it, purging
 * belongs to the system (security-model § *Deletion & erasure*).
 *
 * This is where inbox #131 closes. The hard delete this replaced removed the node rows and the cover blobs and
 * left `veyq_media`, `veyq_media_derivative` and `veyq_bucket` pointing at a node id that no longer existed:
 * unreachable by every read (they all key on `scopeId + nodeId`) *and* unreclaimable by every purge (they carry
 * `deleted_at is null`, so the media trash purge never saw them, and their blobs stayed legitimately referenced).
 * The purge finally owns the whole subtree instead of just its covers.
 *
 * Two shape decisions, both deliberate:
 * - **Each application erases its own rows per item** (its [NodeSubtreeEraser]; Memories' routes through its
 *   FK-ordered per-media body, so that order and the chunk-key enumeration stay in one place). Not
 *   `ScopePurge.eraseScope`'s set-based shape: that erases by *scope* and lets the scope-row delete cascade the
 *   rest. A node subtree is not a scope — neighbouring nodes, an application's other rows and the scope row all
 *   survive it.
 * - **One transaction per scope covers its rows AND their reclaim entries** (ADR 0062). `QuarkusTransaction`, not
 *   `@Transactional` on [run]: a self-invoked `@Transactional` method would not be intercepted at all. This job
 *   deletes no store objects itself — it records the key prefixes and `StoreReclaimSweep` drains them. The old
 *   shape reclaimed best-effort after the commit, which is unobservable by construction: the row that named the
 *   key was already gone when the delete failed.
 *
 * And it **ends every link on the erased nodes** (ADR 0029 §7). Trashing a node leaves its links resolving to
 * nothing and bringing it back on restore; once the node is erased there is nothing to bring back, and a link left
 * standing kept answering `200` with its sealed title, listed nowhere a member could revoke it. With the links go the
 * guest identities nothing can use or open any more (inbox #229). And every moderation measure on an erased node, link
 * or identity ends in the same transaction ([MeasureTargets.erased]).
 */
@ApplicationScoped
class NodeSubtreePurge(
    private val sharing: SharingStore,
    private val nodes: NodesStore,
    private val ledger: ErasureLedger,
    private val measures: MeasureTargets,
    config: VeyqConfig,
    // MutableList, not List: ArC rejects kotlin List with @All (see ScopePurge).
    @All erasers: MutableList<NodeSubtreeEraser>,
) {
    // Sorted by the explicit phase, never by bean order (NodeSubtreeEraser).
    private val sorted = erasers.sortedBy { it.phase }

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
        erase(scopeId, expired)
    }

    /**
     * The erasure ledger's re-application (ADR 0056 decision 8): erase the nodes [nodeIds] of [scopeId] that a restore
     * brought back, each with every descendant the restored tree hangs under it — a node is erased with its subtree, or
     * not at all. Takes the scope row's lock, as [eraseExpiredIn] does; joins the caller's transaction. Answers how many
     * node rows went (0 when none of them exists).
     */
    fun eraseNodes(scopeId: String, nodeIds: List<String>): Int {
        ScopeEntity.lockRow(scopeId) ?: return 0
        val all = NodeEntity.find("scopeId", scopeId).list()
        val byParent = all.groupBy { it.parentId }
        val doomed = LinkedHashSet<String>()
        val stack = ArrayDeque(nodeIds.filter { id -> all.any { it.id == id } })
        while (stack.isNotEmpty()) {
            val cur = stack.removeLast()
            if (doomed.add(cur)) byParent[cur]?.forEach { stack.addLast(it.id) }
        }
        val erased = all.filter { it.id in doomed }
        erase(scopeId, erased)
        return erased.size
    }

    /**
     * Erase [erased] — node rows of [scopeId] that are complete subtrees (every descendant among them) — for good,
     * recording every store object it drops: the expired trash ([eraseExpiredIn]) and a guest's empty upload
     * placeholder (`ShareNodesHandler.abandon`, which checked it has no child). Joins its caller's transaction, which
     * holds the scope row's lock.
     */
    fun erase(scopeId: String, erased: List<NodeEntity>) {
        if (erased.isEmpty()) return
        val nodeIds = erased.map { it.id }

        // Every eraser in phase order: the application rows hanging off the nodes (their own locks, their blobs through
        //    their FK-ordered bodies) and core's attachments — a Memory's cover among them since inbox #341, so its
        //    blob is still reclaimed here, at the purge, never at the trash (a restore gets its cover back). Each
        //    returns the guest identities its rows named, read while they still did; the last step drops the ones
        //    nothing references any more.
        val guests = HashSet<String>()
        sorted.forEach { guests += it.erase(scopeId, nodeIds) }

        // Every link on these nodes is deleted — a tombstone (ADR 0029 §7), not a row delete: its id stays held,
        //    and the identities of guests with uploads elsewhere still hang off it.
        sharing.tombstoneAllOn(nodeIds)

        // The node rows, before the identities: a guest's identity folder names its identity
        //    (`veyq_node.share_identity_id`, ADR 0080 decision 11), and a guest that last wrote a node's name or meta is
        //    named as its editor (ADR 0093 § 8) — `NodeGuestIdentityUsage` keeps an identity while a row names it either
        //    way, so the rows go first and every identity they named joins the ones the step below may drop.
        guests += erased.flatMap { listOfNotNull(it.shareIdentityId, it.nameEditorIdentityId, it.metaEditorIdentityId) }
        // The erasure ledger's lines (ADR 0056 decision 8): a guest's abandoned placeholder has no trash to re-fire, and
        // a capture older than the trash brings the node back live.
        erased.forEach { ledger.erased(ErasureKind.NODE, it.id, scopeId) }
        erased.forEach { NodeEntity.deleteById(it.id) }
        // A content block on an erased node ends with it (ADR 0093 § 6, GDPR Art. 5(1)(e)): it would otherwise stand on
        //    nothing forever and hold its case's records with it. After the deletes, which it flushes (MeasureTargets).
        measures.erased(listOf(BlockKind.CONTENT_NODE), nodeIds)

        // The guests of ANY deleted link whose last attachment, identity folder, field or application row naming them went above — a link
        //    deleted earlier loses its guests here, with the last node that held their uploads. After every eraser
        //    above, the tombstones and the node rows, which it reads (GuestIdentityUsage).
        sharing.dropDeadIdentities(guests)

        // The tree revision (ADR 0081), under the lock taken first. A trashed node is on no level view already, so this
        //    changes nothing a listing shows — but what the purge erased (attachments, rows) is a change of the scope's
        //    tree like any other, and a client that reads the revision should never see one move silently.
        nodes.bumpTree(scopeId)
    }
}
