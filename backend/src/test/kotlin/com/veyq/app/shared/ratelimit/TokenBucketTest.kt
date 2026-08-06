package com.veyq.app.shared.ratelimit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The **mutation-scoped** unit test of the pure token-bucket arithmetic (ADR 0045 §2, ADR 0052) — every boundary
 * of [TokenBucket] is pinned here, so `pitest` can prove the assertions and not merely their existence. The
 * clock is a parameter, so these are pure; the Quarkus-booted siblings ([AuthRateLimitTest],
 * [AuthRateLimitRefillTest]) prove the *wiring*, not the arithmetic.
 *
 * The derivation under test throughout (ADR 0052): `capacity = max`, refilled at `max / window` tokens per second.
 * `max = 60 / window = 60 s` is one token per second — 1 000 000 nanos per milli-token — which is why the numbers
 * below read in milliseconds.
 */
class TokenBucketTest {

    private val max = 60L
    private val window = 60L
    private val capacityMilli = TokenBucket.capacityMilli(max)
    private val nanosPerMilliToken = TokenBucket.nanosPerMilliToken(max, window)

    @Test
    fun theBucketIsDerivedFromTheWindowConfigSoTheOldTuningKeepsItsMeaning() {
        assertEquals(60_000L, capacityMilli, "capacity is the old cap: 60 requests, in milli-tokens")
        assertEquals(1_000_000L, nanosPerMilliToken, "60 per 60s is one token per second — 1ms per milli-token")
        assertEquals(1_200_000_000L, TokenBucket.nanosPerMilliToken(3, 3600), "3 per hour is one token per 20min")

        // The sustained rate IS the old one: from empty, exactly `window` seconds buy back exactly `capacity`.
        val refilled = TokenBucket.refill(
            TokenBucket.State(0, 0), window * 1_000_000_000L, capacityMilli, nanosPerMilliToken,
        )
        assertEquals(capacityMilli, refilled.tokensMilli, "one window of elapsed time refills the whole cap")
    }

    @Test
    fun aFreshBucketStartsFullSoTheFirstBurstIsTheOldCap() {
        val fresh = TokenBucket.full(capacityMilli, 12_345L)
        assertEquals(capacityMilli, fresh.tokensMilli, "a never-seen key gets the whole burst")
        assertEquals(12_345L, fresh.lastRefillNanos, "credited from now, not from the epoch")
    }

    @Test
    fun theRequestPastTheAllowanceIsTheFirstOneRejected() {
        val cap = TokenBucket.capacityMilli(3)
        val perMilli = TokenBucket.nanosPerMilliToken(3, window) // 3 per 60s → one token per 20s
        var state = TokenBucket.full(cap, 0)
        repeat(3) {
            val outcome = TokenBucket.consume(state, 0, cap, perMilli)
            assertNull(outcome.retryAfterSeconds, "request ${it + 1} is inside the allowance of 3")
            state = outcome.state
        }
        assertEquals(0L, state.tokensMilli, "the third request left the bucket exactly empty")
        val refused = TokenBucket.consume(state, 0, cap, perMilli)
        assertNotNull(refused.retryAfterSeconds, "the 4th request has no token to spend")
        assertEquals(20L, refused.retryAfterSeconds, "one token costs 20s at 3 per 60s")
    }

    @Test
    fun refillIsGradualSoARefusedCallerRegainsOneTokenAtATime() {
        val cap = TokenBucket.capacityMilli(3)
        val perMilli = TokenBucket.nanosPerMilliToken(3, window) // one token per 20s
        val empty = TokenBucket.State(0, 0)

        assertNotNull(
            TokenBucket.consume(empty, 19_000_000_000L, cap, perMilli).retryAfterSeconds,
            "19s after emptying, less than one token has accrued — still refused",
        )
        val allowed = TokenBucket.consume(empty, 20_000_000_000L, cap, perMilli)
        assertNull(allowed.retryAfterSeconds, "at 20s exactly one token has accrued and is spendable")
        assertEquals(0L, allowed.state.tokensMilli, "spending that one token empties the bucket again")
    }

    @Test
    fun refillNeverExceedsTheCapacitySoIdleTimeCannotBankABiggerBurst() {
        val idleForADay = TokenBucket.refill(
            TokenBucket.State(0, 0), 86_400_000_000_000L, capacityMilli, nanosPerMilliToken,
        )
        assertEquals(capacityMilli, idleForADay.tokensMilli, "a day of idling still buys only the cap")
        assertEquals(86_400_000_000_000L, idleForADay.lastRefillNanos, "a saturated bucket is credited up to now")
    }

    @Test
    fun theSaturationBoundaryIsExactFromAPartlyFilledBucket() {
        // The saturation test is `gained >= capacity - tokens`, and both halves of it need a case where they are
        // *distinguishable* — PIT found that they weren't. From an EMPTY bucket `capacity - tokens` equals
        // `capacity + tokens`, so a corrupted headroom is invisible; and where the elapsed nanos divide exactly,
        // saturating and not-saturating leave the same state. Hence: partly filled, and an inexact division.
        val cap = TokenBucket.capacityMilli(3)
        val perMilli = TokenBucket.nanosPerMilliToken(3, window) // one token per 20s
        val partlyFilled = TokenBucket.State(tokensMilli = 2_000, lastRefillNanos = 0)

        val saturated = TokenBucket.refill(partlyFilled, 30_000_000_000L, cap, perMilli) // 1 500 milli accrued
        assertEquals(cap, saturated.tokensMilli, "1 500 milli into 1 000 of headroom still stops at the ceiling")

        // Exactly at the boundary, with a half-token remainder pending: the bucket is full, so the remainder is
        // deliberately dropped and the credit mark jumps to now — the one place discarding it costs nothing.
        val exactlyFull = TokenBucket.refill(TokenBucket.State(0, 0), 60_010_000_000L, cap, perMilli)
        assertEquals(cap, exactlyFull.tokensMilli, "exactly one capacity's worth of time fills it, no more")
        assertEquals(60_010_000_000L, exactlyFull.lastRefillNanos, "a full bucket is credited to now, remainder dropped")
    }

