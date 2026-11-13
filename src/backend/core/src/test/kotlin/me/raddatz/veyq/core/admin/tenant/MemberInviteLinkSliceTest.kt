package me.raddatz.veyq.core.admin.tenant

import me.raddatz.veyq.core.crypto.Tokens
import me.raddatz.veyq.core.identity.VeyqUser
import me.raddatz.veyq.core.membership.MembershipEntity
import me.raddatz.veyq.core.membership.SignedGrant
import me.raddatz.veyq.core.membership.TestGrants
import me.raddatz.veyq.core.membership.TestInviteKey
import me.raddatz.veyq.core.tenant.TenantEntity
import me.raddatz.veyq.core.testing.TestFixtures.IDENTITY_PUBLIC_KEY
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedTenant
import me.raddatz.veyq.core.testing.TestFixtures.seedUser
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.Response
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.not
import org.hamcrest.CoreMatchers.notNullValue
import org.hamcrest.CoreMatchers.nullValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * A member invite as a **link** (ADR 0129, cr-member-invite-as-a-link phase 2): bound to nobody, sent nowhere, its token
 * answered to the owner who minted it; refused without sendable mail; spending the tenant's budget only; coexisting with
 * other open invites; accepted by any account but a member's, registering the typed address unconfirmed — while a mailed
 * redemption still confirms its address; re-issued and revoked; and a consumed invite's preview telling itself apart.
 * Every negative is anchored on its status and code and has a control on the same predicate that passes. Distinct
 * domains, addresses and secrets per test (the H2 database and the in-memory limiters live as long as the JVM).
 */
@QuarkusTest
class MemberInviteLinkSliceTest {

    @Inject lateinit var store: TenantStore

    // --- minting (ADR 0129 decisions 1 and 3) ---

    @Test
    fun aLinkMintAnswersItsTokenOnceAndSendsNothing(): Unit = TestMailbox(MINT_SMTP_PORT).start().use { mail ->
        val (tenantId, owner) = seedActiveTenant("link-mint.example.com", mail)
        val key = TestInviteKey()
        val token: String = mintLink(owner, tenantId, key)
            .then().statusCode(201)
            .body("inviteId", equalTo(key.inviteId))
            .body("bootstrapSecret", notNullValue())
            .body("inviteExpiresAt", notNullValue())
            .body("email", nullValue())
            .extract().path("bootstrapSecret")

        val row = tx { TenantInviteEntity.findById(key.inviteId)!! }
        assertEquals(
            listOf<Any?>(null, TenantInviteEntity.MEMBER, TenantInviteEntity.ACTIVE, Tokens.hash(token), key.bundle, owner),
            listOf(row.ownerEmail, row.role, row.status, row.tokenHash, row.invitePublicKey, row.signerUserId),
            "a placeholder member bound to nobody, stored under the answered token's hash",
        )
        // The answered token is the invite's: its preview names no address and serves the invite key.
        preview(token).then().statusCode(200)
            .body("role", equalTo("MEMBER")).body("ownerEmail", nullValue()).body("hasAccount", nullValue())
            .body("invite.inviteId", equalTo(key.inviteId))
        assertEquals(0, mail.total(), "a link mint sends nothing")

        // Control on the same mailbox: a mailed mint does reach it, so "nothing" above is the link's doing.
        given().header("Authorization", bearer(owner)).contentType(ContentType.JSON)
            .body("""{${TestGrants.memberInviteJson(tenantId, "mailed@link-mint.example.com")}}""")
            .`when`().post("/api/tenants/$tenantId/member-invites")
            .then().statusCode(201).body("bootstrapSecret", nullValue())
        mail.awaitBody("mailed@link-mint.example.com")
        assertEquals(1, mail.total())
    }

    @Test
    fun aTenantThatCannotSendMintsNoLink(): Unit = TestMailbox(NOMAIL_SMTP_PORT).start().use { mail ->
        // ADR 0129 decision 3: mail stays a precondition, asked before the mint.
        val (tenantId, owner) = seedActiveTenant("link-nomail.example.com")
        mintLink(owner, tenantId, TestInviteKey()).then().statusCode(503).body("code", equalTo("mail_unavailable"))
        assertEquals(0L, invitesOf(tenantId), "a refused link mint leaves no invite behind")

        // Control: the same request once the tenant can send.
        mail.receiveFor(store, tenantId, "link-nomail.example.com")
        mintLink(owner, tenantId, TestInviteKey()).then().statusCode(201)
        assertEquals(1L, invitesOf(tenantId))
    }

