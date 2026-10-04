package com.veyq.core.spaces.scope

import com.veyq.core.membership.MembershipEntity
import com.veyq.core.membership.SignedGrant

/** The membership facts the rotation decision needs: a member's id, whether their membership is still a soft
 *  `pendingConsent` proposal (cr-0094), their role (the last-owner guard of a removal) and the admission their
 *  membership carries, if any (which a removal takes with it). Lifted out of the persistence row so the decision
 *  is testable without a DB. */
data class RotationMember(
    val userId: String,
    val pendingConsent: Boolean,
    val role: String = MembershipEntity.MEMBER,
    val admissionId: String? = null,
)

/** One client-minted re-grant: the member it is for, and the grant with the rotator's signature over it. */
data class RotationGrant(val userId: String, val grant: SignedGrant)

/** The computed rotation (ADR 0024): the epoch to advance to, exactly which client-minted grants may be applied, the
 *  new tag of every open admission carried forward (admission id → tag), the open admissions it drops, and the
 *  member whose membership it deletes in the same commit (a removal-rotation), if any. */
data class RotationPlan(
    val newEpoch: Int,
    val grants: List<RotationGrant>,
    val retags: Map<String, String>,
    val dropped: Set<String>,
    val removes: String? = null,
)

/** What a rotation request comes to: commit this plan, or refuse it with nothing stored. */
sealed interface RotationDecision {
    data class Commit(val plan: RotationPlan) : RotationDecision
    /** The stated epoch is not the scope's next one, or the caller's own grant is behind or absent (`409 stale_epoch`). */
    object Stale : RotationDecision
    /** The caller's account rotation queued a still-live rotation of this scope that excludes them
     *  (`403 rotation_excluded`, ADR 0060 Decision 4). */
    object Excluded : RotationDecision
    /** An open admission is neither re-tagged nor dropped (`409 admissions_incomplete`). */
    object AdmissionsIncomplete : RotationDecision
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
 * states it creates, its membership directory, its open admissions and the client-minted grants and re-tags, it
 * decides whether the rotation commits and what it writes.
 *
 * Three load-bearing rules — and the reason this is a security decision, not plumbing:
 * - **Only the next epoch commits** (ADR 0024 §4). The client builds its chain-link from its **own grant**, not from
 *   the scope's epoch, so a rotation that lost a race or rode a lagging grant links the new epoch to a key that is
 *   not its predecessor. Committed, every epoch skipped that way is unreachable for every member. Such a rotation
 *   states an epoch that is not `currentEpoch + 1`: stale, nothing stored.
 * - A `pendingConsent` proposal is **never** (re-)granted, so `K_SC` stays gated *before* the wrap even when an honest
 *   client re-grants the whole owner-visible directory (which includes pending proposals) on a member-removal
 *   rotation (cr-0094/cr-0095). A grant addressed to someone who holds no membership on the scope is dropped — there
 *   is nothing to wrap it onto.
 * - **No open admission is left behind** (ADR 0020 move 6). A worker admits only a tag at its own current epoch, so
 *   an admission a rotation does not re-tag could never be delivered to again — the member it admits would wait
 *   forever. The rotator either re-tags it under the new epoch (after verifying its old tag) or drops it on purpose,
 *   and a request that does neither for any one of them is refused. The server checks presence only: it holds no
 *   epoch key, so whether a tag is genuine is every client's question, never this one's.
 *
 * Two facts about the **caller** come first, both read under the scope row's lock (ADR 0060 Decision 4, #206):
 * - **Excluded** — a still-live rotation job of the scope carries the caller's rotation exclusion: their account
 *   rotated after a compromise, and the rotation that excludes their old identity has not landed. For an owner rotator
 *   the owner gate is no reason at all, so this is where the exclusion binds a client of their own.
 * - **Behind** — the caller's own grant is older than the scope's epoch, or absent: they hold no key to chain the new
 *   epoch from, whatever epoch they state. This is the answer the contract already gave for "the caller's grant lags
 *   the scope", now taken from the server's row instead of the client's word. Once the covering rotation landed (and
 *   skipped the rotator's changed key) it is what still holds the rotator; once a human re-admits them, neither fact
 *   applies, which is the intended end of the exclusion.
 *
 * A **removal-rotation** ([removes] set, ADR 0024 Decision 4) also deletes that member's membership in the same
 * commit, so a removal can never end without its rotation. Its grants must not name that member: the remover's client
 * excluded them by its own choice, and a request that re-grants them anyway is refused rather than trimmed — the
 * refusal is the layer-1 half, visible, where a silent trim would hide a client that built its set from the wrong
 * list. The member's admission leaves with the membership, so it is neither required nor written; the last-owner
 * guard ([MembershipPolicy]) applies exactly as it did to the standalone delete this replaced.
 */
object RotationPolicy {
    fun plan(
        currentEpoch: Int,
        statedEpoch: Int,
        members: List<RotationMember>,
        openAdmissions: Set<String>,
        grants: List<RotationGrant>,
        retags: List<Pair<String, String>>,
        dropped: Set<String>,
        removes: String?,
        callerExcluded: Boolean,
        callerGrantedEpoch: Int?,
    ): RotationDecision {
        if (callerExcluded) return RotationDecision.Excluded
        if (callerGrantedEpoch == null || callerGrantedEpoch < currentEpoch) return RotationDecision.Stale
        if (statedEpoch != currentEpoch + 1) return RotationDecision.Stale
        var remaining = members
        var open = openAdmissions
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
            open = removed.admissionId?.let { openAdmissions - it } ?: openAdmissions
        }
        val grantable = remaining.filterNot { it.pendingConsent }.map { it.userId }
        val keptDropped = dropped intersect open
        val carried = retags.filter { (id, _) -> id in open && id !in keptDropped }.toMap()
        if ((open - carried.keys - keptDropped).isNotEmpty()) return RotationDecision.AdmissionsIncomplete
        return RotationDecision.Commit(
            RotationPlan(statedEpoch, grants.filter { it.userId in grantable }, carried, keptDropped, removes),
        )
    }
}
