package me.raddatz.veyq.core.content

import me.raddatz.veyq.core.node.NodeKindRules
import me.raddatz.veyq.core.node.ParentSlot
import me.raddatz.veyq.core.http.Failures
import me.raddatz.veyq.core.http.HttpFailure
import me.raddatz.veyq.core.membership.AccessMode.READ
import me.raddatz.veyq.core.membership.AccessMode.WRITE
import me.raddatz.veyq.core.membership.ScopeAccess
import me.raddatz.veyq.core.membership.refusal
import me.raddatz.veyq.core.persistence.createOrConflict
import io.quarkus.arc.All
import jakarta.enterprise.context.ApplicationScoped

sealed interface CreateNodeOutcome {
    data class Created(val nodeId: String) : CreateNodeOutcome
    object NoScope : CreateNodeOutcome, HttpFailure by Failures.scopeNotFound
    object Suspended : CreateNodeOutcome, HttpFailure by Failures.tenantSuspended
    object ScopeDeleting : CreateNodeOutcome, HttpFailure by Failures.scopeDeleting
    object IllegalStructure : CreateNodeOutcome, HttpFailure by Failures.illegalStructure
    object SlugTaken : CreateNodeOutcome, HttpFailure by Failures.slugTaken
    object IdTaken : CreateNodeOutcome, HttpFailure by Failures.idTaken
    /** The node is tagged with an epoch that is not the scope's current one (ADR 0024 Decision 6) — nothing stored. */
    object StaleEpoch : CreateNodeOutcome, HttpFailure by Failures.staleEpoch
}

sealed interface ListNodesOutcome {
    data class Nodes(val rows: List<NodeRow>) : ListNodesOutcome
    object NoScope : ListNodesOutcome, HttpFailure by Failures.scopeNotFound
    object Suspended : ListNodesOutcome, HttpFailure by Failures.tenantSuspended
}

sealed interface UpdateNodeOutcome {
    object Updated : UpdateNodeOutcome
    object NotFound : UpdateNodeOutcome, HttpFailure by Failures.nodeNotFound
    object Suspended : UpdateNodeOutcome, HttpFailure by Failures.tenantSuspended
    object ScopeDeleting : UpdateNodeOutcome, HttpFailure by Failures.scopeDeleting
    object IllegalStructure : UpdateNodeOutcome, HttpFailure by Failures.illegalStructure
    object SlugTaken : UpdateNodeOutcome, HttpFailure by Failures.slugTaken
    /** `wrappedMeta` sent without the `metaVersion` it was built from — an old client cannot skip the check. */
    object MissingMetaVersion : UpdateNodeOutcome, HttpFailure by Failures.invalidRequest
    /** A part of a placement write without the rest — a name, slug, wrap or parent without its re-wrapped key, its
     *  `epoch` or its `placementVersion` (ADR 0080 Decision 3). */
    object IncompletePlacement : UpdateNodeOutcome, HttpFailure by Failures.invalidRequest
    /** The node was moved or renamed past the stated `placementVersion`; nothing was written (ADR 0080 Decision 3). */
    object StalePlacement : UpdateNodeOutcome, HttpFailure by Failures.stalePlacement
    /** A placement at the scope root sealed at an epoch that is not the scope's current one, or any placement stating
     *  an epoch above it (ADR 0080 Decision 4). */
    object StaleEpoch : UpdateNodeOutcome, HttpFailure by Failures.staleEpoch
    /** The node's `wrappedMeta` moved past the stated version; nothing was written (ADR 0077). Carries the current
     *  blob and version for the loser to re-apply its own change on. */
    data class StaleMeta(val wrappedMeta: String?, val metaVersion: Long) : UpdateNodeOutcome, HttpFailure by Failures.staleVersion
}

sealed interface DeleteNodeOutcome {
    object Deleted : DeleteNodeOutcome
    object NoScope : DeleteNodeOutcome, HttpFailure by Failures.scopeNotFound
    object Suspended : DeleteNodeOutcome, HttpFailure by Failures.tenantSuspended
    object ScopeDeleting : DeleteNodeOutcome, HttpFailure by Failures.scopeDeleting
}

