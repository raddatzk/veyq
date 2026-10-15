package me.raddatz.veyq.core.admin.instance

import me.raddatz.veyq.core.adminchannel.givenAdminChannel
import me.raddatz.veyq.core.blobstore.StoreSettingFixtures
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.ensureUser
import me.raddatz.veyq.core.testing.TestFixtures.seedAttachment
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import io.quarkus.test.junit.QuarkusTest
import io.restassured.http.ContentType
import io.restassured.response.ValidatableResponse
import org.hamcrest.CoreMatchers.containsString
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.not
import org.hamcrest.CoreMatchers.nullValue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The Server Owner's object-store setting and its check (ADR 0089) over the full HTTP stack on the admin channel:
 * what may be stored, what is never returned, the refusals the contract names, and the precondition the setting puts
 * on creating a tenant. The check's own steps are [me.raddatz.veyq.core.blobstore.StoreCheckTest]'s, against a fake
 * store that records what the check sends; here the check can only be refused (no store set) or fail honestly.
 *
 * `%test` has no bundled-store hint, so `bundled: true` is the refused case here; the accepted one runs in the
 * profile that supplies a hint ([me.raddatz.veyq.core.blob.StoreOriginPresignSliceTest]). The H2 mem DB is
 * JVM-shared, so every case clears the setting it stored.
 */
@QuarkusTest
class InstanceStoreSliceTest {

    @BeforeEach
    fun seed() {
        ensureUser(OWNER_ID, "store-owner@example.com", "SERVER_OWNER")
        ensureUser(MEMBER_ID, "store-member@example.com", "MEMBER")
        StoreSettingFixtures.clear()
    }

    @AfterEach
    fun forget() = StoreSettingFixtures.clear()

    @Test
    fun aFreshInstanceReadsNoSettingAndNoCheck() {
        owner().`when`().get("/api/instance/store").then().statusCode(200)
            .body("setting", nullValue())
            .body("lastCheck", nullValue())
            .body("bundledAvailable", equalTo(false))
    }

    @Test
    fun anS3StoreIsStoredAndItsSecretIsNeverReturned() {
        val body = put(s3("secret-never-returned")).statusCode(200)
            .body("setting.bundled", equalTo(false))
            .body("setting.type", equalTo("S3"))
            .body("setting.bucket", equalTo("veyq-blobs"))
            .body("setting.accessKey", equalTo("AKIAEXAMPLE"))
            .body("setting.secretKeySet", equalTo(true))
            .body("setting.garageAdminTokenSet", equalTo(false))
            .body("setting.unreadable.size()", equalTo(0))
            .body("lastCheck", nullValue())
            .extract().asString()

        assertEquals(false, body.contains("secret-never-returned"), "the write response echoed the secret")
        owner().`when`().get("/api/instance/store").then().statusCode(200)
            .body(not(containsString("secret-never-returned")))
            .body("setting.secretKeySet", equalTo(true))
    }

    @Test
    fun aSecretOmittedOnALaterWriteKeepsTheStoredOne() {
        put(s3("first-secret")).statusCode(200)
        put(s3(null, accessKey = "AKIANEW")).statusCode(200)
            .body("setting.accessKey", equalTo("AKIANEW"))
            .body("setting.secretKeySet", equalTo(true))
    }

    @Test
    fun aFirstWriteWithoutASecretIsRefused() {
        put(s3(null)).statusCode(400).body("code", equalTo("invalid_request"))
    }

    @Test
    fun aMalformedSettingIsRefused() {
        put(s3("s", endpoint = "not-a-url")).statusCode(400)
        put(s3("s", bucket = "Not_A_Bucket")).statusCode(400)
        put("""{"bundled":false,"type":"GARAGE","endpoint":"http://g:3900","bucket":"blobs","region":"r","accessKey":"k","secretKey":"s"}""")
            .statusCode(400) // Garage without its admin endpoint and token
        put("""{"bundled":true,"bucket":"blobs"}""").statusCode(400) // the bundled reference carries nothing else
        put("""{"type":"S3"}""").statusCode(400) // bundled is required
    }

    @Test
    fun theBundledStoreIsRefusedWhereTheDeploymentOffersNone() {
        put("""{"bundled":true}""").statusCode(409).body("code", equalTo("no_bundled_store"))
    }

    @Test
    fun aCheckWithNoStoreSetIsRefused() {
        owner().`when`().post("/api/instance/store/check").then().statusCode(409)
            .body("code", equalTo("store_not_configured"))
    }

