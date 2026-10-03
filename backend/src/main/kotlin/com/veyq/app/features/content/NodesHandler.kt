package com.veyq.app.features.content

import com.veyq.app.core.node.NodeEntity
import com.veyq.app.shared.http.Failures
import com.veyq.app.shared.http.HttpFailure
import com.veyq.app.shared.membership.AccessMode.READ
import com.veyq.app.shared.membership.AccessMode.WRITE
import com.veyq.app.shared.membership.ScopeAccess
import com.veyq.app.shared.membership.refusal
import com.veyq.app.shared.persistence.createOrConflict
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
 * Use-case logic for the Memory tree (en.memory.tree, cr-0027). Scope access is the one gate, [ScopeAccess],
 * asked first by every operation (uc.spaces.scope-access): a non-member sees a 404, not a reveal. Structural rules: FOLDER/MEMORY live
 * at the root or under a FOLDER; CHAPTER lives inside a MEMORY; no cycle on move; slug unique per parent.
 */
@ApplicationScoped
class NodesHandler(private val store: NodesStore, private val access: ScopeAccess) {

    private fun parentAllows(kind: String, parent: ParentSlot): Boolean = when (kind) {
        NodeEntity.FOLDER, NodeEntity.MEMORY ->
            parent is ParentSlot.Root || (parent is ParentSlot.Node && parent.kind == NodeEntity.FOLDER)
        NodeEntity.CHAPTER -> parent is ParentSlot.Node && parent.kind == NodeEntity.MEMORY
        else -> false
    }

    fun create(
        userId: String, scopeId: String, nodeId: String, parentSlotId: String?, kind: String,
        encryptedName: String, slugToken: String, orderIndex: Int, epoch: Int, wrappedContentKey: String?, wrappedMeta: String?,
    ): CreateNodeOutcome {
        access.check(userId, scopeId, WRITE)
            .refusal(CreateNodeOutcome.NoScope, CreateNodeOutcome.Suspended, CreateNodeOutcome.ScopeDeleting)?.let { return it }
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
        // The client-minted id (ADR 0072): the name, K_mem wrap and meta are sealed bound to it, so it is stored
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
            val created = store.create(nodeId, scopeId, slot, kind, encryptedName, slugToken, orderIndex, epoch, wrappedContentKey, wrappedMeta)
            if (created) CreateNodeOutcome.Created(nodeId) else replay() ?: CreateNodeOutcome.IdTaken
        }
    }

    fun list(userId: String, scopeId: String): ListNodesOutcome {
        access.check(userId, scopeId, READ).refusal(ListNodesOutcome.NoScope, ListNodesOutcome.Suspended)?.let { return it }
        return ListNodesOutcome.Nodes(store.list(scopeId))
    }

    fun update(
        userId: String, scopeId: String, nodeId: String,
        encryptedName: String?, slugToken: String?, newParentSlotId: String?, orderIndex: Int?, wrappedMeta: String?,
        metaVersion: Long?,
    ): UpdateNodeOutcome {
        // The request's own shape first, as the boundary's validation is: a malformed body is a 400 for anyone.
        if (wrappedMeta != null && metaVersion == null) return UpdateNodeOutcome.MissingMetaVersion
        access.check(userId, scopeId, WRITE)
            .refusal(UpdateNodeOutcome.NotFound, UpdateNodeOutcome.Suspended, UpdateNodeOutcome.ScopeDeleting)?.let { return it }
        val node = store.findInScope(scopeId, nodeId) ?: return UpdateNodeOutcome.NotFound

        var moveSlot: String? = null
        if (newParentSlotId != null) { // a move; the root is addressed as the scope id, never null
            val parent = store.parentSlot(scopeId, newParentSlotId)
            if (parent is ParentSlot.Missing) return UpdateNodeOutcome.IllegalStructure
            if (!parentAllows(node.kind, parent)) return UpdateNodeOutcome.IllegalStructure
            if (newParentSlotId == nodeId || store.wouldCycle(scopeId, nodeId, newParentSlotId)) {
                return UpdateNodeOutcome.IllegalStructure
            }
            moveSlot = newParentSlotId
        }
        val targetSlot = moveSlot ?: node.parentId
        if (slugToken != null && store.slugTaken(targetSlot, slugToken, exceptNodeId = nodeId)) {
            return UpdateNodeOutcome.SlugTaken
        }
        return when (val r = store.applyUpdate(scopeId, nodeId, encryptedName, slugToken, moveSlot, orderIndex, wrappedMeta, metaVersion)) {
            NodesStore.ApplyResult.Applied -> UpdateNodeOutcome.Updated
            NodesStore.ApplyResult.Missing -> UpdateNodeOutcome.NotFound
            NodesStore.ApplyResult.IllegalMove -> UpdateNodeOutcome.IllegalStructure
            is NodesStore.ApplyResult.StaleMeta -> UpdateNodeOutcome.StaleMeta(r.wrappedMeta, r.metaVersion)
        }
    }

    fun delete(userId: String, scopeId: String, nodeId: String): DeleteNodeOutcome {
        access.check(userId, scopeId, WRITE)
            .refusal(DeleteNodeOutcome.NoScope, DeleteNodeOutcome.Suspended, DeleteNodeOutcome.ScopeDeleting)?.let { return it }
        // Bind the node to THIS scope before deleting (mirrors update, cr-0126): membership on scopeId alone does
        // not authorize deleting a node that lives in another scope. Same NoScope 404 whether the node is foreign
        // or absent — non-enumerable, and it stops the cross-scope delete the primary-key path used to allow.
        // Deliberately the trash-blind lookup: an already-trashed subtree is a no-op 204 (E3), a foreign or
        // unknown node is still the non-enumerable 404 the cross-scope case pins.
        store.findDeletableInScope(scopeId, nodeId) ?: return DeleteNodeOutcome.NoScope
        store.deleteSubtree(scopeId, nodeId)
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
