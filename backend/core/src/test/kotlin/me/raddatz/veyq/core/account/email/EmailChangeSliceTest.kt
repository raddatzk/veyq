package me.raddatz.veyq.core.account.email

import me.raddatz.veyq.core.account.login.SessionService
import me.raddatz.veyq.core.admin.tenant.TenantStore
import me.raddatz.veyq.core.admin.tenant.TestMailbox
import me.raddatz.veyq.core.crypto.AuthAttestation
import me.raddatz.veyq.core.crypto.SecretKeyProof
import me.raddatz.veyq.core.crypto.TestProofKey
import me.raddatz.veyq.core.crypto.Tokens
import me.raddatz.veyq.core.identity.VeyqUser
import me.raddatz.veyq.core.membership.TenantRoots
import me.raddatz.veyq.core.tenant.ResolvedTenant
import me.raddatz.veyq.core.testing.RACE_ROUNDS
import me.raddatz.veyq.core.testing.TestFixtures.seedTenant
import me.raddatz.veyq.core.testing.TestFixtures.seedUser
import me.raddatz.veyq.core.testing.answer
import me.raddatz.veyq.core.testing.assertWinnerAndLoser
import me.raddatz.veyq.core.testing.race
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.UUID

/**
 * Slice tests for changing the sign-in address (uc.account.change-email, ADR 0074 Decision 8).
 *
 * Every refusal has a live control on the same predicate, and every "nothing changed" is read from the account row
 * and the pending row, not inferred from a status. The GreenMail mailbox is the oracle for what was mailed where.
 * Each test makes its own accounts, tenant and domain: the `%test` database and the in-memory limiters live as long
 * as the JVM.
 */
@QuarkusTest
class EmailChangeSliceTest {

    @Inject
    lateinit var tenantStore: TenantStore

    @Inject
    lateinit var sessions: SessionService

    @Inject
    lateinit var store: EmailChangeStore

    @Inject
    lateinit var purge: EmailConfirmationPurge

    // --- the request ---

    @Test
    fun aRequestStoresThePendingChangeHashedAndMailsTheNewAddress(): Unit = TestMailbox(REQUEST_SMTP_PORT).start().use { mail ->
        val tenant = sendableTenant(mail)
        val a = account(tenant)
        val target = fresh("new")
        val before = Instant.now()
        request(a, target).then().statusCode(202)

        val token = mail.awaitToken(target)
        val row = pendingOf(a.id)!!
        assertEquals(Tokens.hash(token), row.tokenHash, "only the SHA-256 of the token is stored")
        assertEquals(target, row.newEmail)
        assertEquals(a.newAuth.publicKey, row.authPublicKey, "the key derived under the new address waits with it")
        val ttl = Duration.between(before, row.expiresAt)
        assertTrue(ttl > Duration.ofHours(23) && ttl <= Duration.ofHours(24).plusMinutes(1), "PT24H, was $ttl")
        assertFalse(mail.deliveredTo(a.email), "nothing goes to the old address before the change is confirmed")
        assertEquals(a.email, emailOf(a.id), "a request changes nothing on the account")
        assertEquals(a.auth.publicKey, authKeyOf(a.id))

        val body = mail.awaitBody(target).replace(Regex("=\r?\n"), "")
        assertTrue(body.contains("https://${tenant.domain}/confirm-email-change#$token"), "the link, token in the fragment")
        assertFalse(body.contains(a.email), "the link mail does not name the old address")
    }

    @Test
    fun aNewerRequestReplacesTheOlderOne(): Unit = TestMailbox(REPLACE_SMTP_PORT).start().use { mail ->
        val tenant = sendableTenant(mail)
        val a = account(tenant)
        val first = fresh("first")
        val second = fresh("second")
        request(a, first).then().statusCode(202)
        val firstToken = mail.awaitToken(first)
        request(a, second).then().statusCode(202)
        val secondToken = mail.awaitToken(second)

        confirm(a, firstToken).then().statusCode(404).extract().answer().also { assertEquals(INVALID, it) }
        assertEquals(a.email, emailOf(a.id), "a replaced link installs nothing")
        confirm(a, secondToken).then().statusCode(204)
        assertEquals(second, emailOf(a.id), "control: the newer link installs its address")
    }

