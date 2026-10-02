package com.veyq.app.testing

import io.restassured.response.ExtractableResponse
import io.restassured.response.Response
import org.junit.jupiter.api.Assertions.assertEquals
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors

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
