package com.veyq.app.features.sharing

import com.veyq.app.features.admin.tenant.TenantTermsHandler
import com.veyq.app.features.content.NodeEntity
import com.veyq.app.features.media.CompleteItem
import com.veyq.app.features.media.CompleteItemResult
import com.veyq.app.features.media.DerivativeDeclare
import com.veyq.app.features.media.MediaHandler
import com.veyq.app.features.media.UploadDeclare
import com.veyq.app.features.media.UploadItemResult
import com.veyq.app.features.media.UploadOpener
import com.veyq.app.features.media.WindowItemResult
import com.veyq.app.features.media.WindowRequest
import com.veyq.app.features.platform.capabilities.CapabilityIds
import com.veyq.app.features.platform.capabilities.CapabilityRegistry
import com.veyq.app.features.spaces.scope.ScopesStore
import com.veyq.app.shared.blobstore.BlobStore
import com.veyq.app.shared.blobstore.PresignedRef
import com.veyq.app.shared.blobstore.presignGets
import com.veyq.app.shared.http.FailureStatus.CONFLICT
import com.veyq.app.shared.http.FailureStatus.FORBIDDEN
import com.veyq.app.shared.http.FailureStatus.NOT_FOUND
import com.veyq.app.shared.http.FailureStatus.SERVICE_UNAVAILABLE
import com.veyq.app.shared.http.FailureStatus.UNPROCESSABLE
import com.veyq.app.shared.http.Failures
import com.veyq.app.shared.http.HttpFailure
import com.veyq.app.shared.http.httpFailure
import com.veyq.app.shared.membership.Access
import com.veyq.app.shared.membership.AccessMode.READ
import com.veyq.app.shared.membership.AccessMode.WRITE
import com.veyq.app.shared.membership.ScopeAccess
import com.veyq.app.shared.membership.refusal
import com.veyq.app.shared.persistence.createOrConflict
import com.veyq.app.shared.scope.ScopeEntity
import io.quarkus.narayana.jta.QuarkusTransaction
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64

/** One contributor upload to complete: the original session + role-tagged derivatives (the shared node is server-set). */
data class ShareCompleteItem(
    val mediaId: String,
    val originalUploadId: String,
    val derivatives: List<DerivativeDeclare>,
    val wrappedMeta: String?,
    val wrappedExif: String?,
)

sealed interface CreateShareOutcome {
    data class Created(val shareId: String) : CreateShareOutcome
    object Suspended : CreateShareOutcome, HttpFailure by Failures.tenantSuspended
    object Deleting : CreateShareOutcome, HttpFailure by Failures.scopeDeleting
    /** The sharing capability this role needs is disabled instance-wide (ADR 0032) — a 422, instance policy. */
    object CapabilityDisabled : CreateShareOutcome, HttpFailure by Failures.capabilityDisabled
    /** No such node, or the caller holds no grant for its scope (a 404, no reveal). */
    object NoNode : CreateShareOutcome, HttpFailure by Failures.nodeNotFound
    /** The node is not a MEMORY (only a Memory can be public-shared, ADR 0029 §6) — a 422. */
    object IllegalScope : CreateShareOutcome, HttpFailure by httpFailure("illegal_scope", UNPROCESSABLE)
    object IdTaken : CreateShareOutcome, HttpFailure by Failures.idTaken
    /** The keys do not match the role: a wrap on an UPLOADER share, a drop key on any other — a 400. */
    object InvalidRequest : CreateShareOutcome, HttpFailure by Failures.invalidRequest
    /** The recovery copy's epoch is not the scope's current one (ADR 0024 Decision 6) — a 409, nothing written. */
    object StaleEpoch : CreateShareOutcome, HttpFailure by Failures.staleEpoch
    /** An UPLOADER share brought a drop private key, but the Memory already has one — a 409, nothing written. */
    object DropKeyExists : CreateShareOutcome, HttpFailure by httpFailure("drop_key_exists", CONFLICT)
    /** An UPLOADER share brought no drop private key, and the Memory has none — a 409, nothing written. */
    object DropKeyMissing : CreateShareOutcome, HttpFailure by httpFailure("drop_key_missing", CONFLICT)
}

sealed interface ResolveShareOutcome {
    /** [uploaderKeys]: identity id → wrapped identity key, the only thing a guest is served about any identity. */
    data class Found(val share: ShareEntity, val subtree: SubtreeData, val uploaderKeys: Map<String, String>) : ResolveShareOutcome
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
    /** A wrapped identity key that does not match the role: missing on UPLOADER, present on CONTRIBUTOR — a 400. */
    object InvalidRequest : CreateIdentityOutcome, HttpFailure by Failures.invalidRequest
    /** The share does not accept contributions (not CONTRIBUTOR/UPLOADER) — a 403. */
    object NotContributable : CreateIdentityOutcome, HttpFailure by httpFailure("share_not_contributable", FORBIDDEN)
    /** Password-protected + a wrong token (cr-0164) — a 403. */
    object PasswordIncorrect : CreateIdentityOutcome, HttpFailure by httpFailure("share_password_incorrect", FORBIDDEN)
    /** Too many wrong password attempts on this share (cr-0165) — a 429. */
    object RateLimited : CreateIdentityOutcome, HttpFailure by Failures.rateLimited
}

