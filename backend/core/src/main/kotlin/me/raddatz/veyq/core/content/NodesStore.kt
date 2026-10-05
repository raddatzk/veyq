package me.raddatz.veyq.core.content

import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.node.ParentSlot
import me.raddatz.veyq.core.scope.ScopeEntity
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.time.Instant
import java.util.UUID

/** A node row (parentId is the parent slot — node id, or the scope id at the root). */
data class NodeRow(
    val id: String,
    val parentId: String,
    val kind: String,
    val encryptedName: String,
    val slugToken: String,
    val orderIndex: Int,
    val epoch: Int,
    val wrappedContentKey: String?,
    val wrappedMeta: String?,
    /** A MEMORY's drop private key sealed under its `K_mem` (ADR 0031 point 7), or null until its first WRITE_ONLY share. */
    val sealedDropPrivateKey: String? = null,
    /** How many times [wrappedMeta] was replaced — the version a meta write states (ADR 0077). */
    val metaVersion: Long = 0,
)

/** One soft-deleted subtree ROOT in the space's trash (uc.memory.delete-node). `deletedAt` is the marker the
 *  resource turns into the derived `purgeAfter`; `parentId` is the slot a restore recomputes its slug against. */
data class TrashedNodeRow(
    val id: String,
    val parentId: String,
    val kind: String,
    val encryptedName: String,
    val epoch: Int,
    val deletedAt: Instant,
)

/**
 * Persistence for the Memory tree (cr-0027). Operator-blind: stores ciphertext + the blinded slug token only.
 * Who may act on a scope is decided before this store is reached, by the handler's `ScopeAccess` gate.
 */
@ApplicationScoped
class NodesStore {
    // cr-0073 reclaimed a node's standalone cover blob HERE, at delete time. ADR 0061 moved that into
    // `NodeSubtreePurge`: under a soft-delete, reclaiming the cover when the user deletes would hand a restore
    // back a Memory whose cover is already gone. So this store no longer touches the blob store at all — the
    // one-way dependency (media → content) is restored, and the purge worker owns both directions.

    /**
     * The scope row's lock — the serializer of the scope's node tree (developer-styleguide §12). Every write that
     * decides by a parent or a subtree takes it first, in the transaction that writes: a create and an update in
     * [NodesHandler], [deleteSubtree], [restoreSubtree] and `NodeSubtreePurge`. A trash enumerates the subtree it
     * stamps, so a create or a move that read its parent live before the trash committed would otherwise land under a
     * trashed parent after it; a purge enumerates what is due, so a restore committed in between would be erased.
     */
    fun lockScope(scopeId: String) {
        ScopeEntity.lockRow(scopeId)
    }

    /** Resolve a parent slot within the scope: the scope id (root), a node in the same scope, or missing. */
    fun parentSlot(scopeId: String, parentSlot: String): ParentSlot {
        if (parentSlot == scopeId) return ParentSlot.Root
        val n = NodeEntity.findById(parentSlot)?.takeIf { it.deletedAt == null } ?: return ParentSlot.Missing
        return if (n.scopeId == scopeId) ParentSlot.Node(n.kind) else ParentSlot.Missing
    }

    /**
     * Slug uniqueness — one of the **three reads that deliberately keep seeing soft-deleted rows** (ADR 0061).
     * `ux_node_parent_slug` is a plain UNIQUE(parent_id, slug_token) spanning trashed rows (MariaDB has no
     * filtered index), so adding `deletedAt is null` here would let validation pass and the database reject
     * the insert on flush afterwards — a clean 409 turned into a 500. The trashed row holds its own id as its
     * token, which is outside the contract's base64 token alphabet, so nothing a client can send collides with
     * it through the API; a handler-level caller can, and `NodesSlugTrapTest` pins exactly that.
     */
    fun slugTaken(parentSlot: String, slugToken: String, exceptNodeId: String? = null): Boolean =
        NodeEntity.count("parentId = ?1 and slugToken = ?2 and id <> ?3", parentSlot, slugToken, exceptNodeId ?: "") > 0

    fun findInScope(scopeId: String, nodeId: String): NodeEntity? =
        NodeEntity.findById(nodeId)?.takeIf { it.scopeId == scopeId && it.deletedAt == null }