    @Test
    fun linkMintsSpendTheTenantsBudgetOnly(): Unit = TestMailbox(BUDGET_SMTP_PORT).start().use { mail ->
        // The defaults: 3 per recipient, 20 per tenant (application.properties). A link has no recipient, so a fourth
        // link mint passes where a fourth mail to one address would not — and the tenant's twentieth is its last.
        val (tenantId, owner) = seedActiveTenant("link-budget.example.com", mail)
        repeat(TENANT_BUDGET) { mintLink(owner, tenantId, TestInviteKey()).then().statusCode(201) }
        mintLink(owner, tenantId, TestInviteKey())
            .then().statusCode(429).header("Retry-After", notNullValue()).body("code", equalTo("rate_limited"))
        assertEquals(TENANT_BUDGET.toLong(), invitesOf(tenantId), "the refused mint minted nothing")
        // It is the tenant's bucket the links spent: a mailed mint to an address nobody was invited at is refused too.
        given().header("Authorization", bearer(owner)).contentType(ContentType.JSON)
            .body("""{${TestGrants.memberInviteJson(tenantId, "fresh@link-budget.example.com")}}""")
            .`when`().post("/api/tenants/$tenantId/member-invites")
            .then().statusCode(429).body("code", equalTo("rate_limited"))
    }

    @Test
    fun linkInvitesCoexistAndAMailedMintSupersedesNoneOfThem(): Unit = TestMailbox(COEXIST_SMTP_PORT).start().use { mail ->
        val (tenantId, owner) = seedActiveTenant("link-coexist.example.com", mail)
        val a = TestInviteKey().also { mintLink(owner, tenantId, it).then().statusCode(201) }
        val b = TestInviteKey().also { mintLink(owner, tenantId, it).then().statusCode(201) }
        val first = TestInviteKey()
        mintMailed(owner, tenantId, "same@link-coexist.example.com", first).then().statusCode(201)
        assertEquals(TenantInviteEntity.ACTIVE to TenantInviteEntity.ACTIVE, statusOf(a.inviteId) to statusOf(b.inviteId))

        // Control on the same predicate: a second mailed mint to that address does supersede the first one.
        val second = TestInviteKey()
        mintMailed(owner, tenantId, "same@link-coexist.example.com", second).then().statusCode(201)
        assertEquals(TenantInviteEntity.SUPERSEDED, statusOf(first.inviteId))
        assertEquals(TenantInviteEntity.ACTIVE to TenantInviteEntity.ACTIVE, statusOf(a.inviteId) to statusOf(b.inviteId))

        // The owner's list names the mailed invite's address and none for a link.
        given().header("Authorization", bearer(owner)).`when`().get("/api/tenants/$tenantId/member-invites")
            .then().statusCode(200).body("size()", equalTo(3))
            .body("find { it.inviteId == '${a.inviteId}' }.email", nullValue())
            .body("find { it.inviteId == '${b.inviteId}' }.email", nullValue())
            .body("find { it.inviteId == '${second.inviteId}' }.email", equalTo("same@link-coexist.example.com"))
    }

    // --- accepting (ADR 0129 decisions 2 and 4) ---

    @Test
    fun anyAccountAcceptsALinkAndItsAddressConfirmationIsLeftAsItWas() {
        val (tenantId, owner) = seedActiveTenant("link-any.example.com")
        val unconfirmed = seedUser(email = "whoever@elsewhere.example.org")
        val (secret, key) = seedLinkInvite(tenantId, owner)
        acceptAs(unconfirmed, secret, key, "whoever").then().statusCode(200).body("status", equalTo("ACTIVE"))
        assertEquals(MembershipEntity.MEMBER, roleOf(unconfirmed, tenantId), "an address the link never named joined")
        assertNull(confirmedAt(unconfirmed), "a link redemption proves no address")

        // An account confirmed before keeps the stamp it had, unchanged.
        val confirmed = seedUser(email = "known@elsewhere.example.org")
        val stamp = Instant.parse("2026-01-02T03:04:05.006Z")
        tx { VeyqUser.findById(confirmed)!!.confirmAddress("known@elsewhere.example.org", stamp) }
        val (secret2, key2) = seedLinkInvite(tenantId, owner)
        acceptAs(confirmed, secret2, key2, "known").then().statusCode(200)
        assertEquals(stamp, confirmedAt(confirmed))

        // Control on the same predicate: a mailed invite's redemption does stamp the address.
        val mailedTo = seedUser(email = "mailed@link-any.example.com")
        val (secret3, key3) = seedMailedInvite(tenantId, owner, "mailed@link-any.example.com")
        acceptAs(mailedTo, secret3, key3, "mailedto").then().statusCode(200)
        assertNotNull(confirmedAt(mailedTo), "a mailed invite's token is the mailbox proof")
    }

