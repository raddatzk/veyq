package me.raddatz.veyq.core.account.email

import me.raddatz.veyq.core.account.recovery.RecoveryHandler
import me.raddatz.veyq.core.account.recovery.RecoveryStore
import me.raddatz.veyq.core.account.recovery.RequestOutcome
import me.raddatz.veyq.core.admin.tenant.MailSettingsRow
import me.raddatz.veyq.core.admin.tenant.TenantStore
import me.raddatz.veyq.core.admin.tenant.TestMailbox
import me.raddatz.veyq.core.crypto.SecretKeyProof
import me.raddatz.veyq.core.crypto.TestProofKey
import me.raddatz.veyq.core.crypto.Tokens
import me.raddatz.veyq.core.identity.MasterKeyWrapEntity
import me.raddatz.veyq.core.identity.TenantProfileStore
import me.raddatz.veyq.core.identity.VeyqUser
import me.raddatz.veyq.core.membership.TenantRoots
import me.raddatz.veyq.core.membership.TestGrants
import me.raddatz.veyq.core.membership.TestInviteKey
import me.raddatz.veyq.core.membership.MembershipEntity
import me.raddatz.veyq.core.tenant.ResolvedTenant
import me.raddatz.veyq.core.tenant.TenantEntity
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.newUser
import me.raddatz.veyq.core.testing.TestFixtures.seedTenant
import me.raddatz.veyq.core.testing.TestFixtures.seedUser
import io.quarkus.narayana.jta.QuarkusTransaction
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.equalTo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Slice tests for address confirmation (uc.account.confirm-email-address, ADR 0074).
 *
 * The record is a **fact set by four events and gating nothing**, so the file has two halves: the three events
 * that must set it (and the neighbouring events that must not — each the positive control of the one above it),
 * and the confirmation flow's refusals, every one with a live control on the same predicate. The GreenMail
 * mailbox is the oracle for "mailed": the handler cannot repair what did or did not arrive there.
 *
 * Every test makes its own accounts and its own tenant on its own domain: the `%test` database and the in-memory
 * limiters live as long as the JVM.
 */
@QuarkusTest
class EmailConfirmationSliceTest {

    @Inject
    lateinit var tenantStore: TenantStore

    @Inject
    lateinit var profiles: TenantProfileStore

    @Inject
    lateinit var store: EmailConfirmationStore

    @Inject
    lateinit var recoveryStore: RecoveryStore

    @Inject
    lateinit var recovery: RecoveryHandler

    // --- three of the four events that set the record (ADR 0074 Decision 2; the fourth is EmailChangeSliceTest's) ---

    @Test
    fun memberInviteRedemptionConfirms() {
        // A member invite only ever arrives by mail (ADR 0055 §2), so redeeming it is the proof.
        val tenantId = seedTenant(owner = seedUser())
        val email = fresh("member-reg")
        val (secret, key) = memberInvite(tenantId, email)
        val userId = UUID.randomUUID().toString()
        given().contentType(ContentType.JSON).body(registerBody(secret, userId, grant = false, key = key))
            .`when`().post("/api/tenants/accept/register").then().statusCode(201)
        assertTrue(confirmed(userId), "a redeemed MEMBER invite proves the mailbox")

        // Control: the bootstrap OWNER invite is the copy-link, which never went through the mailbox.
        val ownerEmail = fresh("owner-reg")
        val ownerSecret = pendingTenant(ownerEmail)
        val ownerId = UUID.randomUUID().toString()
        given().contentType(ContentType.JSON).body(registerBody(ownerSecret, ownerId, grant = true))
            .`when`().post("/api/tenants/accept/register").then().statusCode(201)
        assertFalse(confirmed(ownerId), "the copy-link bootstrap owner is unconfirmed")
    }

