package me.raddatz.veyq.core.admin.tenant

import me.raddatz.veyq.core.account.login.SessionService
import me.raddatz.veyq.core.scope.ScopeEntity
import me.raddatz.veyq.core.adminchannel.givenAdminChannel
import me.raddatz.veyq.core.membership.MembershipEntity
import me.raddatz.veyq.core.tenant.TenantEntity
import me.raddatz.veyq.core.testing.TestFixtures.seedTenant
import me.raddatz.veyq.core.testing.TestFixtures.seedUser
import me.raddatz.veyq.core.testing.RACE_ROUNDS
import me.raddatz.veyq.core.testing.whileUncommitted
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.notNullValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Slice tests for tenant suspension (uc.admin.suspend-tenant, ADR 0066): the two Server-Owner transitions and —
 * the `Host` filter they arm on the tenant's own domain. What refuses the same tenant from every other domain is the
 * scope-access gate, and `OperatorBlindProbeTest` pins that on a Host no tenant claims.
 *
 * Two properties here are permanent regression guards, not incidental coverage, and both are asserted on the
 * **specific** observable (`403` + `tenant_suspended` in the body) rather than on "not 2xx", because a wholesale
 * refusal — a stale owner check, a 404 — satisfies a weaker assertion perfectly while proving nothing:
 *  - the gate runs **before authentication** ([anonymousRequestToASuspendedTenantIs403NotUnauthorized]), which is
 *    what makes an already-issued access token worthless the instant the status flips; and
 *  - suspension **revokes no session** ([suspendingOneTenantLeavesAnotherReachableOnTheSameSession]) — sessions
 *    are account-global, so a revoke would collaterally sign a multi-tenant member out of tenants that were
 *    never suspended.
 * Each negative carries its positive control on the same predicate (the same request, same token, before the
 * suspension or against the unaffected tenant), so a green here cannot come from the request failing for an
 * unrelated reason.
 */
@QuarkusTest
class TenantSuspendSliceTest {

    @Inject
    lateinit var sessions: SessionService

    @Inject
    lateinit var store: TenantStore

    // --- the transitions ---

    @Test
    fun suspendFlipsToSuspendedAndReactivateFlipsBack() {
        val (tenantId, _) = seedActiveTenant()
        suspend(ownerToken(), tenantId).then().statusCode(204)
        assertEquals(TenantEntity.SUSPENDED, statusOf(tenantId))
        reactivate(ownerToken(), tenantId).then().statusCode(204)
        assertEquals(TenantEntity.ACTIVE, statusOf(tenantId))
    }

    @Test
    fun suspendingANonActiveTenantIs409() {
        // A1: suspension is defined only from ACTIVE — a PENDING tenant is withdrawn with revokeTenant instead.
        val tenantId = givenAdminChannel().header("Authorization", "Bearer ${ownerToken()}")
            .contentType("application/json")
            .body("""{"tenantName":"Fam","domain":"pending-${UUID.randomUUID()}.example.com","ownerEmail":"o@example.com"}""")
            .`when`().post("/api/tenants").then().statusCode(201).extract().path<String>("tenantId")
        suspend(ownerToken(), tenantId).then().statusCode(409).body("code", equalTo("tenant_not_active"))
        assertEquals(TenantEntity.PENDING, statusOf(tenantId))
    }

    @Test
    fun reactivatingANonSuspendedTenantIs409() {
        // A2, symmetrically: an ACTIVE tenant has nothing to reactivate.
        val (tenantId, _) = seedActiveTenant()
        reactivate(ownerToken(), tenantId).then().statusCode(409).body("code", equalTo("tenant_not_suspended"))
        assertEquals(TenantEntity.ACTIVE, statusOf(tenantId))
    }

    @Test
    fun bothTransitionsAreServerOwnerOnly() {
        val (tenantId, _) = seedActiveTenant()
        suspend(memberToken(), tenantId).then().statusCode(403).body("code", equalTo("not_server_owner"))
        reactivate(memberToken(), tenantId).then().statusCode(403).body("code", equalTo("not_server_owner"))
        assertEquals(TenantEntity.ACTIVE, statusOf(tenantId))
    }

    @Test
    fun bothTransitionsOnAnUnknownTenantAre404() {
        val unknown = UUID.randomUUID().toString()
        suspend(ownerToken(), unknown).then().statusCode(404).body("code", equalTo("tenant_not_found"))
        reactivate(ownerToken(), unknown).then().statusCode(404).body("code", equalTo("tenant_not_found"))
    }

