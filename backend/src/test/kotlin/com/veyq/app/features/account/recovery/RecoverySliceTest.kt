package com.veyq.app.features.account.recovery

import com.veyq.app.features.account.login.SessionFamilyEntity
import com.veyq.app.features.account.login.SessionService
import com.veyq.app.features.admin.tenant.MailSettingsRow
import com.veyq.app.shared.tenant.TenantEntity
import com.veyq.app.features.admin.tenant.TenantMailSender
import com.veyq.app.features.admin.tenant.TenantStore
import com.veyq.app.features.admin.tenant.TestMailbox
import com.veyq.app.shared.scope.ScopeEntity
import com.veyq.app.shared.crypto.SecretKeyProof
import com.veyq.app.shared.crypto.TestProofKey
import com.veyq.app.shared.crypto.Tokens
import com.veyq.app.shared.identity.MasterKeyWrapEntity
import com.veyq.app.shared.identity.VeyqUser
import com.veyq.app.shared.membership.MembershipEntity
import com.veyq.app.shared.tenant.ResolvedTenant
import com.veyq.app.testing.TestFixtures.newUser
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.equalTo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Slice tests for password recovery (uc.account.recover-access, ADR 0054).
 *
 * The CR's acceptance is explicit that the happy path is the *least* interesting case here, so most of this file
 * is the cases that must FAIL: a valid token with a wrong Secret Key, a correct proof with a dead token, and a
 * release attempted with no token at all. A recovery flow that only proves its happy path is worse than none,
 * because it looks finished.
 *
 * Tokens are minted through the store rather than the request endpoint, because the endpoint deliberately never
 * returns one — it only mails it. That is the flow working as designed, not a testability gap.
 *
 * **A test that POSTs a request for a member must await what the answer handed off** (the mail, or the PENDING
 * row) before it returns. The endpoint answers before it mints (ADR 0054 §6), so an un-awaited mint can land
 * after the next test's [seed] and supersede the token that test minted for itself — a red in a test that never
 * touched the endpoint. Negatives go through [RecoveryHandler.deliver] directly, which returns synchronously.
 */
@QuarkusTest
class RecoverySliceTest {

    @Inject
    lateinit var store: RecoveryStore

    @Inject
    lateinit var tenantStore: TenantStore

    @Inject
    lateinit var handler: RecoveryHandler

    @Inject
    lateinit var sessions: SessionService

    @BeforeEach
    fun seed() {
        resetAccount(USER, EMAIL)
    }

    // --- the request endpoint is never an oracle ---

    @Test
    fun requestIsIndistinguishableForKnownAndUnknownAddress(): Unit = TestMailbox(RECOVERY_SMTP_PORT).start().use { mail ->
        // This test only means anything on a tenant that CAN send. Run it against the mailer-less default and both
        // addresses answer 503 for the same instance-level reason: the assertions stay green while proving
        // nothing about enumerability (ADR 0055 §4 narrowed the 202, and this is where that narrowing could have
        // quietly eaten the guarantee). So: a real SMTP server, and a real mail as the difference.
        val tenant = seedTenant(SENDABLE_DOMAIN, mail)
        grantMembership(USER, tenant.id) // "known" means known HERE: a member of the tenant being asked
        val unknown = requestFor(UNKNOWN, SENDABLE_DOMAIN)
        val known = requestFor(EMAIL, SENDABLE_DOMAIN)
        assertEquals(known.statusCode, unknown.statusCode, "status must not depend on whether the account exists")
        assertEquals(known.body.asString(), unknown.body.asString(), "body must not depend on it either")
        assertEquals(202, known.statusCode)

        // The wire is identical; what differs happens after the answer and is invisible to the caller. The mail
        // is awaited rather than read at once: the answer no longer waits for it, which is the point.
        mail.awaitBody(EMAIL)
        // The negative on the part the answer did not wait for, called directly so it is not a race: an unknown
        // address is mailed nothing.
        assertEquals(RequestOutcome.NO_ACCOUNT, handler.deliver(UNKNOWN, tenant))
        assertFalse(mail.deliveredTo(UNKNOWN), "an unknown address is not mailed")
    }

