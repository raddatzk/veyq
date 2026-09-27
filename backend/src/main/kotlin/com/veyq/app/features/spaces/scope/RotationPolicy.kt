package com.veyq.app.features.spaces.scope

import com.veyq.app.shared.membership.SignedGrant

/** The membership facts the rotation decision needs: a member's id and whether their membership is still a soft
 *  `pendingConsent` proposal (cr-0094). Lifted out of the persistence row so the decision is testable without a DB. */
data class RotationMember(val userId: String, val pendingConsent: Boolean)

/** One client-minted re-grant: the member it is for, and the grant with the rotator's signature over it. */
data class RotationGrant(val userId: String, val grant: SignedGrant)

/** The computed rotation (ADR 0024): the epoch to advance to, exactly which client-minted grants may be applied, the
 *  new tag of every open admission carried forward (admission id → tag), and the open admissions it drops. */
data class RotationPlan(
    val newEpoch: Int,
    val grants: List<RotationGrant>,
    val retags: Map<String, String>,
    val dropped: Set<String>,
)

/** What a rotation request comes to: commit this plan, or refuse it with nothing stored. */
sealed interface RotationDecision {
    data class Commit(val plan: RotationPlan) : RotationDecision
    /** The stated epoch is not the scope's next one (`409 stale_epoch`). */
    object Stale : RotationDecision
    /** An open admission is neither re-tagged nor dropped (`409 admissions_incomplete`). */
    object AdmissionsIncomplete : RotationDecision
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
    ): RotationDecision {
        if (statedEpoch != currentEpoch + 1) return RotationDecision.Stale
        val grantable = members.filterNot { it.pendingConsent }.map { it.userId }
        val keptDropped = dropped intersect openAdmissions
        val carried = retags.filter { (id, _) -> id in openAdmissions && id !in keptDropped }.toMap()
        if ((openAdmissions - carried.keys - keptDropped).isNotEmpty()) return RotationDecision.AdmissionsIncomplete
        return RotationDecision.Commit(
            RotationPlan(statedEpoch, grants.filter { it.userId in grantable }, carried, keptDropped),
        )
    }
}
