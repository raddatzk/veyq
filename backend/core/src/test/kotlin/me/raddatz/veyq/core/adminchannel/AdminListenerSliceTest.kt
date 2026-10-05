package me.raddatz.veyq.core.adminchannel

import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.ensureUser
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.equalTo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The admin listener runs the whole pipeline (ADR 0065): JAX-RS answers there, and so does security — an owner
 * session is honoured and an anonymous call is refused exactly as on the public port. What the listener does NOT
 * do is decided elsewhere ([AdminChannel], the channel filter); this class only proves the door is a real door.
 */
@QuarkusTest
class AdminListenerSliceTest {

    @Inject
    lateinit var listener: AdminListener

    @BeforeEach
    fun seed() {
        ensureUser(OWNER_ID, "admin-listener-owner@example.com", "SERVER_OWNER")
    }

    @Test
    fun theListenerIsItsOwnPort() {
        // Without this, every test below could be talking to the public port and pass for the wrong reason.
        assertNotEquals(RestAssured.port, listener.port())
    }

    @Test
    fun theListenerServesAnUnmarkedOperation() {
        val public = given().`when`().get("/api/setup").then().statusCode(200).extract().asString()
        val admin = givenAdminChannel().`when`().get("/api/setup").then().statusCode(200).extract().asString()
        assertEquals(public, admin)
    }

    @Test
    fun theListenerServesARoleGatedOperationWithAnOwnerSession() {
        givenAdminChannel().header("Authorization", bearer(OWNER_ID))
            .`when`().get("/api/instance/release")
            .then().statusCode(200)
    }

    @Test
    fun theListenerRunsSecurityToo() {
        givenAdminChannel().`when`().get("/api/instance/release")
            .then().statusCode(401)
            .body("code", equalTo("authentication_failed"))
    }

    private companion object {
        const val OWNER_ID = "admin-listener-owner"
    }
}
