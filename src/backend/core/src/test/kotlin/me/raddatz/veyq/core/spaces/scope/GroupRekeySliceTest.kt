package me.raddatz.veyq.core.spaces.scope

import me.raddatz.veyq.core.membership.MembershipEntity
import me.raddatz.veyq.core.membership.TestGrants
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.scope.ScopeEntity
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.core.testing.TestFixtures.seedScopeMember
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.equalTo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A group owner's explicit "re-key this space" (ADR 0025 § 1, uc.spaces.manage-group-members A6): a plain rotation of
 * the GROUP scope with nobody removed in it, for the compromise case — a member's lost device. It takes the rotation path
 * every rotation takes, so this pins that the server takes the request a group owner's client builds without a
 * `removesUserId`: the members it anchors re-granted, the one it cannot declared pending, every membership kept, the
 * re-key of the nodes left to every member, and the commit owner-only — a co-owner as well, a plain member never.
 */
@QuarkusTest
class GroupRekeySliceTest {

    @Inject lateinit var scopes: ScopesStore

    @Test
    fun anOwnerReKeysTheGroupWithNobodyRemovedGrantingWhomItAnchorsAndDeclaringTheRest() {
        val group = seedMemberScope(ScopeEntity.GROUP)
        val owner = group.userId
        val groupId = group.scopeId
        val kept = seedScopeMember(groupId, grantedEpoch = 0)
        val unanchored = seedScopeMember(groupId, grantedEpoch = 0)
        seedNode(groupId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)

        // One member neither granted nor declared → refused, nothing stored: the cover the server requires is real.
        rekey(owner, groupId, granted = listOf(owner, kept), pending = emptyList())
            .then().statusCode(409).body("code", equalTo("rotation_incomplete"))
        assertEquals(0, TestGrants.epochOf(groupId), "the refused re-key stored no epoch")

        // Control on the same predicate: the unanchored member declared pending → the next epoch commits, no removal in it.
        rekey(owner, groupId, granted = listOf(owner, kept), pending = listOf(unanchored))
            .then().statusCode(201).body("epoch", equalTo(1))
        assertEquals(1, TestGrants.epochOf(groupId))

        assertEquals(listOf<Any?>(REGRANT, 1), grantOf(owner, groupId), "the rotator holds the new epoch")
        assertEquals(listOf<Any?>(REGRANT, 1), grantOf(kept, groupId), "an anchored member holds the new epoch")
        assertEquals(0, grantOf(unanchored, groupId)!![1], "a declared member keeps its grant, behind")
        assertEquals(setOf(owner, kept, unanchored), membersOf(groupId), "a re-key removes nobody")

        given().header("Authorization", bearer(owner)).`when`().get("/api/scopes/$groupId/members").then().statusCode(200)
            .body("find { it.userId == '$unanchored' }.pending", equalTo(true))
            .body("find { it.userId == '$kept' }.pending", equalTo(false))
        // The node still carries the old epoch: every member's row reads `rekeying`, so any member continues the re-key.
        assertEquals(true, rowRekeying(kept, group.tenantId, groupId))
    }

    @Test
    fun aPlainMemberCannotReKeyTheGroupAndACoOwnerCan() {
        val group = seedMemberScope(ScopeEntity.GROUP)
        val owner = group.userId
        val groupId = group.scopeId
        val member = seedScopeMember(groupId, grantedEpoch = 0)
        val coOwner = seedScopeMember(groupId, grantedEpoch = 0)
        QuarkusTransaction.requiringNew().run {
            MembershipEntity.find("userId = ?1 and scopeId = ?2", coOwner, groupId).firstResult()!!.role = MembershipEntity.OWNER
        }
        val everyone = listOf(owner, member, coOwner)

        rekey(member, groupId, granted = everyone, pending = emptyList())
            .then().statusCode(403).body("code", equalTo("not_scope_owner"))
        assertEquals(0, TestGrants.epochOf(groupId), "nothing stored")

        // Control: a co-owner's identical request commits — any owner of the group may give it a new lock.
        rekey(coOwner, groupId, granted = everyone, pending = emptyList()).then().statusCode(201).body("epoch", equalTo(1))
    }

    // --- helpers ---

    /** The group re-key to `E_1` by [caller]: [REGRANT] to each of [granted] (naming their current key), [pending]
     *  declared — no `removesUserId`, no invites (a group has none). */
    private fun rekey(caller: String, groupId: String, granted: List<String>, pending: List<String>) =
        given().header("Authorization", bearer(caller)).contentType(ContentType.JSON)
            .body(
                """{"epoch":1,"wrappedPrev":"cHJldg==",""" +
                    """"grants":[${granted.joinToString(",") {
                        """{"userId":"$it","wrappedGrant":"$REGRANT",${TestGrants.SIGNATURE_JSON},"recipientIdentityDigest":"${TestGrants.digestOf(it)}"}"""
                    }}],""" +
                    """"pendingUserIds":[${pending.joinToString(",") { "\"$it\"" }}]}""",
            )
            .`when`().post("/api/scopes/$groupId/epochs")

    /** [userId]'s grant on [scopeId] and its epoch; null when they hold no membership there. */
    private fun grantOf(userId: String, scopeId: String): List<Any?>? = QuarkusTransaction.requiringNew().call {
        MembershipEntity.find("userId = ?1 and scopeId = ?2", userId, scopeId).firstResult()?.let { listOf(it.wrappedGrant, it.grantedEpoch) }
    }

    private fun membersOf(scopeId: String): Set<String> =
        QuarkusTransaction.requiringNew().call { MembershipEntity.find("scopeId", scopeId).list().map { it.userId }.toSet() }

    /** [userId]'s row of [scopeId] as a fresh read. */
    private fun rowRekeying(userId: String, tenantId: String, scopeId: String): Boolean? =
        QuarkusTransaction.requiringNew().call { scopes.listMine(userId, tenantId).single { it.id == scopeId }.rekeying }

    private companion object {
        /** The new epoch's stand-in grant ciphertext. */
        const val REGRANT = "bmV3LWdyb3VwLWVwb2No"
    }
}