    @Test
    fun aMemberIsRefusedALinkAndTheLinkStaysGoodForTheOneItWasMeantFor() {
        val (tenantId, owner) = seedActiveTenant("link-member.example.com")
        val member = seedUser(email = "member@link-member.example.com")
        tx { store.joinAsMember(tenantId, member, SignedGrant(TestGrants.WRAPPED, TestGrants.SIGNATURE), 0) }
        val (secret, key) = seedLinkInvite(tenantId, owner)

        acceptAs(member, secret, key, "memberagain").then().statusCode(409).body("code", equalTo("already_member"))
        assertEquals(TenantInviteEntity.ACTIVE, statusOf(key.inviteId), "nothing spent")
        assertEquals(key.bundle, tx { TenantInviteEntity.findById(key.inviteId)!!.invitePublicKey }, "its key kept")

        // Control: someone who is no member accepts the same link.
        val newcomer = seedUser(email = "newcomer@link-member.example.com")
        acceptAs(newcomer, secret, key, "newcomer").then().statusCode(200)
        assertEquals(MembershipEntity.MEMBER, roleOf(newcomer, tenantId))
    }

    @Test
    fun aLinkRegistrationTakesTheTypedAddressUnconfirmedAndAMailedOneStillConfirms() {
        val (tenantId, owner) = seedActiveTenant("link-register.example.com")
        val (secret, key) = seedLinkInvite(tenantId, owner)

        // Without an address the link cannot birth an account: nothing born, the invite still live.
        val refused = UUID.randomUUID().toString()
        register(secret, refused, key, email = null).then().statusCode(422).body("code", equalTo("invite_kind_email_mismatch"))
        assertEquals(0L, tx { VeyqUser.count("id", refused) })
        assertEquals(TenantInviteEntity.ACTIVE, statusOf(key.inviteId))

        // An address someone already has is that person's: `email_taken`, and the link is not spent.
        seedUser(email = "taken@link-register.example.com")
        register(secret, UUID.randomUUID().toString(), key, email = "taken@link-register.example.com")
            .then().statusCode(409).body("code", equalTo("email_taken"))
        assertEquals(TenantInviteEntity.ACTIVE, statusOf(key.inviteId))

        // Control: the typed address, normalized, is the new account's — unconfirmed.
        val newUser = UUID.randomUUID().toString()
        register(secret, newUser, key, email = "  Typed@Link-Register.example.com ").then().statusCode(201).body("userId", equalTo(newUser))
        val account = tx { VeyqUser.findById(newUser)!!.let { it.email to it.emailConfirmedAt } }
        assertEquals("typed@link-register.example.com" to null, account)
        assertEquals(MembershipEntity.MEMBER, roleOf(newUser, tenantId))

        // A mailed invite takes its own address, so a typed one is refused; without one it registers, confirmed.
        val (mailedSecret, mailedKey) = seedMailedInvite(tenantId, owner, "invited@link-register.example.com")
        val mailedUser = UUID.randomUUID().toString()
        register(mailedSecret, mailedUser, mailedKey, email = "other@link-register.example.com")
            .then().statusCode(422).body("code", equalTo("invite_kind_email_mismatch"))
        assertEquals(0L, tx { VeyqUser.count("id", mailedUser) })
        register(mailedSecret, mailedUser, mailedKey, email = null, username = "invited").then().statusCode(201)
        val mailed = tx { VeyqUser.findById(mailedUser)!!.let { it.email to (it.emailConfirmedAt != null) } }
        assertEquals("invited@link-register.example.com" to true, mailed, "a mailed redemption still confirms the address")
    }

