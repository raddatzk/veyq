package com.veyq.app.features.admin.tenant

import com.veyq.app.shared.crypto.Tokens
import com.veyq.app.shared.membership.TestAdmission
import com.veyq.app.testing.TestFixtures.bearer
import com.veyq.app.testing.TestFixtures.seedTenant
import com.veyq.app.testing.TestFixtures.seedUser
import com.veyq.app.testing.race
import com.veyq.app.shared.membership.MembershipEntity
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.not
import org.hamcrest.CoreMatchers.notNullValue
import org.hamcrest.CoreMatchers.nullValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/**
 * Slice tests for member-invite management (uc.spaces.manage-member-invites, cr-0082): the tenant **owner**
 * lists their pending MEMBER invites, **re-issues** one (superseding the old secret + minting a fresh copyable
 * link), and **revokes** one (soft-terminal so its secret replays as no-longer-valid). All membership-gated
 * first (a non-member gets 404, indistinguishable from an unknown tenant — ADR 0042), then owner-gated (403).
 * Distinct domains/emails **and seed secrets** so the JVM-lived H2 mem DB can't bleed state: `token_hash` carries
 * a **global** unique index (`UX_TENANT_INVITE_TOKEN_HASH`), so a secret reused across classes collides at commit
 * (green in isolation, `RollbackException` in the full suite) — treat a seeded secret as a suite-wide fixture.
 */
@QuarkusTest
class MemberInviteManagementSliceTest {

    @Inject lateinit var store: TenantStore

    @Test
    fun ownerListsPendingMemberInvitesOutsiderIs404() {
        val (tenantId, owner) = seedActiveTenant("list-mi.example.com", "owner@list-mi.example.com")
        seedMemberInvite(tenantId, "a@list-mi.example.com", "secret-a")
        seedMemberInvite(tenantId, "b@list-mi.example.com", "secret-b")

        given().header("Authorization", bearer(owner))
            .`when`().get("/api/tenants/$tenantId/member-invites")
            .then().statusCode(200).body("size()", equalTo(2))
            .body("find { it.email == 'a@list-mi.example.com' }.inviteStatus", equalTo("ACTIVE"))
            .body("find { it.email == 'a@list-mi.example.com' }.inviteId", notNullValue())
            .body("find { it.email == 'a@list-mi.example.com' }.inviteExpiresAt", notNullValue())

        // An outsider is not a member of the tenant, so they get the same answer as for a tenant that does not
        // exist — not a 403 that would confirm it (membership is the first guard, ADR 0042 / cr-0162).
        val outsider = seedUser(email = "outsider@list-mi.example.com")
        given().header("Authorization", bearer(outsider))
            .`when`().get("/api/tenants/$tenantId/member-invites")
            .then().statusCode(404).body("code", equalTo("tenant_not_found"))
    }

    @Test
    fun reissueSupersedesOldSecretAndMailsAUsableFreshLink(): Unit = TestMailbox(REISSUE_SMTP_PORT).start().use { mail ->
        val (tenantId, owner) = seedActiveTenant("reissue.example.com", "owner@reissue.example.com", mail)
        val oldSecret = "old-secret-1"
        val member = "member@reissue.example.com"
        seedMemberInvite(tenantId, member, oldSecret)
        val inviteId = firstPendingInviteId(tenantId)
        val admissionBefore = QuarkusTransaction.requiringNew().call { store.listPendingMemberInvites(tenantId).first().admission }

        given().header("Authorization", bearer(owner)).contentType(ContentType.JSON)
            .`when`().post("/api/tenants/$tenantId/member-invites/$inviteId/reissue")
            .then().statusCode(200)
            .body("email", equalTo(member))
            .body("inviteStatus", equalTo("ACTIVE"))
            .body("delivery", equalTo("SENT"))
            .body("bootstrapSecret", nullValue()) // ADR 0055 §2: the fresh secret goes to the invitee, not the owner
        val freshSecret = mail.awaitToken(member)
        assert(freshSecret != oldSecret)
        // A new secret is not a new admission (ADR 0020 move 6): the fresh invite keeps the one the owner tagged,
        // and the owner's list serves it so a tenant rotation can carry it forward.
        given().header("Authorization", bearer(owner)).`when`().get("/api/tenants/$tenantId/member-invites")
            .then().statusCode(200)
            .body("[0].admissionId", equalTo(admissionBefore!!.id))
            .body("[0].admissionEpoch", equalTo(0))
            .body("[0].admissionTag", equalTo(TestAdmission.TAG))

        // The OLD secret is dead — a register replay is rejected as no-longer-valid.
        given().contentType(ContentType.JSON).body(memberRegisterBody(oldSecret))
            .`when`().post("/api/tenants/accept/register")
            .then().statusCode(410).body("code", equalTo("invite_no_longer_valid"))

        // The FRESH secret actually joins the tenant grant-less.
        given().contentType(ContentType.JSON).body(memberRegisterBody(freshSecret))
            .`when`().post("/api/tenants/accept/register")
            .then().statusCode(201).body("status", equalTo("ACTIVE"))
    }

