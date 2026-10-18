package me.raddatz.veyq.core.admin.tenant

import me.raddatz.veyq.core.membership.MembershipEntity
import me.raddatz.veyq.core.scope.ScopeEntity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The pure tenant member-removal decision (ADR 0045 §2), boot-free and mutation-verified. Every assertion pins an
 * exact action + rotate flag — a bare "not empty" would let a mutant that soft-deletes shared content, skips the
 * orphan transfer, or rotates the tenant scope pass (the assertion-strength trap ADR 0045 exists to close). The
 * boundaries under test: the tenant last-owner guard (ADR 0007) and the sole-owner `ownerCount <= 1` orphan trigger
 * and the solo-group `memberCount <= 1` fork (ADR 0014 §6). The end-to-end wiring is the slice `MemberRemoveSliceTest`.
 */
class RemovalPolicyTest {

    private val tenantId = "1936be56-1739-441e-8180-125e45f1c3bd"

    private fun tenant(role: String = MembershipEntity.MEMBER, ownerCount: Long = 2) =
        RemovalScope(tenantId, ScopeEntity.TENANT, role, memberCount = 5, ownerCount = ownerCount)

    private fun group(scopeId: String, role: String, memberCount: Long, ownerCount: Long) =
        RemovalScope(scopeId, ScopeEntity.GROUP, role, memberCount, ownerCount)

    private fun personal(scopeId: String) =
        RemovalScope(scopeId, ScopeEntity.PERSONAL, MembershipEntity.OWNER, memberCount = 1, ownerCount = 1)

    private fun cascade(plan: RemovalPlan): List<CascadeStep> =
        (plan as RemovalPlan.Cascade).steps

    // --- terminal outcomes -----------------------------------------------------------------------------------

    @Test
    fun memberNotFoundWhenNoRowIsTheTenantScope() {
        val plan = RemovalPolicy.plan(listOf(group("g", MembershipEntity.OWNER, 3, 1)), tenantId)
        assertEquals(RemovalPlan.MemberNotFound, plan, "no tenant-scope row means the target is not a member")
    }

    @Test
    fun lastTenantOwnerIsRejected() {
        val plan = RemovalPolicy.plan(listOf(tenant(role = MembershipEntity.OWNER, ownerCount = 1)), tenantId)
        assertEquals(RemovalPlan.LastOwner, plan, "the last tenant OWNER cannot be removed (ADR 0007)")
    }

    @Test
    fun theErasuresServerOwnerDoorCutsTheLastOwnerLikeAnyMember() {
        // ADR 0101 decision 9: the Server Owner's door never refuses, and leaves the tenant ownerless.
        val plan = RemovalPolicy.plan(listOf(tenant(role = MembershipEntity.OWNER, ownerCount = 1)), tenantId, allowLastOwner = true)
        assertEquals(listOf(CascadeStep(tenantId, CascadeAction.REMOVE, rotateAfter = false)), cascade(plan))
        // Control: the same row without the allowance is the last owner, refused.
        assertEquals(RemovalPlan.LastOwner, RemovalPolicy.plan(listOf(tenant(role = MembershipEntity.OWNER, ownerCount = 1)), tenantId))
    }

    @Test
    fun aTenantOwnerWithACoOwnerIsNotTheLastOwner() {
        val plan = RemovalPolicy.plan(listOf(tenant(role = MembershipEntity.OWNER, ownerCount = 2)), tenantId)
        assertEquals(
            listOf(CascadeStep(tenantId, CascadeAction.REMOVE, rotateAfter = false)), cascade(plan),
            "with a co-owner present the removal proceeds; the tenant scope is ACL-revoke only",
        )
    }

    @Test
    fun anOrdinaryTenantMemberIsNeverTheLastOwner() {
        val plan = RemovalPolicy.plan(listOf(tenant(role = MembershipEntity.MEMBER, ownerCount = 1)), tenantId)
        assertEquals(
            listOf(CascadeStep(tenantId, CascadeAction.REMOVE, rotateAfter = false)), cascade(plan),
            "a non-owner is removed regardless of the tenant owner count",
        )
    }

    // --- per-scope action selection --------------------------------------------------------------------------

    @Test
    fun personalScopeIsSoftDeletedAndNeverRotated() {
        val plan = RemovalPolicy.plan(listOf(tenant(), personal("p")), tenantId)
        assertEquals(
            CascadeStep("p", CascadeAction.SOFT_DELETE, rotateAfter = false), cascade(plan).single { it.scopeId == "p" },
            "the target's PERSONAL scope is grace-tombstoned, membership kept, never rotated",
        )
    }

    @Test
    fun soloGroupIsSoftDeletedNotRemoved() {
        val plan = RemovalPolicy.plan(listOf(tenant(), group("solo", MembershipEntity.OWNER, memberCount = 1, ownerCount = 1)), tenantId)
        assertEquals(
            CascadeStep("solo", CascadeAction.SOFT_DELETE, rotateAfter = false), cascade(plan).single { it.scopeId == "solo" },
            "a solo GROUP (memberCount <= 1) is grace-tombstoned like the personal scope, not cut",
        )
    }

    @Test
    fun soleOwnedSharedGroupTransfersThenRemovesAndRotates() {
        val plan = RemovalPolicy.plan(listOf(tenant(), group("shared", MembershipEntity.OWNER, memberCount = 3, ownerCount = 1)), tenantId)
        assertEquals(
            CascadeStep("shared", CascadeAction.TRANSFER_THEN_REMOVE, rotateAfter = true), cascade(plan).single { it.scopeId == "shared" },
            "a group the target solely owns (ownerCount <= 1, but others remain) hands over, then cuts + rotates",
        )
    }

    @Test
    fun coOwnedSharedGroupIsPlainRemoveAndRotates() {
        val plan = RemovalPolicy.plan(listOf(tenant(), group("co", MembershipEntity.OWNER, memberCount = 3, ownerCount = 2)), tenantId)
        assertEquals(
            CascadeStep("co", CascadeAction.REMOVE, rotateAfter = true), cascade(plan).single { it.scopeId == "co" },
            "with a co-owner present there is no orphan transfer — a plain cut + forward rotation",
        )
    }

    @Test
    fun memberInSharedGroupIsPlainRemoveAndRotates() {
        val plan = RemovalPolicy.plan(listOf(tenant(), group("mem", MembershipEntity.MEMBER, memberCount = 3, ownerCount = 1)), tenantId)
        assertEquals(
            CascadeStep("mem", CascadeAction.REMOVE, rotateAfter = true), cascade(plan).single { it.scopeId == "mem" },
            "a non-owner in a shared group is cut without a transfer; the group still rotates forward",
        )
    }
}
