package me.raddatz.veyq.core.admin.instance

import me.raddatz.veyq.core.adminchannel.givenAdminChannel
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.ensureUser
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import org.hamcrest.CoreMatchers.equalTo
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The Server Owner's read of the store bring-up (ADR 0067 Decision 3) over the full HTTP stack: the gate, and
 * the state a `type: s3` instance reports.
 *
 * **`NOT_ATTEMPTED` here is the assertion, not a placeholder.** The `%test` profile leaves
 * `veyq.blob-store.type` at its default `s3`, which is exactly the case ADR 0067 Decision 1 is about — veyq
 * provisions nothing, forever, and that is correct rather than a failure or an `OK`. A vocabulary without this
 * value would have to lie here, which is why phase 1 named every terminal state instead of the success/failure
 * pair the happy path suggests. The other three states are reached by
 * [me.raddatz.veyq.core.blobstore.StoreProvisioningRunnerTest] on the bounded retry itself; the runner is
 * deliberately inert under `%test` (no network in the hermetic suite), so this class can only see this one.
 */
@QuarkusTest
class InstanceStoreProvisioningSliceTest {

    @BeforeEach
    fun seed() {
        ensureUser(OWNER_ID, "store-prov-owner@example.com", "SERVER_OWNER")
        ensureUser(MEMBER_ID, "store-prov-member@example.com", "MEMBER")
    }

    @Test
    fun serverOwnerReadsThatNothingIsProvisionedOnAnS3Instance() {
        givenAdminChannel().header("Authorization", ownerToken())
            .`when`().get("/api/instance/store-provisioning")
            .then().statusCode(200)
            .body("state", equalTo("NOT_ATTEMPTED"))
    }

    @Test
    fun theStoreStateNeverAppearsOnThePreAuthSurface() {
        val body = given().`when`().get("/api/setup").then().statusCode(200).extract().asString()

        // What is wrong with the operator's own infrastructure is the operator's business (ADR 0057 §4's line,
        // applied to the sibling read). The specific observable, not "it failed".
        assertFalse(body.contains("NOT_ATTEMPTED"), "the store-provisioning state must not leak pre-auth. Body was: $body")
        assertFalse(body.contains("storeProvisioning"), "GET /api/setup's response shape does not grow this field")
    }

    @Test
    fun aMemberIsNotTheServerOwnerAndIsRefused() {
        givenAdminChannel().header("Authorization", memberToken())
            .`when`().get("/api/instance/store-provisioning")
            .then().statusCode(403)
            .body("code", equalTo("not_server_owner"))
    }

    @Test
    fun anUnauthenticatedCallerIsRefused() {
        givenAdminChannel().`when`().get("/api/instance/store-provisioning")
            .then().statusCode(401)
            .body("code", equalTo("authentication_failed"))
    }

    private fun ownerToken() = bearer(OWNER_ID)

    private fun memberToken() = bearer(MEMBER_ID)

    private companion object {
        /** Distinct from every other class's: the `%test` H2 mem DB is JVM-shared across test classes. */
        const val OWNER_ID = "instance-store-prov-owner"
        const val MEMBER_ID = "instance-store-prov-member"
    }
}