    // --- re-issue and revoke (ADR 0129 decision 2) ---

    @Test
    fun aLinkIsReissuedWithANewTokenAndKeyAndTheOldOneDies(): Unit = TestMailbox(REISSUE_SMTP_PORT).start().use { mail ->
        val (tenantId, owner) = seedActiveTenant("link-reissue.example.com", mail)
        val old = TestInviteKey()
        val oldToken: String = mintLink(owner, tenantId, old).then().statusCode(201).extract().path("bootstrapSecret")

        // A refused re-issue leaves the old link standing: a grant of an epoch that is not the current one.
        reissueLink(owner, tenantId, old.inviteId, TestInviteKey(), epoch = 1)
            .then().statusCode(409).body("code", equalTo("stale_epoch"))
        assertEquals(TenantInviteEntity.ACTIVE, statusOf(old.inviteId))

        val fresh = TestInviteKey()
        val freshToken: String = reissueLink(owner, tenantId, old.inviteId, fresh)
            .then().statusCode(200).body("inviteId", equalTo(fresh.inviteId))
            .body("bootstrapSecret", not(equalTo(oldToken)))
            .extract().path("bootstrapSecret")
        val gone = tx { TenantInviteEntity.findById(old.inviteId)!! }
        assertEquals(TenantInviteEntity.SUPERSEDED to null, gone.status to gone.invitePublicKey, "the old link's key went with it")
        preview(oldToken).then().statusCode(410).body("code", equalTo("invite_no_longer_valid"))
        preview(freshToken).then().statusCode(200).body("invite.inviteId", equalTo(fresh.inviteId)).body("ownerEmail", nullValue())
        assertEquals(0, mail.total(), "a re-issued link is sent nowhere either")
    }

    @Test
    fun eachWayReissuesOnlyItsOwnKindOfInvite(): Unit = TestMailbox(CROSS_SMTP_PORT).start().use { mail ->
        val (tenantId, owner) = seedActiveTenant("link-cross.example.com", mail)
        val link = TestInviteKey().also { mintLink(owner, tenantId, it).then().statusCode(201) }
        val mailed = TestInviteKey().also { mintMailed(owner, tenantId, "cross@link-cross.example.com", it).then().statusCode(201) }

        // The link re-issue of a mailed invite: no such link invite.
        reissueLink(owner, tenantId, mailed.inviteId, TestInviteKey()).then().statusCode(404).body("code", equalTo("invite_not_found"))
        // The mailed re-issue of a link invite: no body's address is a link's.
        given().header("Authorization", bearer(owner)).contentType(ContentType.JSON)
            .body("""{${TestGrants.memberInviteJson(tenantId, "cross@link-cross.example.com")}}""")
            .`when`().post("/api/tenants/$tenantId/member-invites/${link.inviteId}/reissue")
            .then().statusCode(422).body("code", equalTo("invite_email_mismatch"))
        assertEquals(TenantInviteEntity.ACTIVE to TenantInviteEntity.ACTIVE, statusOf(link.inviteId) to statusOf(mailed.inviteId))

        // Controls: each way re-issues its own kind.
        reissueLink(owner, tenantId, link.inviteId, TestInviteKey()).then().statusCode(200)
        given().header("Authorization", bearer(owner)).contentType(ContentType.JSON)
            .body("""{${TestGrants.memberInviteJson(tenantId, "cross@link-cross.example.com")}}""")
            .`when`().post("/api/tenants/$tenantId/member-invites/${mailed.inviteId}/reissue")
            .then().statusCode(200)
        assertEquals(TenantInviteEntity.SUPERSEDED to TenantInviteEntity.SUPERSEDED, statusOf(link.inviteId) to statusOf(mailed.inviteId))
    }

    @Test
    fun aLinkInviteIsRevokedLikeAMailedOne() {
        val (tenantId, owner) = seedActiveTenant("link-revoke.example.com")
        val (secret, revoked) = seedLinkInvite(tenantId, owner)
        val (keptSecret, kept) = seedLinkInvite(tenantId, owner)
        given().header("Authorization", bearer(owner))
            .`when`().delete("/api/tenants/$tenantId/member-invites/${revoked.inviteId}").then().statusCode(204)
        assertEquals(TenantInviteEntity.SUPERSEDED, statusOf(revoked.inviteId))
        preview(secret).then().statusCode(410).body("code", equalTo("invite_no_longer_valid"))
        // Control: the link nobody revoked still opens.
        assertEquals(TenantInviteEntity.ACTIVE, statusOf(kept.inviteId))
        preview(keptSecret).then().statusCode(200)
    }

