package me.raddatz.veyq.core.sharing

import me.raddatz.veyq.core.content.CreateNodeOutcome
import me.raddatz.veyq.core.content.NodesHandler
import me.raddatz.veyq.core.content.NodesStore
import me.raddatz.veyq.core.content.SubtreeBoundary
import me.raddatz.veyq.core.content.UpdateNodeOutcome
import me.raddatz.veyq.core.http.FailureStatus.FORBIDDEN
import me.raddatz.veyq.core.http.FailureStatus.NOT_FOUND
import me.raddatz.veyq.core.http.FailureStatus.UNPROCESSABLE
import me.raddatz.veyq.core.http.Failures
import me.raddatz.veyq.core.http.HttpFailure
import me.raddatz.veyq.core.http.httpFailure
import me.raddatz.veyq.core.persistence.createOrConflict
import io.quarkus.arc.All
import io.quarkus.narayana.jta.QuarkusTransaction
import jakarta.enterprise.context.ApplicationScoped

sealed interface CreateShareNodeOutcome {
    data class Created(val nodeId: String) : CreateShareNodeOutcome
    /** Unknown, disabled, expired or deleted share (also when a delete or a revoke commits before the write), a space
     *  that serves no guests, or a password-protected share with no token — the non-enumerable 404. */
    object NotFound : CreateShareNodeOutcome, HttpFailure by httpFailure("share_not_found", NOT_FOUND)
    /** Not a READ_WRITE link, a token naming no identity of it, or a root that is trashed or gone — a 403. */
    object NotContributable : CreateShareNodeOutcome, HttpFailure by httpFailure("share_not_contributable", FORBIDDEN)
    object PasswordIncorrect : CreateShareNodeOutcome, HttpFailure by httpFailure("share_password_incorrect", FORBIDDEN)
    /** Too many wrong password attempts, or the upload budget spent — a 429. */
    object RateLimited : CreateShareNodeOutcome, HttpFailure by Failures.rateLimited
    /** The parent is missing, trashed or outside the share's subtree, or `NodeKindRules` refuses the kind there. */
    object IllegalStructure : CreateShareNodeOutcome, HttpFailure by Failures.illegalStructure
    /** The owner of the parent's kind lets no READ_WRITE guest create that kind in it ([GuestNodeRules]) — a 422, the
     *  code a share's refused kind-and-role pair answers on create (`illegal_scope`). */
    object IllegalScope : CreateShareNodeOutcome, HttpFailure by httpFailure("illegal_scope", UNPROCESSABLE)
    object SlugTaken : CreateShareNodeOutcome, HttpFailure by Failures.slugTaken
    object IdTaken : CreateShareNodeOutcome, HttpFailure by Failures.idTaken
    /** The epoch the guest sealed with is above the scope's current one (or the scope has none) — a 409, nothing
     *  stored (ADR 0080 Decision 6, the rule a member's placement off the root follows). */
    object StaleEpoch : CreateShareNodeOutcome, HttpFailure by Failures.staleEpoch
}

sealed interface UpdateShareNodeOutcome {
    object Updated : UpdateShareNodeOutcome
    /** The link's own 404 — as [CreateShareNodeOutcome.NotFound]. */
    object NotFound : UpdateShareNodeOutcome, HttpFailure by httpFailure("share_not_found", NOT_FOUND)
    /** No live node inside the share's subtree has that id — a 404, the same for a node outside it as for none. */
    object NodeNotFound : UpdateShareNodeOutcome, HttpFailure by Failures.nodeNotFound
    object NotContributable : UpdateShareNodeOutcome, HttpFailure by httpFailure("share_not_contributable", FORBIDDEN)
    object PasswordIncorrect : UpdateShareNodeOutcome, HttpFailure by httpFailure("share_password_incorrect", FORBIDDEN)
    object RateLimited : UpdateShareNodeOutcome, HttpFailure by Failures.rateLimited
    /** The node is the share's root; the destination is missing, trashed, outside the subtree, the node or inside its
     *  subtree; or `NodeKindRules` refuses the kind there. */
    object IllegalStructure : UpdateShareNodeOutcome, HttpFailure by Failures.illegalStructure
    /** An owner of the containers on either side refuses the move or the rename ([GuestNodeRules]). */
    object IllegalScope : UpdateShareNodeOutcome, HttpFailure by httpFailure("illegal_scope", UNPROCESSABLE)
    object SlugTaken : UpdateShareNodeOutcome, HttpFailure by Failures.slugTaken
    object StalePlacement : UpdateShareNodeOutcome, HttpFailure by Failures.stalePlacement
    /** Unreachable through the API (every field is required there) — kept so no handler outcome is a 500. */
    object InvalidRequest : UpdateShareNodeOutcome, HttpFailure by Failures.invalidRequest
}

