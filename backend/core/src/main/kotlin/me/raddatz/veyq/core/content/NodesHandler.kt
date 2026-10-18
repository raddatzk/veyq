package me.raddatz.veyq.core.content

import me.raddatz.veyq.core.identity.PrincipalKey
import me.raddatz.veyq.core.node.NodeKindRules
import me.raddatz.veyq.core.node.ParentSlot
import me.raddatz.veyq.core.http.Failures
import me.raddatz.veyq.core.http.HttpFailure
import me.raddatz.veyq.core.membership.AccessMode.READ
import me.raddatz.veyq.core.membership.AccessMode.WRITE
import me.raddatz.veyq.core.membership.ScopeAccess
import me.raddatz.veyq.core.membership.refusal
import me.raddatz.veyq.core.persistence.createOrConflict
import me.raddatz.veyq.core.platform.capabilities.CapabilityRegistry
import me.raddatz.veyq.core.moderation.block.ModerationBlocks
import io.quarkus.arc.All
import io.quarkus.narayana.jta.QuarkusTransaction
import jakarta.enterprise.context.ApplicationScoped

sealed interface CreateNodeOutcome {
    data class Created(val nodeId: String) : CreateNodeOutcome
    object NoScope : CreateNodeOutcome, HttpFailure by Failures.scopeNotFound
    object Suspended : CreateNodeOutcome, HttpFailure by Failures.tenantSuspended
    object ScopeDeleting : CreateNodeOutcome, HttpFailure by Failures.scopeDeleting
    object IllegalStructure : CreateNodeOutcome, HttpFailure by Failures.illegalStructure
    object SlugTaken : CreateNodeOutcome, HttpFailure by Failures.slugTaken
    object IdTaken : CreateNodeOutcome, HttpFailure by Failures.idTaken
    /** The node is tagged with an epoch that is not the scope's current one (ADR 0024 Decision 6) — a bounded create:
     *  one above it (ADR 0080 Decision 6) — nothing stored. */
    object StaleEpoch : CreateNodeOutcome, HttpFailure by Failures.staleEpoch
    /** Creating the kind needs a capability that is switched off instance-wide ([NodeKindRules.capabilityToCreate],
     *  ADR 0088) — nothing stored. */
    object CapabilityDisabled : CreateNodeOutcome, HttpFailure by Failures.capabilityDisabled
}

sealed interface ListNodesOutcome {
    /** The scope's live rows and the tree [revision] they were read at (ADR 0081): read BEFORE them, so the rows are never older than the tag. */
    data class Nodes(val rows: List<NodeRow>, val revision: Long) : ListNodesOutcome

