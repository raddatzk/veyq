package me.raddatz.veyq.core.sharing

import me.raddatz.veyq.core.blob.UploadSessionEntity
import me.raddatz.veyq.core.content.CreateNodeOutcome
import me.raddatz.veyq.core.content.NodeSubtreePurge
import me.raddatz.veyq.core.content.NodesHandler
import me.raddatz.veyq.core.content.NodesStore
import me.raddatz.veyq.core.content.SubtreeBoundary
import me.raddatz.veyq.core.content.UpdateNodeOutcome
import me.raddatz.veyq.core.http.FailureStatus.CONFLICT
import me.raddatz.veyq.core.http.FailureStatus.FORBIDDEN
import me.raddatz.veyq.core.http.FailureStatus.NOT_FOUND
import me.raddatz.veyq.core.http.FailureStatus.UNPROCESSABLE
import me.raddatz.veyq.core.http.Failures
import me.raddatz.veyq.core.http.HttpFailure
import me.raddatz.veyq.core.http.httpFailure
import me.raddatz.veyq.core.identity.PrincipalKey
import me.raddatz.veyq.core.http.ClientSeen
import me.raddatz.veyq.core.moderation.guestwrite.GuestWriteObject
import me.raddatz.veyq.core.moderation.guestwrite.GuestWriteRecords
import me.raddatz.veyq.core.node.NodeEntity
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
    /** The owner of the parent's kind lets no READ_WRITE guest create that kind in it ([GuestNodeRules]), or the owner
     *  of the root's kind lets no WRITE_ONLY guest create it ([WriteOnlyGuestKinds]) — a 422, the code a share's refused
     *  kind-and-role pair answers on create (`illegal_scope`). */
    object IllegalScope : CreateShareNodeOutcome, HttpFailure by httpFailure("illegal_scope", UNPROCESSABLE)
    object SlugTaken : CreateShareNodeOutcome, HttpFailure by Failures.slugTaken
    object IdTaken : CreateShareNodeOutcome, HttpFailure by Failures.idTaken
    /** The epoch the guest sealed with is above the scope's current one (or the scope has none) — a 409, nothing
     *  stored (ADR 0080 Decision 6, the rule a member's placement off the root follows). */
    object StaleEpoch : CreateShareNodeOutcome, HttpFailure by Failures.staleEpoch
    /** Creating the kind needs a capability that is switched off ([me.raddatz.veyq.core.node.NodeKindRules.capabilityToCreate],
     *  asked by `createInScope` for a guest as for a member). Unreachable today: no kind a guest may create names one.
     *  So `createShareNode`'s contract names no `422 capability_disabled` yet: it joins the contract, with a test that
     *  reaches it, in the first change that lets a guest create a kind whose rule names a capability. */
    object CapabilityDisabled : CreateShareNodeOutcome, HttpFailure by Failures.capabilityDisabled
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
    /** The node is the share's root, a guest's identity folder or inside one (only a member moves out of a drop box,
     *  ADR 0080 decision 11); the destination is missing, trashed, outside the subtree, the node or inside its subtree;
     *  or `NodeKindRules` refuses the kind there. */
    object IllegalStructure : UpdateShareNodeOutcome, HttpFailure by Failures.illegalStructure
    /** An owner of the containers on either side refuses the move or the rename ([GuestNodeRules]). */
    object IllegalScope : UpdateShareNodeOutcome, HttpFailure by httpFailure("illegal_scope", UNPROCESSABLE)
    object SlugTaken : UpdateShareNodeOutcome, HttpFailure by Failures.slugTaken
    object StalePlacement : UpdateShareNodeOutcome, HttpFailure by Failures.stalePlacement
    /** Unreachable through the API (every field is required there) — kept so no handler outcome is a 500. */
    object InvalidRequest : UpdateShareNodeOutcome, HttpFailure by Failures.invalidRequest
}

