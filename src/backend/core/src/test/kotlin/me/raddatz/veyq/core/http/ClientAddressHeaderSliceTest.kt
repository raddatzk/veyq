package me.raddatz.veyq.core.http

import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import org.hamcrest.CoreMatchers.equalTo
import org.junit.jupiter.api.Test

/**
 * B3 (ADR 0094 decision 7): with the `header` source the header the edge overwrites names the client's port — for the
 * stop entry of `X-Forwarded-For`, and only when its IP is that entry's. The probe's `?source=header` hands the
 * accessor that source (its KDoc says why it is no profile). The configured default's half, where the same headers
 * name no port at all, is [ClientAddressDefaultSourceSliceTest].
 */
@QuarkusTest
class ClientAddressHeaderSliceTest {

    @Test
    fun theHeaderWithTheStopEntrysIpNamesItsPort() {
        given().header("X-Forwarded-For", "203.0.113.77").header(ClientAddress.PORT_HEADER, "203.0.113.77:51234")
            .`when`().get("/test/client-address?source=header").then().statusCode(200).body(equalTo("203.0.113.77 51234"))
    }

    @Test
    fun theHeaderWithAnotherIpNamesNoPort() {
        given().header("X-Forwarded-For", "203.0.113.77").header(ClientAddress.PORT_HEADER, "198.51.100.66:51234")
            .`when`().get("/test/client-address?source=header").then().statusCode(200).body(equalTo("203.0.113.77 -"))
    }
}
