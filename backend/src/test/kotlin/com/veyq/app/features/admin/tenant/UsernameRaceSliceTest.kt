package com.veyq.app.features.admin.tenant

import com.veyq.app.shared.crypto.Tokens
import com.veyq.app.shared.identity.TenantProfileStore
import com.veyq.app.shared.membership.MembershipEntity
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
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Two people claiming one handle in one tenant at once (developer-styleguide §12): `ux_tp_tenant_username` lets one
 * through, and the other answers `username_taken` — what a replay of its request gets — never a 500. Member accepts
 * of one tenant are already ordered by the tenant scope's lock; a profile edit takes no such lock, so the races are
 * edit against edit and edit against accept. Each test ends with a fresh claim that succeeds.
 */
@QuarkusTest
class UsernameRaceSliceTest {

    @Inject lateinit var profiles: TenantProfileStore

    @Test
    fun concurrentRenamesToOneHandleGiveItToOne() {
        val (tenantId, domain) = seedActiveTenant()
        val (a, b) = member(tenantId) to member(tenantId)
        repeat(RACE_ROUNDS) { round ->
            val handle = handle()
            val answers = race({ rename(a, domain, handle) }, { rename(b, domain, handle) })
            assertWinnerAndLoser(200, 409 to "username_taken", answers, "round $round")
        }
        assertEquals(200 to null, rename(a, domain, handle()))
    }

    /** The accept's loser rolls back whole (its invite stays live), so it answers as a replay: `username_taken`. */
    @Test
    fun aRenameRacingAMemberJoinUnderOneHandleGivesItToOne() {
        val (tenantId, domain) = seedActiveTenant()
        val renamer = member(tenantId)
        repeat(RACE_ROUNDS) { round ->
            val handle = handle()
            val secret = "race-join-$round-${UUID.randomUUID()}"
            seedMemberInvite(tenantId, "$secret@example.com", secret)
            val answers = race({ rename(renamer, domain, handle) }, { register(secret, handle) })
            assertEquals(1, answers.count { it == 409 to "username_taken" }, "round $round: one loser, got $answers")
            assertEquals(1, answers.count { it == 200 to null || it == 201 to null }, "round $round: one winner, got $answers")
        }
        val secret = "race-join-control-${UUID.randomUUID()}"
        seedMemberInvite(tenantId, "$secret@example.com", secret)
        assertEquals(201, register(secret, handle()).first)
    }

    private fun handle() = "h${UUID.randomUUID().toString().replace("-", "").take(12)}"

    private fun seedActiveTenant(): Pair<String, String> {
        val tag = UUID.randomUUID().toString()
        val domain = "un-$tag.example.com"
        val ownerEmail = "owner@$domain"
        return seedTenant(domain = domain, ownerEmail = ownerEmail, owner = seedUser(email = ownerEmail)) to domain
    }

    /** A granted member of [tenantId] with a profile under a handle of its own. */
    private fun member(tenantId: String): String {
        val userId = seedUser()
        QuarkusTransaction.requiringNew().run {
            MembershipEntity().apply {
                id = UUID.randomUUID().toString(); this.userId = userId; this.tenantId = tenantId
                scopeId = tenantId; role = MembershipEntity.MEMBER; wrappedGrant = "bWVtYmVyLWdyYW50"; createdAt = Instant.now()
            }.persist()
            profiles.create(userId, tenantId, handle())
        }
        return userId
    }

    private fun seedMemberInvite(tenantId: String, email: String, secret: String) =
        QuarkusTransaction.requiringNew().run {
            TenantInviteEntity().apply {
                id = UUID.randomUUID().toString(); this.tenantId = tenantId; ownerEmail = email
                tokenHash = Tokens.hash(secret); status = TenantInviteEntity.ACTIVE; role = TenantInviteEntity.MEMBER
                expiresAt = Instant.now().plus(7, ChronoUnit.DAYS); createdAt = Instant.now()
            }.persist()
        }

    private fun rename(userId: String, domain: String, username: String): Pair<Int, String?> =
        given().header("Authorization", bearer(userId)).header("X-Forwarded-Host", domain)
            .contentType(ContentType.JSON).body("""{"username":"$username"}""")
            .`when`().put("/api/account/profile").then().extract().answer()

    private fun register(secret: String, username: String): Pair<Int, String?> =
        given().contentType(ContentType.JSON).body(
            """
            {"bootstrapSecret":"$secret","userId":"${UUID.randomUUID()}","username":"$username",
             "account":{"suiteId":"v1",
               "identityPublicKey":"bWVtYmVyLXB1YmxpYy1rZXk=",
               "wrappedIdentityPrivateKey":"d3JhcHBlZC1pZGVudGl0eS1wcml2YXRlLWtleQ==",
               "authPublicKey":"ZWQyNTUxOS1hdXRoLXB1Yg==",
               "masterKeyWraps":[{"method":"PASSWORD","wrappedKey":"d3JhcHBlZC1tYXN0ZXIta2V5","kdfParams":"a2RmLXBhcmFtcw=="}],
               "secretKeyProofPublicKey":"c2VjcmV0LWtleS1wcm9vZi1wdWJsaWMta2V5"}}
            """.trimIndent(),
        ).`when`().post("/api/tenants/accept/register").then().extract().answer()
}
