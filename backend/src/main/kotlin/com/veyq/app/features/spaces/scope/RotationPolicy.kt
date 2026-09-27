package com.veyq.app.features.spaces.scope

/** The membership facts the rotation decision needs: a member's id and whether their membership is still a soft
 *  `pendingConsent` proposal (cr-0094). Lifted out of the persistence row so the decision is testable without a DB. */
data class RotationMember(val userId: String, val pendingConsent: Boolean)

/** The computed rotation (ADR 0024): the epoch to advance to, and exactly which client-minted grants may be applied. */
data class RotationPlan(val newEpoch: Int, val grants: List<Pair<String, String>>)

/**
 * The pure rotation decision (ADR 0045 §2), lifted out of [ScopesStore.rotate]'s `@Transactional` write so it can be
 * unit-tested and mutation-verified without a Quarkus boot. Given the scope's current epoch, the epoch the client
 * states it creates, its membership directory and the client-minted grants, it decides whether the rotation commits
 * and which grants are deliverable.
 *
 * Two load-bearing rules — and the reason this is a security decision, not plumbing:
 * - **Only the next epoch commits** (ADR 0024 §4). The client builds its chain-link from its **own grant**, not from
 *   the scope's epoch, so a rotation that lost a race or rode a lagging grant links the new epoch to a key that is
 *   not its predecessor. Committed, every epoch skipped that way is unreachable for every member. Such a rotation
 *   states an epoch that is not `currentEpoch + 1`, and the answer is `null`: stale, nothing stored.
 * - A `pendingConsent` proposal is **never** (re-)granted, so `K_SC` stays gated *before* the wrap even when an honest
 *   client re-grants the whole owner-visible directory (which includes pending proposals) on a member-removal
 *   rotation (cr-0094/cr-0095). A grant addressed to someone who holds no membership on the scope is dropped — there
 *   is nothing to wrap it onto.
 */
object RotationPolicy {
    /** The plan, or `null` when [statedEpoch] is not the scope's next epoch (`409 stale_epoch`). */
    fun plan(currentEpoch: Int, statedEpoch: Int, members: List<RotationMember>, grants: List<Pair<String, String>>): RotationPlan? {
        if (statedEpoch != currentEpoch + 1) return null
        val grantable = members.filterNot { it.pendingConsent }.map { it.userId }
        return RotationPlan(statedEpoch, grants.filter { (uid, _) -> uid in grantable })
    }
}
