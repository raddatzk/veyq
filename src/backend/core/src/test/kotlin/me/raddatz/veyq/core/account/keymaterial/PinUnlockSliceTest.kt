package me.raddatz.veyq.core.account.keymaterial

import me.raddatz.veyq.core.account.login.SessionService
import me.raddatz.veyq.core.identity.MasterKeyWrapEntity
import me.raddatz.veyq.core.identity.VeyqUser
import me.raddatz.veyq.core.testing.TestFixtures.newUser
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.not
import org.hamcrest.CoreMatchers.hasItem
import org.hamcrest.CoreMatchers.containsString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Base64
import java.util.UUID

/**
 * A TV's PIN wrap on the server (uc.account.unlock-with-a-pin, ADR 0160 decision 3): the share and the verifier are kept
 * only for DEVICE_PIN and never served with the key material; a matching verifier releases the share and resets the
 * count; the tenth wrong one in a row deletes the wrap, and every later call answers 404; another account's or another
 * method's wrap answers 404 and counts nothing.
 */
@QuarkusTest
class PinUnlockSliceTest {

    @Inject
    lateinit var sessions: SessionService

    @BeforeEach
    fun seed() {
        seedUser(USER_A, "a-pin@example.com")
        seedUser(USER_B, "b-pin@example.com")
        QuarkusTransaction.requiringNew().run {
            MasterKeyWrapEntity.delete("(userId = ?1 or userId = ?2) and method != ?3", USER_A, USER_B, "PASSWORD")
        }
    }

    @Test
    fun enrolKeepsShareAndVerifierAndNeverServesThem() {
        val token = token(USER_A)
        val id = enrol(token)
        val material = given().header("Authorization", "Bearer $token").`when`().get("/api/account/key-material")
        material.then().statusCode(200).body("wraps.method", hasItem("DEVICE_PIN"))
            .body(not(containsString(SHARE))).body(not(containsString(VERIFIER)))
        val row = QuarkusTransaction.requiringNew().call { MasterKeyWrapEntity.findById(id) }!!
        assertEquals(SHARE, row.pinShare)
        assertEquals(VERIFIER, row.pinVerifier)
        assertEquals(0, row.pinFailures)
    }

    @Test
    fun devicePinWithoutShareOrVerifierIs400() {
        add(token(USER_A), """{"method":"DEVICE_PIN","wrappedKey":"d3JhcA==","kdfParams":"$KDF","pinShare":"$SHARE"}""")
            .then().statusCode(400).body("code", equalTo("invalid_request"))
        add(token(USER_A), """{"method":"DEVICE_PIN","wrappedKey":"d3JhcA==","kdfParams":"$KDF","pinVerifier":"$VERIFIER"}""")
            .then().statusCode(400).body("code", equalTo("invalid_request"))
    }

    @Test
    fun aShareOrVerifierOnAnotherMethodIs400() {
        add(token(USER_A), """{"method":"DEVICE_KEY","wrappedKey":"d3JhcA==","kdfParams":"$KDF","pinShare":"$SHARE","pinVerifier":"$VERIFIER"}""")
            .then().statusCode(400).body("code", equalTo("invalid_request"))
        add(token(USER_A), """{"method":"DEVICE","wrappedKey":"d3JhcA==","kdfParams":"$KDF","pinVerifier":"$VERIFIER"}""")
            .then().statusCode(400).body("code", equalTo("invalid_request"))
    }

    @Test
    fun aMatchingVerifierReleasesTheShareAndResetsTheCount() {
        val token = token(USER_A)
        val id = enrol(token)
        unlock(token, id, WRONG).then().statusCode(403).body("code", equalTo("pin_incorrect")).body("attemptsLeft", equalTo(9))
        unlock(token, id, WRONG).then().statusCode(403).body("attemptsLeft", equalTo(8))
        unlock(token, id, VERIFIER).then().statusCode(200).body("pinShare", equalTo(SHARE))
        assertEquals(0, failures(id))
        // The count starts over: nine more wrong ones leave the wrap in place.
        repeat(9) { n -> unlock(token, id, WRONG).then().statusCode(403).body("attemptsLeft", equalTo(9 - n)) }
        unlock(token, id, VERIFIER).then().statusCode(200).body("pinShare", equalTo(SHARE))
    }

