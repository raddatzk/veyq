package me.raddatz.veyq.core.ratelimit

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.junit.QuarkusTestProfile
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.Response
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Verifies [IpRateLimitFilter]'s bucket **actually refills**: a drained IP is throttled, and once enough time has
 * passed for a token to accrue its requests are allowed again. Split out from [AuthRateLimitTest] because it needs
 * a **1-second** window (3 per second → a token every ~333 ms, observable in real time without a long wait) — a
 * different build-time/runtime config, hence its own profile. It keys its own client IP, isolated from everything
 * else, by a single-entry `X-Forwarded-For` from loopback: with an empty trust list that entry is the client
 * (ADR 0094).
 *
 * This is the *runtime* half of ADR 0052's gradual-refill consequence: there is no window edge to wait for any
 * more, so what is asserted is not "the counter cleared" but "one token came back". The arithmetic behind it is
 * pinned in [TokenBucketTest]; this proves the wiring — the real filter, on a real endpoint, over a real clock.
 */
@QuarkusTest
@TestProfile(AuthRateLimitRefillTest.ShortWindowProfile::class)
class AuthRateLimitRefillTest {

    class ShortWindowProfile : QuarkusTestProfile {
        override fun getConfigOverrides() = mapOf(
            "veyq.ratelimit.auth.max-requests" to "3",
            "veyq.ratelimit.auth.window-seconds" to "1",
        )
    }

    @Test
    fun theBucketRefillsSoADrainedCallerIsAllowedAgain() {
        val ip = "203.0.113.7"
        // Drain the bucket: capacity is 3 and a token takes ~333 ms to accrue, so a short burst must run it dry.
        // A few extra requests absorb a slow round-trip that would refill a token mid-burst — the statuses are
        // collected and asserted on, so a burst that never tripped fails loudly instead of passing quietly.
        val statuses = (1..8).map { challenge(ip).statusCode() }
        assertTrue(429 in statuses, "expected the burst to drain the bucket and be throttled, saw $statuses")

        // A token accrues every ~333 ms, so the caller is let through again — one request at a time, with no
        // window edge to wait for.
        assertTrue(pollUntilAllowed(ip, Duration.ofSeconds(3)), "expected a refilled token to allow a request again")
    }

    private fun pollUntilAllowed(ip: String, timeout: Duration): Boolean {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (System.nanoTime() < deadline) {
            if (challenge(ip).statusCode() == 200) return true
            Thread.sleep(100)
        }
        return false
    }

    private fun challenge(ip: String): Response =
        given().header("X-Forwarded-For", ip).contentType(ContentType.JSON)
            .body("""{"email":"ratelimit@example.com"}""")
            .`when`().post("/api/login/challenge")
}
