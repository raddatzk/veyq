package me.raddatz.veyq.core.spaces.scope

import me.raddatz.veyq.core.admin.tenant.RemoveTenantMemberOutcome
import me.raddatz.veyq.core.admin.tenant.TenantHandler
import me.raddatz.veyq.core.membership.MembershipEntity
import me.raddatz.veyq.core.membership.TenantRoots
import me.raddatz.veyq.core.scope.ScopeEntity
import me.raddatz.veyq.core.testing.RACE_ROUNDS
import me.raddatz.veyq.core.testing.TestFixtures.seedTenant
import me.raddatz.veyq.core.testing.TestFixtures.seedUser
import me.raddatz.veyq.core.testing.whileUncommitted
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * A group is never left without an OWNER (ADR 0007), also when two owner-role changes overlap (developer-styleguide
 * §12: the check that decides a write is read where the write happens). Each race holds the first change's commit
 * back ([whileUncommitted]) and runs the second inside that window, so the order is forced: the second change's
 * pre-checks read the state before the first, and only a check under the scope row's lock sees the first. Each
 * answer is the one a replay of the second request after the first commits gets — never a second success.
 */
@QuarkusTest
class OwnerRoleRaceSliceTest {

    @Inject lateinit var handler: ScopesHandler
    @Inject lateinit var tenants: TenantHandler

    @Test
    fun twoOwnersDemotingEachOtherLeaveOneOwner() {
        repeat(RACE_ROUNDS) { round ->
            val t = Tenant()
            val (a, b) = t.member() to t.member()
            val group = t.group(a to OWNER, b to OWNER)

            val second = whileUncommitted(
                change = { assertTrue(handler.setMemberRole(a, group, b, MEMBER) is SetRoleOutcome.Ok, "round $round") },
                overlapping = { handler.setMemberRole(b, group, a, MEMBER) },
            )

            // Replayed after the first commit, B is no owner any more.
            assertEquals(SetRoleOutcome.NotOwner, second, "round $round")
            assertEquals(listOf(a), owners(group), "round $round: the group keeps exactly its first demoter")
        }
        // Control: with a co-owner beside them, an owner's demote lands.
        val t = Tenant()
        val (a, b) = t.member() to t.member()
        val group = t.group(a to OWNER, b to OWNER)
        assertTrue(handler.setMemberRole(a, group, b, MEMBER) is SetRoleOutcome.Ok)
        assertEquals(listOf(a), owners(group))
    }

    @Test
    fun twoOwnersStepDownTogetherLeaveOneOwner() {
        repeat(RACE_ROUNDS) { round ->
            val t = Tenant()
            val (a, b) = t.member() to t.member()
            val group = t.group(a to OWNER, b to OWNER)

            val second = whileUncommitted(
                change = { assertTrue(handler.setMemberRole(a, group, a, MEMBER) is SetRoleOutcome.Ok, "round $round") },
                overlapping = { handler.setMemberRole(b, group, b, MEMBER) },
            )

            // Replayed after the first commit, B is the last owner.
            assertEquals(SetRoleOutcome.LastOwner, second, "round $round")
            assertEquals(listOf(b), owners(group), "round $round: the group keeps the owner who stepped down second")
        }
    }

    @Test
    fun aTenantRemovalOverlappingADemoteLeavesTheGroupAnOwner() {
        // The removal plans its cascade from owner counts it reads: read before a co-owner's demote commits, it cut
        // the other owner as one of two, and the group was left with none. Replayed after the demote, the cut member
        // is the sole owner and the orphan rule hands the group on first.
        repeat(RACE_ROUNDS) { round ->
            val t = Tenant()
            val (stays, cut) = t.member() to t.member()
            val group = t.group(stays to OWNER, cut to OWNER)

            val removal = whileUncommitted(
                change = { assertTrue(handler.setMemberRole(stays, group, stays, MEMBER) is SetRoleOutcome.Ok, "round $round") },
                overlapping = { tenants.removeMember(t.owner, t.id, cut) },
            )

            assertEquals(RemoveTenantMemberOutcome.Ok, removal, "round $round")
            assertEquals(listOf(stays), owners(group), "round $round: the orphan rule handed the group to who stays")
        }
    }

    private fun owners(scopeId: String): List<String> = QuarkusTransaction.requiringNew().call {
        MembershipEntity.find("scopeId = ?1 and role = ?2", scopeId, OWNER).list().map { it.userId }
    }

    /** A fresh ACTIVE tenant with an owner of its root. */
    private class Tenant {
        val owner: String = seedUser()
        val id: String = seedTenant(owner = owner)

        /** An accepted, granted member of the tenant root. */
        fun member(role: String = MEMBER): String =
            seedUser().also { QuarkusTransaction.requiringNew().run { TenantRoots.join(it, id, role) } }

        /** A GROUP of this tenant with [members] at their roles, each holding a grant. */
        fun group(vararg members: Pair<String, String>): String {
            val scopeId = UUID.randomUUID().toString()
            QuarkusTransaction.requiringNew().run {
                ScopeEntity().apply {
                    this.id = scopeId; tenantId = this@Tenant.id; kind = ScopeEntity.GROUP
                    encryptedName = "bmFtZQ=="
                }.persist()
                members.forEachIndexed { i, (userId, role) ->
                    MembershipEntity().apply {
                        this.id = UUID.randomUUID().toString(); this.userId = userId; tenantId = this@Tenant.id
                        this.scopeId = scopeId; this.role = role; wrappedGrant = "Z3JhbnQ="
                        createdAt = Instant.now().plusMillis(i.toLong()) // the orphan rule's "oldest" is the first listed
                    }.persist()
                }
            }
            return scopeId
        }
    }

    private companion object {
        const val OWNER = MembershipEntity.OWNER
        const val MEMBER = MembershipEntity.MEMBER
    }
}
