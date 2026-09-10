package com.veyq.app.shared.blobstore

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * The bounded retry's terminal states (ADR 0067 Decision 3). Asserted on the **property the operator reads** —
 * which state the run ends in — and deliberately not on the mechanism: a test that asserted "a retry ran" or
 * "the executor was submitted to" would be green on code that retried perfectly and reported nothing, which is
 * the failure `GET /api/instance/store-provisioning` exists to prevent.
 *
 * [StoreProvisioningRunner.runBounded] is a pure function precisely so this can run in the hermetic suite: the
 * class around it skips `%test` on purpose (no network there), so a test on the bean would assert nothing.
 */
class StoreProvisioningRunnerTest {

    @Test
    fun aStoreThatComesUpIsReportedProvisioned() {
        val target = CountingProvisioner(failuresBeforeSuccess = 0)

        val state = StoreProvisioningRunner.runBounded(target, maxAttempts = 3, firstBackoff = TINY, maxBackoff = TINY)

        assertEquals(StoreProvisioningState.PROVISIONED, state)
        assertEquals(1, target.attempts)
    }

    @Test
    fun aStoreThatIsSlowToArriveIsStillReportedProvisioned() {
        // The case the bound exists for: the store container starting beside the app under `docker compose up`.
        val target = CountingProvisioner(failuresBeforeSuccess = 2)

        val state = StoreProvisioningRunner.runBounded(target, maxAttempts = 5, firstBackoff = TINY, maxBackoff = TINY)

        assertEquals(StoreProvisioningState.PROVISIONED, state)
        assertEquals(3, target.attempts)
    }

    /**
     * The give-up is **named**, and it is the value the read reports. An unbounded retry against a permanently
     * unreachable store is a loop nobody can read the end of; this is ADR 0062 Decision 5's shape one subsystem
     * over — the loss is not claimed to be impossible, it is made countable.
     */
    @Test
    fun aStoreThatNeverComesUpIsReportedFailedAndNotOk() {
        val target = CountingProvisioner(failuresBeforeSuccess = Int.MAX_VALUE)

        val state = StoreProvisioningRunner.runBounded(target, maxAttempts = 4, firstBackoff = TINY, maxBackoff = TINY)

        assertEquals(StoreProvisioningState.FAILED, state, "a store that never came up must never read as PROVISIONED")
        assertEquals(4, target.attempts, "and the retry must stop at the bound rather than run forever")
    }

    private class CountingProvisioner(private val failuresBeforeSuccess: Int) : StoreProvisioner {
        var attempts = 0

        override fun provision() {
            attempts++
            if (attempts <= failuresBeforeSuccess) throw IllegalStateException("store not reachable")
        }
    }

    private companion object {
        val TINY: Duration = Duration.ofMillis(1)
    }
}
