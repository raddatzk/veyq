package me.raddatz.veyq.core.admin.tenant

import me.raddatz.veyq.core.account.erasure.AccountPurge
import me.raddatz.veyq.core.adminchannel.givenAdminChannel
import me.raddatz.veyq.core.moderation.ModerationLog
import me.raddatz.veyq.core.moderation.block.ModerationBlockEntity
import me.raddatz.veyq.core.identity.TenantProfileEntity
import me.raddatz.veyq.core.identity.VeyqUser
import me.raddatz.veyq.core.membership.MembershipEntity
import me.raddatz.veyq.core.membership.TenantRoots
import me.raddatz.veyq.core.moderation.hold.HoldEntity
import me.raddatz.veyq.core.moderation.hold.Holds
import me.raddatz.veyq.core.moderation.report.ModerationCaseEntity
import me.raddatz.veyq.core.scope.ScopeEntity
import me.raddatz.veyq.core.tenant.TenantEntity
import me.raddatz.veyq.core.testing.TestFixtures
import me.raddatz.veyq.core.testing.TestFixtures.seedTenant
import me.raddatz.veyq.core.testing.TestFixtures.seedUser
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.Response
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.notNullValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Slice tests for deleting an active tenant (uc.admin.delete-tenant, ADR 0101 decision 10), its stop and its purge — run
 * here by [TenantPurge.purge], the forced entry — and for the two ways out of an ownerless tenant: a tenant owner's
 * "make owner" and the Server Owner's owner invite to a member (decision 9).
 */
@QuarkusTest
class TenantDeletionSliceTest {

    @Inject
    lateinit var purge: TenantPurge

    @Inject
    lateinit var holds: Holds

    @Inject
    lateinit var accounts: AccountPurge

    @Test
    fun aDeletedTenantIsOfflineAtOnceAndAStopReturnsItSuspended() {
        val owner = seedUser()
        val tenant = seedTenant(owner = owner)
        val token = TestFixtures.bearer(owner)
        terms(token, tenant).then().statusCode(200) // control: reachable before
        delete(tenant, "wrong-${UUID.randomUUID()}.example.com").then().statusCode(409).body("code", equalTo("tenant_domain_mismatch"))
        assertEquals(TenantEntity.ACTIVE, statusOf(tenant))

        delete(tenant, domainOf(tenant)).then().statusCode(200).body("dueAt", notNullValue())
        assertEquals(TenantEntity.PENDING_DELETION, statusOf(tenant))
        terms(token, tenant).then().statusCode(403).body("code", equalTo("tenant_suspended"))
        delete(tenant, domainOf(tenant)).then().statusCode(409).body("code", equalTo("tenant_not_deletable"))

        stop(tenant).then().statusCode(204)
        assertEquals(TenantEntity.SUSPENDED, statusOf(tenant))
        stop(tenant).then().statusCode(409).body("code", equalTo("tenant_not_pending_deletion"))
    }

    @Test
    fun aPendingTenantIsRevokedNotDeleted() {
        val tenant = seedTenant(status = TenantEntity.PENDING, tenantScope = false)
        delete(tenant, domainOf(tenant)).then().statusCode(409).body("code", equalTo("tenant_not_deletable"))
    }

