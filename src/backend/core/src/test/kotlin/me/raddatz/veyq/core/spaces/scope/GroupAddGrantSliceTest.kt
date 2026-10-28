package me.raddatz.veyq.core.spaces.scope

import me.raddatz.veyq.core.identity.TenantProfileEntity
import me.raddatz.veyq.core.membership.MembershipEntity
import me.raddatz.veyq.core.membership.TenantRoots
import me.raddatz.veyq.core.membership.TestGrants
import me.raddatz.veyq.core.tasks.CapabilityTags
import me.raddatz.veyq.core.tasks.JobEntity
import me.raddatz.veyq.core.testing.TestFixtures.IDENTITY_PUBLIC_KEY
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedTenant
import me.raddatz.veyq.core.testing.TestFixtures.seedUser
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.nullValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * A group add carries the grant (cr-invite-is-a-placeholder-member, ADR 0111 decision 4): the group owner wraps the
 * group's current epoch key to the target's identity key and the membership — direct or a consent proposal — holds it
 * from the add on, so nobody waits for a key-holder; a proposal the target declines held the key, so the group's
 * rotation is queued. Every refusal is anchored on its status and code beside a control that passes.
 */
@QuarkusTest
class GroupAddGrantSliceTest {

    @Test
    fun aGroupAddCarriesTheGrant() {
        val g = world()
        val target = g.member("target")
        add(g, "target", TestGrants.groupAddJson(g.groupId, target))
            .then().statusCode(201)
            .body("userId", equalTo(target))
            .body("pendingConsent", equalTo(false))
            .body("hasGrant", nullValue())
            .body("sealedJoinRecord", nullValue())

        val m = membership(target, g.groupId)!!
        assertEquals(
            listOf<Any?>(MembershipEntity.MEMBER, TestGrants.WRAPPED, 0, TestGrants.SIGNATURE, g.owner, IDENTITY_PUBLIC_KEY),
            listOf(m.role, m.wrappedGrant, m.grantedEpoch, m.grantSignature, m.granterUserId, m.granterIdentityPublicKey),
            "the owner's grant, of the current epoch, the owner its granter with the key they hold",
        )
        // A group's listing carries no join record: that is the tenant's (the TENANT scope's listing only).
        given().header("Authorization", bearer(g.owner)).`when`().get("/api/scopes/${g.groupId}/members")
            .then().statusCode(200)
            .body("find { it.userId == '$target' }.sealedJoinRecord", nullValue())
            .body("find { it.userId == '$target' }.hasGrant", nullValue())
    }

    @Test
    fun aGroupAddAtAStaleEpochIsRefused() {
        val g = world()
        val target = g.member("late")
        rotate(g).then().statusCode(201)
        add(g, "late", TestGrants.grantJson(0, TestGrants.digestOf(target)))
            .then().statusCode(409).body("code", equalTo("stale_epoch"))
        assertEquals(null, membership(target, g.groupId), "nothing written")
        // Control: the epoch the rotation reached.
        add(g, "late", TestGrants.grantJson(1, TestGrants.digestOf(target))).then().statusCode(201)
        assertEquals(1, membership(target, g.groupId)!!.grantedEpoch)
    }

    @Test
    fun aGroupAddNamingAnotherKeyIsRefused() {
        // The grant names the key it is wrapped to (ADR 0111): it must be the target's current identity.
        val g = world()
        val target = g.member("other")
        add(g, "other", TestGrants.grantJson(0, TestGrants.digest(TestGrants.WRAPPED)))
            .then().statusCode(409).body("code", equalTo("recipient_key_not_current"))
        assertEquals(null, membership(target, g.groupId), "nothing written")
        // Control: the digest of the target's key.
        add(g, "other", TestGrants.grantJson(0, TestGrants.digestOf(target))).then().statusCode(201)
    }

