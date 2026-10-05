package me.raddatz.veyq.core.sharing

import me.raddatz.veyq.core.membership.ScopeAccess
import jakarta.enterprise.context.ApplicationScoped
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64

/** What [ShareGuestGate.writeGate] answers. Each refusal is the outcome the guest write calls answered before the
 *  gate was a service; the caller maps it onto its own response, so no status moves. */
sealed interface ShareWriteGate {
    /**
     * The guest may write. [rootKind] is the root node's kind as stored — read as the writer is picked, so a root in
     * the trash still names its kind (the application decides what a trashed root takes). [admitted] is the link's
     * re-check under the share-row lock its delete holds ([SharingStore.admitsGuests]), handed out **unevaluated**:
     * the application calls it first inside the transaction that writes. Asked here, before that transaction, a
     * delete committing in between would be missed (`GuestWriteRaceSliceTest`).
     */
    class Admitted(
        val scopeId: String,
        val rootNodeId: String,
        val rootKind: String,
        val identityId: String,
        val admitted: () -> Boolean,
    ) : ShareWriteGate

    /** Unknown, disabled, expired or deleted share, a space that serves no guests, or a password-protected share
     *  with no token — the non-enumerable 404. */
    object NotFound : ShareWriteGate
    /** Password-protected + a wrong token (cr-0164) — a 403. */
    object PasswordIncorrect : ShareWriteGate
    /** Too many wrong password attempts (cr-0165), or the per-share upload budget spent (ADR 0051) — a 429. */
    object RateLimited : ShareWriteGate
    /** The mode takes no guest writes, the contributor token names no identity of this share, or the root is gone —
     *  a 403. */
    object NotContributable : ShareWriteGate
}

/** What [ShareGuestGate.viewGate] answers. */
sealed interface ShareViewGate {
    /**
     * The guest may read. [liveNodeIds] is the shared subtree's live nodes, by core's own live predicate
     * ([SharingStore.liveSubtreeIds]); empty when the root is in the trash, and [rootKind] is then null, as the resolve
     * names it. No identity set: what an application serves about its guests is the application's (ADR 0030 point 8).
     */
    data class Viewable(
        val scopeId: String,
        val rootNodeId: String,
        val rootKind: String?,
        val liveNodeIds: List<String>,
    ) : ShareViewGate

    /** A write-only mode ([ShareAccessPolicy.canView] false, ADR 0031): the guest is served nothing of the subtree. */
    object WriteOnly : ShareViewGate
    /** Unknown, disabled, expired or deleted share, or a space that serves no guests — the non-enumerable 404. */
    object NotFound : ShareViewGate
    /** Password-protected + no token (cr-0118): the caller answers as its own locked or not-found shape. */
    object Locked : ShareViewGate
    /** Password-protected + a wrong token (cr-0118) — a 403. */
    object PasswordIncorrect : ShareViewGate
    /** Too many wrong password attempts on this share (cr-0165) — a 429. */
    object RateLimited : ShareViewGate
}

/**
 * Core's guest gate on a public link (ADR 0079 § Application guest operations, ADR 0029/0030/0031): an application
 * calls it before its own guest read or write, and does its work only past it. The steps run in this order, each
 * answering its own refusal: an active share → the password → the mode's capability ([ShareAccessPolicy]) → the
 * guest identity's token (writes) → the per-share upload budget, **only where the caller asks** (writes). The budget
 * is the caller's to ask for because the operations differ: completing an upload creates rows and spends it, a
 * delete of one's own contribution does not (`@RateLimitExempt` on `deleteShareMedia`).
 *
 * **Visibility.** This is the one core sharing surface an application is to call for its guest operations. Every
 * core class is public today, [SharingStore] included; once applications reach sharing only through this gate, a
 * later `internal`/`core.api` cut can hide the store. The preamble the gate shares with core's own guest calls
 * (resolve, terms, blob download, identity creation) is `internal`, so no application builds a gate of its own from
 * the pieces.
 */
