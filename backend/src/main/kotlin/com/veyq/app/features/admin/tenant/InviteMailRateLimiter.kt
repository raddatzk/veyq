package com.veyq.app.features.admin.tenant

import com.veyq.app.shared.config.VeyqConfig
import com.veyq.app.shared.ratelimit.TokenBucketCounter
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/**
 * The member-invite mail limiter (cr-email-invite-send-hardening) — the **real** anti-bombing guard.
 *
 * The per-IP `@RateLimit(key = IP)`/`IpRateLimitFilter` machinery keys on the client IP, which an authenticated owner
 * sits behind and which cannot express the thing that actually needs bounding: **how many invites reach one
 * victim address**. So this is a dedicated, handler-level limiter — a sibling of `ShareRateLimiter` — consumed in
 * [TenantHandler] on the two tenant-owner member paths that drive SMTP mail (`inviteMember`, `reissueMemberInvite`),
 * **after** the authorization guards pass and **before** the mint, so a limited request creates no orphan invite
 * and only authorized owners consume a slot.
 *
 * Two buckets on the shared [TokenBucketCounter] (ADR 0052, over the mutation-verified core of ADR 0045 §2) —
 * two axes, one core, no arithmetic of its own:
 *  - **per (tenantId, recipient)** — the tighter, primary guard against bombing one address;
 *  - **per tenantId** — the volume cap against an owner-as-sender.
 *
 * The bucket's gradual refill is an **improvement** on this axis specifically (ADR 0052 § Consequences): 3 mails
 * per hour per recipient stops arriving as a burst of 3 and becomes one per 20 minutes.
 *
 * In-memory + per-instance — defense-in-depth, not a distributed quota (a gateway bucket is the production answer,
 * as for the auth limiter). Operator-blind is untouched: the state is ephemeral and the recipient address it keys
 * on is already server-visible plaintext (ADR 0049 §4).
 */
@ApplicationScoped
class InviteMailRateLimiter(
    private val perRecipientMax: Int,
    private val perTenantMax: Int,
    private val windowSeconds: Long,
) {
    /** CDI wiring: read the `veyq.ratelimit.invite.*` bucket. The primary constructor stays value-only so the
     *  limiter can be bite-tested without stubbing the whole [VeyqConfig] tree. */
    @Inject
    constructor(config: VeyqConfig) : this(
        config.ratelimit().invite().perRecipientMaxRequests(),
        config.ratelimit().invite().perTenantMaxRequests(),
        config.ratelimit().invite().windowSeconds(),
    )

    private val recipientBuckets = TokenBucketCounter()
    private val tenantBuckets = TokenBucketCounter()

    /**
     * Spend one invite-send token for [tenantId] → [recipientEmail]. Returns `null` when allowed, or the
     * `Retry-After` seconds when a budget is exhausted. The recipient (tighter) bucket is charged first, so a
     * rejected recipient does not consume the tenant budget; if the recipient passes but the tenant cap trips, the
     * recipient token was already spent — a deliberate, slightly-conservative over-count (fails safe).
     */
    fun check(tenantId: String, recipientEmail: String): Long? {
        val nowNanos = System.nanoTime()
        // Key on the same lowercase normalization the mint uses (idempotent — callers already normalize).
        // NUL joins the two key parts unambiguously — it cannot occur in a UUID tenantId or an email.
        val recipientKey = "$tenantId\u0000${recipientEmail.lowercase()}"
        recipientBuckets.consume(recipientKey, nowNanos, perRecipientMax.toLong(), windowSeconds)
            ?.let { return it }
        return tenantBuckets.consume(tenantId, nowNanos, perTenantMax.toLong(), windowSeconds)
    }
}
