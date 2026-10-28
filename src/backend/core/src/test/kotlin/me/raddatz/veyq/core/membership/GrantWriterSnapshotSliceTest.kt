package me.raddatz.veyq.core.membership

import me.raddatz.veyq.core.admin.tenant.TenantInviteEntity
import me.raddatz.veyq.core.admin.tenant.TenantStore
import me.raddatz.veyq.core.admin.tenant.TestMailbox
import me.raddatz.veyq.core.identity.TenantProfileEntity
import me.raddatz.veyq.core.scope.ScopeEntity
import me.raddatz.veyq.core.tasks.CapabilityTags
import me.raddatz.veyq.core.crypto.Tokens
import me.raddatz.veyq.core.testing.TestFixtures
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedTenant
import me.raddatz.veyq.core.testing.TestFixtures.seedUser
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.UUID

/**
 * Every path that writes a grant records the key its granter signed it with (ADR 0028 § What is still open,
 * inbox #233). The reads serve that copy, so a writer that forgets it serves no granter key at all and the
 * recipient refuses the grant as unsigned.
 *
 * One leg per writer, each through the writer's real HTTP path, each granter holding an identity key no other
 * account in the suite holds — so a leg cannot pass on a key some other row happens to carry. The legs are the nine
 * writers `scripts/validate_grant_writer_arity.py` derives from the stores and pins by name: a tenth writer turns
 * that guard red until it is named there, and it owes a leg here. Four of them are ADR 0111's: a member invite's grant
 * (on the invite row), its accept's self-grant, a tenant rotation's re-grant of an open invite (on the invite row) and
 * a group add.
 */
@QuarkusTest
class GrantWriterSnapshotSliceTest {

    @Inject
    lateinit var tenants: TenantStore

    @Test
    fun aPersonalScopeBirthRecordsItsOwnersKey() {
        val (owner, key) = granter()
        val tenantId = seedTenant(owner = owner)
        val scopeId = UUID.randomUUID().toString()
        given().header("Authorization", bearer(owner)).header("X-Forwarded-Host", "t-$tenantId.example.com")
            .contentType(ContentType.JSON)
            .body("""{"scopeId":"$scopeId","wrappedGrant":"$GRANT",${TestGrants.SIGNATURE_JSON}}""")
            .`when`().post("/api/scopes").then().statusCode(201)

        assertEquals(owner to key, snapshotOf(owner, scopeId))
    }

    @Test
    fun aGroupBirthRecordsItsCreatorsKey() {
        val (owner, key) = granter()
        val tenantId = seedTenant(owner = owner)
        val scopeId = UUID.randomUUID().toString()
        given().header("Authorization", bearer(owner)).header("X-Forwarded-Host", "t-$tenantId.example.com")
            .contentType(ContentType.JSON)
            .body("""{"scopeId":"$scopeId","encryptedName":"bmFtZQ==","wrappedGrant":"$GRANT",${TestGrants.SIGNATURE_JSON}}""")
            .`when`().post("/api/scopes/groups").then().statusCode(201)

        assertEquals(owner to key, snapshotOf(owner, scopeId))
    }

    @Test
    fun aTenantsColdBirthRecordsItsOwnersKey() {
        val (owner, key) = granter()
        val secret = "snapshot-$owner"
        val tenantId = QuarkusTransaction.requiringNew().call {
            tenants.createPendingTenant(
                "Fam", "snap-$owner.example.com", "u-$owner@example.com", Tokens.hash(secret),
                Instant.now().plus(1, ChronoUnit.DAYS),
            ).first
        }
        given().header("Authorization", bearer(owner)).contentType(ContentType.JSON)
            .body("""{"bootstrapSecret":"$secret","username":"snapowner","grant":"$GRANT",${TestGrants.SIGNATURE_JSON}}""")
            .`when`().post("/api/tenants/accept").then().statusCode(200)

        assertEquals(owner to key, snapshotOf(owner, tenantId))
    }

