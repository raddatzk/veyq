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
import org.hamcrest.CoreMatchers.hasItems
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Slice tests for adding/removing wraps (uc.account.trust-device, server side). A device enrols a DEVICE
 * wrap (trust) and removes it (untrust); the floor can't be removed, deletes are idempotent and scoped to
 * the caller, and both endpoints reject an unauthenticated caller.
 */
@QuarkusTest
class AccountWrapsSliceTest {

    @Inject
    lateinit var sessions: SessionService

    @BeforeEach
    fun seed() {
        seedUserWithPasswordWrap(USER_A, "a-wraps@example.com")
        seedUserWithPasswordWrap(USER_B, "b-wraps@example.com")
        // The H2 mem DB lives for the whole JVM, so DEVICE wraps a prior test added persist — reset the two
        // test users back to just their PASSWORD floor before each test (scoped, so other classes' wraps stay).
        QuarkusTransaction.requiringNew().run {
            MasterKeyWrapEntity.delete("(userId = ?1 or userId = ?2) and method != ?3", USER_A, USER_B, "PASSWORD")
        }
    }

    @Test
    fun addDeviceWrapAppearsInKeyMaterialAndReturnsId() {
        val token = issueToken(USER_A)
        val id = addDeviceWrap(token, "ZGV2aWNlLXdyYXAtYQ==")
        assert(id.isNotEmpty())
        keyMaterial(token).then().statusCode(200).body("wraps.method", hasItems("PASSWORD", "DEVICE"))
    }

    @Test
    fun secondDeviceWrapCoexists() {
        val token = issueToken(USER_A)
        addDeviceWrap(token, "ZGV2aWNlLXdyYXAtMQ==")
        addDeviceWrap(token, "ZGV2aWNlLXdyYXAtMg==")
        keyMaterial(token).then().statusCode(200)
            .body("wraps.findAll { it.method == 'DEVICE' }.size()", equalTo(2))
    }

    @Test
    fun addDeviceKeyWrapRidesNormalKeyMaterial() {
        // DEVICE_KEY (password-less remember-me, cr-0054) is a normal interactive wrap — served like DEVICE,
        // NOT withheld like RECOVERY. Its kdfParams carries only the AEAD iv (opaque to the server).
        val token = issueToken(USER_A)
        val id = addWrap(token, "DEVICE_KEY", "ZGV2aWNlLWtleS13cmFw")
        assert(id.isNotEmpty())
        keyMaterial(token).then().statusCode(200).body("wraps.method", hasItems("PASSWORD", "DEVICE_KEY"))
    }

    @Test
    fun deleteRemovesDeviceKeyWrap() {
        // Re-enrol replaces (one convenience wrap per device, ADR 0005 §9): a DEVICE_KEY wrap must be removable
        // through this endpoint, not only DEVICE.
        val token = issueToken(USER_A)
        val id = addWrap(token, "DEVICE_KEY", "ZGV2aWNlLWtleS1kZWw=")
        delete(token, id).then().statusCode(204)
        keyMaterial(token).then().statusCode(200)
            .body("wraps.findAll { it.method == 'DEVICE_KEY' }.size()", equalTo(0))
    }

    @Test
    fun deleteByIdRemovesTheWrap() {
        val token = issueToken(USER_A)
        val id = addDeviceWrap(token, "ZGV2aWNlLXdyYXAtZGVs")
        delete(token, id).then().statusCode(204)
        keyMaterial(token).then().statusCode(200)
            .body("wraps.findAll { it.method == 'DEVICE' }.size()", equalTo(0))
    }

    @Test
    fun anotherUsersDeleteIsNoOp() {
        val tokenA = issueToken(USER_A)
        val tokenB = issueToken(USER_B)
        val id = addDeviceWrap(tokenA, "ZGV2aWNlLXdyYXAtb3du")
        // B tries to delete A's wrap — idempotent no-op, A's wrap survives.
        delete(tokenB, id).then().statusCode(204)
        keyMaterial(tokenA).then().statusCode(200)
            .body("wraps.findAll { it.method == 'DEVICE' }.size()", equalTo(1))
    }

    @Test
    fun deletingPasswordWrapIsNoOp() {
        val token = issueToken(USER_A)
        delete(token, UUID.nameUUIDFromBytes("$USER_A-pw".toByteArray()).toString()).then().statusCode(204) // the floor's id
        keyMaterial(token).then().statusCode(200).body("wraps.method", hasItems("PASSWORD"))
    }

