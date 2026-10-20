package me.raddatz.veyq.core.http

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured
import me.raddatz.veyq.core.adminchannel.givenAdminChannel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * B2 (ADR 0094 decision 7): with `source=proxy-protocol` the PROXY header's source is the peer and the port source.
 * Its port is believed for the stop entry of `X-Forwarded-For` only when the IPs are equal, and with no
 * `X-Forwarded-For` the PROXY source is the client. A connection without the PROXY header is closed unanswered —
 * which is why the chart's probes move to the admin listener, still answering here in the same instance.
 */
@QuarkusTest
@TestProfile(ProxyProtocolProfile::class)
class ClientAddressProxyProtocolSliceTest {

    private fun probe(proxyLine: String?, vararg headers: Pair<String, String>) =
        RawHttp.exchange(RestAssured.port, proxyLine, "/test/client-address", headers.toList())

    @Test
    fun thePortOfAProxySourceWithTheStopEntrysIpIsTheClients() {
        val response = probe(PROXY, "X-Forwarded-For" to "203.0.113.77")
        assertEquals(200, RawHttp.status(response), response)
        assertEquals("203.0.113.77 51234", RawHttp.body(response))
    }

    @Test
    fun withNoForwardedForTheProxySourceIsTheClient() =
        assertEquals("203.0.113.77 51234", RawHttp.body(probe(PROXY)))

    @Test
    fun aStopEntryWithAnotherIpKeepsItsIpAndGetsNoPort() =
        assertEquals("198.51.100.66 -", RawHttp.body(probe(PROXY, "X-Forwarded-For" to "198.51.100.66")))

    @Test
    fun aConnectionWithoutAProxyHeaderIsClosedWithNoBytes() {
        // "No bytes, then EOF" — not "it threw": an exception would also be a refused connection or a timeout.
        assertEquals("", probe(null))
    }

    @Test
    fun theProbesStillAnswerOnTheAdminListener() {
        // The positive control for the close above: the same instance answers `/api/setup` where no PROXY is spoken,
        // which is where the chart's probes go under `proxyProtocol`.
        givenAdminChannel().`when`().get("/api/setup").then().statusCode(200)
    }

    private companion object {
        const val PROXY = "PROXY TCP4 203.0.113.77 10.0.0.1 51234 8080"
    }
}
