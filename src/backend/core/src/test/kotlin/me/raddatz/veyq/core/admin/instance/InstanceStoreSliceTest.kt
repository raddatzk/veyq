package me.raddatz.veyq.core.admin.instance

import me.raddatz.veyq.core.adminchannel.givenAdminChannel
import me.raddatz.veyq.core.blobstore.StoreCheckOutcome
import me.raddatz.veyq.core.blobstore.StoreCheckResult
import me.raddatz.veyq.core.blobstore.StoreCheckStep
import me.raddatz.veyq.core.blobstore.StoreCheckStepResult
import me.raddatz.veyq.core.blobstore.StoreResolution
import me.raddatz.veyq.core.blobstore.StoreSettingFixtures
import me.raddatz.veyq.core.blobstore.StoreSettingStore
import me.raddatz.veyq.core.blobstore.StoreSettings
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.ensureUser
import me.raddatz.veyq.core.testing.TestFixtures.seedAttachment
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.core.testing.raceRounds
import me.raddatz.veyq.core.testing.whileUncommitted
import com.sun.net.httpserver.HttpServer
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import io.restassured.http.ContentType
import io.restassured.response.ValidatableResponse
import org.hamcrest.CoreMatchers.containsString
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.not
import org.hamcrest.CoreMatchers.nullValue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.time.Instant
import java.util.UUID

/**
 * The Server Owner's object-store setting and its check (ADR 0089) over the full HTTP stack on the admin channel:
 * what may be stored, what is never returned, the refusals the contract names, the precondition the setting puts
 * on creating a tenant — and decision 5's promotion: a write is saved as **pending** and changes nothing the instance
 * does until its own check passes. The check's own steps are [me.raddatz.veyq.core.blobstore.StoreCheckTest]'s; here
 * a check passes against a local fake S3 endpoint ([fakeStore]) and fails against a host nobody answers.
 *
 * The bite of the promotion is [aChangedSettingIsNotUsedUntilItsCheckPassesAndAFailingOneLeavesTheOldInUse]: had a
 * write applied at once (as it did before), the store the backend resolves would be the typo right after the `PUT`.
 *
 * `%test` has no bundled-store hint, so `bundled: true` is the refused case here; the accepted one runs in the
 * profile that supplies a hint ([me.raddatz.veyq.core.blob.StoreOriginPresignSliceTest]). The H2 mem DB is
 * JVM-shared, so every case clears the setting it stored.
 */
@QuarkusTest
class InstanceStoreSliceTest {

    @Inject
    lateinit var settings: StoreSettings

    @Inject
    lateinit var handler: StoreHandler

    @Inject
    lateinit var settingStore: StoreSettingStore

    private lateinit var fakeStore: HttpServer

    @BeforeEach
    fun seed() {
        ensureUser(OWNER_ID, "store-owner@example.com", "SERVER_OWNER")
        ensureUser(MEMBER_ID, "store-member@example.com", "MEMBER")
        StoreSettingFixtures.clear()
        fakeStore = startFakeStore()
    }

    @AfterEach
    fun forget() {
        fakeStore.stop(0)
        StoreSettingFixtures.clear()
    }

    @Test
    fun aFreshInstanceReadsNoSettingAndNoCheck() {
        owner().`when`().get("/api/instance/store").then().statusCode(200)
            .body("setting", nullValue())
            .body("lastCheck", nullValue())
            .body("pending", nullValue())
            .body("bundledAvailable", equalTo(false))
    }

    @Test
    fun anS3StoreIsSavedAsPendingAndItsSecretIsNeverReturned() {
        val body = put(s3("secret-never-returned")).statusCode(200)
            .body("setting", nullValue()) // not in use before it passed its check
            .body("pending.setting.bundled", equalTo(false))
            .body("pending.setting.type", equalTo("S3"))
            .body("pending.setting.bucket", equalTo("veyq-blobs"))
            .body("pending.setting.accessKey", equalTo("AKIAEXAMPLE"))
            .body("pending.setting.secretKeySet", equalTo(true))
            .body("pending.setting.garageAdminTokenSet", equalTo(false))
            .body("pending.setting.unreadable.size()", equalTo(0))
            .body("pending.lastCheck", nullValue())
            .extract().asString()

        assertEquals(false, body.contains("secret-never-returned"), "the write response echoed the secret")
        owner().`when`().get("/api/instance/store").then().statusCode(200)
            .body(not(containsString("secret-never-returned")))
            .body("pending.setting.secretKeySet", equalTo(true))
        assertEquals(StoreResolution.NotSet, settings.current(), "a setting that never passed its check is not used")
    }

