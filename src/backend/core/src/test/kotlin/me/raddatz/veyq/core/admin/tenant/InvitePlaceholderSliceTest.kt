package me.raddatz.veyq.core.admin.tenant

import me.raddatz.veyq.core.crypto.Tokens
import org.junit.jupiter.api.Assertions.assertTrue
import me.raddatz.veyq.core.crypto.InviteAcceptProof
import me.raddatz.veyq.core.identity.TenantProfileEntity
import me.raddatz.veyq.core.membership.MembershipEntity
import me.raddatz.veyq.core.membership.SignedGrant
import me.raddatz.veyq.core.membership.TenantRoots
import me.raddatz.veyq.core.membership.TestGrants
import me.raddatz.veyq.core.membership.TestInviteKey
import me.raddatz.veyq.core.scope.ScopeEntity
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
import org.hamcrest.CoreMatchers.notNullValue
import org.hamcrest.CoreMatchers.nullValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.UUID

/**
 * A tenant member invite is a **placeholder member** (ADR 0111 decisions 1-3): it
 * holds the invite key the owner signed and a grant wrapped to it; every rotation of the tenant carries it; its
 * invitee grants itself at accept and writes a join record; an invite that ends — accepted, revoked, expired — holds
 * no key and no grant. Every refusal below is anchored on its status and code and owes a control on the same predicate
 * that passes; a crafted negative fails exactly one check. Distinct domains, emails and secrets per test (the H2
 * database lives as long as the JVM).
 */
@QuarkusTest
class InvitePlaceholderSliceTest {

    @Inject lateinit var store: TenantStore

    // --- creating an invite (ADR 0111 decisions 1-2) ---

    @Test
    fun aMemberInviteStoresTheInviteKeyItsSignatureAndAGrant(): Unit = TestMailbox(MINT_SMTP_PORT).start().use { mail ->
        val (tenantId, owner) = seedActiveTenant("ph-mint.example.com", mail)
        val email = "invitee@ph-mint.example.com"
        val key = TestInviteKey()
        invite(owner, tenantId, TestGrants.memberInviteJson(tenantId, email, key)).then().statusCode(201)

        val row = tx { TenantInviteEntity.findById(key.inviteId)!! }
        assertEquals(TenantInviteEntity.MEMBER to TenantInviteEntity.ACTIVE, row.role to row.status)
        assertEquals(
            listOf(key.bundle, owner, IDENTITY_PUBLIC_KEY),
            listOf(row.invitePublicKey, row.signerUserId, row.signerIdentityPublicKey),
            "the invite key, and the owner with the key they hold now",
        )
        assertTrue(
            InviteAcceptProof.inviteKeyVerifies(
                row.signerIdentityPublicKey, tenantId, key.inviteId, key.bundle, row.expiresAt.epochSecond, row.inviteKeySignature,
            ),
            "the owner's signature over the invite key, as posted, verifying under that key and the stored expiry",
        )
        assertEquals(
            listOf<Any?>(TestGrants.WRAPPED, 0, TestGrants.SIGNATURE, owner, IDENTITY_PUBLIC_KEY),
            listOf(row.wrappedGrant, row.grantedEpoch, row.grantSignature, row.granterUserId, row.granterIdentityPublicKey),
            "a grant of the current epoch to the invite key, its first granter the inviting owner",
        )

        // The mailed fragment is `<token>.<sealed link, base64url>`: the token the stored hash names, and the sealed
        // part re-encoded for a URL — the server keeps no copy of it.
        val (token, sealed) = mail.awaitFragment(email).split('.').also { assertEquals(2, it.size, "fragment $it") }
        assertEquals(row.tokenHash, Tokens.hash(token))
        assertEquals(Base64.getUrlEncoder().withoutPadding().encodeToString(Base64.getDecoder().decode(TestGrants.SEALED_LINK)), sealed)
    }