    @Test
    fun reissueOnATenantThatCannotSendIsRefusedAndLeavesTheOutstandingInviteAlone() {
        // ADR 0055 §2, and the ordering is the whole point: mintMemberInvite supersedes the outstanding invite as
        // it mints, so a refusal that came *after* it would leave the invitee with nothing on a tenant that could
        // never have sent in the first place. Refusing first keeps their existing link alive.
        val (tenantId, owner) = seedActiveTenant("reissue-nomail.example.com", "owner@reissue-nomail.example.com")
        val secret = "reissue-nomail-seed-secret" // token_hash is globally unique
        seedMemberInvite(tenantId, "m@reissue-nomail.example.com", secret)
        val inviteId = firstPendingInviteId(tenantId)

        given().header("Authorization", bearer(owner)).contentType(ContentType.JSON)
            .`when`().post("/api/tenants/$tenantId/member-invites/$inviteId/reissue")
            .then().statusCode(503).body("code", equalTo("mail_unavailable"))

        // Still exactly one live invite, and it is still the original one.
        assertEquals(inviteId, firstPendingInviteId(tenantId), "the outstanding invite must survive a refusal")
        given().contentType(ContentType.JSON).body(memberRegisterBody(secret))
            .`when`().post("/api/tenants/accept/register")
            .then().statusCode(201).body("status", equalTo("ACTIVE"))
    }

    @Test
    fun reissuingOneRecipientPastTheLimitIs429WithRetryAfter(): Unit = TestMailbox(REISSUE_FLOOD_SMTP_PORT).start().use { mail ->
        // Re-issue is the sharpest bombing vector (repeated fresh links to the same address). The default
        // per-recipient cap is 3, so the 4th re-issue to the same email is refused with 429 + Retry-After — before
        // any fresh mint. Unique tenant + email keeps the shared in-memory limiter isolated from other tests.
        val (tenantId, owner) = seedActiveTenant("reissue-flood.example.com", "owner@reissue-flood.example.com", mail)
        seedMemberInvite(tenantId, "victim@reissue-flood.example.com", "reissue-flood-seed-secret") // token_hash is globally unique
        repeat(3) {
            val id = firstPendingInviteId(tenantId)
            given().header("Authorization", bearer(owner)).contentType(ContentType.JSON)
                .`when`().post("/api/tenants/$tenantId/member-invites/$id/reissue")
                .then().statusCode(200)
        }
        val id = firstPendingInviteId(tenantId)
        given().header("Authorization", bearer(owner)).contentType(ContentType.JSON)
            .`when`().post("/api/tenants/$tenantId/member-invites/$id/reissue")
            .then().statusCode(429).header("Retry-After", notNullValue()).body("code", equalTo("rate_limited"))
    }

    @Test
    fun revokeMakesTheSecretNoLongerAcceptableAndDropsItFromTheList() {
        val (tenantId, owner) = seedActiveTenant("revoke.example.com", "owner@revoke.example.com")
        val secret = "revoke-secret-1"
        seedMemberInvite(tenantId, "member@revoke.example.com", secret)
        val inviteId = firstPendingInviteId(tenantId)

        given().header("Authorization", bearer(owner))
            .`when`().delete("/api/tenants/$tenantId/member-invites/$inviteId")
            .then().statusCode(204)

        // Gone from the owner's pending list.
        given().header("Authorization", bearer(owner))
            .`when`().get("/api/tenants/$tenantId/member-invites")
            .then().statusCode(200).body("size()", equalTo(0))

        // A replay of the revoked secret is rejected.
        given().contentType(ContentType.JSON).body(memberRegisterBody(secret))
            .`when`().post("/api/tenants/accept/register")
            .then().statusCode(410).body("code", equalTo("invite_no_longer_valid"))
    }