/**
 * A `READ_WRITE` guest's tree operations on a share link (ADR 0080 decisions 6, 9, 10): create a node inside the shared
 * subtree, and move or rename one (one placement write). Core's, because they carry no application semantics: behind
 * [ShareGuestGate] instead of a member session, then [NodesHandler]'s principal-free operations with the share's
 * [SubtreeBoundary]. The server checks structure only and opens nothing. **No guest delete:** nodes store no
 * authorship, so "delete your own" cannot be checked, and a contributor could remove anything below the root.
 *
 * Who may do what is the container's owner's ([GuestNodeRules], both sides of a move), on top of where a kind may live
 * at all (`NodeKindRules`, which the node operations ask). A `WRITE_ONLY` guest does none of them (refused by the gate,
 * before its token and the budget). A create spends the per-share upload budget, a placement does not.
 *
 * **Lock order, in ONE transaction:** (1) the scope row ([NodesStore.lockScope]) — the lock every tree writer takes
 * first; (2) the share row ([ShareWriteGate.Admitted.admitted], [SharingStore.admitsGuests]) — the lock a share's
 * revoke, delete and re-activation write under (a re-activation and a member removal take the scope first too); then
 * (3) every structural decision — the root live, the boundary, the owners' rules, the parent, the slug — and (4) the
 * write. Never the share row before the scope: a re-activation holds the scope and waits for the share row, so that
 * order deadlocks against it (`ShareNodeRaceCoreSliceTest`). Everything after (1) is decided under it: a member's move
 * of an ancestor out of the subtree, a trash of the parent, a revoke of the link has either committed — and is seen
 * (READ COMMITTED, ADR 0078) — or waits until this write is done.
 */
