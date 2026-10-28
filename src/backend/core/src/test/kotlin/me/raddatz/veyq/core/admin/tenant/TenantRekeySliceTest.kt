package me.raddatz.veyq.core.admin.tenant

import me.raddatz.veyq.core.crypto.Tokens
import me.raddatz.veyq.core.membership.MembershipEntity
import me.raddatz.veyq.core.membership.TestGrants
import me.raddatz.veyq.core.membership.TestInviteKey
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.spaces.scope.ScopesStore
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.IDENTITY_PUBLIC_KEY
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.core.testing.TestFixtures.seedScopeMember
import me.raddatz.veyq.core.testing.TestFixtures.seedTenant
import me.raddatz.veyq.core.testing.TestFixtures.seedUser
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.equalTo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * A tenant owner's explicit "re-key this space" (ADR 0025 § 2, uc.spaces.remove-tenant-member A8-A9): a plain rotation of the TENANT
 * scope, with nobody removed in it — after a removal cascade or on its own. It takes the rotation path every rotation
 * takes, so this pins that the server takes the whole request a tenant owner's client builds: the members it anchors
 * re-granted, the one it cannot declared pending, every open invite re-granted or declared, the member the cascade
 * removed absent and given nothing, the re-key of the nodes left to every member, and the commit owner-only. Distinct
 * domains per test (the H2 database lives as long as the JVM).
 */
@QuarkusTest
class TenantRekeySliceTest {

    @Inject lateinit var store: TenantStore

    @Inject lateinit var scopes: ScopesStore

    @Test
    fun anOwnerReKeysTheTenantAfterARemovalGrantingWhoRemainsAndDeclaringTheRest() {
        val owner = seedUser(email = "owner@rk-main.example.com")
        val tenantId = seedTenant(domain = "rk-main.example.com", ownerEmail = "owner@rk-main.example.com", owner = owner)
        val kept = seedScopeMember(tenantId, grantedEpoch = 0)
        val unanchored = seedScopeMember(tenantId, grantedEpoch = 0)
        val removed = seedScopeMember(tenantId, grantedEpoch = 0)
        val vouched = seedInvite(tenantId, owner, "v@rk-main.example.com")
        val unvouched = seedInvite(tenantId, owner, "u@rk-main.example.com")
        seedNode(tenantId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)

        // The removal first, by the cascade: it revokes access and moves no epoch (ADR 0025 § 2).
        given().header("Authorization", bearer(owner))
            .`when`().delete("/api/tenants/$tenantId/members/$removed").then().statusCode(204)
        assertEquals(0, TestGrants.epochOf(tenantId), "a removal alone rotates nothing")

        // One member neither granted nor declared → refused, nothing stored: the cover the server requires is real.
        rekey(owner, tenantId, granted = listOf(owner, kept), pending = emptyList(), inviteGrants = listOf(vouched), pendingInvites = listOf(unvouched))
            .then().statusCode(409).body("code", equalTo("rotation_incomplete"))
        assertEquals(0, TestGrants.epochOf(tenantId), "the refused re-key stored no epoch")

        // Control on the same predicate: the unanchored member declared pending → commits the next epoch. A grant the
        // request addresses to the removed member is not a membership: it is dropped, and they hold nothing after.
        rekey(owner, tenantId, granted = listOf(owner, kept, removed), pending = listOf(unanchored), inviteGrants = listOf(vouched), pendingInvites = listOf(unvouched))
            .then().statusCode(201).body("epoch", equalTo(1))
        assertEquals(1, TestGrants.epochOf(tenantId))

        assertEquals(listOf<Any?>(REGRANT, 1), grantOf(owner, tenantId), "the rotator holds the new epoch")
        assertEquals(listOf<Any?>(REGRANT, 1), grantOf(kept, tenantId), "an anchored member holds the new epoch")
        assertEquals(0, grantOf(unanchored, tenantId)!![1], "a declared member keeps its grant, behind")
        assertNull(grantOf(removed, tenantId), "the removed member holds no membership and no grant")
        assertEquals(listOf<Any?>(REGRANT, 1), inviteGrantOf(vouched.inviteId), "a vouched invite carries the new epoch")
        assertEquals(listOf<Any?>(TestGrants.WRAPPED, 0), inviteGrantOf(unvouched.inviteId), "a declared invite keeps its grant")

        // The member list shows the declared member as pending — what grant delivery brings current.
        given().header("Authorization", bearer(owner)).`when`().get("/api/scopes/$tenantId/members").then().statusCode(200)
            .body("find { it.userId == '$unanchored' }.pending", equalTo(true))
            .body("find { it.userId == '$kept' }.pending", equalTo(false))
        // The node still carries the old epoch: every member's row reads `rekeying`, so any member continues the re-key.
        assertEquals(true, rowRekeying(kept, tenantId))
        // The removed member is outside the scope now: the epoch chain answers as for a scope that does not exist.
        given().header("Authorization", bearer(removed)).`when`().get("/api/scopes/$tenantId/epochs")
            .then().statusCode(404).body("code", equalTo("scope_not_found"))
    }