    @Test
    fun existingAccountMemberAcceptConfirms() {
        val tenantId = seedTenant(owner = seedUser())

        // All three sub-branches of the sign-in MEMBER accept: a fresh join, a rejoin (profile outlived a removal),
        // and an already-member consume. Each redeemed a mailed invite.
        val freshJoin = seedUser(email = fresh("fresh-join"))
        val rejoin = seedUser(email = fresh("rejoin")).also { id ->
            QuarkusTransaction.requiringNew().run { profiles.create(id, tenantId, handle("rejoin")) }
        }
        val already = seedUser(email = fresh("already")).also { id ->
            QuarkusTransaction.requiringNew().run { TenantRoots.join(id, tenantId) }
        }
        for (userId in listOf(freshJoin, rejoin, already)) {
            val (secret, key) = memberInvite(tenantId, emailOf(userId))
            given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
                .body("""{"bootstrapSecret":"$secret","username":"${handle("acc")}",${TestGrants.memberJoinJson(key, key.inviteId, userId, IDENTITY)}}""")
                .`when`().post("/api/tenants/accept").then().statusCode(200)
            assertTrue(confirmed(userId), "a sign-in MEMBER accept proves the mailbox")
        }

        // Control: an OWNER sign-in accept of the bootstrap copy-link proves nothing.
        val owner = seedUser(email = fresh("owner-acc"))
        val ownerSecret = pendingTenant(emailOf(owner))
        given().header("Authorization", bearer(owner)).contentType(ContentType.JSON)
            .body("""{"bootstrapSecret":"$ownerSecret","username":"${handle("own")}","grant":"AQ==",${TestGrants.SIGNATURE_JSON}}""")
            .`when`().post("/api/tenants/accept").then().statusCode(200)
        assertFalse(confirmed(owner), "an OWNER sign-in accept is not a mailbox proof")
    }

    @Test
    fun completedResetConfirms() {
        val userId = resettableAccount(fresh("reset"))
        val token = Tokens.random()
        QuarkusTransaction.requiringNew().run {
            recoveryStore.mint(userId, emailOf(userId), Tokens.hash(token), Instant.now().plus(1, ChronoUnit.HOURS))
        }

        // Control on the same account: a release alone is not a completed reset.
        given().contentType(ContentType.JSON).body("""{"token":"$token"}""")
            .`when`().post("/api/recovery/release").then().statusCode(200)
        assertFalse(confirmed(userId), "a release without a completed reset confirms nothing")

        val statement = SecretKeyProof.recoveryStatement(token, NEW_AUTH_KEY, SecretKeyProof.Wrap(NEW_WRAP, NEW_KDF))
        given().contentType(ContentType.JSON)
            .body("""{"token":"$token","secretKeyProofSignature":"${PROOF_KEY.sign(statement)}","wrappedKey":"$NEW_WRAP","kdfParams":"$NEW_KDF","authPublicKey":"$NEW_AUTH_KEY"}""")
            .`when`().post("/api/recovery/complete").then().statusCode(204)
        assertTrue(confirmed(userId), "a completed reset is the mailed token plus the Secret Key")
    }

    // --- the confirmation flow ---

    @Test
    fun confirmNeedsTheAccountsOwnSession(): Unit = TestMailbox(SESSION_SMTP_PORT).start().use { mail ->
        val tenant = sendableTenant(mail)
        val a = member(tenant.id, fresh("session-a"))
        val b = member(tenant.id, fresh("session-b"))
        requestConfirmation(a, tenant.domain).then().statusCode(202)
        val token = mail.awaitToken(emailOf(a))

        // Whoever reads a mistyped mailbox holds the link, and must not be able to confirm with it.
        confirm(b, token).then().statusCode(404).body("code", equalTo("email_confirmation_invalid"))
        assertFalse(confirmed(a), "another account's session confirms nothing")
        assertEquals(listOf(EmailConfirmationEntity.PENDING), statuses(a), "a refusal spends nothing")

        // Control: the account's own session, with the very same token.
        confirm(a, token).then().statusCode(204)
        assertTrue(confirmed(a))
    }

    @Test
    fun aTokenConfirmsOnlyForTheAccountItWasMintedFor() {
        // The two bindings are layers of one defence, and the test above cannot tell them apart: B's address differs
        // from the mailed one, so the address check refuses B on its own. This isolates the ACCOUNT binding by
        // holding the address check off — A's token was mailed to an address A has since left and B now holds, so
        // B's address equals the mailed one and only the account id still says no.
        val address = fresh("handed-on")
        val a = seedUser(email = address)
        val token = mint(a, address)
        QuarkusTransaction.requiringNew().run { VeyqUser.findById(a)!!.email = fresh("a-moved") }
        val b = seedUser(email = address)

        confirm(b, token).then().statusCode(404).body("code", equalTo("email_confirmation_invalid"))
        assertFalse(confirmed(b), "a token minted for another account confirms nothing")

        // Control: B's own token for the same address.
        confirm(b, mint(b, address)).then().statusCode(204)
        assertTrue(confirmed(b))
    }