    /** The client's `If-None-Match` equals the scope's tree revision: nothing a level view shows changed, and no row was read. */
    object NotModified : ListNodesOutcome
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
    /** `wrappedMeta` sent without the `metaRevision` it was built from — an old client cannot skip the check. */
    object MissingMetaRevision : UpdateNodeOutcome, HttpFailure by Failures.invalidRequest
    /** A part of a placement write without the rest — a name, slug, wrap or parent without its re-wrapped key, its
     *  `epoch` or its `placementRevision` (ADR 0080 Decision 3). */
    object IncompletePlacement : UpdateNodeOutcome, HttpFailure by Failures.invalidRequest
    /** The node was moved or renamed past the stated `placementRevision`; nothing was written (ADR 0080 Decision 3). */
    object StalePlacement : UpdateNodeOutcome, HttpFailure by Failures.stalePlacement
    /** A placement at the scope root sealed at an epoch that is not the scope's current one, or any placement stating
     *  an epoch above it (ADR 0080 Decision 4). */
    object StaleEpoch : UpdateNodeOutcome, HttpFailure by Failures.staleEpoch
    /** The node's `wrappedMeta` moved past the stated revision; nothing was written (ADR 0077). Carries the current
     *  blob and revision for the loser to re-apply its own change on. */
    data class StaleMeta(val wrappedMeta: String?, val metaRevision: Long) : UpdateNodeOutcome, HttpFailure by Failures.staleRevision
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
    /** A moderator blocked it or a node above it — deleted, never restored, while the block stands (451). */
    object Blocked : RestoreNodeOutcome, HttpFailure by Failures.blockedByOperator
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
 * `illegal_structure`; no cycle on move; slug unique per parent. Whether a kind may be created at all on this instance
 * is the same rule's [NodeKindRules.capabilityToCreate], asked of [capabilities] (`capability_disabled`).
 */
@ApplicationScoped
class NodesHandler(
    private val store: NodesStore,
    private val access: ScopeAccess,
    // `MutableList`, not `List`: ArC refuses `@All kotlin.List` (see `ScopePurge`'s constructor).
    @All private val kindRules: MutableList<NodeKindRules>,
    private val capabilities: CapabilityRegistry,
    private val blocks: ModerationBlocks,
) {

    private fun parentAllows(kind: String, parent: ParentSlot): Boolean =
        NodeKindRules.parentAllows(kindRules, kind, parent)

    /**
     * The epoch rule for an entry OFF the scope root (ADR 0080 Decisions 4 and 6): it is under its parent's key, not
     * `K_SC`, so it may carry the epoch it was sealed at — the [current] one or one below, which there opens nothing.
     * One above the current (or a scope without one) was sealed at no epoch that exists; stored, it is a number no
     * later reader or rotation can account for. A member's placement off the root and a guest's create ask it.
     */
    private fun sealableOffRoot(epoch: Int, current: Int?): Boolean = current != null && epoch <= current

    fun create(
        userId: String, scopeId: String, nodeId: String, parentSlotId: String?, kind: String,
        encryptedName: String, slugToken: String, orderIndex: Int, epoch: Int, wrappedContentKey: String, wrappedMeta: String?,
    ): CreateNodeOutcome {
        access.check(userId, scopeId, WRITE)
            .refusal(CreateNodeOutcome.NoScope, CreateNodeOutcome.Suspended, CreateNodeOutcome.ScopeDeleting)?.let { return it }
        // The member's create opens its own transaction: the loser of a concurrent create answers as its replay does.
        return createOrConflict(conflict = createReplay(scopeId, parentSlotId, slugToken, nodeId)) {
            createInScope(
                scopeId, nodeId, parentSlotId, kind, encryptedName, slugToken, orderIndex, epoch, wrappedContentKey, wrappedMeta,
                editor = PrincipalKey.User(userId),
            )
        }
    }

    /**
     * The replay a create's loser answers (developer-styleguide §12, the insert half) — the `conflict` of the
     * [createOrConflict] a caller of [createInScope] opens. A node is unique twice — its id, and its slug under the
     * parent (`ux_node_parent_slug`) — so a concurrent create's loser re-asks both, in the order [createInScope] checks
     * them. That holds too when it loses at the store's own id check, which a winner that committed after the slug
     * check already trips.
     */
    internal fun createReplay(scopeId: String, parentSlotId: String?, slugToken: String, nodeId: String): () -> CreateNodeOutcome? = {
        when {
            store.slugTaken(parentSlotId ?: scopeId, slugToken) -> CreateNodeOutcome.SlugTaken
            store.idTaken(nodeId) -> CreateNodeOutcome.IdTaken
            else -> null
        }
    }

    /**
     * The principal-free half of [create]: everything after the caller's own gate (a member's [ScopeAccess], a guest's
     * share gate). A [boundary] confines the parent to a subtree, decided under the scope lock (a parent outside it is
     * `illegal_structure`, like a missing one).
     *
     * It **joins its caller's transaction**, like [updateInScope] and [deleteInScope], and refuses to run without one:
     * the caller opens it with [createOrConflict] and [createReplay] — a failed flush marks a JTA transaction
     * rollback-only, so only the transaction's opener can answer a lost race as its replay. A caller may therefore take
     * the scope lock ([NodesStore.lockScope]) first and then a row of its own — a guest's share row
     * (`ShareNodesHandler`) — before this runs, all in the one transaction that writes. It must never lock a row of its
     * own BEFORE the scope lock: every tree writer takes the scope first, so that order would invert theirs.
     *
     * **Inside an identity folder only its identity creates** (ADR 0080 decision 11: members, and guests of a link
     * above the drop box, place nothing of their own there): a parent that is, or lies inside, a guest's identity folder
     * ([NodesStore.identityFolderOwner]) is `illegal_structure` unless [identityWriter] is that folder's identity — a
     * `WRITE_ONLY` guest creating in its own folder (`ShareNodesHandler`).
     *
     * [editor] is the caller the caller's gate authenticated (a member's principal, a guest's token identity): the new
     * node's name editor, and its meta's when it has one ([NodesStore.create]).
     */
    internal fun createInScope(
        scopeId: String, nodeId: String, parentSlotId: String?, kind: String,
        encryptedName: String, slugToken: String, orderIndex: Int, epoch: Int, wrappedContentKey: String, wrappedMeta: String?,
        editor: PrincipalKey, boundary: SubtreeBoundary? = null, identityWriter: String? = null,
    ): CreateNodeOutcome {
        check(QuarkusTransaction.isActive()) { "createInScope joins its caller's transaction; open one with createOrConflict" }
        // Every decision under the tree's lock (NodesStore.lockScope), taken before ANY node is read. Not only because
        // a parent trashed, or an ancestor moved out of the boundary, between an unlocked read and the write would let
        // the node land under a trashed parent or outside the subtree: this runs inside its caller's transaction, and
        // a node read before the lock stays in that transaction's persistence context — a check after the lock is
        // then handed the stale copy instead of the committed row (measured: `SubtreeBoundaryRaceCoreSliceTest`'s
        // create landed outside with the reads before the lock). A caller that took the lock already — a guest's
        // create — re-takes it here at no cost: the row is this transaction's.
        store.lockScope(scopeId)
        // ADR 0024 Decision 6, layer 1: content sealed under an epoch below the current one is readable by every
        // member removed since — a writer whose grant lags a rotation. Under the lock a rotation takes, so a rotation
        // that committed first is seen. A bounded create (a guest's, ADR 0080 Decision 6) never lands at the scope
        // root — its parent is a node of the subtree, the boundary below refuses any other — so it takes the rule
        // off the root: the epoch it sealed with, at or below the current one, stored as sent (its name and wrap
        // bind it, so the reader must open at that number).
        val current = store.currentEpoch(scopeId)
        val epochAdmitted = if (boundary == null) epoch == current else sealableOffRoot(epoch, current)
        if (!epochAdmitted) return CreateNodeOutcome.StaleEpoch
        val slot = parentSlotId ?: scopeId // null/absent → the scope root (slot = scope id)
        val parent = store.parentSlot(scopeId, slot)
        if (parent is ParentSlot.Missing) return CreateNodeOutcome.IllegalStructure
        // The boundary before anything that answers by what lies outside it (a slug taken there).
        if (boundary != null && !store.withinSubtree(scopeId, boundary.rootNodeId, slot)) return CreateNodeOutcome.IllegalStructure
        if (!parentAllows(kind, parent)) return CreateNodeOutcome.IllegalStructure
        // ADR 0088: a kind whose creation needs a switched-off capability is refused here, on the member's and the
        // guest's create alike (both reach this) — after the placement rule, so a misplaced node answers as it always
        // did. Only creation: a node of the kind that exists is moved and renamed as before (decision 5).
        NodeKindRules.capabilityToCreate(kindRules, kind)?.let { if (!capabilities.isActiveInScope(it, scopeId)) return CreateNodeOutcome.CapabilityDisabled }
        store.identityFolderOwner(scopeId, slot)?.let { owner -> if (owner != identityWriter) return CreateNodeOutcome.IllegalStructure }
        if (store.slugTaken(slot, slugToken)) return CreateNodeOutcome.SlugTaken
        // The client-minted id (ADR 0072): the name, content-key wrap and payload are sealed bound to it, so it is
        // stored verbatim or not at all.
        val created = store.create(nodeId, scopeId, slot, kind, encryptedName, slugToken, orderIndex, epoch, wrappedContentKey, wrappedMeta, editor)
        return if (created) CreateNodeOutcome.Created(nodeId) else createReplay(scopeId, parentSlotId, slugToken, nodeId)() ?: CreateNodeOutcome.IdTaken
    }

    /**
     * The scope's tree. [ifNoneMatch] is the client's last-seen tree revision (ADR 0081): equal to the current one ⇒
     * [ListNodesOutcome.NotModified] before any row is read — the access gate still ran first, so a non-member learns
     * nothing of the revision. The revision is read BEFORE the rows (each statement reads what is committed, ADR 0078):
     * a change that commits in between puts newer rows under an older tag, and the next poll's 200 re-reads them; the
     * other order would tag older rows with a newer revision and strand the change until the next one.
     */
    fun list(userId: String, scopeId: String, ifNoneMatch: Long? = null): ListNodesOutcome {
        access.check(userId, scopeId, READ).refusal(ListNodesOutcome.NoScope, ListNodesOutcome.Suspended)?.let { return it }
        val revision = store.treeRevision(scopeId)
        if (ifNoneMatch != null && ifNoneMatch == revision) return ListNodesOutcome.NotModified
        return ListNodesOutcome.Nodes(store.list(scopeId), revision)
    }

    /**
     * What a placement write (a move or a rename — one request shape, ADR 0080 Decision 3) carries: the destination
     * ([parentId], null = the scope root; a rename names the current parent), the node's content key re-wrapped under
     * the destination's key, the name and slug re-sealed under it, the [epoch] they were sealed at and the
     * [placementRevision] they were built on. Every field but the parent is required once any of them is sent.
     */
    data class PlacementRequest(
        val parentId: String?,
        val encryptedName: String?,
        val slugToken: String?,
        val wrappedContentKey: String?,
        val epoch: Int?,
        val placementRevision: Long?,
    ) {
        /** Whether the request asks for a placement at all — any of its fields, the destination included. */
        val present: Boolean
            get() = parentId != null || encryptedName != null || slugToken != null || wrappedContentKey != null ||
                epoch != null || placementRevision != null

        /** Whether every field a placement needs is there: a rename without a re-wrapped key cannot land, because
         *  the old wrap is sealed at the old revision and would stop opening (bite test 15). */
        val complete: Boolean
            get() = completeWithoutEpoch && epoch != null

        /** [complete] but for the [epoch] — what a bounded placement needs ([updateInScope]): it never touches the
         *  scope root, so it keeps the node's stored epoch and states none. */
        val completeWithoutEpoch: Boolean
            get() = !encryptedName.isNullOrBlank() && !slugToken.isNullOrBlank() && !wrappedContentKey.isNullOrBlank() &&
                placementRevision != null
    }

    fun update(
        userId: String, scopeId: String, nodeId: String, placement: PlacementRequest, orderIndex: Int?,
        wrappedMeta: String?, metaRevision: Long?,
    ): UpdateNodeOutcome {
        // The request's own shape first, as the boundary's validation is: a malformed body is a 400 for anyone.
        malformed(placement, wrappedMeta, metaRevision)?.let { return it }
        access.check(userId, scopeId, WRITE)
            .refusal(UpdateNodeOutcome.NotFound, UpdateNodeOutcome.Suspended, UpdateNodeOutcome.ScopeDeleting)?.let { return it }
        return updateInScope(scopeId, nodeId, placement, orderIndex, wrappedMeta, metaRevision, PrincipalKey.User(userId))
    }

    /** [epochOptional]: only a bounded placement may leave the epoch out ([updateInScope]). */
    private fun malformed(
        placement: PlacementRequest, wrappedMeta: String?, metaRevision: Long?, epochOptional: Boolean = false,
    ): UpdateNodeOutcome? = when {
        wrappedMeta != null && metaRevision == null -> UpdateNodeOutcome.MissingMetaRevision
        placement.present && !(if (epochOptional) placement.completeWithoutEpoch else placement.complete) ->
            UpdateNodeOutcome.IncompletePlacement
        else -> null
    }

    /**
     * The principal-free half of [update]: everything after the caller's own gate (a member's [ScopeAccess], a guest's
     * share gate). A [boundary] confines the node (strictly inside — never the boundary's root) and the destination
     * (inside, the root included); [NodesStore.applyUpdate] decides it under the scope lock, never this function's
     * unlocked fast path: the node outside is `node_not_found`, the root as the node or a destination outside it
     * `illegal_structure`.
     *
     * **A bounded placement may state no epoch** (a guest's, ADR 0080 Decision 6): its node lies strictly inside the
     * boundary and its destination inside it, so neither its current parent nor its destination is the scope root,
     * and the node keeps its stored epoch — the path a placement between two non-root parents always takes. A
     * destination at the scope root without an epoch is `illegal_structure` here already (it is outside every
     * boundary); a node at the scope root is outside it too, which the store answers under the lock.
     *
     * Lock order: the scope row ([NodesStore.lockScope]), then the node row. A caller may run this inside its own
     * transaction (`applyUpdate` joins it), and then it **takes the scope lock first**, before this function's
     * unlocked fast path reads any node: a node read before the lock stays in the transaction's persistence context,
     * and the decisions under the lock would be handed that stale copy (the boundary walk included). After the scope
     * lock it may take rows of its own (a share row, `ShareNodesHandler`); it must never lock one of its own rows or a
     * node row BEFORE the scope lock — every tree writer takes the scope first, so that order would invert theirs.
     *
     * [editor] is the authenticated caller, recorded as the editor of whatever sealed field the write replaces
     * ([NodesStore.applyUpdate]).
     */
    internal fun updateInScope(
        scopeId: String, nodeId: String, placement: PlacementRequest, orderIndex: Int?,
        wrappedMeta: String?, metaRevision: Long?, editor: PrincipalKey, boundary: SubtreeBoundary? = null,
    ): UpdateNodeOutcome {
        malformed(placement, wrappedMeta, metaRevision, epochOptional = boundary != null)?.let { return it }
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
            // Nothing is placed INTO a guest's identity folder (ADR 0080 decision 11) — a member may only delete or move
            // out of a drop box, and a guest places nothing (a WRITE_ONLY one does no placement at all). A rename
            // inside one is a placement to its current parent and is refused too. Stable without the lock: nothing ever
            // enters an identity folder, so no concurrent write can turn a destination outside into one inside.
            if (store.identityFolderOwner(scopeId, slot) != null) return UpdateNodeOutcome.IllegalStructure
            if (slot == nodeId || store.wouldCycle(scopeId, nodeId, slot)) return UpdateNodeOutcome.IllegalStructure
            // ADR 0080 Decision 4: an entry at the scope root lives under K_SC, so it is sealed at the CURRENT epoch —
            // a rename at the root and a move into it alike (ADR 0024 Decision 6: an older epoch's key is one a member
            // removed since still holds). Unlocked, like create's: a placement racing the rotation that supersedes
            // its epoch was sealed while that epoch was current. Below the root the entry is under the parent's key,
            // which no rotation changes: a move out of the root stores the epoch the client sealed at, and a
            // placement between two non-root parents keeps the stored one (the client seals at it; the
            // compare-and-set guarantees the parent it read is still the node's).
            val epoch = placement.epoch
            val storedEpoch = if (epoch == null) {
                // A bounded placement without an epoch (see the KDoc): the node keeps its stored one. The scope root
                // as its destination is outside every boundary; refused here, it never reaches a write with no epoch.
                if (parent is ParentSlot.Root) return UpdateNodeOutcome.IllegalStructure
                null
            } else {
                val current = store.currentEpoch(scopeId)
                // For ANY destination, the rule off the root first ([sealableOffRoot]): an epoch above the current one
                // is refused, since a move out of the root stores the request's epoch. One BELOW the current one stays
                // accepted off the root; at the root the stricter rule below follows.
                if (!sealableOffRoot(epoch, current)) return UpdateNodeOutcome.StaleEpoch
                when {
                    parent is ParentSlot.Root -> {
                        if (epoch != current) return UpdateNodeOutcome.StaleEpoch
                        epoch
                    }
                    node.parentId == scopeId -> epoch
                    else -> null
                }
            }
            write = NodesStore.Placement(
                parentSlot = slot,
                encryptedName = placement.encryptedName!!,
                slugToken = placement.slugToken!!,
                wrappedContentKey = placement.wrappedContentKey!!,
                epoch = storedEpoch,
                expectedRevision = placement.placementRevision!!,
            )
        }
        return when (val r = store.applyUpdate(scopeId, nodeId, write, orderIndex, wrappedMeta, metaRevision, editor, boundary)) {
            NodesStore.ApplyResult.Applied -> UpdateNodeOutcome.Updated
            NodesStore.ApplyResult.Missing -> UpdateNodeOutcome.NotFound
            NodesStore.ApplyResult.IllegalMove -> UpdateNodeOutcome.IllegalStructure
            NodesStore.ApplyResult.SlugTaken -> UpdateNodeOutcome.SlugTaken
            NodesStore.ApplyResult.StalePlacement -> UpdateNodeOutcome.StalePlacement
            is NodesStore.ApplyResult.StaleMeta -> UpdateNodeOutcome.StaleMeta(r.wrappedMeta, r.metaRevision)
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
     * scope lock first, never a caller's own row before it — and a caller that runs this in its own transaction takes
     * it before the node lookup below, for the same persistence-context reason.
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
     * `slugToken` — the server cannot recompute a blinded MAC under the parent's key (`K_SC` only at the scope root,
     * ADR 0080 Decision 2), and the delete consumed the original when it freed the name.
     */
    fun restore(
        userId: String, scopeId: String, nodeId: String, slugToken: String, encryptedName: String?,
    ): RestoreNodeOutcome {
        access.check(userId, scopeId, WRITE)
            .refusal(RestoreNodeOutcome.NotFound, RestoreNodeOutcome.Suspended, RestoreNodeOutcome.ScopeDeleting)?.let { return it }
        // uc.moderation.take-a-measure: a blocked item can be deleted, never restored, while the block stands.
        if (blocks.contentView(scopeId).nodeBlocked(nodeId)) return RestoreNodeOutcome.Blocked
        return when (store.restoreSubtree(scopeId, nodeId, slugToken, encryptedName, PrincipalKey.User(userId))) {
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