    @Test
    fun aMemberWhoIsNotAnOwnerCannotReKeyTheTenant() {
        val owner = seedUser(email = "owner@rk-gate.example.com")
        val tenantId = seedTenant(domain = "rk-gate.example.com", ownerEmail = "owner@rk-gate.example.com", owner = owner)
        val member = seedScopeMember(tenantId, grantedEpoch = 0)

        rekey(member, tenantId, granted = listOf(owner, member), pending = emptyList())
            .then().statusCode(403).body("code", equalTo("not_scope_owner"))
        assertEquals(0, TestGrants.epochOf(tenantId), "nothing stored")

        // Control: the owner's identical request commits.
        rekey(owner, tenantId, granted = listOf(owner, member), pending = emptyList()).then().statusCode(201).body("epoch", equalTo(1))
    }

    // --- helpers ---

    /** The tenant re-key to `E_1` by [caller]: [REGRANT] to each of [granted] (naming their current key), [pending]
     *  declared, [inviteGrants] re-granted to their invite keys, [pendingInvites] declared — no `removesUserId`. */
    private fun rekey(
        caller: String, tenantId: String, granted: List<String>, pending: List<String>,
        inviteGrants: List<TestInviteKey> = emptyList(), pendingInvites: List<TestInviteKey> = emptyList(),
    ) = given().header("Authorization", bearer(caller)).contentType(ContentType.JSON)
        .body(
            """{"epoch":1,"wrappedPrev":"cHJldg==",""" +
                """"grants":[${granted.joinToString(",") {
                    """{"userId":"$it","wrappedGrant":"$REGRANT",${TestGrants.SIGNATURE_JSON},"recipientIdentityDigest":"${TestGrants.digestOf(it)}"}"""
                }}],""" +
                """"pendingUserIds":[${pending.joinToString(",") { "\"$it\"" }}],""" +
                """"inviteGrants":[${inviteGrants.joinToString(",") {
                    """{"inviteId":"${it.inviteId}","wrappedGrant":"$REGRANT",${TestGrants.SIGNATURE_JSON},"recipientIdentityDigest":"${TestGrants.digest(it.bundle)}"}"""
                }}],""" +
                """"pendingInviteIds":[${pendingInvites.joinToString(",") { "\"${it.inviteId}\"" }}]}""",
        )
        .`when`().post("/api/scopes/$tenantId/epochs")

    /** A MEMBER invite to [email] signed by [signer], its grant of `E_0`, stored as the handler does. */
    private fun seedInvite(tenantId: String, signer: String, email: String): TestInviteKey {
        val key = TestInviteKey()
        val minted = QuarkusTransaction.requiringNew().call {
            store.mintMemberInvite(
                tenantId, email, Tokens.hash("rk-${UUID.randomUUID()}"), Instant.now().plus(7, ChronoUnit.DAYS),
                key.stored(signer, IDENTITY_PUBLIC_KEY),
            )
        }
        assertEquals(MintMemberInvite.Minted(key.inviteId), minted)
        return key
    }

    /** [userId]'s grant on [scopeId] and its epoch; null when they hold no membership there. */
    private fun grantOf(userId: String, scopeId: String): List<Any?>? = QuarkusTransaction.requiringNew().call {
        MembershipEntity.find("userId = ?1 and scopeId = ?2", userId, scopeId).firstResult()?.let { listOf(it.wrappedGrant, it.grantedEpoch) }
    }

    private fun inviteGrantOf(inviteId: String): List<Any?> =
        QuarkusTransaction.requiringNew().call { TenantInviteEntity.findById(inviteId)!!.let { listOf(it.wrappedGrant, it.grantedEpoch) } }

    /** [userId]'s tenant row as a fresh read. */
    private fun rowRekeying(userId: String, tenantId: String): Boolean? =
        QuarkusTransaction.requiringNew().call { scopes.listMine(userId, tenantId).single { it.id == tenantId }.rekeying }

    private companion object {
        /** The new epoch's stand-in grant ciphertext. */
        const val REGRANT = "bmV3LXRlbmFudC1lcG9jaA=="
    }
}
