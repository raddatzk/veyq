package me.raddatz.veyq.core.account.keymaterial

import me.raddatz.veyq.core.account.login.AccessTokenEntity
import me.raddatz.veyq.core.account.login.SessionFamilyEntity
import me.raddatz.veyq.core.account.login.SessionService
import me.raddatz.veyq.core.crypto.Tokens
import me.raddatz.veyq.core.identity.MasterKeyWrapEntity
import me.raddatz.veyq.core.identity.VeyqUser
import me.raddatz.veyq.core.platform.capabilities.CapabilityIds
import me.raddatz.veyq.core.platform.capabilities.CapabilitySwitchFixtures
import me.raddatz.veyq.core.testing.TestFixtures.newUser
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.not
import org.hamcrest.Matchers.containsInAnyOrder
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.hasItems
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Slice tests for the authenticated key-material read (uc.account.unlock, server side). The server only
 * serves ciphertext: a valid session fetches the caller's own wraps + identity blob; a stolen-but-dead
 * token (missing / malformed / expired / revoked family) is a generic 401; and one user's token never
 * returns another user's material. RECOVERY is always withheld, a device wrap while its switch is off (ADR 0088
 * decision 5), and PASSWORD never.
 */
@QuarkusTest
class AccountKeyMaterialSliceTest {

    @Inject
    lateinit var sessions: SessionService

    @BeforeEach
    fun seed() {
        seedAccount(USER_A, "a-keymat@example.com", A_PW_WRAP, A_REC_WRAP, A_PRIV)
        seedAccount(USER_B, "b-keymat@example.com", B_PW_WRAP, B_REC_WRAP, B_PRIV)
    }

    @Test
    fun validTokenReturnsCallersMaterial() {
        // The account is seeded with a PASSWORD floor + a RECOVERY wrap, but the normal material serves only
        // the interactive (PASSWORD) wrap — RECOVERY is withheld for the email-gated recovery flow (cr-0052).
        get(issueToken(USER_A))
            .then().statusCode(200)
            // the caller's own id — its sealed name is bound to it (ADR 0072) — and only ever the caller's
            .body("userId", equalTo(USER_A))
            .body("suiteId", equalTo("v1"))
            .body("wrappedIdentityPrivateKey", equalTo(A_PRIV))
            .body("wraps.size()", equalTo(1))
            .body("wraps.method", hasItems("PASSWORD"))
            .body("wraps.find { it.method == 'PASSWORD' }.wrappedKey", equalTo(A_PW_WRAP))
            .body("wraps.find { it.method == 'PASSWORD' }.kdfParams", equalTo(KDF))
    }

    @Test
    fun recoveryWrapIsNeverServedInNormalMaterial() {
        // The Secret-Key-only RECOVERY wrap must not ride the normal unlock material (else a session + the
        // Secret Key would open the vault without the password). It is released only by the recovery flow.
        get(issueToken(USER_A))
            .then().statusCode(200)
            .body("wraps.method", not(hasItem("RECOVERY")))
    }

    @Test
    fun rememberDeviceOffWithholdsBothDeviceWrapsAndServesThePasswordWrap() {
        val userId = seedDeviceAccount("6b65796d-6174-4000-8000-0000000d0001")
        val token = issueToken(userId)
        get(token).then().statusCode(200).body("wraps.method", containsInAnyOrder("PASSWORD", "DEVICE", "DEVICE_KEY")) // control
        CapabilitySwitchFixtures.with(CapabilityIds.REMEMBER_DEVICE) {
            // DEVICE_KEY's own switch is on; the AND along the path withholds it with its parent.
            get(token).then().statusCode(200).body("wraps.method", containsInAnyOrder("PASSWORD"))
        }
        // Back on: every wrap is served again, because none was deleted.
        get(token).then().statusCode(200).body("wraps.method", containsInAnyOrder("PASSWORD", "DEVICE", "DEVICE_KEY"))
        assertEquals(4L, wrapCount(userId), "the switch deleted a wrap")
    }

    @Test
    fun passwordlessOffWithholdsOnlyTheDeviceKeyWrap() {
        val userId = seedDeviceAccount("6b65796d-6174-4000-8000-0000000d0002")
        val token = issueToken(userId)
        CapabilitySwitchFixtures.with(CapabilityIds.REMEMBER_DEVICE_PASSWORDLESS) {
            get(token).then().statusCode(200).body("wraps.method", containsInAnyOrder("PASSWORD", "DEVICE"))
        }
        get(token).then().statusCode(200).body("wraps.method", containsInAnyOrder("PASSWORD", "DEVICE", "DEVICE_KEY"))
        assertEquals(4L, wrapCount(userId), "the switch deleted a wrap")
    }

    @Test
    fun tokenReturnsOnlyOwnMaterialNotAnothers() {
        // User A's token must return A's ciphertext only — never B's, even though both accounts exist.
        get(issueToken(USER_A))
            .then().statusCode(200)
            .body("wrappedIdentityPrivateKey", equalTo(A_PRIV))
            .body("wraps.find { it.method == 'PASSWORD' }.wrappedKey", equalTo(A_PW_WRAP))
    }