    @Test
    fun aSecretOmittedOnALaterWriteKeepsTheStoredOne() {
        put(s3("first-secret")).statusCode(200)
        put(s3(null, accessKey = "AKIANEW")).statusCode(200)
            .body("pending.setting.accessKey", equalTo("AKIANEW"))
            .body("pending.setting.secretKeySet", equalTo(true))
    }

    @Test
    fun aPassingCheckPutsThePendingSettingInUse() {
        put(s3("s", endpoint = fakeEndpoint())).statusCode(200)

        owner().`when`().post("/api/instance/store/check").then().statusCode(200).body("passed", equalTo(true))

        owner().`when`().get("/api/instance/store").then().statusCode(200)
            .body("setting.endpoint", equalTo(fakeEndpoint()))
            .body("lastCheck.passed", equalTo(true))
            .body("pending", nullValue())
        assertEquals(fakeEndpoint(), resolvedEndpoint())
        createTenant().statusCode(201)
    }

    @Test
    fun aChangedSettingIsNotUsedUntilItsCheckPassesAndAFailingOneLeavesTheOldInUse() {
        seedABlob() // a running instance: its media are stored, so only credentials and the public endpoint may change
        put(s3("s", endpoint = fakeEndpoint())).statusCode(200)
        owner().`when`().post("/api/instance/store/check").then().statusCode(200).body("passed", equalTo(true))

        // A typo in the public endpoint — a change still allowed once media are stored (decision 6), so the case a
        // running instance meets: saved, and nothing the instance does follows it.
        put(s3(null, endpoint = fakeEndpoint(), publicEndpoint = TYPO_ENDPOINT)).statusCode(200)
            .body("setting.publicEndpoint", nullValue())
            .body("lastCheck.passed", equalTo(true))
            .body("pending.setting.publicEndpoint", equalTo(TYPO_ENDPOINT))
        assertEquals(null, resolvedPublicEndpoint(), "the write switched the store in use")

        // Its check fails at the probe: it stays pending, the store in use and the tenant gate are untouched.
        owner().`when`().post("/api/instance/store/check").then().statusCode(200).body("passed", equalTo(false))
        owner().`when`().get("/api/instance/store").then()
            .body("setting.publicEndpoint", nullValue())
            .body("lastCheck.passed", equalTo(true))
            .body("pending.setting.publicEndpoint", equalTo(TYPO_ENDPOINT))
            .body("pending.lastCheck.passed", equalTo(false))
        assertEquals(null, resolvedPublicEndpoint(), "a failed check switched the store in use")
        assertEquals(fakeEndpoint(), resolvedEndpoint())
        createTenant().statusCode(201)
    }

    @Test
    fun aPendingSettingCanBeDiscardedAndTheOneInUseStays() {
        put(s3("s", endpoint = fakeEndpoint())).statusCode(200)
        owner().`when`().post("/api/instance/store/check").then().statusCode(200).body("passed", equalTo(true))
        put(s3(null, endpoint = fakeEndpoint(), publicEndpoint = TYPO_ENDPOINT)).statusCode(200).body("pending", not(nullValue()))

        owner().`when`().delete("/api/instance/store/pending").then().statusCode(200)
            .body("pending", nullValue())
            .body("setting.publicEndpoint", nullValue())
        owner().`when`().delete("/api/instance/store/pending").then().statusCode(200).body("pending", nullValue()) // idempotent
        assertEquals(fakeEndpoint(), resolvedEndpoint())
    }