    @Test
    fun confirmRefusesAnAddressTheAccountNoLongerHolds() {
        val userId = seedUser(email = fresh("moved"))
        val token = mint(userId, emailOf(userId))
        // No flow changes an address today, so the move is written straight to the row: the binding is the rule
        // (ADR 0074 Decision 1), whatever path a future change takes.
        val moved = fresh("moved-to")
        QuarkusTransaction.requiringNew().run { VeyqUser.findById(userId)!!.email = moved }
        confirm(userId, token).then().statusCode(404).body("code", equalTo("email_confirmation_invalid"))
        assertFalse(confirmed(userId))

        // Control: a token mailed to the address the account holds now.
        confirm(userId, mint(userId, moved)).then().statusCode(204)
        assertTrue(confirmed(userId))
    }

    @Test
    fun supersededTokenConfirmsNothing() {
        val userId = seedUser(email = fresh("supersede"))
        val first = mint(userId, emailOf(userId))
        val second = mint(userId, emailOf(userId))
        confirm(userId, first).then().statusCode(404).body("code", equalTo("email_confirmation_invalid"))
        assertFalse(confirmed(userId), "a superseded mail confirms nothing")
        confirm(userId, second).then().statusCode(204)
    }

    @Test
    fun tokenConfirmsAtMostOnce() {
        val userId = seedUser(email = fresh("once"))
        val token = mint(userId, emailOf(userId))
        confirm(userId, token).then().statusCode(204)
        confirm(userId, token).then().statusCode(404).body("code", equalTo("email_confirmation_invalid"))

        // The spend itself is a compare-and-set (D4). Through the handler a replay is already refused by the
        // liveness read before the spend, so the CAS is what decides the race that read cannot see: two
        // confirmations that both read the row as live. Modelled here as two spends of one row — only one wins.
        val raced = mint(userId, emailOf(userId))
        val rowId = rowIdOf(raced)
        assertTrue(QuarkusTransaction.requiringNew().call { store.consume(rowId) }, "the first spend wins")
        assertFalse(QuarkusTransaction.requiringNew().call { store.consume(rowId) }, "a second spend of the same row loses")
    }

    @Test
    fun expiredTokenConfirmsNothing() {
        val userId = seedUser(email = fresh("expired"))
        val token = mint(userId, emailOf(userId), expiresAt = Instant.now().minus(1, ChronoUnit.MINUTES))
        confirm(userId, token).then().statusCode(404).body("code", equalTo("email_confirmation_invalid"))
        assertFalse(confirmed(userId))
    }

    @Test
    fun tokenIsStoredHashedWithADayLongExpiry(): Unit = TestMailbox(HASH_SMTP_PORT).start().use { mail ->
        val tenant = sendableTenant(mail)
        val userId = member(tenant.id, fresh("hashed"))
        val before = Instant.now()
        requestConfirmation(userId, tenant.domain).then().statusCode(202)
        val token = mail.awaitToken(emailOf(userId))

        val row = QuarkusTransaction.requiringNew().call { EmailConfirmationEntity.find("userId", userId).firstResult()!! }
        assertEquals(Tokens.hash(token), row.tokenHash, "only the SHA-256 of the token is stored")
        assertNotEquals(token, row.tokenHash)
        assertEquals(emailOf(userId), row.email, "the row binds the address it was mailed to")
        val ttl = Duration.between(before, row.expiresAt)
        assertTrue(ttl > Duration.ofHours(23) && ttl <= Duration.ofHours(24).plusMinutes(1), "PT24H, was $ttl")
    }

    // --- what a request may and may not do ---

