package me.raddatz.veyq.core.account.erasure

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Unit test for what the boot refuses about the account erasure's window (ADR 0101 decision 3, [AccountErasureWindow]):
 * a window that is not positive, and one that with a purge tick reaches past 28 days, the shortest calendar month. Tests
 * the pure predicate; the boot itself runs it with the default in every `@QuarkusTest`.
 */
class AccountErasureWindowTest {
    private val hourly = Duration.ofHours(1)

    @Test
    fun theDefaultAndAnyWindowInsideTheShortestMonthAreAccepted() {
        for (window in listOf(Duration.ofDays(14), Duration.ofMinutes(1), Duration.ofDays(28).minus(hourly))) {
            assertNull(AccountErasureWindow.refusal(window, hourly), "accepted: $window")
        }
    }

    @Test
    fun aWindowThatIsNotPositiveIsRefused() {
        for (window in listOf(Duration.ZERO, Duration.ofDays(-1))) {
            val refusal = AccountErasureWindow.refusal(window, hourly)
            assertNotNull(refusal, "refused: $window")
            assertTrue(refusal!!.contains("veyq.account.erasure.window=$window must be positive"), refusal)
        }
    }

    @Test
    fun aWindowThatWithItsPurgeTickPassesTheShortestMonthIsRefused() {
        // The scope trash's 30 days, which decision 3 rejected; and 28 days exactly, which the tick pushes past.
        for (window in listOf(Duration.ofDays(30), Duration.ofDays(28))) {
            val refusal = AccountErasureWindow.refusal(window, hourly)
            assertNotNull(refusal, "refused: $window")
            assertTrue(refusal!!.contains("veyq.account.erasure.window=$window plus veyq.account.erasure.purge.every=PT1H"), refusal)
        }
        // Control: the same 28 days less the tick is inside.
        assertNull(AccountErasureWindow.refusal(Duration.ofDays(28).minus(hourly), hourly))
    }
}
