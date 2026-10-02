package com.veyq.app.testing

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