    @Test
    fun eachHalfOfTheColdCredentialIsCheckedAndARefusalStoresNothing(): Unit = TestMailbox(PROOF_SMTP_PORT).start().use { mail ->
        val tenant = sendableTenant(mail)
        val a = account(tenant)
        val target = fresh("proof")

        // The password: an attestation by a key that is not the account's current auth key.
        request(a, target, attestation = TestProofKey().sign(attestationOf(a, target)))
            .then().statusCode(403).extract().answer().also { assertEquals(403 to "attestation_failed", it) }
        // The attestation binds the address: one made for another address is no attestation for this one.
        request(a, target, attestation = a.auth.sign(attestationOf(a, fresh("elsewhere"))))
            .then().statusCode(403).extract().answer().also { assertEquals(403 to "attestation_failed", it) }
        // The Secret Key: a session and the password, but a proof under a key the account never registered.
        request(a, target, proof = TestProofKey().sign(proofOf(a, target)))
            .then().statusCode(403).extract().answer().also { assertEquals(403 to "secret_key_proof_failed", it) }
        // The proof binds the address too.
        request(a, target, proof = a.proof.sign(proofOf(a, fresh("elsewhere"))))
            .then().statusCode(403).extract().answer().also { assertEquals(403 to "secret_key_proof_failed", it) }

        assertNull(pendingOf(a.id), "a refused request stores nothing")
        assertFalse(mail.deliveredTo(target), "and mails nothing")

        // Control: the same request with both halves valid.
        request(a, target).then().statusCode(202)
        mail.awaitBody(target)
    }

    @Test
    fun noRelayAnswers503BeforeAnythingIsStored(): Unit = TestMailbox(RELAY_SMTP_PORT).start().use { mail ->
        val silent = silentTenant()
        val a = account(silent)
        request(a, fresh("silent"), host = silent.domain)
            .then().statusCode(503).extract().answer().also { assertEquals(503 to "mail_unavailable", it) }
        assertNull(pendingOf(a.id), "no transport, nothing stored — and no copy-link")

        // Control: the same account on a tenant that can send.
        val sendable = sendableTenant(mail)
        QuarkusTransaction.requiringNew().run { TenantRoots.join(a.id, sendable.id) }
        val target = fresh("sendable")
        request(a, target, host = sendable.domain).then().statusCode(202)
        mail.awaitBody(target)
    }

    @Test
    fun aSendThatFailsWithdrawsTheChangeAndAnswers503() {
        // Sendable by its settings, undeliverable in fact: nothing listens on the relay's port.
        val tenant = silentTenantThatCanSend()
        val a = account(tenant)
        request(a, fresh("dead-relay"))
            .then().statusCode(503).extract().answer().also { assertEquals(503 to "mail_unavailable", it) }
        assertNull(pendingOf(a.id), "a link that never left leaves no pending change behind (ADR 0055 §6)")
    }

    @Test
    fun aNonMemberIsRefusedAndNothingIsStored(): Unit = TestMailbox(MEMBER_SMTP_PORT).start().use { mail ->
        val tenant = sendableTenant(mail)
        val outsider = account(silentTenant())
        val target = fresh("outsider")
        request(outsider, target, host = tenant.domain)
            .then().statusCode(404).extract().answer().also { assertEquals(404 to "tenant_not_found", it) }
        assertNull(pendingOf(outsider.id))
        assertFalse(mail.deliveredTo(target))

        val insider = account(tenant)
        request(insider, target, host = tenant.domain).then().statusCode(202)
    }

    @Test
    fun aTakenAddressGetsTheSameAnswerAsAFreeOne(): Unit = TestMailbox(TAKEN_SMTP_PORT).start().use { mail ->
        val tenant = sendableTenant(mail)
        val holder = account(tenant)
        val a = account(tenant)
        val free = fresh("free")

        val takenAnswer = request(a, holder.email).then().extract()
        val takenToken = mail.awaitToken(holder.email)
        val b = account(tenant)
        val freeAnswer = request(b, free).then().extract()
        mail.awaitToken(free)

        assertEquals(202, takenAnswer.statusCode())
        assertEquals(freeAnswer.statusCode(), takenAnswer.statusCode(), "the status says nothing about the address")
        assertEquals(freeAnswer.body().asString(), takenAnswer.body().asString(), "nor does the body")
        assertTrue(pendingOf(a.id) != null && pendingOf(b.id) != null, "both are stored alike")

        // E1: the taken address is refused at the confirmation, neutrally, and nothing changes.
        confirm(a, takenToken).then().statusCode(404).extract().answer().also { assertEquals(INVALID, it) }
        assertEquals(a.email, emailOf(a.id))
        assertEquals(a.auth.publicKey, authKeyOf(a.id))
        assertEquals(holder.email, emailOf(holder.id), "the holder keeps its address")
        assertTrue(pendingOf(a.id) != null, "a refusal at the swap rolls its spend back: nothing changed at all")
    }

