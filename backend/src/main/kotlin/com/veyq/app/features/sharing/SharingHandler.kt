package com.veyq.app.features.sharing

import com.veyq.app.features.admin.tenant.TenantTermsHandler
import com.veyq.app.features.content.NodeEntity
import com.veyq.app.features.media.CompleteItem
import com.veyq.app.features.media.CompleteItemResult
import com.veyq.app.features.media.DerivativeDeclare
import com.veyq.app.features.media.MediaHandler
import com.veyq.app.features.media.UploadDeclare
import com.veyq.app.features.media.UploadItemResult
import com.veyq.app.features.platform.capabilities.CapabilityIds
import com.veyq.app.features.platform.capabilities.CapabilityRegistry
import com.veyq.app.features.spaces.scope.ScopesStore
import com.veyq.app.shared.blobstore.BlobStore
import com.veyq.app.shared.blobstore.BlobStore.Companion.chunkKey
import com.veyq.app.shared.http.FailureStatus.FORBIDDEN
import com.veyq.app.shared.http.FailureStatus.NOT_FOUND
import com.veyq.app.shared.http.FailureStatus.SERVICE_UNAVAILABLE
import com.veyq.app.shared.http.FailureStatus.UNPROCESSABLE
import com.veyq.app.shared.http.Failures
import com.veyq.app.shared.http.HttpFailure
import com.veyq.app.shared.http.httpFailure
import jakarta.enterprise.context.ApplicationScoped
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64

/** A presigned GET for one chunk (index in chunk order). */
data class PresignRef(val index: Int, val url: String)

/** One contributor upload to complete: the original session + role-tagged derivatives (the shared node is server-set). */
data class ShareCompleteItem(
    val originalUploadId: String,
    val derivatives: List<DerivativeDeclare>,
    val wrappedMeta: String?,
    val wrappedExif: String?,
)

sealed interface CreateShareOutcome {
    data class Created(val shareId: String) : CreateShareOutcome
    /** The sharing capability this role needs is disabled instance-wide (ADR 0032) — a 422, instance policy. */
    object CapabilityDisabled : CreateShareOutcome, HttpFailure by Failures.capabilityDisabled
    /** No such node, or the caller holds no grant for its scope (a 404, no reveal). */
    object NoNode : CreateShareOutcome, HttpFailure by Failures.nodeNotFound
    /** The node is not a MEMORY (only a Memory can be public-shared, ADR 0029 §6) — a 422. */
    object IllegalScope : CreateShareOutcome, HttpFailure by httpFailure("illegal_scope", UNPROCESSABLE)
}

sealed interface ResolveShareOutcome {
    data class Found(val share: ShareEntity, val subtree: SubtreeData, val identities: List<ShareIdentityEntity>) : ResolveShareOutcome
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
    data class Found(val gets: List<PresignRef>) : ShareBlobOutcome
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
    object NotFound : RevokeShareOutcome, HttpFailure by httpFailure("share_not_found", NOT_FOUND)
}

sealed interface UpdateShareOutcome {
    object Done : UpdateShareOutcome
    /** No such share owned by the caller — a non-enumerable 404 (owner-gated, like revoke). */
    object NotFound : UpdateShareOutcome, HttpFailure by httpFailure("share_not_found", NOT_FOUND)
    /** An illegal role change (cr-0119) — `role = UPLOADER`, or a change on an UPLOADER share → 422. */
    object InvalidRole : UpdateShareOutcome, HttpFailure by httpFailure("invalid_role_change", UNPROCESSABLE)
}

sealed interface ReactivateShareOutcome {
    object Done : ReactivateShareOutcome
    /** No such share, or the caller is not a member of its scope — a non-enumerable 404. */
    object NotFound : ReactivateShareOutcome, HttpFailure by httpFailure("share_not_found", NOT_FOUND)
}

sealed interface ShareTermsOutcome {
    /** The owning tenant's public terms — `termsVersion` 0 (with null `de`/`en`) when the operator published none. */
    data class Found(val tenantId: String, val termsVersion: Int, val de: String?, val en: String?) : ShareTermsOutcome
    /** Unknown/disabled/expired share — the same non-enumerable 404 as [ResolveShareOutcome.NotFound]. */
    object NotFound : ShareTermsOutcome, HttpFailure by httpFailure("share_not_found", NOT_FOUND)
}

