package me.raddatz.veyq.core.admin.tenant

import me.raddatz.veyq.core.crypto.Tokens
import me.raddatz.veyq.core.testing.TestScopeRekey
import me.raddatz.veyq.core.identity.TenantProfileEntity
import me.raddatz.veyq.core.membership.MembershipEntity
import me.raddatz.veyq.core.membership.TenantRoots
import me.raddatz.veyq.core.membership.TestGrants
import me.raddatz.veyq.core.membership.TestInviteKey
import me.raddatz.veyq.core.scope.ScopeEntity
import me.raddatz.veyq.core.testing.TestFixtures
import me.raddatz.veyq.core.testing.TestFixtures.IDENTITY_PUBLIC_KEY
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedTenant
import me.raddatz.veyq.core.testing.TestFixtures.seedUser
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.nullValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.UUID

/**
 * An invite carries its owner's anchor, and an accept states the signer key it checked (ADR 0111
 * decisions 19, 20 and 21): the anchor is written at mint and moves only with a rotation its signer commits;
 * the preview serves the signer's chain, the anchor and the epoch links between the anchor and the carried grant; an
 * accept naming another signer key than the stored one is `409 invite_changed`, one naming none `400 invalid_request`;
 * the accept records who rooted the join; and the directory serves the join record's endorsement. Every refusal is
 * anchored on its status and code and has a control on the same predicate. Ids are UUID-unique (one H2 for the JVM).
 */
@QuarkusTest
class InviteAnchorSliceTest {

    @Inject lateinit var store: TenantStore

    // --- the anchor (ADR 0111 decision 19) ---

    @Test
    fun anInviteIsMintedWithTheOwnersGrantAsItsAnchor() {
        val (tenantId, owner) = seedActiveTenant()
        val key = seedInvite(tenantId, owner)
        assertEquals(listOf<Any?>(TestGrants.WRAPPED, 0, TestGrants.SIGNATURE), anchorOf(key.inviteId), "the anchor is the grant")
        assertEquals(listOf<Any?>(TestGrants.WRAPPED, 0, owner), grantOf(key.inviteId))
    }

    @Test
    fun theAnchorMovesOnlyWithARotationItsSignerCommits() {
        val (tenantId, signer) = seedActiveTenant()
        val other = seedUser(identityPublicKey = OTHER_KEY)
        tx { ownerOf(other, tenantId) }
        val key = seedInvite(tenantId, signer)

        // Another owner's rotation carries the invite and leaves its anchor where it was.
        rotate(other, tenantId, epoch = 1, wrapped = REGRANT_1, invite = key).then().statusCode(201)
        assertEquals(listOf<Any?>(REGRANT_1, 1, other), grantOf(key.inviteId), "the carried grant is the rotator's")
        assertEquals(listOf<Any?>(TestGrants.WRAPPED, 0, TestGrants.SIGNATURE), anchorOf(key.inviteId), "the anchor stays the owner's")

        // Control on the same predicate: the signer's own rotation moves the anchor to the grant it carries.
        rotate(signer, tenantId, epoch = 2, wrapped = REGRANT_2, invite = key).then().statusCode(201)
        assertEquals(listOf<Any?>(REGRANT_2, 2, signer), grantOf(key.inviteId))
        assertEquals(listOf<Any?>(REGRANT_2, 2, TestGrants.SIGNATURE), anchorOf(key.inviteId), "the anchor moved with its signer")
    }

