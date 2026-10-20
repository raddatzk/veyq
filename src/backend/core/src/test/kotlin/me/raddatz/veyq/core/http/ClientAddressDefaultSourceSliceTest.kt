package me.raddatz.veyq.core.http

import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured
import io.restassured.RestAssured.given
import org.hamcrest.CoreMatchers.equalTo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/**
 * The default source, `none` (ADR 0094 decision 7), in the default profile:
 *  - **B3's forged-header bite:** the very headers that name port 51234 under `source=header`
 *    ([ClientAddressHeaderSliceTest]) name no port here. Traefik passes an unknown header through, so a client-sent
 *    one would otherwise name another subscriber on the same CGNAT address; the IP check cannot stop that.
 *  - **B2's last case:** with the PROXY protocol off, a PROXY line is no request line. It is answered 400 (measured
 *    first, R6 of the CR: `HTTP/1.0 400 Bad Request`, Vert.x's answer to a request line it cannot parse) and
 *    reaches no resource, so it names no address.
 */
@QuarkusTest
class ClientAddressDefaultSourceSliceTest {

    @Test
    fun aClientSentPortHeaderIsNeverHonoured() {
        given().header("X-Forwarded-For", "203.0.113.77").header(ClientAddress.PORT_HEADER, "203.0.113.77:51234")
            .`when`().get("/test/client-address").then().statusCode(200).body(equalTo("203.0.113.77 -"))
    }

    @Test
    fun aProxyLineIsAMalformedRequestWhenTheProtocolIsOff() {
        val response = RawHttp.exchange(RestAssured.port, "PROXY TCP4 198.51.100.66 10.0.0.1 4711 8080", "/test/client-address")
        assertEquals(400, RawHttp.status(response), response)
        assertFalse("198.51.100.66" in response, response)
    }
}
