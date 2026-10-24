package me.raddatz.veyq.core.testing

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [awaitWaiters]' MariaDB cadence against a model of InnoDB's lock-table cache, on a virtual clock — the half of the
 * harness H2 cannot show, because H2's `SESSIONS` is live. The model is the engine's rule (`trx0i_s.cc`, MariaDB 11.4,
 * [INNODB_LOCK_CACHE_IDLE_MS]): a read refreshes the cache only when it was not read for longer than the idle time, and
 * every read moves that mark. The bites: a side that starts waiting after the first read is seen, and a snapshot an
 * earlier round left in the cache is not read as this holder's waiter. Both were wrong at a 10 ms poll — the side that
 * waited 50 s for the node in `MediaCreateRaceSliceTest` / `MediaLockOrderRaceSliceTest` was never seen. The engine's
 * own proof is `RaceOrderSliceTest.aSideThatWaitsAfterTheFirstReadIsSeen` on MariaDB.
 */
class AwaitWaitersCadenceTest {

    private val pause = waitPollPauseMs("mariadb")

    @Test
    fun aSideThatStartsWaitingAfterTheFirstReadIsSeen() {
        val engine = InnodbLockCache(waiterFromMs = 300) // the side's HTTP request reaches its lock 300 ms in
        val evidence = pollWaiters(1, pause, { false }, engine::sleep, engine::now) { engine.read() }
        assertEquals("the waiter", evidence)
        assertTrue(engine.nowMs() < 1_000, "seen within a second of virtual time, not at the bound: ${engine.nowMs()} ms")
    }

    @Test
    fun anEarlierRoundsSnapshotIsNotThisHoldersWaiter() {
        // The previous round's waiter, on a pooled connection this holder now has, read 20 ms ago; this round has none.
        val engine = InnodbLockCache(waiterFromMs = null, snapshot = listOf("the previous round's waiter"), lastReadMs = -20)
        val failure = assertThrows(AssertionError::class.java) {
            pollWaiters(1, pause, { engine.nowMs() > 1_000 }, engine::sleep, engine::now) { engine.read() }
        }
        assertTrue("it locked none of them" in failure.message!!, failure.message)
    }

    /** InnoDB's `trx_i_s` cache over one holder, on a virtual clock: a waiter from [waiterFromMs] on, if any. */
    private class InnodbLockCache(
        private val waiterFromMs: Long?,
        private var snapshot: List<String> = emptyList(),
        lastReadMs: Long = -1_000_000, // never read: a first read is a fresh one
    ) {
        private var nanos = 0L
        private var lastRead = lastReadMs * 1_000_000

        fun now(): Long = nanos
        fun nowMs(): Long = nanos / 1_000_000
        fun sleep(ms: Long) {
            nanos += ms * 1_000_000
        }

        /** One SELECT over the lock tables: refreshed only past the idle time; 2 ms of its own; the mark moves either way. */
        fun read(): List<String> {
            if (nanos - lastRead > INNODB_LOCK_CACHE_IDLE_MS * 1_000_000) {
                snapshot = if (waiterFromMs != null && nowMs() >= waiterFromMs) listOf("the waiter") else emptyList()
            }
            nanos += 2_000_000
            lastRead = nanos
            return snapshot
        }
    }
}
