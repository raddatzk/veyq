package com.veyq.app.features.spaces.scope

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The pure rotation decision (ADR 0045 §2), boot-free and mutation-verified. Every assertion pins an exact value —
 * a bare "not empty" would let a mutant that grants the *wrong* set pass (the assertion-strength trap ADR 0045 exists
 * to close). The security case — `never grants a pendingConsent proposal` — mirrors the slice
 * `ConsentGroupAddSliceTest.rotationNeverGrantsAPendingProposal`, but here without a Quarkus boot per mutant.
 */
class RotationPolicyTest {

    private fun member(id: String, pending: Boolean = false) = RotationMember(id, pending)

    @Test
    fun commitsTheStatedEpochWhenItIsTheNextOne() {
        val plan = RotationPolicy.plan(currentEpoch = 4, statedEpoch = 5, members = listOf(member("a")), grants = emptyList())
        assertEquals(5, plan?.newEpoch, "a rotation stating the scope's next epoch advances to it")
    }

    @Test
    fun refusesARotationBuiltOnASupersededEpoch() {
        // #177: another rotation committed epoch 5 first (or the caller's grant lags), so this one — built on 4 —
        // states 5 again. Its link wraps E_4, and committing it as 6 would orphan E_5 for every member.
        val plan = RotationPolicy.plan(currentEpoch = 5, statedEpoch = 5, members = listOf(member("a")), grants = listOf("a" to "g-a"))
        assertNull(plan, "a rotation stating the current epoch is stale: nothing may be stored")
    }

    @Test
    fun refusesARotationThatSkipsAnEpoch() {
        // The other direction: no epoch may be skipped either, or its link would name a predecessor that never existed.
        val plan = RotationPolicy.plan(currentEpoch = 4, statedEpoch = 6, members = listOf(member("a")), grants = listOf("a" to "g-a"))
        assertNull(plan, "a rotation stating current + 2 is stale: nothing may be stored")
    }

    @Test
    fun reGrantsEveryNonPendingMemberTheClientListed() {
        val members = listOf(member("owner"), member("alice"))
        val grants = listOf("owner" to "g-owner", "alice" to "g-alice")
        val plan = RotationPolicy.plan(currentEpoch = 0, statedEpoch = 1, members = members, grants = grants)
        assertEquals(grants, plan?.grants, "an ordinary re-grant delivers to every listed key-holder")
    }

    @Test
    fun neverGrantsAPendingConsentProposalEvenWhenTheClientListsIt() {
        val members = listOf(member("owner"), member("partner", pending = true))
        val grants = listOf("owner" to "g-owner", "partner" to "g-partner")
        val plan = RotationPolicy.plan(currentEpoch = 2, statedEpoch = 3, members = members, grants = grants)
        assertEquals(
            listOf("owner" to "g-owner"), plan?.grants,
            "K_SC must never be wrapped onto a pendingConsent proposal (cr-0094)",
        )
    }

    @Test
    fun dropsAGrantAddressedToANonMember() {
        val members = listOf(member("owner"))
        val grants = listOf("owner" to "g-owner", "stranger" to "g-stranger")
        val plan = RotationPolicy.plan(currentEpoch = 0, statedEpoch = 1, members = members, grants = grants)
        assertEquals(
            listOf("owner" to "g-owner"), plan?.grants,
            "a grant with no membership to wrap onto is dropped",
        )
    }
}
