package me.raddatz.veyq.core.admin.tenant

import me.raddatz.veyq.core.scope.ScopeEntity
import me.raddatz.veyq.core.spaces.scope.ScopePurge
import me.raddatz.veyq.core.tasks.CapabilityTags
import me.raddatz.veyq.core.tasks.JobEntity
import me.raddatz.veyq.core.membership.MembershipEntity
import me.raddatz.veyq.core.membership.TestGrants
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedTenant
import me.raddatz.veyq.core.testing.TestFixtures.seedUser
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.nullValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Slice tests for tenant-member removal (uc.spaces.remove-tenant-member, cr-0091): the tenant **owner** removes a
 * member — the ACL-revoke cascade (delete their tenant + co-shared group memberships; the tenant key is NOT
 * rotated, ADR 0025) + the **orphan rule** (a solely-owned group transfers to the oldest remaining member, ADR
 * 0014 §6) + **humane erasure** (their own scopes are grace-tombstoned with read access kept, cr-0090). Distinct
 * domains/emails so the JVM-lived H2 mem DB can't bleed state between tests.
 */
@QuarkusTest
class MemberRemoveSliceTest {

    @Inject lateinit var purge: ScopePurge

    @Test
    fun ownerRemovesMemberCutsSharedButKeepsOwnScopeReadable() {
        val (tenantId, owner) = seedActiveTenant("rm-main.example.com", "owner@rm-main.example.com")
        val member = seedTenantMember(tenantId, "m@rm-main.example.com")
        val personal = seedPersonal(tenantId, member)
        val group = seedGroup(tenantId, owner)   // the owner owns a group
        addMember(tenantId, group, member)       // the member co-shares it

        given().header("Authorization", bearer(owner))
            .`when`().delete("/api/tenants/$tenantId/members/$member")
            .then().statusCode(204)

        // Shared cut: the tenant + co-shared group memberships are gone; the group itself carries on (owner stays).
        assertFalse(hasMembership(member, tenantId), "tenant membership cut")
        assertFalse(hasMembership(member, group), "co-shared group membership cut")
        assertTrue(scopeExists(group), "the shared group carries on")
        assertNull(deletedAt(group), "a co-shared group is not tombstoned")

        // Own scope: the personal scope is grace-tombstoned but the membership is KEPT (read-only grace).
        assertNotNull(deletedAt(personal), "personal scope grace-tombstoned")
        assertTrue(hasMembership(member, personal), "membership kept for read-only grace")
    }

    @Test
    fun soleOwnedGroupWithMembersTransfersToOldestRemaining() {
        val (tenantId, owner) = seedActiveTenant("rm-orphan.example.com", "owner@rm-orphan.example.com")
        val member = seedTenantMember(tenantId, "m@rm-orphan.example.com")
        val elder = seedTenantMember(tenantId, "elder@rm-orphan.example.com")
        val junior = seedTenantMember(tenantId, "junior@rm-orphan.example.com")
        val group = seedGroup(tenantId, member)  // the member solely owns it
        addMember(tenantId, group, elder, createdAt = Instant.parse("2026-01-01T00:00:00Z"))
        addMember(tenantId, group, junior, createdAt = Instant.parse("2026-06-01T00:00:00Z"))

        given().header("Authorization", bearer(owner))
            .`when`().delete("/api/tenants/$tenantId/members/$member")
            .then().statusCode(204)

        assertFalse(hasMembership(member, group), "the departing owner's membership is cut")
        assertEquals(MembershipEntity.OWNER, roleOn(elder, group), "oldest remaining member promoted to OWNER")
        assertEquals(MembershipEntity.MEMBER, roleOn(junior, group), "the newer member stays a MEMBER")
        assertNull(deletedAt(group), "the group carries on under new ownership")
    }