    @Test
    fun aSignerRotatingUnderAnotherKeyThanTheInvitesLeavesTheAnchor() {
        // The anchor's signature must verify under the invite's signer key (`anchor_invalid` otherwise): a rotation by the
        // signer under a key the invite does not name writes the carried grant only.
        val (tenantId, signer) = seedActiveTenant()
        val stale = TestInviteKey()
        val minted = tx {
            store.mintMemberInvite(tenantId, "s-${UUID.randomUUID()}@example.com", Tokens.hash("s-${UUID.randomUUID()}"), week(), stale.stored(signer, OTHER_KEY))
        }
        assertEquals(MintMemberInvite.Minted(stale.inviteId), minted)
        rotate(signer, tenantId, epoch = 1, wrapped = REGRANT_1, invite = stale).then().statusCode(201)
        assertEquals(listOf<Any?>(REGRANT_1, 1, signer), grantOf(stale.inviteId))
        assertEquals(listOf<Any?>(TestGrants.WRAPPED, 0, TestGrants.SIGNATURE), anchorOf(stale.inviteId))
    }

    @Test
    fun anInviteThatEndsDropsItsAnchor() {
        val (tenantId, owner) = seedActiveTenant()
        val key = seedInvite(tenantId, owner)
        given().header("Authorization", bearer(owner))
            .`when`().delete("/api/tenants/$tenantId/member-invites/${key.inviteId}").then().statusCode(204)
        assertEquals(List(3) { null }, anchorOf(key.inviteId))
    }

    // --- the preview (ADR 0111 decision 19) ---

    @Test
    fun thePreviewServesTheSignersChainTheAnchorAndTheLinksBetween() {
        val (tenantId, signer) = seedActiveTenant()
        val other = seedUser(identityPublicKey = OTHER_KEY)
        tx { ownerOf(other, tenantId) }
        val secret = "anchor-preview-${UUID.randomUUID()}"
        val key = seedInvite(tenantId, signer, secret = secret)

        // The grant is the anchor: no links.
        preview(secret)
            .body("invite.signerKeyChain.size()", equalTo(1))
            .body("invite.signerKeyChain[0].identityPublicKey", equalTo(IDENTITY_PUBLIC_KEY))
            .body("invite.anchorWrappedGrant", equalTo(TestGrants.WRAPPED))
            .body("invite.anchorEpoch", equalTo(0))
            .body("invite.anchorGrantSignature", equalTo(TestGrants.SIGNATURE))
            .body("invite.grantedEpoch", equalTo(0))
            .body("invite.successions.size()", equalTo(0))
            .body("invite.signerKeyChainHead", nullValue())

        // Two rotations by another owner: the grant at 2, the anchor at 0, the succession statements of epochs 1 and 2 —
        // and only those (ADR 0115 decision 5).
        rotate(other, tenantId, epoch = 1, wrapped = REGRANT_1, invite = key, successionPublicKey = PREV_1).then().statusCode(201)
        rotate(other, tenantId, epoch = 2, wrapped = REGRANT_2, invite = key, successionPublicKey = PREV_2).then().statusCode(201)
        preview(secret)
            .body("invite.inviteId", equalTo(key.inviteId))
            .body("invite.anchorEpoch", equalTo(0))
            .body("invite.grantedEpoch", equalTo(2))
            .body("invite.wrappedGrant", equalTo(REGRANT_2))
            .body("invite.successions.size()", equalTo(2))
            .body("invite.successions[0].epoch", equalTo(1))
            .body("invite.successions[0].successionPublicKey", equalTo(PREV_1))
            .body("invite.successions[0].successionSignature", equalTo(TestGrants.SIGNATURE))
            .body("invite.successions[1].epoch", equalTo(2))
            .body("invite.successions[1].successionPublicKey", equalTo(PREV_2))
            .body("invite.epochLinks", nullValue())
    }

    // --- the accept (ADR 0111 decisions 20 and 21) ---