sealed interface RestoreNodeOutcome {
    object Restored : RestoreNodeOutcome
    object NotFound : RestoreNodeOutcome, HttpFailure by Failures.nodeNotFound
    object Suspended : RestoreNodeOutcome, HttpFailure by Failures.tenantSuspended
    object ScopeDeleting : RestoreNodeOutcome, HttpFailure by Failures.scopeDeleting
    object SlugTaken : RestoreNodeOutcome, HttpFailure by Failures.slugTaken
}

sealed interface ListTrashOutcome {
    data class Trash(val rows: List<TrashedNodeRow>) : ListTrashOutcome
    object NoScope : ListTrashOutcome, HttpFailure by Failures.scopeNotFound
    object Suspended : ListTrashOutcome, HttpFailure by Failures.tenantSuspended
}

/**
 * Use-case logic for the node tree (en.memory.tree, cr-0027). Scope access is the one gate, [ScopeAccess],
 * asked first by every operation (uc.spaces.scope-access): a non-member sees a 404, not a reveal. Structural rules:
 * a parent that is missing, trashed or in another scope refuses every kind; where a kind may live is the one
 * [NodeKindRules] that handles it (core's `FOLDER`, an application's own kinds — ADR 0079), none or several →
 * `illegal_structure`; no cycle on move; slug unique per parent.
 */