    /**
     * Two checks of one pending setting put it in use once (ADR 0089 decision 3 adds a second checker): the boot's
     * check of an adopted bundled store and the Server Owner's own can overlap. The held side is the first check's
     * promotion ([StoreHandler.settle], in the transaction the hold keeps open); the overlapping side is a whole second
     * check, whose read saw the setting still pending. It waits on the instance singleton's lock, then finds nothing
     * pending and records nothing — without the lock it promoted too, onto the first one's uncommitted active row.
     */
    @Test
    fun twoChecksOfOnePendingSettingPutItInUseOnce() {
        raceRounds { round ->
            StoreSettingFixtures.clear()
            put(s3("s$round", endpoint = fakeEndpoint())).statusCode(200).body("pending", not(nullValue()))
            val target = QuarkusTransaction.requiringNew().call { settingStore.findPending() }!!
            val first = StoreCheckResult(true, Instant.now(), StoreCheckStep.entries.map { StoreCheckStepResult(it, StoreCheckOutcome.PASSED, HELD) })

            val second = whileUncommitted(
                change = { assertTrue(handler.settle(target, checkedPending = true, first), "control: the held check promotes") },
                overlapping = { owner().`when`().post("/api/instance/store/check").then().extract() },
            )

            assertEquals(200, second.statusCode(), second.asString())
            QuarkusTransaction.requiringNew().run {
                assertNull(settingStore.findPending(), "nothing pending any more")
                assertEquals(target, settingStore.find(), "the checked setting is the one in use")
                assertEquals(HELD, settingStore.lastCheck()?.steps?.first()?.detail, "and its check is the first one's, not overwritten by the second")
            }
        }
    }

    @Test
    fun aWriteEqualToTheSettingInUseLeavesNothingPending() {
        put(s3("s", endpoint = fakeEndpoint())).statusCode(200)
        owner().`when`().post("/api/instance/store/check").then().statusCode(200).body("passed", equalTo(true))

        // The form sent back as it stands, the secret omitted: nothing changed, so nothing waits for a check.
        put(s3(null, endpoint = fakeEndpoint())).statusCode(200).body("pending", nullValue())
    }

    @Test
    fun withNothingPendingTheCheckRunsAgainstTheSettingInUse() {
        put(s3("s", endpoint = fakeEndpoint())).statusCode(200)
        owner().`when`().post("/api/instance/store/check").then().statusCode(200).body("passed", equalTo(true))
        fakeStore.stop(0) // the store in use stops answering

        owner().`when`().post("/api/instance/store/check").then().statusCode(200).body("passed", equalTo(false))
        owner().`when`().get("/api/instance/store").then()
            .body("setting.endpoint", equalTo(fakeEndpoint()))
            .body("lastCheck.passed", equalTo(false))
            .body("pending", nullValue())
        createTenant().statusCode(409).body("code", equalTo("store_not_ready"))
        fakeStore = startFakeStore() // for forget()
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
        put(s3("s", endpoint = TYPO_ENDPOINT)).statusCode(200)

        owner().`when`().post("/api/instance/store/check").then().statusCode(200)
            .body("passed", equalTo(false))
            .body("steps[0].step", equalTo("CREDENTIALS")).body("steps[0].outcome", equalTo("PASSED"))
            .body("steps[2].step", equalTo("BUCKET")).body("steps[2].outcome", equalTo("FAILED"))
            .body("steps[3].outcome", equalTo("SKIPPED"))

        owner().`when`().get("/api/instance/store").then().statusCode(200)
            .body("setting", nullValue())
            .body("pending.lastCheck.passed", equalTo(false))
            .body("pending.lastCheck.steps[2].outcome", equalTo("FAILED"))
    }

    @Test
    fun changingTheSettingStartsThePendingOneUnchecked() {
        put(s3("s", endpoint = fakeEndpoint())).statusCode(200)
        owner().`when`().post("/api/instance/store/check").then().statusCode(200).body("passed", equalTo(true))

        put(s3(null, endpoint = fakeEndpoint(), accessKey = "AKIAOTHER")).statusCode(200)
            .body("lastCheck.passed", equalTo(true))
            .body("pending.lastCheck", nullValue())
    }

    @Test
    fun onceBlobsExistTheStoreIsFixedButItsCredentialsAreNot() {
        StoreSettingFixtures.chooseS3(endpoint = "https://s3.eu-central-1.example.com", bucket = "veyq-blobs") // in use
        seedABlob()

        put(s3("s", bucket = "another-bucket")).statusCode(409).body("code", equalTo("store_in_use"))
        put(s3("s", endpoint = "https://s3.elsewhere.example.com")).statusCode(409).body("code", equalTo("store_in_use"))
        // the refusal stored nothing
        owner().`when`().get("/api/instance/store").then().body("setting.bucket", equalTo("veyq-blobs")).body("pending", nullValue())
        // credentials, and the public endpoint, may change
        put(s3("rotated-secret", accessKey = "AKIAROTATED", publicEndpoint = "https://files.example.com")).statusCode(200)
            .body("pending.setting.accessKey", equalTo("AKIAROTATED"))
            .body("pending.setting.publicEndpoint", equalTo("https://files.example.com"))
    }