    @Test
    fun noMailTransportIsRefusedAndMintsNothing() {
        // A tenant with no mail settings cannot deliver a recovery link, and says so instead of accepting a
        // request it will silently drop (ADR 0055 §4). It must also leave no live token: there is no copy-link
        // fallback to hand one to, so a minted-but-undeliverable token would be a live secret nobody can reach
        // and an invitation to look for one (ADR 0054 § Decision 4).
        seedTenant(SILENT_DOMAIN, mailbox = null)
        requestFor(EMAIL, SILENT_DOMAIN).then().statusCode(503).body("code", equalTo("mail_unavailable"))
        val minted = QuarkusTransaction.requiringNew().call<Long> {
            AccountRecoveryEntity.count("userId = ?1 and status = ?2", USER, AccountRecoveryEntity.PENDING)
        }
        assertEquals(0L, minted, "no transport, no token")
    }

    @Test
    fun transportIsAnsweredBeforeTheRateLimiterSoTheTwoCannotBePaired(): Unit = TestMailbox(ORDER_SMTP_PORT).start().use { mail ->
        // The bite-test for ADR 0055 §5. If the per-address limiter ran first, a mailer-less tenant would answer
        // 202 for an address whose bucket is spent and 503 for one that is fresh — an oracle for "was this
        // address asked about recently", which is exactly the class ADR 0054 § Decision 6 exists to close.
        // The limiter is per-address and instance-global, so it can be spent against a *sendable* tenant and the
        // pairing then attempted against a silent one.
        seedTenant(ORDER_SENDABLE_DOMAIN, mail)
        seedTenant(ORDER_SILENT_DOMAIN, mailbox = null)
        val victim = "bucket-victim@example.com"
        repeat(PER_ADDRESS_MAX + 1) { requestFor(victim, ORDER_SENDABLE_DOMAIN) } // spend the bucket

        requestFor(victim, ORDER_SILENT_DOMAIN).then().statusCode(503).body("code", equalTo("mail_unavailable"))
    }

    // --- recovery reaches only this tenant's accounts (ADR 0054 §4) ---

    @Test
    fun requestReachesOnlyThisTenantsAccounts(): Unit = TestMailbox(CROSS_SMTP_PORT).start().use { mail ->
        val a = seedTenant(CROSS_A_DOMAIN, mail)
        val b = seedTenant(CROSS_B_DOMAIN, mail)
        grantMembership(USER, b.id)

        assertEquals(RequestOutcome.NO_ACCOUNT, handler.deliver(EMAIL, a), "tenant A's relay must not carry B's account")
        assertFalse(mail.deliveredTo(EMAIL), "no mail through A's relay")
        assertEquals(0L, pendingRows(), "no account_recovery row for a non-member")

        // Control on the same predicate: the tenant the account belongs to does reach it.
        assertEquals(RequestOutcome.MAILED, handler.deliver(EMAIL, b))
        mail.awaitBody(EMAIL)
    }

    // --- the answer does not wait for anything keyed by the address (ADR 0054 §6) ---

    @Test
    fun requestAnswersBeforeTheAddressIsLookedUp(): Unit = HungSmtp(HUNG_SMTP_PORT).use {
        val t = seedTenant(HUNG_DOMAIN, mailbox = null)
        saveSettingsPointingAt(t, HUNG_SMTP_PORT)
        grantMembership(USER, t.id)

        val started = System.nanoTime()
        val answer = requestFor(EMAIL, HUNG_DOMAIN)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(202, answer.statusCode)
        assertTrue(
            elapsedMs < TenantMailSender.SEND_TIMEOUT_SECONDS * 1000 / 2,
            "a hung relay must not hold the answer (took ${elapsedMs}ms)",
        )
        // Control: the background part ran — the member's token was minted, the send is what hangs.
        awaitPendingRow()
    }

    // --- release ---

    @Test
    fun liveTokenReleasesTheRecoveryWrap() {
        val token = mintToken()
        val material = handler.release(token)
        assertNotNull(material)
        assertEquals(REC_WRAP, material!!.wrappedKey, "the RECOVERY wrap, not the PASSWORD floor")
        assertEquals(PRIV, material.wrappedIdentityPrivateKey)
    }