    @Test
    fun theMailBudgetIsSharedWithTheConfirmation(): Unit = TestMailbox(BUDGET_SMTP_PORT).start().use { mail ->
        val tenant = sendableTenant(mail)
        val a = account(tenant)
        repeat(PER_ACCOUNT_MAX - 1) { request(a, fresh("budget")).then().statusCode(202) }
        given().header("Authorization", a.bearer).header("X-Forwarded-Host", tenant.domain)
            .`when`().post("/api/account/email/confirmation").then().statusCode(202)
        val over = request(a, fresh("over")).then().extract()
        assertEquals(429 to "rate_limited", over.answer(), "one account, one mail budget across both mail kinds")
        assertTrue(over.header("Retry-After") != null)
    }

    // --- the confirmation ---

    @Test
    fun theConfirmationSwapsAddressAndKeyRecordsItConfirmedAndRevokesOtherSessions(): Unit =
        TestMailbox(SWAP_SMTP_PORT).start().use { mail ->
            val tenant = sendableTenant(mail)
            val a = account(tenant)
            val other = sessions.issue(a.id).accessToken
            val target = fresh("swap")
            // Asked for in another spelling: both statements and the stored address are the normalized one.
            request(a, target.uppercase()).then().statusCode(202)
            val token = mail.awaitToken(target)
            assertFalse(confirmedOf(a.id), "precondition: a seeded account is unconfirmed")

            confirm(a, token).then().statusCode(204)

            assertEquals(target, emailOf(a.id), "the address is swapped")
            assertEquals(a.newAuth.publicKey, authKeyOf(a.id), "with the key derived under it, in the same write")
            assertTrue(confirmedOf(a.id), "the link proved the new address")
            assertNull(pendingOf(a.id), "the pending change is spent")
            given().header("Authorization", "Bearer $other").`when`().get("/api/account/email").then().statusCode(401)
            given().header("Authorization", a.bearer).`when`().get("/api/account/email").then().statusCode(200)

            // Sign-in: the new address with the new key works; the old key under it does not, nor the old address.
            assertEquals(200, login(target, a.newAuth), "the new address signs in with the key derived under it")
            assertEquals(401, login(target, a.auth), "the old auth key no longer signs in")
            assertEquals(401, login(a.email, a.auth), "the old address no longer names the account")
        }

    @Test
    fun theOldAddressGetsTheFixedNoticeAndNeverTheNewAddress(): Unit = TestMailbox(NOTICE_SMTP_PORT).start().use { mail ->
        val tenant = sendableTenant(mail)
        val a = account(tenant)
        val target = fresh("notice")
        request(a, target).then().statusCode(202)
        confirm(a, mail.awaitToken(target)).then().statusCode(204)

        val notice = mail.awaitBody(a.email).replace(Regex("=\r?\n"), "")
        assertTrue(notice.contains(tenant.domain), "it names the domain")
        assertFalse(notice.contains(target), "never the new address (ADR 0049's closed set)")
        assertFalse(notice.contains(target.substringBefore('@')), "not even its local part")
        assertFalse(notice.contains('#'), "it carries no link secret")
        assertEquals(1, mail.countTo(a.email))
    }

    @Test
    fun aLinkOpenedInAnotherAccountsSessionChangesNothing(): Unit = TestMailbox(FOREIGN_SMTP_PORT).start().use { mail ->
        val tenant = sendableTenant(mail)
        val a = account(tenant)
        val b = account(tenant)
        val target = fresh("foreign")
        request(a, target).then().statusCode(202)
        val token = mail.awaitToken(target)

        confirm(b, token).then().statusCode(404).extract().answer().also { assertEquals(INVALID, it) }
        assertEquals(b.email, emailOf(b.id))
        assertEquals(a.email, emailOf(a.id))
        assertTrue(pendingOf(a.id) != null, "a refusal spends nothing")

        confirm(a, token).then().statusCode(204)
        assertEquals(target, emailOf(a.id), "control: the account's own session, the very same token")
    }