@ApplicationScoped
class NodesHandler(
    private val store: NodesStore,
    private val access: ScopeAccess,
    // `MutableList`, not `List`: ArC refuses `@All kotlin.List` (see `ScopePurge`'s constructor).
    @All private val kindRules: MutableList<NodeKindRules>,
) {

    private fun parentAllows(kind: String, parent: ParentSlot): Boolean =
        NodeKindRules.parentAllows(kindRules, kind, parent)

    fun create(
        userId: String, scopeId: String, nodeId: String, parentSlotId: String?, kind: String,
        encryptedName: String, slugToken: String, orderIndex: Int, epoch: Int, wrappedContentKey: String, wrappedMeta: String?,
    ): CreateNodeOutcome {
        access.check(userId, scopeId, WRITE)
            .refusal(CreateNodeOutcome.NoScope, CreateNodeOutcome.Suspended, CreateNodeOutcome.ScopeDeleting)?.let { return it }
        return createInScope(scopeId, nodeId, parentSlotId, kind, encryptedName, slugToken, orderIndex, epoch, wrappedContentKey, wrappedMeta)
    }

    /**
     * The principal-free half of [create]: everything after the caller's own gate (a member's [ScopeAccess], a guest's
     * share gate). A [boundary] confines the parent to a subtree, decided under the scope lock (a parent outside it is
     * `illegal_structure`, like a missing one).
     *
     * It owns its transaction ([createOrConflict]): a caller must not call it inside an open one, so it cannot hold a
     * lock of its own — a share row's — across it; the scope lock is the first lock this write takes.
     */
    internal fun createInScope(
        scopeId: String, nodeId: String, parentSlotId: String?, kind: String,
        encryptedName: String, slugToken: String, orderIndex: Int, epoch: Int, wrappedContentKey: String, wrappedMeta: String?,
        boundary: SubtreeBoundary? = null,
    ): CreateNodeOutcome {
        // ADR 0024 Decision 6, layer 1: content sealed under an epoch below the current one is readable by every
        // member removed since — a writer whose grant lags a rotation. The check rename and set-icon already make.
        // Unlocked, like theirs: a create racing the rotation that supersedes its epoch was sealed while that epoch
        // was current, which is the same as having landed a moment earlier.
        if (epoch != store.currentEpoch(scopeId)) return CreateNodeOutcome.StaleEpoch
        val slot = parentSlotId ?: scopeId // null/absent → the scope root (slot = scope id)
        val parent = store.parentSlot(scopeId, slot)
        if (parent is ParentSlot.Missing) return CreateNodeOutcome.IllegalStructure
        if (!parentAllows(kind, parent)) return CreateNodeOutcome.IllegalStructure
        if (store.slugTaken(slot, slugToken)) return CreateNodeOutcome.SlugTaken
        // The client-minted id (ADR 0072): the name, content-key wrap and payload are sealed bound to it, so it is stored
        // verbatim or not at all.
        // A node is unique twice — its id, and its slug under the parent (`ux_node_parent_slug`) — so a concurrent
        // create's loser re-asks both, in the order above: what its replay answers. That holds too when it loses at
        // the store's own id check, which a winner that committed after the slug check above already trips.
        val replay = {
            when {
                store.slugTaken(slot, slugToken) -> CreateNodeOutcome.SlugTaken
                store.idTaken(nodeId) -> CreateNodeOutcome.IdTaken
                else -> null
            }
        }
        return createOrConflict(conflict = replay) {
            // The parent again, under the tree's lock (NodesStore.lockScope): read above only, a parent trashed in
            // between took its subtree without this node, which then stayed live under it.
            store.lockScope(scopeId)
            if (store.parentSlot(scopeId, slot) is ParentSlot.Missing) return@createOrConflict CreateNodeOutcome.IllegalStructure
            // The boundary only here, under the lock: decided before it, a member's move of the parent's ancestor out
            // of the subtree could commit in between, and the node would land outside.
            if (boundary != null && !store.withinSubtree(scopeId, boundary.rootNodeId, slot)) {
                return@createOrConflict CreateNodeOutcome.IllegalStructure
            }
            val created = store.create(nodeId, scopeId, slot, kind, encryptedName, slugToken, orderIndex, epoch, wrappedContentKey, wrappedMeta)
            if (created) CreateNodeOutcome.Created(nodeId) else replay() ?: CreateNodeOutcome.IdTaken
        }
    }

    fun list(userId: String, scopeId: String): ListNodesOutcome {
        access.check(userId, scopeId, READ).refusal(ListNodesOutcome.NoScope, ListNodesOutcome.Suspended)?.let { return it }
        return ListNodesOutcome.Nodes(store.list(scopeId))
    }

    /**
     * What a placement write (a move or a rename — one request shape, ADR 0080 Decision 3) carries: the destination
     * ([parentId], null = the scope root; a rename names the current parent), the node's content key re-wrapped under
     * the destination's key, the name and slug re-sealed under it, the [epoch] they were sealed at and the
     * [placementVersion] they were built on. Every field but the parent is required once any of them is sent.
     */
    data class PlacementRequest(
        val parentId: String?,
        val encryptedName: String?,
        val slugToken: String?,
        val wrappedContentKey: String?,
        val epoch: Int?,
        val placementVersion: Long?,
    ) {
        /** Whether the request asks for a placement at all — any of its fields, the destination included. */
        val present: Boolean
            get() = parentId != null || encryptedName != null || slugToken != null || wrappedContentKey != null ||
                epoch != null || placementVersion != null

        /** Whether every field a placement needs is there: a rename without a re-wrapped key cannot land, because
         *  the old wrap is sealed at the old version and would stop opening (bite test 15). */
        val complete: Boolean
            get() = !encryptedName.isNullOrBlank() && !slugToken.isNullOrBlank() && !wrappedContentKey.isNullOrBlank() &&
                epoch != null && placementVersion != null
    }

    fun update(
        userId: String, scopeId: String, nodeId: String, placement: PlacementRequest, orderIndex: Int?,
        wrappedMeta: String?, metaVersion: Long?,
    ): UpdateNodeOutcome {
        // The request's own shape first, as the boundary's validation is: a malformed body is a 400 for anyone.
        malformed(placement, wrappedMeta, metaVersion)?.let { return it }
        access.check(userId, scopeId, WRITE)
            .refusal(UpdateNodeOutcome.NotFound, UpdateNodeOutcome.Suspended, UpdateNodeOutcome.ScopeDeleting)?.let { return it }
        return updateInScope(scopeId, nodeId, placement, orderIndex, wrappedMeta, metaVersion)
    }

    private fun malformed(placement: PlacementRequest, wrappedMeta: String?, metaVersion: Long?): UpdateNodeOutcome? = when {
        wrappedMeta != null && metaVersion == null -> UpdateNodeOutcome.MissingMetaVersion
        placement.present && !placement.complete -> UpdateNodeOutcome.IncompletePlacement
        else -> null
    }

    /**
     * The principal-free half of [update]: everything after the caller's own gate (a member's [ScopeAccess], a guest's
     * share gate). A [boundary] confines the node (strictly inside — never the boundary's root) and the destination
     * (inside, the root included); [NodesStore.applyUpdate] decides it under the scope lock, never this function's
     * unlocked fast path: the node outside is `node_not_found`, the root as the node or a destination outside it
     * `illegal_structure`.
     *
     * Lock order: the scope row ([NodesStore.lockScope]), then the node row. A caller may run this inside its own
     * transaction (`applyUpdate` joins it) and take the scope lock first, then rows of its own (a share row); it must
     * never lock one of its own rows or a node row BEFORE the scope lock — every tree writer takes the scope first,
     * so that order would invert theirs.
     */
    internal fun updateInScope(
        scopeId: String, nodeId: String, placement: PlacementRequest, orderIndex: Int?,
        wrappedMeta: String?, metaVersion: Long?, boundary: SubtreeBoundary? = null,
    ): UpdateNodeOutcome {
        malformed(placement, wrappedMeta, metaVersion)?.let { return it }
        // A trashed node is not found: it is neither movable nor renamable (its entry is restored, not placed).
        val node = store.findInScope(scopeId, nodeId) ?: return UpdateNodeOutcome.NotFound

        var write: NodesStore.Placement? = null
        if (placement.present) {
            val slot = placement.parentId ?: scopeId // null/absent → the scope root (slot = scope id)
            val parent = store.parentSlot(scopeId, slot)
            // A missing, trashed or foreign destination; a kind its placement rule refuses there; the node itself or
            // a node inside its own subtree (a cycle). The fast path — the store decides the first and the last again
            // under the tree's lock.
            if (parent is ParentSlot.Missing) return UpdateNodeOutcome.IllegalStructure
            if (!parentAllows(node.kind, parent)) return UpdateNodeOutcome.IllegalStructure
            if (slot == nodeId || store.wouldCycle(scopeId, nodeId, slot)) return UpdateNodeOutcome.IllegalStructure
            // ADR 0080 Decision 4: an entry at the scope root lives under K_SC, so it is sealed at the CURRENT epoch —
            // a rename at the root and a move into it alike (ADR 0024 Decision 6: an older epoch's key is one a member
            // removed since still holds). Unlocked, like create's: a placement racing the rotation that supersedes
            // its epoch was sealed while that epoch was current. Below the root the entry is under the parent's key,
            // which no rotation changes: a move out of the root stores the epoch the client sealed at, and a
            // placement between two non-root parents keeps the stored one (the client seals at it; the
            // compare-and-set guarantees the parent it read is still the node's).
            val epoch = placement.epoch!!
            val current = store.currentEpoch(scopeId)
            // For ANY destination, an epoch above the scope's current one (or a scope without one) is refused: no
            // epoch past the current exists, so nothing can be sealed at it, and a move out of the root stores the
            // request's epoch — unchecked, it put an epoch on the row that no later reader or rotation can account
            // for. An epoch BELOW the current one stays accepted off the root: it is the entry's own sealed epoch,
            // and there it opens nothing (the entry is under the parent's key, not K_SC@epoch).
            if (current == null || epoch > current) return UpdateNodeOutcome.StaleEpoch
            val storedEpoch = when {
                parent is ParentSlot.Root -> {
                    if (epoch != current) return UpdateNodeOutcome.StaleEpoch
                    epoch
                }
                node.parentId == scopeId -> epoch
                else -> null
            }
            write = NodesStore.Placement(
                parentSlot = slot,
                encryptedName = placement.encryptedName!!,
                slugToken = placement.slugToken!!,
                wrappedContentKey = placement.wrappedContentKey!!,
                epoch = storedEpoch,
                expectedVersion = placement.placementVersion!!,
            )
        }
        return when (val r = store.applyUpdate(scopeId, nodeId, write, orderIndex, wrappedMeta, metaVersion, boundary)) {
            NodesStore.ApplyResult.Applied -> UpdateNodeOutcome.Updated
            NodesStore.ApplyResult.Missing -> UpdateNodeOutcome.NotFound
            NodesStore.ApplyResult.IllegalMove -> UpdateNodeOutcome.IllegalStructure
            NodesStore.ApplyResult.SlugTaken -> UpdateNodeOutcome.SlugTaken
            NodesStore.ApplyResult.StalePlacement -> UpdateNodeOutcome.StalePlacement
            is NodesStore.ApplyResult.StaleMeta -> UpdateNodeOutcome.StaleMeta(r.wrappedMeta, r.metaVersion)
        }
    }

    fun delete(userId: String, scopeId: String, nodeId: String): DeleteNodeOutcome {
        access.check(userId, scopeId, WRITE)
            .refusal(DeleteNodeOutcome.NoScope, DeleteNodeOutcome.Suspended, DeleteNodeOutcome.ScopeDeleting)?.let { return it }
        return deleteInScope(scopeId, nodeId)
    }

    /**
     * The principal-free half of [delete]: everything after the caller's own gate. A [boundary] confines the node
     * (strictly inside — never the boundary's root), decided under the scope lock in [NodesStore.deleteSubtree]; a
     * node it refuses answers the same non-enumerable 404 as a foreign one. Same lock order as [updateInScope]: the
     * scope lock first, never a caller's own row before it.
     */
    internal fun deleteInScope(scopeId: String, nodeId: String, boundary: SubtreeBoundary? = null): DeleteNodeOutcome {
        // Bind the node to THIS scope before deleting (mirrors update, cr-0126): membership on scopeId alone does
        // not authorize deleting a node that lives in another scope. Same NoScope 404 whether the node is foreign
        // or absent — non-enumerable, and it stops the cross-scope delete the primary-key path used to allow.
        // Deliberately the trash-blind lookup: an already-trashed subtree is a no-op 204 (E3), a foreign or
        // unknown node is still the non-enumerable 404 the cross-scope case pins.
        store.findDeletableInScope(scopeId, nodeId) ?: return DeleteNodeOutcome.NoScope
        if (!store.deleteSubtree(scopeId, nodeId, boundary)) return DeleteNodeOutcome.NoScope
        return DeleteNodeOutcome.Deleted
    }

    /**
     * Restore a trashed subtree (uc.memory.delete-node step 6). The client supplies the freshly computed
     * `slugToken` — the server cannot recompute a blinded MAC under `K_SC`, and the delete consumed the
     * original when it freed the name.
     */
    fun restore(
        userId: String, scopeId: String, nodeId: String, slugToken: String, encryptedName: String?,
    ): RestoreNodeOutcome {
        access.check(userId, scopeId, WRITE)
            .refusal(RestoreNodeOutcome.NotFound, RestoreNodeOutcome.Suspended, RestoreNodeOutcome.ScopeDeleting)?.let { return it }
        return when (store.restoreSubtree(scopeId, nodeId, slugToken, encryptedName)) {
            NodesStore.RestoreResult.DONE -> RestoreNodeOutcome.Restored
            NodesStore.RestoreResult.SLUG_TAKEN -> RestoreNodeOutcome.SlugTaken
            NodesStore.RestoreResult.NOT_FOUND -> RestoreNodeOutcome.NotFound
        }
    }

    fun listTrash(userId: String, scopeId: String): ListTrashOutcome {
        access.check(userId, scopeId, READ).refusal(ListTrashOutcome.NoScope, ListTrashOutcome.Suspended)?.let { return it }
        return ListTrashOutcome.Trash(store.listTrashRoots(scopeId))
    }
}