    /**
     * The delete path's own scope binding — deliberately **without** `deletedAt is null`, so re-deleting an
     * already-trashed subtree is the no-op the use-case promises (E3) instead of a 404, while a foreign or
     * unknown node still answers the non-enumerable 404 `crossScopeDeleteIsRejected` pins. It is the same trap
     * family as [deleteSubtree]'s traversal below: both belong to the delete, and both must reach a trashed row.
     */
    fun findDeletableInScope(scopeId: String, nodeId: String): NodeEntity? =
        NodeEntity.findById(nodeId)?.takeIf { it.scopeId == scopeId }

    /** Would moving `nodeId` under `newParentSlot` create a cycle (the slot is the node or a descendant)?
     *  No `deletedAt` predicate: it **inherits** one. The handler resolves `newParentSlot` through [parentSlot]
     *  first, which filters — and a delete marks a whole subtree under the scope lock a create and a move also take,
     *  so no live node ever hangs under a trashed ancestor for this walk to climb through. */
    fun wouldCycle(scopeId: String, nodeId: String, newParentSlot: String): Boolean {
        var cur = newParentSlot
        while (cur != scopeId) {
            if (cur == nodeId) return true
            val p = NodeEntity.findById(cur) ?: return false
            if (p.scopeId != scopeId) return false
            cur = p.parentId
        }
        return false
    }

    /** The scope's current content-key epoch (ADR 0024), or null if there is no such scope. */
    fun currentEpoch(scopeId: String): Int? = ScopeEntity.findById(scopeId)?.currentEpoch

    /** Does any node, live or trashed, in any scope, already hold the client-minted [id]? */
    fun idTaken(id: String): Boolean = NodeEntity.findById(id) != null

    /** Persist a node under the client-minted [id]; false — nothing written — if any node, live or trashed, in
     *  any scope already holds it (ADR 0072: never overwrite, never substitute). */
    @Transactional
    fun create(
        id: String, scopeId: String, parentSlot: String, kind: String, encryptedName: String,
        slugToken: String, orderIndex: Int, epoch: Int, wrappedContentKey: String?, wrappedMeta: String?,
    ): Boolean {
        if (idTaken(id)) return false
        NodeEntity().apply {
            this.id = id
            this.scopeId = scopeId
            this.parentId = parentSlot
            this.kind = kind
            this.encryptedName = encryptedName
            this.slugToken = slugToken
            this.orderIndex = orderIndex
            this.epoch = epoch
            this.wrappedContentKey = wrappedContentKey
            this.wrappedMeta = wrappedMeta
            createdAt = Instant.now()
        }.persist()
        return true
    }

    fun list(scopeId: String): List<NodeRow> =
        NodeEntity.find("scopeId = ?1 and deletedAt is null", scopeId).list().map {
            NodeRow(
                it.id, it.parentId, it.kind, it.encryptedName, it.slugToken, it.orderIndex, it.epoch, it.wrappedContentKey,
                it.wrappedMeta, it.sealedDropPrivateKey, it.metaVersion,
            )
        }

    /** What [applyUpdate] did: applied, found no live node, refused a move its parent no longer allows, or refused a
     *  stale meta write (with the current value). */
    sealed interface ApplyResult {
        object Applied : ApplyResult
        object Missing : ApplyResult
        object IllegalMove : ApplyResult
        data class StaleMeta(val wrappedMeta: String?, val metaVersion: Long) : ApplyResult
    }

