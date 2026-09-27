package com.veyq.app.features.admin.setup

import com.veyq.app.shared.adminchannel.givenAdminChannel
import com.veyq.app.shared.identity.VeyqUser
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.notNullValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class SetupServerSliceTest {

    private fun ownerBody(
        email: String = "kevin@example.com",
        authPublicKey: String = "ZWQyNTUxOS1hdXRoLXB1Yg==",
        kdfParams: String = "AAAAAwAAgAAAAAABAAAAIHNhbHQtZXhhbXBsZS0xNmJ5dGVz",
    ) = """
        {"email":"$email","suiteId":"v1",
         "identityPublicKey":"aWRlbnRpdHktcHVibGljLWtleQ==",
         "wrappedIdentityPrivateKey":"d3JhcHBlZC1pZGVudGl0eS1wcml2YXRlLWtleQ==",
         "authPublicKey":"$authPublicKey",
         "masterKeyWraps":[{"method":"PASSWORD","wrappedKey":"d3JhcHBlZC1tYXN0ZXIta2V5","kdfParams":"$kdfParams"}],
         "secretKeyProofPublicKey":"c2VjcmV0LWtleS1wcm9vZi1wdWJsaWMta2V5"}
    """.trimIndent()

    @Test
    @Order(1)
    fun setupRequiredInitially() {
        given().`when`().get("/api/setup")
            .then().statusCode(200)
            .body("setupRequired", equalTo(true))
            .body("suites[0]", equalTo("v1"))
    }


    /**
     * uc.admin.admin-channel `setup-answers-only-on-the-admin-channel`: on a fresh instance the public port answers
     * setup as an unknown route and creates nothing — the first caller after `up -d` who is not the operator finds
     * no door. Its positive control on the same predicate is [createsOwner], the identical body on the admin
     * listener answering 201; that it can is itself the proof nothing was created here.
     */
    @Test
    @Order(2)
    fun setupIsAnUnknownRouteOnThePublicPort() {
        given().contentType(ContentType.JSON).body(ownerBody())
            .`when`().post("/api/setup/owner")
            .then().statusCode(404)
        given().`when`().get("/api/setup")
            .then().statusCode(200)
            .body("setupRequired", equalTo(true))
    }

    @Test
    @Order(3)
    fun rejectsNonBase64Key() {
        givenAdminChannel().contentType(ContentType.JSON).body(ownerBody(authPublicKey = "not base64!!"))
            .`when`().post("/api/setup/owner")
            .then().statusCode(400)
    }

    @Test
    @Order(4)
    fun createsOwner() {
        val userId: String = givenAdminChannel().contentType(ContentType.JSON).body(ownerBody())
            .`when`().post("/api/setup/owner")
            .then().statusCode(201)
            .body("userId", notNullValue())
            .extract().path("userId")
        // Rotation, change-password and recovery all verify against this key (ADR 0060 Decision 2); an owner
        // born without it could do none of the three.
        assertEquals(
            "c2VjcmV0LWtleS1wcm9vZi1wdWJsaWMta2V5",
            QuarkusTransaction.requiringNew().call<String?> { VeyqUser.findById(userId)?.secretKeyProofPublicKey },
        )
    }

    @Test
    @Order(5)
    fun setupNoLongerRequired() {
        given().`when`().get("/api/setup")
            .then().statusCode(200)
            .body("setupRequired", equalTo(false))
    }

    @Test
    @Order(6)
    fun rejectsSecondOwner() {
        givenAdminChannel().contentType(ContentType.JSON).body(ownerBody())
            .`when`().post("/api/setup/owner")
            .then().statusCode(409)
            .body("code", equalTo("setup_already_complete"))
    }

    @Test
    @Order(7)
    fun rejectsInvalidEmail() {
        givenAdminChannel().contentType(ContentType.JSON).body(ownerBody(email = "not-an-email"))
            .`when`().post("/api/setup/owner")
            .then().statusCode(400)
    }

    // Boundary validation runs before the single-owner check (proven by Order 7 returning 400 after setup
    // completed), so these negative cases stay 400 regardless of test order.

    @Test
    @Order(8)
    fun rejectsNonBase64KdfParams() {
        givenAdminChannel().contentType(ContentType.JSON).body(ownerBody(kdfParams = "not base64!!"))
            .`when`().post("/api/setup/owner")
            .then().statusCode(400)
    }

    @Test
    @Order(9)
    fun rejectsOversizedKdfParams() {
        // > 256 chars of valid base64 alphabet → trips @Size(max = 256) (kdfParams added in phase 3a)
        givenAdminChannel().contentType(ContentType.JSON).body(ownerBody(kdfParams = "A".repeat(260)))
            .`when`().post("/api/setup/owner")
            .then().statusCode(400)
    }


    @Test
    @Order(11)
    fun rejectsUnknownSuiteId() {
        givenAdminChannel().contentType(ContentType.JSON).body(ownerBody().replace("\"suiteId\":\"v1\"", "\"suiteId\":\"v2\""))
            .`when`().post("/api/setup/owner")
            .then().statusCode(400)
    }

    @Test
    @Order(12)
    fun rejectsEmptyMasterKeyWraps() {
        // minItems: 1 (the PASSWORD floor) — an owner with no wraps must not be accepted.
        givenAdminChannel().contentType(ContentType.JSON)
            .body(ownerBody().replace(Regex("\"masterKeyWraps\":\\[.*?\\]"), "\"masterKeyWraps\":[]"))
            .`when`().post("/api/setup/owner")
            .then().statusCode(400)
    }

    @Test
    @Order(13)
    fun rejectsNonBase64WrappedKeyInsideWrap() {
        // cascaded (@Valid) validation reaches the wrap's own fields, not just the top-level keys.
        givenAdminChannel().contentType(ContentType.JSON)
            .body(ownerBody().replace("d3JhcHBlZC1tYXN0ZXIta2V5", "not base64!!"))
            .`when`().post("/api/setup/owner")
            .then().statusCode(400)
    }

    @Test
    @Order(14)
    fun rejectsMissingAuthPublicKey() {
        val body = """
            {"email":"kevin@example.com","suiteId":"v1",
             "identityPublicKey":"aWRlbnRpdHktcHVibGljLWtleQ==",
             "wrappedIdentityPrivateKey":"d3JhcHBlZC1pZGVudGl0eS1wcml2YXRlLWtleQ==",
             "masterKeyWraps":[{"method":"PASSWORD","wrappedKey":"d3JhcHBlZC1tYXN0ZXIta2V5"}]}
        """.trimIndent()
        givenAdminChannel().contentType(ContentType.JSON).body(body)
            .`when`().post("/api/setup/owner")
            .then().statusCode(400)
    }

    @Test
    @Order(15)
    fun rejectsMissingSecretKeyProofPublicKey() {
        // Required, not optional: an account born without its proof key could never rotate, change its password
        // or recover (ADR 0054 § Consequences), so it is refused at birth rather than discovered at the worst time.
        val body = ownerBody().replace(Regex(""",\s*"secretKeyProofPublicKey":"[^"]*""""), "")
        check("secretKeyProofPublicKey" !in body) { "the fixture must actually drop the field" }
        givenAdminChannel().contentType(ContentType.JSON).body(body)
            .`when`().post("/api/setup/owner")
            .then().statusCode(400)
    }
}
