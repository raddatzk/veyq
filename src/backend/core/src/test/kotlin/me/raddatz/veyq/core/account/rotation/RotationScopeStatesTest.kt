package me.raddatz.veyq.core.account.rotation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The derived state of a scope after the account was secured (cr-secure-the-account decision 8), boot-free. Each rule is
 * pinned alone against the baseline `WAITING` and against the facts of every rule below it, so a reordered, dropped or
 * widened arm turns a named case red. `RotationSliceTest.theStatesFollowTheRows` proves the facts reach it from the rows.
 */
class RotationScopeStatesTest {

    private fun state(atRotation: Int = 2, current: Int = 2, blocked: RotationBlock? = null, nodesLag: Boolean = false) =
        RotationScopeStates.state(RegrantFacts(atRotation, current, blocked, nodesLag))

    @Test
    fun theEpochItHadAtTheRotationIsWaiting() {
        assertEquals(RegrantState.WAITING, state())
        assertEquals(RegrantState.WAITING, state(nodesLag = true), "a lag before the next epoch is no re-key yet")
    }

    @Test
    fun aBlockedScopeIsBlockedWhateverElseHolds() {
        assertEquals(RegrantState.BLOCKED, state(blocked = RotationBlock.TENANT_SUSPENDED))
        assertEquals(RegrantState.BLOCKED, state(current = 3, blocked = RotationBlock.SCOPE_DELETING))
        assertEquals(RegrantState.BLOCKED, state(current = 3, nodesLag = true, blocked = RotationBlock.SCOPE_DELETING))
    }

    @Test
    fun aMovedEpochIsSecuredOnceNoNodeLags() {
        assertEquals(RegrantState.SECURED, state(current = 3))
        assertEquals(RegrantState.REKEYING, state(current = 3, nodesLag = true))
        assertEquals(RegrantState.SECURED, state(current = 7), "however many epochs someone else committed since")
    }
}
