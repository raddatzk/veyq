package me.raddatz.veyq.core.ratelimit

/**
 * The pure token-bucket arithmetic of every rate limit (ADR 0052), lifted out of the shell so the refill,
 * ceiling and `Retry-After` boundaries are unit-tested and mutation-verified without a Quarkus boot
 * (ADR 0045 §2). [TokenBucketCounter] stays the imperative shell: it reads the clock and drives the
 * `ConcurrentHashMap`; it asks these functions for every number it acts on.
 *
 * **Time is monotonic nanos**, passed in as a parameter — never read here. A wall-clock jump (NTP) therefore
 * cannot move a bucket, and negative elapsed time is clamped rather than trusted.
 *
 * **Tokens are integer milli-tokens** (thousandths), never a `Double`: float accumulation across millions of
 * calls drifts, and drift in a mutation-scoped class makes mutants survive for reasons unrelated to the
 * assertions. The trap that follows from integers, and the one most token-bucket implementations get wrong: on
 * refill, [refill] advances `lastRefillNanos` by **the nanos actually converted into tokens**, never to `now` —
 * setting it to `now` discards the sub-token remainder on every call and systematically under-refills under high
 * call rates. `TokenBucketTest.theSubTokenRemainderIsCarriedInsteadOfDiscardedOnEveryCall` fails any
 * implementation that does that.
 *
 * The bucket is **derived from the existing window config** and adds no key (ADR 0052): `capacity =
 * max-per-window` tokens, refilled at `max-per-window / window-seconds` tokens per second — so the sustained rate
 * and the burst size are exactly the ones an operator already tuned.
 */
object TokenBucket {

    /** One request costs one whole token; a token is 1 000 milli-tokens, and all arithmetic is in milli-tokens. */
    const val MILLI_PER_TOKEN = 1_000L

    private const val NANOS_PER_SECOND = 1_000_000_000L

    /** Nanos of elapsed time per milli-token at one token per second — the conversion factor below. */
    private const val NANOS_PER_MILLI_TOKEN_AT_ONE_PER_SECOND = NANOS_PER_SECOND / MILLI_PER_TOKEN

    /**
     * The largest window the conversion can represent. Not a policy ceiling — it is exactly the overflow
     * boundary: one second more and `windowSeconds * NANOS_PER_MILLI_TOKEN_AT_ONE_PER_SECOND` wraps a `Long`.
     */
    private const val MAX_WINDOW_SECONDS = Long.MAX_VALUE / NANOS_PER_MILLI_TOKEN_AT_ONE_PER_SECOND

    /** A bucket's whole state: what it holds, and the instant up to which it has been credited. */
    data class State(val tokensMilli: Long, val lastRefillNanos: Long)

    /** A consume attempt's outcome: the state to store, and the `Retry-After` seconds iff the request was refused. */
    data class Outcome(val state: State, val retryAfterSeconds: Long?)

    /** The ceiling: `maxRequests` whole tokens, in milli-tokens. */
    fun capacityMilli(maxRequests: Long): Long = maxRequests.coerceAtLeast(0) * MILLI_PER_TOKEN

    /**
     * The refill rate, inverted into "nanos of elapsed time that buy one milli-token" — the form the integer
     * arithmetic needs. Derived from the window config: `max` tokens per `window` seconds.
     *
     * **Every input is clamped, and each clamp fails in the safe direction** — a degenerate config must never
     * throw where the window model divided by zero, and must never silently *widen* a limit:
     * - `maxRequests` of 0 yields a zero capacity (every request refused, `Retry-After` = the window) rather than
     *   an `ArithmeticException`;
     * - the floor of 1 nano keeps an absurdly high max — the `%test` profile's effectively-disabled 1 000 000 — a
     *   plain fast refill;
     * - `windowSeconds` saturates at [MAX_WINDOW_SECONDS]. Without that it **wrapped**: the product went
     *   negative and the trailing floor turned it into *one nano per milli-token*, the fastest refill there is.
     *   An absurd window therefore **disabled** the limiter instead of slowing it — the wrong direction to fail
     *   in, and the one asymmetry left when the other two inputs were already guarded.
     */
    fun nanosPerMilliToken(maxRequests: Long, windowSeconds: Long): Long =
        (
            windowSeconds.coerceIn(0, MAX_WINDOW_SECONDS) * NANOS_PER_MILLI_TOKEN_AT_ONE_PER_SECOND /
                maxRequests.coerceAtLeast(1)
            ).coerceAtLeast(1)

