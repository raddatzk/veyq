package me.raddatz.veyq.core.testing

import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * The race harness's own proof (inbox #512): [whileUncommitted] counts a round only when its order was forced — the
 * overlapping side was seen waiting on the held transaction as it committed, or finished inside the hold — and
 * [raceRounds] runs an unforced round again. Each wait the race tests rely on is shown seen: a row lock (H2's
 * `BLOCKER_ID`, InnoDB's lock waits) and a unique key (H2's retry, which names no blocker; InnoDB's lock waits). The
 * bites: a round whose overlapping side starts only after the holder committed is caught and repeated, and with
 * repetition off it fails by name. The table is this class's own scratch, so the proof depends on no entity.
 */
@QuarkusTest
class RaceOrderSliceTest {

    @Inject lateinit var em: EntityManager

    @BeforeEach
    fun table() {
        QuarkusTransaction.requiringNew().run {
            em.createNativeQuery(
                "CREATE TABLE IF NOT EXISTS race_order_probe (id VARCHAR(36) NOT NULL PRIMARY KEY, " +
                    "k VARCHAR(64) NOT NULL, v INT NOT NULL, CONSTRAINT uq_race_order_probe_k UNIQUE (k))",
            ).executeUpdate()
        }
    }

    @Test
    fun aWriteOnTheHeldRowIsSeenWaiting() = raceRounds { round ->
        val id = seed()
        whileUncommitted({ bumpHere(id) }, { bump(id) })
        assertEquals(2, value(id), "round $round: the waiting write followed the held one")
    }

    @Test
    fun anInsertOfTheHeldUniqueKeyIsSeenWaiting() = raceRounds { round ->
        val key = UUID.randomUUID().toString()
        whileUncommitted({ insertHere(UUID.randomUUID().toString(), key) }, {
            runCatching { QuarkusTransaction.requiringNew().run { insertHere(UUID.randomUUID().toString(), key) } }
        })
        assertEquals(1, count(key), "round $round: the second insert of the key waited and lost")
    }

    @Test
    fun anOverlapThatEndsInsideTheHoldCounts() = raceRounds { round ->
        val id = seed()
        val seen = whileUncommitted({ bumpHere(id) }, { value(id) })
        assertEquals(0, seen, "round $round: a read inside the hold sees the state before the change")
    }

    /**
     * [awaitWaiters] sees a side that reaches the held row only after its first read — the shape of every hold that
     * starts a side and then waits for it (an HTTP request takes its lock tens of ms in). Red on MariaDB at a 10 ms
     * poll: InnoDB refreshes its lock tables only when they went unread for 0.1 s, so the poll read its first, empty
     * snapshot until the side's 1205 ended it ("it locked none of them"); `AwaitWaitersCadenceTest` holds the cadence.
     */
    @Test
    fun aSideThatWaitsAfterTheFirstReadIsSeen() {
        val id = seed()
        val pool = Executors.newSingleThreadExecutor()
        try {
            lateinit var side: Future<*>
            QuarkusTransaction.requiringNew().run {
                bumpHere(id)
                em.flush()
                side = pool.submit { Thread.sleep(3 * INNODB_LOCK_CACHE_IDLE_MS); bump(id) } // reaches the row late
                awaitWaiters(1) { side.isDone }
            }
            side.get(OVERLAP_BOUND_S, TimeUnit.SECONDS)
            assertEquals(2, value(id), "the waiting write followed the held one")
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun aRoundWhoseOverlapStartsAfterTheCommitIsRunAgain() {
        var attempts = 0
        raceRounds(rounds = 1) {
            attempts++
            val id = seed()
            whileUncommitted({ bumpHere(id) }, {
                if (attempts < 3) awaitCommitted(id) // the bite: the waiter starts only once the holder committed
                bump(id)
            })
        }
        assertEquals(3, attempts, "two unforced attempts, then the forced one")
    }

    @Test
    fun withoutRepetitionAnUnforcedRoundFailsByName() {
        val misOrdered = {
            val id = seed()
            whileUncommitted({ bumpHere(id) }, { awaitCommitted(id); bump(id) })
        }
        val bare = assertThrows(OrderNotForced::class.java) { misOrdered() }
        assertTrue("no session waited on the holder" in bare.message!! || "no transaction waited" in bare.message!!, bare.message)
        val once = assertThrows(AssertionError::class.java) { raceRounds(rounds = 1, attempts = 1) { misOrdered() } }
        assertTrue("the order was not forced in 1 attempt(s)" in once.message!!, once.message)
    }

    private fun seed(): String = UUID.randomUUID().toString().also { id ->
        QuarkusTransaction.requiringNew().run { insertHere(id, id) }
    }

    private fun insertHere(id: String, key: String) {
        em.createNativeQuery("INSERT INTO race_order_probe (id, k, v) VALUES (?1, ?2, 0)")
            .setParameter(1, id).setParameter(2, key).executeUpdate()
    }

    private fun bumpHere(id: String) {
        em.createNativeQuery("UPDATE race_order_probe SET v = v + 1 WHERE id = ?1").setParameter(1, id).executeUpdate()
    }

    private fun bump(id: String) = QuarkusTransaction.requiringNew().run { bumpHere(id) }

    private fun value(id: String): Int = QuarkusTransaction.requiringNew().call {
        (em.createNativeQuery("SELECT v FROM race_order_probe WHERE id = ?1").setParameter(1, id).singleResult as Number).toInt()
    }

    private fun count(key: String): Int = QuarkusTransaction.requiringNew().call {
        (em.createNativeQuery("SELECT COUNT(*) FROM race_order_probe WHERE k = ?1").setParameter(1, key).singleResult as Number).toInt()
    }

    /** Wait until the held change is committed — a fresh transaction sees it — so what follows overlaps nothing. */
    private fun awaitCommitted(id: String) {
        val until = System.nanoTime() + OVERLAP_BOUND_S * 1_000_000_000
        while (value(id) == 0) {
            check(System.nanoTime() < until) { "the held change never committed" }
            Thread.sleep(20)
        }
    }
}
