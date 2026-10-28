package me.raddatz.veyq.core.spaces.scope

import me.raddatz.veyq.core.admin.tenant.CascadeAction
import me.raddatz.veyq.core.admin.tenant.CascadeStep
import me.raddatz.veyq.core.admin.tenant.RemovalPlan
import me.raddatz.veyq.core.admin.tenant.RemovalPolicy
import me.raddatz.veyq.core.admin.tenant.RemovalScope
import me.raddatz.veyq.core.identity.TenantProfileEntity
import me.raddatz.veyq.core.membership.MembershipEntity
import me.raddatz.veyq.core.membership.SignedGrant
import me.raddatz.veyq.core.membership.TenantRoots
import me.raddatz.veyq.core.membership.TestGrants
import me.raddatz.veyq.core.scope.ScopeEntity
import me.raddatz.veyq.core.testing.TestFixtures.seedTenant
import me.raddatz.veyq.core.testing.TestFixtures.seedUser
import me.raddatz.veyq.core.testing.whileUncommitted
import me.raddatz.veyq.core.testing.raceRounds
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
 * An accept of a consent proposal is held the other way round (inbox #599): the removal's window is between its plan
 * read and the deletes that read decided, so the hold sits after its locks and its read, and its deletes run after the
 * hold (`whileUncommitted`'s `beforeCommit`). The accept must wait on the scope lock the removal holds and then find no
 * proposal; without that lock it committed inside the hold and outlived the deletes.
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
                        this.username = it
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
                    encryptedName = NAME
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

        /** A GROUP of this tenant at epoch 0 owned by [owner] alone, with an open consent proposal to [proposed]. */
        fun proposedGroup(owner: String, proposed: String): String {
            val scopeId = UUID.randomUUID().toString()
            QuarkusTransaction.requiringNew().run {
                ScopeEntity().apply {
                    this.id = scopeId; tenantId = this@Tenant.id; kind = ScopeEntity.GROUP
                    encryptedName = NAME
                }.persist()
                MembershipEntity().apply {
                    this.id = UUID.randomUUID().toString(); userId = owner; tenantId = this@Tenant.id
                    this.scopeId = scopeId; role = MembershipEntity.OWNER; wrappedGrant = "Z3JhbnQ="; createdAt = Instant.now()
                }.persist()
                proposal(scopeId, owner, proposed)
            }
            return scopeId
        }

        /** An open consent proposal on [scopeId] from [owner] to [proposed], holding its grant (ADR 0111 decision 4). */
        fun propose(scopeId: String, owner: String, proposed: String) =
            QuarkusTransaction.requiringNew().run { proposal(scopeId, owner, proposed) }

        private fun proposal(scopeId: String, owner: String, proposed: String) {
            MembershipEntity().apply {
                this.id = UUID.randomUUID().toString(); userId = proposed; tenantId = this@Tenant.id
                this.scopeId = scopeId; role = MembershipEntity.MEMBER; wrappedGrant = "Z3JhbnQ="; pendingConsent = true
                sealedNameKey = "bmFtZS1rZXk="; addedBy = owner; createdAt = Instant.now()
            }.persist()
        }
    }

    private companion object {
        const val NAME = "c2VhbGVkLW5hbWU="
        const val WRAP = "d3JhcC1rLW1lbS11bmRlci1zaGFyZQ=="
        val GRANT = SignedGrant("Z3JhbnQ=", TestGrants.SIGNATURE)
    }

    @Test
    fun aGroupCreatedByAMemberBeingRemovedFromTheTenantIsRefused() {
        raceRounds { round ->
            val t = Tenant()
            val member = t.member()
            val scopeId = UUID.randomUUID().toString()
            val digest = TestGrants.digestOf(member)
            val outcome = whileUncommitted(
                change = { removeFromTenant(t.id, member) },
                overlapping = { handler.createGroup(member, t.id, scopeId, NAME, GRANT, digest) },
            )
            assertEquals(CreateGroupOutcome.NoTenant, outcome, "round $round: a removed member created a group")
            assertNull(tx { ScopeEntity.findById(scopeId) }, "round $round: nothing written")
            val control = handler.createGroup(t.owner, t.id, UUID.randomUUID().toString(), NAME, GRANT, TestGrants.digestOf(t.owner))
            assertTrue(control is CreateGroupOutcome.Created, "round $round: control $control")
        }
    }

    @Test
    fun aPersonalSpaceCreatedByAMemberBeingRemovedFromTheTenantIsRefused() {
        raceRounds { round ->
            val t = Tenant()
            val member = t.member()
            val scopeId = UUID.randomUUID().toString()
            val digest = TestGrants.digestOf(member)
            val outcome = whileUncommitted(
                change = { removeFromTenant(t.id, member) },
                overlapping = { handler.createPersonal(member, t.id, scopeId, GRANT, digest) },
            )
            assertEquals(CreatePersonalOutcome.NoTenant, outcome, "round $round: a removed member created a personal space")
            assertNull(tx { ScopeEntity.findById(scopeId) }, "round $round: nothing written")
            val control = handler.createPersonal(t.owner, t.id, UUID.randomUUID().toString(), GRANT, TestGrants.digestOf(t.owner))
            assertTrue(control is CreatePersonalOutcome.Created, "round $round: control $control")
        }
    }

    @Test
    fun aMemberBeingRemovedFromTheTenantIsNotAddedToAGroup() {
        raceRounds { round ->
            val t = Tenant()
            val target = t.member(username = "target")
            val group = t.group()
            val outcome = whileUncommitted(
                change = { removeFromTenant(t.id, target) },
                overlapping = { handler.addGroupMember(t.owner, group, "target", null, GRANT, 0, TestGrants.digestOf(target)) },
            )
            assertEquals(AddGroupMemberOutcome.MemberNotFound, outcome, "round $round: a removed member was added")
            assertEquals(0L, tx { MembershipEntity.count("userId = ?1 and scopeId = ?2", target, group) }, "round $round")
            val stays = t.member(username = "stays")
            val control = handler.addGroupMember(t.owner, group, "stays", null, GRANT, 0, TestGrants.digestOf(stays))
            assertTrue(control is AddGroupMemberOutcome.Added, "round $round: control $control")
        }
    }

    @Test
    fun anAcceptOverlappingTheRemovalThatTombstonesItsGroupFindsNoInvitation() {
        // inbox #599: the group's only accepted member is removed from the tenant. The removal reads the group as theirs
        // alone, so it tombstones it and expires its proposals. An accept that committed between that read and the
        // expiry survived it — an accepted member of a group in its deletion grace, with no owner left to restore it.
        raceRounds { round ->
            val t = Tenant()
            val leaver = t.member()
            val invitee = t.member()
            val group = t.proposedGroup(owner = leaver, proposed = invitee)
            lateinit var steps: List<CascadeStep>
            val outcome = whileUncommitted(
                change = { steps = planRemoval(t.id, leaver) },
                overlapping = { handler.acceptInvitation(invitee, group) },
                beforeCommit = { executeRemoval(steps, leaver) },
            )
            assertEquals(
                CascadeStep(group, CascadeAction.SOFT_DELETE, rotateAfter = false, expireProposals = true),
                steps.single { it.scopeId == group }, "round $round: the removal read the group as the leaver's own",
            )
            assertEquals(
                0L, tx { MembershipEntity.count("userId = ?1 and scopeId = ?2", invitee, group) },
                "round $round: the invitee survived the tombstone as a member",
            )
            assertEquals(AcceptInvitationOutcome.NotFound, outcome, "round $round: an accept joined a group being tombstoned")
            assertTrue(tx { ScopeEntity.findById(group)!!.deletedAt } != null, "round $round: the group is tombstoned")
            // Control: an accept on a group the removal does not touch lands.
            val other = t.proposedGroup(owner = t.owner, proposed = invitee)
            assertEquals(AcceptInvitationOutcome.Ok, handler.acceptInvitation(invitee, other), "round $round: control")
        }
    }

    @Test
    fun anAcceptOverlappingTheInviteesOwnRemovalFindsNoInvitation() {
        // inbox #599, the invitee's side: the removal reads the invitee's row on the group as a proposal and drops it as
        // a decline would (DROP_PROPOSAL). An accept that committed between that read and the delete was no proposal any
        // more, so the delete took nothing — and a person removed from the tenant stayed an accepted member of its group.
        raceRounds { round ->
            val t = Tenant()
            val invitee = t.member()
            val group = t.proposedGroup(owner = t.owner, proposed = invitee)
            lateinit var steps: List<CascadeStep>
            val outcome = whileUncommitted(
                change = { steps = planRemoval(t.id, invitee) },
                overlapping = { handler.acceptInvitation(invitee, group) },
                beforeCommit = { executeRemoval(steps, invitee) },
            )
            assertEquals(
                CascadeAction.DROP_PROPOSAL, steps.single { it.scopeId == group }.action,
                "round $round: the removal read the invitee's row as a proposal",
            )
            assertEquals(
                0L, tx { MembershipEntity.count("userId = ?1 and scopeId = ?2", invitee, group) },
                "round $round: a member removed from the tenant kept the group",
            )
            assertEquals(AcceptInvitationOutcome.NotFound, outcome, "round $round: a removed member accepted a group")
            // Control: another member's accept on the same group lands.
            val stays = t.member()
            t.propose(group, t.owner, stays)
            assertEquals(AcceptInvitationOutcome.Ok, handler.acceptInvitation(stays, group), "round $round: control")
        }
    }

    @Inject lateinit var scopes: ScopesStore

    @Inject lateinit var handler: ScopesHandler

    // --- helpers ---

    /** A tenant removal's locks and its plan read, as `TenantHandler.removeMember` makes them: the target's scope rows
     *  locked in ascending id order, then the memberships read and planned under them. The hold comes after this — the
     *  window between the plan's read and the writes it decided ([executeRemoval]). */
    private fun planRemoval(tenantId: String, target: String): List<CascadeStep> {
        scopes.scopeIdsInTenant(target, tenantId).sorted().forEach { scopes.lockScope(it) }
        val rows = scopes.membershipsInTenant(target, tenantId)
            .map { RemovalScope(it.scopeId, it.kind, it.role, it.acceptedMembers, it.ownerCount, it.pending) }
        return (RemovalPolicy.plan(rows, tenantId) as RemovalPlan.Cascade).steps
    }

    /** The writes of the planned steps, through the store calls `TenantHandler.execute` makes for them (the counted
     *  removal, the links and the queued rotation left out: nothing here reads them). */
    private fun executeRemoval(steps: List<CascadeStep>, target: String) {
        steps.forEach { step ->
            if (step.expireProposals) scopes.expireProposals(step.scopeId)
            when (step.action) {
                CascadeAction.SOFT_DELETE -> scopes.softDelete(step.scopeId, Instant.now())
                CascadeAction.REMOVE -> scopes.removeMembership(target, step.scopeId)
                CascadeAction.DROP_PROPOSAL -> scopes.deletePendingMembership(target, step.scopeId)
                CascadeAction.TRANSFER_THEN_REMOVE -> error("no hand-over in these races")
            }
        }
    }

    /** A tenant removal's first two writes, as `TenantHandler.removeMember` makes them: the tenant root's lock, then
     *  the membership's delete (see the class doc for why not the handler itself). */
    private fun removeFromTenant(tenantId: String, member: String) {
        scopes.lockScope(tenantId)
        scopes.removeMembership(member, tenantId)
    }

    private fun <T> tx(body: () -> T): T = QuarkusTransaction.requiringNew().call(body)
}
