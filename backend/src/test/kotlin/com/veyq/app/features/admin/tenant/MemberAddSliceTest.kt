package com.veyq.app.features.admin.tenant

import com.veyq.app.shared.membership.TestAdmission
import com.veyq.app.features.tasks.CapabilityTags
import com.veyq.app.shared.crypto.Tokens
import com.veyq.app.shared.membership.MembershipEntity
import com.veyq.app.testing.TestFixtures.bearer
import com.veyq.app.testing.TestFixtures.seedTenant
import com.veyq.app.testing.TestFixtures.seedUser
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.notNullValue
import org.hamcrest.CoreMatchers.nullValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Slice tests for member-add onboarding (uc.spaces.add-member, cr-0077): the tenant **owner** mints a MEMBER
 * invite (owner-gated admission), the invitee **cold-births** their account and joins the ACTIVE tenant as a
 * **grant-less** member, which cr-0076 picks up as a `grant-delivery` job; and the owner reads the member
 * directory (pubkeys + grant status). Distinct domains/emails so the JVM-lived H2 mem DB can't bleed state.
 */
@QuarkusTest
class MemberAddSliceTest {

    @Inject lateinit var store: TenantStore

    @Test
    fun ownerMintsMemberInviteOutsiderIs404(): Unit = TestMailbox(MINT_SMTP_PORT).start().use { mail ->
        val (tenantId, owner) = seedActiveTenant("mint.example.com", "owner@mint.example.com", mail)
        given().header("Authorization", bearer(owner)).contentType(ContentType.JSON)
            .body("""{"email":"newbie@mint.example.com",${TestAdmission.json()}}""")
            .`when`().post("/api/tenants/$tenantId/member-invites")
            .then().statusCode(201).body("email", equalTo("newbie@mint.example.com"))
            .body("inviteStatus", equalTo("ACTIVE")).body("inviteExpiresAt", notNullValue())

        // An outsider (not a member of the tenant at all) cannot admit members — and gets the same answer as for
        // a tenant that does not exist (membership is the first guard, ADR 0042 / cr-0162).
        val outsider = seedUser(email = "outsider@mint.example.com")
        given().header("Authorization", bearer(outsider)).contentType(ContentType.JSON)
            .body("""{"email":"x@mint.example.com",${TestAdmission.json()}}""")
            .`when`().post("/api/tenants/$tenantId/member-invites")
            .then().statusCode(404).body("code", equalTo("tenant_not_found"))
    }

    @Test
    fun memberRegisterJoinsGrantLessAndOwnerClaimOriginatesDelivery() {
        val (tenantId, owner) = seedActiveTenant("join.example.com", "owner@join.example.com")
        val secret = "member-secret-1"
        seedMemberInvite(tenantId, "member@join.example.com", secret)

        // The invitee cold-births an account and joins — NO grant in the body (a member is grant-less).
        val newUserId = given().contentType(ContentType.JSON).body(memberRegisterBody(secret))
            .`when`().post("/api/tenants/accept/register")
            .then().statusCode(201).body("status", equalTo("ACTIVE")).body("userId", notNullValue())
            .extract().path<String>("userId")

        // The membership exists on the TENANT scope, role MEMBER, with NO grant yet (the hollow state).
        assertEquals("MEMBER", membershipRole(newUserId, tenantId))
        assertNull(grantOf(newUserId, tenantId), "a freshly onboarded member is grant-less")

        // cr-0076 integration: the owner (a key-holder) claims grant-delivery → reconcile-on-claim originates
        // the job for the pending member and hands it back, resolving the target's pubkey.
        given().header("Authorization", bearer(owner)).contentType(ContentType.JSON)
            .body("""{"capabilityTags":["${CapabilityTags.GRANT_DELIVERY}"],"scopeId":"$tenantId"}""")
            .`when`().post("/api/tasks/claim")
            .then().statusCode(200)
            .body("capabilityTag", equalTo(CapabilityTags.GRANT_DELIVERY))
            .body("scopeId", equalTo(tenantId))
            .body("grantDelivery.targetUserId", equalTo(newUserId))
            .body("grantDelivery.identityPublicKey", notNullValue())
    }

