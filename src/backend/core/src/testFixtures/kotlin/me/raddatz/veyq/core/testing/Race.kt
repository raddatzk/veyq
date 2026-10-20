package me.raddatz.veyq.core.testing

import io.quarkus.arc.Arc
import io.quarkus.narayana.jta.QuarkusTransaction
import io.restassured.response.ExtractableResponse
import io.restassured.response.Response
import jakarta.persistence.EntityManager
import org.eclipse.microprofile.config.ConfigProvider
import org.hibernate.Session
import org.junit.jupiter.api.Assertions.assertEquals
import java.sql.Connection
import java.sql.SQLException
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Run [bodies] on their own threads, released together by one barrier, and return their results in order — the
 * race shape of `LoginSliceTest.concurrentVerifyConsumesTheChallengeOnce`, for a test that races one single-use
 * secret against itself (developer-styleguide §12, the checked compare-and-set).
 *
 * A barrier only makes the race *likely*, never certain: one round can pass by luck. A race test therefore runs
 * its race several times, with a fresh secret each round.
 */
fun <T> race(vararg bodies: () -> T): List<T> {
    val barrier = CyclicBarrier(bodies.size)
    val pool = Executors.newFixedThreadPool(bodies.size)
    try {
        return pool.invokeAll(bodies.map { body -> Callable { barrier.await(); body() } }).map { it.get() }
    } finally {
        pool.shutdownNow()
    }
}

/** How many times a race test races, each round against a fresh target (see [race]). */
const val RACE_ROUNDS = 5

/** How long [whileUncommitted] holds the first writer's commit back. */
const val OVERLAP_HOLD_MS = 300L

/**
 * How many times [raceRounds] runs one round whose order was not forced ([OrderNotForced]) before the test fails. A
 * round is unforced only when the overlapping side reached the held transaction too late — a loaded machine's first
 * call into a class can take seconds — so five attempts that all miss name a round that cannot be forced, not bad luck.
 */
const val ORDER_ATTEMPTS = 5

/**
 * On H2, how long one statement of another session must have been running at the end of the hold to count as a wait on
 * the holder when the engine names no blocker ([holdForOverlap]): H2 shows a wait on another transaction's uncommitted
 * row in `INFORMATION_SCHEMA.SESSIONS.BLOCKER_ID`, but a wait on its uncommitted unique key is a retry loop inside one
 * statement, `RUNNING` with no blocker (measured, H2 2.4.240). An in-memory statement that waits on nothing ends in
 * well under a millisecond, so one that has run this long while the holder is the only other session with uncommitted
 * rows is inside that retry. The hold is stretched by at most this much for a statement still younger.
 */
const val H2_RETRY_EVIDENCE_MS = 100L

/**
 * One race round whose order the hold did not force: the overlapping side neither finished inside the hold nor was
 * seen waiting on the held transaction when it committed, so the round raced the other order. [raceRounds] runs such a
 * round again on fresh rows; outside it, the test fails with this — never green on the order it did not test.
 */
class OrderNotForced(message: String) : AssertionError(message)

/**
 * Run a race test's [rounds] rounds — `body(round)` each, which builds its own fresh rows and races them through
 * [whileUncommitted] — and run a round again, from the top, when its order was not forced ([OrderNotForced]), up to
 * [attempts] times. A round that is never forced fails the test by name: its assertions were only ever run on the order
 * the test does not mean.
 */
fun raceRounds(rounds: Int = RACE_ROUNDS, attempts: Int = ORDER_ATTEMPTS, body: (round: Int) -> Unit) {
    for (round in 0 until rounds) {
        var attempt = 1
        while (true) {
            try {
                body(round)
                break
            } catch (e: OrderNotForced) {
                if (attempt >= attempts) {
                    throw AssertionError(
                        "round $round: the order was not forced in $attempts attempt(s) — the overlapping side never " +
                            "reached the held transaction before it committed. Last attempt: ${e.message}",
                        e,
                    )
                }
                System.err.println("round $round, attempt $attempt: order not forced, running the round again — ${e.message}")
                attempt++
            }
        }
    }
}