    @Test
    fun aUsedOrExpiredLinkChangesNothing() {
        val tenant = silentTenantThatCanSend()
        val a = account(tenant)
        val target = fresh("used")
        val token = pending(a, target, Instant.now().plus(1, ChronoUnit.HOURS))
        confirm(a, token, host = tenant.domain).then().statusCode(204)
        confirm(a, token, host = tenant.domain).then().statusCode(404).extract().answer().also { assertEquals(INVALID, it) }

        val b = account(tenant)
        val expired = pending(b, fresh("expired"), Instant.now().minus(1, ChronoUnit.MINUTES))
        confirm(b, expired, host = tenant.domain).then().statusCode(404).extract().answer().also { assertEquals(INVALID, it) }
        assertEquals(b.email, emailOf(b.id), "an expired link installs nothing")
        assertEquals(b.auth.publicKey, authKeyOf(b.id))

        // Control: a live one for the same account.
        val live = fresh("live")
        confirm(b, pending(b, live, Instant.now().plus(1, ChronoUnit.HOURS)), host = tenant.domain).then().statusCode(204)
        assertEquals(live, emailOf(b.id))
    }

    @Test
    fun aRelayThatCannotSendTheNoticeChangesNothing(): Unit = TestMailbox(CONFIRM_RELAY_SMTP_PORT).start().use { mail ->
        val silent = silentTenant()
        val a = account(silent)
        val token = pending(a, fresh("no-notice"), Instant.now().plus(1, ChronoUnit.HOURS))
        confirm(a, token, host = silent.domain)
            .then().statusCode(503).extract().answer().also { assertEquals(503 to "mail_unavailable", it) }
        assertEquals(a.email, emailOf(a.id))
        assertTrue(pendingOf(a.id) != null, "the change waits for a relay")

        // Control: the same link once the tenant can send.
        mail.receiveFor(tenantStore, silent.id, silent.domain)
        confirm(a, token, host = silent.domain).then().statusCode(204)
    }

    @Test
    fun twoAccountsConfirmingOneAddressLeaveOneWinner() {
        val tenant = silentTenantThatCanSend()
        repeat(RACE_ROUNDS) { round ->
            val target = fresh("race-$round")
            val a = account(tenant)
            val b = account(tenant)
            val tokenA = pending(a, target, Instant.now().plus(1, ChronoUnit.HOURS))
            val tokenB = pending(b, target, Instant.now().plus(1, ChronoUnit.HOURS))

            val answers = race(
                { confirm(a, tokenA, host = tenant.domain).then().extract().answer() },
                { confirm(b, tokenB, host = tenant.domain).then().extract().answer() },
            )
            assertWinnerAndLoser(204, INVALID, answers, "round $round")
            val holders = listOf(a, b).filter { emailOf(it.id) == target }
            assertEquals(1, holders.size, "round $round: exactly one account holds the address")
            val loser = listOf(a, b).single { it != holders.single() }
            assertEquals(loser.email, emailOf(loser.id), "round $round: the loser is unchanged")
            assertEquals(loser.auth.publicKey, authKeyOf(loser.id))
        }
    }

    @Test
    fun thePurgeDeletesExpiredChangesAndKeepsLiveOnes() {
        val a = account(silentTenant())
        val b = account(silentTenant())
        pending(a, fresh("old"), Instant.now().minus(1, ChronoUnit.MINUTES))
        pending(b, fresh("live"), Instant.now().plus(1, ChronoUnit.HOURS))
        purge.run()
        assertNull(pendingOf(a.id), "an expired change is gone")
        assertTrue(pendingOf(b.id) != null, "a live one stays")
    }

    // --- helpers ---

    /** An account with a real auth key, a real proof key, a session, and the auth key it would have at a new address. */
    private class Account(val id: String, val email: String, val auth: TestProofKey, val proof: TestProofKey, val newAuth: TestProofKey, val bearer: String, val host: String)

    private fun account(tenant: ResolvedTenant): Account {
        val auth = TestProofKey()
        val proof = TestProofKey()
        val email = fresh("acc")
        val id = seedUser(email = email, authPublicKey = auth.publicKey, secretKeyProofPublicKey = proof.publicKey)
        QuarkusTransaction.requiringNew().run { TenantRoots.join(id, tenant.id) }
        return Account(id, email, auth, proof, TestProofKey(), "Bearer ${sessions.issue(id).accessToken}", tenant.domain)
    }

    private fun attestationOf(a: Account, email: String) = AuthAttestation.emailChangeStatement(a.id, email, a.newAuth.publicKey)

