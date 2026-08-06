package com.veyq.app.features.account.recovery

import com.veyq.app.shared.config.VeyqConfig
import com.veyq.app.shared.ratelimit.TokenBucketCounter
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/**
 * The password-recovery mail limiter (cr-account-recovery, ADR 0054) — sibling of `InviteMailRateLimiter`, with
 * a deliberately different key.
 *
 * The invite limiter keys per-(tenant, recipient) because an invite is sent *by* a tenant. A recovery request is
 * sent by nobody: it is unauthenticated and an account is **tenant-global**, so there is no tenant to key on.
 * Two axes replace it:
 *  - **per address** — the anti-bombing guard. Without it a stranger can mail-flood any address they can guess,
 *    since the endpoint deliberately accepts unknown addresses (non-enumerability, ADR 0054 § Decision 6).
 *  - **per instance** — the volume cap against a scraper walking a list of addresses.
 *
 * Charged **before** the account lookup, so a limited request never reveals timing differences between a known
 * and an unknown address, and never mints. The address bucket is charged first, so a rejected address does not
 * consume instance budget.
 *
 * In-memory + per-instance, like every other limiter here — defense-in-depth, not a distributed quota. The
 * address it keys on is already server-visible plaintext (it is the login identifier, ADR 0003).
 */
@ApplicationScoped
class RecoveryRateLimiter(
    private val perAddressMax: Int,
    private val perInstanceMax: Int,
    private val windowSeconds: Long,
) {
    /** CDI wiring: read the `veyq.ratelimit.recovery.*` bucket. The primary constructor stays value-only so the
     *  limiter can be bite-tested without stubbing the whole [VeyqConfig] tree. */
    @Inject
    constructor(config: VeyqConfig) : this(
        config.ratelimit().recovery().perAddressMaxRequests(),
        config.ratelimit().recovery().perInstanceMaxRequests(),
        config.ratelimit().recovery().windowSeconds(),
    )

    private val addressBuckets = TokenBucketCounter()
    private val instanceBucket = TokenBucketCounter()

    /**
     * Spend one recovery-request token for [email]. Returns `null` when allowed, or the `Retry-After` seconds
     * when a budget is exhausted.
     */
    fun check(email: String): Long? {
        val nowNanos = System.nanoTime()
        addressBuckets.consume(email.lowercase(), nowNanos, perAddressMax.toLong(), windowSeconds)
            ?.let { return it }
        return instanceBucket.consume(INSTANCE_KEY, nowNanos, perInstanceMax.toLong(), windowSeconds)
    }

    private companion object {
        /** One shared bucket for the whole instance — the key is a constant, not a dimension. */
        private const val INSTANCE_KEY = "instance"
    }
}
