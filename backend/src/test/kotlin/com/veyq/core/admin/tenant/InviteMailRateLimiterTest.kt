package com.veyq.core.admin.tenant

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Bite tests for the member-invite anti-bombing limiter (cr-email-invite-send-hardening). Pure — no Quarkus boot;
 * the value constructor lets us pin tight limits and cross the boundary deterministically. The pure token-bucket
 * arithmetic itself is mutation-verified in `TokenBucketTest` (ADR 0052); this asserts the two-axis wiring on top
 * of it.
 */
class InviteMailRateLimiterTest {

    private val tenant = "tenant-1"

    @Test
    fun theFourthInviteToOneRecipientIsRateLimited() {
        // per-recipient max = 3 within a wide window (so the tenant cap can't be what trips first).
        val limiter = InviteMailRateLimiter(perRecipientMax = 3, perTenantMax = 1000, windowSeconds = 3600)
        // The design must FAIL here on the 4th send: three pass, the fourth is refused with a Retry-After.
        repeat(3) { assertNull(limiter.check(tenant, "victim@example.com"), "invite ${it + 1} of 3 should pass") }
        val retryAfter = limiter.check(tenant, "victim@example.com")
        assertNotNull(retryAfter, "the 4th invite to the same recipient must be rate-limited")
        assertTrue(retryAfter!! in 1..3600, "Retry-After is a positive within-window second count, was $retryAfter")
    }

    @Test
    fun aDifferentRecipientHasItsOwnBudget() {
        val limiter = InviteMailRateLimiter(perRecipientMax = 3, perTenantMax = 1000, windowSeconds = 3600)
        repeat(3) { limiter.check(tenant, "a@example.com") }
        assertNull(limiter.check(tenant, "b@example.com"), "a different recipient key is not throttled by a@'s window")
    }

    @Test
    fun theRecipientKeyIsCaseInsensitiveSoAliasCasingCannotEvadeIt() {
        val limiter = InviteMailRateLimiter(perRecipientMax = 3, perTenantMax = 1000, windowSeconds = 3600)
        limiter.check(tenant, "Victim@Example.com")
        limiter.check(tenant, "victim@example.com")
        limiter.check(tenant, "VICTIM@EXAMPLE.COM")
        assertNotNull(limiter.check(tenant, "victim@example.com"), "casing variants share one recipient window (normalized)")
    }

    @Test
    fun theTenantVolumeCapTripsAcrossDistinctRecipients() {
        // per-recipient is generous; the tenant total (5) is what must bound an owner blasting many addresses.
        val limiter = InviteMailRateLimiter(perRecipientMax = 100, perTenantMax = 5, windowSeconds = 3600)
        repeat(5) { assertNull(limiter.check(tenant, "user$it@example.com"), "send ${it + 1} of 5 should pass") }
        assertNotNull(limiter.check(tenant, "user6@example.com"), "the 6th send from the tenant (fresh recipient) hits the volume cap")
    }

    @Test
    fun adifferentTenantIsIsolated() {
        val limiter = InviteMailRateLimiter(perRecipientMax = 1, perTenantMax = 1, windowSeconds = 3600)
        assertNull(limiter.check("tenant-A", "x@example.com"))
        assertNull(limiter.check("tenant-B", "x@example.com"), "the same recipient under a different tenant has its own window")
    }
}