    @Test
    fun aRotationRecordsTheRotatorsKeyOnEveryGrantItWrites() {
        val (owner, key) = granter()
        val tenantId = seedTenant(owner = owner)
        val member = seedUser()
        QuarkusTransaction.requiringNew().run {
            MembershipEntity().apply {
                id = UUID.randomUUID().toString(); userId = member; this.tenantId = tenantId; scopeId = tenantId
                role = MembershipEntity.MEMBER; wrappedGrant = GRANT; createdAt = Instant.now()
            }.persist()
        }
        given().header("Authorization", bearer(owner)).contentType(ContentType.JSON)
            .body(
                """{"epoch":1,"wrappedPrev":"cHJldg==","grants":[
                   {"userId":"$owner","wrappedGrant":"$GRANT",${TestGrants.SIGNATURE_JSON}},
                   {"userId":"$member","wrappedGrant":"$GRANT",${TestGrants.SIGNATURE_JSON}}],
                   ${TestGrants.openInvitesJson(tenantId)}}""",
            )
            .`when`().post("/api/scopes/$tenantId/epochs").then().statusCode(201)

        assertEquals(owner to key, snapshotOf(member, tenantId), "the foreign re-grant")
        assertEquals(owner to key, snapshotOf(owner, tenantId), "the rotator's own re-grant")
    }

    @Test
    fun aGrantDeliveryRecordsTheWorkersKey() {
        val (worker, key) = granter()
        val tenantId = seedTenant(owner = worker)
        val target = seedUser()
        // The tenant is at E_1, the worker re-granted, the target BEHIND at E_0 — the one state a delivery serves.
        QuarkusTransaction.requiringNew().run {
            ScopeEntity.findById(tenantId)!!.currentEpoch = 1
            MembershipEntity.update("grantedEpoch = 1 where scopeId = ?1", tenantId)
            MembershipEntity().apply {
                id = UUID.randomUUID().toString(); userId = target; this.tenantId = tenantId; scopeId = tenantId
                role = MembershipEntity.MEMBER; wrappedGrant = "b2xk"; grantedEpoch = 0; createdAt = Instant.now()
            }.persist()
        }
        val jobId = given().header("Authorization", bearer(worker)).contentType(ContentType.JSON)
            .body("""{"capabilityTags":["${CapabilityTags.GRANT_DELIVERY}"]}""")
            .`when`().post("/api/tasks/claim").then().statusCode(200).extract().path<String>("jobId")
        given().header("Authorization", bearer(worker)).contentType(ContentType.JSON)
            .body("""{"sealedResult":"$GRANT","epoch":1,${TestGrants.SIGNATURE_JSON}}""")
            .`when`().post("/api/tasks/$jobId/complete").then().statusCode(204)

        assertEquals(worker to key, snapshotOf(target, tenantId))
    }

    @Test
    fun aMemberInviteRecordsItsSignersKey(): Unit = TestMailbox(INVITE_SMTP_PORT).start().use { mail ->
        // The invite is a placeholder member (ADR 0111 decision 1): its grant to the invite key is the inviting owner's,
        // with the key they hold at the mint — read by the write, never taken from the body. The default seeded key, whose
        // signing half the fixture signs the invite key with (the server checks that signature under it).
        val owner = seedUser()
        val key = TestFixtures.IDENTITY_PUBLIC_KEY
        val domain = "snap-invite-$owner.example.com"
        val tenantId = seedTenant(domain = domain, owner = owner)
        mail.receiveFor(tenants, tenantId, domain)
        val invite = TestInviteKey()
        given().header("Authorization", bearer(owner)).contentType(ContentType.JSON)
            .body("""{${TestGrants.memberInviteJson(tenantId, "invitee-$owner@example.com", invite)}}""")
            .`when`().post("/api/tenants/$tenantId/member-invites").then().statusCode(201)

        assertEquals(owner to key, inviteSnapshotOf(invite.inviteId))
    }

    @Test
    fun anInviteAcceptRecordsTheNewcomersKey() {
        // The newcomer grants itself (ADR 0111 decision 3): the granter is the accepting account, with its own key.
        val (signer, _) = granter()
        val tenantId = seedTenant(owner = signer)
        val (newcomer, key) = granter()
        val secret = "snapshot-accept-$newcomer"
        val invite = TestInviteKey()
        QuarkusTransaction.requiringNew().run {
            tenants.mintMemberInvite(tenantId, "u-$newcomer@example.com", Tokens.hash(secret), Instant.now().plus(1, ChronoUnit.DAYS), invite.stored(signer))
        }
        given().header("Authorization", bearer(newcomer)).contentType(ContentType.JSON)
            .body("""{"bootstrapSecret":"$secret","username":"snapnewcomer",${TestGrants.memberJoinJson(invite, invite.inviteId, newcomer, key)}}""")
            .`when`().post("/api/tenants/accept").then().statusCode(200)

        assertEquals(newcomer to key, snapshotOf(newcomer, tenantId))
    }