    @Test
    fun deletingNonDeviceWrapIsNoOp() {
        // A RECOVERY wrap must NOT be removable through this device-untrust endpoint (whitelist DEVICE).
        val recId = UUID.nameUUIDFromBytes("$USER_A-rec".toByteArray()).toString()
        QuarkusTransaction.requiringNew().run {
            if (MasterKeyWrapEntity.findById(recId) == null) {
                MasterKeyWrapEntity().apply {
                    id = recId
                    userId = USER_A
                    method = "RECOVERY"
                    wrappedKey = "cmVjb3Zlcnktd3JhcA=="
                    kdfParams = KDF
                }.persist()
            }
        }
        val token = issueToken(USER_A)
        delete(token, recId).then().statusCode(204)
        // RECOVERY is no longer served in the normal material (cr-0052) — verify the no-op delete left the row
        // in place directly in the DB.
        QuarkusTransaction.requiringNew().run { assertNotNull(MasterKeyWrapEntity.findById(recId)) }
    }

    @Test
    fun addingBeyondTheDeviceCapIsRejected() {
        // Fill the user to the DEVICE cap, then one more POST → 409 (re-trust replaces, so real use never
        // approaches this; it caps a flooding client).
        QuarkusTransaction.requiringNew().run {
            repeat(KeyMaterialHandler.MAX_WRAPS_PER_METHOD.toInt()) {
                MasterKeyWrapEntity().apply {
                    id = UUID.randomUUID().toString()
                    userId = USER_A
                    method = "DEVICE"
                    wrappedKey = "ZGV2aWNl"
                    kdfParams = KDF
                }.persist()
            }
        }
        given().header("Authorization", "Bearer ${issueToken(USER_A)}").contentType(ContentType.JSON)
            .body("""{"method":"DEVICE","wrappedKey":"b3Zlci10aGUtY2Fw","kdfParams":"$KDF"}""")
            .`when`().post("/api/account/wraps")
            .then().statusCode(409).body("code", equalTo("too_many_devices"))
    }

    @Test
    fun nonDeviceMethodIsRejected() {
        given().header("Authorization", "Bearer ${issueToken(USER_A)}").contentType(ContentType.JSON)
            .body("""{"method":"PASSWORD","wrappedKey":"QQ==","kdfParams":"$KDF"}""")
            .`when`().post("/api/account/wraps")
            .then().statusCode(400)
    }

    // Boundary validation runs with a valid token (auth passes first, then @Valid → 400). The anonymous/bogus-
    // token side now uniformly 401s before validation (cr-0012 eager security), so the malformed-body 400
    // contract lives here, where a real session exists.
    @Test
    fun missingWrappedKeyIs400() {
        given().header("Authorization", "Bearer ${issueToken(USER_A)}").contentType(ContentType.JSON)
            .body("""{"method":"DEVICE","kdfParams":"$KDF"}""")
            .`when`().post("/api/account/wraps")
            .then().statusCode(400)
    }

    @Test
    fun nonBase64WrappedKeyIs400() {
        given().header("Authorization", "Bearer ${issueToken(USER_A)}").contentType(ContentType.JSON)
            .body("""{"method":"DEVICE","wrappedKey":"not base64!!","kdfParams":"$KDF"}""")
            .`when`().post("/api/account/wraps")
            .then().statusCode(400)
    }

    @Test
    fun unauthenticatedAddAndDeleteAre401() {
        given().contentType(ContentType.JSON)
            .body("""{"method":"DEVICE","wrappedKey":"QQ==","kdfParams":"$KDF"}""")
            .`when`().post("/api/account/wraps")
            .then().statusCode(401).body("code", equalTo("authentication_failed"))
        given().`when`().delete("/api/account/wraps/some-id")
            .then().statusCode(401).body("code", equalTo("authentication_failed"))
    }

    // --- helpers ---

    private fun issueToken(userId: String): String = sessions.issue(userId).accessToken

    private fun addDeviceWrap(token: String, wrappedKey: String): String =
        addWrap(token, "DEVICE", wrappedKey)

    private fun addWrap(token: String, method: String, wrappedKey: String): String =
        given().header("Authorization", "Bearer $token").contentType(ContentType.JSON)
            .body("""{"method":"$method","wrappedKey":"$wrappedKey","kdfParams":"$KDF"}""")
            .`when`().post("/api/account/wraps")
            .then().statusCode(201).extract().path("id")

    private fun delete(token: String, id: String) =
        given().header("Authorization", "Bearer $token").`when`().delete("/api/account/wraps/$id")

    private fun keyMaterial(token: String) =
        given().header("Authorization", "Bearer $token").`when`().get("/api/account/key-material")

    private fun seedUserWithPasswordWrap(userId: String, email: String) {
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
        private const val USER_A = "77726170-7300-4000-8000-00000000000a"
        private const val USER_B = "77726170-7300-4000-8000-00000000000b"
        private const val KDF = "a2RmLXBhcmFtcw=="
    }
}