    @Test
    fun reissueAndRevokeOfAnUnknownInviteAre404() {
        val (tenantId, owner) = seedActiveTenant("unknown-mi.example.com", "owner@unknown-mi.example.com")
        val ghost = UUID.randomUUID().toString()

        given().header("Authorization", bearer(owner)).contentType(ContentType.JSON)
            .`when`().post("/api/tenants/$tenantId/member-invites/$ghost/reissue")
            .then().statusCode(404).body("code", equalTo("invite_not_found"))

        given().header("Authorization", bearer(owner))
            .`when`().delete("/api/tenants/$tenantId/member-invites/$ghost")
            .then().statusCode(404).body("code", equalTo("invite_not_found"))
    }

    // --- the invite is spent by a checked compare-and-set (developer-styleguide §12) ---

    @Test
    fun concurrentMemberAcceptsSpendTheInviteOnce() {
        // The MEMBER role on the existing-account path: today the loser either dies on the membership's unique
        // index (500) or reads the winner's membership and "consumes" the spent invite a second time (200).
        repeat(ROUNDS) { round ->
            val tag = "race-mbr-$round-${UUID.randomUUID()}"
            val (tenantId, _) = seedActiveTenant("$tag.example.com", "owner@$tag.example.com")
            seedMemberInvite(tenantId, "m@$tag.example.com", tag)
            val member = seedUser(email = "m@$tag.example.com")
            val auth = bearer(member)
            val answers = race({ memberAccept(auth, tag) }, { memberAccept(auth, tag) })
            assertEquals(1, answers.count { it.first == 200 }, "round $round: exactly one accept may win, got $answers")
            assertEquals(1, answers.count { it == (410 to "invite_no_longer_valid") }, "round $round: the loser answers as a replay, got $answers")
            assertEquals(1L, membershipsOf(member, tenantId), "round $round: one membership, got answers $answers")
        }
        val tag = "race-mbr-control-${UUID.randomUUID()}"
        val (tenantId, _) = seedActiveTenant("$tag.example.com", "owner@$tag.example.com")
        seedMemberInvite(tenantId, "m@$tag.example.com", tag)
        assertEquals(200 to null, memberAccept(bearer(seedUser(email = "m@$tag.example.com")), tag))
    }

    @Test
    fun aRevokeAndASpendOfOneInviteCannotBothWin() {
        // Either order, the second transition finds nothing live and changes nothing: a revoke that lost to an
        // accept must not re-tag the spent invite, and a spend that lost to a revoke must not join.
        val (tenantId, _) = seedActiveTenant("revoke-spend.example.com", "owner@revoke-spend.example.com")
        seedMemberInvite(tenantId, "first@revoke-spend.example.com", "revoke-spend-secret-1")
        val revokedFirst = firstPendingInviteId(tenantId)
        assertTrue(tx { store.revokeInvite(revokedFirst) }, "control: a live invite revokes")
        assertFalse(tx { store.consumeLiveInvite(revokedFirst) }, "a revoked invite cannot be spent")
        assertEquals(TenantInviteEntity.SUPERSEDED, statusOf(revokedFirst))

        seedMemberInvite(tenantId, "second@revoke-spend.example.com", "revoke-spend-secret-2")
        val spentFirst = firstPendingInviteId(tenantId)
        assertTrue(tx { store.consumeLiveInvite(spentFirst) }, "control: a live invite spends")
        assertFalse(tx { store.revokeInvite(spentFirst) }, "a spent invite cannot be revoked")
        assertEquals(TenantInviteEntity.CONSUMED, statusOf(spentFirst))
    }

