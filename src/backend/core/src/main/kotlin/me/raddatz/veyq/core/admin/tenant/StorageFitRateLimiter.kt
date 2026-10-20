package me.raddatz.veyq.core.admin.tenant

import me.raddatz.veyq.core.config.VeyqConfig
import me.raddatz.veyq.core.ratelimit.TokenBucketCounter
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/**
 * The limiter of the member's storage-fit preflight (`checkTenantStorageFit`, ADR 0082): "would these bytes still fit my
 * tenant's quota?" answers yes or no and never a number, so what needs bounding is a long run of questions that
 * bisects towards the free space. A handler-level limiter like [InviteMailRateLimiter], on the shared
 * [TokenBucketCounter] (ADR 0052), consumed in [TenantStorageHandler] **after** the membership gate - so only a member
 * spends a token - and **before** the sum is read:
 *  - **per (tenantId, userId)** - the tighter, primary bound on one member;
 *  - **per tenantId** - the aggregate, against several members asking in turn.
 *
 * In-memory and per-instance, like every limiter here (a gateway bucket is the production answer). Operator-blind is
 * untouched: the state is two ephemeral counters keyed on ids the server already knows.
 */
@ApplicationScoped
class StorageFitRateLimiter(
    private val perMemberMax: Int,
    private val perTenantMax: Int,
    private val windowSeconds: Long,
) {
    /** CDI wiring: read the `veyq.ratelimit.storage-fit.*` bucket. The primary constructor stays value-only so the
     *  limiter can be bite-tested without stubbing the whole [VeyqConfig] tree. */
    @Inject
    constructor(config: VeyqConfig) : this(
        config.ratelimit().storageFit().perMemberMaxRequests(),
        config.ratelimit().storageFit().perTenantMaxRequests(),
        config.ratelimit().storageFit().windowSeconds(),
    )

    private val memberBuckets = TokenBucketCounter()
    private val tenantBuckets = TokenBucketCounter()

    /**
     * Spend one question for [userId] in [tenantId]. Null when allowed, else the `Retry-After` seconds. The member
     * bucket is charged first, so a rejected member does not use up the tenant's budget.
     */
    fun check(tenantId: String, userId: String): Long? {
        val nowNanos = System.nanoTime()
        // NUL joins the parts unambiguously - it cannot occur in a UUID
        memberBuckets.consume("$tenantId\u0000$userId", nowNanos, perMemberMax.toLong(), windowSeconds)?.let { return it }
        return tenantBuckets.consume(tenantId, nowNanos, perTenantMax.toLong(), windowSeconds)
    }
}
