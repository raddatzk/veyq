package com.veyq.app.shared.ratelimit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The imperative shell around [TokenBucket]: per-key isolation, the strict boundary, the `Retry-After` it hands
 * back, the gradual refill, and the bounded map. The clock is a parameter here, so these are pure unit tests —
 * the Quarkus-booted siblings ([AuthRateLimitTest], [AuthRateLimitRefillTest]) prove the *wiring*, not the
 * arithmetic; the arithmetic's own boundaries are pinned (and mutation-verified) in [TokenBucketTest].
 */
class TokenBucketCounterTest {

    private val max = 3L
    private val window = 60L // 3 per 60s → capacity 3, one token back every 20s
    private val second = 1_000_000_000L

    @Test
    fun theRequestPastTheAllowanceIsTheFirstOneRejected() {
        val counter = TokenBucketCounter()
        val now = 1_000L * second
        repeat(3) { assertNull(counter.consume("k", now, max, window), "within the allowance") }
        assertNotNull(counter.consume("k", now, max, window), "the 4th request is over the limit of 3")
    }

    @Test
    fun oneKeyExhaustingItsBudgetDoesNotAffectAnother() {
        val counter = TokenBucketCounter()
        val now = 1_000L * second
        repeat(4) { counter.consume("noisy", now, max, window) }
        assertNotNull(counter.consume("noisy", now, max, window), "the noisy key stays throttled")
        assertNull(counter.consume("quiet", now, max, window), "a different key has its own budget")
    }

    @Test
    fun theRetryAfterIsTheWaitForTheNextToken() {
        val counter = TokenBucketCounter()
        val now = 1_000L * second
        repeat(3) { counter.consume("k", now, max, window) }
        assertEquals(20L, counter.consume("k", now, max, window), "one token accrues every 20s at 3 per 60s")
        assertEquals(10L, counter.consume("k", now + 10 * second, max, window), "10s later only 10s remain of it")
    }

    @Test
    fun theBudgetComesBackGraduallyRatherThanAtAWindowEdge() {
        val counter = TokenBucketCounter()
        val now = 1_000L * second
        repeat(4) { counter.consume("k", now, max, window) }
        assertNotNull(counter.consume("k", now + 19 * second, max, window), "19s in, no whole token yet")
        assertNull(counter.consume("k", now + 20 * second, max, window), "at 20s the first token is back")
        assertNotNull(counter.consume("k", now + 20 * second, max, window), "and only that one — not the whole cap")
    }

    @Test
    fun aFullBucketIsEvictedOnceTheMapGrowsPastItsBoundAndALiveOneIsNot() {
        // maxTrackedKeys = 1, so a second distinct key triggers the sweep. The predicate is "the bucket is full",
        // which is exactly a key that has gone quiet long enough to be indistinguishable from an absent one — so
        // eviction must not touch a still-throttled key. A mutant that drops everything fails the second half.
        val counter = TokenBucketCounter(maxTrackedKeys = 1)
        val now = 1_000L * second
        repeat(4) { counter.consume("throttled", now, max, window) }

        counter.consume("a", now + second, max, window)
        counter.consume("b", now + second, max, window) // pushes past the bound → sweep runs
        assertNotNull(
            counter.consume("throttled", now + second, max, window),
            "the sweep must spare a bucket that is not yet full — dropping it would hand back the whole cap",
        )

        // A full window later "throttled" is replenished, hence indistinguishable from absent: dropping it is free.
        counter.consume("c", now + 60 * second, max, window)
        counter.consume("d", now + 60 * second, max, window)
        assertNull(counter.consume("throttled", now + 60 * second, max, window), "a replenished key is allowed")
    }
}
