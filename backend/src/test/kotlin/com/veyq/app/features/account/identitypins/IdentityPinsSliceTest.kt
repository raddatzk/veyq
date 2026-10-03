package com.veyq.app.features.account.identitypins

import com.veyq.app.features.account.login.SessionService
import com.veyq.app.testing.TestFixtures.seedUser
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.nullValue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Slice tests for the operator-blind identity-pin store (en.account.identity-pinning, cr-0098, ADR 0028). The
 * server stores + serves one opaque sealed blob + a version: a born-empty account reads `{ blob: null, version: 0 }`;
 * a version-guarded PUT round-trips and bumps the version; a stale version is a `409 stale_version`; and the store
 * is keyed off the caller's own token (no cross-account read). Each test seeds a UUID-unique account so the
 * JVM-lived H2 mem DB (shared across test classes) can't bleed a written pin row between tests.
 */
@QuarkusTest
class IdentityPinsSliceTest {

    @Inject
    lateinit var sessions: SessionService

    @Test
    fun bornEmptyStoreReadsNullBlobVersionZero() {
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
        // A born-empty store only accepts a first write against version 0 — a non-zero expected version is stale.
        put(issueToken(seedUser()), BLOB_1, 5)
            .then().statusCode(409).body("code", equalTo("stale_version"))
    }

    @Test
    fun staleVersionAfterAWriteIsConflict() {
        val token = issueToken(seedUser())
        put(token, BLOB_1, 0).then().statusCode(200).body("version", equalTo(1))
        // Another device already moved the store to version 1; a second write still based on version 0 is stale.
        put(token, BLOB_2, 0)
            .then().statusCode(409).body("code", equalTo("stale_version"))
        // The store is untouched by the rejected write.
        get(token).then().statusCode(200).body("blob", equalTo(BLOB_1)).body("version", equalTo(1))
    }

    @Test
    fun storeIsPerAccountNotShared() {
        put(issueToken(seedUser()), BLOB_1, 0).then().statusCode(200)
        // A different account that has never written is still born-empty (one account's blob never leaks to another).
        get(issueToken(seedUser()))
            .then().statusCode(200)
            .body("blob", nullValue())
            .body("version", equalTo(0))
    }

    @Test
    fun aWriteWithoutItsVersionIs400AndStoresNothing() {
        // A missing `version` must not bind to 0 — on a born-empty store 0 is exactly the version that wins, so an
        // old client would write unchecked.
        val token = issueToken(seedUser())
        given().header("Authorization", "Bearer $token").contentType(ContentType.JSON)
            .body("""{"blob":"$BLOB_1"}""")
            .`when`().put(PATH)
            .then().statusCode(400)
        get(token).then().statusCode(200).body("blob", nullValue()).body("version", equalTo(0))
    }

    @Test
    fun missingTokenIsGeneric401() {
        given().`when`().get(PATH)
            .then().statusCode(401).body("code", equalTo("authentication_failed"))
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
        private const val PATH = "/api/account/identity-pins"
        private const val BLOB_1 = "c2VhbGVkLXBpbnMtb25l"
        private const val BLOB_2 = "c2VhbGVkLXBpbnMtdHdv"
    }
}