    /**
     * Apply a partial update. A present [wrappedMeta] is written only if [metaVersion] is still the stored one
     * (ADR 0077); on a miss **nothing** is written — a rename in the same request included — and the current blob
     * and version come back for the `409`.
     *
     * The compare-and-set runs FIRST, as a bulk UPDATE, before this transaction loads the entity. Loaded first, the
     * managed copy would hold the pre-update `wrappedMeta`/`metaVersion`; today `@DynamicUpdate` keeps a rename's
     * flush off those columns, but this order does not lean on that — loaded after it, the entity reads the row as
     * it now is, so no flush can write a stale meta back over the compare-and-set's result.
     *
     * Under the tree's lock ([lockScope]), taken before anything else: a move's parent and its cycle check are decided
     * again under it (the handler's are the fast path), and every write finds the node as a trash that held the lock
     * left it — a move or a slug written onto a subtree being trashed would land on a node the trash just stamped.
     * Neither check loads the moved node itself, so the order above holds.
     */
    @Transactional
    fun applyUpdate(
        scopeId: String, nodeId: String, encryptedName: String?, slugToken: String?, newParentSlot: String?, orderIndex: Int?,
        wrappedMeta: String?, metaVersion: Long?,
    ): ApplyResult {
        lockScope(scopeId)
        if (newParentSlot != null &&
            (parentSlot(scopeId, newParentSlot) is ParentSlot.Missing || wouldCycle(scopeId, nodeId, newParentSlot))
        ) {
            return ApplyResult.IllegalMove
        }
        if (wrappedMeta != null) {
            val expected = checkNotNull(metaVersion) { "the handler refuses wrappedMeta without its metaVersion" }
            val rows = NodeEntity.update(
                "wrappedMeta = ?1, metaVersion = ?2 where id = ?3 and metaVersion = ?4 and deletedAt is null",
                wrappedMeta, expected + 1, nodeId, expected,
            )
            if (rows == 0) {
                val current = NodeEntity.findById(nodeId)?.takeIf { it.deletedAt == null } ?: return ApplyResult.Missing
                return ApplyResult.StaleMeta(current.wrappedMeta, current.metaVersion)
            }
        }
        val node = NodeEntity.findById(nodeId)?.takeIf { it.deletedAt == null } ?: return ApplyResult.Missing
        encryptedName?.let { node.encryptedName = it }
        slugToken?.let { node.slugToken = it }
        newParentSlot?.let { node.parentId = it }
        orderIndex?.let { node.orderIndex = it }
        return ApplyResult.Applied
    }

    /**
     * **Soft-delete** the node + its whole subtree within the scope (uc.memory.delete-node, ADR 0061): stamp
     * `deletedAt` on every doomed node in one transaction. **Nothing is destroyed** — the subtree's media,
     * derivative, bucket and blob rows and every store object survive untouched, which is what makes a restore
     * complete rather than partial.
     *
     * Two halves of uc.memory.delete-node E3, and they pull in opposite directions. **Idempotent:** a delete of
     * an already-trashed root returns without touching anything. **But when the root is live, every doomed node
     * is re-stamped, including descendants an earlier, narrower delete had already trashed** — the whole subtree
     * has to age out on ONE timestamp, or the purge reaches the inner rows first and erases the Memory inside a
     * folder the user can still see in the trash and still believes is restorable. That is what "stops early and
     * strands the rest" costs, and `NodeTrashSliceTest` pins it against the purge rather than against the marker.
     */
    @Transactional
    fun deleteSubtree(scopeId: String, nodeId: String) {
        lockScope(scopeId) // before the enumeration below: a create or move under the subtree lands before it or not at all
        // No `deletedAt is null` here — the SECOND of the three reads that deliberately keep seeing trashed rows
        // (ADR 0061). The traversal must reach descendants whatever their own state, or re-deleting a partially
        // deleted subtree stops early and strands the rest (uc.memory.delete-node E3).
        val all = NodeEntity.find("scopeId", scopeId).list()
        val root = all.firstOrNull { it.id == nodeId } ?: return
        if (root.deletedAt != null) return // E3: deleting an already-trashed subtree changes nothing
        val doomed = collectSubtree(all, nodeId)
        val now = Instant.now()
        for (n in all) {
            if (n.id !in doomed) continue
            n.deletedAt = now
            // ADR 0061 Decision 5: free the name immediately, by the WRITE. `ux_node_parent_slug` is a plain
            // UNIQUE(parent_id, slug_token) spanning trashed rows (MariaDB has no filtered index), so a
            // trashed node would otherwise hold its name hostage for the whole retention window. The node's own
            // id is unique by construction, blind (the server may not compute a slug token — it is a MAC under
            // K_SC), and outside the contract's base64 token alphabet, so nothing a client can send collides
            // with it. The client re-supplies a fresh token on restore.
            n.slugToken = n.id
        }
        // cr-0107 (ADR 0033): still no media_revision/delete_revision bump here, but the reason changed with
        // ADR 0061 — the node rows and their counters now SURVIVE the delete, so "no open gallery can poll a
        // deleted node" is no longer true of the rows. It is true of the path: every media read routes through
        // the node gate (`MediaStore.findNode`), which filters trashed nodes, so a gallery polling a trashed
        // node gets a 404 rather than a stale set. The conclusion the note supports is unchanged: the delete
        // leaves each node's own media rows untouched, so no *surviving* node's gallery set changes. The
        // observable delete-reconcile is still the share delete-own path (cr-0102), which bumps both counters.
    }