    /** A never-seen key starts **full**: a fresh bucket must grant the same burst the first window did. */
    fun full(capacityMilli: Long, nowNanos: Long): State = State(capacityMilli, nowNanos)

    /**
     * Credit the time since `lastRefillNanos`, capped at [capacityMilli].
     *
     * Two boundaries live here. Negative elapsed time (a stale state, a clock oddity) is **clamped to zero** — it
     * never removes tokens. And the sub-token remainder is **carried**: `lastRefillNanos` advances only by
     * `gained × nanosPerMilliToken`, so the leftover nanos still buy their token on a later call. The remainder is
     * discarded only when the bucket saturates, where it is already meaningless (the surplus tokens are dropped
     * too), and dropping it is what stops an idle key from banking unbounded credit.
     */
    fun refill(state: State, nowNanos: Long, capacityMilli: Long, nanosPerMilliToken: Long): State {
        val elapsedNanos = (nowNanos - state.lastRefillNanos).coerceAtLeast(0)
        val gainedMilli = elapsedNanos / nanosPerMilliToken
        return if (gainedMilli >= capacityMilli - state.tokensMilli) {
            State(capacityMilli, nowNanos)
        } else {
            State(state.tokensMilli + gainedMilli, state.lastRefillNanos + gainedMilli * nanosPerMilliToken)
        }
    }

    /**
     * Refill, then spend one token if there is one. Returns the state to store either way — a **refused** request
     * still stores the refill, so the clock is not re-credited from an old instant on the next call.
     */
    fun consume(state: State, nowNanos: Long, capacityMilli: Long, nanosPerMilliToken: Long): Outcome {
        val refilled = refill(state, nowNanos, capacityMilli, nanosPerMilliToken)
        return if (refilled.tokensMilli >= MILLI_PER_TOKEN) {
            Outcome(State(refilled.tokensMilli - MILLI_PER_TOKEN, refilled.lastRefillNanos), null)
        } else {
            Outcome(refilled, retryAfterSeconds(refilled.tokensMilli, nanosPerMilliToken))
        }
    }

    /**
     * The wait until one whole token is available, in whole seconds, rounded **up** and never below 1 — a
     * `Retry-After: 0` would invite an immediate retry that is still refused. This is the honest answer to "when
     * may I retry?", which the fixed window's time-to-the-next-slot-edge never was.
     */
    fun retryAfterSeconds(tokensMilli: Long, nanosPerMilliToken: Long): Long {
        val deficitMilli = MILLI_PER_TOKEN - tokensMilli.coerceAtLeast(0)
        val waitNanos = deficitMilli * nanosPerMilliToken
        return ((waitNanos + NANOS_PER_SECOND - 1) / NANOS_PER_SECOND).coerceAtLeast(1)
    }

    /**
     * Whether the bucket would be **full** at [nowNanos] — the eviction predicate. A full bucket is
     * indistinguishable from an absent one (a never-seen key starts full), so dropping it is exactly free, where
     * the fixed window had to reason about elapsed slots.
     */
    fun isFullAt(state: State, nowNanos: Long, capacityMilli: Long, nanosPerMilliToken: Long): Boolean =
        refill(state, nowNanos, capacityMilli, nanosPerMilliToken).tokensMilli >= capacityMilli
}