    // --- a consumed invite's preview (ADR 0129 decision 6) ---

    @Test
    fun aConsumedInvitesPreviewIsRedeemedAndARevokedOrExpiredOneIsNoLongerValid() {
        val (tenantId, owner) = seedActiveTenant("link-redeemed.example.com")
        val (consumed, consumedKey) = seedLinkInvite(tenantId, owner)
        preview(consumed).then().statusCode(200) // control: live, it opens
        acceptAs(seedUser(email = "first@link-redeemed.example.com"), consumed, consumedKey, "first").then().statusCode(200)
        preview(consumed).then().statusCode(410).body("code", equalTo("invite_redeemed"))

        // The same token shape, other dead states: a revoked one, an expired one (swept and unswept).
        val (revoked, revokedKey) = seedLinkInvite(tenantId, owner)
        tx { store.revokeInvite(revokedKey.inviteId) }
        preview(revoked).then().statusCode(410).body("code", equalTo("invite_no_longer_valid"))
        val (overdue, _) = seedLinkInvite(tenantId, owner, expiresAt = Instant.now().minus(1, ChronoUnit.MINUTES))
        preview(overdue).then().statusCode(410).body("code", equalTo("invite_no_longer_valid"))
        tx { store.expireOverdueInvites(Instant.now()) }
        preview(overdue).then().statusCode(410).body("code", equalTo("invite_no_longer_valid"))

        // Every consumed invite, not only a link's: a mailed one redeemed answers the same.
        val (mailed, mailedKey) = seedMailedInvite(tenantId, owner, "mailed@link-redeemed.example.com")
        acceptAs(seedUser(email = "mailed@link-redeemed.example.com"), mailed, mailedKey, "mailedone").then().statusCode(200)
        preview(mailed).then().statusCode(410).body("code", equalTo("invite_redeemed"))

        // A suspended tenant's invite answers as a dead one whatever its own state (uc.spaces.scope-access A4).
        tx { TenantEntity.findById(tenantId)!!.status = TenantEntity.SUSPENDED }
        preview(consumed).then().statusCode(410).body("code", equalTo("invite_no_longer_valid"))
    }

    // --- helpers ---

    private fun mintLink(owner: String, tenantId: String, key: TestInviteKey, epoch: Int? = null): Response =
        given().header("Authorization", bearer(owner)).contentType(ContentType.JSON)
            .body("""{${TestGrants.memberInviteLinkJson(tenantId, key, epoch)}}""")
            .`when`().post("/api/tenants/$tenantId/member-invite-links")

    private fun reissueLink(owner: String, tenantId: String, inviteId: String, key: TestInviteKey, epoch: Int? = null): Response =
        given().header("Authorization", bearer(owner)).contentType(ContentType.JSON)
            .body("""{${TestGrants.memberInviteLinkJson(tenantId, key, epoch)}}""")
            .`when`().post("/api/tenants/$tenantId/member-invite-links/$inviteId/reissue")

    private fun mintMailed(owner: String, tenantId: String, email: String, key: TestInviteKey): Response =
        given().header("Authorization", bearer(owner)).contentType(ContentType.JSON)
            .body("""{${TestGrants.memberInviteJson(tenantId, email, key)}}""")
            .`when`().post("/api/tenants/$tenantId/member-invites")

    private fun preview(secret: String): Response =
        given().contentType(ContentType.JSON).body("""{"bootstrapSecret":"$secret"}""").`when`().post("/api/tenants/accept/preview")

