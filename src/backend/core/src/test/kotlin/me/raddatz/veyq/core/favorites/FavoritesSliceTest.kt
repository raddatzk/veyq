package me.raddatz.veyq.core.favorites

import me.raddatz.veyq.core.account.login.SessionService
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedTenant
import me.raddatz.veyq.core.testing.TestFixtures.seedUser
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import me.raddatz.veyq.core.membership.MembershipEntity
import me.raddatz.veyq.core.scope.ScopeEntity
import java.time.Instant
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.nullValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Slice tests for the per-(user, scope) node pins (en.memory.favorites, ADR 0068 — core's, so they boot with core
 * alone): a member reads/writes their own sealed blob (operator-blind — stored verbatim, last-write-wins, capped);
 * a co-member never reads it; another tenant's member is 404; unauthenticated is 401.
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

    @Test
    fun theCapIsTheContractsAndNotOneCharacterMore() {
        val (userId, scopeId) = seedMember()
        // 65 536 base64 chars is the last blob that fits (ADR 0068 § The ceiling); the column is MEDIUMTEXT.
        val atCap = "A".repeat(65_536)
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON).body("""{"ciphertext":"$atCap"}""")
            .`when`().put("/api/scopes/$scopeId/favorites").then().statusCode(204)
        given().header("Authorization", bearer(userId)).`when`().get("/api/scopes/$scopeId/favorites")
            .then().statusCode(200).body("ciphertext", equalTo(atCap))
        val over = "A".repeat(65_537)
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON).body("""{"ciphertext":"$over"}""")
            .`when`().put("/api/scopes/$scopeId/favorites").then().statusCode(400).body("code", equalTo("invalid_request"))
        // the refused write changed nothing
        given().header("Authorization", bearer(userId)).`when`().get("/api/scopes/$scopeId/favorites")
            .then().statusCode(200).body("ciphertext", equalTo(atCap))
    }

    @Test
    fun aBlankOrNonBase64BodyIs400() {
        val (userId, scopeId) = seedMember()
        for (bad in listOf("", "not base64 !!")) {
            given().header("Authorization", bearer(userId)).contentType(ContentType.JSON).body("""{"ciphertext":"$bad"}""")
                .`when`().put("/api/scopes/$scopeId/favorites").then().statusCode(400)
        }
    }

    @Test
    fun aCoMemberOfTheSameScopeNeverReadsOrOverwritesMyBlob() {
        val (owner, scopeId) = seedMember()
        val other = seedUser()
        QuarkusTransaction.requiringNew().run {
            MembershipEntity().apply {
                id = UUID.randomUUID().toString(); userId = other; tenantId = scopeId; this.scopeId = scopeId
                role = MembershipEntity.MEMBER; wrappedGrant = "Z3JhbnQ="; createdAt = Instant.now()
            }.persist()
        }
        given().header("Authorization", bearer(owner)).contentType(ContentType.JSON).body("""{"ciphertext":"$sealed"}""")
            .`when`().put("/api/scopes/$scopeId/favorites").then().statusCode(204)
        // the co-member is a member (200), yet sees no blob of mine: the row is keyed by the caller
        given().header("Authorization", bearer(other)).`when`().get("/api/scopes/$scopeId/favorites")
            .then().statusCode(200).body("ciphertext", nullValue())
        val theirs = "dGhlaXJz" // "theirs"
        given().header("Authorization", bearer(other)).contentType(ContentType.JSON).body("""{"ciphertext":"$theirs"}""")
            .`when`().put("/api/scopes/$scopeId/favorites").then().statusCode(204)
        given().header("Authorization", bearer(owner)).`when`().get("/api/scopes/$scopeId/favorites")
            .then().statusCode(200).body("ciphertext", equalTo(sealed))
        given().header("Authorization", bearer(other)).`when`().get("/api/scopes/$scopeId/favorites")
            .then().statusCode(200).body("ciphertext", equalTo(theirs))
    }

    @Test
    fun anotherTenantsMemberIs404AndTheirOwnScopeIsUntouched() {
        val (userA, scopeA) = seedMember()
        val (userB, scopeB) = seedMember()
        given().header("Authorization", bearer(userA)).contentType(ContentType.JSON).body("""{"ciphertext":"$sealed"}""")
            .`when`().put("/api/scopes/$scopeA/favorites").then().statusCode(204)
        given().header("Authorization", bearer(userB)).`when`().get("/api/scopes/$scopeA/favorites")
            .then().statusCode(404).body("code", equalTo("scope_not_found"))
        given().header("Authorization", bearer(userB)).contentType(ContentType.JSON).body("""{"ciphertext":"$sealed"}""")
            .`when`().put("/api/scopes/$scopeA/favorites").then().statusCode(404)
        given().header("Authorization", bearer(userB)).`when`().get("/api/scopes/$scopeB/favorites")
            .then().statusCode(200).body("ciphertext", nullValue())
    }

    @Test
    fun erasingTheScopeTakesItsPinRowsWithIt() {
        val (userId, scopeId) = seedMember()
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON).body("""{"ciphertext":"$sealed"}""")
            .`when`().put("/api/scopes/$scopeId/favorites").then().statusCode(204)
        QuarkusTransaction.requiringNew().run { FavoritesScopeEraser().eraseScope(scopeId) }
        assertEquals(0L, QuarkusTransaction.requiringNew().call { FavoritesEntity.count("scopeId", scopeId) })
    }

    // --- helpers ---

    /** Seed a user + an ACTIVE tenant + the user's TENANT-scope membership. Returns (userId, scopeId). */
    @Test
    fun aPinIsStillAcceptedWhileTheSpaceWaitsForItsDeletion() {
        val (userId, scopeId) = seedMember()
        QuarkusTransaction.requiringNew().run {
            ScopeEntity.findById(scopeId)!!.apply { deletedAt = Instant.now() }.persist()
        }
        // A pin is the member's own preference, not a write to the space: the deletion grace does not refuse it.
        given().header("Authorization", bearer(userId))
            .contentType(ContentType.JSON).body("""{"ciphertext":"$sealed"}""")
            .`when`().put("/api/scopes/$scopeId/favorites")
            .then().statusCode(204)
        given().header("Authorization", bearer(userId))
            .`when`().get("/api/scopes/$scopeId/favorites")
            .then().statusCode(200).body("ciphertext", equalTo(sealed))
    }

    private fun seedMember(): Pair<String, String> {
        val userId = seedUser()
        return userId to seedTenant(owner = userId)
    }
}