    @Test
    fun unknownWrapMethodIsSkippedNotA500() {
        val userId = "6b65796d-6174-4000-8000-00000000b065"
        QuarkusTransaction.requiringNew().run {
            if (VeyqUser.findById(userId) == null) {
                newUser(
                    id = userId, email = "bogus-keymat@example.com", serverRole = "SERVER_OWNER",
                    wrappedIdentityPrivateKey = "QS1pZGVudGl0eS1wcml2",
                ).persist()
                persistWrap(userId, "PASSWORD", A_PW_WRAP)
                persistWrap(userId, "FUTURE_METHOD", A_REC_WRAP) // not in the enum → must be skipped, not 500
            }
        }
        get(issueToken(userId))
            .then().statusCode(200)
            .body("wraps.size()", equalTo(1))
            .body("wraps[0].method", equalTo("PASSWORD"))
    }

    @Test
    fun missingTokenIsGeneric401() {
        given().`when`().get(PATH)
            .then().statusCode(401).body("code", equalTo("authentication_failed"))
    }

    @Test
    fun malformedTokenIsGeneric401() {
        get("not-a-real-token")
            .then().statusCode(401).body("code", equalTo("authentication_failed"))
    }

    @Test
    fun expiredTokenIs401() {
        val token = issueToken(USER_A)
        QuarkusTransaction.requiringNew().run {
            AccessTokenEntity.find("tokenHash", Tokens.hash(token)).firstResult()!!
                .expiresAt = Instant.now().minusSeconds(60)
        }
        get(token).then().statusCode(401).body("code", equalTo("authentication_failed"))
    }

    @Test
    fun revokedFamilyTokenIs401() {
        val token = issueToken(USER_A)
        QuarkusTransaction.requiringNew().run {
            val at = AccessTokenEntity.find("tokenHash", Tokens.hash(token)).firstResult()!!
            SessionFamilyEntity.findById(at.familyId)!!.status = SessionFamilyEntity.REVOKED
        }
        get(token).then().statusCode(401).body("code", equalTo("authentication_failed"))
    }

    // --- helpers ---

    private fun issueToken(userId: String): String = sessions.issue(userId).accessToken

    private fun get(token: String) =
        given().header("Authorization", "Bearer $token").`when`().get(PATH)

    private fun seedAccount(userId: String, email: String, pwWrap: String, recWrap: String, priv: String) {
        QuarkusTransaction.requiringNew().run {
            if (VeyqUser.findById(userId) == null) {
                newUser(id = userId, email = email, serverRole = "SERVER_OWNER", wrappedIdentityPrivateKey = priv)
                    .persist()
                persistWrap(userId, "PASSWORD", pwWrap)
                persistWrap(userId, "RECOVERY", recWrap)
            }
        }
    }

    /** An account holding every wrap a remembered device can have, beside the PASSWORD floor and RECOVERY. */
    private fun seedDeviceAccount(userId: String): String {
        QuarkusTransaction.requiringNew().run {
            if (VeyqUser.findById(userId) == null) {
                newUser(id = userId, email = "$userId@example.com", serverRole = "MEMBER", wrappedIdentityPrivateKey = A_PRIV).persist()
                persistWrap(userId, "PASSWORD", A_PW_WRAP)
                persistWrap(userId, "RECOVERY", A_REC_WRAP)
                persistWrap(userId, "DEVICE", A_DEVICE_WRAP)
                persistWrap(userId, "DEVICE_KEY", A_DEVICE_KEY_WRAP)
            }
        }
        return userId
    }

    private fun wrapCount(userId: String): Long =
        QuarkusTransaction.requiringNew().call { MasterKeyWrapEntity.count("userId", userId) }

    private fun persistWrap(userId: String, method: String, wrappedKey: String) {
        MasterKeyWrapEntity().apply {
            id = UUID.randomUUID().toString()
            this.userId = userId
            this.method = method
            this.wrappedKey = wrappedKey
            kdfParams = KDF
        }.persist()
    }

    companion object {
        private const val PATH = "/api/account/key-material"
        private const val USER_A = "6b65796d-6174-4000-8000-00000000000a"
        private const val USER_B = "6b65796d-6174-4000-8000-00000000000b"
        private const val A_PW_WRAP = "QS1wYXNzd29yZC13cmFw"
        private const val A_REC_WRAP = "QS1yZWNvdmVyeS13cmFw"
        private const val A_DEVICE_WRAP = "QS1kZXZpY2Utd3JhcA=="
        private const val A_DEVICE_KEY_WRAP = "QS1kZXZpY2Uta2V5LXdyYXA="
        private const val A_PRIV = "QS1pZGVudGl0eS1wcml2"
        private const val B_PW_WRAP = "Qi1wYXNzd29yZC13cmFw"
        private const val B_REC_WRAP = "Qi1yZWNvdmVyeS13cmFw"
        private const val B_PRIV = "Qi1pZGVudGl0eS1wcml2"
        private const val KDF = "a2RmLXBhcmFtcw=="
    }
}