    /** The sign-in accept by [userId], whose identity is [IDENTITY_PUBLIC_KEY] (every `seedUser` account's). */
    private fun acceptAs(userId: String, secret: String, key: TestInviteKey, username: String): Response =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"bootstrapSecret":"$secret","username":"$username",${TestGrants.memberJoinJson(key, key.inviteId, userId, IDENTITY_PUBLIC_KEY)}}""")
            .`when`().post("/api/tenants/accept")

    private fun register(secret: String, userId: String, key: TestInviteKey, email: String?, username: String = "registered"): Response =
        given().contentType(ContentType.JSON).body(
            """
            {"bootstrapSecret":"$secret","userId":"$userId","username":"$username",${email?.let { "\"email\":\"$it\"," } ?: ""}
             ${TestGrants.memberJoinJson(key, key.inviteId, userId, MEMBER_IDENTITY)},
             "account":{"suiteId":"v1",
               "identityPublicKey":"$MEMBER_IDENTITY",
               "wrappedIdentityPrivateKey":"d3JhcHBlZC1pZGVudGl0eS1wcml2YXRlLWtleQ==",
               "authPublicKey":"ZWQyNTUxOS1hdXRoLXB1Yg==",
               "masterKeyWraps":[{"method":"PASSWORD","wrappedKey":"d3JhcHBlZC1tYXN0ZXIta2V5","kdfParams":"a2RmLXBhcmFtcw=="}],
               "secretKeyProofPublicKey":"c2VjcmV0LWtleS1wcm9vZi1wdWJsaWMta2V5"}}
            """.trimIndent(),
        ).`when`().post("/api/tenants/accept/register")

    /** An ACTIVE tenant at epoch 0 with a granted OWNER (identity [IDENTITY_PUBLIC_KEY]); mail on when [mailbox] is given. */
    private fun seedActiveTenant(domain: String, mailbox: TestMailbox? = null): Pair<String, String> {
        val ownerId = seedUser(email = "owner@$domain")
        val tenantId = seedTenant(domain = domain, ownerEmail = "owner@$domain", owner = ownerId)
        mailbox?.receiveFor(store, tenantId, domain)
        return tenantId to ownerId
    }

    /** A link invite signed by [owner], its grant of `E_0`, stored as the handler stores one; returns its secret and key. */
    private fun seedLinkInvite(
        tenantId: String, owner: String, expiresAt: Instant = Instant.now().plus(7, ChronoUnit.DAYS),
    ): Pair<String, TestInviteKey> = seedInvite(tenantId, owner, null, expiresAt)

    /** A mailed invite to [email], seeded the same way. */
    private fun seedMailedInvite(tenantId: String, owner: String, email: String): Pair<String, TestInviteKey> =
        seedInvite(tenantId, owner, email, Instant.now().plus(7, ChronoUnit.DAYS))

    private fun seedInvite(tenantId: String, owner: String, email: String?, expiresAt: Instant): Pair<String, TestInviteKey> {
        val key = TestInviteKey()
        val secret = "link-${UUID.randomUUID()}"
        val minted = tx { store.mintMemberInvite(tenantId, email, Tokens.hash(secret), expiresAt, key.stored(owner)) }
        assertEquals(MintMemberInvite.Minted(key.inviteId), minted)
        return secret to key
    }

    private fun statusOf(inviteId: String): String = tx { TenantInviteEntity.findById(inviteId)!!.status }

    private fun invitesOf(tenantId: String): Long = tx { TenantInviteEntity.count("tenantId", tenantId) }

    private fun roleOf(userId: String, tenantId: String): String? =
        tx { MembershipEntity.find("userId = ?1 and scopeId = ?2", userId, tenantId).firstResult()?.role }

    private fun confirmedAt(userId: String): Instant? = tx { VeyqUser.findById(userId)!!.emailConfirmedAt }

    private fun <T> tx(body: () -> T): T = QuarkusTransaction.requiringNew().call(body)

    private companion object {
        /** The identity bundle a cold-born invitee registers. */
        const val MEMBER_IDENTITY = "bGluay1yZWdpc3RlcmVkLWlkZW50aXR5"

        /** `veyq.ratelimit.invite.per-tenant-max-requests`' default (application.properties). */
        const val TENANT_BUDGET = 20

        // One JVM for the whole suite ⇒ distinct SMTP ports (TestMailbox says how to find the taken ones).
        const val MINT_SMTP_PORT = 13956
        const val NOMAIL_SMTP_PORT = 13957
        const val BUDGET_SMTP_PORT = 13958
        const val COEXIST_SMTP_PORT = 13959
        const val REISSUE_SMTP_PORT = 13968
        const val CROSS_SMTP_PORT = 13969
    }
}