    /**
     * Restore the trashed subtree rooted at [nodeId]: clear `deletedAt` across it and write the client's freshly
     * computed [slugToken] back onto the root. Only descendants stamped in the **same** delete come back — a node
     * the user had trashed separately beforehand carries an earlier marker and stays in the trash (where it
     * becomes a root of its own, its parent being live again). False when there is no such trashed root in the
     * scope (unknown, already purged, or still live) ⇒ a non-enumerable 404.
     */
    /** The 3-way result of a restore: applied, no such trashed root, or the name was taken meanwhile. */
    enum class RestoreResult { DONE, NOT_FOUND, SLUG_TAKEN }

    @Transactional
    fun restoreSubtree(scopeId: String, nodeId: String, slugToken: String, encryptedName: String?): RestoreResult {
        lockScope(scopeId) // the purge's lock: it erases what it selected under it, or finds this restore committed
        val all = NodeEntity.find("scopeId", scopeId).list()
        val root = all.firstOrNull { it.id == nodeId && it.deletedAt != null } ?: return RestoreResult.NOT_FOUND
        // Only a trash ROOT restores. A trashed node under a trashed parent comes back **with** that parent;
        // clearing it on its own would leave a live node hanging under a trashed one — visible in no listing at
        // all, because the client assembles the tree by parentId and the parent is filtered out.
        if (all.any { it.id == root.parentId && it.deletedAt != null }) return RestoreResult.NOT_FOUND
        // Decided HERE, inside the transaction that writes the token: a live sibling may have taken the name
        // while this subtree sat in the trash, and the constraint spans trashed rows either way. Checking it at
        // the handler would leave the window between the two reads open, and letting the database decide turns
        // an ordinary disambiguation (A1) into a 500.
        if (slugTaken(root.parentId, slugToken, exceptNodeId = nodeId)) return RestoreResult.SLUG_TAKEN
        val subtree = collectSubtree(all, nodeId)
        for (n in all) {
            if (n.id in subtree) n.deletedAt = null
        }
        root.slugToken = slugToken
        // The A1 retry re-seals the name in the SAME write as the token it is derived from.
        encryptedName?.let { root.encryptedName = it }
        return RestoreResult.DONE
    }

    /** The scope's trash: the ROOT of each soft-deleted subtree, newest-deleted first. A trashed node whose own
     *  parent is trashed went down with that parent — it comes back with it, so listing it would offer a restore
     *  that cannot stand alone (ADR 0061 Decision 3). */
    fun listTrashRoots(scopeId: String): List<TrashedNodeRow> {
        val all = NodeEntity.find("scopeId = ?1 and deletedAt is not null", scopeId).list()
        val trashedIds = all.mapTo(HashSet()) { it.id }
        return all.filter { it.parentId !in trashedIds }
            .sortedWith(compareByDescending<NodeEntity> { it.deletedAt }.thenBy { it.id })
            .map { TrashedNodeRow(it.id, it.parentId, it.kind, it.encryptedName, it.epoch, it.deletedAt!!) }
    }

    /** BFS the scope's rows from [rootId], collecting the root + every descendant id (state-blind by design —
     *  both callers above are the delete/restore pair, which must reach trashed rows). */
    private fun collectSubtree(all: List<NodeEntity>, rootId: String): Set<String> {
        val byParent = all.groupBy { it.parentId }
        val out = LinkedHashSet<String>()
        val stack = ArrayDeque(listOf(rootId))
        while (stack.isNotEmpty()) {
            val cur = stack.removeLast()
            if (!out.add(cur)) continue
            byParent[cur]?.forEach { stack.addLast(it.id) }
        }
        return out
    }
}