/**
 * What [holdForOverlap] saw at the end of the hold: whether the overlapping side was in the order the hold forces, and
 * the evidence either way (an [OrderNotForced] message names it).
 */
class Overlap(val forced: Boolean, val evidence: String) {
    /** Throw [OrderNotForced] for a round whose order was not forced, after the round's own work has ended. */
    fun requireForced() {
        if (!forced) throw OrderNotForced(evidence)
    }
}

/**
 * Hold the current transaction's commit back [OVERLAP_HOLD_MS] — called inside the holding transaction, after its
 * writes are flushed — and then say whether the overlapping side is in the order the hold forces: [finished] (it ran
 * entirely inside the hold), or the engine shows it waiting on this transaction.
 *
 * - **MariaDB**: `information_schema.INNODB_LOCK_WAITS` names a transaction whose blocker runs on this connection —
 *   the engine's own record, for a row lock and for a unique key alike (measured, MariaDB 11.4). It needs `PROCESS`,
 *   which `scripts/run_backend_races_on_mariadb.py` grants its throwaway user; without it this fails by name.
 * - **H2**: `INFORMATION_SCHEMA.SESSIONS.BLOCKER_ID` names this session (a row or primary-key lock); or, for the wait H2
 *   shows no blocker for — another transaction's uncommitted unique key, which H2 retries inside one statement until its
 *   lock timeout — another session has run one statement (`EXECUTING_STATEMENT_START` stays put through the retry) for
 *   [H2_RETRY_EVIDENCE_MS] while this transaction is the only other one holding uncommitted rows ([h2Overlap]).
 *
 * The answer is read just before the commit: a side seen waiting then is released by that commit, so it read before
 * the change and wrote after it — the order the race test asserts on.
 */
fun holdForOverlap(finished: () -> Boolean = { false }): Overlap {
    Thread.sleep(OVERLAP_HOLD_MS) // the change's commit, held back
    if (finished()) return Overlap(true, "the overlapping side finished inside the hold")
    val session = Arc.container().instance(EntityManager::class.java).get().unwrap(Session::class.java)
    return session.doReturningWork { connection ->
        when (val kind = ConfigProvider.getConfig().getValue("quarkus.datasource.db-kind", String::class.java)) {
            "h2" -> h2Overlap(connection, finished)
            "mariadb" -> innodbOverlap(connection)
            else -> throw AssertionError("holdForOverlap has no wait evidence for db-kind $kind")
        }
    }
}

/** MariaDB's half of [holdForOverlap]: the engine's lock-wait record against this connection's transaction. */
private fun innodbOverlap(connection: Connection): Overlap {
    val waits = try {
        connection.createStatement().use { st ->
            st.executeQuery(
                "SELECT r.trx_mysql_thread_id, LEFT(r.trx_query, 160) FROM information_schema.INNODB_LOCK_WAITS w " +
                    "JOIN information_schema.INNODB_TRX b ON b.trx_id = w.blocking_trx_id " +
                    "JOIN information_schema.INNODB_TRX r ON r.trx_id = w.requesting_trx_id " +
                    "WHERE b.trx_mysql_thread_id = CONNECTION_ID()",
            ).use { rs -> generateSequence { if (rs.next()) "connection ${rs.getLong(1)}: ${rs.getString(2)}" else null }.toList() }
        }
    } catch (e: SQLException) {
        throw AssertionError(
            "holdForOverlap reads InnoDB's lock waits, which need the PROCESS privilege (MariaDB error ${e.errorCode}); " +
                "scripts/run_backend_races_on_mariadb.py grants it to its throwaway user",
            e,
        )
    }
    if (waits.isNotEmpty()) return Overlap(true, "InnoDB: waiting on the holder — ${waits.joinToString("; ")}")
    val running = connection.createStatement().use { st ->
        st.executeQuery(
            "SELECT ID, TIME_MS, STATE, LEFT(INFO, 160) FROM information_schema.PROCESSLIST " +
                "WHERE ID <> CONNECTION_ID() AND COMMAND = 'Query'",
        ).use { rs ->
            generateSequence { if (rs.next()) "connection ${rs.getLong(1)} (${rs.getLong(2)} ms, ${rs.getString(3)}): ${rs.getString(4)}" else null }.toList()
        }
    }
    return Overlap(false, "InnoDB: no transaction waited on the holder when it committed; running then: ${running.ifEmpty { listOf("none") }}")
}

