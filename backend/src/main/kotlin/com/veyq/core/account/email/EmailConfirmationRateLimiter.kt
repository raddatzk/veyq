package com.veyq.core.account.email

import com.veyq.core.config.VeyqConfig
import com.veyq.core.ratelimit.TokenBucketCounter
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/**
 * The address-confirmation mail limiter (ADR 0074 Decision 4) — sibling of `RecoveryRateLimiter`, with one axis
 * instead of two.
 *
 * Keyed on the **account id** only. The caller is authenticated and can mail nothing but their own address, so the
 * one abuse this bounds is an account turning its tenant's relay into a mail cannon aimed at itself — which a
 * per-account bucket bounds whole. No per-tenant or per-instance cap is added: the population of accounts is
 * bounded by invitations (nobody can register freely), so the per-account bound already bounds every relay. The
 * generic `@RateLimit(key = USER)` bucket (600 per minute) is far too loose for mail, which is why this exists.
 *
 * Charged after the transport and membership checks and before the mint, so a limited request never mints. In
 * memory and per instance, like every limiter here.
 */
@ApplicationScoped
class EmailConfirmationRateLimiter(
    private val perAccountMax: Int,
    private val windowSeconds: Long,
) {
    /** CDI wiring: read the `veyq.ratelimit.email-confirmation.*` bucket. The primary constructor stays value-only so
     *  the limiter can be tested without stubbing the whole [VeyqConfig] tree. */
    @Inject
    constructor(config: VeyqConfig) : this(
        config.ratelimit().emailConfirmation().perAccountMaxRequests(),
        config.ratelimit().emailConfirmation().windowSeconds(),
    )

    private val accountBuckets = TokenBucketCounter()

    /** Spend one confirmation mail for [userId]. Returns `null` when allowed, or the `Retry-After` seconds. */
    fun check(userId: String): Long? =
        accountBuckets.consume(userId, System.nanoTime(), perAccountMax.toLong(), windowSeconds)
}
