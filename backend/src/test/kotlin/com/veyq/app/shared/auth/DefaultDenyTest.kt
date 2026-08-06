package com.veyq.app.shared.auth

import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.core.Response
import org.junit.jupiter.api.Test

/**
 * Proves default-deny is actually active (cr-0012 phase 3): a JAX-RS endpoint with **no** security annotation
 * is denied, not served. The probe lives in test scope only, so it never ships — its sole purpose is to fail
 * the day someone disables `quarkus.security.jaxrs.deny-unannotated-endpoints` or it silently stops working.
 */
@Path("/test/unannotated-probe")
class UnannotatedProbeResource {
    @GET
    fun probe(): Response = Response.ok("should-be-unreachable").build()
}

@QuarkusTest
class DefaultDenyTest {

    @Test
    fun unannotatedEndpointIsDeniedNotServed() {
        given().`when`().get("/test/unannotated-probe")
            .then().statusCode(401)
    }
}
