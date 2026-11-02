package me.raddatz.veyq.core.spaces.scope

import me.raddatz.veyq.core.TestIds
import me.raddatz.veyq.core.membership.TestGrants
import me.raddatz.veyq.core.membership.TenantRoots
import me.raddatz.veyq.core.testing.RACE_ROUNDS
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedTenant
import me.raddatz.veyq.core.testing.TestFixtures.seedUser
import me.raddatz.veyq.core.testing.answer
import me.raddatz.veyq.core.testing.assertWinnerAndLoser
import me.raddatz.veyq.core.testing.race
import me.raddatz.veyq.core.testing.whileUncommitted
import me.raddatz.veyq.core.testing.raceRounds
import me.raddatz.veyq.core.membership.MembershipEntity
import me.raddatz.veyq.core.membership.SignedGrant
import me.raddatz.veyq.core.scope.ScopeEntity
import jakarta.inject.Inject
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The scope creates raced against themselves (developer-styleguide §12): the loser answers what a replay of its
 * request gets — the handler's own checks, in its own order — never the 500 of the scope's primary key. Each test
 * ends with a fresh create that succeeds, so a site that refuses everything cannot pass.
 */
@QuarkusTest
class ScopeCreateRaceSliceTest {

    @Inject lateinit var handler: ScopesHandler
    @Inject lateinit var store: ScopesStore

    private val personalGrant = "cGVyc29uYWwtc2NvcGUtZ3JhbnQ="
    private val groupGrant = "Z3JvdXAtc2NvcGUtZ3JhbnQ="
    private val groupName = "c2VhbGVkLWdyb3VwLW5hbWU="

    /** A double-submitted personal create: a replay of it finds the caller's personal scope first. */
    @Test
    fun concurrentPersonalCreatesOfOneBodyCreateOneScope() {
        val domain = domainOf(seedTenant())
        repeat(RACE_ROUNDS) { round ->
            val userId = member(domain)
            val body = personalBody(TestIds.uuid())
            val answers = race({ createPersonal(userId, domain, body) }, { createPersonal(userId, domain, body) })
            assertWinnerAndLoser(201, 409 to "personal_scope_exists", answers, "round $round")
        }
        assertEquals(201 to null, createPersonal(member(domain), domain, personalBody(TestIds.uuid())))
    }

    /**
     * Two creates of one member under two ids: no id collides, so only a key on "one personal scope per member and
     * tenant" can refuse the second. The first create's commit is held back and the second runs inside that window,
     * so its "exists?" read misses the first — the order is forced, not likely. Replayed after the first commit, the
     * second finds the caller's personal scope.
     */
    @Test
    fun overlappingPersonalCreatesUnderTwoIdsCreateOneScope() {
        val domain = domainOf(seedTenant())
        val tenantId = domain.removePrefix("t-").removeSuffix(".example.com")
        raceRounds { round ->
            val userId = member(domain)
            val grant = SignedGrant(personalGrant, TestGrants.SIGNATURE)
            val digest = TestGrants.digestOf(userId)
            val second = whileUncommitted(
                change = { assertEquals(true, store.createPersonal(TestIds.uuid(), userId, tenantId, grant), "round $round") },
                overlapping = { handler.createPersonal(userId, tenantId, TestIds.uuid(), grant, digest) },
            )
            assertEquals(CreatePersonalOutcome.AlreadyExists, second, "round $round")
            assertEquals(1L, personalScopesOf(userId, tenantId), "round $round: one personal scope per member and tenant")
        }
        // Control: another member's personal create in the same tenant lands.
        val other = member(domain)
        assertEquals(201 to null, createPersonal(other, domain, personalBody(TestIds.uuid())))
        assertEquals(1L, personalScopesOf(other, tenantId))
    }

    private fun personalScopesOf(userId: String, tenantId: String): Long = QuarkusTransaction.requiringNew().call {
        val scopeIds = MembershipEntity.find("userId = ?1 and tenantId = ?2", userId, tenantId).list().map { it.scopeId }
        ScopeEntity.count("id in ?1 and kind = ?2", scopeIds, ScopeEntity.PERSONAL)
    }

    /** Two members minting one scope id: the loser has no personal scope, so its replay answers `id_taken`. */
    @Test
    fun concurrentPersonalCreatesOfOneIdByTwoMembersCreateOneScope() {
        val domain = domainOf(seedTenant())
        repeat(RACE_ROUNDS) { round ->
            val (a, b) = member(domain) to member(domain)
            val body = personalBody(TestIds.uuid())
            val answers = race({ createPersonal(a, domain, body) }, { createPersonal(b, domain, body) })
            assertWinnerAndLoser(201, 409 to "id_taken", answers, "round $round")
        }
        assertEquals(201 to null, createPersonal(member(domain), domain, personalBody(TestIds.uuid())))
    }

    @Test
    fun concurrentGroupCreatesOfOneIdCreateOneScope() {
        val domain = domainOf(seedTenant())
        val userId = member(domain)
        repeat(RACE_ROUNDS) { round ->
            val body = groupBody(TestIds.uuid())
            val answers = race({ createGroup(userId, domain, body) }, { createGroup(userId, domain, body) })
            assertWinnerAndLoser(201, 409 to "id_taken", answers, "round $round")
        }
        assertEquals(201 to null, createGroup(userId, domain, groupBody(TestIds.uuid())))
    }

    private fun domainOf(tenantId: String) = "t-$tenantId.example.com"

    /** A fresh accepted member of the tenant behind [domain], with no personal scope yet. */
    private fun member(domain: String): String {
        val userId = seedUser()
        val tenantId = domain.removePrefix("t-").removeSuffix(".example.com")
        QuarkusTransaction.requiringNew().run { TenantRoots.join(userId, tenantId) }
        return userId
    }

    private fun personalBody(scopeId: String) =
        """{"scopeId":"$scopeId","wrappedGrant":"$personalGrant",${TestGrants.SIGNATURE_JSON},"recipientIdentityDigest":"${TestGrants.FIXTURE_DIGEST}"}"""

    private fun groupBody(scopeId: String) =
        """{"scopeId":"$scopeId","encryptedName":"$groupName","wrappedNameValueKey":"d3JhcHBlZC12YWx1ZS1rZXk=","wrappedGrant":"$groupGrant",${TestGrants.SIGNATURE_JSON},"recipientIdentityDigest":"${TestGrants.FIXTURE_DIGEST}"}"""

    private fun createPersonal(userId: String, domain: String, body: String): Pair<Int, String?> =
        given().header("Authorization", bearer(userId)).header("X-Forwarded-Host", domain).contentType(ContentType.JSON)
            .body(body).`when`().post("/api/scopes").then().extract().answer()

    private fun createGroup(userId: String, domain: String, body: String): Pair<Int, String?> =
        given().header("Authorization", bearer(userId)).header("X-Forwarded-Host", domain).contentType(ContentType.JSON)
            .body(body).`when`().post("/api/scopes/groups").then().extract().answer()
}