private class H2Session(val id: Int, val blocker: Int?, val start: OffsetDateTime?, val uncommitted: Boolean, val statement: String?)

/**
 * H2's half of [holdForOverlap]. The retry evidence is sound because, with this transaction the only other one holding
 * uncommitted rows, nothing else can keep an in-memory statement running: it is retrying on this transaction's key.
 * A statement younger than [H2_RETRY_EVIDENCE_MS] stretches the hold until it is that old or ends.
 */
private fun h2Overlap(connection: Connection, finished: () -> Boolean): Overlap {
    val deadline = System.nanoTime() + H2_RETRY_EVIDENCE_MS * 1_000_000
    while (true) {
        val (me, others) = connection.createStatement().use { st ->
            st.executeQuery(
                "SELECT SESSION_ID(), SESSION_ID, BLOCKER_ID, EXECUTING_STATEMENT_START, CONTAINS_UNCOMMITTED, " +
                    "LEFT(EXECUTING_STATEMENT, 160) FROM INFORMATION_SCHEMA.SESSIONS",
            ).use { rs ->
                var me = -1
                val all = mutableListOf<H2Session>()
                while (rs.next()) {
                    me = rs.getInt(1)
                    all += H2Session(
                        rs.getInt(2), rs.getObject(3) as Int?, rs.getObject(4, OffsetDateTime::class.java), rs.getBoolean(5), rs.getString(6),
                    )
                }
                me to all.filter { it.id != me }
            }
        }
        others.firstOrNull { it.blocker == me }?.let { return Overlap(true, "H2: session ${it.id} is blocked on the holder: ${it.statement}") }
        val now = Instant.now()
        val running = others.filter { it.statement != null && it.start != null }
        val retrying = running.firstOrNull { s ->
            Duration.between(s.start!!.toInstant(), now).toMillis() >= H2_RETRY_EVIDENCE_MS &&
                others.none { it.id != s.id && it.uncommitted }
        }
        if (retrying != null) {
            val ms = Duration.between(retrying.start!!.toInstant(), now).toMillis()
            return Overlap(true, "H2: session ${retrying.id} has run one statement for $ms ms against the holder's uncommitted rows: ${retrying.statement}")
        }
        if (finished()) return Overlap(true, "the overlapping side finished inside the hold")
        val left = (deadline - System.nanoTime()) / 1_000_000
        if (running.isEmpty() || left <= 0) {
            val seen = running.map { "session ${it.id} (${Duration.between(it.start!!.toInstant(), now).toMillis()} ms): ${it.statement}" }
            return Overlap(false, "H2: no session waited on the holder when it committed; running then: ${seen.ifEmpty { listOf("none") }}")
        }
        Thread.sleep(minOf(left, 10L))
    }
}

/**
 * How long [whileUncommitted] waits for either side before it calls the round hung — a hang guard, never a speed bound:
 * what a round asserts is the order the hold forces, not how fast either side ran. A side that waits on the other's row
 * lock is ended by the engine before this (H2's lock timeout is 2 s, InnoDB's `innodb_lock_wait_timeout` 50 s), so the
 * guard sits above the slower engine's ceiling and only a hang outside the engine reaches it. It was 10 s, which
 * slow-but-healthy work crossed: under CPU load a side's first call in the JVM loads its classes, each class file read
 * waits on the host's file scanner, and an HTTP take was measured still in `CaseViews.case`'s class loading 10 s after
 * the change had committed (2026-10-19, load ~60) — `MeasureTargetErasureRacesSliceTest` red with a bare `TimeoutException`.
 */