@ApplicationScoped
class ShareNodesHandler(
    private val gate: ShareGuestGate,
    private val nodes: NodesHandler,
    private val tree: NodesStore,
    private val shares: SharingStore,
    /** `MutableList`, not `List`: ArC refuses `@All kotlin.List` (see `ScopePurge`'s constructor). */
    @All private val rules: MutableList<GuestNodeRules>,
) {

    fun create(
        shareId: String, pwToken: String?, contributorToken: String, nodeId: String, parentId: String, kind: String,
        encryptedName: String, slugToken: String, orderIndex: Int, epoch: Int, wrappedContentKey: String,
    ): CreateShareNodeOutcome {
        // A create adds a row, so it spends the upload budget (ADR 0051, `@RateLimit(key = SHARE)`) — after every check
        // of the gate, so an unauthorized caller spends nothing.
        val gated = when (val g = gate.writeGate(shareId, pwToken, contributorToken, spendUploadBudget = true, onlyRole = ShareEntity.READ_WRITE)) {
            is ShareWriteGate.Admitted -> g
            ShareWriteGate.NotFound -> return CreateShareNodeOutcome.NotFound
            ShareWriteGate.PasswordIncorrect -> return CreateShareNodeOutcome.PasswordIncorrect
            ShareWriteGate.RateLimited -> return CreateShareNodeOutcome.RateLimited
            ShareWriteGate.NotContributable -> return CreateShareNodeOutcome.NotContributable
        }
        val scopeId = gated.scopeId
        val root = gated.rootNodeId
        return createOrConflict(conflict = { nodes.createReplay(scopeId, parentId, slugToken, nodeId)()?.let(::fromNodes) }) {
            when (underLock(gated, shareId)) {
                Refusal.NOT_FOUND -> return@createOrConflict CreateShareNodeOutcome.NotFound
                Refusal.NOT_CONTRIBUTABLE -> return@createOrConflict CreateShareNodeOutcome.NotContributable
                null -> {}
            }
            // The boundary first, before anything that answers by what lies outside: a parent outside the subtree is
            // `illegal_structure` whatever its kind, so no guest learns the kind of a node it holds no key of.
            if (!tree.withinSubtree(scopeId, root, parentId)) return@createOrConflict CreateShareNodeOutcome.IllegalStructure
            // The kind only (a node never changes it); whether the parent is live is the create's own question.
            val parentKind = kindOf(scopeId, parentId) ?: return@createOrConflict CreateShareNodeOutcome.IllegalStructure
            if (!GuestNodeRules.readWriteAllows(rules, parentKind, GuestNodeOp.CreateChild(kind))) {
                return@createOrConflict CreateShareNodeOutcome.IllegalScope
            }
            // ADR 0080 decision 6: the epoch the guest sealed with, stored as sent — its name and wrap bind it, so the
            // reader opens at that number. A guest never writes at the scope root, so it keys nothing of the entry (that
            // is under the parent's key); `createInScope` checks it under this scope lock with the rule off the root (at
            // or below the current one, else `stale_epoch`), as a member's placement there.
            fromNodes(
                nodes.createInScope(
                    scopeId, nodeId, parentId, kind, encryptedName, slugToken, orderIndex, epoch, wrappedContentKey,
                    wrappedMeta = null, boundary = SubtreeBoundary(root),
                ),
            )
        }
    }

    fun update(
        shareId: String, pwToken: String?, contributorToken: String, nodeId: String, parentId: String,
        encryptedName: String, slugToken: String, wrappedContentKey: String, placementVersion: Long,
    ): UpdateShareNodeOutcome {
        // A placement adds no row, so it spends no budget (`@RateLimitExempt` on the route).
        val gated = when (val g = gate.writeGate(shareId, pwToken, contributorToken, spendUploadBudget = false, onlyRole = ShareEntity.READ_WRITE)) {
            is ShareWriteGate.Admitted -> g
            ShareWriteGate.NotFound -> return UpdateShareNodeOutcome.NotFound
            ShareWriteGate.PasswordIncorrect -> return UpdateShareNodeOutcome.PasswordIncorrect
            ShareWriteGate.RateLimited -> return UpdateShareNodeOutcome.RateLimited
            ShareWriteGate.NotContributable -> return UpdateShareNodeOutcome.NotContributable
        }
        val scopeId = gated.scopeId
        val root = gated.rootNodeId
        return QuarkusTransaction.requiringNew().call {
            when (underLock(gated, shareId)) {
                Refusal.NOT_FOUND -> return@call UpdateShareNodeOutcome.NotFound
                Refusal.NOT_CONTRIBUTABLE -> return@call UpdateShareNodeOutcome.NotContributable
                null -> {}
            }
            // The boundary first (see create): the root is never placed by a guest, a node outside is not found, a
            // destination outside is illegal — each before an owner's rule could answer by a kind outside.
            if (nodeId == root) return@call UpdateShareNodeOutcome.IllegalStructure
            val node = tree.findInScope(scopeId, nodeId)
            if (node == null || !tree.withinSubtree(scopeId, root, nodeId)) return@call UpdateShareNodeOutcome.NodeNotFound
            if (!tree.withinSubtree(scopeId, root, parentId)) return@call UpdateShareNodeOutcome.IllegalStructure
            // Both containers' owners decide (ADR 0080 decisions 6 and 10): a rename asks the parent's, a move the
            // source's to let the child out and the destination's to let it in. The node lies strictly inside, so its
            // parent is a node of the subtree.
            val sourceKind = kindOf(scopeId, node.parentId) ?: return@call UpdateShareNodeOutcome.IllegalStructure
            val destinationKind = kindOf(scopeId, parentId) ?: return@call UpdateShareNodeOutcome.IllegalStructure
            val allowed = if (node.parentId == parentId) {
                GuestNodeRules.readWriteAllows(rules, sourceKind, GuestNodeOp.RenameChild(node.kind))
            } else {
                GuestNodeRules.readWriteAllows(rules, sourceKind, GuestNodeOp.MoveChildOut(node.kind)) &&
                    GuestNodeRules.readWriteAllows(rules, destinationKind, GuestNodeOp.MoveChildIn(node.kind))
            }
            if (!allowed) return@call UpdateShareNodeOutcome.IllegalScope
            // No epoch: the node keeps its stored one (a bounded placement never touches the scope root).
            val placement = NodesHandler.PlacementRequest(
                parentId = parentId, encryptedName = encryptedName, slugToken = slugToken,
                wrappedContentKey = wrappedContentKey, epoch = null, placementVersion = placementVersion,
            )
            when (nodes.updateInScope(scopeId, nodeId, placement, null, null, null, SubtreeBoundary(root))) {
                UpdateNodeOutcome.Updated -> UpdateShareNodeOutcome.Updated
                UpdateNodeOutcome.NotFound -> UpdateShareNodeOutcome.NodeNotFound
                UpdateNodeOutcome.IllegalStructure -> UpdateShareNodeOutcome.IllegalStructure
                UpdateNodeOutcome.SlugTaken -> UpdateShareNodeOutcome.SlugTaken
                UpdateNodeOutcome.StalePlacement -> UpdateShareNodeOutcome.StalePlacement
                UpdateNodeOutcome.IncompletePlacement, UpdateNodeOutcome.MissingMetaVersion -> UpdateShareNodeOutcome.InvalidRequest
                // No epoch and no meta is sent, and the gate refused a space that serves no guests.
                UpdateNodeOutcome.StaleEpoch, is UpdateNodeOutcome.StaleMeta, UpdateNodeOutcome.Suspended, UpdateNodeOutcome.ScopeDeleting ->
                    error("a guest placement states no epoch and no meta, and passed the gate")
            }
        }
    }

    private enum class Refusal { NOT_FOUND, NOT_CONTRIBUTABLE }

    /**
     * Steps (1)–(3a) of the lock order (class KDoc), in the caller's transaction: the scope lock, then the share row's
     * — the link still live, enabled and `READ_WRITE` as its lock holds it (a revoke, a delete or a role change
     * committed meanwhile is seen) — then the root live: the gate lets a trashed root through (it names its kind), and
     * a guest writes nothing under a root in the trash.
     */
    private fun underLock(gated: ShareWriteGate.Admitted, shareId: String): Refusal? {
        tree.lockScope(gated.scopeId)
        if (!gated.admitted()) return Refusal.NOT_FOUND
        if (shares.find(shareId)?.role != ShareEntity.READ_WRITE) return Refusal.NOT_CONTRIBUTABLE
        if (tree.findInScope(gated.scopeId, gated.rootNodeId) == null) return Refusal.NOT_CONTRIBUTABLE
        return null
    }

    /** A node's kind, trashed or not — a kind never changes, and whether the node is live is the node operation's
     *  own question, decided under the same lock; null for no such row in [scopeId]. */
    private fun kindOf(scopeId: String, nodeId: String): String? = tree.findDeletableInScope(scopeId, nodeId)?.kind

    private fun fromNodes(o: CreateNodeOutcome): CreateShareNodeOutcome = when (o) {
        is CreateNodeOutcome.Created -> CreateShareNodeOutcome.Created(o.nodeId)
        CreateNodeOutcome.IllegalStructure -> CreateShareNodeOutcome.IllegalStructure
        CreateNodeOutcome.SlugTaken -> CreateShareNodeOutcome.SlugTaken
        CreateNodeOutcome.IdTaken -> CreateShareNodeOutcome.IdTaken
        CreateNodeOutcome.StaleEpoch -> CreateShareNodeOutcome.StaleEpoch
        // The gate refused a space that serves no guests, and `createInScope` answers none of the member gate's refusals.
        CreateNodeOutcome.NoScope, CreateNodeOutcome.Suspended, CreateNodeOutcome.ScopeDeleting ->
            error("a guest create past the gate cannot answer $o")
    }
}
