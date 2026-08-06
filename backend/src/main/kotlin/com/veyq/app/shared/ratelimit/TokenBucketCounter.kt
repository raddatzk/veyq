package com.veyq.app.shared.ratelimit

import java.util.concurrent.ConcurrentHashMap

/**
 * The in-memory, per-key rate limiter shared by **every** bound in the app — the imperative shell around the pure,
 * mutation-verified [TokenBucket] arithmetic (ADR 0052, ADR 0045 §2). It drives the concurrent map and takes the
 * monotonic clock as a parameter; it asks [TokenBucket] for every number and boundary it acts on, and
 * re-implements none of them. **That "every bound" is enforced, not claimed:**
 * `scripts/validate_ratelimit_core.py` keeps rate-limit state out of every limiter outside this package.
 *
 * The caller passes the **window config it already has** (`maxRequests` per `windowSeconds`); the bucket is
 * derived from it (ADR 0052) — capacity = `maxRequests`, refilled at `maxRequests / windowSeconds` per second — so
 * an operator's existing tuning keeps both its burst size and its sustained rate.
 *
 * **Deliberate limitations** (a gateway / distributed bucket is the production answer, see backend CLAUDE.md):
 * buckets are **per-instance** (not shared across a horizontally-scaled deployment) and **reset on restart**.
 *
 * The map is bounded **in steady state**, not absolutely, and the difference is worth stating rather than
 * rounding off: past [maxTrackedKeys] the eviction pass drops every **full** bucket, and a bucket is full again
 * one token's worth of time after its last request — so a flood of distinct (possibly spoofed) keys is reclaimed
 * as fast as it goes idle. What it does *not* do is bound a flood that arrives **faster than one token refills**:
 * those buckets are all partly spent, nothing is evictable, and the map grows for as long as the flood outpaces
 * the refill. At the per-IP bound's one-token-per-second that needs ~100 000 distinct keys per second, which is
 * why this is a documented shape rather than a hole — and it is the same shape the fixed window had.
 *
 * A full bucket is indistinguishable from an absent one (a never-seen key starts full), so the eviction is free
 * of behavioural cost — the caller cannot tell whether its key was kept or re-created.
 */
class TokenBucketCounter(private val maxTrackedKeys: Int = MAX_TRACKED_KEYS) {

    private val buckets = ConcurrentHashMap<String, TokenBucket.State>()

    /**
     * Spends one token of [key]'s budget at [nowNanos] (`System.nanoTime()`), and returns the `Retry-After`
     * seconds if there was none left — or null if the request is allowed.
     */
    fun consume(key: String, nowNanos: Long, maxRequests: Long, windowSeconds: Long): Long? {
        val capacityMilli = TokenBucket.capacityMilli(maxRequests)
        val nanosPerMilliToken = TokenBucket.nanosPerMilliToken(maxRequests, windowSeconds)

        if (buckets.size > maxTrackedKeys) {
            buckets.entries.removeIf {
                TokenBucket.isFullAt(it.value, nowNanos, capacityMilli, nanosPerMilliToken)
            }
        }

        // compute() is atomic per key, so the refill-and-spend is too; the verdict rides out in a captured local.
        var retryAfterSeconds: Long? = null
        buckets.compute(key) { _, existing ->
            val outcome = TokenBucket.consume(
                state = existing ?: TokenBucket.full(capacityMilli, nowNanos),
                nowNanos = nowNanos,
                capacityMilli = capacityMilli,
                nanosPerMilliToken = nanosPerMilliToken,
            )
            retryAfterSeconds = outcome.retryAfterSeconds
            outcome.state
        }
        return retryAfterSeconds
    }

    private companion object {
        const val MAX_TRACKED_KEYS = 100_000
    }
}
