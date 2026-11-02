package me.raddatz.veyq.core.sharing

import me.raddatz.veyq.core.identity.PrincipalKey
import me.raddatz.veyq.core.quota.StorageQuota
import me.raddatz.veyq.core.quota.UploadAdmission
import me.raddatz.veyq.core.blob.UploadDeclare
import me.raddatz.veyq.core.blob.UploadItemResult
import me.raddatz.veyq.core.blob.WindowRequest
import me.raddatz.veyq.core.blob.WindowItemResult
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.node.rekey.NodeEpochGate
import me.raddatz.veyq.core.sharing.ShareEntity
import me.raddatz.veyq.core.sharing.ShareGuestGate.PwCheck
import me.raddatz.veyq.core.sharing.ShareGuestWrites
import me.raddatz.veyq.core.admin.tenant.TenantTermsHandler
import me.raddatz.veyq.core.platform.capabilities.ApplicationSwitch
import me.raddatz.veyq.core.platform.capabilities.CapabilityRegistry
import me.raddatz.veyq.core.spaces.scope.ScopesStore
import me.raddatz.veyq.core.blobstore.BlobStore
import me.raddatz.veyq.core.blobstore.PresignedRef
import me.raddatz.veyq.core.blobstore.presignGets
import me.raddatz.veyq.core.http.FailureStatus.CONFLICT
import me.raddatz.veyq.core.http.FailureStatus.FORBIDDEN
import me.raddatz.veyq.core.http.FailureStatus.NOT_FOUND
import me.raddatz.veyq.core.http.FailureStatus.SERVICE_UNAVAILABLE
import me.raddatz.veyq.core.http.FailureStatus.UNPROCESSABLE
import me.raddatz.veyq.core.http.Failures
import me.raddatz.veyq.core.http.HttpFailure
import me.raddatz.veyq.core.http.httpFailure
import me.raddatz.veyq.core.membership.Access
import me.raddatz.veyq.core.membership.AccessMode.READ
import me.raddatz.veyq.core.membership.AccessMode.WRITE
import me.raddatz.veyq.core.membership.ScopeAccess
import me.raddatz.veyq.core.membership.refusal
import me.raddatz.veyq.core.moderation.block.ModerationBlocks
import me.raddatz.veyq.core.persistence.createOrConflict
import me.raddatz.veyq.core.http.ClientSeen
import me.raddatz.veyq.core.moderation.guestwrite.GuestWriteObject
import me.raddatz.veyq.core.moderation.guestwrite.GuestWriteRecords
import me.raddatz.veyq.core.scope.ScopeEntity
import io.quarkus.arc.All
import io.quarkus.narayana.jta.QuarkusTransaction
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import jakarta.transaction.Transactional
import java.time.Instant

sealed interface CreateShareOutcome {
    data class Created(val shareId: String) : CreateShareOutcome
    object Suspended : CreateShareOutcome, HttpFailure by Failures.tenantSuspended
    object Deleting : CreateShareOutcome, HttpFailure by Failures.scopeDeleting
    /** The sharing capability this role needs is disabled instance-wide (ADR 0032) — a 422, instance policy. */
    object CapabilityDisabled : CreateShareOutcome, HttpFailure by Failures.capabilityDisabled
    /** The link misses a requirement in effect for its tenant (ADR 0088 decision 8, [LinkRequirements]) — a 422. */
    object LinkPasswordRequired : CreateShareOutcome, HttpFailure by Failures.linkPasswordRequired
    object LinkExpiryRequired : CreateShareOutcome, HttpFailure by Failures.linkExpiryRequired
    /** No such node, or the caller holds no grant for its scope (a 404, no reveal). */
    object NoNode : CreateShareOutcome, HttpFailure by Failures.nodeNotFound
    /** The node's kind may not be the root of a share of this role ([ShareRoots], ADR 0080 decision 6) — a 422. */
    object IllegalScope : CreateShareOutcome, HttpFailure by httpFailure("illegal_scope", UNPROCESSABLE)
    object IdTaken : CreateShareOutcome, HttpFailure by Failures.idTaken
    /** The keys do not match the role: a wrap on a WRITE_ONLY share, a drop key on any other — a 400. */
    object InvalidRequest : CreateShareOutcome, HttpFailure by Failures.invalidRequest
    /** The recovery copy's epoch is not the scope's current one (ADR 0024 Decision 6), or `nodeEpoch` is not the shared
     *  node's stored one (ADR 0109 point 6) — a 409, nothing written. */
    object StaleEpoch : CreateShareOutcome, HttpFailure by Failures.staleEpoch
    /** The shared node is due for its re-key (ADR 0114 decision 3) — a 409, nothing written. */
    object RekeyPending : CreateShareOutcome, HttpFailure by Failures.nodeRekeyPending
    /** A WRITE_ONLY share brought a drop private key, but the node already has one — a 409, nothing written. */
    object DropKeyExists : CreateShareOutcome, HttpFailure by httpFailure("drop_key_exists", CONFLICT)
    /** A WRITE_ONLY share brought no drop private key, and the node has none — a 409, nothing written. */
    object DropKeyMissing : CreateShareOutcome, HttpFailure by httpFailure("drop_key_missing", CONFLICT)
}

sealed interface ResolveShareOutcome {
    /** [nodes]: the live shared subtree (none for a WRITE_ONLY link). [rootKind]: the shared root node's kind as
     *  stored (core interprets none), null while the root is in the trash. */
    data class Found(
        val share: ShareEntity,
        val nodes: List<ShareNodeRow>,
        val rootKind: String?,
        /** The shared root's own key's epoch (ADR 0114 decision 3) — what a guest's write states as the root's;
         *  served for every unlocked role, null only when the root row is gone. */
        val rootEpoch: Int?,
        /** The root's `scopeEpoch` — what a guest mints a new node's key under (ADR 0114 decision 3); null with [rootEpoch]. */
        val rootScopeEpoch: Int?,
        /** What hangs off the served nodes — `node|slot|blob` per attachment: no part of the body, but a guest reads it
         *  beside the nodes (a file's blob, a cover), so the conditional resolve's tag covers it (ADR 0081). */
        val attachments: List<String> = emptyList(),
    ) : ResolveShareOutcome
    /** Unknown, disabled, or expired — a non-enumerable 404 (identical in every case). */
    object NotFound : ResolveShareOutcome, HttpFailure by httpFailure("share_not_found", NOT_FOUND)
    /** Password-protected + no token given (cr-0118) — a LOCKED shell (only `passwordRequired` + `pwSalt`). */
    data class Locked(val share: ShareEntity) : ResolveShareOutcome
    /** Password-protected + a wrong token (cr-0118) — a 403. */
    object PasswordIncorrect : ResolveShareOutcome, HttpFailure by httpFailure("share_password_incorrect", FORBIDDEN)
    /** Too many wrong password attempts on this share (cr-0165) — a 429. */
    object RateLimited : ResolveShareOutcome, HttpFailure by Failures.rateLimited
}

sealed interface CreateIdentityOutcome {
    data class Created(val identityId: String, val contributorToken: String) : CreateIdentityOutcome
    /** Unknown/disabled/expired share, or password-protected with no token given (cr-0164) — a 404. */
    object NotFound : CreateIdentityOutcome, HttpFailure by httpFailure("share_not_found", NOT_FOUND)
    object IdTaken : CreateIdentityOutcome, HttpFailure by Failures.idTaken
    /** `nodeEpoch` is not the shared root's stored epoch (ADR 0109 point 6) — a 409, nothing stored; the guest re-resolves. */
    object StaleEpoch : CreateIdentityOutcome, HttpFailure by Failures.staleEpoch
    /** The shared root is due for its re-key (ADR 0114 decision 3) — a 409, nothing stored. */
    object RekeyPending : CreateIdentityOutcome, HttpFailure by Failures.nodeRekeyPending
    /** A wrapped identity key that does not match the role: missing on WRITE_ONLY, present on READ_WRITE — a 400. */
    object InvalidRequest : CreateIdentityOutcome, HttpFailure by Failures.invalidRequest
    /** The share does not accept contributions (not READ_WRITE/WRITE_ONLY) — a 403. */
    object NotContributable : CreateIdentityOutcome, HttpFailure by httpFailure("share_not_contributable", FORBIDDEN)
    /** Password-protected + a wrong token (cr-0164) — a 403. */
    object PasswordIncorrect : CreateIdentityOutcome, HttpFailure by httpFailure("share_password_incorrect", FORBIDDEN)
    /** Too many wrong password attempts on this share (cr-0165) — a 429. */
    object RateLimited : CreateIdentityOutcome, HttpFailure by Failures.rateLimited
}