    @Test
    fun releaseDoesNotSpendTheToken() {
        val token = mintToken()
        assertNotNull(handler.release(token))
        assertNotNull(handler.release(token), "still live: the completing reset spends it, not the release")
    }

    @Test
    fun expiredTokenReleasesNothing() {
        val token = mintToken(expiresAt = Instant.now().minus(1, ChronoUnit.MINUTES))
        assertNull(handler.release(token))
    }

    @Test
    fun unknownTokenReleasesNothing() {
        assertNull(handler.release(Tokens.random()))
    }

    @Test
    fun freshRequestSupersedesTheOutstandingToken() {
        val first = mintToken()
        val second = mintToken()
        assertNull(handler.release(first), "superseded")
        assertNotNull(handler.release(second))
    }

    // --- the second factor: the Secret-Key proof. These are the cases that must fail. ---

    @Test
    fun validTokenWithWrongSecretKeyDoesNotReset() {
        // The mailbox factor is genuine; the proof is signed with a key this account never registered, because
        // the caller does not hold its Secret Key. The control is the same token under the account's own proof
        // key — which also shows the refusal did not spend the token, so a mistyped kit costs a retry, not the mail.
        val token = mintToken()
        val outcome = handler.complete(token, OTHER_KEY.sign(statementFor(token)), NEW_WRAP, NEW_KDF, NEW_AUTH_KEY)
        assertEquals(ResetOutcome.NotProven, outcome)
        assertEquals(PW_WRAP, wrapOf("PASSWORD"), "the floor must be untouched")
        assertEquals(OLD_AUTH_KEY, authKeyOf(), "the auth key must be untouched")

        assertEquals(ResetOutcome.Applied, handler.complete(token, proof(token), NEW_WRAP, NEW_KDF, NEW_AUTH_KEY))
    }

    @Test
    fun aProofForAnotherTokenDoesNotReset() {
        // The statement binds the token it spends. A signature the person made for an earlier (superseded or
        // spent) token is no proof for this one.
        val earlier = mintToken()
        val token = mintToken()
        assertEquals(ResetOutcome.NotProven, handler.complete(token, proof(earlier), NEW_WRAP, NEW_KDF, NEW_AUTH_KEY))
        assertEquals(PW_WRAP, wrapOf("PASSWORD"))
    }

    @Test
    fun aFailedProofAnswersSecretKeyProofFailedOnTheWire() {
        val token = mintToken()
        given().contentType(ContentType.JSON)
            .body(
                """{"token":"$token","secretKeyProofSignature":"${OTHER_KEY.sign(statementFor(token))}",
                   "wrappedKey":"$NEW_WRAP","kdfParams":"$NEW_KDF","authPublicKey":"$NEW_AUTH_KEY"}""",
            )
            .`when`().post("/api/recovery/complete")
            .then().statusCode(403).body("code", equalTo("secret_key_proof_failed"))
        assertEquals(PW_WRAP, wrapOf("PASSWORD"))
    }

    @Test
    fun correctSecretKeyWithDeadTokenDoesNotReset() {
        val token = mintToken(expiresAt = Instant.now().minus(1, ChronoUnit.MINUTES))
        val outcome = handler.complete(token, proof(token), NEW_WRAP, NEW_KDF, NEW_AUTH_KEY)
        assertEquals(ResetOutcome.TokenNotLive, outcome)
        assertEquals(PW_WRAP, wrapOf("PASSWORD"))
    }

    @Test
    fun spentTokenCannotBeReplayed() {
        val token = mintToken()
        assertEquals(ResetOutcome.Applied, handler.complete(token, proof(token), NEW_WRAP, NEW_KDF, NEW_AUTH_KEY))
        assertEquals(
            ResetOutcome.TokenNotLive,
            handler.complete(
                token,
                // A fully valid proof for DIFFERENT new material: were the reset re-run, it would visibly install it.
                PROOF_KEY.sign(SecretKeyProof.recoveryStatement(token, "b3RoZXI=", SecretKeyProof.Wrap("b3RoZXI=", NEW_KDF))),
                "b3RoZXI=", NEW_KDF, "b3RoZXI=",
            ),
            "a replay must not re-run the reset",
        )
    }