    @Test
    fun theTenthFailureInARowDeletesTheWrapAndTheEleventhAnswers404() {
        val token = token(USER_A)
        val id = enrol(token)
        repeat(9) { n -> unlock(token, id, WRONG).then().statusCode(403).body("attemptsLeft", equalTo(9 - n)) }
        unlock(token, id, WRONG).then().statusCode(404).body("code", equalTo("not_found"))
        assertNull(QuarkusTransaction.requiringNew().call { MasterKeyWrapEntity.findById(id) })
        // Even the right PIN finds nothing now: the account on that TV needs a new pairing.
        unlock(token, id, VERIFIER).then().statusCode(404)
    }

    @Test
    fun anotherAccountsWrapAnswers404AndCountsNothing() {
        val id = enrol(token(USER_A))
        unlock(token(USER_B), id, VERIFIER).then().statusCode(404).body("code", equalTo("not_found"))
        unlock(token(USER_B), id, WRONG).then().statusCode(404)
        assertEquals(0, failures(id))
    }

    @Test
    fun aWrapOfAnotherMethodAnswers404() {
        val token = token(USER_A)
        val id = add(token, """{"method":"DEVICE_KEY","wrappedKey":"d3JhcA==","kdfParams":"$KDF"}""")
            .then().statusCode(201).extract().path<String>("id")
        unlock(token, id, VERIFIER).then().statusCode(404)
        unlock(token, UUID.randomUUID().toString(), VERIFIER).then().statusCode(404)
    }

    @Test
    fun aVerifierOfTheWrongLengthIs400AndCountsNothing() {
        val token = token(USER_A)
        val id = enrol(token)
        unlock(token, id, "c2hvcnQ=").then().statusCode(400)
        assertEquals(0, failures(id))
    }

    @Test
    fun untrustDeletesTheWrapWithItsShare() {
        val token = token(USER_A)
        val id = enrol(token)
        given().header("Authorization", "Bearer $token").`when`().delete("/api/account/wraps/$id").then().statusCode(204)
        unlock(token, id, VERIFIER).then().statusCode(404)
    }

    @Test
    fun unauthenticatedUnlockIs401() {
        given().contentType(ContentType.JSON).body("""{"verifier":"$VERIFIER"}""")
            .`when`().post("/api/account/wraps/${UUID.randomUUID()}/pin-unlock")
            .then().statusCode(401).body("code", equalTo("authentication_failed"))
    }

    // --- helpers ---

    private fun token(userId: String): String = sessions.issue(userId).accessToken

    private fun add(token: String, body: String) =
        given().header("Authorization", "Bearer $token").contentType(ContentType.JSON).body(body)
            .`when`().post("/api/account/wraps")

    private fun enrol(token: String): String =
        add(token, """{"method":"DEVICE_PIN","wrappedKey":"cGluLXdyYXA=","kdfParams":"$KDF","pinShare":"$SHARE","pinVerifier":"$VERIFIER"}""")
            .then().statusCode(201).extract().path("id")

    private fun unlock(token: String, id: String, verifier: String) =
        given().header("Authorization", "Bearer $token").contentType(ContentType.JSON).body("""{"verifier":"$verifier"}""")
            .`when`().post("/api/account/wraps/$id/pin-unlock")

    private fun failures(id: String): Int = QuarkusTransaction.requiringNew().call { MasterKeyWrapEntity.findById(id)!!.pinFailures }

    private fun seedUser(userId: String, email: String) {
        QuarkusTransaction.requiringNew().run {
            if (VeyqUser.findById(userId) == null) {
                newUser(id = userId, email = email, serverRole = "SERVER_OWNER").persist()
                MasterKeyWrapEntity().apply {
                    id = UUID.nameUUIDFromBytes("$userId-pw".toByteArray()).toString()
                    this.userId = userId
                    method = "PASSWORD"
                    wrappedKey = "cGFzc3dvcmQtd3JhcA=="
                    kdfParams = KDF
                }.persist()
            }
        }
    }

    companion object {
        private const val USER_A = "70696e75-6e6c-4000-8000-00000000000a"
        private const val USER_B = "70696e75-6e6c-4000-8000-00000000000b"
        private const val KDF = "a2RmLXBhcmFtcw=="
        private val SHARE: String = Base64.getEncoder().encodeToString(ByteArray(32) { 0x66 })
        private val VERIFIER: String = Base64.getEncoder().encodeToString(ByteArray(32) { 0x32 })
        private val WRONG: String = Base64.getEncoder().encodeToString(ByteArray(32) { 0x33 })
    }
}