    @Test
    fun anAcceptNamingAnotherSignerKeyIsRefusedAsInviteChanged() {
        val (tenantId, owner) = seedActiveTenant()
        val secret = "anchor-changed-${UUID.randomUUID()}"
        val email = "changed-${UUID.randomUUID()}@example.com"
        val newcomer = seedUser(email = email)
        val key = seedInvite(tenantId, owner, secret = secret, email = email)

        accept(newcomer, secret, TestGrants.memberJoinJson(key, key.inviteId, newcomer, IDENTITY_PUBLIC_KEY, signerIdentityDigest = TestGrants.digest(OTHER_KEY)))
            .then().statusCode(409).body("code", equalTo("invite_changed"))
        assertEquals(0L, tx { MembershipEntity.count("userId = ?1 and scopeId = ?2", newcomer, tenantId) }, "no membership")
        assertEquals(TenantInviteEntity.ACTIVE, tx { TenantInviteEntity.findById(key.inviteId)!!.status }, "the invite is still live")

        // Control: the digest of the stored signer key.
        accept(newcomer, secret, TestGrants.memberJoinJson(key, key.inviteId, newcomer, IDENTITY_PUBLIC_KEY)).then().statusCode(200)
    }

    @Test
    fun aMemberAcceptWithoutTheSignerDigestIsMalformed() {
        val (tenantId, owner) = seedActiveTenant()
        val secret = "anchor-missing-${UUID.randomUUID()}"
        val email = "missing-${UUID.randomUUID()}@example.com"
        val newcomer = seedUser(email = email)
        val key = seedInvite(tenantId, owner, secret = secret, email = email)
        val withoutDigest = TestGrants.memberJoinJson(key, key.inviteId, newcomer, IDENTITY_PUBLIC_KEY)
            .replace(Regex(""","signerIdentityDigest":"[^"]*""""), "")

        accept(newcomer, secret, withoutDigest).then().statusCode(400).body("code", equalTo("invalid_request"))
        assertEquals(TenantInviteEntity.ACTIVE, tx { TenantInviteEntity.findById(key.inviteId)!!.status })
        // The register path refuses it the same way.
        val registered = UUID.randomUUID().toString()
        val regJoin = TestGrants.memberJoinJson(key, key.inviteId, registered, MEMBER_IDENTITY)
            .replace(Regex(""","signerIdentityDigest":"[^"]*""""), "")
        given().contentType(ContentType.JSON).body(registerBody(secret, registered, regJoin))
            .`when`().post("/api/tenants/accept/register").then().statusCode(400).body("code", equalTo("invalid_request"))

        // Control: with it.
        accept(newcomer, secret, TestGrants.memberJoinJson(key, key.inviteId, newcomer, IDENTITY_PUBLIC_KEY)).then().statusCode(200)
    }

    @Test
    fun theRegisterAcceptRefusesAnotherSignerKeyToo() {
        val (tenantId, owner) = seedActiveTenant()
        val secret = "anchor-reg-${UUID.randomUUID()}"
        val key = seedInvite(tenantId, owner, secret = secret)
        val registered = UUID.randomUUID().toString()
        given().contentType(ContentType.JSON)
            .body(registerBody(secret, registered, TestGrants.memberJoinJson(key, key.inviteId, registered, MEMBER_IDENTITY, signerIdentityDigest = TestGrants.digest(OTHER_KEY))))
            .`when`().post("/api/tenants/accept/register").then().statusCode(409).body("code", equalTo("invite_changed"))
        // Control.
        given().contentType(ContentType.JSON)
            .body(registerBody(secret, registered, TestGrants.memberJoinJson(key, key.inviteId, registered, MEMBER_IDENTITY)))
            .`when`().post("/api/tenants/accept/register").then().statusCode(201)
        assertEquals(owner to null, rootOf(registered, tenantId), "the register path records the signer too")
    }

    @Test
    fun anAcceptRecordsWhoRootedTheJoin() {
        val (tenantId, owner) = seedActiveTenant()
        val secret = "anchor-root-${UUID.randomUUID()}"
        val email = "root-${UUID.randomUUID()}@example.com"
        val newcomer = seedUser(email = email)
        val key = seedInvite(tenantId, owner, secret = secret, email = email)
        accept(newcomer, secret, TestGrants.memberJoinJson(key, key.inviteId, newcomer, IDENTITY_PUBLIC_KEY)).then().statusCode(200)
        assertEquals(owner to null, rootOf(newcomer, tenantId), "the invite's signer, and no endorsement yet")
    }

    @Test
    fun aRejoinRecordsItsNewRootAndDropsTheOldEndorsement() {
        // A member removed and invited back writes a new join record: the endorsement covered the old one, never this.
        val (tenantId, owner) = seedActiveTenant()
        val formerRoot = seedUser()
        val email = "rejoin-${UUID.randomUUID()}@example.com"
        val returning = seedUser(email = email)
        tx {
            TenantProfileEntity().apply {
                id = UUID.randomUUID().toString(); userId = returning; this.tenantId = tenantId; username = "r${UUID.randomUUID().toString().take(8)}"
                sealedJoinRecord = "b2xkLXJlY29yZA=="; joinSignerUserId = formerRoot; joinEndorsement = TestGrants.SIGNATURE
            }.persist()
        }
        val secret = "anchor-rejoin-${UUID.randomUUID()}"
        val key = seedInvite(tenantId, owner, secret = secret, email = email)
        accept(returning, secret, TestGrants.memberJoinJson(key, key.inviteId, returning, IDENTITY_PUBLIC_KEY)).then().statusCode(200)
        assertEquals(owner to null, rootOf(returning, tenantId))
        assertEquals(TestGrants.JOIN_RECORD, tx { profileOf(returning, tenantId).sealedJoinRecord })
    }

    // --- the directory serves the endorsement (ADR 0111 decision 21) ---

    @Test
    fun theTenantDirectoryServesTheJoinEndorsement() {
        val (tenantId, owner) = seedActiveTenant()
        val member = seedUser()
        tx { TenantRoots.join(member, tenantId) }
        tx {
            TenantProfileEntity().apply {
                id = UUID.randomUUID().toString(); userId = member; this.tenantId = tenantId; username = "e${UUID.randomUUID().toString().take(8)}"
                sealedJoinRecord = TestGrants.JOIN_RECORD; joinSignerUserId = owner; joinEndorsement = TestGrants.SIGNATURE
            }.persist()
        }
        given().header("Authorization", bearer(owner)).`when`().get("/api/scopes/$tenantId/members").then().statusCode(200)
            .body("find { it.userId == '$member' }.joinEndorsement", equalTo(TestGrants.SIGNATURE))
            .body("find { it.userId == '$owner' }.joinEndorsement", nullValue())
    }

    // --- helpers ---

    private fun seedActiveTenant(): Pair<String, String> {
        val ownerId = seedUser()
        return seedTenant(owner = ownerId) to ownerId
    }

    private fun seedInvite(
        tenantId: String, signer: String, secret: String = "anchor-${UUID.randomUUID()}", email: String = "i-${UUID.randomUUID()}@example.com",
    ): TestInviteKey {
        val key = TestInviteKey()
        val minted = tx { store.mintMemberInvite(tenantId, email, Tokens.hash(secret), week(), key.stored(signer, IDENTITY_PUBLIC_KEY)) }
        assertEquals(MintMemberInvite.Minted(key.inviteId), minted)
        return key
    }

    private fun week(): Instant = Instant.now().plus(7, ChronoUnit.DAYS)

    private fun ownerOf(userId: String, tenantId: String) {
        MembershipEntity().apply {
            id = UUID.randomUUID().toString(); this.userId = userId; this.tenantId = tenantId; scopeId = tenantId
            role = MembershipEntity.OWNER; wrappedGrant = TestFixtures.GRANT; grantedEpoch = ScopeEntity.findById(tenantId)!!.currentEpoch
            createdAt = Instant.now()
        }.persist()
    }

    /** [caller]'s commit of [epoch] on the tenant scope: re-grants every member and carries [invite], each with [wrapped]. */
    private fun rotate(
        caller: String, tenantId: String, epoch: Int, wrapped: String, invite: TestInviteKey,
        successionPublicKey: String = TestScopeRekey.SUCCESSION_PUBLIC_KEY,
    ) =
        given().header("Authorization", bearer(caller)).contentType(ContentType.JSON)
            .body(
                """{"epoch":$epoch,"successionPublicKey":"$successionPublicKey","successionSignature":"${TestGrants.SIGNATURE}",""" +
                    """"rekeyId":"${TestScopeRekey.stageAll(tenantId)}","grants":[${
                    tx { MembershipEntity.find("scopeId", tenantId).list().map { it.userId } }.joinToString(",") { u ->
                        """{"userId":"$u","wrappedGrant":"$wrapped",${TestGrants.SIGNATURE_JSON},"recipientIdentityDigest":"${TestGrants.digestOf(u)}"}"""
                    }
                }],""" +
                    """"inviteGrants":[{"inviteId":"${invite.inviteId}","wrappedGrant":"$wrapped",${TestGrants.SIGNATURE_JSON},"recipientIdentityDigest":"${TestGrants.digest(invite.bundle)}"}]}""",
            )
            .`when`().post("/api/scopes/$tenantId/epochs")

    private fun preview(secret: String) =
        given().contentType(ContentType.JSON).body("""{"bootstrapSecret":"$secret"}""")
            .`when`().post("/api/tenants/accept/preview").then().statusCode(200)

    private fun accept(userId: String, secret: String, join: String) =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"bootstrapSecret":"$secret","username":"u${UUID.randomUUID().toString().take(8)}",$join}""")
            .`when`().post("/api/tenants/accept")

    private fun registerBody(secret: String, userId: String, join: String) = """
        {"bootstrapSecret":"$secret","userId":"$userId","username":"r${UUID.randomUUID().toString().take(8)}",$join,
         "account":{"suiteId":"v1",
           "identityPublicKey":"$MEMBER_IDENTITY",
           "wrappedIdentityPrivateKey":"d3JhcHBlZC1pZGVudGl0eS1wcml2YXRlLWtleQ==",
           "authPublicKey":"ZWQyNTUxOS1hdXRoLXB1Yg==",
           "masterKeyWraps":[{"method":"PASSWORD","wrappedKey":"d3JhcHBlZC1tYXN0ZXIta2V5","kdfParams":"a2RmLXBhcmFtcw=="}],
           "secretKeyProofPublicKey":"c2VjcmV0LWtleS1wcm9vZi1wdWJsaWMta2V5"}}
    """.trimIndent()

    private fun anchorOf(inviteId: String): List<Any?> =
        tx { TenantInviteEntity.findById(inviteId)!!.let { listOf(it.anchorWrappedGrant, it.anchorEpoch, it.anchorGrantSignature) } }

    private fun grantOf(inviteId: String): List<Any?> =
        tx { TenantInviteEntity.findById(inviteId)!!.let { listOf(it.wrappedGrant, it.grantedEpoch, it.granterUserId) } }

    private fun profileOf(userId: String, tenantId: String): TenantProfileEntity =
        TenantProfileEntity.find("userId = ?1 and tenantId = ?2", userId, tenantId).firstResult()!!

    /** Who rooted [userId]'s join in [tenantId], and the endorsement it carries. */
    private fun rootOf(userId: String, tenantId: String): Pair<String?, String?> =
        tx { profileOf(userId, tenantId).let { it.joinSignerUserId to it.joinEndorsement } }

    private fun <T> tx(body: () -> T): T = QuarkusTransaction.requiringNew().call(body)

    private companion object {
        const val MEMBER_IDENTITY = "cmVnaXN0ZXJlZC1pZGVudGl0eQ=="
        val OTHER_KEY: String = Base64.getEncoder().encodeToString("another-owner-identity".toByteArray())
        const val REGRANT_1 = "cmVncmFudGVkLXRvLWUx"
        const val REGRANT_2 = "cmVncmFudGVkLXRvLWUy"
        const val PREV_1 = "cHJldi1vZi1lMQ=="
        const val PREV_2 = "cHJldi1vZi1lMg=="
    }
}
