package com.veyq.app.features.content.favorites

import com.veyq.app.features.account.login.SessionService
import com.veyq.app.testing.TestFixtures.bearer
import com.veyq.app.testing.TestFixtures.seedTenant
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
 * Slice tests for per-(user, scope) favorites (en.memory.favorites, cr-0031): a member reads/writes their own
 * sealed blob (operator-blind — stored verbatim, last-write-wins); a non-member is 404; unauthenticated is 401.
 * Seeds use UUID-unique domains/emails so the JVM-lived H2 mem DB (shared across test classes) can't bleed.
 */
@QuarkusTest
class FavoritesSliceTest {

    @Inject
    lateinit var sessions: SessionService

    private val sealed = "ZmF2b3JpdGVzLWNpcGhlcnRleHQ=" // "favorites-ciphertext"

    @Test
    fun getWithoutAnyStoredReturnsNullCiphertext() {
        val (userId, scopeId) = seedMember()
        given().header("Authorization", bearer(userId))
            .`when`().get("/api/scopes/$scopeId/favorites")
            .then().statusCode(200).body("ciphertext", nullValue())
    }

    @Test
    fun putThenGetReturnsTheVerbatimCiphertext() {
        val (userId, scopeId) = seedMember()
        given().header("Authorization", bearer(userId))
            .contentType(ContentType.JSON).body("""{"ciphertext":"$sealed"}""")
            .`when`().put("/api/scopes/$scopeId/favorites")
            .then().statusCode(204)

        // operator-blind: the stored blob is exactly the ciphertext the client sent.
        given().header("Authorization", bearer(userId))
            .`when`().get("/api/scopes/$scopeId/favorites")
            .then().statusCode(200).body("ciphertext", equalTo(sealed))
    }

    @Test
    fun lastWriteWins() {
        val (userId, scopeId) = seedMember()
        val token = sessions.issue(userId).accessToken
        val second = "c2Vjb25kLWNpcGhlcnRleHQ=" // "second-ciphertext"
        for (ct in listOf(sealed, second)) {
            given().header("Authorization", "Bearer $token").contentType(ContentType.JSON)
                .body("""{"ciphertext":"$ct"}""").`when`().put("/api/scopes/$scopeId/favorites").then().statusCode(204)
        }
        given().header("Authorization", "Bearer $token")
            .`when`().get("/api/scopes/$scopeId/favorites")
            .then().statusCode(200).body("ciphertext", equalTo(second))
    }

    @Test
    fun nonMemberIs404() {
        val (_, scopeId) = seedMember()
        val outsider = seedUser()
        given().header("Authorization", bearer(outsider))
            .`when`().get("/api/scopes/$scopeId/favorites")
            .then().statusCode(404).body("code", equalTo("scope_not_found"))
        given().header("Authorization", bearer(outsider))
            .contentType(ContentType.JSON).body("""{"ciphertext":"$sealed"}""")
            .`when`().put("/api/scopes/$scopeId/favorites")
            .then().statusCode(404).body("code", equalTo("scope_not_found"))
    }

    @Test
    fun unauthenticatedIs401() {
        given().`when`().get("/api/scopes/${UUID.randomUUID()}/favorites")
            .then().statusCode(401).body("code", equalTo("authentication_failed"))
    }

    // --- helpers ---

    /** Seed a user + an ACTIVE tenant + the user's TENANT-scope membership. Returns (userId, scopeId). */
    private fun seedMember(): Pair<String, String> {
        val userId = seedUser()
        return userId to seedTenant(owner = userId)
    }
}
