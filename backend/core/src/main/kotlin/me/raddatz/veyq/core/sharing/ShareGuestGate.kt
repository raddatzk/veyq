package me.raddatz.veyq.core.sharing

import me.raddatz.veyq.core.content.NodesStore
import me.raddatz.veyq.core.membership.ScopeAccess
import me.raddatz.veyq.core.platform.capabilities.CapabilityRegistry
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
     * delete committing in between would be missed (`GuestCompleteAfterDeleteRaceSliceTest`; `GuestWriteRaceSliceTest`
     * holds its delete uncommitted, so an early check waits on the same lock and stays green there).
     */
    class Admitted(
        val scopeId: String,
        val rootNodeId: String,
        val rootKind: String,
        val identityId: String,
        /** The link's role as the gate read it — `READ_WRITE` or `WRITE_ONLY` (a `WRITE_ONLY` guest writes only into
         *  the root or inside its own identity folder there, [ShareGuestGate.target]). */
        val role: String,
        val admitted: () -> Boolean,
    ) : ShareWriteGate

    /** Unknown, disabled, expired or deleted share, a space that serves no guests, a role whose instance capability
     *  is switched off, or a password-protected share with no token — the non-enumerable 404. */
    object NotFound : ShareWriteGate
    /** Password-protected + a wrong token (cr-0164) — a 403. */
    object PasswordIncorrect : ShareWriteGate
    /** Too many wrong password attempts (cr-0165), or the per-share upload budget spent (ADR 0051) — a 429. */
    object RateLimited : ShareWriteGate
    /** The mode takes no guest writes, the contributor token names no identity of this share, or the root is gone —
     *  a 403. */
    object NotContributable : ShareWriteGate
}

/**
 * What [ShareGuestGate.target] answers: the node a guest's upload is for (ADR 0080 decision 6 — a link's role applies
 * to every node below its root as the application allows it).
 */
