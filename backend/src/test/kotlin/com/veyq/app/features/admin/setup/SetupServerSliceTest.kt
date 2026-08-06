package com.veyq.app.features.admin.setup

import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.notNullValue
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
         "recoveryMasterKeyTag":"cmVjb3ZlcnktdGFn"}
    """.trimIndent()

    @Test
    @Order(1)
    fun setupRequiredInitially() {
        given().`when`().get("/api/setup")
            .then().statusCode(200)
            .body("setupRequired", equalTo(true))
            .body("suites[0]", equalTo("v1"))
    }


    @Test
    @Order(3)
    fun rejectsNonBase64Key() {
        given().contentType(ContentType.JSON).body(ownerBody(authPublicKey = "not base64!!"))
            .`when`().post("/api/setup/owner")
            .then().statusCode(400)
    }

    @Test
    @Order(4)
    fun createsOwner() {
        given().contentType(ContentType.JSON).body(ownerBody())
            .`when`().post("/api/setup/owner")
            .then().statusCode(201)
            .body("userId", notNullValue())
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
        given().contentType(ContentType.JSON).body(ownerBody())
            .`when`().post("/api/setup/owner")
            .then().statusCode(409)
            .body("code", equalTo("setup_already_complete"))
    }

    @Test
    @Order(7)
    fun rejectsInvalidEmail() {
        given().contentType(ContentType.JSON).body(ownerBody(email = "not-an-email"))
            .`when`().post("/api/setup/owner")
            .then().statusCode(400)
    }

    // Boundary validation runs before the single-owner check (proven by Order 7 returning 400 after setup
    // completed), so these negative cases stay 400 regardless of test order.

    @Test
    @Order(8)
    fun rejectsNonBase64KdfParams() {
        given().contentType(ContentType.JSON).body(ownerBody(kdfParams = "not base64!!"))
            .`when`().post("/api/setup/owner")
            .then().statusCode(400)
    }

    @Test
    @Order(9)
    fun rejectsOversizedKdfParams() {
        // > 256 chars of valid base64 alphabet → trips @Size(max = 256) (kdfParams added in phase 3a)
        given().contentType(ContentType.JSON).body(ownerBody(kdfParams = "A".repeat(260)))
            .`when`().post("/api/setup/owner")
            .then().statusCode(400)
    }


    @Test
    @Order(11)
    fun rejectsUnknownSuiteId() {
        given().contentType(ContentType.JSON).body(ownerBody().replace("\"suiteId\":\"v1\"", "\"suiteId\":\"v2\""))
            .`when`().post("/api/setup/owner")
            .then().statusCode(400)
    }

    @Test
    @Order(12)
    fun rejectsEmptyMasterKeyWraps() {
        // minItems: 1 (the PASSWORD floor) — an owner with no wraps must not be accepted.
        given().contentType(ContentType.JSON)
            .body(ownerBody().replace(Regex("\"masterKeyWraps\":\\[.*?\\]"), "\"masterKeyWraps\":[]"))
            .`when`().post("/api/setup/owner")
            .then().statusCode(400)
    }

    @Test
    @Order(13)
    fun rejectsNonBase64WrappedKeyInsideWrap() {
        // cascaded (@Valid) validation reaches the wrap's own fields, not just the top-level keys.
        given().contentType(ContentType.JSON)
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
        given().contentType(ContentType.JSON).body(body)
            .`when`().post("/api/setup/owner")
            .then().statusCode(400)
    }
}
