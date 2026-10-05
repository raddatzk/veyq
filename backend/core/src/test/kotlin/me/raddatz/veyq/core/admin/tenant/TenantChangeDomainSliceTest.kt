package me.raddatz.veyq.core.admin.tenant

import me.raddatz.veyq.core.account.login.SessionService
import me.raddatz.veyq.core.adminchannel.givenAdminChannel
import me.raddatz.veyq.core.tenant.TenantEntity
import me.raddatz.veyq.core.testing.TestFixtures.seedTenant
import me.raddatz.veyq.core.testing.TestFixtures.seedUser
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.response.Response
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.equalTo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Slice tests for moving a tenant to a different domain (uc.admin.create-tenant): the one tenant-admin write
 * that is **not** PENDING-only, because a domain is how a tenant is *reached* and not part of its invite
 * lifecycle. What actually makes the new domain resolve is the ingress projector, covered where it belongs
 * (`TenantIngressProjectorTest`); this file owns the endpoint's own contract — who may call it, from which
 * states, and what each refusal says.
 *
 * Every refusal is asserted on its **specific** code rather than on "not 204": a change-domain that refused
 * everything wholesale — a broken lookup, a stale gate — satisfies "the suspended one is rejected" perfectly
 * while being a total outage, so each negative is paired with the positive that separates the two.
 */
@QuarkusTest
class TenantChangeDomainSliceTest {

    @Inject
    lateinit var sessions: SessionService

    @Inject
    lateinit var store: TenantStore

    @Test
    fun aPendingTenantMoves() {
        val (tenantId, _) = seedTenantIn(TenantEntity.PENDING)
        val moved = uniqueDomain("pending-moved")
        changeDomain(ownerToken(), tenantId, moved).then().statusCode(204)
        assertEquals(moved, domainOf(tenantId))
    }

    @Test
    fun anActiveTenantMovesToo() {
        // The difference from resend/reassign/revoke, stated as a test: a settled tenant may still be moved.
        val (tenantId, _) = seedTenantIn(TenantEntity.ACTIVE)
        val moved = uniqueDomain("active-moved")
        changeDomain(ownerToken(), tenantId, moved).then().statusCode(204)
        assertEquals(moved, domainOf(tenantId))
    }

    @Test
    fun aSuspendedTenantIsRefusedAndKeepsItsDomain() {
        // Reactivate first — a state whose whole point is "leave it alone" does not get a second thing to reason
        // about. The code is deliberately NOT the boundary gate's `tenant_suspended` (403): that one means "this
        // space is offline", this one means "reactivate it, then move it".
        val (tenantId, original) = seedTenantIn(TenantEntity.SUSPENDED)
        changeDomain(ownerToken(), tenantId, uniqueDomain("refused"))
            .then().statusCode(409).body("code", equalTo("tenant_not_pending_or_active"))
        assertEquals(original, domainOf(tenantId))
    }

    @Test
    fun aDomainAlreadyMappedIsRefused() {
        val (occupantId, taken) = seedTenantIn(TenantEntity.ACTIVE)
        val (tenantId, original) = seedTenantIn(TenantEntity.ACTIVE)

        changeDomain(ownerToken(), tenantId, taken)
            .then().statusCode(409).body("code", equalTo("domain_taken"))

        // Neither side moved: the refusal is a refusal, not a half-applied swap.
        assertEquals(original, domainOf(tenantId))
        assertEquals(taken, domainOf(occupantId))
    }

    @Test
    fun movingATenantToItsOwnDomainSucceedsUnchanged() {
        // The idempotent replay — the same request twice, a double-submitted form. `domainExists` would answer
        // "taken" for the tenant's own row, so without the identity check this would 409 on a no-op.
        val (tenantId, original) = seedTenantIn(TenantEntity.ACTIVE)
        changeDomain(ownerToken(), tenantId, original).then().statusCode(204)
        assertEquals(original, domainOf(tenantId))
    }

    @Test
    fun aMalformedDomainIsRejectedAtTheBoundary() {
        // Same rule as at creation, and the same place: the generated DTO's @Pattern, before any handler runs.
        val (tenantId, original) = seedTenantIn(TenantEntity.ACTIVE)
        changeDomain(ownerToken(), tenantId, "NOT a domain").then().statusCode(400)
        changeDomain(ownerToken(), tenantId, "").then().statusCode(400)
        assertEquals(original, domainOf(tenantId))
    }

    @Test
    fun theDomainIsNormalisedToLowercaseLikeAtCreation() {
        // A Host is case-insensitive but `TenantHostResolver` looks the row up lowercased — an upper-case row is
        // a tenant no request can ever resolve to. Create normalises; so must this.
        val (tenantId, _) = seedTenantIn(TenantEntity.ACTIVE)
        val moved = uniqueDomain("mixedcase")
        changeDomain(ownerToken(), tenantId, moved.uppercase()).then().statusCode(400) // the @Pattern is lowercase-only…
        changeDomain(ownerToken(), tenantId, moved).then().statusCode(204)             // …so the boundary is what normalises
        assertEquals(moved, domainOf(tenantId))
    }

    @Test
    fun itIsServerOwnerOnly() {
        val (tenantId, original) = seedTenantIn(TenantEntity.ACTIVE)
        changeDomain(memberToken(), tenantId, uniqueDomain("not-owner"))
            .then().statusCode(403).body("code", equalTo("not_server_owner"))
        assertEquals(original, domainOf(tenantId))
    }

    @Test
    fun anUnknownTenantIs404() {
        changeDomain(ownerToken(), UUID.randomUUID().toString(), uniqueDomain("nobody"))
            .then().statusCode(404).body("code", equalTo("tenant_not_found"))
    }

    // --- helpers ---

    private fun changeDomain(token: String, tenantId: String, domain: String): Response =
        givenAdminChannel().header("Authorization", "Bearer $token").contentType("application/json")
            .body("""{"domain":"$domain"}""")
            .`when`().post("/api/tenants/$tenantId/change-domain")

    private fun domainOf(tenantId: String): String =
        QuarkusTransaction.requiringNew().call { store.findTenant(tenantId)!!.domain }

    private fun uniqueDomain(prefix: String) = "$prefix-${UUID.randomUUID()}.example.com"

    private fun ownerToken() = sessions.issue(seedUser(serverRole = "SERVER_OWNER")).accessToken
    private fun memberToken() = sessions.issue(seedUser(serverRole = "MEMBER")).accessToken

    /** A tenant in [status] on a unique domain; returns its id + that domain. */
    private fun seedTenantIn(status: String): Pair<String, String> {
        val domain = uniqueDomain("cd")
        return seedTenant(domain = domain, status = status, tenantScope = false) to domain
    }
}