    @Test
    fun aRotationDoesNotBringARevokedInviteBack() {
        // A tenant rotation reads the open invites under the scope lock, which a revoke does not take. A revoke
        // that commits between that read and the re-tag must stay a revoke: the re-tag writes only the admission.
        val (tenantId, _) = seedActiveTenant("retag-revoke.example.com", "owner@retag-revoke.example.com")
        seedMemberInvite(tenantId, "m@retag-revoke.example.com", "retag-revoke-secret")
        val inviteId = firstPendingInviteId(tenantId)
        val admissionId = tx { store.listPendingMemberInvites(tenantId).first().admission!!.id }
        val read = CountDownLatch(1)
        val revoked = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val rotation = pool.submit {
                QuarkusTransaction.requiringNew().run {
                    val invites = store.openMemberInvites(tenantId) // what the rotation was shown
                    read.countDown()
                    revoked.await()
                    store.carryInvites(invites, newEpoch = 1, retags = mapOf(admissionId to RETAGGED), dropped = emptySet())
                }
            }
            read.await()
            assertTrue(tx { store.revokeInvite(inviteId) })
            revoked.countDown()
            rotation.get()
        } finally {
            pool.shutdownNow()
        }
        assertEquals(TenantInviteEntity.SUPERSEDED, statusOf(inviteId), "the revoked secret must stay revoked")
        assertEquals(TestAdmission.TAG, tx { TenantInviteEntity.findById(inviteId)!!.admissionTag }, "a revoked invite has nothing left to carry")

        // Control: an invite nobody revoked is carried — the re-tag is not simply inert.
        seedMemberInvite(tenantId, "live@retag-revoke.example.com", "retag-live-secret")
        val live = tx { store.openMemberInvites(tenantId).single() }
        tx { store.carryInvites(store.openMemberInvites(tenantId), newEpoch = 1, retags = mapOf(live.admissionId!! to RETAGGED), dropped = emptySet()) }
        val carried = tx { TenantInviteEntity.findById(live.id)!!.let { Triple(it.status, it.admissionEpoch, it.admissionTag) } }
        assertEquals(Triple(TenantInviteEntity.ACTIVE, 1, RETAGGED), carried)
    }

    @Test
    fun manageOnAnUnknownTenantIs404NotFound() {
        val owner = seedUser(email = "stray@nowhere-mi.example.com")
        val ghostTenant = UUID.randomUUID().toString()
        given().header("Authorization", bearer(owner))
            .`when`().get("/api/tenants/$ghostTenant/member-invites")
            .then().statusCode(404).body("code", equalTo("tenant_not_found"))
    }

    // --- helpers ---

    /** An ACTIVE tenant + its TENANT scope + a granted OWNER. Returns (tenantId, ownerUserId). */
    /** Pass a [mailbox] whenever the test re-issues an invite — without a sendable mailer that is now a 503. */
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
                TestAdmission.admission(), checkEpoch = false,
            )
        }

    private fun firstPendingInviteId(tenantId: String): String =
        QuarkusTransaction.requiringNew().call { store.listPendingMemberInvites(tenantId).first().inviteId }

    private fun <T> tx(body: () -> T): T = QuarkusTransaction.requiringNew().call(body)

    private fun statusOf(inviteId: String): String = tx { TenantInviteEntity.findById(inviteId)!!.status }

    private fun membershipsOf(userId: String, tenantId: String): Long =
        tx { MembershipEntity.count("userId = ?1 and scopeId = ?2", userId, tenantId) }

    /** An existing-account MEMBER accept (no grant), as (status, error code). */
    private fun memberAccept(auth: String, secret: String): Pair<Int, String?> =
        given().header("Authorization", auth).contentType(ContentType.JSON)
            .body("""{"bootstrapSecret":"$secret","username":"racer"}""")
            .`when`().post("/api/tenants/accept").then().extract().let { it.statusCode() to it.path<String?>("code") }

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
        // One JVM for the whole suite ⇒ distinct ports. SmtpInviteMailSendTest holds 13925-13929,
        // MemberAddSliceTest 13930-13933.
        const val REISSUE_SMTP_PORT = 13934
        const val REISSUE_FLOOD_SMTP_PORT = 13935

        /** One round of a race can pass by luck; each round spends a fresh secret. */
        const val ROUNDS = 5

        /** 32 bytes of base64, distinct from [TestAdmission.TAG] — what a rotation re-tags an invite to. */
        const val RETAGGED = "cmV0YWdnZWQtcmV0YWdnZWQtcmV0YWdnZWQtcmV0YWc="
    }
}
