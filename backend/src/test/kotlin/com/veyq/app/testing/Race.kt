package com.veyq.app.testing

import io.quarkus.arc.Arc
import io.quarkus.narayana.jta.QuarkusTransaction
import io.restassured.response.ExtractableResponse
import io.restassured.response.Response
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
 * Run [change] in a transaction of its own, flush it so its row locks are taken, and hold its commit back
 * [OVERLAP_HOLD_MS]; inside that window run [overlapping] on another thread, and return what it returned once both
 * are done. A plain read does not wait for a row lock, so [overlapping] reads the row as it was before [change] and
 * its own write then waits for [change]'s commit — the shape of every managed write that can revert another
 * writer's column (developer-styleguide §12). Deterministic, not likely: the order is forced, so one round already
 * shows the defect; the callers still run [RACE_ROUNDS] rounds on fresh rows.
 */
fun <T> whileUncommitted(change: () -> Unit, overlapping: () -> T): T {
    val changed = CountDownLatch(1)
    val pool = Executors.newFixedThreadPool(2)
    try {
        val holder = pool.submit {
            QuarkusTransaction.requiringNew().run {
                change()
                Arc.container().instance(EntityManager::class.java).get().flush()
                changed.countDown()
                Thread.sleep(OVERLAP_HOLD_MS) // the change's commit, held back
            }
        }
        if (!changed.await(10, TimeUnit.SECONDS)) holder.get(1, TimeUnit.SECONDS) // surfaces the change's own failure
        val other = pool.submit(
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
        )
        holder.get(10, TimeUnit.SECONDS)
        return other.get(10, TimeUnit.SECONDS)
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