    // --- the gate ---

    @Test
    fun anonymousRequestToASuspendedTenantIs403NotUnauthorized() {
        // The ordering proof. /api/scopes is @Authenticated, so anonymously it is a 401 — that is the control,
        // asserted first so a later 403 cannot be the endpoint refusing for its own reasons. Once the tenant is
        // SUSPENDED the *same* anonymous request must answer 403 tenant_suspended: only a gate running ahead of
        // authentication can turn a would-be 401 into that. Behind authentication it would still read 401.
        val (tenantId, domain) = seedActiveTenant()
        given().header("X-Forwarded-Host", domain).`when`().get("/api/scopes")
            .then().statusCode(401).body("code", equalTo("authentication_failed"))

        suspend(ownerToken(), tenantId).then().statusCode(204)

        given().header("X-Forwarded-Host", domain).`when`().get("/api/scopes")
            .then().statusCode(403).body("code", equalTo("tenant_suspended"))
    }

    @Test
    fun aStillValidAccessTokenIsRefusedAgainstASuspendedTenant() {
        // The "blocks immediately, no exceptions" property: one token, issued before the suspension and never
        // revoked, reaching the tenant fine and then refused — with nothing about the session having changed.
        val (tenantId, domain) = seedActiveTenant()
        val member = seedTenantMember(tenantId)
        val token = sessions.issue(member).accessToken

        given().header("Authorization", "Bearer $token").header("X-Forwarded-Host", domain)
            .`when`().get("/api/scopes").then().statusCode(200)
            .body("find { it.id == '$tenantId' }", notNullValue())

        suspend(ownerToken(), tenantId).then().statusCode(204)

        given().header("Authorization", "Bearer $token").header("X-Forwarded-Host", domain)
            .`when`().get("/api/scopes").then().statusCode(403).body("code", equalTo("tenant_suspended"))

        // Reversible, and the session was never touched: the very same token resumes once reactivated.
        reactivate(ownerToken(), tenantId).then().statusCode(204)
        given().header("Authorization", "Bearer $token").header("X-Forwarded-Host", domain)
            .`when`().get("/api/scopes").then().statusCode(200)
            .body("find { it.id == '$tenantId' }", notNullValue())
    }

    @Test
    fun suspendingOneTenantLeavesAnotherReachableOnTheSameSession() {
        // The property that ruled out a session-revoke (ADR 0066). One account, two tenants, one session; suspend
        // the first and the second must stay fully reachable on that same token. A revoke "for extra safety" —
        // sessions being account-global — reds this the moment it is added, which is the whole point of keeping it.
        val (suspendedId, suspendedDomain) = seedActiveTenant()
        val (otherId, otherDomain) = seedActiveTenant()
        val member = seedTenantMember(suspendedId)
        joinTenant(member, otherId)
        val token = sessions.issue(member).accessToken

        suspend(ownerToken(), suspendedId).then().statusCode(204)

        // Refused on the suspended tenant's domain…
        given().header("Authorization", "Bearer $token").header("X-Forwarded-Host", suspendedDomain)
            .`when`().get("/api/scopes").then().statusCode(403).body("code", equalTo("tenant_suspended"))
        // …and untouched on the other one's, on the same session — reaching its data, not merely not-blocked.
        given().header("Authorization", "Bearer $token").header("X-Forwarded-Host", otherDomain)
            .`when`().get("/api/scopes").then().statusCode(200)
            .body("find { it.id == '$otherId' }", notNullValue())
    }

    @Test
    fun aHostClaimingNoTenantPassesThroughUnchanged() {
        // The gate only ever narrows: an unknown host resolves to no tenant, so nothing changes for it — before
        // or after some *other* tenant is suspended. (Without this, a gate that defaulted to refusing on an
        // unresolvable host would lock out the bootstrap domain and every self-hosted direct-port deployment.)
        val (tenantId, _) = seedActiveTenant()
        suspend(ownerToken(), tenantId).then().statusCode(204)
        given().header("X-Forwarded-Host", "nobody-${UUID.randomUUID()}.example.com")
            .`when`().get("/api/scopes").then().statusCode(401).body("code", equalTo("authentication_failed"))
    }

