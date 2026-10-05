package com.veyq.core.spaces.scope

import com.veyq.core.identity.TenantProfileEntity
import com.veyq.core.membership.MembershipEntity
import com.veyq.core.membership.SignedGrant
import com.veyq.core.membership.TenantRoots
import com.veyq.core.membership.TestAdmission
import com.veyq.core.scope.ScopeEntity
import com.veyq.core.testing.RACE_ROUNDS
import com.veyq.core.testing.TestFixtures.seedTenant
import com.veyq.core.testing.TestFixtures.seedUser
import com.veyq.core.testing.whileUncommitted
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import java.time.Instant
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A write a member's removal would clean up is refused once that removal has committed — also when the write's own
 * gate read the membership before the removal committed (developer-styleguide §12: a gate read outside its write's
 * transaction is sound only where some serial order of the two explains the outcome). A removal turns the removed
 * member's links off, deletes their item favorites and plans its tenant cascade from the memberships it reads; a
 * write that passed its gate before the removal and landed after it escaped all three, an outcome neither order of
 * the two produces. Each write now re-checks under the lock the removal holds.
 *
 * Each race holds the removal's commit back ([whileUncommitted]) and runs the write inside that window, so the order
 * is forced: the write's gate reads the member as still there. A group removal is the real removal-rotation
 * ([ScopesStore.rotate]). A tenant removal opens its own transaction, which [whileUncommitted] cannot hold, so it is
 * held as its two first writes — the tenant root's lock and the membership's delete, through the store calls
 * `TenantHandler.removeMember` makes. Each race has a control: the same write by a member who stays lands.
 *
 * **Split from `RemovalRaceSliceTest`** (cr-platform-gradle-modules step 8a): these methods run with core alone, so they live in
 * `:core`; the rest of the class is `RemovalRaceSliceTest` in `:apps:memories`. The bodies moved verbatim.
 */
@QuarkusTest
class RemovalRaceCoreSliceTest {

    /** A fresh ACTIVE tenant with an owner of its root. */
    private class Tenant {
        val owner: String = seedUser()
        val id: String = seedTenant(owner = owner)

        /** An accepted, granted member of the tenant root, with a per-tenant [username] when given. */
        fun member(username: String? = null): String = seedUser().also { userId ->
            QuarkusTransaction.requiringNew().run {
                TenantRoots.join(userId, id)
                username?.let {
                    TenantProfileEntity().apply {
                        this.id = UUID.randomUUID().toString(); this.userId = userId; tenantId = this@Tenant.id
                        this.username = it; createdAt = Instant.now()
                    }.persist()
                }
            }
        }

        /** A GROUP of this tenant at epoch 0, owned by [owner], with each of [members] a granted MEMBER. */
        fun group(vararg members: String): String {
            val scopeId = UUID.randomUUID().toString()
            QuarkusTransaction.requiringNew().run {
                ScopeEntity().apply {
                    this.id = scopeId; tenantId = this@Tenant.id; kind = ScopeEntity.GROUP
                    encryptedName = NAME; createdAt = Instant.now()
                }.persist()
                (listOf(owner to MembershipEntity.OWNER) + members.map { it to MembershipEntity.MEMBER }).forEach { (userId, role) ->
                    MembershipEntity().apply {
                        this.id = UUID.randomUUID().toString(); this.userId = userId; tenantId = this@Tenant.id
                        this.scopeId = scopeId; this.role = role; wrappedGrant = "Z3JhbnQ="; createdAt = Instant.now()
                    }.persist()
                }
            }
            return scopeId
        }
    }

    private companion object {
        const val NAME = "c2VhbGVkLW5hbWU="
        const val WRAP = "d3JhcC1rLW1lbS11bmRlci1zaGFyZQ=="
        val GRANT = SignedGrant("Z3JhbnQ=", TestAdmission.SIGNATURE)
    }

    @Test
    fun aGroupCreatedByAMemberBeingRemovedFromTheTenantIsRefused() {
        repeat(RACE_ROUNDS) { round ->
            val t = Tenant()
            val member = t.member()
            val scopeId = UUID.randomUUID().toString()
            val outcome = whileUncommitted(
                change = { removeFromTenant(t.id, member) },
                overlapping = { handler.createGroup(member, t.id, scopeId, NAME, GRANT, TestAdmission.admission()) },
            )
            assertEquals(CreateGroupOutcome.NoTenant, outcome, "round $round: a removed member created a group")
            assertNull(tx { ScopeEntity.findById(scopeId) }, "round $round: nothing written")
            val control = handler.createGroup(t.owner, t.id, UUID.randomUUID().toString(), NAME, GRANT, TestAdmission.admission())
            assertTrue(control is CreateGroupOutcome.Created, "round $round: control $control")
        }
    }

    @Test
    fun aPersonalSpaceCreatedByAMemberBeingRemovedFromTheTenantIsRefused() {
        repeat(RACE_ROUNDS) { round ->
            val t = Tenant()
            val member = t.member()
            val scopeId = UUID.randomUUID().toString()
            val outcome = whileUncommitted(
                change = { removeFromTenant(t.id, member) },
                overlapping = { handler.createPersonal(member, t.id, scopeId, GRANT) },
            )
            assertEquals(CreatePersonalOutcome.NoTenant, outcome, "round $round: a removed member created a personal space")
            assertNull(tx { ScopeEntity.findById(scopeId) }, "round $round: nothing written")
            val control = handler.createPersonal(t.owner, t.id, UUID.randomUUID().toString(), GRANT)
            assertTrue(control is CreatePersonalOutcome.Created, "round $round: control $control")
        }
    }

    @Test
    fun aMemberBeingRemovedFromTheTenantIsNotAddedToAGroup() {
        repeat(RACE_ROUNDS) { round ->
            val t = Tenant()
            val target = t.member(username = "target")
            val group = t.group()
            val outcome = whileUncommitted(
                change = { removeFromTenant(t.id, target) },
                overlapping = { handler.addGroupMember(t.owner, group, "target", null, TestAdmission.admission()) },
            )
            assertEquals(AddGroupMemberOutcome.MemberNotFound, outcome, "round $round: a removed member was added")
            assertEquals(0L, tx { MembershipEntity.count("userId = ?1 and scopeId = ?2", target, group) }, "round $round")
            t.member(username = "stays")
            val control = handler.addGroupMember(t.owner, group, "stays", null, TestAdmission.admission())
            assertTrue(control is AddGroupMemberOutcome.Added, "round $round: control $control")
        }
    }

    @Inject lateinit var scopes: ScopesStore

    @Inject lateinit var handler: ScopesHandler

    // --- helpers ---

    /** A tenant removal's first two writes, as `TenantHandler.removeMember` makes them: the tenant root's lock, then
     *  the membership's delete (see the class doc for why not the handler itself). */
    private fun removeFromTenant(tenantId: String, member: String) {
        scopes.lockScope(tenantId)
        scopes.removeMembership(member, tenantId)
    }

    private fun <T> tx(body: () -> T): T = QuarkusTransaction.requiringNew().call(body)
}
