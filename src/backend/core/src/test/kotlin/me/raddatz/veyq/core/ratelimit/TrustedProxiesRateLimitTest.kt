package me.raddatz.veyq.core.ratelimit

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.Response
import org.hamcrest.CoreMatchers.equalTo
import org.junit.jupiter.api.Test

/**
 * B3 (ADR 0094 decision 3): behind a load balancer that `veyq.http.trusted-proxies` lists, the limiter keys on the
 * entry left of it — the one the load balancer wrote — not on the load balancer and not on the client's own leftmost
 * entry. The profile is also the wiring proof that the mapping parses a listed entry: a value it could not convert
 * would fail this class's boot.
 */
@QuarkusTest
@TestProfile(TrustedProxiesRateLimitTest.TrustedLoadBalancer::class)
class TrustedProxiesRateLimitTest {

    class TrustedLoadBalancer : QuarkusTestProfile {
        override fun getConfigOverrides() = mapOf(
            "veyq.ratelimit.auth.max-requests" to "3",
            "veyq.ratelimit.auth.window-seconds" to "60",
            "veyq.http.trusted-proxies" to "10.0.0.5",
        )
    }

    @Test
    fun theListedLoadBalancerIsSkippedAndTheForgedLeftmostEntryIgnored() {
        repeat(3) { challenge("198.51.100.66, 203.0.113.77, 10.0.0.5").then().statusCode(200) }
        challenge("198.51.100.99, 203.0.113.77, 10.0.0.5").then().statusCode(429).body("code", equalTo("rate_limited"))
        // The control: another client behind the same load balancer is its own bucket. Without the skip, every
        // request here keys 10.0.0.5 and this one is throttled too.
        challenge("198.51.100.66, 203.0.113.78, 10.0.0.5").then().statusCode(200)
    }

    private fun challenge(forwardedFor: String): Response =
        given().header("X-Forwarded-For", forwardedFor).contentType(ContentType.JSON)
            .body("""{"email":"ratelimit@example.com"}""")
            .`when`().post("/api/login/challenge")
}