sealed interface ShareUploadOutcome {
    data class Results(val items: List<UploadItemResult>) : ShareUploadOutcome
    object NotFound : ShareUploadOutcome, HttpFailure by httpFailure("share_not_found", NOT_FOUND)
    /** Not an uploading share, the contributor token is invalid, no application takes guest uploads into the target's
     *  kind, or the target is refused by the role or a trashed root ([ShareWriteTarget.NotContributable]) — a 403. */
    object NotContributable : ShareUploadOutcome, HttpFailure by httpFailure("share_not_contributable", FORBIDDEN)
    /** The named target is no live node inside the share's subtree ([ShareWriteTarget.NotFound]) — a 404. */
    object NodeNotFound : ShareUploadOutcome, HttpFailure by Failures.nodeNotFound
    /** A declared role the target's application takes from no guest ([ShareGuestWrites.admitsGuestRole]) — a 400. */
    object InvalidRequest : ShareUploadOutcome, HttpFailure by Failures.invalidRequest
    /** Password-protected + a wrong token (cr-0164) — a 403. */
    object PasswordIncorrect : ShareUploadOutcome, HttpFailure by httpFailure("share_password_incorrect", FORBIDDEN)
    /** Per-share rate limit exceeded — a 429. */
    object RateLimited : ShareUploadOutcome, HttpFailure by Failures.rateLimited
}

sealed interface ShareWindowOutcome {
    data class Results(val items: List<WindowItemResult>) : ShareWindowOutcome
    object NotFound : ShareWindowOutcome, HttpFailure by httpFailure("share_not_found", NOT_FOUND)
    /** As [ShareUploadOutcome.NotContributable] — a 403. */
    object NotContributable : ShareWindowOutcome, HttpFailure by httpFailure("share_not_contributable", FORBIDDEN)
    /** As [ShareUploadOutcome.NodeNotFound] — a 404. */
    object NodeNotFound : ShareWindowOutcome, HttpFailure by Failures.nodeNotFound
    /** Password-protected + a wrong token (cr-0164) — a 403. */
    object PasswordIncorrect : ShareWindowOutcome, HttpFailure by httpFailure("share_password_incorrect", FORBIDDEN)
    /** Too many wrong password tokens on this share (cr-0165) — a 429. */
    object RateLimited : ShareWindowOutcome, HttpFailure by Failures.rateLimited
}

sealed interface ShareBlobOutcome {
    data class Found(val gets: List<PresignedRef>) : ShareBlobOutcome
    /** Unknown/disabled/expired share, the blob is not under the shared subtree, or a required password token is
     *  absent — a non-enumerable 404. */
    object NotFound : ShareBlobOutcome, HttpFailure by httpFailure("share_not_found", NOT_FOUND)
    /** Password-protected + a wrong token (cr-0118) — a 403. */
    object PasswordIncorrect : ShareBlobOutcome, HttpFailure by httpFailure("share_password_incorrect", FORBIDDEN)
    /** Too many wrong password attempts on this share (cr-0165) — a 429. */
    object RateLimited : ShareBlobOutcome, HttpFailure by Failures.rateLimited
    /** The store cannot presign (the in-memory dev store) — a 503. */
    object StoreUnavailable : ShareBlobOutcome, HttpFailure by httpFailure("store_unavailable", SERVICE_UNAVAILABLE)
    /** A moderator blocked the content this blob belongs to (uc.moderation.take-a-measure) — a 451, as for a member. */
    object Blocked : ShareBlobOutcome, HttpFailure by Failures.blockedByOperator
}

/** No `Deleting`: a revoke passes a space in its deletion grace ([SharingHandler.revoke]). */
sealed interface RevokeShareOutcome {
    object Done : RevokeShareOutcome
    object Suspended : RevokeShareOutcome, HttpFailure by Failures.tenantSuspended
    object NotFound : RevokeShareOutcome, HttpFailure by httpFailure("share_not_found", NOT_FOUND)
}

sealed interface DeleteShareOutcome {
    object Done : DeleteShareOutcome
    object Suspended : DeleteShareOutcome, HttpFailure by Failures.tenantSuspended
    object Deleting : DeleteShareOutcome, HttpFailure by Failures.scopeDeleting
    /** No such share, deleted already, the caller is no member, or another member's who is still here — one 404. */
    object NotFound : DeleteShareOutcome, HttpFailure by httpFailure("share_not_found", NOT_FOUND)
}

sealed interface UpdateShareOutcome {
    object Done : UpdateShareOutcome
    object Suspended : UpdateShareOutcome, HttpFailure by Failures.tenantSuspended
    object Deleting : UpdateShareOutcome, HttpFailure by Failures.scopeDeleting
    /** No such share owned by the caller — a non-enumerable 404 (owner-gated, like revoke). */
    object NotFound : UpdateShareOutcome, HttpFailure by httpFailure("share_not_found", NOT_FOUND)
    /** An illegal role change (cr-0119) — `role = WRITE_ONLY`, a change on a WRITE_ONLY share, or a role the shared
     *  node's kind may not be the root of ([ShareRoots]) → 422. */
    object InvalidRole : UpdateShareOutcome, HttpFailure by httpFailure("invalid_role_change", UNPROCESSABLE)
    /** A change into a role whose instance capability is not active (ADR 0088) — the 422 `create` answers. */
    object CapabilityDisabled : UpdateShareOutcome, HttpFailure by Failures.capabilityDisabled
    /** The link misses a requirement in effect for its tenant (ADR 0088 decision 8, [LinkRequirements]) — a 422. */
    object LinkPasswordRequired : UpdateShareOutcome, HttpFailure by Failures.linkPasswordRequired
    object LinkExpiryRequired : UpdateShareOutcome, HttpFailure by Failures.linkExpiryRequired
    /** The edit reopens a link whose end date has passed and lacks the root's key or name for its role (ADR 0114
     *  decision 7) — a 422, nothing written. */
    object LinkKeyRequired : UpdateShareOutcome, HttpFailure by Failures.linkKeyRequired
    /** A key the link's role does not take, or a reopen's key on an edit that reopens nothing — a 400. */
    object InvalidRequest : UpdateShareOutcome, HttpFailure by Failures.invalidRequest
    /** A reopen's `nodeEpoch` is not the root's own stored epoch (ADR 0114 decision 3) — a 409, nothing written. */
    object StaleEpoch : UpdateShareOutcome, HttpFailure by Failures.staleEpoch
    /** A reopen of a root due for its re-key — a 409, nothing written. */
    object RekeyPending : UpdateShareOutcome, HttpFailure by Failures.nodeRekeyPending
}

sealed interface SetShareSealedNameOutcome {
    object Done : SetShareSealedNameOutcome
    object Suspended : SetShareSealedNameOutcome, HttpFailure by Failures.tenantSuspended
    object Deleting : SetShareSealedNameOutcome, HttpFailure by Failures.scopeDeleting
    /** No such share, it was deleted, or the caller may not write in its space — one non-enumerable 404. */
    object NotFound : SetShareSealedNameOutcome, HttpFailure by httpFailure("share_not_found", NOT_FOUND)
    /** The link is closed — switched off or past its end date: nothing new is sealed under its `K_share` (ADR 0114
     *  decision 5); its reopen carries the current name. */
    object Closed : SetShareSealedNameOutcome, HttpFailure by httpFailure("link_closed", UNPROCESSABLE)
}

sealed interface ReactivateShareOutcome {
    object Done : ReactivateShareOutcome
    object Suspended : ReactivateShareOutcome, HttpFailure by Failures.tenantSuspended
    object Deleting : ReactivateShareOutcome, HttpFailure by Failures.scopeDeleting
    /** The capability this share's role needs is off (ADR 0032) — re-activating publishes, so it checks what create checks. */
    object CapabilityDisabled : ReactivateShareOutcome, HttpFailure by Failures.capabilityDisabled
    /** No such share, or the caller is not a member of its scope — a non-enumerable 404. */
    object NotFound : ReactivateShareOutcome, HttpFailure by httpFailure("share_not_found", NOT_FOUND)
    /** A departed member may hold this link ([ReissuePolicy], ADR 0075) — a 409, nothing written; re-issue it. */
    object ReissueRequired : ReactivateShareOutcome, HttpFailure by httpFailure("reissue_required", CONFLICT)
    /** A moderator blocked the link or a guest uploader who came through it (uc.moderation.take-a-measure) — a 409. */
    object Blocked : ReactivateShareOutcome, HttpFailure by Failures.shareBlockedByOperator
    /** The reopen lacks the root's key or name for the link's role (ADR 0114 decision 7) — a 422, nothing written. */
    object LinkKeyRequired : ReactivateShareOutcome, HttpFailure by Failures.linkKeyRequired
    /** A key the link's role does not take — a 400. */
    object InvalidRequest : ReactivateShareOutcome, HttpFailure by Failures.invalidRequest
    /** `nodeEpoch` is not the root's own stored epoch (ADR 0114 decision 3) — a 409, nothing written. */
    object StaleEpoch : ReactivateShareOutcome, HttpFailure by Failures.staleEpoch
    /** The root is due for its re-key — a 409, nothing written. */
    object RekeyPending : ReactivateShareOutcome, HttpFailure by Failures.nodeRekeyPending
}

