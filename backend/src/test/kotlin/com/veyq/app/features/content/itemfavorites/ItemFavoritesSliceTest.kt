package com.veyq.app.features.content.itemfavorites

import com.veyq.app.features.account.login.SessionService
import com.veyq.app.features.admin.tenant.TenantEntity
import com.veyq.app.features.spaces.scope.ScopeEntity
import com.veyq.app.features.spaces.scope.ScopesStore
import com.veyq.app.shared.identity.VeyqUser
import com.veyq.app.shared.membership.MembershipEntity
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Slice tests for per-(member, scope, node) item favorites (en.memory.favorites, ADR 0068).
 *
 * The property that separates this resource from the pin set next door is that the read is **deliberately not
 * caller-filtered**: the gallery's "everyone's / only mine" filter and its per-item count are computed on
 * device, so every member's sealed blob has to cross. [getReturnsEveryMembersBlobNotJustTheCallers] is that
 * assertion, and filtering the read to the caller is the one-line change that reddens it.
 *
 * The complementary half — a caller can only replace their **own** blob — is a *shape*, not a check: the PUT
 * body carries no member id at all, so there is nothing to address another member with. It is asserted from
 * the outside in [aPutTouchesOnlyTheCallersBlob].
 *
 * Seeds use UUID-unique domains/emails so the JVM-lived H2 mem DB (shared across test classes) can't bleed.
 */
@QuarkusTest
class ItemFavoritesSliceTest {

    @Inject
    lateinit var sessions: SessionService

    @Inject
    lateinit var scopes: ScopesStore

    private val mine = "bWluZS1jaXBoZXJ0ZXh0" // "mine-ciphertext"
    private val theirs = "dGhlaXJzLWNpcGhlcg==" // "theirs-cipher"

    private fun token(userId: String) = "Bearer ${sessions.issue(userId).accessToken}"

    private fun put(userId: String, scopeId: String, nodeId: String, ciphertext: String) =
        given().header("Authorization", token(userId)).contentType(ContentType.JSON)
            .body("""{"ciphertext":"$ciphertext"}""")
            .`when`().put("/api/scopes/$scopeId/nodes/$nodeId/item-favorites")
            .then().statusCode(204)

    @Test
    fun getWithoutAnyStoredReturnsAnEmptyArray() {
        val (userId, scopeId) = seedMember()
        given().header("Authorization", token(userId))
            .`when`().get("/api/scopes/$scopeId/nodes/${UUID.randomUUID()}/item-favorites")
            .then().statusCode(200).body("$", hasSize<Any>(0))
    }

    @Test
    fun putThenGetReturnsTheVerbatimCiphertext() {
        val (userId, scopeId) = seedMember()
        val node = UUID.randomUUID().toString()
        put(userId, scopeId, node, mine)

        // operator-blind: the stored blob is exactly the ciphertext the client sent.
        given().header("Authorization", token(userId))
            .`when`().get("/api/scopes/$scopeId/nodes/$node/item-favorites")
            .then().statusCode(200)
            .body("$", hasSize<Any>(1))
            .body("[0].self", equalTo(true))
            .body("[0].ciphertext", equalTo(mine))
    }

    /**
     * **The bite.** A member must see their co-members' marks, or "everyone's favorites" cannot be rendered at
     * all — the blind server cannot aggregate for the client. Filtering this read to the caller turns the
     * feature into a second private pin set and reddens here.
     */
    @Test
    fun getReturnsEveryMembersBlobNotJustTheCallers() {
        val (alice, scopeId) = seedMember()
        val bob = seedAdditionalMember(scopeId)
        val node = UUID.randomUUID().toString()
        put(alice, scopeId, node, mine)
        put(bob, scopeId, node, theirs)

        for ((caller, ownBlob) in listOf(alice to mine, bob to theirs)) {
            val body = given().header("Authorization", token(caller))
                .`when`().get("/api/scopes/$scopeId/nodes/$node/item-favorites")
                .then().statusCode(200).body("$", hasSize<Any>(2))
                .extract().jsonPath()
            val entries = (0..1).map { body.getBoolean("[$it].self") to body.getString("[$it].ciphertext") }
            assert(entries.map { it.second }.toSet() == setOf(mine, theirs)) {
                "caller $caller must receive BOTH members' blobs verbatim, got $entries"
            }
            assert(entries.filter { it.first }.map { it.second } == listOf(ownBlob)) {
                "exactly one entry is flagged self, and it is the caller's own — got $entries"
            }
            // No member id anywhere on the wire: the product never shows who marked what, so it is never sent.
            assert(body.getString("[0].memberId") == null && body.getString("[1].memberId") == null) {
                "entries must carry no member id at all"
            }
        }
    }

    /** A write addresses the caller's row and nothing else — the body has no member id to aim elsewhere with. */
    @Test
    fun aPutTouchesOnlyTheCallersBlob() {
        val (alice, scopeId) = seedMember()
        val bob = seedAdditionalMember(scopeId)
        val node = UUID.randomUUID().toString()
        put(alice, scopeId, node, mine)
        put(bob, scopeId, node, theirs)

        val rewritten = "cmV3cml0dGVu" // "rewritten"
        put(alice, scopeId, node, rewritten)

        val body = given().header("Authorization", token(bob))
            .`when`().get("/api/scopes/$scopeId/nodes/$node/item-favorites")
            .then().statusCode(200).body("$", hasSize<Any>(2)).extract().jsonPath()
        val entries = (0..1).map { body.getBoolean("[$it].self") to body.getString("[$it].ciphertext") }
        assert(entries.single { it.first }.second == theirs) {
            "bob's own blob must be untouched by alice's PUT — got $entries"
        }
        assert(entries.single { !it.first }.second == rewritten) { "alice's own blob is the one that moved" }
    }