const val OVERLAP_BOUND_S = 60L

/**
 * Run [change] in a transaction of its own, flush it so its row locks are taken, and hold its commit back
 * [OVERLAP_HOLD_MS]; inside that window run [overlapping] on another thread, and return what it returned once both
 * are done. A plain read does not wait for a row lock, so [overlapping] reads the row as it was before [change] and
 * its own write then waits for [change]'s commit — the shape of every managed write that can revert another
 * writer's column (developer-styleguide §12).
 *
 * The order is forced only while [overlapping] reaches its lock inside the hold: under heavy load a side's first call
 * can take seconds, and that round would race the other order, green (inbox #512). So the round is checked, not
 * trusted: when [overlapping] neither finished inside the hold nor was seen waiting on [change]'s transaction as it
 * committed ([holdForOverlap]), this waits for it and throws [OrderNotForced] instead of returning — its result is from
 * the order the caller does not assert on. Call it inside [raceRounds], which runs such a round again on fresh rows.
 *
 * [change] must write in the transaction this opens. A writer that opens its own (`QuarkusTransaction.requiringNew`,
 * as `TenantHandler.removeMember` does) commits inside the hold, and the race overlaps nothing — green for the wrong
 * reason. Hold such a writer as its first writes, through the store calls it makes (`RemovalRaceSliceTest`).
 */
fun <T> whileUncommitted(change: () -> Unit, overlapping: () -> T): T {
    val changed = CountDownLatch(1)
    val other = AtomicReference<Future<T>?>()
    val pool = Executors.newFixedThreadPool(2)
    try {
        val holder = pool.submit(
            Callable {
                QuarkusTransaction.requiringNew().call {
                    change()
                    Arc.container().instance(EntityManager::class.java).get().flush()
                    changed.countDown()
                    holdForOverlap { other.get()?.isDone == true }
                }
            },
        )
        if (!changed.await(OVERLAP_BOUND_S, TimeUnit.SECONDS)) holder.get(1, TimeUnit.SECONDS) // surfaces the change's own failure
        other.set(
            pool.submit(
                Callable {
                    // A handler reads outside a transaction, which needs the request context a pool thread lacks.
                    val request = Arc.container().requestContext()
                    request.activate()
                    try {
                        overlapping()
                    } finally {
                        request.terminate()
                    }
                },
            ),
        )
        val overlap = holder.get(OVERLAP_BOUND_S, TimeUnit.SECONDS)
        if (overlap.forced) return other.get()!!.get(OVERLAP_BOUND_S, TimeUnit.SECONDS)
        // The other order ran: let it end before the next attempt, and keep whatever it threw beside the verdict.
        val failure = runCatching { other.get()!!.get(OVERLAP_BOUND_S, TimeUnit.SECONDS) }.exceptionOrNull()
        throw OrderNotForced(overlap.evidence).apply { failure?.let(::addSuppressed) }
    } finally {
        pool.shutdownNow()
    }
}

/**
 * A race's answers, as (status, error code), are exactly one [winner] status and one [loser] — the named conflict a
 * replay of the request gets (developer-styleguide §12, the insert half). A 500 is neither, so it fails here by name.
 */
fun assertWinnerAndLoser(winner: Int, loser: Pair<Int, String?>, answers: List<Pair<Int, String?>>, round: String) {
    assertEquals(1, answers.count { it.first == winner }, "$round: exactly one create may win, got $answers")
    assertEquals(1, answers.count { it == loser }, "$round: the loser answers as a replay ($loser), got $answers")
}

/** A response as (status, error code) for [assertWinnerAndLoser]; a body that is not an error envelope — a success,
 *  or a 500's — has no code. */
fun ExtractableResponse<Response>.answer(): Pair<Int, String?> =
    statusCode() to runCatching { path<String?>("code") }.getOrNull()