@ApplicationScoped
class ShareGuestGate(
    private val store: SharingStore,
    private val rateLimiter: ShareRateLimiter,
    private val passwordAttemptLimiter: SharePasswordAttemptLimiter,
    private val access: ScopeAccess,
) {

    /**
     * The gate for a guest write on [shareId] by the identity [contributorToken] names. [spendUploadBudget] spends one
     * token of the share's upload budget ([ShareRateLimiter.allowUpload]) after every other check has passed, so an
     * unauthorized caller spends nothing.
     */
    fun writeGate(shareId: String, pwToken: String?, contributorToken: String, spendUploadBudget: Boolean): ShareWriteGate {
        val share = activeShare(shareId) ?: return ShareWriteGate.NotFound
        when (passwordCheck(share, pwToken)) { // cr-0164: the password gate fronts the write path, before the role check
            PwCheck.MISSING -> return ShareWriteGate.NotFound // no token → non-enumerable 404 (no role reveal)
            PwCheck.WRONG -> return ShareWriteGate.PasswordIncorrect
            PwCheck.TOO_MANY -> return ShareWriteGate.RateLimited // cr-0165
            PwCheck.OK -> {}
        }
        if (!ShareAccessPolicy.canUpload(share.role)) return ShareWriteGate.NotContributable
        val identity = store.findIdentity(shareId, contributorToken) ?: return ShareWriteGate.NotContributable
        if (spendUploadBudget && !rateLimiter.allowUpload(shareId)) return ShareWriteGate.RateLimited
        // The root's kind picks the application that writes; a root that is gone has none (not contributable).
        val rootKind = store.findNode(share.nodeId)?.kind ?: return ShareWriteGate.NotContributable
        return ShareWriteGate.Admitted(share.scopeId, share.nodeId, rootKind, identity.id) { store.admitsGuests(shareId) }
    }

    /** The gate for a guest read on [shareId]: past it, the shared subtree's live nodes for a viewable mode. */
    fun viewGate(shareId: String, pwToken: String?): ShareViewGate {
        val share = activeShare(shareId) ?: return ShareViewGate.NotFound
        when (passwordCheck(share, pwToken)) { // cr-0118: nothing is served without a valid password token
            PwCheck.MISSING -> return ShareViewGate.Locked
            PwCheck.WRONG -> return ShareViewGate.PasswordIncorrect
            PwCheck.TOO_MANY -> return ShareViewGate.RateLimited // cr-0165
            PwCheck.OK -> {}
        }
        if (!ShareAccessPolicy.canView(share.role)) return ShareViewGate.WriteOnly
        // As the resolve reads it: a trashed root names no kind and serves no node.
        val rootKind = store.findNode(share.nodeId)?.takeIf { it.deletedAt == null }?.kind
        return ShareViewGate.Viewable(share.scopeId, share.nodeId, rootKind, store.liveSubtreeIds(share.scopeId, share.nodeId))
    }

    internal enum class PwCheck { OK, MISSING, WRONG, TOO_MANY }

    /**
     * A share that exists, is enabled, has not expired, and whose space serves guests — else null (the single
     * non-enumerable 404 gate). A space being deleted or a suspended tenant answers exactly as a revoked link does:
     * a guest is no member and learns neither state, and nothing is written onto the share, so it serves again
     * unchanged after a restore or a reactivation (uc.spaces.scope-access A4).
     */
    internal fun activeShare(shareId: String): ShareEntity? {
        val share = store.find(shareId) ?: return null
        if (!share.enabled) return null
        val expiry = share.expiresAt
        if (expiry != null && !expiry.isAfter(Instant.now())) return null
        if (!access.isLive(share.scopeId)) return null
        return share
    }

    /** cr-0118 (ADR 0037): the access-control password gate. A non-password share is always OK; a password share
     *  needs the guest's `pwToken` (base64) to `SHA-256`-hash to the stored `pw_verifier` (constant-time compare).
     *  No token → MISSING (locked); a mismatch or a malformed token → WRONG. The server runs **no** Argon2id (the
     *  KDF is client-side) and never sees the raw password.
     *
     *  cr-0165: a **wrong** attempt consumes one per-share slot of the brute-force bound; once the window's cap is
     *  exceeded it returns TOO_MANY (→ 429) instead of WRONG (→ 403). A **correct** password is admitted *before*
     *  the counter is touched, so a legitimate guest is never locked out; MISSING (the challenge fetch) never spends
     *  budget. This is the online bound ADR 0037 relies on, keyed per share (not per IP — a proxy collapses guest IPs). */
    internal fun passwordCheck(share: ShareEntity, pwToken: String?): PwCheck {
        if (!share.passwordRequired) return PwCheck.OK
        if (pwToken.isNullOrBlank()) return PwCheck.MISSING
        val verifier = share.pwVerifier ?: return PwCheck.MISSING
        if (matchesVerifier(pwToken, verifier)) return PwCheck.OK // a malformed token is a wrong attempt // correct password always passes, even past an exhausted budget
        return if (passwordAttemptLimiter.allow(share.id)) PwCheck.WRONG else PwCheck.TOO_MANY
    }

    /** `base64(SHA-256(base64-decoded token)) == verifier`, compared in constant time; a token that is not base64
     *  matches nothing. The one compare behind both client-derived bearer proofs — the password token and the write
     *  token. */
    internal fun matchesVerifier(token: String, verifier: String): Boolean = try {
        val digest = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(Base64.getDecoder().decode(token)))
        MessageDigest.isEqual(digest.toByteArray(), verifier.toByteArray())
    } catch (e: IllegalArgumentException) {
        false
    }
}
