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
import jakarta.enterprise.context.ApplicationScoped
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
}

/** The shares on one Memory node (cr-0104) — `[]` for a non-member or an unknown node, a refusal only for a member. */
sealed interface NodeSharesOutcome {
    data class Shares(val rows: List<ShareEntity>) : NodeSharesOutcome
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
        passwordRequired: Boolean, pwSalt: String?, pwVerifier: String?, sealedPassword: String?,
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
        // current one is refused — an older one is an epoch a member removed since still holds. Unlocked, like theirs.
        if (sealedShareKeyEpoch != null && sealedShareKeyEpoch != scopes.currentEpoch(node.scopeId)) {
            return CreateShareOutcome.StaleEpoch
        }
        // The client-minted share id (ADR 0072): the recovery wrap and every contributor name are bound to it.
        val created = store.create(
            shareId, userId, node.scopeId, nodeId, role, keys, sealedName, expiresAt, sealedNote, sealedShareKey,
            sealedShareKeyEpoch, sealedShareName,
            passwordRequired, pwSalt, pwVerifier, sealedPassword,
        )
        return when (created) {
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
    fun list(userId: String): List<ShareEntity> =
        store.listByOwner(userId).filter { access.check(userId, it.scopeId, READ) == Access.Ok }

    /** Every public share on a Memory node, for the shared-scope manage panel (cr-0104): visible to any **member**
     *  of the node's scope, not only the shares' owners — so a member can see + adopt an orphaned (disabled) share.
     *  Empty when the node is unknown or the caller is not a member (no reveal); a member of a suspended tenant is
     *  told so. The resource tags each row `mine`. */
    fun listForNode(userId: String, nodeId: String): NodeSharesOutcome {
        val node = store.findNode(nodeId) ?: return NodeSharesOutcome.Shares(emptyList())
        return when (access.check(userId, node.scopeId, READ)) {
            Access.Ok -> NodeSharesOutcome.Shares(store.listByNode(nodeId))
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
     *  (no adoption during the space's deletion grace) and the instance capability for the share's role. */
    fun reactivate(userId: String, shareId: String): ReactivateShareOutcome {
        val share = store.find(shareId) ?: return ReactivateShareOutcome.NotFound
        access.check(userId, share.scopeId, WRITE)
            .refusal(ReactivateShareOutcome.NotFound, ReactivateShareOutcome.Suspended, ReactivateShareOutcome.Deleting)
            ?.let { return it }
        if (!capabilities.isActive(capabilityFor(share.role))) return ReactivateShareOutcome.CapabilityDisabled
        store.reactivate(userId, shareId)
        return ReactivateShareOutcome.Done
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
        shareId: String, pwToken: String?, identityId: String, sealedDisplayName: String, wrappedIdentityKey: String?,
    ): CreateIdentityOutcome {
        val share = activeShare(shareId) ?: return CreateIdentityOutcome.NotFound
        when (passwordCheck(share, pwToken)) { // cr-0164: the password gate fronts the write path, before the role check
            PwCheck.MISSING -> return CreateIdentityOutcome.NotFound // no token → non-enumerable 404 (no role reveal)
            PwCheck.WRONG -> return CreateIdentityOutcome.PasswordIncorrect
            PwCheck.TOO_MANY -> return CreateIdentityOutcome.RateLimited // cr-0165
            PwCheck.OK -> {}
        }
        if (!ShareAccessPolicy.canUpload(share.role)) return CreateIdentityOutcome.NotContributable
        // ADR 0031 point 7: an UPLOADER identity seals under its own key and must hand it over wrapped to the drop
        // key, or nobody could ever open what it uploads; a CONTRIBUTOR seals under `K_mem` and has none to hand.
        val uploader = share.role == ShareEntity.UPLOADER
        if (uploader == wrappedIdentityKey.isNullOrEmpty()) return CreateIdentityOutcome.InvalidRequest
        // The only guest call that writes a row before any upload exists — bounded per share (ADR 0051,
        // `@RateLimit(key = SHARE)`), after the authorization gates so an unauthorized caller spends nothing.
        if (!rateLimiter.allowIdentity(shareId)) return CreateIdentityOutcome.RateLimited
        // The client-minted identity id (ADR 0072): the sealed display name is bound to it.
        val token = store.createIdentity(identityId, shareId, sealedDisplayName, wrappedIdentityKey)
            ?: return CreateIdentityOutcome.IdTaken
        return CreateIdentityOutcome.Created(identityId, token)
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
        val match = try {
            val digest = Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(Base64.getDecoder().decode(pwToken)),
            )
            MessageDigest.isEqual(digest.toByteArray(), verifier.toByteArray())
        } catch (e: IllegalArgumentException) {
            false // malformed base64 token → a wrong attempt
        }
        if (match) return PwCheck.OK // correct password always passes, even past an exhausted budget
        return if (passwordAttemptLimiter.allow(share.id)) PwCheck.WRONG else PwCheck.TOO_MANY
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