    // --- the reset itself ---

    @Test
    fun bothFactorsReplaceFloorAndAuthKeyWithoutReEncrypting() {
        val token = mintToken()
        val identityBefore = identityOf()

        assertEquals(ResetOutcome.Applied, handler.complete(token, proof(token), NEW_WRAP, NEW_KDF, NEW_AUTH_KEY))

        assertEquals(NEW_WRAP, wrapOf("PASSWORD"), "the floor now opens under the new password")
        assertEquals(NEW_AUTH_KEY, authKeyOf(), "login follows the same password")
        assertEquals(identityBefore, identityOf(), "the identity key is untouched — no content is re-encrypted")
        assertEquals(REC_WRAP, wrapOf("RECOVERY"), "the recovery lock still opens with the same Emergency Kit")
    }

    @Test
    fun resetDropsDeviceKeyWrapsToo() {
        QuarkusTransaction.requiringNew().run {
            persistWrap(USER, "DEVICE", "ZGV2aWNl")
            persistWrap(USER, "DEVICE_KEY", "ZGV2aWNlLWtleQ==")
            persistWrap(USER, "PASSKEY", "cGFzc2tleQ==")
        }
        assertTrue(hasWrap("DEVICE_KEY"), "precondition")
        val recoveryBefore = wrapRowOf("RECOVERY")
        assertNotNull(recoveryBefore, "precondition")

        mintToken().let { handler.complete(it, proof(it), NEW_WRAP, NEW_KDF, NEW_AUTH_KEY) }

        assertFalse(hasWrap("DEVICE"), "a DEVICE wrap mixes the old password and would merely fail")
        assertFalse(
            hasWrap("DEVICE_KEY"),
            "a DEVICE_KEY wrap takes NO password, so surviving the reset would keep granting password-less entry",
        )
        // The control on the same predicate: a whitelist that over-reaches and takes the recovery path with it
        // would lock the person out of the route they just used.
        assertEquals(recoveryBefore, wrapRowOf("RECOVERY"), "the Emergency Kit's wrap survives byte-for-byte")
        assertFalse(hasWrap("PASSKEY"), "every method not declared a survivor goes — a whitelist, not a blacklist")
    }

    @Test
    fun resetRevokesEveryLiveSession() {
        val issued = sessions.issue(USER)
        assertNotNull(issued)

        mintToken().let { handler.complete(it, proof(it), NEW_WRAP, NEW_KDF, NEW_AUTH_KEY) }

        val live = QuarkusTransaction.requiringNew().call<Long> {
            SessionFamilyEntity.count("userId = ?1 and status = ?2", USER, SessionFamilyEntity.ACTIVE)
        }
        assertEquals(0L, live, "whoever prompted the reset may be holding a session")
    }

    // --- helpers ---

    /** `X-Forwarded-Host` is what `RequestTenant` resolves the tenant from first, and that tenant's relay is the only
     *  one that may send the link — so it is how a test says "ask this tenant". */
    private fun requestFor(email: String, host: String) = given()
        .contentType(ContentType.JSON)
        .header("X-Forwarded-Host", host)
        .body("""{"email":"$email"}""")
        .`when`().post("/api/recovery/request")
        .then().extract().response()

    /** An ACTIVE tenant on [domain]; with a [mailbox] it can send, without one it deliberately cannot. */
    private fun seedTenant(domain: String, mailbox: TestMailbox?): ResolvedTenant {
        val tenantId = QuarkusTransaction.requiringNew().call {
            TenantEntity.find("domain", domain).firstResult()?.id ?: java.util.UUID.randomUUID().toString().also { id ->
                TenantEntity().apply {
                    this.id = id; name = "Fam"; this.domain = domain; ownerEmail = "owner@$domain"
                    status = TenantEntity.ACTIVE; createdAt = Instant.now()
                }.persist()
            }
        }
        mailbox?.receiveFor(tenantStore, tenantId, domain)
        return ResolvedTenant(tenantId, domain)
    }

