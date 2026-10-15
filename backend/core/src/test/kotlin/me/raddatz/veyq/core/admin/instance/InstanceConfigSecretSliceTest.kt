package me.raddatz.veyq.core.admin.instance

import me.raddatz.veyq.core.adminchannel.givenAdminChannel
import me.raddatz.veyq.core.blobstore.SealedValue
import me.raddatz.veyq.core.blobstore.StoreSettingEntity
import me.raddatz.veyq.core.blobstore.StoreSettingFixtures
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.ensureUser
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import org.hamcrest.CoreMatchers.containsString
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.not
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The Server Owner's read of the config-secret state (ADR 0050) on the admin channel, over the full HTTP stack —
 * the verification step of a restore and an upgrade (uc.admin.back-up-and-restore-instance step 11), run by a
 * Server Owner who owns no tenant and so cannot read it through a tenant's mail settings.
 *
 * **The read changes nothing.** Every sealed value carries the fingerprint of the key that sealed it (ADR 0089
 * decision 4), so there is nothing to adopt and nothing for a read to write — a restore's verification must not write
 * the row a later comparison trusts. [aReadWritesNothing] pins that, by the row count.
 *
 * The H2 mem DB is JVM-shared, so every test that stores a sealed value clears it again.
 */
@QuarkusTest
class InstanceConfigSecretSliceTest {

    @BeforeEach
    fun seed() {
        ensureUser(OWNER_ID, "config-secret-owner@example.com", "SERVER_OWNER")
        ensureUser(MEMBER_ID, "config-secret-member@example.com", "MEMBER")
    }

    @Test
    fun serverOwnerWhoOwnsNoTenantReadsTheState() {
        givenAdminChannel().header("Authorization", bearer(OWNER_ID))
            .`when`().get("/api/instance/config-secret")
            .then().statusCode(200)
            .body("state", equalTo("OK"))
    }

    @Test
    fun aReadWritesNothing() {
        StoreSettingFixtures.clear()
        assertEquals(0L, rows(), "precondition: no store setting row")
        givenAdminChannel().header("Authorization", bearer(OWNER_ID))
            .`when`().get("/api/instance/config-secret")
            .then().statusCode(200)
            .body("state", equalTo("OK"))
        assertEquals(0L, rows(), "the read wrote a row")
    }

    @Test
    fun aStoredValueSealedUnderAnotherKeyReadsAsMismatch() {
        try {
            StoreSettingFixtures.chooseS3(secretKey = SealedValue("c2VhbGVk", "bm9uY2U=", "not-the-current-key"))
            givenAdminChannel().header("Authorization", bearer(OWNER_ID))
                .`when`().get("/api/instance/config-secret")
                .then().statusCode(200)
                .body("state", equalTo("MISMATCH"))
        } finally {
            StoreSettingFixtures.clear()
        }
    }

    @Test
    fun aMemberIsNotTheServerOwnerAndGetsNoState() {
        givenAdminChannel().header("Authorization", bearer(MEMBER_ID))
            .`when`().get("/api/instance/config-secret")
            .then().statusCode(403)
            .body("code", equalTo("not_server_owner"))
            .body(not(containsString("\"state\"")))
    }

    @Test
    fun anUnauthenticatedCallerIsRefused() {
        givenAdminChannel().`when`().get("/api/instance/config-secret")
            .then().statusCode(401)
            .body("code", equalTo("authentication_failed"))
    }

    @Test
    fun onThePublicPortTheOperationDoesNotExist() {
        given().header("Authorization", bearer(OWNER_ID))
            .`when`().get("/api/instance/config-secret")
            .then().statusCode(404)
            .body(not(containsString("\"state\"")))
    }

    /**
     * Counted in a transaction of its own. Read on the test thread outside one, Panache answers from the
     * request-scoped session, which can still hold what it loaded before the call — and a write the read made would
     * be invisible to the very assertion that exists to catch it (the first bite of this test passed that way).
     */
    private fun rows(): Long = QuarkusTransaction.requiringNew().call { StoreSettingEntity.count() }

    private companion object {
        /** Distinct from every other class's: the `%test` H2 mem DB is JVM-shared across test classes. */
        const val OWNER_ID = "5d0c6f3e-2b8a-4e41-9c7d-0a6e3f1b8c24"
        const val MEMBER_ID = "8f3b1e72-6c4d-4a59-b0e8-2d7a9c5f1e63"
    }
}
