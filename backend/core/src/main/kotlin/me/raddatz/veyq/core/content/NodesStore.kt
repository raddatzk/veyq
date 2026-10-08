package me.raddatz.veyq.core.content

import me.raddatz.veyq.core.blob.NodeAttachmentEntity
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.node.ParentSlot
import me.raddatz.veyq.core.scope.ScopeEntity
import me.raddatz.veyq.core.sharing.ShareIdentityEntity
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
    /** Every node's content key, wrapped under its parent's (ADR 0080 Decision 1) — never null. */
    val wrappedContentKey: String,
    val wrappedMeta: String?,
    /** The drop private key of a WRITE_ONLY link's shared root, sealed under its content key (ADR 0031 point 7), or null until its first WRITE_ONLY share. */
    val sealedDropPrivateKey: String? = null,
    /** How many times [wrappedMeta] was replaced — the version a meta write states (ADR 0077). */
    val metaVersion: Long = 0,
    /** How many times the node was moved or renamed — the version its name and key wrap are sealed at, and the one a
     *  placement write states (ADR 0080 Decision 3). */
    val placementVersion: Long = 0,
    /** An identity folder's guest identity (ADR 0080 decision 11, [NodeEntity.shareIdentityId]), else null. */
    val shareIdentityId: String? = null,
    /** With [shareIdentityId]: that identity's `K_up` wrapped to the parent's drop public key, the first link of the
     *  chain its entry opens through. */
    val wrappedIdentityKey: String? = null,
    /** When the server stored the node, to the millisecond ([NodeEntity.createdAt]): set once, never moved. */
    val createdAt: Instant,
)

/** One soft-deleted subtree ROOT in the space's trash (uc.memory.delete-node). `deletedAt` is the marker the
 *  resource turns into the derived `purgeAfter`; `parentId` is the slot a restore recomputes its slug against. */
