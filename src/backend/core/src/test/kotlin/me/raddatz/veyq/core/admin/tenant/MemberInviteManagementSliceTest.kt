package me.raddatz.veyq.core.admin.tenant

import me.raddatz.veyq.core.crypto.Tokens
import me.raddatz.veyq.core.membership.TestGrants
import me.raddatz.veyq.core.membership.TestInviteKey
import me.raddatz.veyq.core.testing.TestFixtures.IDENTITY_PUBLIC_KEY
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedTenant
import me.raddatz.veyq.core.testing.TestFixtures.seedUser
import me.raddatz.veyq.core.testing.race
import me.raddatz.veyq.core.testing.whileUncommitted
import me.raddatz.veyq.core.testing.raceRounds
import me.raddatz.veyq.core.spaces.scope.InviteGrant
import me.raddatz.veyq.core.spaces.scope.RotationDecision
import me.raddatz.veyq.core.spaces.scope.RotationGrant
import me.raddatz.veyq.core.spaces.scope.ScopesStore
import me.raddatz.veyq.core.membership.SignedGrant
import me.raddatz.veyq.core.membership.MembershipEntity
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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/**
 * Slice tests for member-invite management (uc.spaces.manage-member-invites, cr-0082, ADR 0111): the tenant **owner**
 * lists their pending MEMBER invites, **re-issues** one (superseding the old invite — its key and grant with it — and
 * minting the fresh one the owner's client built: a new key, grant and link), and **revokes** one (soft-terminal so
 * its secret replays as no-longer-valid). All membership-gated first (a non-member gets 404, indistinguishable from an
 * unknown tenant — ADR 0042), then owner-gated (403). Distinct domains/emails **and seed secrets** so the JVM-lived H2
 * mem DB can't bleed state: `token_hash` carries a **global** unique index (`UX_TENANT_INVITE_TOKEN_HASH`), so a
 * secret reused across classes collides at commit (green in isolation, `RollbackException` in the full suite) — treat
 * a seeded secret as a suite-wide fixture.
 */
@QuarkusTest
class MemberInviteManagementSliceTest {

    @Inject lateinit var store: TenantStore
    @Inject lateinit var scopes: ScopesStore

    @Test
    fun ownerListsPendingMemberInvitesOutsiderIs404() {
        val (tenantId, owner) = seedActiveTenant("list-mi.example.com", "owner@list-mi.example.com")
        seedMemberInvite(tenantId, owner, "a@list-mi.example.com", "secret-a")
        seedMemberInvite(tenantId, owner, "b@list-mi.example.com", "secret-b")

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
        val oldKey = seedMemberInvite(tenantId, owner, member, oldSecret)
        val inviteId = firstPendingInviteId(tenantId)
        val freshKey = TestInviteKey()

        reissue(owner, tenantId, inviteId, member, freshKey)
            .then().statusCode(200)
            .body("email", equalTo(member))
            .body("inviteStatus", equalTo("ACTIVE"))
            .body("delivery", equalTo("SENT"))
            .body("bootstrapSecret", nullValue()) // ADR 0055 §2: the fresh secret goes to the invitee, not the owner
        val freshSecret = mail.awaitToken(member)
        assert(freshSecret != oldSecret)
        // A reissue is a new invite (ADR 0111 decision 2): the fresh one holds the new key, the old one none.
        assertEquals(freshKey.inviteId, firstPendingInviteId(tenantId))
        assertEquals(freshKey.bundle, tx { TenantInviteEntity.findById(freshKey.inviteId)!!.invitePublicKey })
        assertNull(tx { TenantInviteEntity.findById(inviteId)!!.invitePublicKey }, "the superseded invite's key is dropped")

        // The OLD secret is dead — a register replay is rejected as no-longer-valid.
        given().contentType(ContentType.JSON).body(memberRegisterBody(oldSecret, oldKey))
            .`when`().post("/api/tenants/accept/register")
            .then().statusCode(410).body("code", equalTo("invite_no_longer_valid"))

        // The FRESH secret, with the fresh key's proof, joins the tenant.
        given().contentType(ContentType.JSON).body(memberRegisterBody(freshSecret, freshKey))
            .`when`().post("/api/tenants/accept/register")
            .then().statusCode(201).body("status", equalTo("ACTIVE"))
    }