    @Test
    fun neitherTransitionTouchesMembershipsScopesOrGrants() {
        // Availability, never confidentiality: `status` is the only column either transition writes. Pinned on the
        // rows that would have to move if suspension ever reached for a key — the tenant's scopes and every
        // membership grant under it, byte for byte across a full suspend/reactivate round trip.
        val (tenantId, _) = seedActiveTenant()
        seedTenantMember(tenantId)
        val before = membershipFingerprint(tenantId)

        suspend(ownerToken(), tenantId).then().statusCode(204)
        assertEquals(before, membershipFingerprint(tenantId))
        reactivate(ownerToken(), tenantId).then().statusCode(204)
        assertEquals(before, membershipFingerprint(tenantId))
    }

    // --- a managed write of the tenant row cannot lift a suspension (inbox #224) ---

    @Test
    fun aTermsEditOverlappingASuspensionLeavesTheTenantSuspended() {
        // The tenant owner's terms edit reads the row ACTIVE before the Server Owner's suspension commits and flushes
        // after it. A full-row UPDATE wrote the `status` it had read back, and the suspension was undone.
        repeat(RACE_ROUNDS) { round ->
            val (tenantId, _) = seedActiveTenant()
            val revision = whileUncommitted(
                change = { assertTrue(store.suspend(tenantId)) },
                overlapping = { store.updateTerms(tenantId, "{\"en\":\"terms $round\"}") },
            )
            assertEquals(TenantEntity.SUSPENDED, statusOf(tenantId), "round $round: the terms edit lifted the suspension")
            // Control: the edit itself landed.
            assertEquals(1, revision, "round $round")
            assertEquals(1, termsRevisionOf(tenantId), "round $round")
        }
    }

    @Test
    fun aQuotaChangeOverlappingASuspensionLeavesTheTenantSuspended() {
        repeat(RACE_ROUNDS) { round ->
            val (tenantId, _) = seedActiveTenant()
            whileUncommitted(
                change = { assertTrue(store.suspend(tenantId)) },
                overlapping = { assertTrue(store.setStorageQuota(tenantId, 1_000_000L)) },
            )
            assertEquals(TenantEntity.SUSPENDED, statusOf(tenantId), "round $round: the quota change lifted the suspension")
            // Control: the quota itself landed.
            assertEquals(1_000_000L, QuarkusTransaction.requiringNew().call { store.findTenant(tenantId)!!.storageQuotaBytes }, "round $round")
        }
    }

    // --- helpers ---

    private fun termsRevisionOf(tenantId: String): Int =
        QuarkusTransaction.requiringNew().call { store.findTenant(tenantId)!!.termsRevision }

    private fun ownerToken() = sessions.issue(seedUser(serverRole = "SERVER_OWNER")).accessToken
    private fun memberToken() = sessions.issue(seedUser()).accessToken

    private fun suspend(token: String, tenantId: String) =
        givenAdminChannel().header("Authorization", "Bearer $token").`when`().post("/api/tenants/$tenantId/suspend")

    private fun reactivate(token: String, tenantId: String) =
        givenAdminChannel().header("Authorization", "Bearer $token").`when`().post("/api/tenants/$tenantId/reactivate")

    private fun statusOf(tenantId: String): String =
        QuarkusTransaction.requiringNew().call { store.findTenant(tenantId)!!.status }

    /** Every scope + membership row under the tenant, grants included — the things suspension must not move. */
    private fun membershipFingerprint(tenantId: String): List<String> =
        QuarkusTransaction.requiringNew().call {
            val scopes = ScopeEntity.find("tenantId", tenantId).list().map { "scope:${it.id}:${it.kind}" }
            val members = MembershipEntity.find("tenantId", tenantId).list()
                .map { "member:${it.userId}:${it.scopeId}:${it.role}:${it.wrappedGrant}" }
            (scopes + members).sorted()
        }

    /** A user + an ACTIVE tenant on a unique domain + its TENANT root scope (the post-activation state). */
    private fun seedActiveTenant(): Pair<String, String> {
        val domain = "s-${UUID.randomUUID()}.example.com"
        return seedTenant(domain = domain) to domain
    }

    /** A fresh account holding a MEMBER membership on the tenant's root scope. */
    private fun seedTenantMember(tenantId: String): String {
        val userId = seedUser()
        joinTenant(userId, tenantId)
        return userId
    }

    private fun joinTenant(userId: String, tenantId: String) {
        QuarkusTransaction.requiringNew().run {
            MembershipEntity().apply {
                id = UUID.randomUUID().toString()
                this.userId = userId
                this.tenantId = tenantId
                scopeId = tenantId
                role = MembershipEntity.MEMBER
                wrappedGrant = "bWVtYmVyLWdyYW50" // "member-grant" — opaque ciphertext, never read here
                createdAt = Instant.now()
            }.persist()
        }
    }
}