data class TrashedNodeRow(
    val id: String,
    val parentId: String,
    val kind: String,
    val encryptedName: String,
    val epoch: Int,
    /** The placement version [encryptedName] is sealed at; a trash and a restore leave it alone. */
    val placementVersion: Long,
    val deletedAt: Instant,
    /** A trashed identity folder's identity and its wrapped `K_up` (ADR 0080 decision 11), which its entry opens through. */
    val shareIdentityId: String? = null,
    val wrappedIdentityKey: String? = null,
    /** Whether the node itself holds a blob in one of core's attachment slots (inbox #391: a `FILE` whose upload never
     *  finished holds none — the trash says so rather than offering it as a file). */
    val attached: Boolean = false,
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
     * the insert on flush afterwards — a clean 409 turned into a 500. A trash root holds its own id as its
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

    /**
     * Is [slot] the node [rootNodeId] or below it ([SubtreeBoundary])? The upward walk of [wouldCycle], and like it
     * without a `deletedAt` predicate: no live node hangs under a trashed ancestor. Only meaningful **under the scope
     * lock** ([lockScope]) — read before it, a member's move of an ancestor out of the subtree can commit between the
     * walk and the write, and the write then lands outside.
     */
    fun withinSubtree(scopeId: String, rootNodeId: String, slot: String): Boolean {
        var cur = slot
        while (true) {
            if (cur == rootNodeId) return true
            if (cur == scopeId) return false
            val p = NodeEntity.findById(cur) ?: return false
            if (p.scopeId != scopeId) return false
            cur = p.parentId
        }
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
        slugToken: String, orderIndex: Int, epoch: Int, wrappedContentKey: String, wrappedMeta: String?,
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
            createdAt = NodeEntity.birthInstant()
        }.persist()
        return true
    }

    fun list(scopeId: String): List<NodeRow> {
        val nodes = NodeEntity.find("scopeId = ?1 and deletedAt is null", scopeId).list()
        val wrapped = identityKeys(nodes.mapNotNull { it.shareIdentityId })
        return nodes.map {
            NodeRow(
                it.id, it.parentId, it.kind, it.encryptedName, it.slugToken, it.orderIndex, it.epoch, it.wrappedContentKey,
                it.wrappedMeta, it.sealedDropPrivateKey, it.metaVersion, it.placementVersion,
                it.shareIdentityId, it.shareIdentityId?.let(wrapped::get), it.createdAt,
            )
        }
    }

    /** The wrapped `K_up` of each of [identityIds] (ADR 0080 decision 11) — what an identity folder's entry opens
     *  through. One bounded read; an identity without a wrapped key (a READ_WRITE guest's) is absent. */
    fun identityKeys(identityIds: Collection<String>): Map<String, String> =
        if (identityIds.isEmpty()) emptyMap()
        else ShareIdentityEntity.find("id in ?1 and wrappedIdentityKey is not null", identityIds.distinct()).list()
            .associate { it.id to it.wrappedIdentityKey!! }

    /**
     * The identity whose identity folder holds [slot] — the slot itself or its nearest ancestor that names one
     * ([NodeEntity.shareIdentityId], ADR 0080 decision 11) — or null when [slot] lies in no identity folder. The upward
     * walk of [withinSubtree], and like it without a `deletedAt` predicate. Inside an identity folder only its identity
     * creates (`NodesHandler.createInScope`). Nothing ever moves INTO an identity folder (that rule refuses it), so the
     * answer for a live slot cannot change under a concurrent write.
     */
    fun identityFolderOwner(scopeId: String, slot: String): String? {
        var cur = slot
        while (cur != scopeId) {
            val p = NodeEntity.findById(cur) ?: return null
            if (p.scopeId != scopeId) return null
            p.shareIdentityId?.let { return it }
            cur = p.parentId
        }
        return null
    }

    /** The identity folder [identityId] holds directly under [parentId], live or trashed, or null. */
    fun identityFolder(parentId: String, identityId: String): NodeEntity? =
        NodeEntity.find("parentId = ?1 and shareIdentityId = ?2", parentId, identityId).firstResult()

    /** Persist an identity folder (ADR 0080 decision 11): a `FOLDER` under [parentId] that [identityId] holds, its entry
     *  and key wrap under the identity's `K_up`. Joins its caller's transaction, which decided everything under the
     *  scope lock (`IdentityFolders.ensure`); `ux_node_parent_identity` is the backstop of one per (parent, identity). */
    fun createIdentityFolder(
        id: String, scopeId: String, parentId: String, identityId: String, encryptedName: String, slugToken: String,
        epoch: Int, wrappedContentKey: String,
    ) {
        NodeEntity().apply {
            this.id = id
            this.scopeId = scopeId
            this.parentId = parentId
            this.kind = NodeEntity.FOLDER
            this.encryptedName = encryptedName
            this.slugToken = slugToken
            this.orderIndex = 0
            this.epoch = epoch
            this.wrappedContentKey = wrappedContentKey
            this.shareIdentityId = identityId
            createdAt = NodeEntity.birthInstant()
        }.persist()
    }

    /** What [applyUpdate] did: applied, found no live node, refused a placement its destination no longer allows (a
     *  trashed or foreign parent, a cycle) or whose slug is taken there, or refused a stale placement or a stale meta
     *  write (the latter with the current value). In every refusal nothing was written. */
    sealed interface ApplyResult {
        object Applied : ApplyResult
        object Missing : ApplyResult
        object IllegalMove : ApplyResult
        object SlugTaken : ApplyResult
        object StalePlacement : ApplyResult
        data class StaleMeta(val wrappedMeta: String?, val metaVersion: Long) : ApplyResult
    }

    /**
     * A placement write (ADR 0080 Decision 3): every field of the node's directory entry and key wrap at once, built on
     * [expectedVersion]. [epoch] is the epoch to store, or null to keep the stored one (a placement between two
     * non-root parents, ADR 0080 Decision 4 — the handler decides which).
     */
    data class Placement(
        val parentSlot: String,
        val encryptedName: String,
        val slugToken: String,
        val wrappedContentKey: String,
        val epoch: Int?,
        val expectedVersion: Long,
    )

    /**
     * Apply a partial update: a [placement], a reorder, a payload re-seal — each optional. A placement is written only
     * if the node's `placementVersion` is still [Placement.expectedVersion] (ADR 0080 Decision 3), a [wrappedMeta]
     * only if [metaVersion] is still the stored one (ADR 0077); on either miss **nothing** is written and the caller
     * learns which.
     *
     * **One statement.** Every column the request changes, both version bumps and both version compares sit in ONE
     * bulk UPDATE, so its row count is the whole verdict: two compare-and-sets in two statements would have to undo
     * the first when the second missed. It runs before this transaction loads the node, so no managed copy holds the
     * pre-update row for a flush to write back; on a miss the node is loaded afterwards, as it now is, to tell the two
     * conflicts apart.
     *
     * **Lock order: the scope row ([lockScope]), then the node row.** The tree's lock is taken before anything else,
     * and the node row is locked only by the UPDATE, after it. A placement decides by more than its own row — the
     * destination must be live (a trash enumerates and stamps a subtree under the same lock), must not lie inside the
     * node's own subtree, and must not already hold the slug — so it serializes on the parent of every row it reads,
     * the scope (developer-styleguide §12). Two moves that would each be legal alone but form a cycle together (A
     * into B while B into A) thus run one after the other, and the second's [wouldCycle] reads the first's committed
     * parent (READ COMMITTED, ADR 0078). No writer takes a node row and then the scope: the media writes that lock a
     * node row (`MediaStore.commitCover`, a primary bucket) never take the scope lock, and every tree write here
     * takes the scope first, so there is no inversion.
     *
     * A [boundary] ([SubtreeBoundary]) is decided here, under the lock, and only here: the node outside it is
     * [ApplyResult.Missing], the boundary's root as the node or a destination outside it [ApplyResult.IllegalMove].
     */
    @Transactional
    fun applyUpdate(
        scopeId: String, nodeId: String, placement: Placement?, orderIndex: Int?, wrappedMeta: String?, metaVersion: Long?,
        boundary: SubtreeBoundary? = null,
    ): ApplyResult {
        lockScope(scopeId)
        if (boundary != null) {
            if (nodeId == boundary.rootNodeId) return ApplyResult.IllegalMove
            if (!withinSubtree(scopeId, boundary.rootNodeId, nodeId)) return ApplyResult.Missing
            if (placement != null && !withinSubtree(scopeId, boundary.rootNodeId, placement.parentSlot)) return ApplyResult.IllegalMove
        }
        if (placement != null) {
            // Decided again under the lock — the handler's checks are the fast path, these are the verdict.
            if (parentSlot(scopeId, placement.parentSlot) is ParentSlot.Missing ||
                placement.parentSlot == nodeId || wouldCycle(scopeId, nodeId, placement.parentSlot)
            ) {
                return ApplyResult.IllegalMove
            }
            if (slugTaken(placement.parentSlot, placement.slugToken, exceptNodeId = nodeId)) return ApplyResult.SlugTaken
        }
        val sets = mutableListOf<String>()
        val conds = mutableListOf("id = :id", "deletedAt is null")
        val params = mutableMapOf<String, Any>("id" to nodeId)
        placement?.let { p ->
            sets += listOf(
                "parentId = :parentId", "encryptedName = :encryptedName", "slugToken = :slugToken",
                "wrappedContentKey = :wrappedContentKey", "placementVersion = :nextPlacement",
                // An identity folder's one possible placement is a member's move out of its drop box (a rename or a
                // move inside is refused by the kinds' placement rules): its key is re-wrapped under the new parent's,
                // so it is no identity's folder any more (ADR 0080 decision 11). A no-op on every other node.
                "shareIdentityId = null",
            )
            params += mapOf(
                "parentId" to p.parentSlot, "encryptedName" to p.encryptedName, "slugToken" to p.slugToken,
                "wrappedContentKey" to p.wrappedContentKey, "nextPlacement" to p.expectedVersion + 1,
                "placementVersion" to p.expectedVersion,
            )
            conds += "placementVersion = :placementVersion"
            p.epoch?.let { sets += "epoch = :epoch"; params["epoch"] = it }
        }
        orderIndex?.let { sets += "orderIndex = :orderIndex"; params["orderIndex"] = it }
        if (wrappedMeta != null) {
            val expected = checkNotNull(metaVersion) { "the handler refuses wrappedMeta without its metaVersion" }
            sets += listOf("wrappedMeta = :wrappedMeta", "metaVersion = :nextMeta")
            params += mapOf("wrappedMeta" to wrappedMeta, "nextMeta" to expected + 1, "metaVersion" to expected)
            conds += "metaVersion = :metaVersion"
        }
        if (sets.isEmpty()) return if (findInScope(scopeId, nodeId) != null) ApplyResult.Applied else ApplyResult.Missing
        val rows = NodeEntity.update("${sets.joinToString(", ")} where ${conds.joinToString(" and ")}", params)
        if (rows > 0) return ApplyResult.Applied
        val current = NodeEntity.findById(nodeId)?.takeIf { it.deletedAt == null } ?: return ApplyResult.Missing
        if (placement != null && current.placementVersion != placement.expectedVersion) return ApplyResult.StalePlacement
        if (wrappedMeta != null && current.metaVersion != metaVersion) return ApplyResult.StaleMeta(current.wrappedMeta, current.metaVersion)
        return ApplyResult.Missing
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
     *
     * False, with nothing stamped, when a [boundary] ([SubtreeBoundary]) refuses the node — its root, or a node outside
     * it — decided under the lock like everything else here.
     */
    @Transactional
    fun deleteSubtree(scopeId: String, nodeId: String, boundary: SubtreeBoundary? = null): Boolean {
        lockScope(scopeId) // before the enumeration below: a create or move under the subtree lands before it or not at all
        if (boundary != null &&
            (nodeId == boundary.rootNodeId || !withinSubtree(scopeId, boundary.rootNodeId, nodeId))
        ) {
            return false
        }
        // No `deletedAt is null` here — the SECOND of the three reads that deliberately keep seeing trashed rows
        // (ADR 0061). The traversal must reach descendants whatever their own state, or re-deleting a partially
        // deleted subtree stops early and strands the rest (uc.memory.delete-node E3).
        val all = NodeEntity.find("scopeId", scopeId).list()
        val root = all.firstOrNull { it.id == nodeId } ?: return true
        if (root.deletedAt != null) return true // E3: deleting an already-trashed subtree changes nothing
        val doomed = collectSubtree(all, nodeId)
        val now = Instant.now()
        for (n in all) {
            if (n.id in doomed) n.deletedAt = now
        }
        // ADR 0061 Decision 5: free the ROOT's name immediately, by the WRITE. `ux_node_parent_slug` is a plain
        // UNIQUE(parent_id, slug_token) spanning trashed rows (MariaDB has no filtered index), so a trashed root
        // would otherwise hold its name hostage under its LIVE parent for the whole retention window. The node's own
        // id is unique by construction, blind (the server may not compute a slug token — it is a MAC under the
        // parent's key), and outside the contract's base64 token alphabet, so nothing a client can send collides
        // with it. The client re-supplies a fresh token on restore.
        // Only the root: every descendant's name lives under a parent that is trashed now, and nothing can claim a
        // name there — create, move and restore all refuse a trashed parent (ADR 0080 Decision 8). So a descendant
        // keeps its token and comes back with a valid one; freeing it too would restore it holding no name at all,
        // beside which a same-named sibling could then be created (inbox #357). A descendant an EARLIER, narrower
        // delete trashed holds that delete's freed token, re-stamped or not — the server cannot re-derive it, so
        // [restoreSubtree] leaves it in the trash.
        root.slugToken = root.id
        // cr-0107 (ADR 0033): still no media_revision/delete_revision bump here, but the reason changed with
        // ADR 0061 — the node rows and their counters now SURVIVE the delete, so "no open gallery can poll a
        // deleted node" is no longer true of the rows. It is true of the path: every media read routes through
        // the node gate (`MediaStore.findNode`), which filters trashed nodes, so a gallery polling a trashed
        // node gets a 404 rather than a stale set. The conclusion the note supports is unchanged: the delete
        // leaves each node's own media rows untouched, so no *surviving* node's gallery set changes. The
        // observable delete-reconcile is still the share delete-own path (cr-0102), which bumps both counters.
        return true
    }

    /** The 3-way result of a restore: applied, no such trashed root, or the name was taken meanwhile. */
    enum class RestoreResult { DONE, NOT_FOUND, SLUG_TAKEN }

    /**
     * Restore the trashed subtree rooted at [nodeId]: clear `deletedAt` across the subtree and write the client's
     * freshly computed [slugToken] back onto the root. A subtree is on ONE marker — [deleteSubtree] re-stamps every
     * descendant, one an earlier, narrower delete had trashed included — and every descendant comes back with the token
     * it had, since only a delete's root has its token freed. **Except** such an earlier delete's root: it holds a freed
     * token, so it and its subtree stay in the trash on that one marker and it becomes a trash root of its own (inbox
     * #357; ADR 0080 Decision 8: nothing is live under a trashed parent, and nothing outlives the parent whose key
     * wraps its own — the purge of the marker erases it whole). [RestoreResult.NOT_FOUND]
     * when there is no such trash root in the scope (unknown, already purged, still live, or under a trashed parent)
     * ⇒ a non-enumerable 404.
     */
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
        // A descendant holding a FREED token (its own id) was the root of an earlier, narrower delete: its name was
        // released under a parent that was live then, and a sibling may hold it now. The server cannot re-derive a
        // slug, so that descendant does not come back here — it stays in the trash on the marker it has, with its
        // whole subtree (nothing is live under a trashed parent), and with this root live it is a trash root again,
        // restorable on its own with a token the client derives under the live parent (inbox #357).
        val subtree = collectSubtree(all, nodeId) { it.slugToken == it.id }
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
        val roots = all.filter { it.parentId !in trashedIds }
        val wrapped = identityKeys(roots.mapNotNull { it.shareIdentityId })
        val attached = if (roots.isEmpty()) emptySet() else NodeAttachmentEntity
            .find("scopeId = ?1 and nodeId in ?2", scopeId, roots.map { it.id }).list().mapTo(HashSet()) { it.nodeId }
        return roots
            .sortedWith(compareByDescending<NodeEntity> { it.deletedAt }.thenBy { it.id })
            .map {
                TrashedNodeRow(
                    it.id, it.parentId, it.kind, it.encryptedName, it.epoch, it.placementVersion, it.deletedAt!!,
                    it.shareIdentityId, it.shareIdentityId?.let(wrapped::get), it.id in attached,
                )
            }
    }

    /** BFS the scope's rows from [rootId], collecting the root + every descendant id (state-blind by design —
     *  both callers above are the delete/restore pair, which must reach trashed rows), except a node [prune] matches
     *  and everything below it. */
    private fun collectSubtree(all: List<NodeEntity>, rootId: String, prune: (NodeEntity) -> Boolean = { false }): Set<String> {
        val byParent = all.groupBy { it.parentId }
        val out = LinkedHashSet<String>()
        val stack = ArrayDeque(listOf(rootId))
        while (stack.isNotEmpty()) {
            val cur = stack.removeLast()
            if (!out.add(cur)) continue
            byParent[cur]?.forEach { if (!prune(it)) stack.addLast(it.id) }
        }
        return out
    }
}
