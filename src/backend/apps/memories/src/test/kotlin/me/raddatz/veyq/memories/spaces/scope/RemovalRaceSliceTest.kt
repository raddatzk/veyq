package me.raddatz.veyq.memories.spaces.scope

import me.raddatz.veyq.core.sharing.ShareEntity
import me.raddatz.veyq.memories.itemfavorites.ItemFavoritesEntity
import me.raddatz.veyq.core.sharing.ShareWriteProof
import me.raddatz.veyq.core.TestIds
import me.raddatz.veyq.core.identity.TenantProfileEntity
import me.raddatz.veyq.core.membership.MembershipEntity
import me.raddatz.veyq.core.membership.SignedGrant
import me.raddatz.veyq.core.membership.TenantRoots
import me.raddatz.veyq.core.membership.TestAdmission
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.scope.ScopeEntity
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.core.testing.TestFixtures.seedTenant
import me.raddatz.veyq.core.testing.TestFixtures.seedUser
import me.raddatz.veyq.core.testing.whileUncommitted
import me.raddatz.veyq.core.testing.raceRounds
import me.raddatz.veyq.memories.node.MemoriesNodeKinds
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import java.time.Instant
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import me.raddatz.veyq.core.spaces.scope.RotationDecision
import me.raddatz.veyq.core.spaces.scope.RotationGrant
import me.raddatz.veyq.core.spaces.scope.ScopesStore

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
 * **Split** (cr-platform-gradle-modules step 8a): the methods that run with core alone are `RemovalRaceCoreSliceTest` in `:core`.
 */
@QuarkusTest
class RemovalRaceSliceTest {

    @Inject lateinit var scopes: ScopesStore

    @Test
    fun aLinkCreatedByAMemberBeingRemovedIsRefused() {
        raceRounds { round ->
            val t = Tenant()
            val member = t.member()
            val group = t.group(member)
            val memory = seedNode(group, MemoriesNodeKinds.MEMORY, parentId = group, wrappedContentKey = CONTENT_KEY)
            val id = TestIds.shareId()
            val status = whileUncommitted(
                change = { removeFromGroup(t.owner, group, member, round) },
                overlapping = { postShare(member, memory, id) },
            )
            assertEquals(404, status, "round $round: a removed member created a link")
            assertNull(tx { ShareEntity.findById(id) }, "round $round: nothing written")
            // Control: a member who stays shares the same Memory, with a copy at the epoch the removal reached — once the
            // Memory is re-keyed to it (ADR 0109 point 6: a node the rotation has not re-keyed takes no new link).
            rekeyed(memory, 1)
            assertEquals(201, postShare(t.owner, memory, TestIds.shareId(), epoch = 1, nodeEpoch = 1), "round $round: control")
        }
    }

    @Test
    fun aLinkReissuedByAMemberBeingRemovedIsRefused() {
        raceRounds { round ->
            val t = Tenant()
            val member = t.member()
            val group = t.group(member)
            val memory = seedNode(group, MemoriesNodeKinds.MEMORY, parentId = group, wrappedContentKey = CONTENT_KEY)
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
            assertFalse(tx { ShareEntity.findById(old)!!.deleted }, "round $round: the old link is not spent")
            // Control: a member who stays re-issues it (the removal moved the epoch to 1), once the Memory is re-keyed to it.
            rekeyed(memory, 1)
            assertEquals(201, reissue(t.owner, old, TestIds.shareId(), epoch = 1, nodeEpoch = 1), "round $round: control")
        }
    }

    /**
     * The member also holds a favorite written before the removal, in the group and in the tenant root: the removal
     * deletes the group's (the removal-rotation's `MembershipEraser` call — without it the count below is 1) and leaves
     * the tenant root's, which the member keeps (the positive control on the same `(userId, scopeId)` delete).
     */
    @Test
    fun anItemFavoriteOfAMemberBeingRemovedIsRefused() {
        raceRounds { round ->
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

    // --- helpers ---

    /** The real removal-rotation of [member] out of [group], to epoch 1 — the one way a keyed member leaves. */
    private fun removeFromGroup(owner: String, group: String, member: String, round: Int) {
        val decision = scopes.rotate(
            group, 1, "cHJldg==", owner, listOf(RotationGrant(owner, GRANT)), emptyList(), emptySet(), removes = member,
        )
        assertTrue(decision is RotationDecision.Commit, "round $round: $decision")
    }

    /** [nodeId]'s re-key reached [epoch]: its key is minted at it (the re-key's own commit is `RekeyCoreSliceTest`'s). */
    private fun rekeyed(nodeId: String, epoch: Int) = tx { NodeEntity.findById(nodeId)!!.epoch = epoch }

    private fun postShare(userId: String, nodeId: String, id: String, epoch: Int = 0, nodeEpoch: Int = 0): Int =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"shareId":"$id",${ShareWriteProof.field(id)},"nodeId":"$nodeId","role":"READ","wrap":"$WRAP","sealedName":"$NAME","sealedShareKey":"$NAME","nodeEpoch":$nodeEpoch,"sealedShareKeyEpoch":$epoch}""")
            .`when`().post("/api/shares").statusCode()

    private fun reissue(userId: String, shareId: String, newId: String, epoch: Int = 0, nodeEpoch: Int = 0): Int =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"shareId":"$newId",${ShareWriteProof.field(newId)},"wrap":"$WRAP","sealedName":"$NAME","sealedShareKey":"$NAME","nodeEpoch":$nodeEpoch,"sealedShareKeyEpoch":$epoch}""")
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
    }

    private companion object {
        const val NAME = "c2VhbGVkLW5hbWU="
        const val WRAP = "d3JhcC1rLW1lbS11bmRlci1zaGFyZQ=="
        val GRANT = SignedGrant("Z3JhbnQ=", TestAdmission.SIGNATURE)
    }
}
