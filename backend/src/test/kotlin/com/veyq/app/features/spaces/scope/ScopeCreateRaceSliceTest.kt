package com.veyq.app.features.spaces.scope

import com.veyq.app.shared.TestIds
import com.veyq.app.shared.membership.TestAdmission
import com.veyq.app.shared.membership.TenantRoots
import com.veyq.app.testing.RACE_ROUNDS
import com.veyq.app.testing.TestFixtures.bearer
import com.veyq.app.testing.TestFixtures.seedTenant
import com.veyq.app.testing.TestFixtures.seedUser
import com.veyq.app.testing.answer
import com.veyq.app.testing.assertWinnerAndLoser
import com.veyq.app.testing.race
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
        """{"scopeId":"$scopeId","wrappedGrant":"$personalGrant",${TestAdmission.SIGNATURE_JSON}}"""

    private fun groupBody(scopeId: String) =
        """{"scopeId":"$scopeId","encryptedName":"$groupName","wrappedGrant":"$groupGrant",${TestAdmission.SIGNATURE_JSON},${TestAdmission.json()}}"""

    private fun createPersonal(userId: String, domain: String, body: String): Pair<Int, String?> =
        given().header("Authorization", bearer(userId)).header("X-Forwarded-Host", domain).contentType(ContentType.JSON)
            .body(body).`when`().post("/api/scopes").then().extract().answer()

    private fun createGroup(userId: String, domain: String, body: String): Pair<Int, String?> =
        given().header("Authorization", bearer(userId)).header("X-Forwarded-Host", domain).contentType(ContentType.JSON)
            .body(body).`when`().post("/api/scopes/groups").then().extract().answer()
}