    @Test
    fun aReissueForAnotherAddressIsRefusedAndLeavesTheInviteAlone(): Unit = TestMailbox(REISSUE_EMAIL_SMTP_PORT).start().use { mail ->
        // A reissue re-sends to the SAME person: a body naming another address is refused, nothing minted.
        val (tenantId, owner) = seedActiveTenant("reissue-email.example.com", "owner@reissue-email.example.com", mail)
        seedMemberInvite(tenantId, owner, "m@reissue-email.example.com", "reissue-email-seed-secret")
        val inviteId = firstPendingInviteId(tenantId)

        reissue(owner, tenantId, inviteId, "other@reissue-email.example.com", TestInviteKey())
            .then().statusCode(422).body("code", equalTo("invite_email_mismatch"))
        assertEquals(inviteId, firstPendingInviteId(tenantId), "the outstanding invite must survive a refusal")
        // Control: the same reissue for the invite's own address goes through.
        reissue(owner, tenantId, inviteId, "m@reissue-email.example.com", TestInviteKey()).then().statusCode(200)
    }

    @Test
    fun reissueOnATenantThatCannotSendIsRefusedAndLeavesTheOutstandingInviteAlone() {
        // ADR 0055 §2, and the ordering is the whole point: mintMemberInvite supersedes the outstanding invite as
        // it mints, so a refusal that came *after* it would leave the invitee with nothing on a tenant that could
        // never have sent in the first place. Refusing first keeps their existing link alive.
        val (tenantId, owner) = seedActiveTenant("reissue-nomail.example.com", "owner@reissue-nomail.example.com")
        val secret = "reissue-nomail-seed-secret" // token_hash is globally unique
        val email = "m@reissue-nomail.example.com"
        val key = seedMemberInvite(tenantId, owner, email, secret)
        val inviteId = firstPendingInviteId(tenantId)

        reissue(owner, tenantId, inviteId, email, TestInviteKey())
            .then().statusCode(503).body("code", equalTo("mail_unavailable"))

        // Still exactly one live invite, and it is still the original one.
        assertEquals(inviteId, firstPendingInviteId(tenantId), "the outstanding invite must survive a refusal")
        given().contentType(ContentType.JSON).body(memberRegisterBody(secret, key))
            .`when`().post("/api/tenants/accept/register")
            .then().statusCode(201).body("status", equalTo("ACTIVE"))
    }

    @Test
    fun reissuingOneRecipientPastTheLimitIs429WithRetryAfter(): Unit = TestMailbox(REISSUE_FLOOD_SMTP_PORT).start().use { mail ->
        // Re-issue is the sharpest bombing vector (repeated fresh links to the same address). The default
        // per-recipient cap is 3, so the 4th re-issue to the same email is refused with 429 + Retry-After — before
        // any fresh mint. Unique tenant + email keeps the shared in-memory limiter isolated from other tests.
        val (tenantId, owner) = seedActiveTenant("reissue-flood.example.com", "owner@reissue-flood.example.com", mail)
        val victim = "victim@reissue-flood.example.com"
        seedMemberInvite(tenantId, owner, victim, "reissue-flood-seed-secret") // token_hash is globally unique
        repeat(3) {
            reissue(owner, tenantId, firstPendingInviteId(tenantId), victim, TestInviteKey()).then().statusCode(200)
        }
        reissue(owner, tenantId, firstPendingInviteId(tenantId), victim, TestInviteKey())
            .then().statusCode(429).header("Retry-After", notNullValue()).body("code", equalTo("rate_limited"))
    }