sealed interface ShareDeleteOutcome {
    object Done : ShareDeleteOutcome
    /** No such share/media, the media is not owned by this identity, or password-protected with no token
     *  (cr-0164) — a non-enumerable 404. */
    object NotFound : ShareDeleteOutcome, HttpFailure by httpFailure("share_media_not_found", NOT_FOUND)
    /** The share does not accept contributions, or the contributor token is invalid — a 403. */
    object NotContributable : ShareDeleteOutcome, HttpFailure by httpFailure("share_not_contributable", FORBIDDEN)
    /** Password-protected + a wrong token (cr-0164) — a 403. */
    object PasswordIncorrect : ShareDeleteOutcome, HttpFailure by httpFailure("share_password_incorrect", FORBIDDEN)
    /** Too many wrong password attempts on this share (cr-0165) — a 429. */
    object RateLimited : ShareDeleteOutcome, HttpFailure by Failures.rateLimited
}

sealed interface ShareUploadOutcome {
    data class Results(val items: List<UploadItemResult>) : ShareUploadOutcome
    object NotFound : ShareUploadOutcome, HttpFailure by httpFailure("share_not_found", NOT_FOUND)
    /** Not a CONTRIBUTOR share, or the contributor token is invalid — a 403. */
    object NotContributable : ShareUploadOutcome, HttpFailure by httpFailure("share_not_contributable", FORBIDDEN)
    /** Password-protected + a wrong token (cr-0164) — a 403. */
    object PasswordIncorrect : ShareUploadOutcome, HttpFailure by httpFailure("share_password_incorrect", FORBIDDEN)
    /** Per-share rate limit exceeded — a 429. */
    object RateLimited : ShareUploadOutcome, HttpFailure by Failures.rateLimited
}

sealed interface ShareWindowOutcome {
    data class Results(val items: List<WindowItemResult>) : ShareWindowOutcome
    object NotFound : ShareWindowOutcome, HttpFailure by httpFailure("share_not_found", NOT_FOUND)
    /** Not a CONTRIBUTOR share, or the contributor token is invalid — a 403. */
    object NotContributable : ShareWindowOutcome, HttpFailure by httpFailure("share_not_contributable", FORBIDDEN)
    /** Password-protected + a wrong token (cr-0164) — a 403. */
    object PasswordIncorrect : ShareWindowOutcome, HttpFailure by httpFailure("share_password_incorrect", FORBIDDEN)
    /** Too many wrong password tokens on this share (cr-0165) — a 429. */
    object RateLimited : ShareWindowOutcome, HttpFailure by Failures.rateLimited
}

sealed interface ShareCompleteOutcome {
    data class Results(val items: List<CompleteItemResult>) : ShareCompleteOutcome
    object NotFound : ShareCompleteOutcome, HttpFailure by httpFailure("share_not_found", NOT_FOUND)
    object NotContributable : ShareCompleteOutcome, HttpFailure by httpFailure("share_not_contributable", FORBIDDEN)
    /** Password-protected + a wrong token (cr-0164) — a 403. */
    object PasswordIncorrect : ShareCompleteOutcome, HttpFailure by httpFailure("share_password_incorrect", FORBIDDEN)
    /** Too many wrong password attempts on this share (cr-0165) — a 429. */
    object RateLimited : ShareCompleteOutcome, HttpFailure by Failures.rateLimited
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
}

sealed interface RevokeShareOutcome {
    object Done : RevokeShareOutcome
    object Suspended : RevokeShareOutcome, HttpFailure by Failures.tenantSuspended
    object Deleting : RevokeShareOutcome, HttpFailure by Failures.scopeDeleting
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
    /** An illegal role change (cr-0119) — `role = UPLOADER`, or a change on an UPLOADER share → 422. */
    object InvalidRole : UpdateShareOutcome, HttpFailure by httpFailure("invalid_role_change", UNPROCESSABLE)
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
}