/**
 * Use-case logic for public viewer shares (cr-0100, ADR 0029). Two authorization surfaces:
 *  - **Curator** (create/list/revoke/reactivate): authenticated + gated to a **MEMORY in a scope the caller is
 *    a member of** (personal, group, or tenant — cr-0104).
 *  - **Guest** (resolve/blob): unauthenticated, keyed only on share state (`enabled` + `expiresAt`) — a
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
) {

    fun create(
        userId: String, nodeId: String, role: String, wrap: String, sealedName: String, expiresAt: Instant?,
        sealedNote: String?, sealedShareKey: String?, sealedShareName: String?,
        passwordRequired: Boolean, pwSalt: String?, pwVerifier: String?, sealedPassword: String?,
    ): CreateShareOutcome {
        // Instance policy first (ADR 0032): the capability this role needs must be active, else 422 — checked
        // before any node/scope lookup so it reads as "the operator turned sharing off", not a scope error.
        if (!capabilities.isActive(capabilityFor(role))) return CreateShareOutcome.CapabilityDisabled
        val node = store.findNode(nodeId) ?: return CreateShareOutcome.NoNode
        if (!store.isMember(userId, node.scopeId)) return CreateShareOutcome.NoNode // no reveal
        // Any member of the node's scope may share (cr-0104) — personal, group, or tenant; only a MEMORY node can
        // be public-shared (ADR 0029 §6, ADR 0030).
        if (node.kind != NodeEntity.MEMORY) return CreateShareOutcome.IllegalScope
        val shareId = store.create(
            userId, node.scopeId, nodeId, role, wrap, sealedName, expiresAt, sealedNote, sealedShareKey, sealedShareName,
            passwordRequired, pwSalt, pwVerifier, sealedPassword,
        )
        return CreateShareOutcome.Created(shareId)
    }

    fun list(userId: String): List<ShareEntity> = store.listByOwner(userId)

    /** Every public share on a Memory node, for the shared-scope manage panel (cr-0104): visible to any **member**
     *  of the node's scope, not only the shares' owners — so a member can see + adopt an orphaned (disabled) share.
     *  Empty when the node is unknown or the caller is not a member (no reveal). The resource tags each row `mine`. */
    fun listForNode(userId: String, nodeId: String): List<ShareEntity> {
        val node = store.findNode(nodeId) ?: return emptyList()
        if (!store.isMember(userId, node.scopeId)) return emptyList()
        return store.listByNode(nodeId)
    }

    fun revoke(userId: String, shareId: String): RevokeShareOutcome =
        if (store.revoke(userId, shareId)) RevokeShareOutcome.Done else RevokeShareOutcome.NotFound

    /** Edit a share in place (cr-0115/cr-0116/cr-0119, ADR 0036) — owner-gated. A share not owned by the caller is a
     *  non-enumerable 404. The `K_share`-sealed ciphertexts (re-sealed client-side) full-replace the note + name;
     *  a non-null `role` changes the grant in place (VIEWER ↔ CONTRIBUTOR only — UPLOADER, either as the target or
     *  as the share's current role, is `InvalidRole` → 422). Role change is a plaintext authz change: no re-crypto,
     *  the wrap/`K_share`/link are untouched. */
    fun updateShare(
        userId: String, shareId: String, sealedNote: String?, sealedShareName: String?, role: String?,
        expiresAt: Instant?, passwordRequired: Boolean?, pwSalt: String?, pwVerifier: String?, sealedPassword: String?,
    ): UpdateShareOutcome =
        when (store.updateShare(userId, shareId, sealedNote, sealedShareName, role, expiresAt, passwordRequired, pwSalt, pwVerifier, sealedPassword)) {
            SharingStore.UpdateResult.DONE -> UpdateShareOutcome.Done
            SharingStore.UpdateResult.NOT_FOUND -> UpdateShareOutcome.NotFound
            SharingStore.UpdateResult.INVALID_ROLE -> UpdateShareOutcome.InvalidRole
        }

    /** Re-activate a soft-revoked share and adopt it (cr-0104): any current member of the share's scope may flip
     *  it back on, which transfers ownership to them. A non-member / unknown share is a non-enumerable 404. */
    fun reactivate(userId: String, shareId: String): ReactivateShareOutcome =
        if (store.reactivate(userId, shareId)) ReactivateShareOutcome.Done else ReactivateShareOutcome.NotFound

    /**
     * Resolve a share for a guest. For a **viewable** role (VIEWER/CONTRIBUTOR) the full sealed subtree + the
     * contributor identities cross; for an **UPLOADER** (write-only drop-box, ADR 0031) the resolve is gated to
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
            return ResolveShareOutcome.Found(share, store.subtree(share.scopeId, share.nodeId), store.listIdentities(shareId))
        }
        // UPLOADER: the minimal capability — no nodes, no media, no identities. The client seals its uploads under
        // `K_mem` (from the share `wrap`) and shows the title (from `sealedName`); it needs nothing from the subtree.
        // Returning even the root node would hand the uploader that node's `K_mem`-decryptable `wrappedMeta`.
        return ResolveShareOutcome.Found(share, SubtreeData(emptyList(), emptyList()), emptyList())
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
    fun createIdentity(shareId: String, pwToken: String?, sealedDisplayName: String): CreateIdentityOutcome {
        val share = activeShare(shareId) ?: return CreateIdentityOutcome.NotFound
        when (passwordCheck(share, pwToken)) { // cr-0164: the password gate fronts the write path, before the role check
            PwCheck.MISSING -> return CreateIdentityOutcome.NotFound // no token → non-enumerable 404 (no role reveal)
            PwCheck.WRONG -> return CreateIdentityOutcome.PasswordIncorrect
            PwCheck.TOO_MANY -> return CreateIdentityOutcome.RateLimited // cr-0165
            PwCheck.OK -> {}
        }
        if (!ShareAccessPolicy.canUpload(share.role)) return CreateIdentityOutcome.NotContributable
        // The only guest call that writes a row before any upload exists — bounded per share (ADR 0051,
        // `@RateLimit(key = SHARE)`), after the authorization gates so an unauthorized caller spends nothing.
        if (!rateLimiter.allowIdentity(shareId)) return CreateIdentityOutcome.RateLimited
        val (identityId, token) = store.createIdentity(shareId, sealedDisplayName)
        return CreateIdentityOutcome.Created(identityId, token)
    }

    /** Start a contributor upload — gated on the share (contributable + enabled) + a valid token, then presign. */
    fun startUploads(shareId: String, pwToken: String?, contributorToken: String, declares: List<UploadDeclare>): ShareUploadOutcome {
        val share = activeShare(shareId) ?: return ShareUploadOutcome.NotFound
        when (passwordCheck(share, pwToken)) { // cr-0164: password gate before the role/token check
            PwCheck.MISSING -> return ShareUploadOutcome.NotFound
            PwCheck.WRONG -> return ShareUploadOutcome.PasswordIncorrect
            PwCheck.TOO_MANY -> return ShareUploadOutcome.RateLimited // cr-0165
            PwCheck.OK -> {}
        }
        if (!ShareAccessPolicy.canUpload(share.role)) return ShareUploadOutcome.NotContributable
        if (store.findIdentity(shareId, contributorToken) == null) return ShareUploadOutcome.NotContributable
        if (!rateLimiter.allowUpload(shareId)) return ShareUploadOutcome.RateLimited
        return ShareUploadOutcome.Results(mediaHandler.startForScope(share.scopeId, declares))
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
                nodeId = share.nodeId, // contributors attach to the shared Memory root — server-set, not client-chosen
                originalUploadId = it.originalUploadId,
                derivatives = it.derivatives,
                wrappedMeta = it.wrappedMeta,
                wrappedExif = it.wrappedExif,
            )
        }
        return ShareCompleteOutcome.Results(mediaHandler.completeForNode(share.scopeId, completeItems, identity.id))
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

    fun resolveBlob(shareId: String, blobId: String, pwToken: String?): ShareBlobOutcome {
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
        if (!blobStore.canPresign) return ShareBlobOutcome.StoreUnavailable
        val gets = (0 until blob.chunkCount).map { i -> PresignRef(i, blobStore.presignGet(chunkKey(blob.storageKey, i))!!) }
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

    /** A share that exists, is enabled, and has not expired — else null (the single non-enumerable 404 gate). */
    private fun activeShare(shareId: String): ShareEntity? {
        val share = store.find(shareId) ?: return null
        if (!share.enabled) return null
        val expiry = share.expiresAt
        if (expiry != null && !expiry.isAfter(Instant.now())) return null
        return share
    }
}