    /**
     * The partition is per **node**, which is the whole reason the 65 536-char cap stays out of reach and a
     * toggle does not re-upload the scope. Marks in one Memory must not surface in another.
     */
    @Test
    fun blobsArePerNodeNotPerScope() {
        val (userId, scopeId) = seedMember()
        val one = UUID.randomUUID().toString()
        val two = UUID.randomUUID().toString()
        put(userId, scopeId, one, mine)

        given().header("Authorization", token(userId))
            .`when`().get("/api/scopes/$scopeId/nodes/$two/item-favorites")
            .then().statusCode(200).body("$", hasSize<Any>(0))
        given().header("Authorization", token(userId))
            .`when`().get("/api/scopes/$scopeId/nodes/$one/item-favorites")
            .then().statusCode(200).body("$", hasSize<Any>(1))
    }

    @Test
    fun lastWriteWins() {
        val (userId, scopeId) = seedMember()
        val node = UUID.randomUUID().toString()
        val second = "c2Vjb25kLWNpcGhlcnRleHQ=" // "second-ciphertext"
        put(userId, scopeId, node, mine)
        put(userId, scopeId, node, second)

        given().header("Authorization", token(userId))
            .`when`().get("/api/scopes/$scopeId/nodes/$node/item-favorites")
            .then().statusCode(200).body("$", hasSize<Any>(1)).body("[0].ciphertext", equalTo(second))
    }

    /**
     * Losing the grant takes the marks with it (ADR 0068). Left behind, a removed member keeps voting in an
     * aggregate every remaining member sees, with no way for anyone — including them — to take it back.
     */
    @Test
    fun removingAMemberDropsTheirItemFavorites() {
        val (alice, scopeId) = seedMember()
        val bob = seedAdditionalMember(scopeId)
        val node = UUID.randomUUID().toString()
        put(alice, scopeId, node, mine)
        put(bob, scopeId, node, theirs)

        scopes.removeMembership(bob, scopeId)

        given().header("Authorization", token(alice))
            .`when`().get("/api/scopes/$scopeId/nodes/$node/item-favorites")
            .then().statusCode(200)
            .body("$", hasSize<Any>(1))
            .body("[0].self", equalTo(true))
            .body("[0].ciphertext", equalTo(mine))
    }

    @Test
    fun nonMemberIs404() {
        val (_, scopeId) = seedMember()
        val outsider = seedUser()
        val node = UUID.randomUUID().toString()
        given().header("Authorization", token(outsider))
            .`when`().get("/api/scopes/$scopeId/nodes/$node/item-favorites")
            .then().statusCode(404).body("code", equalTo("scope_not_found"))
        given().header("Authorization", token(outsider)).contentType(ContentType.JSON)
            .body("""{"ciphertext":"$mine"}""")
            .`when`().put("/api/scopes/$scopeId/nodes/$node/item-favorites")
            .then().statusCode(404).body("code", equalTo("scope_not_found"))
    }

    @Test
    fun unauthenticatedIs401() {
        given().`when`().get("/api/scopes/${UUID.randomUUID()}/nodes/${UUID.randomUUID()}/item-favorites")
            .then().statusCode(401).body("code", equalTo("authentication_failed"))
    }

    // --- helpers ---

    /** Seed a user + an ACTIVE tenant + the user's TENANT-scope membership. Returns (userId, scopeId). */
    private fun seedMember(): Pair<String, String> {
        val userId = seedUser()
        val tenantId = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            TenantEntity().apply {
                id = tenantId
                name = "Fam"
                domain = "t-$tenantId.example.com"
                ownerEmail = "owner-$tenantId@example.com"
                status = TenantEntity.ACTIVE
                createdAt = Instant.now()
            }.persist()
            ScopeEntity().apply {
                id = tenantId
                this.tenantId = tenantId
                kind = ScopeEntity.TENANT
                createdAt = Instant.now()
            }.persist()
            membership(userId, tenantId, tenantId)
        }
        return userId to tenantId
    }

    /** A second member on an existing scope — what makes "everyone's" more than a word. */
    private fun seedAdditionalMember(scopeId: String): String {
        val userId = seedUser()
        QuarkusTransaction.requiringNew().run { membership(userId, scopeId, scopeId) }
        return userId
    }

    private fun membership(userId: String, tenantId: String, scopeId: String) {
        MembershipEntity().apply {
            id = UUID.randomUUID().toString()
            this.userId = userId
            this.tenantId = tenantId
            this.scopeId = scopeId
            role = MembershipEntity.OWNER
            wrappedGrant = "dGVuYW50LWdyYW50"
            createdAt = Instant.now()
        }.persist()
    }

    private fun seedUser(): String {
        val userId = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            VeyqUser().apply {
                id = userId
                email = "u-$userId@example.com"
                serverRole = "MEMBER"
                suiteId = "v1"
                identityPublicKey = "aWRlbnRpdHktcHVibGljLWtleQ=="
                wrappedIdentityPrivateKey = "aWRlbnRpdHktcHJpdg=="
                authPublicKey = "YXV0aC1wdWJsaWMta2V5"
                createdAt = Instant.now()
            }.persist()
        }
        return userId
    }
}
