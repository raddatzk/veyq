package me.raddatz.veyq.core.adminchannel

import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.ensureUser
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import io.restassured.specification.RequestSpecification
import jakarta.inject.Inject
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * uc.admin.admin-channel A2 / `the-socket-decides-not-the-headers`: every forwarding header a client can write,
 * naming the admin listener and a private client address, leaves the public port's refusal unchanged.
 *
 * **The profile is the point.** No shipped target turns Quarkus's forwarding on (ADR 0094 decision 4); this profile
 * turns on every forwarding source Quarkus has — `Forwarded` and `X-Forwarded-*`, host included — as the worst case
 * a setting turned back on would make, so the forwarding layer really does rewrite `host()` and `remoteAddress()`
 * from these headers. Without it nothing is rewritten and a
 * predicate that read those values would pass here for the wrong reason (measured: that is the bite that reddens
 * this class — reading `remoteAddress()` and `host()` instead of the socket).
 */
@QuarkusTest
@TestProfile(AdminChannelForgedHeadersSliceTest.Forwarding::class)
class AdminChannelForgedHeadersSliceTest {

    class Forwarding : QuarkusTestProfile {
        override fun getConfigOverrides() = mapOf(
            "quarkus.http.proxy.proxy-address-forwarding" to "true",
            "quarkus.http.proxy.allow-forwarded" to "true",
            "quarkus.http.proxy.allow-x-forwarded" to "true",
            "quarkus.http.proxy.enable-forwarded-host" to "true",
        )
    }

    @Inject
    lateinit var listener: AdminListener

    @BeforeEach
    fun seed() {
        ensureUser(OWNER_ID, "admin-forged-owner@example.com", "SERVER_OWNER")
    }

    private fun forged(spec: RequestSpecification): RequestSpecification {
        val admin = "localhost:${listener.port()}"
        return spec.header("Authorization", bearer(OWNER_ID))
            .header("Forwarded", "for=10.0.0.1;host=$admin;proto=http")
            .header("X-Forwarded-Host", admin)
            .header("X-Forwarded-Port", listener.port().toString())
            .header("X-Forwarded-For", "10.0.0.1")
    }

    @Test
    fun forgedForwardingHeadersDoNotOpenThePublicPort() {
        forged(given()).`when`().get("/api/instance/release").then().statusCode(404)
    }

    @Test
    fun theSameHeadersOnTheAdminListenerChangeNothingThere() {
        // Control on the same predicate: the forged request is still a well-formed owner request.
        forged(givenAdminChannel()).`when`().get("/api/instance/release").then().statusCode(200)
    }

    private companion object {
        const val OWNER_ID = "0ed82d21-75b2-48b4-b20e-cb2337d32964"
    }
}