sealed interface ReissueShareOutcome {
    data class Created(val shareId: String) : ReissueShareOutcome
    object Suspended : ReissueShareOutcome, HttpFailure by Failures.tenantSuspended
    object Deleting : ReissueShareOutcome, HttpFailure by Failures.scopeDeleting
    /** No such share, the caller is not a member of its scope, or it is enabled and not theirs — one 404. */
    object NotFound : ReissueShareOutcome, HttpFailure by httpFailure("share_not_found", NOT_FOUND)
    /** The capability the old share's role needs is off (ADR 0032) — a re-issue publishes a link. */
    object CapabilityDisabled : ReissueShareOutcome, HttpFailure by Failures.capabilityDisabled
    /** The link misses a requirement in effect for its tenant (ADR 0088 decision 8, [LinkRequirements]) — a 422. */
    object LinkPasswordRequired : ReissueShareOutcome, HttpFailure by Failures.linkPasswordRequired
    object LinkExpiryRequired : ReissueShareOutcome, HttpFailure by Failures.linkExpiryRequired
    /** The keys do not match the old share's role — a 400, as on create. */
    object InvalidRequest : ReissueShareOutcome, HttpFailure by Failures.invalidRequest
    /** The recovery copy is not sealed at the scope's current epoch (ADR 0024 Decision 6) — a 409, nothing written. */
    object StaleEpoch : ReissueShareOutcome, HttpFailure by Failures.staleEpoch
    /** The shared node is due for its re-key (ADR 0114 decision 3) — a 409, nothing written. */
    object RekeyPending : ReissueShareOutcome, HttpFailure by Failures.nodeRekeyPending
    object IdTaken : ReissueShareOutcome, HttpFailure by Failures.idTaken
    /** The share is already deleted — re-issued once already (a concurrent re-issue won, or an earlier one ran) or
     *  deleted. A member's answer only (inbox #252): a 409, nothing written; the caller re-reads the list. */
    object ShareDeleted : ReissueShareOutcome, HttpFailure by httpFailure("share_deleted", CONFLICT)
    /** A moderator blocked the link itself — a new id would serve what was blocked (decision 2 of
     *  cr-moderation-review-and-measures); a 409, nothing written. */
    object Blocked : ReissueShareOutcome, HttpFailure by Failures.shareBlockedByOperator
}

/** A share as the manage list serves it: the row plus [ReissuePolicy]'s answer for it (ADR 0075 point 4), whether
 *  the caller may delete it — the gate's own answer (ADR 0029 §7) — and whether naming a new guest on it is refused
 *  for want of a write verifier (ADR 0076 point 5), and why a guest is not served it. */
data class ListedShare(
    val share: ShareEntity, val reissueRequired: Boolean, val deletable: Boolean, val newGuestsRefused: Boolean,
    /** Why a guest is not served it now — the guest gate's own answer ([ShareGuestGate.guestStops], inbox #457). */
    val guestStops: List<GuestStop>,
)

/** The shares on one node (cr-0104) — `[]` for a non-member or an unknown node, a refusal only for a member. */
sealed interface NodeSharesOutcome {
    data class Shares(val rows: List<ListedShare>) : NodeSharesOutcome
    object Suspended : NodeSharesOutcome, HttpFailure by Failures.tenantSuspended
}

sealed interface ShareTermsOutcome {
    /** The owning tenant's public terms — `termsRevision` 0 (with null `de`/`en`) when the space owner published none. */
    data class Found(
        val tenantId: String, val termsRevision: Int, val de: String?, val en: String?,
        /** The operator section after the space's text (ADR 0035 decision 6). */
        val section: me.raddatz.veyq.core.terms.RenderedSection,
    ) : ShareTermsOutcome
    /** Unknown/disabled/expired share — the same non-enumerable 404 as [ResolveShareOutcome.NotFound]. */
    object NotFound : ShareTermsOutcome, HttpFailure by httpFailure("share_not_found", NOT_FOUND)
}

/**
 * Use-case logic for public viewer shares (cr-0100, ADR 0029). Two authorization surfaces:
 *  - **Curator** (create/list/revoke/reactivate/update): authenticated, and gated first by [ScopeAccess] on the
 *    share's (or node's) space — a **node of a kind that may be the root of a share of that role ([ShareRoots]), in a
 *    scope the caller is a member of** (personal, group, or tenant — cr-0104), writable only outside its deletion grace and a suspension (uc.spaces.scope-access).
 *  - **Guest** (resolve/blob): unauthenticated, keyed only on share state (`enabled` + `expiresAt` + a live space) — a
 *    parallel authz path, never a relaxed scope filter. The blob GET is authorized structurally (the blob is
 *    attached under the shared subtree), and bytes go **direct-from-store** so the operator never learns which
 *    blob a viewer opens. A disabled/expired/unknown share is a non-enumerable 404 in every case.
 */