    @Test
    fun thePurgeErasesTheTenantAndLeavesItsAccountsAndItsLiveHolds() {
        val owner = seedUser()
        val member = seedUser()
        val tenant = seedTenant(owner = owner)
        val group = UUID.randomUUID().toString()
        val (liveHold, endedHold) = UUID.randomUUID().toString() to UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            TenantRoots.join(member, tenant)
            ScopeEntity().apply { id = group; tenantId = tenant; kind = ScopeEntity.GROUP }.persist()
            TenantProfileEntity().apply { id = UUID.randomUUID().toString(); userId = member; tenantId = tenant; username = "m${member.take(8)}" }.persist()
            TenantTermsAcceptanceEntity().apply {
                id = UUID.randomUUID().toString(); userId = member; tenantId = tenant; acceptedRevision = 1; acceptedAt = Instant.now()
            }.persist()
            val caseId = UUID.randomUUID().toString()
            ModerationCaseEntity().apply {
                id = caseId; itemKind = "NODE"; itemId = UUID.randomUUID().toString(); scopeId = group; category = "OTHER"; state = "OPEN"
            }.persist()
            hold(liveHold, caseId, Instant.now().plus(Duration.ofDays(30)))
            hold(endedHold, caseId, Instant.now().minusSeconds(60))
        }
        delete(tenant, domainOf(tenant)).then().statusCode(200)
        purge.purge(tenant)
        QuarkusTransaction.requiringNew().run {
            assertNull(TenantEntity.findById(tenant))
            assertNull(ScopeEntity.findById(tenant))
            assertNull(ScopeEntity.findById(group))
            assertEquals(0L, MembershipEntity.count("tenantId", tenant))
            assertEquals(0L, TenantProfileEntity.count("tenantId", tenant))
            assertEquals(0L, TenantTermsAcceptanceEntity.count("tenantId", tenant))
            assertNotNull(VeyqUser.findById(member), "every account stays (ADR 0101 decision 10)")
            assertNotNull(VeyqUser.findById(owner))
            assertNotNull(HoldEntity.findById(liveHold), "a hold hangs on its case, not on the tenant")
            assertNotNull(HoldEntity.findById(endedHold), "the tenant purge ends no hold, not even an ended one")
        }
        // Control: an ended hold goes by its own purge, the live one stays.
        QuarkusTransaction.requiringNew().run { holds.purgeExpired(Instant.now()) }
        QuarkusTransaction.requiringNew().run {
            assertNull(HoldEntity.findById(endedHold))
            assertNotNull(HoldEntity.findById(liveHold))
        }
    }

    /**
     * A tenant's erasure keeps the terms evidence exactly as an account's erasure does (inbox #593, ADR 0101 decision 6):
     * a member a moderation case names keeps their acceptance rows past the tenant, to that case's log date, and the one
     * sweep of kept rows deletes them once no case names the member any more.
     */
    @Test
    fun aCaseNamingAMemberKeepsItsTermsRowsPastTheTenantsErasureUntilNoCaseNamesItAnyMore() {
        val tenant = seedTenant(owner = seedUser())
        val elsewhere = seedTenant(owner = seedUser())
        val named = seedUser()
        val control = seedUser()
        val blockId = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            listOf(named, control).forEach { TenantRoots.join(it, tenant); accept(it, tenant) }
            TenantRoots.join(named, elsewhere)
            accept(named, elsewhere)
            ModerationBlockEntity().apply {
                id = blockId; kind = "MEMBER_SIGN_IN"; targetId = named; takenBy = UUID.randomUUID().toString()
                takenAt = Instant.now(); activeKey = "MEMBER_SIGN_IN:$named"
            }.persist()
        }
        delete(tenant, domainOf(tenant)).then().statusCode(200)
        purge.purge(tenant)
        QuarkusTransaction.requiringNew().run {
            assertNull(TenantEntity.findById(tenant))
            assertEquals(1L, TenantTermsAcceptanceEntity.count("userId = ?1 and tenantId = ?2", named, tenant), "a measure in force keeps the evidence")
            assertEquals(0L, TenantTermsAcceptanceEntity.count("userId", control), "control: no case, no rows")
        }
        // The measure lifted: its record, a step on no case, keeps the rows to its own date, and no further.
        val lifted = Instant.now()
        QuarkusTransaction.requiringNew().run {
            ModerationBlockEntity.findById(blockId)!!.apply { activeKey = null; liftedAt = lifted; liftedBy = UUID.randomUUID().toString() }
        }
        QuarkusTransaction.requiringNew().run { accounts.sweepKeptTerms(ModerationLog.retentionEnd(lifted).minusSeconds(60)) }
        QuarkusTransaction.requiringNew().run {
            assertEquals(1L, TenantTermsAcceptanceEntity.count("userId = ?1 and tenantId = ?2", named, tenant))
        }
        QuarkusTransaction.requiringNew().run { accounts.sweepKeptTerms(ModerationLog.retentionEnd(lifted).plusSeconds(60)) }
        QuarkusTransaction.requiringNew().run {
            assertEquals(0L, TenantTermsAcceptanceEntity.count("userId = ?1 and tenantId = ?2", named, tenant), "past that date they go")
            assertEquals(1L, TenantTermsAcceptanceEntity.count("userId = ?1 and tenantId = ?2", named, elsewhere),
                "control: a living member's rows in a living tenant are no kept rows, and the sweep never touches them")
        }
    }

    /**
     * The tenant's erasure asks the sweep's question too (ADR 0101 decision 6): a case closed the day before, whose
     * measure on a member was lifted, still names the member while its records are kept, so the member's terms rows
     * outlive the tenant to that case's log date; a member no record names loses them at once.
     */
    @Test
    fun aCaseClosedTheDayBeforeTheTenantsErasureKeepsTheNamedMembersTermsRowsToItsDate() {
        val tenant = seedTenant(owner = seedUser())
        val named = seedUser()
        val control = seedUser()
        val closed = Instant.now().minus(Duration.ofDays(1))
        QuarkusTransaction.requiringNew().run {
            listOf(named, control).forEach { TenantRoots.join(it, tenant); accept(it, tenant) }
            val caseId = UUID.randomUUID().toString()
            ModerationCaseEntity().apply {
                id = caseId; itemKind = "NODE"; itemId = UUID.randomUUID().toString(); scopeId = tenant; category = "OTHER"
                state = "CLOSED"; decidedAt = closed; closedAt = closed
            }.persist()
            ModerationBlockEntity().apply {
                id = UUID.randomUUID().toString(); kind = "MEMBER_SIGN_IN"; targetId = named; this.caseId = caseId
                takenBy = UUID.randomUUID().toString(); takenAt = closed.minus(Duration.ofDays(2))
                liftedAt = closed; liftedBy = takenBy; activeKey = null
            }.persist()
        }
        delete(tenant, domainOf(tenant)).then().statusCode(200)
        purge.purge(tenant)
        QuarkusTransaction.requiringNew().run {
            assertNull(TenantEntity.findById(tenant))
            assertEquals(1L, TenantTermsAcceptanceEntity.count("userId = ?1 and tenantId = ?2", named, tenant), "a kept case keeps the evidence")
            assertEquals(0L, TenantTermsAcceptanceEntity.count("userId", control), "control: no record, no rows")
        }
        QuarkusTransaction.requiringNew().run { accounts.sweepKeptTerms(ModerationLog.retentionEnd(closed).minusSeconds(60)) }
        assertEquals(1L, QuarkusTransaction.requiringNew().call { TenantTermsAcceptanceEntity.count("userId", named) }, "kept while the case's records are")
        QuarkusTransaction.requiringNew().run { accounts.sweepKeptTerms(ModerationLog.retentionEnd(closed).plusSeconds(60)) }
        assertEquals(0L, QuarkusTransaction.requiringNew().call { TenantTermsAcceptanceEntity.count("userId", named) }, "past the case's date they go")
    }

    @Test
    fun anOwnerMakesAMemberAnOwnerAndTheLastOwnerCannotBeDemoted() {
        val owner = seedUser()
        val member = seedUser()
        val tenant = seedTenant(owner = owner)
        QuarkusTransaction.requiringNew().run { TenantRoots.join(member, tenant) }
        role(TestFixtures.bearer(member), tenant, owner, "MEMBER").then().statusCode(403).body("code", equalTo("not_tenant_owner"))
        role(TestFixtures.bearer(owner), tenant, owner, "MEMBER").then().statusCode(409).body("code", equalTo("last_owner"))
        role(TestFixtures.bearer(owner), tenant, member, "OWNER").then().statusCode(204)
        assertEquals(MembershipEntity.OWNER, roleOf(member, tenant))
        // With a second owner, the first may step back.
        role(TestFixtures.bearer(owner), tenant, owner, "MEMBER").then().statusCode(204)
        assertEquals(MembershipEntity.MEMBER, roleOf(owner, tenant))
        role(TestFixtures.bearer(member), tenant, UUID.randomUUID().toString(), "OWNER").then().statusCode(404)
            .body("code", equalTo("member_not_found"))
    }

    @Test
    fun theServerOwnerInvitesAMemberToOwnAnOwnerlessTenant() {
        val member = seedUser()
        val tenant = seedTenant()
        QuarkusTransaction.requiringNew().run { TenantRoots.join(member, tenant) }
        ownerInvite(tenant, "u-${seedUser()}@example.com").then().statusCode(409).body("code", equalTo("not_tenant_member"))
        val secret = ownerInvite(tenant, "u-$member@example.com").then().statusCode(200)
            .extract().path<String>("bootstrapSecret")
        given().contentType(ContentType.JSON).body("""{"bootstrapSecret":"$secret"}""")
            .`when`().post("/api/tenants/accept/preview").then().statusCode(200).body("handover", equalTo(true))
        acceptOwnership(TestFixtures.bearer(seedUser()), secret).then().statusCode(403).body("code", equalTo("not_invited_owner"))
        acceptOwnership(TestFixtures.bearer(member), secret).then().statusCode(204)
        assertEquals(MembershipEntity.OWNER, roleOf(member, tenant))
        acceptOwnership(TestFixtures.bearer(member), secret).then().statusCode(410)
    }

    // --- helpers ---

    private val ownerToken by lazy { TestFixtures.bearer(seedUser(serverRole = "SERVER_OWNER")) }

    private fun domainOf(tenantId: String): String = "t-$tenantId.example.com"

    private fun delete(tenantId: String, domain: String): Response =
        givenAdminChannel().header("Authorization", ownerToken).contentType(ContentType.JSON)
            .body("""{"domain":"$domain"}""").`when`().post("/api/tenants/$tenantId/deletion")

    private fun stop(tenantId: String): Response =
        givenAdminChannel().header("Authorization", ownerToken).`when`().delete("/api/tenants/$tenantId/deletion")

    private fun ownerInvite(tenantId: String, email: String): Response =
        givenAdminChannel().header("Authorization", ownerToken).contentType(ContentType.JSON)
            .body("""{"email":"$email"}""").`when`().post("/api/tenants/$tenantId/owner-invite")

    private fun acceptOwnership(token: String, secret: String): Response =
        given().header("Authorization", token).contentType(ContentType.JSON)
            .body("""{"bootstrapSecret":"$secret"}""").`when`().post("/api/tenants/accept/ownership")

    private fun role(token: String, tenantId: String, userId: String, role: String): Response =
        given().header("Authorization", token).contentType(ContentType.JSON)
            .body("""{"role":"$role"}""").`when`().patch("/api/tenants/$tenantId/members/$userId")

    private fun accept(userId: String, tenantId: String) {
        TenantTermsAcceptanceEntity().apply {
            id = UUID.randomUUID().toString(); this.userId = userId; this.tenantId = tenantId; acceptedRevision = 1
            acceptedAt = Instant.now()
        }.persist()
    }

    private fun terms(token: String, tenantId: String): Response =
        given().header("Authorization", token).`when`().get("/api/tenants/$tenantId/terms")

    private fun statusOf(tenantId: String): String = QuarkusTransaction.requiringNew().call { TenantEntity.findById(tenantId)!!.status }

    private fun roleOf(userId: String, tenantId: String): String? = QuarkusTransaction.requiringNew().call {
        MembershipEntity.find("userId = ?1 and scopeId = ?2", userId, tenantId).firstResult()?.role
    }

    private fun hold(id: String, caseId: String, expiresAt: Instant) {
        HoldEntity().apply { this.id = id; this.caseId = caseId; trigger = "CASE_OPEN"; this.expiresAt = expiresAt }.persist()
    }
}
