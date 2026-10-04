package com.veyq.core.sharing

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The pure re-issue decision (ADR 0075 point 4, ADR 0045 §2), boot-free and mutation-verified. Each disjunct is
 * pinned **alone** against a baseline that answers `false` — a share whose every input says "nobody left" — so a
 * mutant that drops an arm, flips a `<` to `<=`, or ignores the copy gate turns exactly one case red. The slices
 * (`ShareSharedScopeSliceTest`) prove the inputs reach it from the database.
 */
class ReissuePolicyTest {

    /** Every input at "nothing happened": not switched off, owner a member, a copy at the current epoch and count. */
    private fun required(
        reissueOnly: Boolean = false, ownerIsMember: Boolean = true, copyEpoch: Int? = 3, currentEpoch: Int = 3,
        removalsAtMint: Int = 5, removals: Int = 5,
    ) = ReissuePolicy.required(reissueOnly, ownerIsMember, copyEpoch, currentEpoch, removalsAtMint, removals)

    @Test
    fun aShareNobodyCouldHaveLeftWithIsReactivatable() {
        assertFalse(required(), "current epoch, current count, owner a member, no switch-off: re-activation may revive it")
    }

    @Test
    fun aBulkSwitchOffAloneRequiresAReissue() {
        assertTrue(required(reissueOnly = true), "a switch-off says a link may have leaked — it comes back only by re-issue")
    }

    @Test
    fun aDepartedOwnerAloneRequiresAReissue() {
        assertTrue(required(ownerIsMember = false), "the owner holds their own link — it is never revived for them")
    }

    @Test
    fun aCopyBelowTheCurrentEpochAloneRequiresAReissue() {
        assertTrue(required(copyEpoch = 2), "every member of the older epoch could rebuild the link from the copy")
    }

    @Test
    fun aCopyMintedBeforeARemovalAloneRequiresAReissue() {
        assertTrue(required(removalsAtMint = 4), "a member removed since minting could have rebuilt the link")
    }

    @Test
    fun aCopyAboveEitherCurrentValueIsNoSignal() {
        // A stamp at or above the current value is "minted after": the comparisons are strict, not `!=`.
        assertFalse(required(copyEpoch = 4), "a copy sealed at a later epoch than the scope's is not stale")
        assertFalse(required(removalsAtMint = 6), "a stamp above the scope's count is not minted before a removal")
    }

    @Test
    fun aShareWithoutACopyIsJudgedByTheSwitchOffAndItsOwnerAlone() {
        // No copy, so no other member could ever rebuild it: neither the epoch nor the count may mark it …
        assertFalse(required(copyEpoch = null, currentEpoch = 9, removalsAtMint = 0, removals = 9),
            "without a recovery copy only its owner held the link — the epoch and the count say nothing about it")
        // … while the two arms that need no copy still do.
        assertTrue(required(copyEpoch = null, ownerIsMember = false), "its departed owner still holds it")
        assertTrue(required(copyEpoch = null, reissueOnly = true), "a switch-off still sticks without a copy")
    }
}