    private fun proofOf(a: Account, email: String) = SecretKeyProof.emailChangeStatement(a.auth.publicKey, a.newAuth.publicKey, email)

    private fun request(
        a: Account,
        email: String,
        attestation: String = a.auth.sign(attestationOf(a, email)),
        proof: String = a.proof.sign(proofOf(a, email)),
        host: String = a.host,
    ) = given().header("Authorization", a.bearer).header("X-Forwarded-Host", host).contentType(ContentType.JSON)
        .body(
            """{"newEmail":"$email","authPublicKey":"${a.newAuth.publicKey}",""" +
                """"attestationSignature":"$attestation","secretKeyProofSignature":"$proof"}""",
        )
        .`when`().post("/api/account/email-change")

    private fun confirm(a: Account, token: String, host: String = a.host) =
        given().header("Authorization", a.bearer).header("X-Forwarded-Host", host).contentType(ContentType.JSON)
            .body("""{"token":"$token"}""").`when`().post("/api/account/email-change/confirm")

    /** A pending change written straight to the store — for the tests about the confirmation alone. */
    private fun pending(a: Account, email: String, expiresAt: Instant): String {
        val token = Tokens.random()
        QuarkusTransaction.requiringNew().run { store.replace(a.id, email, a.newAuth.publicKey, Tokens.hash(token), expiresAt) }
        return token
    }

    /** Sign in by challenge + verify; the status of the verify. */
    private fun login(email: String, key: TestProofKey): Int {
        val nonce: String = given().contentType(ContentType.JSON).body("""{"email":"$email"}""")
            .`when`().post("/api/login/challenge").then().statusCode(200).extract().path("nonce")
        return given().contentType(ContentType.JSON)
            .body("""{"email":"$email","nonce":"$nonce","signature":"${key.sign(Base64.getDecoder().decode(nonce))}"}""")
            .`when`().post("/api/login/verify").then().extract().statusCode()
    }

    private fun pendingOf(userId: String): EmailChangeEntity? =
        QuarkusTransaction.requiringNew().call { EmailChangeEntity.find("userId", userId).firstResult() }

    private fun emailOf(userId: String): String = QuarkusTransaction.requiringNew().call { VeyqUser.findById(userId)!!.email }

    private fun authKeyOf(userId: String): String = QuarkusTransaction.requiringNew().call { VeyqUser.findById(userId)!!.authPublicKey }

    private fun confirmedOf(userId: String): Boolean =
        QuarkusTransaction.requiringNew().call { VeyqUser.findById(userId)!!.emailConfirmedAt != null }

    private fun fresh(label: String) = "$label-${UUID.randomUUID().toString().take(8)}@example.com"

    private fun sendableTenant(mail: TestMailbox): ResolvedTenant = silentTenant().also { mail.receiveFor(tenantStore, it.id, it.domain) }

    private fun silentTenant(): ResolvedTenant {
        val domain = "change-${UUID.randomUUID().toString().take(8)}.example.com"
        return ResolvedTenant(seedTenant(domain = domain), domain)
    }

    /** A tenant whose relay settings say it can send, pointed at a port no mailbox listens on: the confirmation's gate
     *  passes and the notice's send fails after the commit, which the tests about the swap alone do not read. */
    private fun silentTenantThatCanSend(): ResolvedTenant = silentTenant().also { t ->
        TestMailbox(DEAD_SMTP_PORT).receiveFor(tenantStore, t.id, t.domain)
    }

    private companion object {
        /** `veyq.ratelimit.email-confirmation.per-account-max-requests`. */
        const val PER_ACCOUNT_MAX = 3

        val INVALID = 404 to "email_change_invalid"

        // One JVM for the whole suite ⇒ ports no other test holds (`grep -rhoE '139[0-9]{2}' backend/`).
        const val REQUEST_SMTP_PORT = 13980
        const val REPLACE_SMTP_PORT = 13981
        const val PROOF_SMTP_PORT = 13982
        const val RELAY_SMTP_PORT = 13983
        const val MEMBER_SMTP_PORT = 13984
        const val TAKEN_SMTP_PORT = 13985
        const val BUDGET_SMTP_PORT = 13986
        const val SWAP_SMTP_PORT = 13987
        const val NOTICE_SMTP_PORT = 13988
        const val FOREIGN_SMTP_PORT = 13989
        const val CONFIRM_RELAY_SMTP_PORT = 13990

        /** Nothing listens here. */
        const val DEAD_SMTP_PORT = 13991
    }
}
