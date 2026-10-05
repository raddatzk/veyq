package com.veyq.core.ratelimit

import com.veyq.core.admin.setup.SetupResource
import com.veyq.core.admin.tenant.TenantsResource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Pins that the public (`@PermitAll`) account-creation endpoints declare `@RateLimit(key = IP)` (cr-0130, folded
 * onto the ADR 0051 vocabulary). The limiter is name-bound, so a silently dropped annotation would un-limit an
 * unauthenticated write with no other signal. The filter *mechanism* (per-IP token bucket, 429) is covered by
 * `AuthRateLimitTest` / `AuthRateLimitRefillTest`; this test guards the wiring.
 *
 * It is a **spot-check on three endpoints** — the universal *every endpoint declares a posture* sweep is
 * `RateLimitPostureSweepTest`. The two are not redundant: the sweep proves a posture exists, this proves these
 * three carry the *right* one.
 */
class RateLimitedEndpointsTest {

    // Bridge/synthetic overloads carry no annotations — resolving to one of those would read as "undeclared" and
    // make this test pass or fail for a reason that has nothing to do with the posture.
    private fun postureOf(cls: Class<*>, method: String): RateLimitPosture =
        RateLimitPosture.of(cls, cls.methods.single { it.name == method && !it.isBridge && !it.isSynthetic })

    @Test
    fun publicAccountCreationEndpointsAreIpRateLimited() {
        val expected = RateLimitPosture.Limited(RateLimitKey.IP)
        assertEquals(expected, postureOf(SetupResource::class.java, "createServerOwner"))
        assertEquals(expected, postureOf(TenantsResource::class.java, "previewTenantInvite"))
        assertEquals(expected, postureOf(TenantsResource::class.java, "registerAndAcceptTenantInvite"))
    }
}
