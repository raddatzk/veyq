package com.veyq.app.features.spaces.scope

import com.veyq.app.shared.membership.MembershipEntity
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The pure scope-membership last-owner guards (ADR 0045 §2, ADR 0007), boot-free and mutation-verified. Every case
 * pins an exact `true`/`false` with a rule-stating message — a bare presence assert would let a mutant that flips the
 * `<= 1` boundary, drops the `targetRole == MEMBER` arm, or ignores the caller's role pass (the assertion-strength
 * trap ADR 0045 exists to close). The `ownerCount == 2 → allowed` and `promote-when-last-owner → allowed` cases pin
 * the boundary and the demote-only arm explicitly. The end-to-end wiring is the slice `ScopesSliceTest`.
 */
class MembershipPolicyTest {

    private val owner = MembershipEntity.OWNER
    private val member = MembershipEntity.MEMBER

    // --- demote: rejected only when demoting the sole remaining OWNER to MEMBER --------------------------------

    @Test
    fun demotingTheSoleOwnerToMemberIsTheLastOwner() {
        assertTrue(
            MembershipPolicy.isLastOwnerDemote(owner, member, ownerCount = 1),
            "demoting the last OWNER to MEMBER would strand the scope ownerless — rejected (ADR 0007)",
        )
    }

    @Test
    fun demotingAnOwnerWithACoOwnerIsAllowed() {
        assertFalse(
            MembershipPolicy.isLastOwnerDemote(owner, member, ownerCount = 2),
            "with a co-owner present the demotion proceeds (ownerCount == 2 is not the last owner)",
        )
    }

    @Test
    fun promotingToOwnerWhenLastOwnerIsNotADemote() {
        assertFalse(
            MembershipPolicy.isLastOwnerDemote(owner, owner, ownerCount = 1),
            "raising the target to OWNER never removes an owner — the guard is demote-only (targetRole == MEMBER)",
        )
    }

    @Test
    fun demotingANonOwnerTargetIsNeverTheLastOwner() {
        assertFalse(
            MembershipPolicy.isLastOwnerDemote(member, member, ownerCount = 1),
            "a target who is not currently an OWNER cannot be the last owner, whatever the count",
        )
    }

    // --- remove: rejected only when removing the sole remaining OWNER ------------------------------------------

    @Test
    fun removingTheSoleOwnerIsTheLastOwner() {
        assertTrue(
            MembershipPolicy.isLastOwnerRemoval(owner, ownerCount = 1),
            "removing the last OWNER would strand the scope ownerless — rejected (ADR 0007)",
        )
    }

    @Test
    fun removingAnOwnerWithACoOwnerIsAllowed() {
        assertFalse(
            MembershipPolicy.isLastOwnerRemoval(owner, ownerCount = 2),
            "with a co-owner present the removal proceeds (ownerCount == 2 is not the last owner)",
        )
    }

    @Test
    fun removingANonOwnerIsNeverTheLastOwner() {
        assertFalse(
            MembershipPolicy.isLastOwnerRemoval(member, ownerCount = 1),
            "a non-owner is removed regardless of the owner count",
        )
    }
}