    @Test
    fun aTenantRotationRecordsTheRotatorsKeyOnEveryInviteItReGrants() {
        // The invite was minted by another owner; the rotation's re-grant replaces its granter with the rotator.
        val (rotator, key) = granter()
        val tenantId = seedTenant(owner = rotator)
        val (signer, _) = granter()
        val invite = TestInviteKey()
        QuarkusTransaction.requiringNew().run {
            tenants.mintMemberInvite(
                tenantId, "invited-$rotator@example.com", Tokens.hash("snapshot-carry-$rotator"), Instant.now().plus(1, ChronoUnit.DAYS),
                invite.stored(signer, signerIdentityPublicKey = "c2lnbmVycy1vbGQta2V5"),
            )
        }
        given().header("Authorization", bearer(rotator)).contentType(ContentType.JSON)
            .body(
                """{"epoch":1,"wrappedPrev":"cHJldg==","grants":[{"userId":"$rotator","wrappedGrant":"$GRANT",${TestGrants.SIGNATURE_JSON}}],
                   "inviteGrants":[{"inviteId":"${invite.inviteId}","wrappedGrant":"$GRANT",${TestGrants.SIGNATURE_JSON},"recipientIdentityDigest":"${TestGrants.digest(invite.bundle)}"}]}""",
            )
            .`when`().post("/api/scopes/$tenantId/epochs").then().statusCode(201)

        assertEquals(rotator to key, inviteSnapshotOf(invite.inviteId))
    }

    @Test
    fun aGroupAddRecordsTheAddingOwnersKey() {
        val (owner, key) = granter()
        val tenantId = seedTenant(owner = owner)
        val groupId = UUID.randomUUID().toString()
        given().header("Authorization", bearer(owner)).header("X-Forwarded-Host", "t-$tenantId.example.com")
            .contentType(ContentType.JSON)
            .body("""{"scopeId":"$groupId","encryptedName":"bmFtZQ==","wrappedGrant":"$GRANT",${TestGrants.SIGNATURE_JSON}}""")
            .`when`().post("/api/scopes/groups").then().statusCode(201)
        val target = seedUser()
        QuarkusTransaction.requiringNew().run {
            TenantRoots.join(target, tenantId)
            TenantProfileEntity().apply {
                id = UUID.randomUUID().toString(); userId = target; this.tenantId = tenantId; username = "snaptarget"
            }.persist()
        }
        given().header("Authorization", bearer(owner)).header("X-Forwarded-Host", "t-$tenantId.example.com")
            .contentType(ContentType.JSON)
            .body("""{"username":"snaptarget",${TestGrants.groupAddJson(groupId, target)}}""")
            .`when`().post("/api/scopes/$groupId/members").then().statusCode(201)

        assertEquals(owner to key, snapshotOf(target, groupId))
    }

    // --- helpers ---------------------------------------------------------------------------------------------

    /** An account whose identity key is its own — no other row in the JVM-wide H2 database carries it. Its email is
     *  [seedUser]'s default, `u-<id>@example.com`, which a tenant invite can name. */
    private fun granter(): Pair<String, String> {
        val id = UUID.randomUUID().toString()
        val key = Base64.getEncoder().encodeToString("identity-of-$id".toByteArray())
        return seedUser(id = id, identityPublicKey = key) to key
    }

    /** The granter and granter key recorded on [userId]'s grant in [scopeId]. */
    private fun snapshotOf(userId: String, scopeId: String): Pair<String?, String?> = QuarkusTransaction.requiringNew().call {
        MembershipEntity.find("userId = ?1 and scopeId = ?2", userId, scopeId).firstResult()!!
            .let { it.granterUserId to it.granterIdentityPublicKey }
    }

    /** The granter and granter key recorded on the member invite [inviteId]'s grant. */
    private fun inviteSnapshotOf(inviteId: String): Pair<String?, String?> = QuarkusTransaction.requiringNew().call {
        TenantInviteEntity.findById(inviteId)!!.let { it.granterUserId to it.granterIdentityPublicKey }
    }

    private companion object {
        const val GRANT = "Z3JhbnQtYnktYS1yZWFsLXdyaXRlcg=="

        // One JVM for the whole suite ⇒ distinct SMTP ports (the admin/tenant classes hold 13925-13947).
        const val INVITE_SMTP_PORT = 13948
    }
}