    @Test
    fun aDeclinedGroupInvitationQueuesTheGroupsRotation() {
        // The proposal held the group's key (ADR 0111 decision 4), so a decline is a removal: the group rotates.
        val g = world()
        val target = g.member("declines", consent = true)
        add(g, "declines", """"sealedNameKey":"$NAME_KEY",${TestGrants.groupAddJson(g.groupId, target)}""")
            .then().statusCode(201).body("pendingConsent", equalTo(true))
        assertEquals(TestGrants.WRAPPED, membership(target, g.groupId)!!.wrappedGrant, "the proposal holds the grant")

        given().header("Authorization", bearer(target)).`when`().post("/api/scopes/${g.groupId}/invitations/decline")
            .then().statusCode(204)
        assertEquals(null, membership(target, g.groupId), "the proposal is gone")
        assertEquals(1L, rotationJobs(g.groupId), "the declined key's group rotation is queued")

        // Control: a second group whose proposal is ACCEPTED queues nothing — the member keeps the key.
        val h = world()
        val accepts = h.member("accepts", consent = true)
        add(h, "accepts", """"sealedNameKey":"$NAME_KEY",${TestGrants.groupAddJson(h.groupId, accepts)}""").then().statusCode(201)
        given().header("Authorization", bearer(accepts)).`when`().post("/api/scopes/${h.groupId}/invitations/accept")
            .then().statusCode(204)
        assertEquals(0L, rotationJobs(h.groupId), "an accepted proposal queues no rotation")
        // …and a decline with no proposal open (here: after the accept) is the silent no-op it always was.
        given().header("Authorization", bearer(accepts)).`when`().post("/api/scopes/${h.groupId}/invitations/decline")
            .then().statusCode(204)
        assertEquals(0L, rotationJobs(h.groupId), "a decline that deleted nothing queues nothing")
        assertEquals(TestGrants.WRAPPED, membership(accepts, h.groupId)!!.wrappedGrant)
    }

    // --- helpers ---

    private class World(val tenantId: String, val owner: String, val groupId: String) {
        val domain = "t-$tenantId.example.com"

        /** A tenant member named [username], with a profile there; [consent] asks to be consulted before a group add. */
        fun member(username: String, consent: Boolean = false): String {
            val userId = seedUser(requireGroupAddConsent = consent)
            val tenant = tenantId
            QuarkusTransaction.requiringNew().run {
                TenantRoots.join(userId, tenant)
                TenantProfileEntity().apply {
                    id = UUID.randomUUID().toString(); this.userId = userId; tenantId = tenant; this.username = username
                }.persist()
            }
            return userId
        }
    }

    /** A tenant with a granted owner and a group the owner created through the API, at epoch 0. */
    private fun world(): World {
        val owner = seedUser()
        val tenantId = seedTenant(owner = owner)
        val groupId = UUID.randomUUID().toString()
        given().header("Authorization", bearer(owner)).header("X-Forwarded-Host", "t-$tenantId.example.com")
            .contentType(ContentType.JSON)
            .body("""{"scopeId":"$groupId","encryptedName":"bmFtZQ==","wrappedGrant":"${TestGrants.WRAPPED}",${TestGrants.SIGNATURE_JSON}}""")
            .`when`().post("/api/scopes/groups").then().statusCode(201)
        return World(tenantId, owner, groupId)
    }

    private fun add(g: World, username: String, fields: String) =
        given().header("Authorization", bearer(g.owner)).header("X-Forwarded-Host", g.domain).contentType(ContentType.JSON)
            .body("""{"username":"$username",$fields}""")
            .`when`().post("/api/scopes/${g.groupId}/members")

    /** The owner rotates the group to `E_1`, re-granting only itself. */
    private fun rotate(g: World) =
        given().header("Authorization", bearer(g.owner)).contentType(ContentType.JSON)
            .body("""{"epoch":1,"wrappedPrev":"cHJldg==","grants":[{"userId":"${g.owner}","wrappedGrant":"${TestGrants.WRAPPED}",${TestGrants.SIGNATURE_JSON}}]}""")
            .`when`().post("/api/scopes/${g.groupId}/epochs")

    private fun membership(userId: String, scopeId: String): MembershipEntity? = QuarkusTransaction.requiringNew().call {
        MembershipEntity.find("userId = ?1 and scopeId = ?2", userId, scopeId).firstResult()
    }

    private fun rotationJobs(scopeId: String): Long = QuarkusTransaction.requiringNew().call {
        JobEntity.count("scopeId = ?1 and capabilityTag = ?2", scopeId, CapabilityTags.SCOPE_ROTATION)
    }

    private companion object {
        const val NAME_KEY = "c2VhbGVkLW5hbWUta2V5"
    }
}