    /** Make [userId] belong to [tenantId]: a membership on its TENANT root scope, the row an accepted invite writes.
     *  Idempotent, because memberships outlive [seed] (it resets the account, not what it belongs to). */
    private fun grantMembership(userId: String, tenantId: String) = QuarkusTransaction.requiringNew().run {
        if (ScopeEntity.findById(tenantId) == null) {
            ScopeEntity().apply {
                id = tenantId; this.tenantId = tenantId; kind = ScopeEntity.TENANT; createdAt = Instant.now()
            }.persist()
        }
        if (MembershipEntity.count("userId = ?1 and tenantId = ?2", userId, tenantId) == 0L) {
            MembershipEntity().apply {
                id = java.util.UUID.randomUUID().toString(); this.userId = userId; this.tenantId = tenantId
                scopeId = tenantId; role = MembershipEntity.MEMBER; createdAt = Instant.now()
            }.persist()
        }
    }

    /** Point [tenant]'s relay at [port] — for a server that is not a [TestMailbox] (the hung one). */
    private fun saveSettingsPointingAt(tenant: ResolvedTenant, port: Int) = QuarkusTransaction.requiringNew().run {
        tenantStore.saveMailSettings(
            tenant.id,
            MailSettingsRow(
                host = "127.0.0.1", port = port, from = "noreply@${tenant.domain}",
                username = null, passwordCiphertext = null, passwordNonce = null,
                tls = false, enabled = true, subjectTemplate = null, bodyTemplate = null,
            ),
        )
    }

    private fun pendingRows(): Long = QuarkusTransaction.requiringNew().call<Long> {
        AccountRecoveryEntity.count("userId = ?1 and status = ?2", USER, AccountRecoveryEntity.PENDING)
    }

