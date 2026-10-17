package me.raddatz.veyq.core.ratelimit

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured
import me.raddatz.veyq.core.http.ProxyProtocolProfile
import me.raddatz.veyq.core.http.RawHttp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * B5 (ADR 0094 decisions 3 and 7): under the PROXY protocol the port is known, and the limiter still keys on the IP
 * alone — two connections from one address with two source ports share one bucket. A key that held the port would
 * open a bucket per connection.
 */
@QuarkusTest
@TestProfile(ProxyProtocolProfile::class)
class ProxyProtocolRateLimitTest {

    private fun challenge(source: String, port: Int): Int? = RawHttp.status(
        RawHttp.exchange(
            RestAssured.port,
            "PROXY TCP4 $source 10.0.0.1 $port 8080",
            "/api/login/challenge",
            jsonBody = """{"email":"ratelimit-proxy@example.com"}""",
        ),
    )

    @Test
    fun twoSourcePortsOfOneAddressShareOneBucket() {
        repeat(3) { assertEquals(200, challenge("203.0.113.77", 1111)) }
        assertEquals(429, challenge("203.0.113.77", 2222))
        // The control: another address is its own bucket, so the 429 above is the bucket, not the route.
        assertEquals(200, challenge("203.0.113.78", 1111))
    }
}