    @Test
    fun aTenantIsRefusedUntilASettingHasPassedItsCheck() {
        put(s3("s", endpoint = fakeEndpoint())).statusCode(200) // saved, not checked: nothing in use

        createTenant().statusCode(409).body("code", equalTo("store_not_ready"))

        owner().`when`().post("/api/instance/store/check").then().statusCode(200).body("passed", equalTo(true))
        createTenant().statusCode(201)

        // a changed setting waits beside the checked one, so the precondition holds
        put(s3(null, endpoint = fakeEndpoint(), accessKey = "AKIAAGAIN")).statusCode(200)
        createTenant().statusCode(201)
    }

    @Test
    fun aMemberIsNotTheServerOwnerAndIsRefusedOnEveryOperation() {
        val member = givenAdminChannel().header("Authorization", bearer(MEMBER_ID))
        member.`when`().get("/api/instance/store").then().statusCode(403).body("code", equalTo("not_server_owner"))
        givenAdminChannel().header("Authorization", bearer(MEMBER_ID)).contentType(ContentType.JSON).body(s3("s"))
            .`when`().put("/api/instance/store").then().statusCode(403)
        givenAdminChannel().header("Authorization", bearer(MEMBER_ID))
            .`when`().post("/api/instance/store/check").then().statusCode(403)
        givenAdminChannel().header("Authorization", bearer(MEMBER_ID))
            .`when`().delete("/api/instance/store/pending").then().statusCode(403)
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

    private fun resolvedEndpoint(): String? = (settings.current() as? StoreResolution.Ready)?.connection?.endpoint

    private fun resolvedPublicEndpoint(): String? = (settings.current() as? StoreResolution.Ready)?.connection?.publicEndpoint

    private fun fakeEndpoint() = "http://127.0.0.1:${fakeStore.address.port}"

    /**
     * A local S3 endpoint that answers what the check asks: the bucket exists, the probe object is stored and read
     * back, and a preflight is answered with its own origin. It ignores the signature — the check's steps are
     * StoreCheckTest's; here only "this store passes" matters.
     */
    private fun startFakeStore(): HttpServer {
        val objects = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
        return HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                val path = ex.requestURI.path
                var body = ByteArray(0)
                val status = when (ex.requestMethod) {
                    "HEAD" -> 200
                    "PUT" -> { objects[path] = ex.requestBody.readBytes(); 200 }
                    "GET" -> objects[path]?.let { body = it; 200 } ?: 404
                    "OPTIONS" -> { ex.requestHeaders.getFirst("Origin")?.let { ex.responseHeaders.add("Access-Control-Allow-Origin", it) }; 200 }
                    else -> 400
                }
                ex.sendResponseHeaders(status, if (ex.requestMethod == "HEAD" || body.isEmpty()) -1 else body.size.toLong())
                if (body.isNotEmpty()) ex.responseBody.use { it.write(body) }
                ex.close()
            }
            start()
        }
    }

    private fun seedABlob() {
        val m = seedMemberScope()
        val node = seedNode(m.scopeId, "FOLDER", wrappedContentKey = CONTENT_KEY)
        seedAttachment(m.scopeId, node, slot = "COVER")
    }

    private companion object {
        /** A host nobody answers (`.invalid` never resolves): a typo's store. */
        const val TYPO_ENDPOINT = "http://store-check.invalid:9000"

        /** The held check's step detail, which tells its record apart from the overlapping check's. */
        const val HELD = "the held check"

        /** Distinct from every other class's: the `%test` H2 mem DB is JVM-shared across test classes. */
        const val OWNER_ID = "7c2e9b14-5d3a-4f68-8a01-e4b6d9c3f527"
        const val MEMBER_ID = "1b8d4f06-9e27-4c53-b3a8-5f0e2d7a6c91"
    }
}