    /** Wait, bounded, for [USER]'s token to be minted — the proof that the part after the answer ran. */
    private fun awaitPendingRow(timeoutMs: Long = 10_000) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (pendingRows() == 0L) {
            check(System.nanoTime() < deadline) { "no PENDING recovery row for $USER within ${timeoutMs}ms" }
            Thread.sleep(50)
        }
    }

    /**
     * An SMTP server that accepts and never greets — the relay that holds a sender for its whole timeout. The
     * lever for "the answer does not wait for the send": nothing this CR changes can make a hung relay answer.
     */
    private class HungSmtp(port: Int) : AutoCloseable {
        private val server = ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"))
        private val accepted = java.util.concurrent.CopyOnWriteArrayList<Socket>()
        private val acceptor = Thread {
            while (!server.isClosed) runCatching { accepted += server.accept() }
        }.apply { isDaemon = true; start() }

        override fun close() {
            server.close()
            accepted.forEach { runCatching { it.close() } }
            acceptor.join(1_000)
        }
    }

    /** What a legitimate client signs to complete a reset with [token] into this fixture's new password. */
    private fun statementFor(token: String) =
        SecretKeyProof.recoveryStatement(token, NEW_AUTH_KEY, SecretKeyProof.Wrap(NEW_WRAP, NEW_KDF))

    private fun proof(token: String) = PROOF_KEY.sign(statementFor(token))

    private fun mintToken(expiresAt: Instant = Instant.now().plus(1, ChronoUnit.HOURS)): String {
        val token = Tokens.random()
        store.mint(USER, EMAIL, Tokens.hash(token), expiresAt)
        return token
    }

    private fun wrapOf(method: String): String? = QuarkusTransaction.requiringNew().call<String?> {
        MasterKeyWrapEntity.find("userId = ?1 and method = ?2", USER, method).firstResult()?.wrappedKey
    }

    private fun hasWrap(method: String): Boolean = wrapOf(method) != null

    private fun wrapRowOf(method: String): Pair<String, String?>? = QuarkusTransaction.requiringNew().call<Pair<String, String?>?> {
        MasterKeyWrapEntity.find("userId = ?1 and method = ?2", USER, method).firstResult()?.let { it.wrappedKey to it.kdfParams }
    }

    private fun authKeyOf(): String? =
        QuarkusTransaction.requiringNew().call<String?> { VeyqUser.findById(USER)?.authPublicKey }

    private fun identityOf(): String? =
        QuarkusTransaction.requiringNew().call<String?> { VeyqUser.findById(USER)?.wrappedIdentityPrivateKey }

    /** Reset the account to its pre-recovery state without deleting the row: other tables (session families
     *  from a previous test in this class) hold FK references to it, so a delete-and-recreate would trip a
     *  constraint rather than give a clean slate. */
    private fun resetAccount(userId: String, email: String) {
        QuarkusTransaction.requiringNew().run {
            AccountRecoveryEntity.delete("userId", userId)
            MasterKeyWrapEntity.delete("userId", userId)
            SessionFamilyEntity.update("status = ?1 where userId = ?2", SessionFamilyEntity.REVOKED, userId)
            val user = VeyqUser.findById(userId) ?: newUser(
                id = userId, email = email, serverRole = "SERVER_OWNER", wrappedIdentityPrivateKey = PRIV,
                authPublicKey = OLD_AUTH_KEY, secretKeyProofPublicKey = PROOF_KEY.publicKey,
            ).also { it.persist() }
            user.wrappedIdentityPrivateKey = PRIV
            user.authPublicKey = OLD_AUTH_KEY
            user.secretKeyProofPublicKey = PROOF_KEY.publicKey
            persistWrap(userId, "PASSWORD", PW_WRAP)
            persistWrap(userId, "RECOVERY", REC_WRAP)
        }
    }

    private fun persistWrap(userId: String, method: String, wrapped: String) {
        MasterKeyWrapEntity().apply {
            id = java.util.UUID.randomUUID().toString()
            this.userId = userId
            this.method = method
            wrappedKey = wrapped
            kdfParams = "a2RmLXBhcmFtcw=="
        }.persist()
    }

    private companion object {
        const val USER = "11111111-2222-3333-4444-555555555555"
        const val EMAIL = "recover-me@example.com"
        const val UNKNOWN = "nobody-at-all@example.com"
        const val PRIV = "d3JhcHBlZC1pZGVudGl0eS1wcml2YXRl"
        const val PW_WRAP = "cGFzc3dvcmQtd3JhcA=="
        const val REC_WRAP = "cmVjb3Zlcnktd3JhcA=="
        const val OLD_AUTH_KEY = "b2xkLWF1dGgta2V5"

        /** The Secret-Key proof key the account registered at birth; [OTHER_KEY] is a Secret Key it does not hold. */
        val PROOF_KEY = TestProofKey()
        val OTHER_KEY = TestProofKey()

        const val NEW_WRAP = "bmV3LXBhc3N3b3JkLXdyYXA="
        const val NEW_KDF = "bmV3LWtkZi1wYXJhbXM="
        const val NEW_AUTH_KEY = "bmV3LWF1dGgta2V5"

        /** `veyq.ratelimit.recovery.per-address-max-requests` — the bucket the ordering bite-test spends. */
        const val PER_ADDRESS_MAX = 3

        const val SENDABLE_DOMAIN = "recover-mail.example.com"
        const val SILENT_DOMAIN = "recover-silent.example.com"
        const val ORDER_SENDABLE_DOMAIN = "recover-order-mail.example.com"
        const val ORDER_SILENT_DOMAIN = "recover-order-silent.example.com"

        // One JVM for the whole suite ⇒ distinct ports (SmtpInviteMailSendTest 13925-13929,
        // MemberAddSliceTest 13930-13933, MemberInviteManagementSliceTest 13934-13935, TenantOwnerGateTest 13938).
        const val RECOVERY_SMTP_PORT = 13936
        const val ORDER_SMTP_PORT = 13937
        const val CROSS_SMTP_PORT = 13940
        const val HUNG_SMTP_PORT = 13941

        const val CROSS_A_DOMAIN = "recover-cross-a.example.com"
        const val CROSS_B_DOMAIN = "recover-cross-b.example.com"
        const val HUNG_DOMAIN = "recover-hung.example.com"
    }
}
