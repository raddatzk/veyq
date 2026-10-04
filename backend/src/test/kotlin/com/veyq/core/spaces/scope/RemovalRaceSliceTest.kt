package com.veyq.core.spaces.scope

import com.veyq.core.node.NodeEntity
import com.veyq.core.sharing.ShareEntity
import com.veyq.memories.itemfavorites.ItemFavoritesEntity
import com.veyq.core.sharing.ShareWriteProof
import com.veyq.core.TestIds
import com.veyq.core.identity.TenantProfileEntity
import com.veyq.core.membership.MembershipEntity
import com.veyq.core.membership.SignedGrant
import com.veyq.core.membership.TenantRoots
import com.veyq.core.membership.TestAdmission
import com.veyq.core.scope.ScopeEntity
import com.veyq.core.testing.RACE_ROUNDS
import com.veyq.core.testing.TestFixtures.CONTENT_KEY
import com.veyq.core.testing.TestFixtures.bearer
import com.veyq.core.testing.TestFixtures.seedNode
import com.veyq.core.testing.TestFixtures.seedTenant
import com.veyq.core.testing.TestFixtures.seedUser
import com.veyq.core.testing.whileUncommitted
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
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
 */
@QuarkusTest
class RemovalRaceSliceTest {

    @Inject lateinit var scopes: ScopesStore
    @Inject lateinit var handler: ScopesHandler

    @Test
    fun aLinkCreatedByAMemberBeingRemovedIsRefused() {
        repeat(RACE_ROUNDS) { round ->
            val t = Tenant()
            val member = t.member()
            val group = t.group(member)
            val memory = seedNode(group, NodeEntity.MEMORY, parentId = group, wrappedContentKey = CONTENT_KEY)
            val id = TestIds.shareId()
            val status = whileUncommitted(
                change = { removeFromGroup(t.owner, group, member, round) },
                overlapping = { postShare(member, memory, id) },
            )
            assertEquals(404, status, "round $round: a removed member created a link")
            assertNull(tx { ShareEntity.findById(id) }, "round $round: nothing written")
            // Control: a member who stays shares the same Memory, with a copy at the epoch the removal reached.
            assertEquals(201, postShare(t.owner, memory, TestIds.shareId(), epoch = 1), "round $round: control")
        }
    }

    @Test
    fun aLinkReissuedByAMemberBeingRemovedIsRefused() {
        repeat(RACE_ROUNDS) { round ->
            val t = Tenant()
            val member = t.member()
            val group = t.group(member)
            val memory = seedNode(group, NodeEntity.MEMORY, parentId = group, wrappedContentKey = CONTENT_KEY)
            // A disabled link is any member's to re-issue (ADR 0075), so the member being removed may start one.
            val old = TestIds.shareId()
            assertEquals(201, postShare(t.owner, memory, old))
            given().header("Authorization", bearer(t.owner)).`when`().post("/api/shares/$old/revoke").then().statusCode(204)
            val fresh = TestIds.shareId()
            val status = whileUncommitted(
                change = { removeFromGroup(t.owner, group, member, round) },
                overlapping = { reissue(member, old, fresh) },
            )
            assertEquals(404, status, "round $round: a removed member re-issued a link")
            assertNull(tx { ShareEntity.findById(fresh) }, "round $round: no new link")
            assertNull(tx { ShareEntity.findById(old)!!.deletedAt }, "round $round: the old link is not spent")
            // Control: a member who stays re-issues it (the removal moved the epoch to 1).
            assertEquals(201, reissue(t.owner, old, TestIds.shareId(), epoch = 1), "round $round: control")
        }
    }

    /**
     * The member also holds a favorite written before the removal, in the group and in the tenant root: the removal
     * deletes the group's (the removal-rotation's `MembershipEraser` call — without it the count below is 1) and leaves
     * the tenant root's, which the member keeps (the positive control on the same `(userId, scopeId)` delete).
     */
    @Test
    fun anItemFavoriteOfAMemberBeingRemovedIsRefused() {
        repeat(RACE_ROUNDS) { round ->
            val t = Tenant()
            val member = t.member()
            val group = t.group(member)
            val node = UUID.randomUUID().toString()
            val earlier = UUID.randomUUID().toString()
            assertEquals(204, putItemFavorite(member, group, earlier), "round $round: the member's earlier favorite")
            assertEquals(204, putItemFavorite(member, t.id, earlier), "round $round: the member's favorite elsewhere")
            val status = whileUncommitted(
                change = { removeFromGroup(t.owner, group, member, round) },
                overlapping = { putItemFavorite(member, group, node) },
            )
            assertEquals(404, status, "round $round: a removed member wrote an item favorite")
            assertEquals(0L, tx { ItemFavoritesEntity.count("userId = ?1 and scopeId = ?2", member, group) }, "round $round")
            assertEquals(1L, tx { ItemFavoritesEntity.count("userId = ?1 and scopeId = ?2", member, t.id) },
                "round $round: the removal took the member's favorite in another scope")
            assertEquals(204, putItemFavorite(t.owner, group, node), "round $round: control")
        }
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

    // --- helpers ---

    /** The real removal-rotation of [member] out of [group], to epoch 1 — the one way a keyed member leaves. */
    private fun removeFromGroup(owner: String, group: String, member: String, round: Int) {
        val decision = scopes.rotate(
            group, 1, "cHJldg==", owner, listOf(RotationGrant(owner, GRANT)), emptyList(), emptySet(), removes = member,
        )
        assertTrue(decision is RotationDecision.Commit, "round $round: $decision")
    }

    /** A tenant removal's first two writes, as `TenantHandler.removeMember` makes them: the tenant root's lock, then
     *  the membership's delete (see the class doc for why not the handler itself). */
    private fun removeFromTenant(tenantId: String, member: String) {
        scopes.lockScope(tenantId)
        scopes.removeMembership(member, tenantId)
    }

    private fun postShare(userId: String, nodeId: String, id: String, epoch: Int = 0): Int =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"shareId":"$id",${ShareWriteProof.field(id)},"nodeId":"$nodeId","role":"VIEWER","wrap":"$WRAP","sealedName":"$NAME","sealedShareKey":"$NAME","sealedShareKeyEpoch":$epoch}""")
            .`when`().post("/api/shares").statusCode()

    private fun reissue(userId: String, shareId: String, newId: String, epoch: Int = 0): Int =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"shareId":"$newId",${ShareWriteProof.field(newId)},"wrap":"$WRAP","sealedName":"$NAME","sealedShareKey":"$NAME","sealedShareKeyEpoch":$epoch}""")
            .`when`().post("/api/shares/$shareId/reissue").statusCode()

    private fun putItemFavorite(userId: String, scopeId: String, nodeId: String): Int =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"ciphertext":"$NAME"}""")
            .`when`().put("/api/scopes/$scopeId/nodes/$nodeId/item-favorites").statusCode()

    private fun <T> tx(body: () -> T): T = QuarkusTransaction.requiringNew().call(body)

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
}
