package com.veyq.app.features.spaces.scope

/** The membership facts the rotation decision needs: a member's id and whether their membership is still a soft
 *  `pendingConsent` proposal (cr-0094). Lifted out of the persistence row so the decision is testable without a DB. */
data class RotationMember(val userId: String, val pendingConsent: Boolean)

/** The computed rotation (ADR 0024): the epoch to advance to, and exactly which client-minted grants may be applied. */
data class RotationPlan(val newEpoch: Int, val grants: List<Pair<String, String>>)

/**
 * The pure rotation decision (ADR 0045 §2), lifted out of [ScopesStore.rotate]'s `@Transactional` write so it can be
 * unit-tested and mutation-verified without a Quarkus boot. Given the scope's current epoch, its membership directory
 * and the client-minted grants, it decides the next epoch and which grants are deliverable.
 *
 * The load-bearing rule — and the reason this is a security decision, not plumbing: a `pendingConsent` proposal is
 * **never** (re-)granted, so `K_SC` stays gated *before* the wrap even when an honest client re-grants the whole
 * owner-visible directory (which includes pending proposals) on a member-removal rotation (cr-0094/cr-0095). A grant
 * addressed to someone who holds no membership on the scope is dropped — there is nothing to wrap it onto.
 */
object RotationPolicy {
    fun plan(currentEpoch: Int, members: List<RotationMember>, grants: List<Pair<String, String>>): RotationPlan {
        val grantable = members.filterNot { it.pendingConsent }.map { it.userId }
        return RotationPlan(currentEpoch + 1, grants.filter { (uid, _) -> uid in grantable })
    }
}
