package me.raddatz.veyq.core.adminchannel

import me.raddatz.veyq.core.http.ProxyProtocolProfile
import me.raddatz.veyq.core.http.RawHttp
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.ensureUser
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * uc.admin.admin-channel `a-proxy-header-cannot-name-the-admin-listener` (ADR 0094 decision 7, D4 of its CR): with
 * the PROXY protocol on, a connection to the PUBLIC port whose PROXY header names the admin listener's port as its
 * destination and a private client as its source is still the public port. Vert.x hands the header's addresses out
 * as `localAddress()` / `remoteAddress()`; the predicate must read the socket's real ones. Live when written: on the
 * tree before the fix this call answered 200 — the Server-Owner operations, opened on the public port by whoever
 * reaches the gateway.
 */
@QuarkusTest
@TestProfile(ProxyProtocolProfile::class)
class AdminChannelProxyProtocolSliceTest {

    @Inject
    lateinit var listener: AdminListener

    @BeforeEach
    fun seed() {
        ensureUser(OWNER_ID, "admin-proxy-owner@example.com", "SERVER_OWNER")
    }

    @Test
    fun aProxyHeaderNamingTheAdminListenerDoesNotOpenThePublicPort() {
        val response = RawHttp.exchange(
            RestAssured.port,
            "PROXY TCP4 10.0.0.9 10.0.0.1 51234 ${listener.port()}",
            "/api/instance/release",
            listOf("Authorization" to bearer(OWNER_ID)),
        )
        assertEquals(404, RawHttp.status(response), response)
    }

    @Test
    fun theSameCallOnTheAdminListenerAnswers() {
        // The positive control on the same predicate: the call is a well-formed owner request, which the admin
        // listener — no PROXY there — answers.
        givenAdminChannel().header("Authorization", bearer(OWNER_ID))
            .`when`().get("/api/instance/release").then().statusCode(200)
    }

    private companion object {
        const val OWNER_ID = "6d1f0c52-2f0e-4c1a-9a43-0f7b7c2d5e91"
    }
}