    @Test
    fun revokeMakesTheSecretNoLongerAcceptableAndDropsItFromTheList() {
        val (tenantId, owner) = seedActiveTenant("revoke.example.com", "owner@revoke.example.com")
        val secret = "revoke-secret-1"
        val key = seedMemberInvite(tenantId, owner, "member@revoke.example.com", secret)
        val inviteId = firstPendingInviteId(tenantId)

        given().header("Authorization", bearer(owner))
            .`when`().delete("/api/tenants/$tenantId/member-invites/$inviteId")
            .then().statusCode(204)

        // Gone from the owner's pending list.
        given().header("Authorization", bearer(owner))
            .`when`().get("/api/tenants/$tenantId/member-invites")
            .then().statusCode(200).body("size()", equalTo(0))

        // A replay of the revoked secret is rejected.
        given().contentType(ContentType.JSON).body(memberRegisterBody(secret, key))
            .`when`().post("/api/tenants/accept/register")
            .then().statusCode(410).body("code", equalTo("invite_no_longer_valid"))
    }

    @Test
    fun reissueAndRevokeOfAnUnknownInviteAre404() {
        val (tenantId, owner) = seedActiveTenant("unknown-mi.example.com", "owner@unknown-mi.example.com")
        val ghost = UUID.randomUUID().toString()

        reissue(owner, tenantId, ghost, "ghost@unknown-mi.example.com", TestInviteKey())
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
            val (tenantId, owner) = seedActiveTenant("$tag.example.com", "owner@$tag.example.com")
            val key = seedMemberInvite(tenantId, owner, "m@$tag.example.com", tag)
            val member = seedUser(email = "m@$tag.example.com")
            val auth = bearer(member)
            val answers = race({ memberAccept(auth, tag, key, member) }, { memberAccept(auth, tag, key, member) })
            assertEquals(1, answers.count { it.first == 200 }, "round $round: exactly one accept may win, got $answers")
            assertEquals(1, answers.count { it == (410 to "invite_no_longer_valid") }, "round $round: the loser answers as a replay, got $answers")
            assertEquals(1L, membershipsOf(member, tenantId), "round $round: one membership, got answers $answers")
        }
        val tag = "race-mbr-control-${UUID.randomUUID()}"
        val (tenantId, owner) = seedActiveTenant("$tag.example.com", "owner@$tag.example.com")
        val key = seedMemberInvite(tenantId, owner, "m@$tag.example.com", tag)
        val member = seedUser(email = "m@$tag.example.com")
        assertEquals(200 to null, memberAccept(bearer(member), tag, key, member))
    }

    @Test
    fun aRevokeAndASpendOfOneInviteCannotBothWin() {
        // Either order, the second transition finds nothing live and changes nothing: a revoke that lost to an
        // accept must not touch the spent invite, and a spend that lost to a revoke must not join.
        val (tenantId, owner) = seedActiveTenant("revoke-spend.example.com", "owner@revoke-spend.example.com")
        seedMemberInvite(tenantId, owner, "first@revoke-spend.example.com", "revoke-spend-secret-1")
        val revokedFirst = firstPendingInviteId(tenantId)
        assertTrue(tx { store.revokeInvite(revokedFirst) }, "control: a live invite revokes")
        assertFalse(tx { store.consumeLiveInvite(revokedFirst) }, "a revoked invite cannot be spent")
        assertEquals(TenantInviteEntity.SUPERSEDED, statusOf(revokedFirst))

        seedMemberInvite(tenantId, owner, "second@revoke-spend.example.com", "revoke-spend-secret-2")
        val spentFirst = firstPendingInviteId(tenantId)
        assertTrue(tx { store.consumeLiveInvite(spentFirst) }, "control: a live invite spends")
        assertFalse(tx { store.revokeInvite(spentFirst) }, "a spent invite cannot be revoked")
        assertEquals(TenantInviteEntity.CONSUMED, statusOf(spentFirst))
    }

    @Test
    fun aRotationDoesNotBringARevokedInviteBack() {
        // A tenant rotation reads the open invites under the scope lock, which a revoke does not take. A revoke
        // that commits between that read and the re-grant must stay a revoke: the re-grant writes only the grant
        // columns, and only of a still-ACTIVE invite — a revoked one keeps no key and gets no grant.
        val (tenantId, owner) = seedActiveTenant("retag-revoke.example.com", "owner@retag-revoke.example.com")
        seedMemberInvite(tenantId, owner, "m@retag-revoke.example.com", "retag-revoke-secret")
        val inviteId = firstPendingInviteId(tenantId)
        val read = CountDownLatch(1)
        val revoked = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val rotation = pool.submit {
                QuarkusTransaction.requiringNew().run {
                    val invites = store.openMemberInvites(tenantId) // what the rotation was shown
                    read.countDown()
                    revoked.await()
                    store.carryInvites(invites.map { InviteRegrant(it.id, REGRANT) }, newEpoch = 1, rotatorId = owner)
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
        assertEquals(null to null, tx { TenantInviteEntity.findById(inviteId)!!.let { it.wrappedGrant to it.grantedEpoch } },
            "a revoked invite gets no grant")

        // Control: an invite nobody revoked is re-granted — the carry is not simply inert.
        seedMemberInvite(tenantId, owner, "live@retag-revoke.example.com", "retag-live-secret")
        val live = tx { store.openMemberInvites(tenantId).single() }
        tx { store.carryInvites(listOf(InviteRegrant(live.id, REGRANT)), newEpoch = 1, rotatorId = owner) }
        val carried = tx { TenantInviteEntity.findById(live.id)!!.let { Triple(it.status, it.grantedEpoch, it.wrappedGrant) } }
        assertEquals(Triple(TenantInviteEntity.ACTIVE, 1, REGRANT.wrappedGrant), carried)
    }

    @Test
    fun manageOnAnUnknownTenantIs404NotFound() {
        val owner = seedUser(email = "stray@nowhere-mi.example.com")
        val ghostTenant = UUID.randomUUID().toString()
        given().header("Authorization", bearer(owner))
            .`when`().get("/api/tenants/$ghostTenant/member-invites")
            .then().statusCode(404).body("code", equalTo("tenant_not_found"))
    }

    // --- an invite written while a tenant rotation holds the scope row (cr-a-gate-holds-until-its-write-commits) ---

    @Test
    fun anInviteMintedAtAnEpochARotationIsLeavingIsStale(): Unit = TestMailbox(STALE_MINT_SMTP_PORT).start().use { mail ->
        // The mint compares the invite grant's epoch with the tenant scope's under the rotation's lock. The gate before
        // it had loaded that scope row, and a lock on a loaded row handed back the copy from before the lock: a
        // grant of the epoch the rotation left was minted, behind a rotation that could not carry it.
        raceRounds(ROUNDS) { round ->
            val domain = "stale-mint-$round-${UUID.randomUUID().toString().take(8)}.example.com" // fresh per attempt: raceRounds may run a round again
            val (tenantId, owner) = seedActiveTenant(domain, "owner@$domain", mail)
            val atLeft = """{${TestGrants.memberInviteJson(tenantId, "m@$domain", epoch = 0)}}"""
            val answer = whileUncommitted(
                change = { assertTrue(rotateTenant(tenantId, owner) is RotationDecision.Commit, "round $round") },
                overlapping = { invite(owner, tenantId, atLeft).statusCode() },
            )
            assertEquals(409, answer, "round $round: a grant of the left epoch is stale")
            assertTrue(tx { store.openMemberInvites(tenantId) }.isEmpty(), "round $round: nothing minted")
            // Control: the invite at the epoch the rotation reached is minted.
            invite(owner, tenantId, """{${TestGrants.memberInviteJson(tenantId, "m@$domain", epoch = 1)}}""").then().statusCode(201)
        }
    }

    @Test
    fun aReissueBuiltAtTheEpochARotationLeftIsStale(): Unit = TestMailbox(STALE_REISSUE_SMTP_PORT).start().use { mail ->
        // A reissue mints the invite the owner's client built: its grant of the epoch the client read. Racing a
        // tenant rotation, it waits on the rotation's lock and then finds that epoch left — refused, nothing written,
        // so no invite can hold a grant of an epoch nobody re-granted it from; the outstanding invite, which the
        // rotation carried, stays.
        raceRounds(ROUNDS) { round ->
            val domain = "stale-reissue-$round-${UUID.randomUUID().toString().take(8)}.example.com" // fresh per attempt
            val email = "m@$domain"
            val (tenantId, owner) = seedActiveTenant(domain, "owner@$domain", mail)
            val key = seedMemberInvite(tenantId, owner, email, "stale-reissue-secret-$domain")
            val inviteId = firstPendingInviteId(tenantId)
            val atLeft = """{${TestGrants.memberInviteJson(tenantId, email, epoch = 0)}}"""
            val answer = whileUncommitted(
                change = {
                    val decision = rotateTenant(tenantId, owner, listOf(InviteGrant(inviteId, REGRANT, TestGrants.digest(key.bundle))))
                    assertTrue(decision is RotationDecision.Commit, "round $round: $decision")
                },
                overlapping = {
                    given().header("Authorization", bearer(owner)).contentType(ContentType.JSON).body(atLeft)
                        .`when`().post("/api/tenants/$tenantId/member-invites/$inviteId/reissue").statusCode()
                },
            )
            assertEquals(409, answer, "round $round: a reissue at the left epoch is stale")
            val open = tx { store.openMemberInvites(tenantId).single() }
            assertEquals(inviteId to 1, open.id to open.grantedEpoch, "round $round: the carried invite stays, at the new epoch")
            // Control: the reissue built at the epoch the rotation reached goes through.
            given().header("Authorization", bearer(owner)).contentType(ContentType.JSON)
                .body("""{${TestGrants.memberInviteJson(tenantId, email, epoch = 1)}}""")
                .`when`().post("/api/tenants/$tenantId/member-invites/$inviteId/reissue").then().statusCode(200)
        }
    }

    private fun rotateTenant(tenantId: String, owner: String, inviteGrants: List<InviteGrant> = emptyList()) = scopes.rotate(
        tenantId, 1, "cHJldg==", owner, listOf(RotationGrant(owner, SignedGrant("Z3JhbnQ=", TestGrants.SIGNATURE), TestGrants.digestOf(owner))),
        inviteGrants, emptySet(),
    )

    private fun invite(owner: String, tenantId: String, body: String) =
        given().header("Authorization", bearer(owner)).contentType(ContentType.JSON).body(body)
            .`when`().post("/api/tenants/$tenantId/member-invites")

    private fun reissue(owner: String, tenantId: String, inviteId: String, email: String, key: TestInviteKey) =
        given().header("Authorization", bearer(owner)).contentType(ContentType.JSON)
            .body("""{${TestGrants.memberInviteJson(tenantId, email, key)}}""")
            .`when`().post("/api/tenants/$tenantId/member-invites/$inviteId/reissue")

    // --- helpers ---

    /** An ACTIVE tenant + its TENANT scope + a granted OWNER. Returns (tenantId, ownerUserId). */
    /** Pass a [mailbox] whenever the test re-issues an invite — without a sendable mailer that is now a 503. */
    private fun seedActiveTenant(domain: String, ownerEmail: String, mailbox: TestMailbox? = null): Pair<String, String> {
        val ownerId = seedUser(email = ownerEmail)
        val tenantId = seedTenant(domain = domain, ownerEmail = ownerEmail, owner = ownerId)
        mailbox?.receiveFor(store, tenantId, domain)
        return tenantId to ownerId
    }

    /** A MEMBER invite signed by [owner], its grant of the tenant's `E_0`, stored as the handler stores one. */
    private fun seedMemberInvite(tenantId: String, owner: String, email: String, secret: String): TestInviteKey {
        val key = TestInviteKey()
        QuarkusTransaction.requiringNew().run {
            store.mintMemberInvite(tenantId, email, Tokens.hash(secret), Instant.now().plus(7, ChronoUnit.DAYS), key.stored(owner))
        }
        return key
    }

    private fun firstPendingInviteId(tenantId: String): String =
        QuarkusTransaction.requiringNew().call { store.listPendingMemberInvites(tenantId).first().inviteId }

    private fun <T> tx(body: () -> T): T = QuarkusTransaction.requiringNew().call(body)

    private fun statusOf(inviteId: String): String = tx { TenantInviteEntity.findById(inviteId)!!.status }

    private fun membershipsOf(userId: String, tenantId: String): Long =
        tx { MembershipEntity.count("userId = ?1 and scopeId = ?2", userId, tenantId) }

    /** An existing-account MEMBER accept with its join under [key], as (status, error code). */
    private fun memberAccept(auth: String, secret: String, key: TestInviteKey, userId: String): Pair<Int, String?> =
        given().header("Authorization", auth).contentType(ContentType.JSON)
            .body("""{"bootstrapSecret":"$secret","username":"racer",${TestGrants.memberJoinJson(key, key.inviteId, userId, IDENTITY_PUBLIC_KEY)}}""")
            .`when`().post("/api/tenants/accept").then().extract().let { it.statusCode() to it.path<String?>("code") }

    /** A cold-birth register body with the MEMBER join under [key] (the invite it redeems). */
    private fun memberRegisterBody(secret: String, key: TestInviteKey): String {
        val userId = UUID.randomUUID().toString()
        return """
        {"bootstrapSecret":"$secret","userId":"$userId","username":"newmember",
         ${TestGrants.memberJoinJson(key, key.inviteId, userId, MEMBER_IDENTITY)},
         "account":{"suiteId":"v1",
           "identityPublicKey":"$MEMBER_IDENTITY",
           "wrappedIdentityPrivateKey":"d3JhcHBlZC1pZGVudGl0eS1wcml2YXRlLWtleQ==",
           "authPublicKey":"ZWQyNTUxOS1hdXRoLXB1Yg==",
           "masterKeyWraps":[{"method":"PASSWORD","wrappedKey":"d3JhcHBlZC1tYXN0ZXIta2V5","kdfParams":"a2RmLXBhcmFtcw=="}],
           "secretKeyProofPublicKey":"c2VjcmV0LWtleS1wcm9vZi1wdWJsaWMta2V5"}}
        """.trimIndent()
    }

    private companion object {
        // One JVM for the whole suite ⇒ distinct ports. SmtpInviteMailSendTest holds 13925-13929,
        // MemberAddSliceTest 13930-13933.
        const val REISSUE_SMTP_PORT = 13934
        const val REISSUE_FLOOD_SMTP_PORT = 13935
        const val STALE_MINT_SMTP_PORT = 13942
        const val STALE_REISSUE_SMTP_PORT = 13943
        const val REISSUE_EMAIL_SMTP_PORT = 13947

        /** One round of a race can pass by luck; each round spends a fresh secret. */
        const val ROUNDS = 5

        /** The identity bundle a cold-born invitee registers. */
        const val MEMBER_IDENTITY = "bWVtYmVyLXB1YmxpYy1rZXk="

        /** What a rotation re-grants an invite: a wrap distinct from [TestGrants.WRAPPED], the one it was minted with. */
        val REGRANT = SignedGrant("cmVncmFudGVkLXRvLWUx", TestGrants.SIGNATURE)
    }
}