    @Test
    fun theMailedSecretIsTheOnlyChannelAndItWorks(): Unit = TestMailbox(MAILED_SMTP_PORT).start().use { mail ->
        // ADR 0055 §2: the mint response no longer hands the owner the raw secret — a token minted for the
        // invited person does not travel through whoever is looking at the owner's screen. Prove both halves:
        // the response carries no secret, and the one that arrives *in the mail* is the real, usable token.
        val (tenantId, owner) = seedActiveTenant("mailed.example.com", "owner@mailed.example.com", mail)
        val invitee = "newbie@mailed.example.com"
        val admissionId = UUID.randomUUID().toString()
        given().header("Authorization", bearer(owner)).contentType(ContentType.JSON)
            .body("""{"email":"$invitee",${TestAdmission.json(0, admissionId)}}""")
            .`when`().post("/api/tenants/$tenantId/member-invites")
            .then().statusCode(201)
            .body("bootstrapSecret", nullValue())
            .body("delivery", equalTo("SENT")) // the only reachable delivery on this path (ADR 0055 §7)

        val secret = mail.awaitToken(invitee)
        val newUserId = given().contentType(ContentType.JSON).body(memberRegisterBody(secret))
            .`when`().post("/api/tenants/accept/register")
            .then().statusCode(201).body("status", equalTo("ACTIVE"))
            .extract().path<String>("userId")
        assertEquals("MEMBER", membershipRole(newUserId, tenantId))
        assertNull(grantOf(newUserId, tenantId))
        // ADR 0020 move 6: the admission the owner's client tagged rides the invite onto the membership it becomes.
        val carried = QuarkusTransaction.requiringNew().call {
            MembershipEntity.find("userId = ?1 and scopeId = ?2", newUserId, tenantId).firstResult()!!
                .let { Triple(it.admissionId, it.admissionEpoch, it.admissionTag) }
        }
        assertEquals(Triple(admissionId, 0, TestAdmission.TAG), carried)
    }

    @Test
    fun aTenantThatCannotSendMailIsRefusedAndMintsNothing() {
        // ADR 0055 §2: no mail settings ⇒ no channel ⇒ the invite is refused *before* the mint, rather than
        // degraded to a copy-link. The "mints nothing" half is the point: a minted-but-undeliverable invite is a
        // live secret with no recipient, which is exactly what the copy-link used to paper over.
        val (tenantId, owner) = seedActiveTenant("nomail.example.com", "owner@nomail.example.com")
        given().header("Authorization", bearer(owner)).contentType(ContentType.JSON)
            .body("""{"email":"newbie@nomail.example.com",${TestAdmission.json()}}""")
            .`when`().post("/api/tenants/$tenantId/member-invites")
            .then().statusCode(503).body("code", equalTo("mail_unavailable"))

        assertEquals(0, activeInvitesFor(tenantId), "a refused invite must leave no token behind")
    }

    @Test
    fun aSendThatFailsLeavesNoLiveInvite() {
        // ADR 0055 §6: SMTP is configured (so the gate passes) but nothing is listening, so the send errors. The
        // mint has already committed by then — deliberately, so a hung MTA cannot hold a DB connection — so the
        // failure path must *compensate* it. Without that, the owner gets an error while a live secret nobody can
        // reach sits in the table until it expires.
        val (tenantId, owner) = seedActiveTenant("deadsmtp.example.com", "owner@deadsmtp.example.com")
        pointAtDeadSmtp(tenantId, "deadsmtp.example.com")

        given().header("Authorization", bearer(owner)).contentType(ContentType.JSON)
            .body("""{"email":"newbie@deadsmtp.example.com",${TestAdmission.json()}}""")
            .`when`().post("/api/tenants/$tenantId/member-invites")
            .then().statusCode(503).body("code", equalTo("mail_send_failed"))

        assertEquals(0, activeInvitesFor(tenantId), "a failed send must revoke the invite it minted")
    }

