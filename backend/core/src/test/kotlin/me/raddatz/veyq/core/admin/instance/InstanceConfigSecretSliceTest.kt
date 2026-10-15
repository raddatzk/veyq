package me.raddatz.veyq.core.admin.instance

import me.raddatz.veyq.core.adminchannel.givenAdminChannel
import me.raddatz.veyq.core.instance.InstanceStore
import me.raddatz.veyq.core.instance.VeyqInstance
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.ensureUser
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.containsString
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.not
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The Server Owner's read of the config-secret state (ADR 0050) on the admin channel, over the full HTTP stack —
 * the verification step of a restore and an upgrade (uc.admin.back-up-and-restore-instance step 11), run by a
 * Server Owner who owns no tenant and so cannot read it through a tenant's mail settings.
 *
 * **The read changes nothing.** The mail paths' [me.raddatz.veyq.core.crypto.ConfigSecretKeyGuard.state] adopts
 * the current key's fingerprint when none is stored; this read must not, or a restore's verification would write
 * the row a later comparison trusts. [aReadOnAnInstanceWithNoFingerprintLeavesItWithNone] pins that, by the row.
 *
 * The H2 mem DB is JVM-shared, so every test that moves the stored fingerprint puts back the value it found.
 */
@QuarkusTest
class InstanceConfigSecretSliceTest {

    @Inject
    lateinit var instance: InstanceStore

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
    fun aReadOnAnInstanceWithNoFingerprintLeavesItWithNone() {
        withStoredFingerprint(null) {
            assertNull(stored(), "precondition: no fingerprint is stored before the read")
            givenAdminChannel().header("Authorization", bearer(OWNER_ID))
                .`when`().get("/api/instance/config-secret")
                .then().statusCode(200)
                .body("state", equalTo("OK"))
            assertNull(stored(), "the read adopted the current key's fingerprint")
        }
    }

    @Test
    fun aKeyThatIsNotTheOneThatSealedReadsAsMismatch() {
        withStoredFingerprint("not-the-current-key") {
            givenAdminChannel().header("Authorization", bearer(OWNER_ID))
                .`when`().get("/api/instance/config-secret")
                .then().statusCode(200)
                .body("state", equalTo("MISMATCH"))
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

    /** Runs [block] with the stored fingerprint set to [value], then restores what was stored before. */
    private fun withStoredFingerprint(value: String?, block: () -> Unit) {
        val before = stored()
        setStored(value)
        try {
            block()
        } finally {
            setStored(before)
        }
    }

    /**
     * The row as committed, read in a transaction of its own. Read outside one, the test thread's request-scoped
     * session hands back the entity it loaded earlier — so a read that DID adopt would still look unadopted, and
     * the test that exists to catch that would pass on it (it did, on the first bite).
     */
    private fun stored(): String? = QuarkusTransaction.requiringNew().call { instance.configSecretFingerprint() }

    private fun setStored(value: String?) = QuarkusTransaction.requiringNew().run {
        VeyqInstance.findById(VeyqInstance.SINGLETON_ID)!!.configSecretFingerprint = value
    }

    private companion object {
        /** Distinct from every other class's: the `%test` H2 mem DB is JVM-shared across test classes. */
        const val OWNER_ID = "5d0c6f3e-2b8a-4e41-9c7d-0a6e3f1b8c24"
        const val MEMBER_ID = "8f3b1e72-6c4d-4a59-b0e8-2d7a9c5f1e63"
    }
}