    @Test
    fun soloGroupIsGraceTombstonedWithMembershipKept() {
        val (tenantId, owner) = seedActiveTenant("rm-solo.example.com", "owner@rm-solo.example.com")
        val member = seedTenantMember(tenantId, "m@rm-solo.example.com")
        val solo = seedGroup(tenantId, member)   // the member is the only member

        given().header("Authorization", bearer(owner))
            .`when`().delete("/api/tenants/$tenantId/members/$member")
            .then().statusCode(204)

        assertNotNull(deletedAt(solo), "a solo group the member alone held is grace-tombstoned")
        assertTrue(hasMembership(member, solo), "their membership is kept for read-only grace")
    }

    @Test
    fun lastTenantOwnerCannotBeRemoved() {
        val (tenantId, owner) = seedActiveTenant("rm-last.example.com", "owner@rm-last.example.com")
        given().header("Authorization", bearer(owner))
            .`when`().delete("/api/tenants/$tenantId/members/$owner")
            .then().statusCode(409).body("code", equalTo("last_owner"))
        assertTrue(hasMembership(owner, tenantId), "the last owner keeps their membership")
    }

    @Test
    fun nonOwnerForbiddenAndUnknownMemberNotFound() {
        val (tenantId, owner) = seedActiveTenant("rm-guard.example.com", "owner@rm-guard.example.com")
        val member = seedTenantMember(tenantId, "m@rm-guard.example.com")
        val outsider = seedTenantMember(tenantId, "out@rm-guard.example.com")

        // A non-owner cannot remove anyone (owner-gated).
        given().header("Authorization", bearer(outsider))
            .`when`().delete("/api/tenants/$tenantId/members/$member")
            .then().statusCode(403).body("code", equalTo("not_tenant_owner"))
        assertTrue(hasMembership(member, tenantId), "the member is untouched by a forbidden attempt")

        // The owner removing a non-member → 404.
        val stranger = seedUser(email = "stranger@rm-guard.example.com")
        given().header("Authorization", bearer(owner))
            .`when`().delete("/api/tenants/$tenantId/members/$stranger")
            .then().statusCode(404).body("code", equalTo("member_not_found"))
    }

    @Test
    fun scopePurgeErasesTheGraceTombstonedOwnScope() {
        val (tenantId, owner) = seedActiveTenant("rm-purge.example.com", "owner@rm-purge.example.com")
        val member = seedTenantMember(tenantId, "m@rm-purge.example.com")
        val personal = seedPersonal(tenantId, member)

        given().header("Authorization", bearer(owner))
            .`when`().delete("/api/tenants/$tenantId/members/$member")
            .then().statusCode(204)
        assertNotNull(deletedAt(personal), "grace-tombstoned on removal")

        purge.purge(personal)   // the scheduled grace-purge, run now
        assertFalse(scopeExists(personal), "the grace-tombstoned own scope is erased")
        assertFalse(hasMembership(member, personal), "and its kept membership is gone with it")
    }

    // --- cr-0092: the async group-key-rotation backstop (SCOPE_ROTATION on the blind task queue) --------------

    @Test
    fun removalQueuesRotationOnlyForCoSharedGroupsNotOwnScopesOrTenant() {
        val (tenantId, owner) = seedActiveTenant("rm-rot1.example.com", "owner@rm-rot1.example.com")
        val keyholder = seedTenantMember(tenantId, "kh@rm-rot1.example.com")
        val member = seedTenantMember(tenantId, "m@rm-rot1.example.com")
        val coShared = seedGroup(tenantId, keyholder) // owned by a remaining key-holder, not the removing owner
        addMember(tenantId, coShared, member)         // the member co-shares it
        val solo = seedGroup(tenantId, member)        // the member's own solo group → soft-deleted, not rotated
        val personal = seedPersonal(tenantId, member) // own scope → soft-deleted, not rotated

        given().header("Authorization", bearer(owner))
            .`when`().delete("/api/tenants/$tenantId/members/$member")
            .then().statusCode(204)

        assertEquals(1L, liveRotationJobs(coShared), "one rotation job queued for the co-shared group")
        assertEquals(0L, liveRotationJobs(solo), "a soft-deleted solo group is not rotated")
        assertEquals(0L, liveRotationJobs(personal), "a personal scope is not rotated")
        assertEquals(0L, liveRotationJobs(tenantId), "the tenant scope is ACL-revoke only, never rotated (ADR 0025)")
    }