    @Test
    fun aCheckAgainstAStoreNobodyAnswersFailsAtItsStepAndIsRemembered() {
        put(s3("s", endpoint = "http://store-check.invalid:9000")).statusCode(200)

        owner().`when`().post("/api/instance/store/check").then().statusCode(200)
            .body("passed", equalTo(false))
            .body("steps[0].step", equalTo("CREDENTIALS")).body("steps[0].outcome", equalTo("PASSED"))
            .body("steps[2].step", equalTo("BUCKET")).body("steps[2].outcome", equalTo("FAILED"))
            .body("steps[3].outcome", equalTo("SKIPPED"))

        owner().`when`().get("/api/instance/store").then().statusCode(200)
            .body("lastCheck.passed", equalTo(false))
            .body("lastCheck.steps[2].outcome", equalTo("FAILED"))
    }

    @Test
    fun changingTheSettingClearsItsCheck() {
        put(s3("s")).statusCode(200)
        StoreSettingFixtures.recordPassedCheck()
        owner().`when`().get("/api/instance/store").then().body("lastCheck.passed", equalTo(true))

        put(s3(null, accessKey = "AKIAOTHER")).statusCode(200).body("lastCheck", nullValue())
    }

    @Test
    fun onceBlobsExistTheStoreIsFixedButItsCredentialsAreNot() {
        seedABlob()
        put(s3("s")).statusCode(200) // no earlier store to differ from

        put(s3(null, bucket = "another-bucket")).statusCode(409).body("code", equalTo("store_in_use"))
        put(s3(null, endpoint = "https://s3.elsewhere.example.com")).statusCode(409).body("code", equalTo("store_in_use"))
        // the refusal stored nothing
        owner().`when`().get("/api/instance/store").then().body("setting.bucket", equalTo("veyq-blobs"))
        // credentials, and the public endpoint, may change
        put(s3("rotated-secret", accessKey = "AKIAROTATED", publicEndpoint = "https://files.example.com")).statusCode(200)
            .body("setting.accessKey", equalTo("AKIAROTATED"))
            .body("setting.publicEndpoint", equalTo("https://files.example.com"))
    }

    @Test
    fun aTenantIsRefusedUntilTheCurrentSettingHasPassedItsCheck() {
        put(s3("s")).statusCode(200) // a store is set, and has not been checked

        createTenant().statusCode(409).body("code", equalTo("store_not_ready"))

        StoreSettingFixtures.recordPassedCheck()
        createTenant().statusCode(201)

        // a changed setting has not been checked: the precondition is gone again
        put(s3(null, accessKey = "AKIAAGAIN")).statusCode(200)
        createTenant().statusCode(409).body("code", equalTo("store_not_ready"))
    }

    @Test
    fun aMemberIsNotTheServerOwnerAndIsRefusedOnEveryOperation() {
        val member = givenAdminChannel().header("Authorization", bearer(MEMBER_ID))
        member.`when`().get("/api/instance/store").then().statusCode(403).body("code", equalTo("not_server_owner"))
        givenAdminChannel().header("Authorization", bearer(MEMBER_ID)).contentType(ContentType.JSON).body(s3("s"))
            .`when`().put("/api/instance/store").then().statusCode(403)
        givenAdminChannel().header("Authorization", bearer(MEMBER_ID))
            .`when`().post("/api/instance/store/check").then().statusCode(403)
    }

    // --- helpers ---

    private fun owner() = givenAdminChannel().header("Authorization", bearer(OWNER_ID))

    private fun put(body: String): ValidatableResponse =
        owner().contentType(ContentType.JSON).body(body).`when`().put("/api/instance/store").then()

    private fun createTenant(): ValidatableResponse =
        owner().contentType(ContentType.JSON)
            .body("""{"tenantName":"Fam","domain":"store-gate-${UUID.randomUUID()}.example.com","ownerEmail":"o@example.com"}""")
            .`when`().post("/api/tenants").then()

    private fun s3(
        secret: String?,
        endpoint: String = "https://s3.eu-central-1.example.com",
        bucket: String = "veyq-blobs",
        accessKey: String = "AKIAEXAMPLE",
        publicEndpoint: String? = null,
    ): String = buildString {
        append("""{"bundled":false,"type":"S3","endpoint":"$endpoint","bucket":"$bucket","region":"eu-central-1","accessKey":"$accessKey"""")
        publicEndpoint?.let { append(""","publicEndpoint":"$it"""") }
        secret?.let { append(""","secretKey":"$it"""") }
        append("}")
    }

    private fun seedABlob() {
        val m = seedMemberScope()
        val node = seedNode(m.scopeId, "FOLDER", wrappedContentKey = CONTENT_KEY)
        seedAttachment(m.scopeId, node, slot = "COVER")
    }

    private companion object {
        /** Distinct from every other class's: the `%test` H2 mem DB is JVM-shared across test classes. */
        const val OWNER_ID = "7c2e9b14-5d3a-4f68-8a01-e4b6d9c3f527"
        const val MEMBER_ID = "1b8d4f06-9e27-4c53-b3a8-5f0e2d7a6c91"
    }
}