    @Test
    fun invitingOneRecipientPastTheLimitIs429WithRetryAfter(): Unit = TestMailbox(FLOOD_SMTP_PORT).start().use { mail ->
        // The anti-bombing guard at the HTTP boundary (cr-email-invite-send-hardening): the default per-recipient
        // cap is 3, so the 4th invite to the SAME address is refused with 429 + Retry-After — before any mint. A
        // unique tenant + email keeps the shared in-memory limiter isolated from the other tests in this JVM.
        val (tenantId, owner) = seedActiveTenant("flood.example.com", "owner@flood.example.com", mail)
        val victim = "victim@flood.example.com"
        repeat(3) {
            given().header("Authorization", bearer(owner)).contentType(ContentType.JSON)
                .body("""{"email":"$victim",${TestAdmission.json()}}""")
                .`when`().post("/api/tenants/$tenantId/member-invites")
                .then().statusCode(201)
        }
        given().header("Authorization", bearer(owner)).contentType(ContentType.JSON)
            .body("""{"email":"$victim",${TestAdmission.json()}}""")
            .`when`().post("/api/tenants/$tenantId/member-invites")
            .then().statusCode(429).header("Retry-After", notNullValue()).body("code", equalTo("rate_limited"))
    }

    @Test
    fun existingAccountAcceptsMemberInviteAndJoinsGrantLess() {
        // The second onboarding flow (uc.spaces.add-member): the invited email ALREADY has an account, so it
        // signs in and accepts (POST /api/tenants/accept) rather than cold-registering. A member joins grant-
        // less here too — no grant in the body, the key delivered later by a key-holder (cr-0076).
        val (tenantId, _) = seedActiveTenant("existing.example.com", "owner@existing.example.com")
        val secret = "existing-member-secret"
        seedMemberInvite(tenantId, "existing@existing.example.com", secret)
        val existing = seedUser(email = "existing@existing.example.com") // already registered before the invite

        given().header("Authorization", bearer(existing)).contentType(ContentType.JSON)
            .body("""{"bootstrapSecret":"$secret","username":"existingmbr"}""") // no grant — an existing-account member joins grant-less
            .`when`().post("/api/tenants/accept")
            .then().statusCode(200).body("status", equalTo("ACTIVE"))

        assertEquals("MEMBER", membershipRole(existing, tenantId))
        assertNull(grantOf(existing, tenantId), "an existing-account member joins grant-less too")
    }

    @Test
    fun ownerInviteRegisterStillRequiresAGrant() {
        // The OWNER path is unchanged: registering to an OWNER invite without a grant is a 400.
        val secret = "owner-secret-1"
        QuarkusTransaction.requiringNew().run {
            store.createPendingTenant("Fam", "owner-need-grant.example.com", "o@owner-need-grant.example.com",
                Tokens.hash(secret), Instant.now().plus(7, ChronoUnit.DAYS))
        }
        given().contentType(ContentType.JSON).body(memberRegisterBody(secret)) // body omits grant
            .`when`().post("/api/tenants/accept/register")
            .then().statusCode(400).body("code", equalTo("invalid_request"))
    }

    @Test
    fun memberDirectoryListsMembersWithGrantStatus() {
        val (tenantId, owner) = seedActiveTenant("dir.example.com", "owner@dir.example.com")
        val pending = seedUser(email = "pending@dir.example.com", identityPublicKey = "cGVuZGluZy1wdWJrZXk=")
        QuarkusTransaction.requiringNew().run {
            MembershipEntity().apply {
                id = UUID.randomUUID().toString(); userId = pending; this.tenantId = tenantId
                scopeId = tenantId; role = MembershipEntity.MEMBER; wrappedGrant = null; createdAt = Instant.now()
            }.persist()
        }

        given().header("Authorization", bearer(owner))
            .`when`().get("/api/scopes/$tenantId/members")
            .then().statusCode(200).body("size()", equalTo(2))
            .body("find { it.userId == '$owner' }.hasGrant", equalTo(true))
            .body("find { it.userId == '$owner' }.role", equalTo("OWNER"))
            .body("find { it.userId == '$pending' }.hasGrant", equalTo(false))
            .body("find { it.userId == '$pending' }.identityPublicKey", equalTo("cGVuZGluZy1wdWJrZXk="))

        // A non-member cannot read the directory, and gets the same answer as for a scope that does not exist
        // (membership is the first guard, ADR 0042 / cr-0162 — no scope enumeration).
        val outsider = seedUser(email = "nope@dir.example.com")
        given().header("Authorization", bearer(outsider))
            .`when`().get("/api/scopes/$tenantId/members")
            .then().statusCode(404).body("code", equalTo("scope_not_found"))
    }

