package me.raddatz.veyq.core.ratelimit

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
 *
 * **And the map forgets by time, whatever its size** (ADR 0092 § 5): every counter registers with its
 * [TokenBucketSweeper] when it is made, and the sweeper's pass every minute ([TokenBucketSweep]) drops every full
 * bucket. A key is a client address or, for the recovery and invite mails, an email address; before the pass it stayed
 * in heap until 100 000 keys were tracked or the process restarted, and now it lives only while its bucket refills.
 * The keys are not hashed: a hash would sit in the same heap for the same time and answer nothing more. Registration
 * is here, not at the counters' users, so every counter has it and none can forget it.
 */
class TokenBucketCounter(
    private val maxTrackedKeys: Int = MAX_TRACKED_KEYS,
    sweeper: TokenBucketSweeper = TokenBucketSweeper.instance,
) {

    /** A bucket and the rate it was last spent at — what a pass needs to tell whether it is full with no caller asking. */
    private class Tracked(val state: TokenBucket.State, val capacityMilli: Long, val nanosPerMilliToken: Long) {
        fun isFullAt(nowNanos: Long): Boolean = TokenBucket.isFullAt(state, nowNanos, capacityMilli, nanosPerMilliToken)
    }

    private val buckets = ConcurrentHashMap<String, Tracked>()

    init {
        sweeper.register(this)
    }

    /**
     * Spends one token of [key]'s budget at [nowNanos] (`System.nanoTime()`), and returns the `Retry-After`
     * seconds if there was none left — or null if the request is allowed.
     */
    fun consume(key: String, nowNanos: Long, maxRequests: Long, windowSeconds: Long): Long? {
        val capacityMilli = TokenBucket.capacityMilli(maxRequests)
        val nanosPerMilliToken = TokenBucket.nanosPerMilliToken(maxRequests, windowSeconds)

        if (buckets.size > maxTrackedKeys) dropFull(nowNanos)

        // compute() is atomic per key, so the refill-and-spend is too; the verdict rides out in a captured local.
        var retryAfterSeconds: Long? = null
        buckets.compute(key) { _, existing ->
            val outcome = TokenBucket.consume(
                state = existing?.state ?: TokenBucket.full(capacityMilli, nowNanos),
                nowNanos = nowNanos,
                capacityMilli = capacityMilli,
                nanosPerMilliToken = nanosPerMilliToken,
            )
            retryAfterSeconds = outcome.retryAfterSeconds
            Tracked(outcome.state, capacityMilli, nanosPerMilliToken)
        }
        return retryAfterSeconds
    }

    /**
     * Drops every bucket that is full at [nowNanos], each judged at the rate it was last spent at. `removeIf` on the
     * map's entries removes an entry only while it still holds the value it judged, so a spend racing a pass is kept.
     */
    internal fun dropFull(nowNanos: Long) {
        buckets.entries.removeIf { it.value.isFullAt(nowNanos) }
    }

    /** How many keys this counter holds — the observable of the eviction tests. */
    internal fun trackedKeys(): Int = buckets.size

    private companion object {
        const val MAX_TRACKED_KEYS = 100_000
    }
}
