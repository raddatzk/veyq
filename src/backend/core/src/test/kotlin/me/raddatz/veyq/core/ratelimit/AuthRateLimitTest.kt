package me.raddatz.veyq.core.ratelimit

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
 * Runs under a profile that drops the limit to 3/window. Each test presents its **own** synthetic client IP via
 * `X-Forwarded-For`, sent from loopback with an empty trust list, so the rightmost entry is the client (ADR 0094)
 * and no Quarkus forwarding setting is involved. That gives every method an isolated bucket — the tests are
 * order-independent (they don't share the 127.0.0.1 counter) and the per-IP property is testable directly; so every
 * method uses addresses no other method uses. The window here is long (60s), so at 3 per 60s a token takes 20s to accrue and no refill lands
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

    /**
     * B1 + B2 (ADR 0094): a client that writes its own leftmost `X-Forwarded-For` entry opens no fresh bucket. The
     * three requests and the fourth differ only in that forged entry; the rightmost, written by the proxy, is the
     * key. B2, the control on the same predicate: a different rightmost entry is a different bucket, so the key is
     * not one global bucket either.
     */
    @Test
    fun aForgedLeftmostEntryOpensNoBucket() {
        repeat(3) { challenge("198.51.100.66, 203.0.113.77").then().statusCode(200) }
        challenge("198.51.100.99, 203.0.113.77").then().statusCode(429).body("code", equalTo("rate_limited"))
        challenge("198.51.100.66, 203.0.113.78").then().statusCode(200)
    }

    /**
     * The key is the IP alone (ADR 0094 decision 3): the stop entry's port, a new one per connection behind a load
     * balancer that writes it, opens no bucket. The control on the same predicate: another IP with a port it shares
     * is its own bucket.
     */
    @Test
    fun aPortAtTheStopDoesNotChangeTheKey() {
        listOf("1111", "2222", "3333").forEach { challenge("203.0.113.79:$it").then().statusCode(200) }
        challenge("203.0.113.79:4444").then().statusCode(429).body("code", equalTo("rate_limited"))
        challenge("203.0.113.80:1111").then().statusCode(200)
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