sealed interface ShareWriteTarget {
    /**
     * The node [nodeId] of [kind] — the application that takes this kind writes into it. [named] is false for the share
     * root taken by default (no node named: every check and answer as before targets existed), true for a node the
     * guest named, which the write checks again under the scope lock ([ShareGuestGate.admittedInto]).
     */
    data class Node(val nodeId: String, val kind: String, val named: Boolean) : ShareWriteTarget
    /** No live node inside the share's subtree has that id — the same for a node outside it as for none (as a guest's
     *  placement answers it: `node_not_found`). */
    object NotFound : ShareWriteTarget
    /** A `WRITE_ONLY` link named a node other than its root outside its guest's own identity folder (its guest holds
     *  no other key below it), or the root is in the trash — `share_not_contributable`, as a guest's tree operations
     *  answer a trashed root. */
    object NotContributable : ShareWriteTarget
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
    /** Unknown, disabled, expired or deleted share, a space that serves no guests, or a role whose instance
     *  capability is switched off — the non-enumerable 404. */
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
 * answering its own refusal: an active share ([activeShare]: its role's instance switch included) → the password →
 * the mode's rights ([ShareAccessPolicy.canView] / [ShareAccessPolicy.canUpload]) → the guest identity's token
 * (writes) → the per-share upload budget, **only where the caller asks** (writes). The budget
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
    private val nodes: NodesStore,
    private val capabilities: CapabilityRegistry,
) {

    /**
     * The gate for a guest write on [shareId] by the identity [contributorToken] names. [spendUploadBudget] spends one
     * token of the share's upload budget ([ShareRateLimiter.allowUpload]) after every other check has passed, so an
     * unauthorized caller spends nothing. Core's guest upload start passes false and spends the token itself, after its
     * own request checks (`SharingHandler.startUploads`, inbox #367): its role check needs the target this gate admits. [onlyRole], when set, narrows the uploading roles to that one (core's guest
     * tree operations take only `READ_WRITE`, ADR 0080 decision 6) — refused as [ShareWriteGate.NotContributable], before
     * the token and the budget.
     */
    fun writeGate(
        shareId: String, pwToken: String?, contributorToken: String, spendUploadBudget: Boolean, onlyRole: String? = null,
    ): ShareWriteGate {
        val share = activeShare(shareId) ?: return ShareWriteGate.NotFound
        when (passwordCheck(share, pwToken)) { // cr-0164: the password gate fronts the write path, before the role check
            PwCheck.MISSING -> return ShareWriteGate.NotFound // no token → non-enumerable 404 (no role reveal)
            PwCheck.WRONG -> return ShareWriteGate.PasswordIncorrect
            PwCheck.TOO_MANY -> return ShareWriteGate.RateLimited // cr-0165
            PwCheck.OK -> {}
        }
        if (!ShareAccessPolicy.canUpload(share.role)) return ShareWriteGate.NotContributable
        if (onlyRole != null && share.role != onlyRole) return ShareWriteGate.NotContributable
        val identity = store.findIdentity(shareId, contributorToken) ?: return ShareWriteGate.NotContributable
        if (spendUploadBudget && !rateLimiter.allowUpload(shareId)) return ShareWriteGate.RateLimited
        // The root's kind picks the application that writes; a root that is gone has none (not contributable).
        val rootKind = store.findNode(share.nodeId)?.kind ?: return ShareWriteGate.NotContributable
        return ShareWriteGate.Admitted(share.scopeId, share.nodeId, rootKind, identity.id, share.role) { store.admitsGuests(shareId) }
    }

    /**
     * The node a guest's upload past [gated] is for. [nodeId] null is the share root as stored — trashed or not, so an
     * upload that names no node is decided exactly as before targets existed (the application answers a trashed root).
     * A named node must be, in this order (none answering by anything outside the subtree): allowed by the role — a
     * `WRITE_ONLY` link only its root, or a node inside its guest's own live identity folder in the root (ADR 0080
     * decision 11, [writeOnlyBoundary]); the root live; the node live and inside the share's subtree — for a
     * `WRITE_ONLY` link, inside that identity folder (an upward walk, [NodesStore.withinSubtree]). Which kinds take uploads is not this gate's question but its caller's, which
     * dispatches by [ShareWriteTarget.Node.kind] (`ShareGuestWrites`, `UploadAdmission`, the application's completion).
     *
     * A plain read: a start or a window writes nothing onto the node, and the write that does (the completion) asks
     * again under the scope lock ([admittedInto]).
     */
    fun target(gated: ShareWriteGate.Admitted, nodeId: String?): ShareWriteTarget {
        if (nodeId == null) return ShareWriteTarget.Node(gated.rootNodeId, gated.rootKind, named = false)
        if (gated.role == ShareEntity.WRITE_ONLY && nodeId != gated.rootNodeId) {
            val folder = writeOnlyBoundary(gated) ?: return ShareWriteTarget.NotContributable
            if (!nodes.withinSubtree(gated.scopeId, folder, nodeId)) return ShareWriteTarget.NotContributable
        }
        if (nodes.findInScope(gated.scopeId, gated.rootNodeId) == null) return ShareWriteTarget.NotContributable
        val node = liveInside(gated, nodeId) ?: return ShareWriteTarget.NotFound
        return ShareWriteTarget.Node(node.id, node.kind, named = true)
    }

    /**
     * The subtree a `WRITE_ONLY` guest past [gated] may name nodes in below its root: its own identity folder there
     * (ADR 0080 decision 11, `veyq_node.share_identity_id`), live — or null when it has none, or a member trashed it.
     * Nothing ever moves into an identity folder (`NodesHandler`), so only a trash or a move out changes the answer, and
     * [admittedInto] asks it again under the scope lock.
     */
    fun writeOnlyBoundary(gated: ShareWriteGate.Admitted): String? =
        nodes.identityFolder(gated.rootNodeId, gated.identityId)?.takeIf { it.deletedAt == null && it.shareIdentityId != null }?.id

    /**
     * The write-side re-check of an upload into [target], handed out **unevaluated** like [ShareWriteGate.Admitted.admitted]
     * and called first in the transaction that writes. **Lock order**, as `ShareNodesHandler`'s: (1) the scope row — the
     * lock every tree writer takes first, so a member's trash of the target, or a move of it or an ancestor out of the
     * subtree, has either committed and is seen (READ COMMITTED, ADR 0078) or waits until this write is done; (2) the
     * share row ([ShareWriteGate.Admitted.admitted]: the link still live under the lock its revoke and delete hold);
     * (3) for a named target, the root live and the target live and inside the subtree (its kind never changes; the
     * caller checked it). The root taken by default passes the same two locks and nothing more, so its answers stay
     * what they were. Never the share row first: a re-activation holds the scope and waits for the share row.
     *
     * **The scope lock also when no node is named** (inbox #360, decided 2026-10-09): skipping it there would order
     * nothing wrongly, but it would buy no parallelism where it is wanted. Many guests uploading through one link name
     * no node, so they all complete into its root, and every such attach locks that root's node row anyway (Memories'
     * primary-bucket resolve and revision bump, in the same transaction) — they run one after another with or
     * without the scope lock. What the lock adds is waiting for tree writes and for completes into other nodes of the
     * scope, one short transaction each; Depot names its node on every complete, so it takes the lock regardless. One
     * lock order for every complete is worth more than that.
     */
    fun admittedInto(gated: ShareWriteGate.Admitted, target: ShareWriteTarget.Node): () -> Boolean = {
        nodes.lockScope(gated.scopeId)
        gated.admitted() &&
            (!target.named || (nodes.findInScope(gated.scopeId, gated.rootNodeId) != null && liveInside(gated, target.nodeId) != null)) &&
            // A WRITE_ONLY guest's named node: still inside its own live identity folder (a member may have trashed it
            // or moved it out of the drop box meanwhile, ADR 0080 decision 11).
            (!target.named || target.nodeId == gated.rootNodeId || gated.role != ShareEntity.WRITE_ONLY ||
                writeOnlyBoundary(gated)?.let { nodes.withinSubtree(gated.scopeId, it, target.nodeId) } == true)
    }

    /** [nodeId] as a live node of the share's scope inside its subtree (the root included), or null. */
    private fun liveInside(gated: ShareWriteGate.Admitted, nodeId: String) =
        nodes.findInScope(gated.scopeId, nodeId)?.takeIf { nodes.withinSubtree(gated.scopeId, gated.rootNodeId, nodeId) }

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
     * A share that exists, is enabled, has not expired, whose space serves guests, and whose role's instance capability
     * is on — else null (the single non-enumerable 404 gate). A space being deleted or a suspended tenant answers
     * exactly as a revoked link does: a guest is no member and learns neither state, and nothing is written onto the
     * share, so it serves again unchanged after a restore or a reactivation (uc.spaces.scope-access A4).
     *
     * **The share switch** (ADR 0088 decision 5): while `core.public-shares`, `.contribute` or `.drop-box` is
     * effectively off ([ShareAccessPolicy.capabilityFor], the AND along the path is [CapabilityRegistry]'s), a link of
     * that role is refused here, so every guest path — core's resolve, terms, blob, identity, upload start and window,
     * tree operations, and every application's read and write through [viewGate] / [writeGate] — answers it exactly
     * as an unknown link, and a guest learns nothing of the instance's policy. Before the password: a locked challenge
     * would tell the guest the link exists. Nothing is written, so switching back on serves the same link again. No
     * re-check under the share-row lock ([SharingStore.admitsGuests]): a switch is no row the write could lock against,
     * and each later step of a guest write (a window, the complete) passes this gate again.
     */
    internal fun activeShare(shareId: String): ShareEntity? {
        val share = store.find(shareId) ?: return null
        if (!share.enabled) return null
        val expiry = share.expiresAt
        if (expiry != null && !expiry.isAfter(Instant.now())) return null
        if (!access.isLive(share.scopeId)) return null
        if (!capabilities.isActive(ShareAccessPolicy.capabilityFor(share.role))) return null
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