    @Test
    fun noMailRefusesAndMintsNothing(): Unit = TestMailbox(NO_MAIL_SMTP_PORT).start().use { mail ->
        val silent = silentTenant()
        val userId = member(silent.id, fresh("silent"))
        requestConfirmation(userId, silent.domain).then().statusCode(503).body("code", equalTo("mail_unavailable"))
        assertEquals(0, statuses(userId).size, "no transport, no token — and no copy-link to hand one to")

        // Control: the same account on a tenant that can send.
        val sendable = sendableTenant(mail)
        QuarkusTransaction.requiringNew().run { TenantRoots.join(userId, sendable.id) }
        requestConfirmation(userId, sendable.domain).then().statusCode(202)
        assertEquals(listOf(EmailConfirmationEntity.PENDING), statuses(userId))
        mail.awaitBody(emailOf(userId))
        assertEquals(1, mail.countTo(emailOf(userId)))
    }

    @Test
    fun failedSendSupersedesTheTokenAndAnswers503() {
        // A relay that refuses the connection: sendable by its settings, undeliverable in fact.
        val tenant = silentTenant()
        QuarkusTransaction.requiringNew().run {
            tenantStore.saveMailSettings(
                tenant.id,
                MailSettingsRow(
                    host = "127.0.0.1", port = DEAD_SMTP_PORT, from = "noreply@${tenant.domain}",
                    username = null, passwordCiphertext = null, passwordNonce = null, passwordFingerprint = null,
                    tls = false, enabled = true, subjectTemplate = null, bodyTemplate = null,
                ),
            )
        }
        val userId = member(tenant.id, fresh("dead-relay"))
        requestConfirmation(userId, tenant.domain).then().statusCode(503).body("code", equalTo("mail_unavailable"))
        assertEquals(listOf(EmailConfirmationEntity.SUPERSEDED), statuses(userId), "a mail that never left leaves no live token")
    }

    @Test
    fun nonMemberIsRefusedAndNothingIsMailed(): Unit = TestMailbox(MEMBER_SMTP_PORT).start().use { mail ->
        val tenant = sendableTenant(mail)
        val outsider = seedUser(email = fresh("outsider"))
        requestConfirmation(outsider, tenant.domain).then().statusCode(404).body("code", equalTo("tenant_not_found"))
        assertFalse(mail.deliveredTo(emailOf(outsider)), "a relay carries links only for its own tenant's members")
        assertEquals(0, statuses(outsider).size)

        // Control: a member on the same host and relay.
        val insider = member(tenant.id, fresh("insider"))
        requestConfirmation(insider, tenant.domain).then().statusCode(202)
        mail.awaitBody(emailOf(insider))
    }

    @Test
    fun sendBudgetBoundsMails(): Unit = TestMailbox(BUDGET_SMTP_PORT).start().use { mail ->
        val tenant = sendableTenant(mail)
        val userId = member(tenant.id, fresh("budget"))
        repeat(PER_ACCOUNT_MAX) { requestConfirmation(userId, tenant.domain).then().statusCode(202) }
        val over = requestConfirmation(userId, tenant.domain)
        assertEquals(429, over.statusCode, "the request over budget is refused")
        assertEquals("rate_limited", over.jsonPath().getString("code"))
        assertTrue(over.header("Retry-After") != null, "429 carries Retry-After")
        assertEquals(PER_ACCOUNT_MAX, mail.countTo(emailOf(userId)), "the mailbox holds exactly the budget")
    }

    @Test
    fun mailCarriesOnlyLinkDomainExpiry(): Unit = TestMailbox(CONTENT_SMTP_PORT).start().use { mail ->
        val tenant = sendableTenant(mail)
        val userId = member(tenant.id, fresh("content"))
        QuarkusTransaction.requiringNew().run { profiles.create(userId, tenant.id, USERNAME) }
        requestConfirmation(userId, tenant.domain).then().statusCode(202)

        val token = mail.awaitToken(emailOf(userId))
        val body = mail.awaitBody(emailOf(userId)).replace(Regex("=\r?\n"), "")
        assertTrue(body.contains("https://${tenant.domain}/confirm-email#$token"), "the link, with the token in the fragment")
        assertFalse(body.contains(TENANT_NAME), "the tenant is named by its domain only (ADR 0049)")
        assertFalse(body.contains(USERNAME), "the person is not named")
    }

    // --- it gates nothing (ADR 0074 Decision 3) ---