    @Test
    fun aMemberInviteAtAStaleEpochIsRefused(): Unit = TestMailbox(STALE_SMTP_PORT).start().use { mail ->
        // A rotation committed between the owner's read and the mint: a grant of the left epoch is refused, nothing
        // written — no rotation could have carried it.
        val (tenantId, owner) = seedActiveTenant("ph-stale.example.com", mail)
        rotateTo1(tenantId)
        val stale = TestInviteKey()
        invite(owner, tenantId, TestGrants.memberInviteJson(tenantId, "a@ph-stale.example.com", stale, epoch = 0))
            .then().statusCode(409).body("code", equalTo("stale_epoch"))
        assertNull(tx { TenantInviteEntity.findById(stale.inviteId) }, "nothing minted")
        // Control: the same invite at the current epoch is minted.
        val current = TestInviteKey()
        invite(owner, tenantId, TestGrants.memberInviteJson(tenantId, "a@ph-stale.example.com", current, epoch = 1)).then().statusCode(201)
        assertEquals(1, tx { TenantInviteEntity.findById(current.inviteId)!!.grantedEpoch })
    }

    @Test
    fun aMemberInviteWhoseGrantNamesAnotherKeyIsRefused(): Unit = TestMailbox(DIGEST_SMTP_PORT).start().use { mail ->
        // The grant names the key it is wrapped to; it must be the invite key the server stores beside it.
        val (tenantId, owner) = seedActiveTenant("ph-digest.example.com", mail)
        val key = TestInviteKey()
        val body = TestGrants.memberInviteJson(tenantId, "a@ph-digest.example.com", key)
            .replace(TestGrants.digest(key.bundle), TestGrants.digest(TestInviteKey().bundle))
        invite(owner, tenantId, body).then().statusCode(409).body("code", equalTo("recipient_key_not_current"))
        assertNull(tx { TenantInviteEntity.findById(key.inviteId) }, "nothing minted")
        // Control: the digest of the invite's own key.
        invite(owner, tenantId, TestGrants.memberInviteJson(tenantId, "a@ph-digest.example.com", key)).then().statusCode(201)
    }