sealed interface AbandonShareNodeOutcome {
    object Abandoned : AbandonShareNodeOutcome
    /** The link's own 404 — as [CreateShareNodeOutcome.NotFound]. */
    object NotFound : AbandonShareNodeOutcome, HttpFailure by httpFailure("share_not_found", NOT_FOUND)
    /** No live node inside the guest's reach has that id — a 404, the same for a node outside it as for none. */
    object NodeNotFound : AbandonShareNodeOutcome, HttpFailure by Failures.nodeNotFound
    object NotContributable : AbandonShareNodeOutcome, HttpFailure by httpFailure("share_not_contributable", FORBIDDEN)
    object PasswordIncorrect : AbandonShareNodeOutcome, HttpFailure by httpFailure("share_password_incorrect", FORBIDDEN)
    object RateLimited : AbandonShareNodeOutcome, HttpFailure by Failures.rateLimited
    /** The share's root, a `WRITE_ONLY` guest's identity folder itself, or — for a `READ_WRITE` guest — an identity
     *  folder or a node inside one. */
    object IllegalStructure : AbandonShareNodeOutcome, HttpFailure by Failures.illegalStructure
    /** The node is no placeholder of this guest's: its name was last written by anybody but the calling identity, a
     *  live link is rooted on it, it holds something — a child, an upload somebody else has open into it, its
     *  application's content — or its kind's owner declares nothing ([GuestPlaceholders]): nothing is written. */
    object NotAPlaceholder : AbandonShareNodeOutcome, HttpFailure by httpFailure("not_a_placeholder", CONFLICT)
}