    @Test
    fun unconfirmedMemberCanStillRecover(): Unit = TestMailbox(RECOVER_SMTP_PORT).start().use { mail ->
        val tenant = sendableTenant(mail)
        val unconfirmed = member(tenant.id, fresh("recover-unconfirmed"))
        assertFalse(confirmed(unconfirmed), "precondition")
        assertEquals(RequestOutcome.MAILED, recovery.deliver(emailOf(unconfirmed), tenant), "recovery reads no confirmation state")
        mail.awaitBody(emailOf(unconfirmed))

        // Control: the same for a confirmed member.
        val confirmedMember = member(tenant.id, fresh("recover-confirmed"))
        QuarkusTransaction.requiringNew().run { VeyqUser.findById(confirmedMember)!!.emailConfirmedAt = Instant.now() }
        assertEquals(RequestOutcome.MAILED, recovery.deliver(emailOf(confirmedMember), tenant))
        mail.awaitBody(emailOf(confirmedMember))
    }

    @Test
    fun theReadShowsTheServersAddress() {
        val email = fresh("read")
        val userId = seedUser(email = email)
        given().header("Authorization", bearer(userId)).`when`().get("/api/account/email")
            .then().statusCode(200).body("email", equalTo(email)).body("confirmed", equalTo(false))
    }

    // --- helpers ---

    private fun fresh(label: String) = "$label-${UUID.randomUUID().toString().take(8)}@example.com"

    private fun handle(label: String) = "$label${UUID.randomUUID().toString().take(8)}"

    private fun emailOf(userId: String): String = QuarkusTransaction.requiringNew().call { VeyqUser.findById(userId)!!.email }

    private fun confirmed(userId: String): Boolean =
        given().header("Authorization", bearer(userId)).`when`().get("/api/account/email")
            .then().statusCode(200).extract().jsonPath().getBoolean("confirmed")

    private fun requestConfirmation(userId: String, host: String) =
        given().header("Authorization", bearer(userId)).header("X-Forwarded-Host", host)
            .`when`().post("/api/account/email/confirmation").then().extract().response()