    @Test
    fun aMemberInviteOffTheServersLifetimeIsRefused(): Unit = TestMailbox(EXPIRY_SMTP_PORT).start().use { mail ->
        // The owner signs the expiry, so the server takes the client's — within a minute of its own 7 days only.
        val (tenantId, owner) = seedActiveTenant("ph-expiry.example.com", mail)
        val key = TestInviteKey()
        val sixDays = Instant.now().plus(6, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS)
        val body = TestGrants.memberInviteJson(tenantId, "a@ph-expiry.example.com", key)
            .replace(Regex(""""expiresAt":"[^"]+""""), """"expiresAt":"$sixDays"""")
        // a rule no schema states: the 422, never the shape's 400 (the API fuzzing sent such an expiry as valid)
        invite(owner, tenantId, body).then().statusCode(422).body("code", equalTo("invite_expiry_invalid"))
        assertNull(tx { TenantInviteEntity.findById(key.inviteId) }, "nothing minted")
        // Control: the server's own lifetime.
        invite(owner, tenantId, TestGrants.memberInviteJson(tenantId, "a@ph-expiry.example.com", key)).then().statusCode(201)
    }

    @Test
    fun aMemberInviteNotSignedByTheCallerIsRefused(): Unit = TestMailbox(SIGNATURE_SMTP_PORT).start().use { mail ->
        // A session without the owner's unlocked vault (a phished password) holds no identity key to sign the invite key
        // with: the server checks the `invite-key` signature under the caller's stored key, so such an invite is never
        // minted — the role the admission tag played on an honest server. Every other field is genuine.
        val (tenantId, owner) = seedActiveTenant("ph-signature.example.com", mail)
        val key = TestInviteKey()
        val forged = TestGrants.memberInviteJson(tenantId, "a@ph-signature.example.com", key, signerSeed = ByteArray(32) { 0x11 })
        invite(owner, tenantId, forged).then().statusCode(422).body("code", equalTo("invite_signature_invalid"))
        assertNull(tx { TenantInviteEntity.findById(key.inviteId) }, "nothing minted")
        // Control: the caller's own signature.
        invite(owner, tenantId, TestGrants.memberInviteJson(tenantId, "a@ph-signature.example.com", key)).then().statusCode(201)
    }

    // --- every rotation of the tenant carries every open invite (ADR 0111 decision 2) ---

    @Test
    fun aRotationOfTheTenantMustCarryEveryOpenInvite() {
        val (tenantId, owner) = seedActiveTenant("ph-rotate.example.com")
        val signer = seedUser(identityPublicKey = SIGNER_KEY)
        val a = seedInvite(tenantId, signer, "a@ph-rotate.example.com")
        val b = seedInvite(tenantId, signer, "b@ph-rotate.example.com")

        // One left out: neither re-granted nor declared pending → refused, nothing stored.
        rotate(owner, tenantId, inviteGrants = listOf(a)).then().statusCode(409).body("code", equalTo("rotation_incomplete"))
        assertEquals(0, TestGrants.epochOf(tenantId), "no epoch committed")
        assertEquals(listOf<Any?>(TestGrants.WRAPPED, 0, signer), grantOf(a.inviteId), "nothing re-granted")

        // A re-grant that names a key that is not the invite's → refused, nothing stored.
        rotate(owner, tenantId, inviteGrants = listOf(a), pending = listOf(b), digestOf = { TestGrants.digest(b.bundle) })
            .then().statusCode(409).body("code", equalTo("recipient_key_not_current"))
        assertEquals(0, TestGrants.epochOf(tenantId), "no epoch committed")

        // Control on the same predicate: the one left out declared pending → commits.
        rotate(owner, tenantId, inviteGrants = listOf(a), pending = listOf(b)).then().statusCode(201).body("epoch", equalTo(1))
        assertEquals(1, TestGrants.epochOf(tenantId))
        // The re-granted invite holds the new epoch, the rotator its granter with the key they hold; the pending one keeps
        // the grant it held.
        assertEquals(listOf<Any?>(REGRANT.wrappedGrant, 1, owner), grantOf(a.inviteId))
        assertEquals(IDENTITY_PUBLIC_KEY, tx { TenantInviteEntity.findById(a.inviteId)!!.granterIdentityPublicKey })
        assertEquals(listOf<Any?>(TestGrants.WRAPPED, 0, signer), grantOf(b.inviteId))
    }

    // --- every member reads the open invites, no email (ADR 0111 decision 2) ---

    @Test
    fun listOpenInvitesServesEveryMemberAndNoEmail() {
        val (tenantId, owner) = seedActiveTenant("ph-list.example.com")
        val member = seedUser()
        tx { TenantRoots.join(member, tenantId) }
        val email = "invitee-list@ph-list.example.com"
        val key = seedInvite(tenantId, owner, email)

        val body = given().header("Authorization", bearer(member))
            .`when`().get("/api/scopes/$tenantId/open-invites")
            .then().statusCode(200)
            .body("size()", equalTo(1))
            .body("[0].inviteId", equalTo(key.inviteId))
            .body("[0].invitePublicKey", equalTo(key.bundle))
            .body("[0].inviteKeySignature", equalTo(TestGrants.SIGNATURE))
            .body("[0].signerUserId", equalTo(owner))
            .body("[0].signerIdentityPublicKey", equalTo(SIGNER_KEY))
            .body("[0].expiresAt", notNullValue())
            .body("[0].email", nullValue())
            .extract().asString()
        assertFalse(body.contains(email) || body.contains("@"), "an ordinary member never reads an invitee's address: $body")

        // A stranger to the tenant gets the gate's answer, the same as for a scope that does not exist.
        val outsider = seedUser()
        given().header("Authorization", bearer(outsider))
            .`when`().get("/api/scopes/$tenantId/open-invites")
            .then().statusCode(404).body("code", equalTo("scope_not_found"))
    }

    // --- the invitee grants itself and writes its join record (ADR 0111 decisions 3 and 12) ---

    @Test
    fun acceptingWritesAGrantedMembershipAndTheJoinRecordAndConsumesTheKey() {
        val (tenantId, owner) = seedActiveTenant("ph-accept.example.com")

        // The sign-in accept: an account that exists, with an identity key of its own.
        val newcomerKey = Base64.getEncoder().encodeToString("newcomer-${UUID.randomUUID()}".toByteArray())
        val newcomer = seedUser(email = "signin@ph-accept.example.com", identityPublicKey = newcomerKey)
        val key = seedInvite(tenantId, owner, "signin@ph-accept.example.com", secret = "ph-accept-signin")
        val signIn = """{"bootstrapSecret":"ph-accept-signin","username":"signin",${TestGrants.memberJoinJson(key, key.inviteId, newcomer, newcomerKey)}}"""
        given().header("Authorization", bearer(newcomer)).contentType(ContentType.JSON).body(signIn)
            .`when`().post("/api/tenants/accept").then().statusCode(200).body("status", equalTo("ACTIVE"))
        assertJoined(tenantId, newcomer, newcomerKey, key)
        // A second accept with the same token is refused as a replay.
        given().header("Authorization", bearer(newcomer)).contentType(ContentType.JSON).body(signIn)
            .`when`().post("/api/tenants/accept").then().statusCode(410).body("code", equalTo("invite_no_longer_valid"))

        // The register-and-accept: the account is born with the accept.
        val registered = UUID.randomUUID().toString()
        val key2 = seedInvite(tenantId, owner, "register@ph-accept.example.com", secret = "ph-accept-register")
        val register = registerBody("ph-accept-register", registered, TestGrants.memberJoinJson(key2, key2.inviteId, registered, MEMBER_IDENTITY))
        given().contentType(ContentType.JSON).body(register)
            .`when`().post("/api/tenants/accept/register").then().statusCode(201).body("userId", equalTo(registered))
        assertJoined(tenantId, registered, MEMBER_IDENTITY, key2)
        given().contentType(ContentType.JSON).body(registerBody("ph-accept-register", UUID.randomUUID().toString(), ""))
            .`when`().post("/api/tenants/accept/register").then().statusCode(410).body("code", equalTo("invite_no_longer_valid"))

        // The TENANT listing serves each joined member's join record (no `hasGrant` any more); the first owner has none.
        given().header("Authorization", bearer(owner))
            .`when`().get("/api/scopes/$tenantId/members")
            .then().statusCode(200)
            .body("find { it.userId == '$newcomer' }.sealedJoinRecord", equalTo(TestGrants.JOIN_RECORD))
            .body("find { it.userId == '$registered' }.sealedJoinRecord", equalTo(TestGrants.JOIN_RECORD))
            .body("find { it.userId == '$owner' }.sealedJoinRecord", nullValue())
            .body("find { it.userId == '$newcomer' }.hasGrant", nullValue())
    }

    @Test
    fun thePreviewServesAMemberInvitesKeySignatureAndGrant() {
        // Decision 8: the invitee's client checks the served key, the owner's signature and its grant against its link
        // before it trusts any of it — so the preview carries all of them, the signer's key as it signed.
        val (tenantId, owner) = seedActiveTenant("ph-preview.example.com")
        val key = seedInvite(tenantId, owner, "preview@ph-preview.example.com", secret = "ph-preview-secret")
        given().contentType(ContentType.JSON).body("""{"bootstrapSecret":"ph-preview-secret"}""")
            .`when`().post("/api/tenants/accept/preview").then().statusCode(200)
            .body("role", equalTo("MEMBER"))
            .body("invite.inviteId", equalTo(key.inviteId))
            .body("invite.invitePublicKey", equalTo(key.bundle))
            .body("invite.inviteKeySignature", equalTo(TestGrants.SIGNATURE))
            .body("invite.signerUserId", equalTo(owner))
            .body("invite.signerIdentityPublicKey", equalTo(SIGNER_KEY))
            .body("invite.wrappedGrant", equalTo(TestGrants.WRAPPED))
            .body("invite.grantedEpoch", equalTo(0))
            .body("invite.granterUserId", equalTo(owner))
            .body("invite.granterIdentityPublicKey", equalTo(SIGNER_KEY))
        // Control: an OWNER invite carries no key, so no `invite`.
        val ownerSecret = "ph-preview-owner"
        tx { store.mintOwnerInvite(tenantId, "owner@ph-preview.example.com", Tokens.hash(ownerSecret), Instant.now().plus(7, ChronoUnit.DAYS)) }
        given().contentType(ContentType.JSON).body("""{"bootstrapSecret":"$ownerSecret"}""")
            .`when`().post("/api/tenants/accept/preview").then().statusCode(200)
            .body("role", equalTo("OWNER")).body("invite", nullValue())
    }

    @Test
    fun anInviteProofByAnotherKeyIsRefusedAndWritesNothing() {
        // The proof is the invite key's signature over (invite, account, identity): one by another key fails exactly
        // that check — the token, the self-grant and its digest are all right.
        val (tenantId, owner) = seedActiveTenant("ph-proof.example.com")
        val newcomer = seedUser(email = "proof@ph-proof.example.com")
        val key = seedInvite(tenantId, owner, "proof@ph-proof.example.com", secret = "ph-proof-secret")
        val forged = """{"bootstrapSecret":"ph-proof-secret","username":"proof",${TestGrants.memberJoinJson(TestInviteKey(), key.inviteId, newcomer, IDENTITY_PUBLIC_KEY)}}"""
        given().header("Authorization", bearer(newcomer)).contentType(ContentType.JSON).body(forged)
            .`when`().post("/api/tenants/accept").then().statusCode(403).body("code", equalTo("invite_proof_invalid"))

        assertEquals(0L, tx { MembershipEntity.count("userId = ?1 and scopeId = ?2", newcomer, tenantId) }, "no membership")
        assertEquals(0L, tx { TenantProfileEntity.count("userId = ?1 and tenantId = ?2", newcomer, tenantId) }, "no profile")
        val live = tx { TenantInviteEntity.findById(key.inviteId)!! }
        assertEquals(TenantInviteEntity.ACTIVE to key.bundle, live.status to live.invitePublicKey, "the invite is still live, its key kept")

        // Control: the invite key's own proof is accepted.
        given().header("Authorization", bearer(newcomer)).contentType(ContentType.JSON)
            .body("""{"bootstrapSecret":"ph-proof-secret","username":"proof",${TestGrants.memberJoinJson(key, key.inviteId, newcomer, IDENTITY_PUBLIC_KEY)}}""")
            .`when`().post("/api/tenants/accept").then().statusCode(200)
    }

    // --- an invite that ends holds no key and no grant (ADR 0111 decision 2) ---

    @Test
    fun revokingOrExpiringAnInviteDropsItsKeyAndGrant() {
        val (tenantId, owner) = seedActiveTenant("ph-end.example.com")

        val revoked = seedInvite(tenantId, owner, "revoked@ph-end.example.com")
        val kept = seedInvite(tenantId, owner, "kept@ph-end.example.com")
        given().header("Authorization", bearer(owner))
            .`when`().delete("/api/tenants/$tenantId/member-invites/${revoked.inviteId}").then().statusCode(204)
        assertEquals(TenantInviteEntity.SUPERSEDED, statusOf(revoked.inviteId))
        assertEquals(List(9) { null }, keyColumns(revoked.inviteId), "a revoked invite holds no key and no grant")
        // Control: the invite nobody revoked keeps both.
        assertEquals(TenantInviteEntity.ACTIVE, statusOf(kept.inviteId))
        assertEquals(key(kept, owner), keyColumns(kept.inviteId))

        val overdue = seedInvite(tenantId, owner, "overdue@ph-end.example.com", expiresAt = Instant.now().minus(1, ChronoUnit.MINUTES))
        tx { store.expireOverdueInvites(Instant.now()) }
        assertEquals(TenantInviteEntity.EXPIRED, statusOf(overdue.inviteId))
        assertEquals(List(9) { null }, keyColumns(overdue.inviteId), "an expired invite holds no key and no grant")
        // Control: the sweep leaves the live invite as it was.
        assertEquals(TenantInviteEntity.ACTIVE, statusOf(kept.inviteId))
        assertEquals(key(kept, owner), keyColumns(kept.inviteId))
    }

    // --- helpers ---

    /** The membership holds the self-grant (granter = the newcomer, with its key), the profile the join record, and the
     *  invite is spent with no key and no grant left. */
    private fun assertJoined(tenantId: String, userId: String, identity: String, key: TestInviteKey) {
        val m = tx { MembershipEntity.find("userId = ?1 and scopeId = ?2", userId, tenantId).firstResult()!! }
        assertEquals(
            listOf<Any?>(MembershipEntity.MEMBER, TestGrants.WRAPPED, 0, TestGrants.SIGNATURE, userId, identity),
            listOf(m.role, m.wrappedGrant, m.grantedEpoch, m.grantSignature, m.granterUserId, m.granterIdentityPublicKey),
        )
        assertEquals(TestGrants.JOIN_RECORD, tx {
            TenantProfileEntity.find("userId = ?1 and tenantId = ?2", userId, tenantId).firstResult()!!.sealedJoinRecord
        })
        assertEquals(TenantInviteEntity.CONSUMED, statusOf(key.inviteId))
        assertEquals(List(9) { null }, keyColumns(key.inviteId), "an accepted invite holds no key and no grant")
    }

    /** An ACTIVE tenant at epoch 0 with a granted OWNER (identity [IDENTITY_PUBLIC_KEY]); mail on when [mailbox] is given. */
    private fun seedActiveTenant(domain: String, mailbox: TestMailbox? = null): Pair<String, String> {
        val ownerId = seedUser(email = "owner@$domain")
        val tenantId = seedTenant(domain = domain, ownerEmail = "owner@$domain", owner = ownerId)
        mailbox?.receiveFor(store, tenantId, domain)
        return tenantId to ownerId
    }

    /** A MEMBER invite to [email] signed by [signer] (with [SIGNER_KEY]), its grant of `E_0`, stored as the handler does. */
    private fun seedInvite(
        tenantId: String, signer: String, email: String, secret: String = "ph-${UUID.randomUUID()}",
        expiresAt: Instant = Instant.now().plus(7, ChronoUnit.DAYS),
    ): TestInviteKey {
        val key = TestInviteKey()
        val minted = tx { store.mintMemberInvite(tenantId, email, Tokens.hash(secret), expiresAt, key.stored(signer, SIGNER_KEY)) }
        assertEquals(MintMemberInvite.Minted(key.inviteId), minted)
        return key
    }

    /** What [seedInvite] stored in the nine key-and-grant columns. */
    private fun key(k: TestInviteKey, signer: String): List<Any?> =
        listOf(k.bundle, TestGrants.SIGNATURE, signer, SIGNER_KEY, TestGrants.WRAPPED, 0, TestGrants.SIGNATURE, signer, SIGNER_KEY)

    private fun keyColumns(inviteId: String): List<Any?> = tx {
        TenantInviteEntity.findById(inviteId)!!.let {
            listOf(
                it.invitePublicKey, it.inviteKeySignature, it.signerUserId, it.signerIdentityPublicKey,
                it.wrappedGrant, it.grantedEpoch, it.grantSignature, it.granterUserId, it.granterIdentityPublicKey,
            )
        }
    }

    /** The invite's grant, its epoch and its granter. */
    private fun grantOf(inviteId: String): List<Any?> =
        tx { TenantInviteEntity.findById(inviteId)!!.let { listOf(it.wrappedGrant, it.grantedEpoch, it.granterUserId) } }

    private fun statusOf(inviteId: String): String = tx { TenantInviteEntity.findById(inviteId)!!.status }

    /** The tenant scope rotated to `E_1` (its members re-granted), as a committed rotation leaves it. */
    private fun rotateTo1(tenantId: String) = tx {
        ScopeEntity.findById(tenantId)!!.currentEpoch = 1
        MembershipEntity.update("grantedEpoch = 1 where scopeId = ?1", tenantId)
    }

    private fun invite(owner: String, tenantId: String, fields: String) =
        given().header("Authorization", bearer(owner)).contentType(ContentType.JSON).body("{$fields}")
            .`when`().post("/api/tenants/$tenantId/member-invites")

    /** The owner's rotation of the tenant to `E_1`: re-grants itself, [REGRANT]s [inviteGrants] (naming the key
     *  [digestOf] gives, by default the invite's own) and declares [pending]. */
    private fun rotate(
        owner: String, tenantId: String, inviteGrants: List<TestInviteKey>, pending: List<TestInviteKey> = emptyList(),
        digestOf: (TestInviteKey) -> String = { TestGrants.digest(it.bundle) },
    ) = given().header("Authorization", bearer(owner)).contentType(ContentType.JSON)
        .body(
            """{"epoch":1,"wrappedPrev":"cHJldg==","grants":[{"userId":"$owner","wrappedGrant":"${REGRANT.wrappedGrant}",${TestGrants.SIGNATURE_JSON},"recipientIdentityDigest":"${TestGrants.digestOf(owner)}"}],""" +
                """"inviteGrants":[${inviteGrants.joinToString(",") {
                    """{"inviteId":"${it.inviteId}","wrappedGrant":"${REGRANT.wrappedGrant}",${TestGrants.SIGNATURE_JSON},"recipientIdentityDigest":"${digestOf(it)}"}"""
                }}],""" +
                """"pendingInviteIds":[${pending.joinToString(",") { "\"${it.inviteId}\"" }}]}""",
        )
        .`when`().post("/api/scopes/$tenantId/epochs")

    private fun registerBody(secret: String, userId: String, join: String) = """
        {"bootstrapSecret":"$secret","userId":"$userId","username":"registered",${if (join.isEmpty()) "" else "$join,"}
         "account":{"suiteId":"v1",
           "identityPublicKey":"$MEMBER_IDENTITY",
           "wrappedIdentityPrivateKey":"d3JhcHBlZC1pZGVudGl0eS1wcml2YXRlLWtleQ==",
           "authPublicKey":"ZWQyNTUxOS1hdXRoLXB1Yg==",
           "masterKeyWraps":[{"method":"PASSWORD","wrappedKey":"d3JhcHBlZC1tYXN0ZXIta2V5","kdfParams":"a2RmLXBhcmFtcw=="}],
           "secretKeyProofPublicKey":"c2VjcmV0LWtleS1wcm9vZi1wdWJsaWMta2V5"}}
    """.trimIndent()

    private fun <T> tx(body: () -> T): T = QuarkusTransaction.requiringNew().call(body)

    private companion object {
        /** The identity bundle a cold-born invitee registers. */
        const val MEMBER_IDENTITY = "cmVnaXN0ZXJlZC1pZGVudGl0eQ=="

        /** The identity key a seeded invite's signer signed with — distinct from every account's current one. */
        const val SIGNER_KEY = "c2lnbmVyLWtleS1hcy1zaWduZWQ="

        /** What a rotation re-grants: a wrap distinct from [TestGrants.WRAPPED], the one an invite is minted with. */
        val REGRANT = SignedGrant("cmVncmFudGVkLXRvLWUx", TestGrants.SIGNATURE)

        // One JVM for the whole suite ⇒ distinct SMTP ports.
        const val MINT_SMTP_PORT = 13951
        const val STALE_SMTP_PORT = 13952
        const val DIGEST_SMTP_PORT = 13953
        const val EXPIRY_SMTP_PORT = 13954
        const val SIGNATURE_SMTP_PORT = 13955
    }
}