    @Test
    fun queuedRotationClaimableByRemainingKeyHolderOnlyThenCompletionClearsIt() {
        val (tenantId, owner) = seedActiveTenant("rm-rot2.example.com", "owner@rm-rot2.example.com")
        val keyholder = seedTenantMember(tenantId, "kh@rm-rot2.example.com")
        val member = seedTenantMember(tenantId, "m@rm-rot2.example.com")
        val group = seedGroup(tenantId, keyholder)
        addMember(tenantId, group, member)

        given().header("Authorization", bearer(owner))
            .`when`().delete("/api/tenants/$tenantId/members/$member").then().statusCode(204)

        // The removing tenant owner is NOT a key-holder of the group → nothing claimable for them (the whole
        // point of the async backstop). The removed member no longer holds the group grant → cannot claim either.
        claimRotation(owner).then().statusCode(204)
        claimRotation(member).then().statusCode(204)

        // The remaining key-holder claims it — the cleartext envelope only (no grant-delivery target, no key).
        val jobId = claimRotation(keyholder)
            .then().statusCode(200)
            .body("capabilityTag", equalTo(CapabilityTags.SCOPE_ROTATION))
            .body("scopeId", equalTo(group))
            .body("grantDelivery", nullValue())
            .extract().path<String>("jobId")

        // The worker's on-device rotation-commit advances the epoch during its lease — the commit its completion
        // requires — and completing then clears the job; none re-claimable.
        given().header("Authorization", bearer(keyholder)).contentType(ContentType.JSON)
            .body(
                """{"epoch":1,"wrappedPrev":"cHJldg==","grants":[{"userId":"$keyholder","wrappedGrant":"Z3JhbnQ=",""" +
                    """${TestGrants.SIGNATURE_JSON}}],${TestGrants.openInvitesJson(group)}}""",
            )
            .`when`().post("/api/scopes/$group/epochs").then().statusCode(201)
        given().header("Authorization", bearer(keyholder)).contentType(ContentType.JSON).body("{}")
            .`when`().post("/api/tasks/$jobId/complete").then().statusCode(204)
        assertEquals(0L, liveRotationJobs(group), "a completed rotation job is no longer live")
        claimRotation(keyholder).then().statusCode(204)
    }

    @Test
    fun secondRemovalCoalescesIntoTheOneLiveRotationJob() {
        val (tenantId, owner) = seedActiveTenant("rm-rot3.example.com", "owner@rm-rot3.example.com")
        val keyholder = seedTenantMember(tenantId, "kh@rm-rot3.example.com")
        val first = seedTenantMember(tenantId, "a@rm-rot3.example.com")
        val second = seedTenantMember(tenantId, "b@rm-rot3.example.com")
        val group = seedGroup(tenantId, keyholder)
        addMember(tenantId, group, first)
        addMember(tenantId, group, second)

        given().header("Authorization", bearer(owner))
            .`when`().delete("/api/tenants/$tenantId/members/$first").then().statusCode(204)
        assertEquals(1L, liveRotationJobs(group), "the first removal queues a rotation")

        // A second removal from the same group before an owner claimed the job collapses into the one pending job —
        // a single rotation re-grants the surviving set regardless of how many removals coalesced.
        given().header("Authorization", bearer(owner))
            .`when`().delete("/api/tenants/$tenantId/members/$second").then().statusCode(204)
        assertEquals(1L, liveRotationJobs(group), "the second removal coalesces into the one pending job")
    }