sealed interface ReissueShareOutcome {
    data class Created(val shareId: String) : ReissueShareOutcome
    object Suspended : ReissueShareOutcome, HttpFailure by Failures.tenantSuspended
    object Deleting : ReissueShareOutcome, HttpFailure by Failures.scopeDeleting
    /** No such share, the caller is not a member of its scope, or it is enabled and not theirs — one 404. */
    object NotFound : ReissueShareOutcome, HttpFailure by httpFailure("share_not_found", NOT_FOUND)
    /** The capability the old share's role needs is off (ADR 0032) — a re-issue publishes a link. */
    object CapabilityDisabled : ReissueShareOutcome, HttpFailure by Failures.capabilityDisabled
    /** The keys do not match the old share's role — a 400, as on create. */
    object InvalidRequest : ReissueShareOutcome, HttpFailure by Failures.invalidRequest
    /** The recovery copy is not sealed at the scope's current epoch (ADR 0024 Decision 6) — a 409, nothing written. */
    object StaleEpoch : ReissueShareOutcome, HttpFailure by Failures.staleEpoch
    object IdTaken : ReissueShareOutcome, HttpFailure by Failures.idTaken
    /** The share is already deleted — re-issued once already (a concurrent re-issue won, or an earlier one ran) or
     *  deleted. A member's answer only (inbox #252): a 409, nothing written; the caller re-reads the list. */
    object ShareDeleted : ReissueShareOutcome, HttpFailure by httpFailure("share_deleted", CONFLICT)
}

/** A share as the manage list serves it: the row plus [ReissuePolicy]'s answer for it (ADR 0075 point 4) and
 *  whether the caller may delete it — the gate's own answer (ADR 0029 §7). */
data class ListedShare(val share: ShareEntity, val reissueRequired: Boolean, val deletable: Boolean)

/** The shares on one Memory node (cr-0104) — `[]` for a non-member or an unknown node, a refusal only for a member. */
sealed interface NodeSharesOutcome {
    data class Shares(val rows: List<ListedShare>) : NodeSharesOutcome
    object Suspended : NodeSharesOutcome, HttpFailure by Failures.tenantSuspended
}

sealed interface ShareTermsOutcome {
    /** The owning tenant's public terms — `termsVersion` 0 (with null `de`/`en`) when the operator published none. */
    data class Found(val tenantId: String, val termsVersion: Int, val de: String?, val en: String?) : ShareTermsOutcome
    /** Unknown/disabled/expired share — the same non-enumerable 404 as [ResolveShareOutcome.NotFound]. */
    object NotFound : ShareTermsOutcome, HttpFailure by httpFailure("share_not_found", NOT_FOUND)
}

/**
 * Use-case logic for public viewer shares (cr-0100, ADR 0029). Two authorization surfaces:
 *  - **Curator** (create/list/revoke/reactivate/update): authenticated, and gated first by [ScopeAccess] on the
 *    share's (or node's) space — a **MEMORY in a scope the caller is a member of** (personal, group, or tenant —
 *    cr-0104), writable only outside its deletion grace and a suspension (uc.spaces.scope-access).
 *  - **Guest** (resolve/blob): unauthenticated, keyed only on share state (`enabled` + `expiresAt` + a live space) — a
 *    parallel authz path, never a relaxed scope filter. The blob GET is authorized structurally (the blob is
 *    attached under the shared subtree), and bytes go **direct-from-store** so the operator never learns which
 *    media a viewer opens. A disabled/expired/unknown share is a non-enumerable 404 in every case.
 */