    @Test
    fun theSubTokenRemainderIsCarriedInsteadOfDiscardedOnEveryCall() {
        // THE trap (ADR 0052): advancing `lastRefillNanos` to `now` instead of by the nanos actually converted
        // discards the sub-token remainder on every call, so a high call rate systematically under-refills.
        // Ten calls 1.5ms apart credit 15ms of time == 15 milli-tokens. An "advance to now" implementation
        // credits 1 milli-token per call and lands on 10 — this assertion is what makes it go red.
        var state = TokenBucket.State(0, 0)
        repeat(10) { state = TokenBucket.refill(state, (it + 1) * 1_500_000L, capacityMilli, nanosPerMilliToken) }
        assertEquals(15L, state.tokensMilli, "15ms of elapsed time is 15 milli-tokens, remainder carried")
        assertEquals(15_000_000L, state.lastRefillNanos, "credited exactly up to the tokens handed out")
    }

    @Test
    fun negativeElapsedTimeIsClampedSoAClockOddityCannotTakeTokensAway() {
        val state = TokenBucket.State(tokensMilli = 5_000, lastRefillNanos = 10_000_000_000L)
        val backwards = TokenBucket.refill(state, 9_000_000_000L, capacityMilli, nanosPerMilliToken)
        assertEquals(5_000L, backwards.tokensMilli, "a now before the last refill neither adds nor removes tokens")
        assertEquals(10_000_000_000L, backwards.lastRefillNanos, "and the credit mark never moves backwards")
    }

    @Test
    fun theRetryAfterIsTheWaitForOneWholeTokenRoundedUpAndNeverZero() {
        assertEquals(1L, TokenBucket.retryAfterSeconds(0, nanosPerMilliToken), "one token per second → 1s")
        assertEquals(1L, TokenBucket.retryAfterSeconds(500, nanosPerMilliToken), "half a token short still rounds up")
        assertEquals(
            1L, TokenBucket.retryAfterSeconds(999, TokenBucket.nanosPerMilliToken(1_000_000, window)),
            "never 0 — a Retry-After of 0 invites a retry that is refused again",
        )
        assertEquals(1_200L, TokenBucket.retryAfterSeconds(0, TokenBucket.nanosPerMilliToken(3, 3600)),
            "3 per hour → 20 minutes for the next token")
    }

    @Test
    fun aFullBucketIsEvictableAndAPartlySpentOneIsNot() {
        val cap = TokenBucket.capacityMilli(3)
        val perMilli = TokenBucket.nanosPerMilliToken(3, window) // one token per 20s
        val empty = TokenBucket.State(0, 0)
        assertFalse(TokenBucket.isFullAt(empty, 40_000_000_000L, cap, perMilli), "2 of 3 tokens back — still live")
        assertTrue(TokenBucket.isFullAt(empty, 60_000_000_000L, cap, perMilli), "fully refilled — free to drop")
    }

    @Test
    fun aDegenerateConfigRefusesInsteadOfThrowing() {
        // The window model divided by `windowSeconds` and multiplied against `maxRequests` with no guard; a
        // max of 0 (an operator closing an endpoint) must mean "refuse", not an ArithmeticException in a filter.
        val cap = TokenBucket.capacityMilli(0)
        val perMilli = TokenBucket.nanosPerMilliToken(0, window)
        assertEquals(0L, cap, "a max of 0 is a bucket that holds nothing")
        val outcome = TokenBucket.consume(TokenBucket.full(cap, 0), 0, cap, perMilli)
        assertEquals(60L, outcome.retryAfterSeconds, "refused, with the window as the wait")
        assertEquals(
            1L, TokenBucket.nanosPerMilliToken(10_000_000_000L, window),
            "an absurdly high max clamps to the fastest representable refill instead of dividing to zero",
        )
    }

    @Test
    fun anAbsurdWindowSaturatesInsteadOfWrappingTheConversion() {
        // The conversion multiplies the window by NANOS_PER_SECOND / MILLI_PER_TOKEN. Both other inputs were
        // clamped and this one was not — and the asymmetry was the tell. The damage is the wrap: a window past
        // Long.MAX_VALUE / 1_000_000 overflowed to a negative product, which the trailing coerceAtLeast(1) then
        // turned into ONE nano per milli-token — the fastest refill representable. An absurd window silently
        // *disabled* the limiter instead of slowing it to a crawl, which is the wrong direction to fail in.
        val largestRepresentable = Long.MAX_VALUE / 1_000_000L
        val atTheCeiling = TokenBucket.nanosPerMilliToken(maxRequests = 1, windowSeconds = largestRepresentable)

        assertEquals(
            largestRepresentable * 1_000_000L, atTheCeiling,
            "the largest representable window still converts exactly — the clamp must not bite one below it",
        )
        assertEquals(
            atTheCeiling, TokenBucket.nanosPerMilliToken(1, largestRepresentable + 1),
            "one second past the ceiling saturates instead of wrapping",
        )
        assertEquals(
            atTheCeiling, TokenBucket.nanosPerMilliToken(1, Long.MAX_VALUE),
            "and so does the extreme",
        )
        assertTrue(atTheCeiling > 0, "a wrapped conversion goes non-positive, which is how it became unlimited")
    }
}
