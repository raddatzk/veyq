package com.veyq.app.features.account.login

import jakarta.persistence.PessimisticLockException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.sql.SQLException

/**
 * The rotation retry (ADR 0004 / cr-refresh-one-generation-grace). Driven by injection, not by a real
 * deadlock: the test profile is **H2**, and the cycle this answers is an InnoDB insert-intention gap lock, so
 * no slice test on this stack can produce one. The lever is therefore a body that throws the exception the
 * driver would throw, wrapped exactly as Hibernate wraps it.
 */
class SessionRotationRetryTest {

    private fun deadlock(): RuntimeException =
        PessimisticLockException(
            "could not execute statement",
            SQLException("Deadlock found when trying to get lock; try restarting transaction", "40001", 1213),
            null,
        )

    @Test
    fun retriesADeadlockVictimAndSucceeds() {
        var calls = 0
        val result = withDeadlockRetry(attempts = 3) {
            calls++
            if (calls == 1) throw deadlock()
            "rotated"
        }
        assertEquals("rotated", result)
        assertEquals(2, calls, "the first attempt is the victim; the second must actually run")
    }

    @Test
    fun givesUpRatherThanLoopingForever() {
        var calls = 0
        val thrown = assertThrows(PessimisticLockException::class.java) {
            withDeadlockRetry(attempts = 3) {
                calls++
                throw deadlock()
            }
        }
        assertEquals(3, calls, "a bounded retry runs exactly its attempts, no more")
        assertTrue(thrown.cause is SQLException, "the last failure is rethrown intact, not swallowed")
    }

    @Test
    fun doesNotRetryAnythingThatIsNotASerializationFailure() {
        var calls = 0
        assertThrows(IllegalStateException::class.java) {
            withDeadlockRetry(attempts = 3) {
                calls++
                throw IllegalStateException("a real bug, not a lock")
            }
        }
        assertEquals(1, calls, "only SQLSTATE 40001 is retryable — everything else must surface at once")
    }

    @Test
    fun findsTheSqlStateThroughTheWHOLEcauseChain() {
        // The driver's SQLException reaches the retry through a Hibernate wrapper AND a JTA rollback wrapper,
        // so a check that only looks one level down would never fire in production.
        var calls = 0
        val result = withDeadlockRetry(attempts = 2) {
            calls++
            if (calls == 1) throw RuntimeException("ARJUNA016053: rollback", deadlock())
            "rotated"
        }
        assertEquals("rotated", result)
        assertEquals(2, calls)
    }
}