/**
 * A `READ_WRITE` guest's tree operations on a share link (ADR 0080 decisions 6, 9, 10): create a node inside the shared
 * subtree, and move or rename one (one placement write). Core's, because they carry no application semantics: behind
 * [ShareGuestGate] instead of a member session, then [NodesHandler]'s principal-free operations with the share's
 * [SubtreeBoundary]. The server checks structure only and opens nothing. **No guest delete of anything that holds
 * something** (whether a guest deletes content of its own is inbox #467's question). What a guest may take back is an
 * **empty upload placeholder its own identity named** ([abandon]): the node it created for an upload that was then
 * refused (a full link) or given up, erased at once so nothing of it is kept.
 *
 * Who may do what is the container's owner's ([GuestNodeRules], both sides of a move), on top of where a kind may live
 * at all (`NodeKindRules`, which the node operations ask). A `WRITE_ONLY` guest only creates, and only inside its own
 * identity folder in the root (ADR 0080 decision 11: the application's own operation made it, `IdentityFolders`), the
 * folder being its subtree here, and only the kinds its root's application declares ([WriteOnlyGuestKinds]: a drop
 * box's guests create `FILE`s only); it places nothing (refused by the gate, before its token and the budget). No guest
 * places an identity folder or anything inside one — a `READ_WRITE` guest of a link above the drop box reads them, and
 * only a member moves them out (decision 11). A create
 * spends the per-share upload budget, a placement does not.
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
    @All private val writeOnlyKinds: MutableList<WriteOnlyGuestKinds>,
    @All private val placeholders: MutableList<GuestPlaceholders>,
    private val purge: NodeSubtreePurge,
    /** The guest-write record (ADR 0093 § 10): a create and a placement enter the guest as the name's last editor. */
    private val guestWriteRecords: GuestWriteRecords,
) {

    /** [seen] is the client the resource resolved; a create that stored the node records it (ADR 0093 § 10), in this
     *  transaction — a replay ([NodesHandler.createReplay]) stored nothing and records nothing. */
    fun create(
        shareId: String, pwToken: String?, contributorToken: String, nodeId: String, parentId: String, kind: String,
        encryptedName: String, slugToken: String, orderIndex: Int, epoch: Int, wrappedContentKey: String, seen: ClientSeen?,
    ): CreateShareNodeOutcome {
        // A create adds a row, so it spends the upload budget (ADR 0051, `@RateLimit(key = SHARE)`) — after every check
        // of the gate, so an unauthorized caller spends nothing.
        // READ_WRITE anywhere in the subtree; WRITE_ONLY only inside its own identity folder (ADR 0080 decision 11).
        val gated = when (val g = gate.writeGate(shareId, pwToken, contributorToken, spendUploadBudget = true)) {
            is ShareWriteGate.Admitted -> g
            ShareWriteGate.NotFound -> return CreateShareNodeOutcome.NotFound
            ShareWriteGate.PasswordIncorrect -> return CreateShareNodeOutcome.PasswordIncorrect
            ShareWriteGate.RateLimited -> return CreateShareNodeOutcome.RateLimited
            ShareWriteGate.NotContributable -> return CreateShareNodeOutcome.NotContributable
        }
        val scopeId = gated.scopeId
        val writeOnly = gated.role == ShareEntity.WRITE_ONLY
        return createOrConflict(conflict = { nodes.createReplay(scopeId, parentId, slugToken, nodeId)()?.let(::fromNodes) }) {
            when (underLock(gated, shareId, gated.role)) {
                Refusal.NOT_FOUND -> return@createOrConflict CreateShareNodeOutcome.NotFound
                Refusal.NOT_CONTRIBUTABLE -> return@createOrConflict CreateShareNodeOutcome.NotContributable
                null -> {}
            }
            // A WRITE_ONLY guest holds no key of the root: its subtree is its own identity folder in the root, live
            // (ADR 0080 decision 11); none yet, or one a member trashed, takes nothing.
            val root = if (!writeOnly) gated.rootNodeId else
                tree.identityFolder(gated.rootNodeId, gated.identityId)?.takeIf { it.deletedAt == null }?.id
                    ?: return@createOrConflict CreateShareNodeOutcome.NotContributable
            // The boundary first, before anything that answers by what lies outside: a parent outside the subtree is
            // `illegal_structure` whatever its kind, so no guest learns the kind of a node it holds no key of.
            if (!tree.withinSubtree(scopeId, root, parentId)) return@createOrConflict CreateShareNodeOutcome.IllegalStructure
            // The kind only (a node never changes it); whether the parent is live is the create's own question.
            val parentKind = kindOf(scopeId, parentId) ?: return@createOrConflict CreateShareNodeOutcome.IllegalStructure
            if (!GuestNodeRules.readWriteAllows(rules, parentKind, GuestNodeOp.CreateChild(kind))) {
                return@createOrConflict CreateShareNodeOutcome.IllegalScope
            }
            // A WRITE_ONLY guest creates only the kinds its root's application lets it (Depot's drop box: `FILE`s only,
            // inbox #377 (c)) — kinds never change, so this needs no lock of its own.
            if (writeOnly && !WriteOnlyGuestKinds.allows(writeOnlyKinds, gated.rootKind, kind)) {
                return@createOrConflict CreateShareNodeOutcome.IllegalScope
            }
            // ADR 0080 decision 6: the epoch the guest sealed with, stored as sent — its name and wrap bind it, so the
            // reader opens at that number. A guest never writes at the scope root, so it keys nothing of the entry (that
            // is under the parent's key); `createInScope` checks it under this scope lock with the rule off the root (at
            // or below the current one, else `stale_epoch`), as a member's placement there.
            fromNodes(
                nodes.createInScope(
                    scopeId, nodeId, parentId, kind, encryptedName, slugToken, orderIndex, epoch, wrappedContentKey,
                    wrappedMeta = null, editor = PrincipalKey.Share(gated.identityId), boundary = SubtreeBoundary(root),
                    identityWriter = if (writeOnly) gated.identityId else null,
                ),
            ).also {
                if (it is CreateShareNodeOutcome.Created) {
                    guestWriteRecords.record(seen, gated.identityId, scopeId, GuestWriteObject.NODE, nodeId, GuestWriteRecords.NAME)
                }
            }
        }
    }

    /** [seen] is the client the resource resolved; a placement that was written records it (ADR 0093 § 10: a rename or a
     *  move re-seals the name, so the guest is its last editor), in this transaction. */
    fun update(
        shareId: String, pwToken: String?, contributorToken: String, nodeId: String, parentId: String,
        encryptedName: String, slugToken: String, wrappedContentKey: String, placementRevision: Long, seen: ClientSeen?,
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
            when (underLock(gated, shareId, ShareEntity.READ_WRITE)) {
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
            // Only a member moves anything out of a drop box (ADR 0080 decision 11, owner decision 2026-10-08, inbox #377
            // (a)): a guest places neither a guest's identity folder nor anything inside one — known by core's own column
            // (`share_identity_id`, [NodesStore.identityFolderOwner]), never by an application's kind. Decided here, before
            // the owners' rules, so it holds whatever an application declares for the container above. Stable under the
            // scope lock: nothing ever moves INTO an identity folder, and a member's move out commits before or after.
            if (tree.identityFolderOwner(scopeId, nodeId) != null) return@call UpdateShareNodeOutcome.IllegalStructure
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
                wrappedContentKey = wrappedContentKey, epoch = null, placementRevision = placementRevision,
            )
            when (nodes.updateInScope(scopeId, nodeId, placement, null, null, null, PrincipalKey.Share(gated.identityId), SubtreeBoundary(root))) {
                UpdateNodeOutcome.Updated -> UpdateShareNodeOutcome.Updated.also {
                    guestWriteRecords.record(seen, gated.identityId, scopeId, GuestWriteObject.NODE, nodeId, GuestWriteRecords.NAME)
                }
                UpdateNodeOutcome.NotFound -> UpdateShareNodeOutcome.NodeNotFound
                UpdateNodeOutcome.IllegalStructure -> UpdateShareNodeOutcome.IllegalStructure
                UpdateNodeOutcome.SlugTaken -> UpdateShareNodeOutcome.SlugTaken
                UpdateNodeOutcome.StalePlacement -> UpdateShareNodeOutcome.StalePlacement
                UpdateNodeOutcome.IncompletePlacement, UpdateNodeOutcome.MissingMetaRevision -> UpdateShareNodeOutcome.InvalidRequest
                // No epoch and no meta is sent, and the gate refused a space that serves no guests.
                UpdateNodeOutcome.StaleEpoch, is UpdateNodeOutcome.StaleMeta, UpdateNodeOutcome.Suspended, UpdateNodeOutcome.ScopeDeleting ->
                    error("a guest placement states no epoch and no meta, and passed the gate")
            }
        }
    }

    /**
     * Erase an empty upload placeholder (ADR 0080 decision 6, `abandonShareNode`): the node a guest created for an upload
     * that was refused or given up. Within the guest's reach — strictly inside the subtree for `READ_WRITE` (never an
     * identity folder or anything inside one), strictly inside its own identity folder for `WRITE_ONLY` — and only the
     * calling identity's own: its name was last written by that identity ([NodeEntity.nameEditor], ADR 0093 § 8), so
     * a member's empty node, or one a member renamed, is none. And only a node that holds nothing anybody could lose:
     * no live link rooted on it (the erase would end that link — a guest's own placeholder never carries one), no
     * child (live or trashed: a trashed one is restorable), no upload session a member or another identity opened for
     * it (so no guest cuts another's upload off), and its kind's owner declares it empty ([GuestPlaceholders]). The
     * erase is the trash purge's ([NodeSubtreePurge.erase]), at once, with the bump of the tree revision. Spends no
     * budget: it removes a row. A session this identity opened for the node stays and ages out (`UploadSessionPurge`),
     * as every given-up upload's does.
     *
     * A move counts as naming (ADR 0080 decision 6, owner 2026-10-16): a placement re-seals the name, so a guest that
     * moved a member's empty node is its name's last editor and may take it back — a name with nothing behind it,
     * which that guest could already rename away; the link check keeps it from ending anybody's link.
     *
     * The lock order of the class: everything below is decided under the scope lock, so a completion into the node
     * either committed first — and the owner's check sees its content — or runs after and finds no node.
     */
    fun abandon(shareId: String, pwToken: String?, contributorToken: String, nodeId: String): AbandonShareNodeOutcome {
        val gated = when (val g = gate.writeGate(shareId, pwToken, contributorToken, spendUploadBudget = false)) {
            is ShareWriteGate.Admitted -> g
            ShareWriteGate.NotFound -> return AbandonShareNodeOutcome.NotFound
            ShareWriteGate.PasswordIncorrect -> return AbandonShareNodeOutcome.PasswordIncorrect
            ShareWriteGate.RateLimited -> return AbandonShareNodeOutcome.RateLimited
            ShareWriteGate.NotContributable -> return AbandonShareNodeOutcome.NotContributable
        }
        val scopeId = gated.scopeId
        val writeOnly = gated.role == ShareEntity.WRITE_ONLY
        return QuarkusTransaction.requiringNew().call {
            when (underLock(gated, shareId, gated.role)) {
                Refusal.NOT_FOUND -> return@call AbandonShareNodeOutcome.NotFound
                Refusal.NOT_CONTRIBUTABLE -> return@call AbandonShareNodeOutcome.NotContributable
                null -> {}
            }
            // The reach, as create's: a WRITE_ONLY guest's is its own live identity folder in the root.
            val root = if (!writeOnly) gated.rootNodeId else
                tree.identityFolder(gated.rootNodeId, gated.identityId)?.takeIf { it.deletedAt == null }?.id
                    ?: return@call AbandonShareNodeOutcome.NotContributable
            // The boundary first (see update): the reach's own root is never erased, a node outside is not found.
            if (nodeId == root) return@call AbandonShareNodeOutcome.IllegalStructure
            val node = tree.findInScope(scopeId, nodeId)
            if (node == null || !tree.withinSubtree(scopeId, root, nodeId)) return@call AbandonShareNodeOutcome.NodeNotFound
            // A READ_WRITE guest of a link above a drop box touches no guest's identity folder nor anything in one (see
            // update); a WRITE_ONLY guest's reach IS inside its own.
            if (!writeOnly && tree.identityFolderOwner(scopeId, nodeId) != null) return@call AbandonShareNodeOutcome.IllegalStructure
            if (!isPlaceholder(scopeId, node, gated.identityId)) return@call AbandonShareNodeOutcome.NotAPlaceholder
            purge.erase(scopeId, listOf(node))
            AbandonShareNodeOutcome.Abandoned
        }
    }

    /** [node] is [identityId]'s own and holds nothing anybody could lose (see [abandon]); under the scope lock. A
     *  session row names the node it was opened for as its client declared it (`ownerId`), so one naming this node
     *  blocks the erase whoever's it really is — it can only keep a placeholder, never erase anything that is not one. */
    private fun isPlaceholder(scopeId: String, node: NodeEntity, identityId: String): Boolean {
        if (node.nameEditor != PrincipalKey.Share(identityId)) return false
        // A link is rooted on it (ADR 0080 decision 6, D3): the erase tombstones every link on the node, so a revoked one
        // counts too — only a deleted one is no link any more.
        if (ShareEntity.count("nodeId = ?1 and deleted = false", node.id) > 0) return false
        if (NodeEntity.count("parentId", node.id) > 0) return false
        val openByAnother = UploadSessionEntity.count(
            "scopeId = ?1 and ownerId = ?2 and (openerShareIdentityId is null or openerShareIdentityId <> ?3)",
            scopeId, node.id, identityId,
        )
        if (openByAnother > 0) return false
        return GuestPlaceholders.isEmpty(placeholders, node.kind, scopeId, node.id)
    }

    private enum class Refusal { NOT_FOUND, NOT_CONTRIBUTABLE }

    /**
     * Steps (1)–(3a) of the lock order (class KDoc), in the caller's transaction: the scope lock, then the share row's
     * — the link still live, enabled and `READ_WRITE` as its lock holds it (a revoke, a delete or a role change
     * committed meanwhile is seen) — then the root live: the gate lets a trashed root through (it names its kind), and
     * a guest writes nothing under a root in the trash.
     */
    private fun underLock(gated: ShareWriteGate.Admitted, shareId: String, role: String): Refusal? {
        tree.lockScope(gated.scopeId)
        if (!gated.admitted()) return Refusal.NOT_FOUND
        if (shares.find(shareId)?.role != role) return Refusal.NOT_CONTRIBUTABLE
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
        CreateNodeOutcome.CapabilityDisabled -> CreateShareNodeOutcome.CapabilityDisabled
        // The gate refused a space that serves no guests, and `createInScope` answers none of the member gate's refusals.
        CreateNodeOutcome.NoScope, CreateNodeOutcome.Suspended, CreateNodeOutcome.ScopeDeleting ->
            error("a guest create past the gate cannot answer $o")
    }
}