@ApplicationScoped
class SharingHandler(
    private val store: SharingStore,
    private val blobStore: BlobStore,
    /** Who takes a guest's uploads, by the target node's kind (see [ShareGuestWrites]); core only gates and dispatches. */
    private val guestWrites: Instance<ShareGuestWrites>,
    /** Which roles a node of a kind may be shared with — its owner's answer (see [ShareRoots]); none → no share.
     *  `MutableList`, not `List`: ArC refuses `@All kotlin.List` (see `ScopePurge`'s constructor). */
    @param:All private val roots: MutableList<ShareRoots>,
    /** The guest gate every guest call passes first (active link, password, mode, token, budget). */
    private val gate: ShareGuestGate,
    private val rateLimiter: ShareRateLimiter,
    private val capabilities: CapabilityRegistry,
    private val applications: ApplicationSwitch,
    private val scopes: ScopesStore,
    private val tenantTerms: TenantTermsHandler,
    private val access: ScopeAccess,
    /** Which application measures a link's usage, by the target node's kind (see [UploadAdmission]). */
    private val quota: StorageQuota,
    private val blocks: ModerationBlocks,
    /** The guest-write record (ADR 0093 § 10): a guest naming itself is one of the writes it keeps. */
    private val guestWriteRecords: GuestWriteRecords,
    /** ADR 0109 point 6: a share wraps its node's key, a guest's name is sealed under its root's — each states that epoch. */
    private val epochs: NodeEpochGate,
) {

    /**
     * The epoch gate's answer as [T]'s refusal, or null when [stated] is [nodeId]'s stored epoch and the node is re-keyed
     * to the scope's current one (ADR 0109 point 6). Takes the node's row lock in the caller's transaction — after the
     * scope and share rows the path holds, before anything it writes.
     */
    private fun <T> epochRefusal(scopeId: String, nodeId: String, stated: Int, missing: T, stale: T, pending: T): T? =
        when (epochs.check(scopeId, nodeId, stated)) {
            NodeEpochGate.Verdict.OK -> null
            NodeEpochGate.Verdict.MISSING -> missing
            NodeEpochGate.Verdict.STALE -> stale
            NodeEpochGate.Verdict.PENDING -> pending
        }

    fun create(
        userId: String, shareId: String, nodeId: String, role: String, wrap: String?,
        sealedDropPublicKey: String?, sealedDropPrivateKey: String?, sealedName: String, expiresAt: Instant?,
        sealedNote: String?, sealedShareKey: String, sealedShareKeyEpoch: Int, sealedShareName: String?,
        passwordRequired: Boolean, pwSalt: String?, pwVerifier: String?, sealedPassword: String?, writeVerifier: String,
        nodeEpoch: Int,
    ): CreateShareOutcome {
        // Instance policy first (ADR 0032): the capability this role needs must be active, else 422 — checked
        // before any node/scope lookup so it reads as "the operator turned sharing off", not a scope error.
        if (!capabilities.isActive(ShareAccessPolicy.capabilityFor(role), null)) return CreateShareOutcome.CapabilityDisabled
        val keys = shareKeys(role, wrap, sealedDropPublicKey, sealedDropPrivateKey) ?: return CreateShareOutcome.InvalidRequest
        // A node in the trash is shared by nobody: a link on it would serve nothing, and a restore is not a new share.
        val node = store.findNode(nodeId)?.takeIf { it.deletedAt == null } ?: return CreateShareOutcome.NoNode
        access.check(userId, node.scopeId, WRITE)
            .refusal(CreateShareOutcome.NoNode, CreateShareOutcome.Suspended, CreateShareOutcome.Deleting)?.let { return it }
        // The tenant's level (ADR 0088 decision 2): the node's tenant may have narrowed it, else 422. After the access
        // check, because only the node names the tenant: a caller who is no member of its scope gets the 404 it gets
        // anyway, and learns nothing of another tenant's policy.
        if (!capabilities.isActiveInScope(ShareAccessPolicy.capabilityFor(role), node.scopeId)) {
            return CreateShareOutcome.CapabilityDisabled
        }
        // No new link onto a node of a switched-off application (uc.platform.switch-application-off); the links that
        // exist keep serving it, read-only.
        if (!applications.isKindWritable(node.kind, node.scopeId)) return CreateShareOutcome.CapabilityDisabled
        // The tenant's link requirements (ADR 0088 decision 8): a new link is made now.
        linkRefusal(node.scopeId, passwordRequired && pwVerifier != null, Instant.now(), expiresAt)?.let {
            return if (it === Failures.linkPasswordRequired) CreateShareOutcome.LinkPasswordRequired else CreateShareOutcome.LinkExpiryRequired
        }
        // Any member of the node's scope may share (cr-0104) — personal, group, or tenant; only a node whose kind's owner
        // declares this role for a share root ([ShareRoots], ADR 0080 decision 6). Whether a guest may then upload
        // under it is a separate question, [writesFor]'s.
        if (!ShareRoots.allows(roots, node.kind, role)) return CreateShareOutcome.IllegalScope
        // ADR 0024 Decision 6, as NodesHandler checks it: a recovery copy sealed under an epoch other than the scope's
        // current one is refused — an older one is an epoch a member removed since still holds. The copy is mandatory
        // (ADR 0080 decision 12): the request bound refused its absence before the handler.
        if (sealedShareKeyEpoch != scopes.currentEpoch(node.scopeId)) return CreateShareOutcome.StaleEpoch
        // The client-minted share id (ADR 0072): the recovery wrap and every contributor name are bound to it. A
        // double-submitted create's loser answers as its replay does.
        val replay = { if (store.idTaken(shareId)) SharingStore.CreateResult.ID_TAKEN else null }
        var refused: CreateShareOutcome? = null
        val stored = createOrConflict(conflict = replay) {
            // The two checks above, again under the scope row's lock, taken first: every removal holds it while it
            // turns the removed member's links off (developer-styleguide §12). Checked only before it, a member being
            // removed created a link that removal never saw, and kept it live in a group they had left.
            scopes.lockScope(node.scopeId)
            refused = access.check(userId, node.scopeId, WRITE)
                .refusal(CreateShareOutcome.NoNode, CreateShareOutcome.Suspended, CreateShareOutcome.Deleting)
                ?: CreateShareOutcome.StaleEpoch.takeIf { sealedShareKeyEpoch != scopes.currentEpoch(node.scopeId) }
                // The share wraps the node's key, and a WRITE_ONLY link's drop private key is sealed under it: the node's
                // epoch as the caller read it, under its row lock — after the scope's, before the drop key's update.
                ?: epochRefusal(
                    node.scopeId, nodeId, nodeEpoch, CreateShareOutcome.NoNode, CreateShareOutcome.StaleEpoch,
                    CreateShareOutcome.RekeyPending,
                )
            if (refused != null) {
                null
            } else {
                store.create(
                    shareId, userId, node.scopeId, nodeId, role, keys, sealedName, expiresAt, sealedNote, sealedShareKey,
                    sealedShareKeyEpoch, sealedShareName,
                    passwordRequired, pwSalt, pwVerifier, sealedPassword, writeVerifier,
                )
            }
        }
        refused?.let { return it }
        // A double-submitted node's FIRST WRITE_ONLY link loses at the guarded drop-key update, not at the commit: both
        // carry the private key, and the loser's update waits for the winner's and finds the key set. Its replay meets
        // the id check first and answers `id_taken` — `drop_key_exists` would send the client to re-seal and retry the
        // id the winner holds. So that refusal re-asks the replay question, in a fresh transaction once the loser's
        // has ended, as `createOrConflict` does for a failed commit.
        // Two DIFFERENT links racing for the first drop key keep `drop_key_exists`: their ids are free.
        val created = if (stored == SharingStore.CreateResult.DROP_KEY_EXISTS) {
            QuarkusTransaction.requiringNew().call(replay) ?: stored
        } else {
            stored
        }
        return when (created) {
            null -> CreateShareOutcome.NoNode // unreachable: a null store result is a refusal, answered above
            SharingStore.CreateResult.CREATED -> CreateShareOutcome.Created(shareId)
            SharingStore.CreateResult.ID_TAKEN -> CreateShareOutcome.IdTaken
            SharingStore.CreateResult.DROP_KEY_EXISTS -> CreateShareOutcome.DropKeyExists
            SharingStore.CreateResult.DROP_KEY_MISSING -> CreateShareOutcome.DropKeyMissing
        }
    }

    /**
     * The keys a share of [role] carries, or null when the request's do not match it (ADR 0031 point 7). A
     * WRITE_ONLY share never carries `symWrap(K_share, content key)` — the one party it exists to keep out would hold
     * the shared node's read key — and every other role never carries a drop key. Sent-but-empty counts as sent: refused.
     */
    private fun shareKeys(role: String, wrap: String?, dropPublic: String?, dropPrivate: String?): ShareKeys? =
        if (role == ShareEntity.WRITE_ONLY) {
            if (wrap != null || dropPublic.isNullOrEmpty() || dropPrivate?.isEmpty() == true) null
            else ShareKeys.Drop(dropPublic, dropPrivate)
        } else {
            if (wrap.isNullOrEmpty() || dropPublic != null || dropPrivate != null) null else ShareKeys.MemoryWrap(wrap)
        }

    /**
     * The caller's own shares — "my shares". Account-addressed, so it filters instead of refusing: a share in a space
     * the caller may no longer read (they left it, or its tenant is suspended) drops out, because every row carries
     * that space's sealed names (uc.spaces.scope-access).
     */
    fun list(userId: String): List<ListedShare> =
        listed(userId, store.listByOwner(userId).filter { access.check(userId, it.scopeId, READ) == Access.Ok })

    /** Every public share on a node, for the shared-scope manage panel (cr-0104): visible to any **member**
     *  of the node's scope, not only the shares' owners — so a member can see + adopt an orphaned (disabled) share.
     *  Empty when the node is unknown or the caller is not a member (no reveal); a member of a suspended tenant is
     *  told so. The resource tags each row `mine`. */
    fun listForNode(userId: String, nodeId: String): NodeSharesOutcome {
        val node = store.findNode(nodeId) ?: return NodeSharesOutcome.Shares(emptyList())
        return when (access.check(userId, node.scopeId, READ)) {
            Access.Ok -> NodeSharesOutcome.Shares(listed(userId, store.listByNode(nodeId)))
            Access.Suspended -> NodeSharesOutcome.Suspended
            else -> NodeSharesOutcome.Shares(emptyList())
        }
    }

    /**
     * Revoke a share — only its creator, and only while they are still a member of its space (share-shared-scope A3).
     * The one link write a space in its deletion grace allows (uc.spaces.scope-access A1): switching a link off only
     * ever closes exposure, and it matters once the deletion is stopped — the link then stays off. So `Deleting` is
     * waived, and only that: the gate's WRITE answer is `Deleting` only past its membership, suspension and
     * tenant-membership steps, so a stranger, a member of a suspended tenant and a former tenant member stop as on
     * every write. Every other link write — create, edit, re-activate, re-issue, delete — stays refused there.
     */
    fun revoke(userId: String, shareId: String): RevokeShareOutcome {
        val share = store.find(shareId) ?: return RevokeShareOutcome.NotFound
        val state = access.check(userId, share.scopeId, WRITE)
        if (state != Access.Deleting) {
            state.refusal(RevokeShareOutcome.NotFound, RevokeShareOutcome.Suspended)?.let { return it }
        }
        return if (store.revoke(userId, shareId)) RevokeShareOutcome.Done else RevokeShareOutcome.NotFound
    }

    /**
     * Delete a share (ADR 0029 §7): a tombstone — its ciphertext goes, its id and its guests' uploads stay. Its owner
     * may at any time, an enabled share turned off in the same transaction; another member only once the owner is
     * no longer a member of the scope ([deletable]) — deleting a co-member's parked link would destroy their note
     * and password copy, which re-activation never did. Everything else is the non-enumerable 404.
     */
    fun delete(userId: String, shareId: String): DeleteShareOutcome {
        val share = store.find(shareId) ?: return DeleteShareOutcome.NotFound
        access.check(userId, share.scopeId, WRITE)
            .refusal(DeleteShareOutcome.NotFound, DeleteShareOutcome.Suspended, DeleteShareOutcome.Deleting)?.let { return it }
        if (!deletable(userId, share)) return DeleteShareOutcome.NotFound
        return if (store.delete(shareId)) DeleteShareOutcome.Done else DeleteShareOutcome.NotFound
    }

    /** Whether [userId] may delete [share] — the ONE source of the delete gate and the list's `deletable`. */
    private fun deletable(userId: String, share: ShareEntity): Boolean = share.ownerAccount == userId || !ownerIsMember(share)

    /** "Still a member" as the gate answers it for a writer — the scope AND the tenant, so a member removed from the
     *  tenant, whose rows on other spaces linger through the grace window, counts as gone. Only membership counts:
     *  a space in its deletion grace refuses every writer alike, which is the gate's answer, not the owner's. */
    private fun ownerIsMember(share: ShareEntity): Boolean =
        access.check(share.ownerAccount, share.scopeId, WRITE) != Access.NotMember

    /** Edit a share in place (cr-0115/cr-0116/cr-0119, ADR 0036) — owner-gated. A share not owned by the caller is a
     *  non-enumerable 404. The `K_share`-sealed ciphertexts (re-sealed client-side) full-replace the note + name;
     *  a non-null `role` changes the grant in place (READ ↔ READ_WRITE only — WRITE_ONLY, either as the target or
     *  as the share's current role, is `InvalidRole` → 422, and so is a role the shared node's kind may not be the
     *  root of, [ShareRoots] as on create). Role change is a plaintext authz change: no re-crypto, the
     *  wrap/`K_share`/link are untouched.
     *
     *  **An end date is a close, and moving it back a reopen** (ADR 0114 decisions 6 and 7): an edit of an enabled link
     *  whose end date has passed that moves the date into the future or clears it carries [reopen] — the root's key and
     *  name for the link's role, stating the root's own epoch — else `LinkKeyRequired`; the epoch is judged under the
     *  root's row lock, after the scope's and the share's (`StaleEpoch`, `RekeyPending`). An edit that reopens nothing
     *  carries none of it (`InvalidRequest`). An end date set in the past closes an open link at once
     *  ([SharingStore.updateShare]). Whether it reopens is decided under the share row's lock, so a sweep or a second
     *  device in between is seen. */
    fun updateShare(
        userId: String, shareId: String, sealedNote: String?, sealedShareName: String?, role: String?,
        expiresAt: Instant?, passwordRequired: Boolean?, pwSalt: String?, pwVerifier: String?, sealedPassword: String?,
        reopen: ReopenRequest = ReopenRequest.NONE,
    ): UpdateShareOutcome {
        val share = store.find(shareId) ?: return UpdateShareOutcome.NotFound
        access.check(userId, share.scopeId, WRITE)
            .refusal(UpdateShareOutcome.NotFound, UpdateShareOutcome.Suspended, UpdateShareOutcome.Deleting)?.let { return it }
        // The root's kind, read outside the store's row lock: a share never changes its node and a node never its kind.
        // A root row that is gone answers no kind, and so allows no role (fail closed).
        if (role != null && !ShareRoots.allows(roots, store.findNode(share.nodeId)?.kind.orEmpty(), role)) {
            return UpdateShareOutcome.InvalidRole
        }
        // A change INTO a role publishes a link of that role, so it asks what `create` asks (ADR 0088): a READ link
        // turned READ_WRITE while `.contribute` is off would mint a contribute link past the switch, and since the
        // guest gate refuses that role (ADR 0088 decision 5) it would serve no guest at all. Only an actual change:
        // a request naming the current role, or narrowing to READ while READ is on, passes.
        if (role != null && role != share.role && !capabilities.isActiveInScope(ShareAccessPolicy.capabilityFor(role), share.scopeId)) {
            return UpdateShareOutcome.CapabilityDisabled
        }
        // Nor into a write role on a switched-off application's node (uc.platform.switch-application-off): READ_WRITE is the
        // one role an edit can change into (WRITE_ONLY is not editable in place).
        if (role == ShareEntity.READ_WRITE && role != share.role &&
            !applications.isKindWritable(store.findNode(share.nodeId)?.kind.orEmpty(), share.scopeId)
        ) {
            return UpdateShareOutcome.CapabilityDisabled
        }
        // The link requirements (ADR 0088 decision 8) on the link as the edit leaves it: the password tri-state (null
        // keeps the stored one) and the end date, a full replace, measured from the link's own start.
        val keepsPassword = when (passwordRequired) {
            null -> share.pwVerifier != null
            true -> pwVerifier != null
            false -> false
        }
        linkRefusal(share.scopeId, keepsPassword, share.createdAt, expiresAt)?.let {
            return if (it === Failures.linkPasswordRequired) UpdateShareOutcome.LinkPasswordRequired else UpdateShareOutcome.LinkExpiryRequired
        }
        return QuarkusTransaction.requiringNew().call {
            // Lock order: the scope row (a close marks the nodes below the link), the share row, then the root's.
            scopes.lockScope(share.scopeId)
            val locked = store.liveForUpdate(shareId)?.takeIf { it.ownerAccount == userId } ?: return@call UpdateShareOutcome.NotFound
            val now = Instant.now()
            val reopening = if (store.reopensByDate(locked, expiresAt, now)) {
                val keys = when (val k = reopenKeys(locked.role, reopen, userId)) {
                    ReopenKeys.Invalid -> return@call UpdateShareOutcome.InvalidRequest
                    ReopenKeys.Missing -> return@call UpdateShareOutcome.LinkKeyRequired
                    is ReopenKeys.Given -> k.reopen
                }
                epochRefusal(
                    locked.scopeId, locked.nodeId, checkNotNull(reopen.nodeEpoch), UpdateShareOutcome.NotFound,
                    UpdateShareOutcome.StaleEpoch, UpdateShareOutcome.RekeyPending,
                )?.let { return@call it }
                keys
            } else {
                if (reopen.any) return@call UpdateShareOutcome.InvalidRequest
                null
            }
            when (
                store.updateShare(
                    userId, shareId, sealedNote, sealedShareName, role, expiresAt, passwordRequired, pwSalt, pwVerifier,
                    sealedPassword, reopening, now,
                )
            ) {
                SharingStore.UpdateResult.DONE -> UpdateShareOutcome.Done
                SharingStore.UpdateResult.NOT_FOUND -> UpdateShareOutcome.NotFound
                SharingStore.UpdateResult.INVALID_ROLE -> UpdateShareOutcome.InvalidRole // refused before any write
            }
        }
    }

    /** What a reopen of a closed link carries (ADR 0114 decision 7): the root's own epoch as read, its current key under
     *  the link's `K_share` — [wrap] (READ / READ_WRITE) or [sealedDropPublicKey] (WRITE_ONLY) — and its name under it. */
    data class ReopenRequest(val nodeEpoch: Int?, val wrap: String?, val sealedDropPublicKey: String?, val sealedName: String?) {
        /** Whether the request carries any of it. */
        val any: Boolean get() = nodeEpoch != null || wrap != null || sealedDropPublicKey != null || sealedName != null

        companion object {
            val NONE = ReopenRequest(null, null, null, null)
        }
    }

    /** [reopenKeys]' answer. */
    private sealed interface ReopenKeys {
        /** A key the role does not take, or a key without its `nodeEpoch` — a 400. */
        object Invalid : ReopenKeys
        /** The role's key or the name is missing (or empty) — `422 link_key_required`. */
        object Missing : ReopenKeys
        data class Given(val reopen: SharingStore.Reopen) : ReopenKeys
    }

    /** The reopen [r] carries for a link of [role], sealed by [editor]. The role is the share's own, so the request's
     *  schema cannot say which key it needs: a key of the other role is malformed, a missing one is the 422. */
    private fun reopenKeys(role: String, r: ReopenRequest, editor: String): ReopenKeys {
        val viewable = role != ShareEntity.WRITE_ONLY
        if ((viewable && r.sealedDropPublicKey != null) || (!viewable && r.wrap != null)) return ReopenKeys.Invalid
        val key = if (viewable) r.wrap else r.sealedDropPublicKey
        if (key.isNullOrEmpty() || r.sealedName.isNullOrEmpty()) return ReopenKeys.Missing
        if (r.nodeEpoch == null) return ReopenKeys.Invalid
        val keys = if (viewable) ShareKeys.MemoryWrap(key) else ShareKeys.Drop(key, sealedPrivateKey = null)
        return ReopenKeys.Given(SharingStore.Reopen(keys, r.sealedName, editor))
    }

    /**
     * Store a share root's name re-sealed under the share's `K_share` (ADR 0080 decision 14): the member who renamed
     * the root re-seals the name of every share on it through each one's recovery copy. **Any** member who may write
     * in the share's space may — the gate's WRITE answer, as a node rename asks it (`NodesHandler.update`) — not only
     * the share's owner: `updateShare` stays owner-gated (ADR 0036 point 3) and replaces all its fields, so it cannot
     * carry this write. A re-seal lands under open links only (ADR 0114 decision 5) — nothing new under a closed one's
     * `K_share`, whose reopen carries the current name: a switched-off link or one past its end date is
     * [SetShareSealedNameOutcome.Closed], decided under the share row's lock; the renaming client skips closed links
     * anyway. A deleted one is the 404. Last writer wins: the value is always the root's current name. The server never
     * opens it.
     *
     * The gate is read before the store's transaction, as `updateShare` reads it. Sound by the serial-order rule
     * (developer-styleguide §12): the write changes an existing row's one column, which no removal enumerates or
     * reads, so a removal racing it is explained by "the name was set, then the member was removed".
     */
    fun setSealedName(userId: String, shareId: String, sealedName: String): SetShareSealedNameOutcome {
        val share = store.find(shareId) ?: return SetShareSealedNameOutcome.NotFound
        access.check(userId, share.scopeId, WRITE)
            .refusal(SetShareSealedNameOutcome.NotFound, SetShareSealedNameOutcome.Suspended, SetShareSealedNameOutcome.Deleting)
            ?.let { return it }
        return when (store.setSealedName(shareId, sealedName, PrincipalKey.User(userId))) {
            SharingStore.SealedNameResult.DONE -> SetShareSealedNameOutcome.Done
            SharingStore.SealedNameResult.NOT_FOUND -> SetShareSealedNameOutcome.NotFound
            SharingStore.SealedNameResult.CLOSED -> SetShareSealedNameOutcome.Closed
        }
    }

    /** Re-activate a soft-revoked share and adopt it (cr-0104): any current member of the share's scope may flip
     *  it back on, which transfers ownership to them. A non-member / unknown share is a non-enumerable 404.
     *  Re-activating publishes the link again, so it passes what creating does (share-shared-scope A2): the gate
     *  (no adoption during the space's deletion grace) and the instance capability for the share's role. A link a
     *  departed member may hold is refused `reissue_required` (ADR 0075) — re-activation revives the same link.
     *
     *  Every check runs under the scope row's lock, taken first. Every removal holds that lock while it deletes the
     *  membership, counts the removal and turns the removed member's links off (`ScopesStore.rotate`,
     *  `TenantHandler.removeMember`), so a removal that committed before this is seen — the caller is no member, or
     *  the link is marked for re-issue — and one that comes after finds this link on and turns it off with the rest.
     *  Checked before the lock, a member being removed re-activated a link and kept it live in a group it had left.
     *  The scope id comes from a scalar read, so the share's first load is the locked one, never a stale copy.
     *
     *  **The reopen carries the key** (ADR 0114 decision 7): the close cleared the link's wrap (or drop public key) and
     *  its re-key sealed nothing new under it, so a switched-off link comes back only with [reopen] — the root's current
     *  key and name under its `K_share`, else `LinkKeyRequired` — and its `nodeEpoch` is judged under the root's row
     *  lock, after the scope's and the share's (`StaleEpoch`, `RekeyPending`). An enabled link is adopted as it is and
     *  keeps its keys. A key the role does not take is `InvalidRequest` either way. */
    @Transactional
    fun reactivate(userId: String, shareId: String, reopen: ReopenRequest): ReactivateShareOutcome {
        val scopeId = store.liveScopeOf(shareId) ?: return ReactivateShareOutcome.NotFound
        scopes.lockScope(scopeId)
        val share = store.liveForUpdate(shareId) ?: return ReactivateShareOutcome.NotFound
        access.check(userId, share.scopeId, WRITE)
            .refusal(ReactivateShareOutcome.NotFound, ReactivateShareOutcome.Suspended, ReactivateShareOutcome.Deleting)
            ?.let { return it }
        if (!capabilities.isActiveInScope(ShareAccessPolicy.capabilityFor(share.role), share.scopeId)) return ReactivateShareOutcome.CapabilityDisabled
        // A reactivation publishes the link again: not onto a switched-off application's node.
        if (!applications.isKindWritable(store.findNode(share.nodeId)?.kind.orEmpty(), share.scopeId)) return ReactivateShareOutcome.CapabilityDisabled
        // ADR 0075: the same link comes back on, so a link a departed member may hold is re-issued instead.
        val scope = scopes.find(share.scopeId) ?: return ReactivateShareOutcome.NotFound
        if (reissueRequired(share, scope)) return ReactivateShareOutcome.ReissueRequired
        if (blocks.shareBlocked(share.id)) return ReactivateShareOutcome.Blocked
        val keys = reopenKeys(share.role, reopen, userId)
        if (keys == ReopenKeys.Invalid) return ReactivateShareOutcome.InvalidRequest
        val now = Instant.now()
        if (share.enabled) {
            store.reactivate(userId, shareId, null, now)
            return ReactivateShareOutcome.Done
        }
        val given = keys as? ReopenKeys.Given ?: return ReactivateShareOutcome.LinkKeyRequired
        epochRefusal(
            share.scopeId, share.nodeId, checkNotNull(reopen.nodeEpoch), ReactivateShareOutcome.NotFound,
            ReactivateShareOutcome.StaleEpoch, ReactivateShareOutcome.RekeyPending,
        )?.let { return it }
        store.reactivate(userId, shareId, given.reopen, now)
        return ReactivateShareOutcome.Done
    }

    /** The manage list's rows, each with [reissueRequired], [deletable] for [userId] and [refusesNewGuests] — the
     *  scope read once per scope, not once per row. */
    private fun listed(userId: String, rows: List<ShareEntity>): List<ListedShare> {
        val scopesById = rows.map { it.scopeId }.distinct().mapNotNull { scopes.find(it) }.associateBy { it.id }
        return rows.map { s ->
            ListedShare(
                s, scopesById[s.scopeId]?.let { reissueRequired(s, it) } ?: true, deletable(userId, s), refusesNewGuests(s),
                gate.guestStops(s),
            )
        }
    }

    /**
     * [ReissuePolicy] for one share from what the server holds (ADR 0075 point 4) — the ONE source of both the
     * re-activation refusal and the list's `reissueRequired`, so the panel's mark and the refusal cannot disagree.
     * "Still a member" is [ownerIsMember].
     */
    private fun reissueRequired(share: ShareEntity, scope: ScopeEntity): Boolean =
        ReissuePolicy.required(
            reissueOnly = share.reissueOnly,
            ownerIsMember = ownerIsMember(share),
            // The epoch the link was minted at, not its copy's: a rotation moves the copy (ADR 0115 decision 2).
            mintedEpoch = share.mintedEpoch.takeIf { share.sealedShareKey != null },
            currentEpoch = scope.currentEpoch,
            removalsAtMint = share.memberRemovalsAtMint,
            removals = scope.memberRemovals,
        )

    /**
     * Re-issue a share (ADR 0075): a new share under the client-minted [newShareId] with a fresh `K_share`, and the
     * old one deleted (ADR 0029 §7), in one transaction ([SharingStore.reissue]). The node, the scope and the role come from
     * the old row, so a re-issue can neither move a link nor flip it into or out of WRITE_ONLY. An enabled share is
     * the owner's to re-issue (as revoke); a disabled one any member's, who owns the new share (as re-activation
     * adopts). A share is re-issued at most once: a deleted one — tombstoned by an earlier or a concurrent re-issue,
     * or by a delete — is [ReissueShareOutcome.ShareDeleted] for a member of its scope and the 404 for anyone else;
     * the store decides it again under the old row's lock. Every refusal writes nothing.
     */
    fun reissue(
        userId: String, shareId: String, newShareId: String, wrap: String?, sealedDropPublicKey: String?,
        sealedName: String, expiresAt: Instant?, sealedNote: String?, sealedShareKey: String, sealedShareKeyEpoch: Int,
        sealedShareName: String?, passwordRequired: Boolean, pwSalt: String?, pwVerifier: String?, sealedPassword: String?,
        writeVerifier: String, nodeEpoch: Int,
    ): ReissueShareOutcome {
        val old = store.findIncludingDeleted(shareId) ?: return ReissueShareOutcome.NotFound
        reissueRefusal(userId, old.scopeId, old.deleted, old.enabled, old.ownerAccount)?.let { return it }
        if (!capabilities.isActiveInScope(ShareAccessPolicy.capabilityFor(old.role), old.scopeId)) return ReissueShareOutcome.CapabilityDisabled
        // A re-issue publishes a new link: not onto a switched-off application's node.
        if (!applications.isKindWritable(store.findNode(old.nodeId)?.kind.orEmpty(), old.scopeId)) return ReissueShareOutcome.CapabilityDisabled
        if (blocks.shareBlockedItself(old.id)) return ReissueShareOutcome.Blocked
        // A re-issue makes a new link now, so it meets the requirements as a new one does.
        linkRefusal(old.scopeId, passwordRequired && pwVerifier != null, Instant.now(), expiresAt)?.let {
            return if (it === Failures.linkPasswordRequired) ReissueShareOutcome.LinkPasswordRequired else ReissueShareOutcome.LinkExpiryRequired
        }
        // No drop private key: the old WRITE_ONLY share proves the node already holds one (it is set once and
        // never cleared), and every upload so far is sealed to it (ADR 0031 point 7).
        val keys = shareKeys(old.role, wrap, sealedDropPublicKey, null) ?: return ReissueShareOutcome.InvalidRequest
        if (sealedShareKeyEpoch != scopes.currentEpoch(old.scopeId)) return ReissueShareOutcome.StaleEpoch
        return QuarkusTransaction.requiringNew().call {
            // The checks above, again under the scope row's lock, taken first: every removal holds it while it turns
            // the removed member's links off, and a re-activation holds it while it adopts one (developer-styleguide
            // §12). Checked only before it, a member being removed re-issued a live link that removal never saw. The
            // share's state is read by a scalar query, so the row the store spends is first loaded after the spend.
            scopes.lockScope(old.scopeId)
            val state = store.stateOf(shareId) ?: return@call ReissueShareOutcome.NotFound
            reissueRefusal(userId, old.scopeId, state.deleted, state.enabled, state.owner)?.let { return@call it }
            if (sealedShareKeyEpoch != scopes.currentEpoch(old.scopeId)) return@call ReissueShareOutcome.StaleEpoch
            // The new link wraps the node's key (ADR 0109 point 6): its epoch, judged under the node's row lock — taken
            // after the share row's (scope → share → node, every guest write's order), so the share row is locked here
            // first without loading it ([SharingStore.lockShareRow]); the spend below then finds the lock its own.
            store.lockShareRow(old.id)
            epochRefusal(
                old.scopeId, old.nodeId, nodeEpoch, ReissueShareOutcome.NotFound, ReissueShareOutcome.StaleEpoch,
                ReissueShareOutcome.RekeyPending,
            )?.let { return@call it }
            when (
                store.reissue(
                    old.id, newShareId, userId, keys, sealedName, expiresAt, sealedNote, sealedShareKey, sealedShareKeyEpoch,
                    sealedShareName, passwordRequired, pwSalt, pwVerifier, sealedPassword, writeVerifier,
                )
            ) {
                SharingStore.ReissueResult.CREATED -> ReissueShareOutcome.Created(newShareId)
                SharingStore.ReissueResult.ID_TAKEN -> ReissueShareOutcome.IdTaken
                SharingStore.ReissueResult.NOT_FOUND -> ReissueShareOutcome.NotFound
                SharingStore.ReissueResult.DELETED -> ReissueShareOutcome.ShareDeleted
            }
        }
    }

    /** The link requirements in effect for [scopeId]'s tenant, asked of a link with these properties ([LinkRequirements]). */
    private fun linkRefusal(scopeId: String, hasPassword: Boolean, createdAt: Instant, expiresAt: Instant?): HttpFailure? =
        LinkRequirements.refusal(capabilities.requirementsInScope(scopeId), hasPassword, createdAt, expiresAt)

    /** A re-issue's refusals that a removal, a delete or an adoption can turn: the caller's membership, a deleted
     *  share, an enabled one that is not the caller's. The store's compare-and-set decides "deleted" again. */
    private fun reissueRefusal(
        userId: String, scopeId: String, deleted: Boolean, enabled: Boolean, owner: String,
    ): ReissueShareOutcome? {
        access.check(userId, scopeId, WRITE)
            .refusal(ReissueShareOutcome.NotFound, ReissueShareOutcome.Suspended, ReissueShareOutcome.Deleting)?.let { return it }
        if (deleted) return ReissueShareOutcome.ShareDeleted
        if (enabled && owner != userId) return ReissueShareOutcome.NotFound
        return null
    }

    /**
     * Resolve a share for a guest: the link and, for a **viewable** role (READ/READ_WRITE), the sealed subtree's live
     * nodes with their keys; for a **WRITE_ONLY** link (write-only drop-box, ADR 0031) **no node** — the uploader is
     * structurally handed nothing to see. The `canView` gate is the server-side enforcement of "cannot see others".
     * No application data crosses here: what an application attached under the nodes is its own guest read behind
     * [ShareGuestGate.viewGate], with its own privacy rule (Memories' `listShareMedia`, ADR 0030 point 8, ADR 0079).
     */
    fun resolve(shareId: String, pwToken: String?): ResolveShareOutcome {
        val share = gate.activeShare(shareId) ?: return ResolveShareOutcome.NotFound
        when (gate.passwordCheck(share, pwToken)) { // cr-0118: nothing is served without a valid password token
            PwCheck.MISSING -> return ResolveShareOutcome.Locked(share)
            PwCheck.WRONG -> return ResolveShareOutcome.PasswordIncorrect
            PwCheck.TOO_MANY -> return ResolveShareOutcome.RateLimited // cr-0165
            PwCheck.OK -> {}
        }
        // Only past the password gate: a locked response names no kind (inbox #329). Read for every role from the one
        // row, so a WRITE_ONLY guest — served no node — learns which application renders the link, and nothing else of
        // the subtree. A trashed root names none, as it serves no node (`ShareDeletedNodeSliceTest`).
        val root = store.findNode(share.nodeId)
        val rootKind = root?.takeIf { it.deletedAt == null }?.kind
        // ADR 0114 decision 3: what a guest's write states as the root's epoch, and the scope epoch it mints a new
        // node's key under — every unlocked role, WRITE_ONLY included (its identity's `K_up` is wrapped to the root's
        // drop key, which a re-key replaces; its identity folder is minted under the root's scope epoch).
        val rootEpoch = root?.epoch
        val rootScopeEpoch = root?.scopeEpoch
        if (ShareAccessPolicy.canView(share.role)) {
            val nodes = store.subtree(share.scopeId, share.nodeId)
            return ResolveShareOutcome.Found(
                share, nodes, rootKind, rootEpoch, rootScopeEpoch, store.attachmentTags(share.scopeId, nodes.map { it.id }),
            )
        }
        // WRITE_ONLY: the minimal capability — no nodes; only the root's kind. The client seals its uploads under its own
        // identity key, wrapped to the root node's drop public key (served sealed under `K_share` in place of the wrap, ADR
        // 0031 point 7), and shows the title (from `sealedName`); it needs nothing from the subtree.
        return ResolveShareOutcome.Found(share, emptyList(), rootKind, rootEpoch, rootScopeEpoch)
    }

    /**
     * The owning tenant's public terms for an **accountless** visitor to accept **client-side** (uc.sharing.share-accept,
     * ADR 0035 §3 — the accountless half of the two-tier gate). Reuses [ShareGuestGate.activeShare] so an
     * unknown/disabled/expired share is the **same non-enumerable 404** as [resolve]: the caller passes only the `shareId` they already hold,
     * and the opaque `tenantId` is only *returned* (the client's localStorage key) — **no `tenantId` existence oracle**.
     * The terms are public operational content, so this is independent of the share password. **No acceptance is
     * recorded** — the server keeps nothing about the accountless visitor.
     *
     * Honest about its scope: what this serves is an **operational gate, not enforcement**. The client stores
     * acceptance in localStorage, which an accountless visitor can bypass (devtools / a fresh profile / resolving the
     * share directly). It records + surfaces consent; it does not *prevent* access. The legally-meaningful protection
     * is the terms' own content (CSAM/illegal-content + review-and-removal) and the decryptable-on-report path, not
     * this prompt. A tenant with no published terms yields `termsRevision` 0, and the client then never gates.
     */
    fun shareTerms(shareId: String): ShareTermsOutcome {
        val share = gate.activeShare(shareId) ?: return ShareTermsOutcome.NotFound
        // The share→tenant hop: share.scopeId → the scope's tenant (a PERSONAL scope still resolves its tenant;
        // ADR 0029 §6). Defensive null (a share always has a scope+tenant by FK) → the same non-enumerable 404.
        val tenantId = scopes.scopeTenantId(share.scopeId) ?: return ShareTermsOutcome.NotFound
        val terms = tenantTerms.guestTerms(tenantId) ?: return ShareTermsOutcome.NotFound
        return ShareTermsOutcome.Found(tenantId, terms.revision, terms.terms?.de, terms.terms?.en, terms.section)
    }

    // --- contributor / uploader (cr-0101, cr-0102) -----------------------------

    /** Name yourself on a contributable share (READ_WRITE/WRITE_ONLY): seal-name in, an anonymous identity + token out.
     *  [seen] is the client its resource resolved: the new identity's name is a guest write the record keeps (ADR 0093
     *  § 10), in this transaction, once the identity is stored — a replay stores nothing and records nothing. */
    fun createIdentity(
        shareId: String, pwToken: String?, writeToken: String?, identityId: String, sealedDisplayName: String,
        wrappedIdentityKey: String?, nodeEpoch: Int, seen: ClientSeen?,
    ): CreateIdentityOutcome {
        val share = gate.activeShare(shareId) ?: return CreateIdentityOutcome.NotFound
        when (gate.passwordCheck(share, pwToken)) { // cr-0164: the password gate fronts the write path, before the role check
            PwCheck.MISSING -> return CreateIdentityOutcome.NotFound // no token → non-enumerable 404 (no role reveal)
            PwCheck.WRONG -> return CreateIdentityOutcome.PasswordIncorrect
            PwCheck.TOO_MANY -> return CreateIdentityOutcome.RateLimited // cr-0165
            PwCheck.OK -> {}
        }
        // ADR 0076: the id alone names no guest — the caller proves it holds the link's key. After the password gate
        // (so a password-protected link still answers its own refusals first), before the role check (so a wrong
        // proof learns nothing of the role). A share holding no verifier fails closed.
        if (!holdsTheKey(share, writeToken)) return CreateIdentityOutcome.NotFound
        if (!ShareAccessPolicy.canUpload(share.role)) return CreateIdentityOutcome.NotContributable
        // ADR 0031 point 7: a WRITE_ONLY identity seals under its own key and must hand it over wrapped to the drop
        // key, or nobody could ever open what it uploads; a READ_WRITE identity seals under the shared node's content key and has none to hand.
        val uploader = share.role == ShareEntity.WRITE_ONLY
        if (uploader == wrappedIdentityKey.isNullOrEmpty()) return CreateIdentityOutcome.InvalidRequest
        // The only guest call that writes a row before any upload exists — bounded per share (ADR 0051,
        // `@RateLimit(key = SHARE)`), after the authorization gates so an unauthorized caller spends nothing.
        if (!rateLimiter.allowIdentity(shareId)) return CreateIdentityOutcome.RateLimited
        // The client-minted identity id (ADR 0072): the sealed display name is bound to it.
        return createOrConflict(conflict = { if (store.identityIdTaken(identityId)) CreateIdentityOutcome.IdTaken else null }) {
            // The link again, under the row lock its delete holds: gated only above, a guest named itself on a link
            // deleted in between, and nothing would ever drop that identity.
            if (!store.admitsGuests(shareId)) return@createOrConflict CreateIdentityOutcome.NotFound
            // ADR 0109 point 6: a READ_WRITE guest's name is sealed under the root's key, a WRITE_ONLY guest's `K_up` to the
            // root's drop key — both replaced by a re-key. The root's epoch as the guest read it (`PublicShare.rootEpoch`),
            // under the root's row lock, taken after the share row's (every guest write's order: share → node).
            epochRefusal(
                share.scopeId, share.nodeId, nodeEpoch, CreateIdentityOutcome.NotFound, CreateIdentityOutcome.StaleEpoch,
                CreateIdentityOutcome.RekeyPending,
            )?.let { return@createOrConflict it }
            val token = store.createIdentity(identityId, shareId, sealedDisplayName, wrappedIdentityKey)
                ?: return@createOrConflict CreateIdentityOutcome.IdTaken
            guestWriteRecords.record(seen, identityId, GuestWriteObject.IDENTITY, identityId, GuestWriteRecords.NAME)
            CreateIdentityOutcome.Created(identityId, token)
        }
    }

    /**
     * Start a contributor upload — gated on the share (contributable + enabled) + a valid token, then presign. The
     * uploads are for [nodeId] (ADR 0080 decision 6: a link's role applies to every node below its root as the
     * application allows it), or the share's root when null ([ShareGuestGate.target]); the writer and the per-link
     * measure are picked by THAT node's kind. Opening a session writes nothing onto the node, so the target is checked
     * by a plain read here; the completion decides it again under the scope lock.
     */
    fun startUploads(
        shareId: String, pwToken: String?, contributorToken: String, declares: List<UploadDeclare>, domain: String?, nodeId: String? = null,
    ): ShareUploadOutcome {
        // The gate without the budget: a start spends it only once its own request checks have passed (below).
        val gated = when (val g = gate.writeGate(shareId, pwToken, contributorToken, spendUploadBudget = false)) {
            is ShareWriteGate.Admitted -> g
            ShareWriteGate.NotFound -> return ShareUploadOutcome.NotFound
            ShareWriteGate.PasswordIncorrect -> return ShareUploadOutcome.PasswordIncorrect
            ShareWriteGate.RateLimited -> return ShareUploadOutcome.RateLimited
            ShareWriteGate.NotContributable -> return ShareUploadOutcome.NotContributable
        }
        val target = when (val t = gate.target(gated, nodeId)) {
            is ShareWriteTarget.Node -> t
            ShareWriteTarget.NotFound -> return ShareUploadOutcome.NodeNotFound
            ShareWriteTarget.NotContributable -> return ShareUploadOutcome.NotContributable
        }
        val writes = writesFor(target.kind) ?: return ShareUploadOutcome.NotContributable
        // No upload starts for a switched-off application's node (uc.platform.switch-application-off) — before the budget.
        applications.requireKindWritable(target.kind, gated.scopeId)
        // A link whose usage no single application measures is never admitted (fail closed, [UploadAdmission]); the
        // reservation asks again under the tenant lock, and refuses there too.
        if (quota.admissionFor(target.kind) == null) return ShareUploadOutcome.NotContributable
        // The role is an open token on the wire; which roles a guest uploads in is the target's application's (Memories:
        // never a COVER). Asked only past the gate, so an unauthorized caller still learns nothing but its 403/404.
        if (declares.any { !writes.admitsGuestRole(it.role) }) return ShareUploadOutcome.InvalidRequest
        // Starting creates upload sessions, so it spends the per-share upload budget (ADR 0051, `@RateLimit(key = SHARE)`)
        // — last, after the target, the writer and the roles: a start refused for its own request creates nothing and so
        // spends nothing (inbox #367; the role needs the target's writer, which only the gate's identity can name).
        if (!rateLimiter.allowUpload(shareId)) return ShareUploadOutcome.RateLimited
        return ShareUploadOutcome.Results(writes.start(gated.scopeId, shareId, target.kind, gated.identityId, declares, domain))
    }

    /**
     * Sign the next window of a contributor's open uploads — the link's whole gate again (active share, password,
     * upload role, token), because a window is where a revoked, expired or disabled link stops an upload already in
     * flight (ADR 0013 §2). It does **not** spend [ShareRateLimiter.allowUpload]: that budget counts the writes that
     * create sessions and rows, and a window creates neither. Only the identity that opened a session gets its window.
     * The target [nodeId] is checked again as the start checked it (null = the root), so a target trashed or moved out
     * of the subtree stops the upload at its next window as a revoke does.
     */
    fun windowUploads(
        shareId: String, pwToken: String?, contributorToken: String, requests: List<WindowRequest>, domain: String?, nodeId: String? = null,
    ): ShareWindowOutcome {
        val gated = when (val g = gate.writeGate(shareId, pwToken, contributorToken, spendUploadBudget = false)) {
            is ShareWriteGate.Admitted -> g
            ShareWriteGate.NotFound -> return ShareWindowOutcome.NotFound
            ShareWriteGate.PasswordIncorrect -> return ShareWindowOutcome.PasswordIncorrect
            ShareWriteGate.RateLimited -> return ShareWindowOutcome.RateLimited
            ShareWriteGate.NotContributable -> return ShareWindowOutcome.NotContributable
        }
        val target = when (val t = gate.target(gated, nodeId)) {
            is ShareWriteTarget.Node -> t
            ShareWriteTarget.NotFound -> return ShareWindowOutcome.NodeNotFound
            ShareWriteTarget.NotContributable -> return ShareWindowOutcome.NotContributable
        }
        val writes = writesFor(target.kind) ?: return ShareWindowOutcome.NotContributable
        return ShareWindowOutcome.Results(writes.window(gated.scopeId, gated.identityId, requests, domain))
    }

    fun resolveBlob(shareId: String, blobId: String, pwToken: String?, domain: String?): ShareBlobOutcome {
        val share = gate.activeShare(shareId) ?: return ShareBlobOutcome.NotFound
        when (gate.passwordCheck(share, pwToken)) { // cr-0118: no bytes without the password (missing → 404, wrong → 403)
            PwCheck.MISSING -> return ShareBlobOutcome.NotFound
            PwCheck.WRONG -> return ShareBlobOutcome.PasswordIncorrect
            PwCheck.TOO_MANY -> return ShareBlobOutcome.RateLimited // cr-0165
            PwCheck.OK -> {}
        }
        // A write-only role has NO download capability (ADR 0031) — a WRITE_ONLY share serves no blob, even for an
        // in-subtree id. This makes "cannot see" a real server gate, not just id-secrecy (the resolve returns none).
        if (!ShareAccessPolicy.canView(share.role)) return ShareBlobOutcome.NotFound
        val blob = store.authorizedBlob(share.scopeId, share.nodeId, blobId) ?: return ShareBlobOutcome.NotFound
        // A content block refuses the bytes to everyone (uc.moderation.take-a-measure) — after the link's own gate, so a
        // guest learns of a block only inside a link it holds.
        if (blocks.blobBlocked(share.scopeId, blobId)) return ShareBlobOutcome.Blocked
        val gets = blobStore.presignGets(blob.storageKey, blob.chunkCount, domain) ?: return ShareBlobOutcome.StoreUnavailable
        return ShareBlobOutcome.Found(gets)
    }

    /**
     * The application that takes a guest's uploads into a node of [targetKind] — the kind of the upload's target
     * ([ShareGuestGate.target]: the share root, or a node below it the guest named; ADR 0080 decision 6), never the
     * root's when another node is named — called only after the guest gate has passed. Null (read as "not
     * contributable") for a kind no application registers — a `FOLDER`, the root of a folder link included: [ShareRoots]
     * lets a folder be shared READ_WRITE, and its guests upload into the Memories and files below it, never into the folder — or of
     * a kind **more than one** claims: exactly one per kind is the contract (`ShareGuestWrites`), and picking the first
     * would rest on CDI bean order. Every case fails closed rather than guessing a writer;
     * `ShareGuestWritesPartitionSliceTest` pins the partition. The start asks [StorageQuota.admissionFor] by the same
     * kind, so writer and measure cannot disagree.
     */
    private fun writesFor(targetKind: String): ShareGuestWrites? = guestWrites.filter { it.supports(targetKind) }.singleOrNull()

    /** ADR 0076 point 5, as the manage list serves it (inbox #270): a link that takes guests but holds no verifier —
     *  minted before the column — refuses every new guest in [createIdentity] until it is re-issued. The same two
     *  facts that gate decides on: [holdsTheKey]'s null verifier, and the role check after it. */
    private fun refusesNewGuests(share: ShareEntity): Boolean =
        share.writeVerifier == null && ShareAccessPolicy.canUpload(share.role)

    /** ADR 0076: [writeToken] is the preimage of the share's write verifier. No verifier, no token, a wrong or a
     *  malformed one — all false. No attempt limiter: the token has 256 bits. */
    private fun holdsTheKey(share: ShareEntity, writeToken: String?): Boolean {
        val verifier = share.writeVerifier ?: return false
        return !writeToken.isNullOrBlank() && gate.matchesVerifier(writeToken, verifier)
    }
}
