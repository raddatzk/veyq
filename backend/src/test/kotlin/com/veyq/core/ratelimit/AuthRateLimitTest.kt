package com.veyq.core.ratelimit

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.junit.QuarkusTestProfile
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.Response
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.not
import org.junit.jupiter.api.Test

/**
 * Verifies [IpRateLimitFilter] throttles **every** governed public-auth endpoint (login challenge/verify +
 * token refresh — the three that carry `@RateLimit(key = IP)`), keys the window **per client IP**, and rejects with a
 * `429` + `rate_limited` past the limit. Mechanism + matrix: `docs/crosscutting/cc.security.rate-limit.md`.
 *
 * Runs under a profile that drops the limit to 3/window and turns on proxy-address-forwarding so each test can
 * present its **own** synthetic client IP via `X-Forwarded-For`. That gives every method an isolated bucket —
 * the tests are order-independent (they don't share the 127.0.0.1 counter) and the per-IP property is testable
 * directly. The window here is long (60s), so at 3 per 60s a token takes 20s to accrue and no refill lands
 * mid-test; the **refill** is its own short-window sibling ([AuthRateLimitRefillTest]). None of these endpoints
 * need a seeded user: the
 * limiter is an AUTHENTICATION-priority request filter that runs *before* the handler, so a 429 fires on the
 * (N+1)th call regardless of whether the request body would otherwise 200/400/401.
 */
@QuarkusTest
@TestProfile(AuthRateLimitTest.LowLimitProfile::class)
class AuthRateLimitTest {

    class LowLimitProfile : QuarkusTestProfile {
        override fun getConfigOverrides() = mapOf(
            "veyq.ratelimit.auth.max-requests" to "3",
            "veyq.ratelimit.auth.window-seconds" to "60",
            // Honour X-Forwarded-For so each test keys its own client IP (see class doc).
            "quarkus.http.proxy.proxy-address-forwarding" to "true",
            "quarkus.http.proxy.allow-x-forwarded" to "true",
        )
    }

    @Test
    fun challengeIsThrottledOverTheLimit() {
        val ip = "192.0.2.1"
        repeat(3) { challenge(ip).then().statusCode(200) } // inside the window
        challenge(ip).then().statusCode(429).body("code", equalTo("rate_limited"))
    }

    @Test
    fun verifyIsThrottledOverTheLimit() {
        val ip = "192.0.2.2"
        repeat(3) { verify(ip).then().statusCode(not(equalTo(429))) } // reach the handler (a generic 401)
        verify(ip).then().statusCode(429).body("code", equalTo("rate_limited"))
    }

    @Test
    fun refreshIsThrottledOverTheLimit() {
        val ip = "192.0.2.3"
        repeat(3) { refresh(ip).then().statusCode(not(equalTo(429))) } // reach the handler (a refresh_rejected 401)
        refresh(ip).then().statusCode(429).body("code", equalTo("rate_limited"))
    }

    @Test
    fun oneIpTrippingTheLimitDoesNotThrottleAnother() {
        val tripped = "198.51.100.10"
        val bystander = "198.51.100.20"
        repeat(3) { challenge(tripped).then().statusCode(200) }
        challenge(tripped).then().statusCode(429) // this IP is now throttled...
        challenge(bystander).then().statusCode(200) // ...but a different IP is unaffected.
    }

    private fun challenge(ip: String): Response =
        given().header("X-Forwarded-For", ip).contentType(ContentType.JSON)
            .body("""{"email":"ratelimit@example.com"}""")
            .`when`().post("/api/login/challenge")

    private fun verify(ip: String): Response =
        given().header("X-Forwarded-For", ip).contentType(ContentType.JSON)
            .body("""{"email":"ratelimit@example.com","nonce":"AA==","signature":"AA=="}""")
            .`when`().post("/api/login/verify")

    private fun refresh(ip: String): Response =
        given().header("X-Forwarded-For", ip).contentType(ContentType.JSON)
            .body("""{"refreshToken":"not-a-real-token"}""")
            .`when`().post("/api/token/refresh")
}
