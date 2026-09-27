package com.veyq.app.features.account.deviceregister

import com.veyq.app.features.account.login.SessionService
import com.veyq.app.testing.TestFixtures.seedUser
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.not
import org.hamcrest.CoreMatchers.nullValue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Slice tests for the operator-blind device register (en.account.device-register, ADR 0004). The server stores +
 * serves one opaque sealed blob + a version: a born-empty account reads `{ blob: null, version: 0 }`; a
 * version-guarded PUT round-trips and bumps the version; a stale version is a `409 stale_version`; and the
 * register is keyed off the caller's own token (no cross-account read).
 *
 * The 409 cases carry the most weight here and are not mere race guards: the client's whole correctness argument
 * — that a union-merge cannot resurrect a removed device — rests on being *forced* through the re-read/merge
 * path. A server that silently accepted a stale write would leave that client code correct and unreachable. So
 * each conflict case also asserts the store is **untouched**, which a 409 status alone does not show.
 *
 * Each test seeds a UUID-unique account so the JVM-lived H2 mem DB (shared across test classes) can't bleed a
 * written register row between tests.
 */
@QuarkusTest
class DeviceRegisterSliceTest {

    @Inject
    lateinit var sessions: SessionService

    @Test
    fun bornEmptyRegisterReadsNullBlobVersionZero() {
        get(issueToken(seedUser()))
            .then().statusCode(200)
            .body("blob", nullValue())
            .body("version", equalTo(0))
    }

    @Test
    fun firstWriteRoundTripsAndBumpsToVersionOne() {
        val token = issueToken(seedUser())
        put(token, BLOB_1, 0)
            .then().statusCode(200)
            .body("blob", equalTo(BLOB_1))
            .body("version", equalTo(1))
        get(token)
            .then().statusCode(200)
            .body("blob", equalTo(BLOB_1))
            .body("version", equalTo(1))
    }

    @Test
    fun subsequentWriteAtCurrentVersionBumpsAgain() {
        val token = issueToken(seedUser())
        put(token, BLOB_1, 0).then().statusCode(200).body("version", equalTo(1))
        put(token, BLOB_2, 1)
            .then().statusCode(200)
            .body("blob", equalTo(BLOB_2))
            .body("version", equalTo(2))
    }

    @Test
    fun staleVersionOnFirstWriteIsConflict() {
        val token = issueToken(seedUser())
        // A born-empty register only accepts a first write against version 0 — a non-zero expected version is stale.
        put(token, BLOB_1, 5)
            .then().statusCode(409).body("code", equalTo("stale_version"))
        // The control the status code cannot give: the rejected write created no row at all.
        get(token).then().statusCode(200).body("blob", nullValue()).body("version", equalTo(0))
    }

    @Test
    fun staleVersionAfterAWriteIsConflictAndLeavesTheStoredBlobIntact() {
        val token = issueToken(seedUser())
        put(token, BLOB_1, 0).then().statusCode(200).body("version", equalTo(1))
        // Another device already moved the register to version 1; a second write still based on version 0 is stale.
        put(token, BLOB_2, 0)
            .then().statusCode(409).body("code", equalTo("stale_version"))
        // The register is untouched by the rejected write — this is what makes the client's re-read honest.
        get(token).then().statusCode(200)
            .body("blob", equalTo(BLOB_1))
            .body("blob", not(equalTo(BLOB_2)))
            .body("version", equalTo(1))
    }

    @Test
    fun aWriteAtAFutureVersionIsAlsoRejected() {
        val token = issueToken(seedUser())
        put(token, BLOB_1, 0).then().statusCode(200).body("version", equalTo(1))
        // The guard is an equality compare-and-set, not "at least" — a client that guessed ahead is stale too,
        // and must re-read rather than skip a version nobody wrote.
        put(token, BLOB_2, 7).then().statusCode(409).body("code", equalTo("stale_version"))
        get(token).then().statusCode(200).body("blob", equalTo(BLOB_1)).body("version", equalTo(1))
    }

    @Test
    fun registerIsPerAccountNotShared() {
        val mine = issueToken(seedUser())
        put(mine, BLOB_1, 0).then().statusCode(200)
        // A different account that has never written is still born-empty (one account's blob never leaks to another)…
        val theirs = issueToken(seedUser())
        get(theirs).then().statusCode(200).body("blob", nullValue()).body("version", equalTo(0))
        // …and writing theirs does not disturb mine. Without this control, a single shared row would satisfy the
        // assertion above for the wrong reason on the very first read.
        put(theirs, BLOB_2, 0).then().statusCode(200).body("version", equalTo(1))
        get(mine).then().statusCode(200).body("blob", equalTo(BLOB_1))
    }

    @Test
    fun theRegisterNeedsASessionAndRefusesWithoutOne() {
        given().`when`().get(PATH)
            .then().statusCode(401).body("code", equalTo("authentication_failed"))
        given().contentType(ContentType.JSON).body("""{"blob":"$BLOB_1","version":0}""")
            .`when`().put(PATH)
            .then().statusCode(401).body("code", equalTo("authentication_failed"))
    }

    @Test
    fun theRegisterCarriesNothingButTheSealedBlobAndItsVersion() {
        // Structural, and the point of the whole feature: whatever the client puts in the blob, the server's own
        // response surface stays two fields. A device name that ever became a server-readable field would show up
        // here as a third one.
        val token = issueToken(seedUser())
        put(token, BLOB_1, 0).then().statusCode(200)
        get(token).then().statusCode(200)
            .body("$", org.hamcrest.Matchers.aMapWithSize<String, Any>(2))
            .body("$", org.hamcrest.Matchers.hasKey("blob"))
            .body("$", org.hamcrest.Matchers.hasKey("version"))
    }

    // --- helpers ---

    private fun issueToken(userId: String): String = sessions.issue(userId).accessToken

    private fun get(token: String) =
        given().header("Authorization", "Bearer $token").`when`().get(PATH)

    private fun put(token: String, blob: String, version: Long) =
        given().header("Authorization", "Bearer $token")
            .contentType(ContentType.JSON)
            .body("""{"blob":"$blob","version":$version}""")
            .`when`().put(PATH)

    companion object {
        private const val PATH = "/api/account/device-register"
        private const val BLOB_1 = "c2VhbGVkLXJlZ2lzdGVyLW9uZQ=="
        private const val BLOB_2 = "c2VhbGVkLXJlZ2lzdGVyLXR3bw=="
    }
}
