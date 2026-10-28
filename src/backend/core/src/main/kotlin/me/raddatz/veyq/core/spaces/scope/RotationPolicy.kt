package me.raddatz.veyq.core.spaces.scope

import me.raddatz.veyq.core.membership.MembershipEntity
import me.raddatz.veyq.core.membership.SignedGrant

/** The membership facts the rotation decision needs: a member's id and their role (the last-owner guard of a
 *  removal). Lifted out of the persistence row so the decision is testable without a DB. A soft `pendingConsent`
 *  proposal is a member here like any other: it holds the grant its proposer wrapped (ADR 0111 decision 4). */
data class RotationMember(
    val userId: String,
    val role: String = MembershipEntity.MEMBER,
)

/** One client-minted re-grant: the member it is for, the grant with the rotator's signature over it, and the digest of
 *  the identity bundle it is wrapped to (ADR 0111 decision 10 — compared with the member's current key). */
data class RotationGrant(val userId: String, val grant: SignedGrant, val recipientIdentityDigest: String)

/** One client-minted re-grant of an open tenant invite (ADR 0111 decision 2): the invite, the new epoch's key wrapped
 *  to its invite key with the rotator's signature, and the digest of the key it names. */
data class InviteGrant(val inviteId: String, val grant: SignedGrant, val recipientIdentityDigest: String)

/** The computed rotation (ADR 0024): the epoch to advance to, exactly which client-minted grants may be applied —
 *  to members and to open tenant invites — and the member whose membership it deletes in the same commit (a
 *  removal-rotation), if any. */
data class RotationPlan(
    val newEpoch: Int,
    val grants: List<RotationGrant>,
    val inviteGrants: List<InviteGrant> = emptyList(),
    val removes: String? = null,
)

/** What a rotation request comes to: commit this plan, or refuse it with nothing stored. */
sealed interface RotationDecision {
    data class Commit(val plan: RotationPlan) : RotationDecision
    /** The stated epoch is not the scope's next one, the caller's own grant is behind or absent, or the caller was let
     *  in by a pending re-key row the scope has moved past (`409 stale_epoch`). */
    object Stale : RotationDecision
    /** A member or an open tenant invite is neither re-granted nor declared pending (`409 rotation_incomplete`). */
    object Incomplete : RotationDecision
    /** A re-grant names a key that is not its recipient's current one (`409 recipient_key_not_current`). */
    object RecipientKeyNotCurrent : RotationDecision
    /** A removal-rotation names someone who holds no membership on the scope (`404 member_not_found`). */
    object MemberNotFound : RotationDecision
    /** A removal-rotation would remove the scope's last OWNER (`409 last_owner`, ADR 0007). */
    object LastOwner : RotationDecision
    /** A removal-rotation's grants name the member it removes (`422 removed_member_granted`). */
    object RemovedMemberGranted : RotationDecision
}

