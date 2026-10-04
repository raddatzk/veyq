package com.veyq.core.ratelimit

import com.veyq.core.testing.TestFixtures.bearer
import com.veyq.core.testing.TestFixtures.seedUser
import io.quarkus.security.Authenticated
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.core.Response
import org.hamcrest.CoreMatchers.equalTo
import org.junit.jupiter.api.Test

/**
 * Proves [UserRateLimitFilter] actually enforces `@RateLimit(key = USER)`: the window keys on the **principal**,
 * not the address, and it fires past the limit with the same `429 rate_limited` + `Retry-After` envelope as the
 * per-IP bound.
 *
 * It runs against a **test-scope probe resource** because no production endpoint declares `key = USER` yet. That
 * is deliberate rather than a stopgap: ADR 0051 states the key is boundary-enforced, and an enforcement claim that
 * nothing executes is exactly the "claim nothing backs" the working rules reject. The probe makes the claim true
 * today, and the first real `key = USER` endpoint inherits a proven filter instead of an untested one.
 *
 * All calls come from the same client address on purpose — that is what makes the per-principal axis visible: a
 * per-IP filter would have throttled the second user too.
 */
@Path("/test/user-rate-limited-probe")
@Authenticated
@RateLimit(key = RateLimitKey.USER)
class UserRateLimitedProbeResource {
    @GET
    fun probe(): Response = Response.ok("ok").build()
}

@QuarkusTest
@TestProfile(UserRateLimitTest.LowUserLimitProfile::class)
class UserRateLimitTest {

    class LowUserLimitProfile : QuarkusTestProfile {
        override fun getConfigOverrides() = mapOf(
            "veyq.ratelimit.user.max-requests" to "3",
            "veyq.ratelimit.user.window-seconds" to "60",
        )
    }

    @Test
    fun aPrincipalIsThrottledPastItsAllowance() {
        val bearer = bearer(seedUser())
        repeat(3) { probe(bearer).then().statusCode(200) }
        probe(bearer).then().statusCode(429)
            .body("code", equalTo("rate_limited"))
            .header("Retry-After", org.hamcrest.CoreMatchers.notNullValue())
    }

    @Test
    fun onePrincipalTrippingTheLimitDoesNotThrottleAnother() {
        val tripped = bearer(seedUser())
        val bystander = bearer(seedUser())
        repeat(3) { probe(tripped).then().statusCode(200) }
        probe(tripped).then().statusCode(429) // this principal is now throttled...
        probe(bystander).then().statusCode(200) // ...but a different one is unaffected, from the same address.
    }

    @Test
    fun anAnonymousCallerIsRejectedByAuthNotSilentlyCountedAsOneSharedKey() {
        // `key = USER` requires `@Authenticated` (the posture sweep asserts it statically). Without a principal
        // there is nothing to key on, and the filter must not invent one — the security layer answers first.
        given().`when`().get(PATH).then().statusCode(401)
    }

    private fun probe(bearer: String) = given().header("Authorization", bearer).`when`().get(PATH)

    private companion object {
        const val PATH = "/test/user-rate-limited-probe"
    }
}