@ApplicationScoped
class SharingHandler(
    private val store: SharingStore,
    private val blobStore: BlobStore,
    private val mediaHandler: MediaHandler,
    private val rateLimiter: ShareRateLimiter,
    private val passwordAttemptLimiter: SharePasswordAttemptLimiter,
    private val capabilities: CapabilityRegistry,
    private val scopes: ScopesStore,
    private val tenantTerms: TenantTermsHandler,
    private val access: ScopeAccess,
) {

    fun create(
        userId: String, shareId: String, nodeId: String, role: String, wrap: String?,
        sealedDropPublicKey: String?, sealedDropPrivateKey: String?, sealedName: String, expiresAt: Instant?,
        sealedNote: String?, sealedShareKey: String?, sealedShareKeyEpoch: Int?, sealedShareName: String?,
        passwordRequired: Boolean, pwSalt: String?, pwVerifier: String?, sealedPassword: String?, writeVerifier: String,
    ): CreateShareOutcome {
        // Instance policy first (ADR 0032): the capability this role needs must be active, else 422 — checked
        // before any node/scope lookup so it reads as "the operator turned sharing off", not a scope error.
        if (!capabilities.isActive(capabilityFor(role))) return CreateShareOutcome.CapabilityDisabled
        val keys = shareKeys(role, wrap, sealedDropPublicKey, sealedDropPrivateKey) ?: return CreateShareOutcome.InvalidRequest
        // The recovery copy of K_share and the epoch of K_SC it is sealed under travel together (ADR 0036 point 4).
        if ((sealedShareKey == null) != (sealedShareKeyEpoch == null)) return CreateShareOutcome.InvalidRequest
        val node = store.findNode(nodeId) ?: return CreateShareOutcome.NoNode
        access.check(userId, node.scopeId, WRITE)
            .refusal(CreateShareOutcome.NoNode, CreateShareOutcome.Suspended, CreateShareOutcome.Deleting)?.let { return it }
        // Any member of the node's scope may share (cr-0104) — personal, group, or tenant; only a MEMORY node can
        // be public-shared (ADR 0029 §6, ADR 0030).
        if (node.kind != NodeEntity.MEMORY) return CreateShareOutcome.IllegalScope
        // ADR 0024 Decision 6, as NodesHandler checks it: a recovery copy sealed under an epoch other than the scope's
        // current one is refused — an older one is an epoch a member removed since still holds.
        if (sealedShareKeyEpoch != null && sealedShareKeyEpoch != scopes.currentEpoch(node.scopeId)) {
            return CreateShareOutcome.StaleEpoch
        }
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
                ?: CreateShareOutcome.StaleEpoch.takeIf {
                    sealedShareKeyEpoch != null && sealedShareKeyEpoch != scopes.currentEpoch(node.scopeId)
                }
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
        // A double-submitted Memory's FIRST UPLOADER link loses at the guarded drop-key update, not at the commit: both
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
     * The keys a share of [role] carries, or null when the request's do not match it (ADR 0031 point 7). An
     * UPLOADER share never carries `symWrap(K_share, K_mem)` — the one party it exists to keep out would hold the
     * Memory's read key — and every other role never carries a drop key. Sent-but-empty counts as sent: refused.
     */
    private fun shareKeys(role: String, wrap: String?, dropPublic: String?, dropPrivate: String?): ShareKeys? =
        if (role == ShareEntity.UPLOADER) {
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

    /** Every public share on a Memory node, for the shared-scope manage panel (cr-0104): visible to any **member**
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

    /** Revoke a share — only its creator, and only while they are still a member of its space (share-shared-scope A3). */
    fun revoke(userId: String, shareId: String): RevokeShareOutcome {
        val share = store.find(shareId) ?: return RevokeShareOutcome.NotFound
        access.check(userId, share.scopeId, WRITE)
            .refusal(RevokeShareOutcome.NotFound, RevokeShareOutcome.Suspended, RevokeShareOutcome.Deleting)?.let { return it }
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
     *  a non-null `role` changes the grant in place (VIEWER ↔ CONTRIBUTOR only — UPLOADER, either as the target or
     *  as the share's current role, is `InvalidRole` → 422). Role change is a plaintext authz change: no re-crypto,
     *  the wrap/`K_share`/link are untouched. */
    fun updateShare(
        userId: String, shareId: String, sealedNote: String?, sealedShareName: String?, role: String?,
        expiresAt: Instant?, passwordRequired: Boolean?, pwSalt: String?, pwVerifier: String?, sealedPassword: String?,
    ): UpdateShareOutcome {
        val share = store.find(shareId) ?: return UpdateShareOutcome.NotFound
        access.check(userId, share.scopeId, WRITE)
            .refusal(UpdateShareOutcome.NotFound, UpdateShareOutcome.Suspended, UpdateShareOutcome.Deleting)?.let { return it }
        return when (store.updateShare(userId, shareId, sealedNote, sealedShareName, role, expiresAt, passwordRequired, pwSalt, pwVerifier, sealedPassword)) {
            SharingStore.UpdateResult.DONE -> UpdateShareOutcome.Done
            SharingStore.UpdateResult.NOT_FOUND -> UpdateShareOutcome.NotFound
            SharingStore.UpdateResult.INVALID_ROLE -> UpdateShareOutcome.InvalidRole
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
     *  The scope id comes from a scalar read, so the share's first load is the locked one, never a stale copy. */
    @Transactional
    fun reactivate(userId: String, shareId: String): ReactivateShareOutcome {
        val scopeId = store.liveScopeOf(shareId) ?: return ReactivateShareOutcome.NotFound
        scopes.lockScope(scopeId)
        val share = store.liveForUpdate(shareId) ?: return ReactivateShareOutcome.NotFound
        access.check(userId, share.scopeId, WRITE)
            .refusal(ReactivateShareOutcome.NotFound, ReactivateShareOutcome.Suspended, ReactivateShareOutcome.Deleting)
            ?.let { return it }
        if (!capabilities.isActive(capabilityFor(share.role))) return ReactivateShareOutcome.CapabilityDisabled
        // ADR 0075: the same link comes back on, so a link a departed member may hold is re-issued instead.
        val scope = scopes.find(share.scopeId) ?: return ReactivateShareOutcome.NotFound
        if (reissueRequired(share, scope)) return ReactivateShareOutcome.ReissueRequired
        store.reactivate(userId, shareId)
        return ReactivateShareOutcome.Done
    }

    /** The manage list's rows, each with [reissueRequired] and [deletable] for [userId] — the scope read once per
     *  scope, not once per row. */
    private fun listed(userId: String, rows: List<ShareEntity>): List<ListedShare> {
        val scopesById = rows.map { it.scopeId }.distinct().mapNotNull { scopes.find(it) }.associateBy { it.id }
        return rows.map { s ->
            ListedShare(s, scopesById[s.scopeId]?.let { reissueRequired(s, it) } ?: true, deletable(userId, s))
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
            copyEpoch = share.sealedShareKeyEpoch,
            currentEpoch = scope.currentEpoch,
            removalsAtMint = share.memberRemovalsAtMint,
            removals = scope.memberRemovals,
        )

    /**
     * Re-issue a share (ADR 0075): a new share under the client-minted [newShareId] with a fresh `K_share`, and the
     * old one deleted (ADR 0029 §7), in one transaction ([SharingStore.reissue]). The Memory, the scope and the role come from
     * the old row, so a re-issue can neither move a link nor flip it into or out of UPLOADER. An enabled share is
     * the owner's to re-issue (as revoke); a disabled one any member's, who owns the new share (as re-activation
     * adopts). A share is re-issued at most once: a deleted one — tombstoned by an earlier or a concurrent re-issue,
     * or by a delete — is [ReissueShareOutcome.ShareDeleted] for a member of its scope and the 404 for anyone else;
     * the store decides it again under the old row's lock. Every refusal writes nothing.
     */
    fun reissue(
        userId: String, shareId: String, newShareId: String, wrap: String?, sealedDropPublicKey: String?,
        sealedName: String, expiresAt: Instant?, sealedNote: String?, sealedShareKey: String, sealedShareKeyEpoch: Int,
        sealedShareName: String?, passwordRequired: Boolean, pwSalt: String?, pwVerifier: String?, sealedPassword: String?,
        writeVerifier: String,
    ): ReissueShareOutcome {
        val old = store.findIncludingDeleted(shareId) ?: return ReissueShareOutcome.NotFound
        reissueRefusal(userId, old.scopeId, old.deletedAt != null, old.enabled, old.ownerAccount)?.let { return it }
        if (!capabilities.isActive(capabilityFor(old.role))) return ReissueShareOutcome.CapabilityDisabled
        // No drop private key: the old UPLOADER share proves the Memory already holds one (it is set once and
        // never cleared), and every upload so far is sealed to it (ADR 0031 point 7).
        val keys = shareKeys(old.role, wrap, sealedDropPublicKey, null) ?: return ReissueShareOutcome.InvalidRequest
        if (sealedShareKeyEpoch != scopes.currentEpoch(old.scopeId)) return ReissueShareOutcome.StaleEpoch
        return QuarkusTransaction.requiringNew().call {
            // The checks above, again under the scope row's lock, taken first: every removal holds it while it turns
            // the removed member's links off, and a re-activation holds it while it adopts one (developer-styleguide
            // §12). Checked only before it, a member being removed re-issued a live link that removal never saw. The
            // share's state is read by a scalar query, so the row the store spends is first loaded after the spend.
            scopes.lockScope(old.scopeId)
            val (enabled, owner) = store.enabledAndOwnerOf(shareId) ?: return@call ReissueShareOutcome.NotFound
            reissueRefusal(userId, old.scopeId, false, enabled, owner)?.let { return@call it }
            if (sealedShareKeyEpoch != scopes.currentEpoch(old.scopeId)) return@call ReissueShareOutcome.StaleEpoch
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
     * Resolve a share for a guest. For a **viewable** role (VIEWER/CONTRIBUTOR) the full sealed subtree + the
     * wrapped keys of the UPLOADER identities whose media it holds cross — no guest's name, no contributor
     * identity (ADR 0030 point 8); for an **UPLOADER** (write-only drop-box, ADR 0031) the resolve is gated to
     * `media: []` + the **root node only** and **no identities** — the uploader is structurally handed nothing to
     * see (no gallery, no other guests). The `canView` gate is the server-side enforcement of "cannot see others".
     */
    fun resolve(shareId: String, pwToken: String?): ResolveShareOutcome {
        val share = activeShare(shareId) ?: return ResolveShareOutcome.NotFound
        when (passwordCheck(share, pwToken)) { // cr-0118: nothing is served without a valid password token
            PwCheck.MISSING -> return ResolveShareOutcome.Locked(share)
            PwCheck.WRONG -> return ResolveShareOutcome.PasswordIncorrect
            PwCheck.TOO_MANY -> return ResolveShareOutcome.RateLimited // cr-0165
            PwCheck.OK -> {}
        }
        if (ShareAccessPolicy.canView(share.role)) {
            val subtree = store.subtree(share.scopeId, share.nodeId)
            val uploaderKeys = store.uploaderKeys(subtree)
            // A media names its identity only where the guest needs it to find the key (ADR 0031 point 7); a
            // contributor's opens under K_mem, and its id would only tell a guest which uploads came from one
            // person (ADR 0030 point 8). Derived from the same map, so the two can never disagree.
            val media = subtree.media.map { if (it.identityId in uploaderKeys) it else it.copy(identityId = null) }
            return ResolveShareOutcome.Found(share, SubtreeData(subtree.nodes, media), uploaderKeys)
        }
        // UPLOADER: the minimal capability — no nodes, no media, no identities. The client seals its uploads under
        // its own identity key, wrapped to the Memory's drop public key (served sealed under `K_share` in place of
        // the wrap, ADR 0031 point 7), and shows the title (from `sealedName`); it needs nothing from the subtree.
        return ResolveShareOutcome.Found(share, SubtreeData(emptyList(), emptyList()), emptyMap())
    }

    /**
     * The owning tenant's public terms for an **accountless** visitor to accept **client-side** (uc.sharing.share-accept,
     * ADR 0035 §3 — the accountless half of the two-tier gate). Reuses [activeShare] so an unknown/disabled/expired
     * share is the **same non-enumerable 404** as [resolve]: the caller passes only the `shareId` they already hold,
     * and the opaque `tenantId` is only *returned* (the client's localStorage key) — **no `tenantId` existence oracle**.
     * The terms are public operational content, so this is independent of the share password. **No acceptance is
     * recorded** — the server keeps nothing about the accountless visitor.
     *
     * Honest about its scope: what this serves is an **operational gate, not enforcement**. The client stores
     * acceptance in localStorage, which an accountless visitor can bypass (devtools / a fresh profile / resolving the
     * share directly). It records + surfaces consent; it does not *prevent* access. The legally-meaningful protection
     * is the terms' own content (CSAM/illegal-content + review-and-removal) and the decryptable-on-report path, not
     * this prompt. A tenant with no published terms yields `termsVersion` 0, and the client then never gates.
     */
    fun shareTerms(shareId: String): ShareTermsOutcome {
        val share = activeShare(shareId) ?: return ShareTermsOutcome.NotFound
        // The share→tenant hop: share.scopeId → the scope's tenant (a PERSONAL scope still resolves its tenant;
        // ADR 0029 §6). Defensive null (a share always has a scope+tenant by FK) → the same non-enumerable 404.
        val tenantId = scopes.scopeTenantId(share.scopeId) ?: return ShareTermsOutcome.NotFound
        val terms = tenantTerms.guestTerms(tenantId)
        return ShareTermsOutcome.Found(tenantId, terms?.version ?: 0, terms?.terms?.de, terms?.terms?.en)
    }

    // --- contributor / uploader (cr-0101, cr-0102) -----------------------------

    /** Name yourself on a contributable share (CONTRIBUTOR/UPLOADER): seal-name in, an anonymous identity + token out. */
    fun createIdentity(
        shareId: String, pwToken: String?, writeToken: String?, identityId: String, sealedDisplayName: String,
        wrappedIdentityKey: String?,
    ): CreateIdentityOutcome {
        val share = activeShare(shareId) ?: return CreateIdentityOutcome.NotFound
        when (passwordCheck(share, pwToken)) { // cr-0164: the password gate fronts the write path, before the role check
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
        // ADR 0031 point 7: an UPLOADER identity seals under its own key and must hand it over wrapped to the drop
        // key, or nobody could ever open what it uploads; a CONTRIBUTOR seals under `K_mem` and has none to hand.
        val uploader = share.role == ShareEntity.UPLOADER
        if (uploader == wrappedIdentityKey.isNullOrEmpty()) return CreateIdentityOutcome.InvalidRequest
        // The only guest call that writes a row before any upload exists — bounded per share (ADR 0051,
        // `@RateLimit(key = SHARE)`), after the authorization gates so an unauthorized caller spends nothing.
        if (!rateLimiter.allowIdentity(shareId)) return CreateIdentityOutcome.RateLimited
        // The client-minted identity id (ADR 0072): the sealed display name is bound to it.
        return createOrConflict(conflict = { if (store.identityIdTaken(identityId)) CreateIdentityOutcome.IdTaken else null }) {
            store.createIdentity(identityId, shareId, sealedDisplayName, wrappedIdentityKey)
                ?.let { CreateIdentityOutcome.Created(identityId, it) } ?: CreateIdentityOutcome.IdTaken
        }
    }

    /** Start a contributor upload — gated on the share (contributable + enabled) + a valid token, then presign. */
    fun startUploads(shareId: String, pwToken: String?, contributorToken: String, declares: List<UploadDeclare>, domain: String?): ShareUploadOutcome {
        val share = activeShare(shareId) ?: return ShareUploadOutcome.NotFound
        when (passwordCheck(share, pwToken)) { // cr-0164: password gate before the role/token check
            PwCheck.MISSING -> return ShareUploadOutcome.NotFound
            PwCheck.WRONG -> return ShareUploadOutcome.PasswordIncorrect
            PwCheck.TOO_MANY -> return ShareUploadOutcome.RateLimited // cr-0165
            PwCheck.OK -> {}
        }
        if (!ShareAccessPolicy.canUpload(share.role)) return ShareUploadOutcome.NotContributable
        val identity = store.findIdentity(shareId, contributorToken) ?: return ShareUploadOutcome.NotContributable
        if (!rateLimiter.allowUpload(shareId)) return ShareUploadOutcome.RateLimited
        return ShareUploadOutcome.Results(mediaHandler.startForLink(share.scopeId, share.id, identity.id, declares, domain))
    }

    /**
     * Sign the next window of a contributor's open uploads — the link's whole gate again (active share, password,
     * upload role, token), because a window is where a revoked, expired or disabled link stops an upload already in
     * flight (ADR 0013 §2). It does **not** spend [ShareRateLimiter.allowUpload]: that budget counts the writes that
     * create sessions and rows, and a window creates neither. Only the identity that opened a session gets its window.
     */
    fun windowUploads(shareId: String, pwToken: String?, contributorToken: String, requests: List<WindowRequest>, domain: String?): ShareWindowOutcome {
        val share = activeShare(shareId) ?: return ShareWindowOutcome.NotFound
        when (passwordCheck(share, pwToken)) {
            PwCheck.MISSING -> return ShareWindowOutcome.NotFound
            PwCheck.WRONG -> return ShareWindowOutcome.PasswordIncorrect
            PwCheck.TOO_MANY -> return ShareWindowOutcome.RateLimited
            PwCheck.OK -> {}
        }
        if (!ShareAccessPolicy.canUpload(share.role)) return ShareWindowOutcome.NotContributable
        val identity = store.findIdentity(shareId, contributorToken) ?: return ShareWindowOutcome.NotContributable
        return ShareWindowOutcome.Results(mediaHandler.windowForScope(share.scopeId, UploadOpener.Contributor(identity.id), requests, domain))
    }

    /** Complete a contributor upload — attach to the share's own Memory node, stamped with the identity. */
    fun completeUploads(shareId: String, pwToken: String?, contributorToken: String, items: List<ShareCompleteItem>): ShareCompleteOutcome {
        val share = activeShare(shareId) ?: return ShareCompleteOutcome.NotFound
        when (passwordCheck(share, pwToken)) { // cr-0164: password gate before the role/token check
            PwCheck.MISSING -> return ShareCompleteOutcome.NotFound
            PwCheck.WRONG -> return ShareCompleteOutcome.PasswordIncorrect
            PwCheck.TOO_MANY -> return ShareCompleteOutcome.RateLimited // cr-0165
            PwCheck.OK -> {}
        }
        if (!ShareAccessPolicy.canUpload(share.role)) return ShareCompleteOutcome.NotContributable
        val identity = store.findIdentity(shareId, contributorToken) ?: return ShareCompleteOutcome.NotContributable
        // Same per-share upload budget as the start call (ADR 0051, `@RateLimit(key = SHARE)`): the two are one
        // contributor write flow, and attaching is what actually creates the media rows.
        if (!rateLimiter.allowUpload(shareId)) return ShareCompleteOutcome.RateLimited
        val completeItems = items.map {
            CompleteItem(
                mediaId = it.mediaId,
                nodeId = share.nodeId, // contributors attach to the shared Memory root — server-set, not client-chosen
                originalUploadId = it.originalUploadId,
                derivatives = it.derivatives,
                wrappedMeta = it.wrappedMeta,
                wrappedExif = it.wrappedExif,
            )
        }
        return ShareCompleteOutcome.Results(mediaHandler.completeForNode(share.scopeId, completeItems, UploadOpener.Contributor(identity.id)))
    }

    /**
     * Delete one of your own contributions (cr-0102 delete-own). Gated on the share (contributable + enabled) +
     * a valid token; the store authorizes the delete by a **single-row ownership** check (the media's
     * `share_identity_id` equals the token's identity), never gallery filtering, and puts its blob objects on the
     * reclaim list (ADR 0062 — `StoreReclaimSweep` deletes them). A foreign/unknown media is a non-enumerable 404.
     */
    fun deleteMedia(shareId: String, pwToken: String?, contributorToken: String, mediaId: String): ShareDeleteOutcome {
        val share = activeShare(shareId) ?: return ShareDeleteOutcome.NotFound
        when (passwordCheck(share, pwToken)) { // cr-0164: password gate before the role/token check
            PwCheck.MISSING -> return ShareDeleteOutcome.NotFound
            PwCheck.WRONG -> return ShareDeleteOutcome.PasswordIncorrect
            PwCheck.TOO_MANY -> return ShareDeleteOutcome.RateLimited // cr-0165
            PwCheck.OK -> {}
        }
        if (!ShareAccessPolicy.canUpload(share.role)) return ShareDeleteOutcome.NotContributable
        val identity = store.findIdentity(shareId, contributorToken) ?: return ShareDeleteOutcome.NotContributable
        if (!store.deleteContributedMedia(share.scopeId, mediaId, identity.id)) return ShareDeleteOutcome.NotFound
        return ShareDeleteOutcome.Done
    }

    fun resolveBlob(shareId: String, blobId: String, pwToken: String?, domain: String?): ShareBlobOutcome {
        val share = activeShare(shareId) ?: return ShareBlobOutcome.NotFound
        when (passwordCheck(share, pwToken)) { // cr-0118: no bytes without the password (missing → 404, wrong → 403)
            PwCheck.MISSING -> return ShareBlobOutcome.NotFound
            PwCheck.WRONG -> return ShareBlobOutcome.PasswordIncorrect
            PwCheck.TOO_MANY -> return ShareBlobOutcome.RateLimited // cr-0165
            PwCheck.OK -> {}
        }
        // A write-only role has NO download capability (ADR 0031) — an UPLOADER share serves no blob, even for an
        // in-subtree id. This makes "cannot see" a real server gate, not just id-secrecy (the resolve returns none).
        if (!ShareAccessPolicy.canView(share.role)) return ShareBlobOutcome.NotFound
        val blob = store.authorizedBlob(share.scopeId, share.nodeId, blobId) ?: return ShareBlobOutcome.NotFound
        val gets = blobStore.presignGets(blob.storageKey, blob.chunkCount, domain) ?: return ShareBlobOutcome.StoreUnavailable
        return ShareBlobOutcome.Found(gets)
    }

    /** The instance capability a role's public share needs (ADR 0032): UPLOADER → the uploader-links toggle,
     *  VIEWER/CONTRIBUTOR → the public-links toggle. Kept coarser than the role (2 capabilities, 3 roles). */
    private fun capabilityFor(role: String): String =
        if (role == ShareEntity.UPLOADER) CapabilityIds.SHARING_UPLOADER_LINKS else CapabilityIds.SHARING_PUBLIC_LINKS

    private enum class PwCheck { OK, MISSING, WRONG, TOO_MANY }

    /** cr-0118 (ADR 0037): the access-control password gate. A non-password share is always OK; a password share
     *  needs the guest's `pwToken` (base64) to `SHA-256`-hash to the stored `pw_verifier` (constant-time compare).
     *  No token → MISSING (locked); a mismatch or a malformed token → WRONG. The server runs **no** Argon2id (the
     *  KDF is client-side) and never sees the raw password.
     *
     *  cr-0165: a **wrong** attempt consumes one per-share slot of the brute-force bound; once the window's cap is
     *  exceeded it returns TOO_MANY (→ 429) instead of WRONG (→ 403). A **correct** password is admitted *before*
     *  the counter is touched, so a legitimate guest is never locked out; MISSING (the challenge fetch) never spends
     *  budget. This is the online bound ADR 0037 relies on, keyed per share (not per IP — a proxy collapses guest IPs). */
    private fun passwordCheck(share: ShareEntity, pwToken: String?): PwCheck {
        if (!share.passwordRequired) return PwCheck.OK
        if (pwToken.isNullOrBlank()) return PwCheck.MISSING
        val verifier = share.pwVerifier ?: return PwCheck.MISSING
        if (matchesVerifier(pwToken, verifier)) return PwCheck.OK // a malformed token is a wrong attempt // correct password always passes, even past an exhausted budget
        return if (passwordAttemptLimiter.allow(share.id)) PwCheck.WRONG else PwCheck.TOO_MANY
    }

    /** ADR 0076: [writeToken] is the preimage of the share's write verifier. No verifier, no token, a wrong or a
     *  malformed one — all false. No attempt limiter: the token has 256 bits. */
    private fun holdsTheKey(share: ShareEntity, writeToken: String?): Boolean {
        val verifier = share.writeVerifier ?: return false
        return !writeToken.isNullOrBlank() && matchesVerifier(writeToken, verifier)
    }

    /** `base64(SHA-256(base64-decoded token)) == verifier`, compared in constant time; a token that is not base64
     *  matches nothing. The one compare behind both client-derived bearer proofs — the password token and the write
     *  token. */
    private fun matchesVerifier(token: String, verifier: String): Boolean = try {
        val digest = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(Base64.getDecoder().decode(token)))
        MessageDigest.isEqual(digest.toByteArray(), verifier.toByteArray())
    } catch (e: IllegalArgumentException) {
        false
    }

    /**
     * A share that exists, is enabled, has not expired, and whose space serves guests — else null (the single
     * non-enumerable 404 gate). A space being deleted or a suspended tenant answers exactly as a revoked link does:
     * a guest is no member and learns neither state, and nothing is written onto the share, so it serves again
     * unchanged after a restore or a reactivation (uc.spaces.scope-access A4).
     */
    private fun activeShare(shareId: String): ShareEntity? {
        val share = store.find(shareId) ?: return null
        if (!share.enabled) return null
        val expiry = share.expiresAt
        if (expiry != null && !expiry.isAfter(Instant.now())) return null
        if (!access.isLive(share.scopeId)) return null
        return share
    }
}