/**
 * The pure rotation decision (ADR 0045 §2), lifted out of [ScopesStore.rotate]'s `@Transactional` write so it can be
 * unit-tested and mutation-verified without a Quarkus boot. Given the scope's current epoch, the epoch the client
 * states it creates, its membership directory, its open tenant invites and the client-minted grants, it decides
 * whether the rotation commits and what it writes.
 *
 * Four load-bearing rules — and the reason this is a security decision, not plumbing:
 * - **Only the next epoch commits** (ADR 0024 §4). The client builds its chain-link from its **own grant**, not from
 *   the scope's epoch, so a rotation that lost a race or rode a lagging grant links the new epoch to a key that is
 *   not its predecessor. Committed, every epoch skipped that way is unreachable for every member. Such a rotation
 *   states an epoch that is not `currentEpoch + 1`: stale, nothing stored.
 * - **No member is left behind silently** (ADR 0111 decision 9). The rotator grants every member whose key its client
 *   anchors and declares the rest pending ([pendingUserIds]); a request that does neither for any one member (the
 *   removed one aside) is refused, so a member left behind is always a declared one — its grant behind the current
 *   epoch is the stored pending marker, which the grant delivery then brings current. A grant addressed to someone who
 *   holds no membership on the scope is dropped — there is nothing to wrap it onto. A `pendingConsent` proposal holds a
 *   grant from its proposal on (ADR 0111 decision 4), so it is a member here like any other.
 * - **No open tenant invite is left behind silently** (ADR 0111 decision 2). An invite is a placeholder member: one a
 *   rotation forgot would hand its newcomer a key the members already left. The rotator either re-grants it the new
 *   epoch (after verifying the owner's signature on its key and anchoring the signer) or declares it pending, and a
 *   request that does neither for any one of them is refused. An id that names no open invite is ignored — one
 *   revoked or accepted since the rotator read the list has nothing left to carry. The server checks presence and the
 *   key a grant names, never the signature: whether the invite is genuine is every client's question.
 * - **A non-owner commits only the epoch its pending re-key row names** (ADR 0111 decision 8): an account that secured
 *   itself mints the next epoch of every scope it was re-granted, owner or not, but only that one — [callerRegrantEpoch]
 *   must still be the scope's current epoch, else someone else's commit already met the row's purpose (stale).
 *
 * One fact about the **caller** comes first, read under the scope row's lock (#206): **behind** — the caller's own
 * grant is older than the scope's epoch, or absent: they hold no key to chain the new epoch from, whatever epoch they
 * state.
 *
 * A **removal-rotation** ([removes] set, ADR 0024 Decision 4) also deletes that member's membership in the same
 * commit, so a removal can never end without its rotation. Its grants must not name that member: the remover's client
 * excluded them by its own choice, and a request that re-grants them anyway is refused rather than trimmed — the
 * refusal is the layer-1 half, visible, where a silent trim would hide a client that built its set from the wrong
 * list. The last-owner guard ([MembershipPolicy]) applies exactly as it did to the standalone delete this replaced.
 */
object RotationPolicy {
    fun plan(
        currentEpoch: Int,
        statedEpoch: Int,
        members: List<RotationMember>,
        openInvites: Set<String>,
        grants: List<RotationGrant>,
        inviteGrants: List<InviteGrant>,
        pendingInviteIds: Set<String>,
        pendingUserIds: Set<String>,
        removes: String?,
        callerGrantedEpoch: Int?,
        callerRegrantEpoch: Int? = null,
    ): RotationDecision {
        if (callerGrantedEpoch == null || callerGrantedEpoch < currentEpoch) return RotationDecision.Stale
        if (statedEpoch != currentEpoch + 1) return RotationDecision.Stale
        if (callerRegrantEpoch != null && callerRegrantEpoch != currentEpoch) return RotationDecision.Stale
        var remaining = members
        if (removes != null) {
            val removed = members.firstOrNull { it.userId == removes } ?: return RotationDecision.MemberNotFound
            // `filter { … }.size` and `firstOrNull { … } != null`, not `count { … }` / `any { … }`: those two inline
            // stdlib bodies into this method — an `is Collection && isEmpty()` shortcut and count's overflow check —
            // and PIT (no Kotlin plugin) mutates them into four equivalent mutants no test can kill, which overran the
            // mutation gate's per-class budget of one (inbox #279). Same decision, no unkillable branches.
            val owners = members.filter { it.role == MembershipEntity.OWNER }.size.toLong()
            if (MembershipPolicy.isLastOwnerRemoval(removed.role, owners)) return RotationDecision.LastOwner
            if (grants.firstOrNull { it.userId == removes } != null) return RotationDecision.RemovedMemberGranted
            remaining = members - removed
        }
        val grantable = remaining.map { it.userId }
        if ((grantable - grants.map { it.userId }.toSet() - pendingUserIds).isNotEmpty()) return RotationDecision.Incomplete
        val carried = inviteGrants.filter { it.inviteId in openInvites }
        if ((openInvites - carried.map { it.inviteId }.toSet() - pendingInviteIds).isNotEmpty()) return RotationDecision.Incomplete
        return RotationDecision.Commit(
            RotationPlan(statedEpoch, grants.filter { it.userId in grantable }, carried, removes),
        )
    }
}