    private fun confirm(userId: String, token: String) =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON).body("""{"token":"$token"}""")
            .`when`().post("/api/account/email/confirmation/confirm")

    private fun mint(userId: String, email: String, expiresAt: Instant = Instant.now().plus(1, ChronoUnit.HOURS)): String {
        val token = Tokens.random()
        store.mint(userId, email, Tokens.hash(token), expiresAt)
        return token
    }

    private fun rowIdOf(token: String): String =
        QuarkusTransaction.requiringNew().call { EmailConfirmationEntity.find("tokenHash", Tokens.hash(token)).firstResult()!!.id }

    private fun statuses(userId: String): List<String> = QuarkusTransaction.requiringNew().call {
        EmailConfirmationEntity.find("userId", userId).list().map { it.status }
    }

    /** An account that belongs to [tenantId] — an accepted root membership, the row an accepted invite writes. */
    private fun member(tenantId: String, email: String): String = seedUser(email = email).also { id ->
        QuarkusTransaction.requiringNew().run { TenantRoots.join(id, tenantId) }
    }

    /** An ACTIVE tenant on its own domain whose relay is [mail]. */
    private fun sendableTenant(mail: TestMailbox): ResolvedTenant = silentTenant().also { mail.receiveFor(tenantStore, it.id, it.domain) }

    /** An ACTIVE tenant on its own domain with no mail settings: it cannot send. */
    private fun silentTenant(): ResolvedTenant {
        val domain = "confirm-${UUID.randomUUID().toString().take(8)}.example.com"
        val id = seedTenant(domain = domain)
        QuarkusTransaction.requiringNew().run { TenantEntity.findById(id)!!.name = TENANT_NAME }
        return ResolvedTenant(id, domain)
    }

    /** A MEMBER invite to [email] on [tenantId], signed by its owner; returns its secret and its invite key. */
    private fun memberInvite(tenantId: String, email: String): Pair<String, TestInviteKey> {
        val secret = Tokens.random()
        val key = TestInviteKey()
        QuarkusTransaction.requiringNew().run {
            val owner = MembershipEntity.find("scopeId = ?1 and role = ?2", tenantId, MembershipEntity.OWNER).firstResult()!!.userId
            tenantStore.mintMemberInvite(tenantId, email, Tokens.hash(secret), Instant.now().plus(7, ChronoUnit.DAYS), key.stored(owner))
        }
        return secret to key
    }

    /** A PENDING tenant whose bootstrap OWNER invite goes to [ownerEmail]; returns its secret. */
    private fun pendingTenant(ownerEmail: String): String {
        val secret = Tokens.random()
        QuarkusTransaction.requiringNew().run {
            tenantStore.createPendingTenant(
                "Fam", "pending-${UUID.randomUUID().toString().take(8)}.example.com", ownerEmail, Tokens.hash(secret),
                Instant.now().plus(7, ChronoUnit.DAYS),
            )
        }
        return secret
    }

    /** An account a reset can complete on: the PASSWORD floor, the RECOVERY wrap and a Secret-Key proof key. */
    private fun resettableAccount(email: String): String {
        val id = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            newUser(id = id, email = email, secretKeyProofPublicKey = PROOF_KEY.publicKey).persist()
            listOf("PASSWORD" to "cGFzc3dvcmQtd3JhcA==", "RECOVERY" to "cmVjb3Zlcnktd3JhcA==").forEach { (method, wrapped) ->
                MasterKeyWrapEntity().apply {
                    this.id = UUID.randomUUID().toString(); userId = id; this.method = method
                    wrappedKey = wrapped; kdfParams = "a2RmLXBhcmFtcw=="
                }.persist()
            }
        }
        return id
    }

    /** A register-and-accept body; an OWNER birth ([grant]) also carries the first grant, a MEMBER's its join under [key]. */
    private fun registerBody(secret: String, userId: String, grant: Boolean, key: TestInviteKey? = null): String {
        val birth = if (grant) ""","grant":"AQ==",${TestGrants.SIGNATURE_JSON}"""
        else key?.let { ",${TestGrants.memberJoinJson(it, it.inviteId, userId, IDENTITY)}" } ?: ""
        return """
            {"bootstrapSecret":"$secret","userId":"$userId","username":"${handle("reg")}",
             "account":{"suiteId":"v1",
               "identityPublicKey":"$IDENTITY",
               "wrappedIdentityPrivateKey":"d3JhcHBlZC1pZGVudGl0eS1wcml2YXRlLWtleQ==",
               "authPublicKey":"ZWQyNTUxOS1hdXRoLXB1Yg==",
               "masterKeyWraps":[{"method":"PASSWORD","wrappedKey":"d3JhcHBlZC1tYXN0ZXIta2V5","kdfParams":"a2RmLXBhcmFtcw=="}],
               "secretKeyProofPublicKey":"c2VjcmV0LWtleS1wcm9vZi1wdWJsaWMta2V5"}$birth}
        """.trimIndent()
    }

    private companion object {
        /** The identity bundle every account here holds — the fixture's, and the one a register births. */
        const val IDENTITY = "aWRlbnRpdHktcHVibGljLWtleQ=="

        /** `veyq.ratelimit.email-confirmation.per-account-max-requests`. */
        const val PER_ACCOUNT_MAX = 3

        /** Distinctive, so the content assertion cannot pass by accident. */
        const val TENANT_NAME = "Zebrafinch Family Archive"
        const val USERNAME = "quokkahandle"

        val PROOF_KEY = TestProofKey()
        const val NEW_WRAP = "bmV3LXBhc3N3b3JkLXdyYXA="
        const val NEW_KDF = "bmV3LWtkZi1wYXJhbXM="
        const val NEW_AUTH_KEY = "bmV3LWF1dGgta2V5"

        // One JVM for the whole suite ⇒ ports no other test holds (`grep -rhoE '139[0-9]{2}' src/backend/src/test`).
        const val SESSION_SMTP_PORT = 13960
        const val HASH_SMTP_PORT = 13961
        const val NO_MAIL_SMTP_PORT = 13962
        const val MEMBER_SMTP_PORT = 13963
        const val BUDGET_SMTP_PORT = 13964
        const val CONTENT_SMTP_PORT = 13965
        const val RECOVER_SMTP_PORT = 13966

        /** Nothing listens here: a relay that refuses the connection. */
        const val DEAD_SMTP_PORT = 13967
    }
}
