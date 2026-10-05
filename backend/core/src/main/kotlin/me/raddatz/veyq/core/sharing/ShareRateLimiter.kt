package me.raddatz.veyq.core.sharing

import me.raddatz.veyq.core.config.VeyqConfig
import me.raddatz.veyq.core.ratelimit.TokenBucketCounter
import jakarta.enterprise.context.ApplicationScoped

/**
 * The per-share bounds on the **anonymous contributor write path** (cr-0101, ADR 0030) — the handler-side
 * enforcement behind `@RateLimit(key = SHARE)` (ADR 0051). In-memory + per-instance: defense-in-depth against an
 * abusive link holder, not a distributed quota (a gateway bucket is the production answer, mirroring the per-IP
 * limiter).
 *
 * **Keyed on `shareId`, not on the caller's IP, and that is the whole point**: a share's guests can all sit behind
 * one proxy address — a per-IP counter would collapse into a global cap on the share while an IP-rotating abuser
 * evaded it. The same reasoning already governs [SharePasswordAttemptLimiter].
 *
 * Two **separate budgets**, because they bound different things and must not consume each other:
 *  - [allowUpload] — the contributor upload/attach flow (`startShareBlobUploads`, `completeShareMedia`). Generous:
 *    a legitimate bulk contributor uploading an evening's photos is the normal case, not the abuse case.
 *  - [allowIdentity] — naming yourself on a contributable share (`createShareIdentity`). This is the one
 *    unauthenticated call that **creates a row before any upload exists**, so an unbounded link holder could grow
 *    the identity table on its own. A real guest does this **once** per share, which is why the cap can be an
 *    order of magnitude tighter than the upload one without ever touching honest traffic.
 */
@ApplicationScoped
class ShareRateLimiter(
    config: VeyqConfig,
) {
    private val uploadRate = config.share().upload().rate()
    private val identityRate = config.share().identity().rate()

    private val uploads = TokenBucketCounter()
    private val identities = TokenBucketCounter()

    /** Spend one upload token for [shareId]; false once the budget is exhausted. */
    fun allowUpload(shareId: String): Boolean =
        uploads.consume(
            key = shareId,
            nowNanos = System.nanoTime(),
            maxRequests = uploadRate.maxPerWindow().toLong(),
            windowSeconds = uploadRate.windowSeconds(),
        ) == null

    /** Spend one identity-creation token for [shareId]; false once the budget is exhausted. */
    fun allowIdentity(shareId: String): Boolean =
        identities.consume(
            key = shareId,
            nowNanos = System.nanoTime(),
            maxRequests = identityRate.maxPerWindow().toLong(),
            windowSeconds = identityRate.windowSeconds(),
        ) == null
}
