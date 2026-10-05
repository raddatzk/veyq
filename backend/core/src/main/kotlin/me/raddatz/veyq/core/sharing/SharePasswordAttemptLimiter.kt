package me.raddatz.veyq.core.sharing

import me.raddatz.veyq.core.config.VeyqConfig
import me.raddatz.veyq.core.ratelimit.TokenBucketCounter
import jakarta.enterprise.context.ApplicationScoped

/**
 * A best-effort, per-share bound on **wrong** share-password attempts (cr-0165, ADR 0037) — the online
 * brute-force bound ADR 0037 relies on, and the one bound whose *security* argument is this arithmetic rather
 * than its UX. In-memory + per-instance, on the shared [TokenBucketCounter] like every other bound (ADR 0052; a
 * gateway bucket is still the production answer). Keyed on `shareId`.
 *
 * **Only a wrong attempt spends a token** (the caller — `SharingHandler.passwordCheck` — invokes this only when
 * the token mismatched): a correct password is admitted before this is ever reached, so a legitimate guest is
 * never locked out, and a tokenless (challenge-fetch) request never spends budget.
 *
 * **What the bucket changed here, deliberately** (ADR 0052 § Consequences): a guest who burns the budget no
 * longer faces a wall until a window rolls — they regain one guess per `window / max` seconds. Guess *throughput*,
 * which is what bounds a brute force, is unchanged; the 2× window-boundary burst is gone. Wanting the wall back is
 * a penalty/backoff feature, not a property of this core.
 */
@ApplicationScoped
class SharePasswordAttemptLimiter(
    config: VeyqConfig,
) {
    private val maxPerWindow: Int = config.share().password().rate().maxPerWindow()
    private val windowSeconds: Long = config.share().password().rate().windowSeconds()

    private val attempts = TokenBucketCounter()

    /** Spend one token for [shareId] on a wrong attempt; returns false once the budget is exhausted. */
    fun allow(shareId: String): Boolean =
        attempts.consume(
            key = shareId,
            nowNanos = System.nanoTime(),
            maxRequests = maxPerWindow.toLong(),
            windowSeconds = windowSeconds,
        ) == null
}