    // --- helpers ---

    /** ACTIVE invites of this tenant — the "nothing was minted / it was revoked again" assertions read this. */
    private fun activeInvitesFor(tenantId: String): Int =
        QuarkusTransaction.requiringNew().call {
            TenantInviteEntity.count("tenantId = ?1 and status = ?2", tenantId, TenantInviteEntity.ACTIVE).toInt()
        }

    /** Complete, enabled SMTP settings pointing at a port nothing ever binds: the sendability gate passes and the
     *  send then fails — the only way to reach the compensation path (ADR 0055 §6). */
    private fun pointAtDeadSmtp(tenantId: String, domain: String) =
        QuarkusTransaction.requiringNew().run {
            store.saveMailSettings(
                tenantId,
                MailSettingsRow(
                    host = "127.0.0.1", port = DEAD_SMTP_PORT, from = "noreply@$domain",
                    username = null, passwordCiphertext = null, passwordNonce = null,
                    tls = false, enabled = true, subjectTemplate = null, bodyTemplate = null,
                ),
            )
        }

    /** An ACTIVE tenant + its TENANT scope + a granted OWNER. Returns (tenantId, ownerUserId).
     *  Pass a [mailbox] whenever the test actually invites someone — without a sendable mailer that is now a 503. */
    private fun seedActiveTenant(domain: String, ownerEmail: String, mailbox: TestMailbox? = null): Pair<String, String> {
        val ownerId = seedUser(email = ownerEmail)
        val tenantId = seedTenant(domain = domain, ownerEmail = ownerEmail, owner = ownerId)
        mailbox?.receiveFor(store, tenantId, domain)
        return tenantId to ownerId
    }

    private fun seedMemberInvite(tenantId: String, email: String, secret: String) =
        QuarkusTransaction.requiringNew().run {
            store.mintMemberInvite(
                tenantId, email, Tokens.hash(secret), Instant.now().plus(7, ChronoUnit.DAYS),
                com.veyq.app.shared.membership.TestAdmission.admission(), checkEpoch = false,
            )
        }

    private fun membershipRole(userId: String, tenantId: String): String? =
        QuarkusTransaction.requiringNew().call {
            MembershipEntity.find("userId = ?1 and tenantId = ?2", userId, tenantId).firstResult()?.role
        }

    private fun grantOf(userId: String, tenantId: String): String? =
        QuarkusTransaction.requiringNew().call {
            MembershipEntity.find("userId = ?1 and tenantId = ?2", userId, tenantId).firstResult()?.wrappedGrant
        }

    /** A cold-birth register body with NO grant (a MEMBER joins grant-less). */
    private fun memberRegisterBody(secret: String) = """
        {"bootstrapSecret":"$secret","userId":"${UUID.randomUUID()}","username":"newmember",
         "account":{"suiteId":"v1",
           "identityPublicKey":"bWVtYmVyLXB1YmxpYy1rZXk=",
           "wrappedIdentityPrivateKey":"d3JhcHBlZC1pZGVudGl0eS1wcml2YXRlLWtleQ==",
           "authPublicKey":"ZWQyNTUxOS1hdXRoLXB1Yg==",
           "masterKeyWraps":[{"method":"PASSWORD","wrappedKey":"d3JhcHBlZC1tYXN0ZXIta2V5","kdfParams":"a2RmLXBhcmFtcw=="}],
           "secretKeyProofPublicKey":"c2VjcmV0LWtleS1wcm9vZi1wdWJsaWMta2V5"}}
    """.trimIndent()

    private companion object {
        // One JVM for the whole suite ⇒ distinct ports. SmtpInviteMailSendTest holds 13925-13929.
        const val MINT_SMTP_PORT = 13930
        const val MAILED_SMTP_PORT = 13931
        const val FLOOD_SMTP_PORT = 13932
        const val DEAD_SMTP_PORT = 13933 // nothing ever binds this — the send must fail (connection refused)
    }
}
