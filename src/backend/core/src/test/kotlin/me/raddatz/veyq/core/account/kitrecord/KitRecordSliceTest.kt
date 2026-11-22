package me.raddatz.veyq.core.account.kitrecord

import me.raddatz.veyq.core.account.login.SessionService
import me.raddatz.veyq.core.testing.TestFixtures.seedUser
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.nullValue
import org.hamcrest.Matchers.aMapWithSize
import org.hamcrest.Matchers.hasKey
import org.junit.jupiter.api.Test

/**
 * Slice tests for the account's kit record (ADR 0157 decision 3, uc.account.renew-emergency-kit): the server stores and
 * serves one opaque sealed blob per account, keyed off the caller's own token. An account with no record reads `{}` (the
 * blob absent — the client reads layout 1); a `PUT` stores, and the next one replaces it with no revision (last writer
 * wins); one account's record is never another's; and the record is the blob alone — a layout number the server could
 * read would show up as a second field.
 *
 * Each test seeds a UUID-unique account so the JVM-lived H2 mem DB (shared across test classes) can't bleed a written
 * row between tests.
 */
@QuarkusTest
class KitRecordSliceTest {

    @Inject
    lateinit var sessions: SessionService

    @Test
    fun anAccountWithNoRecordReadsAnAbsentBlob() {
        // `{}` on the wire: the server includes no null, as the device register's `blob` (ADR 0157 decision 1).
        get(issueToken(seedUser()))
            .then().statusCode(200)
            .body("blob", nullValue())
            .body("$", aMapWithSize<String, Any>(0))
    }

    @Test
    fun aWriteIsReadBackAndTheNextReplacesItWithoutARevision() {
        val token = issueToken(seedUser())
        put(token, BLOB_1).then().statusCode(204)
        get(token).then().statusCode(200).body("blob", equalTo(BLOB_1))
        // Last writer wins: no revision to state, none to go stale.
        put(token, BLOB_2).then().statusCode(204)
        get(token).then().statusCode(200)
            .body("blob", equalTo(BLOB_2))
            .body("$", aMapWithSize<String, Any>(1))
            .body("$", hasKey("blob"))
    }

    @Test
    fun aMemberReadsAndWritesOnlyTheirOwnRecord() {
        val mine = issueToken(seedUser())
        put(mine, BLOB_1).then().statusCode(204)
        // Another account that never wrote still has none: one account's blob never reaches another…
        val theirs = issueToken(seedUser())
        get(theirs).then().statusCode(200).body("blob", nullValue())
        // …and their write lands on their own row, leaving mine. Without this control one shared row would satisfy the
        // read above for the wrong reason on its very first read.
        put(theirs, BLOB_2).then().statusCode(204)
        get(theirs).then().statusCode(200).body("blob", equalTo(BLOB_2))
        get(mine).then().statusCode(200).body("blob", equalTo(BLOB_1))
    }

    @Test
    fun theRecordNeedsASession() {
        given().`when`().get(PATH)
            .then().statusCode(401).body("code", equalTo("authentication_failed"))
        given().contentType(ContentType.JSON).body("""{"blob":"$BLOB_1"}""")
            .`when`().put(PATH)
            .then().statusCode(401).body("code", equalTo("authentication_failed"))
    }

    @Test
    fun aMalformedWriteIs400AndStoresNothing() {
        val token = issueToken(seedUser())
        for (body in listOf("""{}""", """{"blob":null}""", """{"blob":""}""", """{"blob":"not base64!!"}""")) {
            given().header("Authorization", "Bearer $token").contentType(ContentType.JSON).body(body)
                .`when`().put(PATH)
                .then().statusCode(400).body("code", equalTo("invalid_request"))
        }
        // Over the contract's cap (65532 base64 characters, inside MariaDB's TEXT).
        put(token, "A".repeat(65536)).then().statusCode(400).body("code", equalTo("invalid_request"))
        get(token).then().statusCode(200).body("blob", nullValue())
    }

    // --- helpers ---

    private fun issueToken(userId: String): String = sessions.issue(userId).accessToken

    private fun get(token: String) =
        given().header("Authorization", "Bearer $token").`when`().get(PATH)

    private fun put(token: String, blob: String) =
        given().header("Authorization", "Bearer $token")
            .contentType(ContentType.JSON)
            .body("""{"blob":"$blob"}""")
            .`when`().put(PATH)

    companion object {
        private const val PATH = "/api/account/kit-record"
        private const val BLOB_1 = "c2VhbGVkLWtpdC1yZWNvcmQtb25l"
        private const val BLOB_2 = "c2VhbGVkLWtpdC1yZWNvcmQtdHdv"
    }
}