    // --- helpers ---

    private fun claimRotation(userId: String) =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"capabilityTags":["${CapabilityTags.SCOPE_ROTATION}"]}""")
            .`when`().post("/api/tasks/claim")

    /** Count the live (PENDING/LEASED) SCOPE_ROTATION jobs for a scope — the coalescing / origination assertion. */
    private fun liveRotationJobs(scopeId: String): Long =
        QuarkusTransaction.requiringNew().call {
            JobEntity.count(
                "scopeId = ?1 and capabilityTag = ?2 and status in ?3",
                scopeId, CapabilityTags.SCOPE_ROTATION, listOf(JobEntity.PENDING, JobEntity.LEASED),
            )
        }

    /** An ACTIVE tenant + its TENANT scope + a granted OWNER. Returns (tenantId, ownerUserId). */
    private fun seedActiveTenant(domain: String, ownerEmail: String): Pair<String, String> {
        val ownerId = seedUser(email = ownerEmail)
        return seedTenant(domain = domain, ownerEmail = ownerEmail, owner = ownerId) to ownerId
    }

    /** A tenant member: a tenant-scope membership (scopeId == tenantId). */
    private fun seedTenantMember(tenantId: String, email: String, role: String = MembershipEntity.MEMBER): String {
        val id = seedUser(email = email)
        QuarkusTransaction.requiringNew().run { membership(id, tenantId, tenantId, role) }
        return id
    }

    /** A GROUP scope in the tenant + an OWNER membership for `ownerId`. Returns scopeId. */
    private fun seedGroup(tenantId: String, ownerId: String): String {
        val scopeId = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            ScopeEntity().apply {
                id = scopeId; this.tenantId = tenantId; kind = ScopeEntity.GROUP
                encryptedName = "bmFtZQ=="
            }.persist()
            membership(ownerId, tenantId, scopeId, MembershipEntity.OWNER)
        }
        return scopeId
    }

    /** A PERSONAL scope in the tenant + the self-OWNER membership. Returns scopeId. */
    private fun seedPersonal(tenantId: String, userId: String): String {
        val scopeId = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            ScopeEntity().apply {
                id = scopeId; this.tenantId = tenantId; kind = ScopeEntity.PERSONAL
            }.persist()
            membership(userId, tenantId, scopeId, MembershipEntity.OWNER)
        }
        return scopeId
    }

    private fun addMember(
        tenantId: String, scopeId: String, userId: String,
        role: String = MembershipEntity.MEMBER, createdAt: Instant = Instant.now(),
    ) = QuarkusTransaction.requiringNew().run { membership(userId, tenantId, scopeId, role, createdAt) }

    /** Persist a membership. Must run inside an active transaction. */
    private fun membership(
        userId: String, tenantId: String, scopeId: String, role: String, createdAt: Instant = Instant.now(),
    ) {
        MembershipEntity().apply {
            id = UUID.randomUUID().toString(); this.userId = userId; this.tenantId = tenantId
            this.scopeId = scopeId; this.role = role; wrappedGrant = "Z3JhbnQ="; this.createdAt = createdAt
        }.persist()
    }

    private fun hasMembership(userId: String, scopeId: String): Boolean =
        QuarkusTransaction.requiringNew().call { MembershipEntity.count("userId = ?1 and scopeId = ?2", userId, scopeId) > 0 }

    private fun roleOn(userId: String, scopeId: String): String? =
        QuarkusTransaction.requiringNew().call {
            MembershipEntity.find("userId = ?1 and scopeId = ?2", userId, scopeId).firstResult()?.role
        }

    private fun deletedAt(scopeId: String): Instant? =
        QuarkusTransaction.requiringNew().call { ScopeEntity.findById(scopeId)?.deletedAt }

    private fun scopeExists(scopeId: String): Boolean =
        QuarkusTransaction.requiringNew().call { ScopeEntity.findById(scopeId) != null }
}
