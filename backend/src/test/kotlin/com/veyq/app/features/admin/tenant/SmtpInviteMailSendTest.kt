package com.veyq.app.features.admin.tenant

import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.GreenMailUtil
import com.icegreen.greenmail.util.ServerSetup
import com.veyq.app.generated.model.DeliveryResult
import com.veyq.app.shared.crypto.ConfigSecretKeyGuard
import com.veyq.app.shared.crypto.ConfigSecretSeal
import com.veyq.app.shared.instance.InstanceStore
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * End-to-end SMTP send (cr-email-smtp-transport, ADR 0048/0049) against a **hermetic in-JVM GreenMail** server —
 * runs in `./gradlew check`, no external container. Proves two things the renderer unit test cannot: that the
 * `@Alternative` [SmtpTenantInviteMailer] is the wired [TenantInviteMailer], and that a **real** sent mail carries
 * exactly the rendered closed-set content (link+domain+expiry + owner prose) from the tenant's own address — and
 * that an unconfigured tenant sends **no** SMTP at all (the copy-link fallback). Mailpit (`mail` profile) is the
 * manual dev smoke; this is the automated coverage claim.
 */
@QuarkusTest
class SmtpInviteMailSendTest {

    @Inject lateinit var mailer: TenantInviteMailer // the @Alternative SmtpTenantInviteMailer wins this injection
    @Inject lateinit var store: TenantStore
    @Inject lateinit var seal: ConfigSecretSeal
    @Inject lateinit var keyState: ConfigSecretKeyGuard
    @Inject lateinit var instance: InstanceStore

    @Test
    fun aConfiguredTenantSendsARenderedInviteCarryingOnlyTheClosedSet() {
        val greenMail = GreenMail(ServerSetup(SMTP_PORT, "127.0.0.1", "smtp"))
        greenMail.start()
        try {
            greenMail.setUser("smtpuser", "smtppass") // Vert.x AUTHs with the unsealed password against this user
            val domain = "send.example.com"
            seedTenant(domain)
            val sealed = seal.seal("smtppass")
            QuarkusTransaction.requiringNew().run {
                store.saveMailSettings(
                    tenantIdByDomain(domain),
                    MailSettingsRow(
                        host = "127.0.0.1", port = SMTP_PORT, from = "noreply@$domain", username = "smtpuser",
                        passwordCiphertext = sealed.ciphertext, passwordNonce = sealed.nonce,
                        tls = false, enabled = true,
                        subjectTemplate = "Invite to {{domain}}",
                        bodyTemplate = "Hi! You are invited to {{domain}}. Accept {{link}} before {{expiry}}.",
                    ),
                )
            }

            val delivery = mailer.sendInvite(domain, "invitee@elsewhere.example", "TOKEN-abc123", Instant.parse("2026-07-31T12:00:00Z"))

            assertEquals(DeliveryResult.SENT, delivery, "a configured tenant that sends OK reports SENT")
            assertTrue(greenMail.waitForIncomingEmail(5_000, 1), "the invite mail should be delivered")
            val messages = greenMail.receivedMessages
            assertEquals(1, messages.size)
            val message = messages[0]
            assertEquals("Invite to send.example.com", message.subject)
            assertEquals("noreply@send.example.com", message.from[0].toString())
            val body = GreenMailUtil.getBody(message)
            assertTrue(body.contains("https://send.example.com/tenant/accept#TOKEN-abc123"), "the accept link (secret in fragment) rides the body")
            assertTrue(body.contains("send.example.com"), "the tenant domain rides the body")
            assertTrue(body.contains("2026-07-31 12:00 UTC"), "the expiry rides the body")
            assertFalse(body.contains("{{"), "every closed-set tag must be substituted — no raw template markers")
        } finally {
            greenMail.stop()
        }
    }

    @Test
    fun anUnconfiguredTenantCannotSendAndAttemptsNoSmtp() {
        val greenMail = GreenMail(ServerSetup(SMTP_PORT_2, "127.0.0.1", "smtp"))
        greenMail.start()
        try {
            val domain = "no-smtp.example.com"
            // Deliberately no mail-settings row ⇒ the port's "cannot send" answer. Whether that becomes an actual
            // copy-link is the CALLER's business: only the ServerOwner bootstrap invite still has one (ADR 0055
            // §3); a member invite is refused with 503 before it ever reaches this port (§2).
            seedTenant(domain)

            val delivery = mailer.sendInvite(domain, "x@elsewhere.example", "TOKEN-xyz", Instant.parse("2026-07-31T12:00:00Z"))

            assertEquals(DeliveryResult.FALLBACK_COPYLINK, delivery, "an unconfigured tenant reports cannot-send")
            assertFalse(greenMail.waitForIncomingEmail(1_000, 1), "an unconfigured tenant must not send SMTP")
            assertEquals(0, greenMail.receivedMessages.size)
        } finally {
            greenMail.stop()
        }
    }

    @Test
    fun anAuthlessHostSendsWithNoLoginEvenWithoutAPassword() {
        // A legit auth-less MTA (e.g. a trusted LAN relay): enabled + host, but NO username ⇒ no SMTP login, so no
        // password (and no KEK) is needed. The send-gate keys auth on `username` like `deliver` does, so this must
        // SEND — it must NOT wrongly fall back to the copy-link just because `passwordCiphertext` is null.
        val greenMail = GreenMail(ServerSetup(AUTHLESS_SMTP_PORT, "127.0.0.1", "smtp")) // no setUser ⇒ accepts anonymously
        greenMail.start()
        try {
            val domain = "authless.example.com"
            seedTenant(domain)
            QuarkusTransaction.requiringNew().run {
                store.saveMailSettings(
                    tenantIdByDomain(domain),
                    MailSettingsRow(
                        host = "127.0.0.1", port = AUTHLESS_SMTP_PORT, from = "noreply@$domain",
                        username = null, passwordCiphertext = null, passwordNonce = null,
                        tls = false, enabled = true, subjectTemplate = "Invite to {{domain}}",
                        bodyTemplate = "Accept {{link}} before {{expiry}}.",
                    ),
                )
            }

            val delivery = mailer.sendInvite(domain, "invitee@elsewhere.example", "TOKEN-authless", Instant.parse("2026-07-31T12:00:00Z"))

            assertEquals(DeliveryResult.SENT, delivery, "an auth-less host (no username/password) still sends — no copy-link fallback")
            assertTrue(greenMail.waitForIncomingEmail(5_000, 1), "the invite mail should be delivered over the anonymous MTA")
            assertEquals(1, greenMail.receivedMessages.size)
            assertEquals("noreply@authless.example.com", greenMail.receivedMessages[0].from[0].toString())
        } finally {
            greenMail.stop()
        }
    }

    @Test
    fun aConfiguredTenantWhoseSmtpIsUnreachableReportsFailed() {
        // A tenant with enabled + fully-configured SMTP, but nothing is listening on the configured port. The send
        // errors (connection refused) and must surface as FAILED — no longer silently swallowed. The owner still
        // holds the returned copy-link (the mint already committed, in the real flow, before this send ran).
        val domain = "dead-smtp.example.com"
        seedTenant(domain)
        val sealed = seal.seal("smtppass")
        QuarkusTransaction.requiringNew().run {
            store.saveMailSettings(
                tenantIdByDomain(domain),
                MailSettingsRow(
                    host = "127.0.0.1", port = DEAD_SMTP_PORT, from = "noreply@$domain", username = "smtpuser",
                    passwordCiphertext = sealed.ciphertext, passwordNonce = sealed.nonce,
                    tls = false, enabled = true, subjectTemplate = null, bodyTemplate = null,
                ),
            )
        }

        val delivery = mailer.sendInvite(domain, "invitee@elsewhere.example", "TOKEN-dead", Instant.parse("2026-07-31T12:00:00Z"))

        assertEquals(DeliveryResult.FAILED, delivery, "a configured tenant whose SMTP send errors reports FAILED")
    }

    @Test
    fun aConfiguredTenantWhoseConfigSecretKeyChangedCannotSendAndAttemptsNoSmtp() {
        // Enabled + fully-configured SMTP, but the config-secret key no longer matches the one that sealed the
        // password (the key file changed/was lost ⇒ MISMATCH, ADR 0050 amendment). The stored password can't be
        // unsealed, so the port must never attempt a send with a bad key.
        //
        // What FALLBACK_COPYLINK means here is the **port's** "cannot send" answer, not a channel: on a member
        // invite the handler asks `canSend` first and refuses with 503 before any mint (ADR 0055 §2), so this
        // value only ever becomes an actual copy-link on the ServerOwner bootstrap invite (§3). This test pins
        // the port; MemberAddSliceTest pins the refusal that sits above it.
        val greenMail = GreenMail(ServerSetup(MISMATCH_SMTP_PORT, "127.0.0.1", "smtp"))
        greenMail.start()
        try {
            greenMail.setUser("smtpuser", "smtppass")
            val domain = "key-changed.example.com"
            seedTenant(domain)
            val sealed = seal.seal("smtppass") // sealed under the CURRENT (OK) key
            QuarkusTransaction.requiringNew().run {
                store.saveMailSettings(
                    tenantIdByDomain(domain),
                    MailSettingsRow(
                        host = "127.0.0.1", port = MISMATCH_SMTP_PORT, from = "noreply@$domain", username = "smtpuser",
                        passwordCiphertext = sealed.ciphertext, passwordNonce = sealed.nonce,
                        tls = false, enabled = true, subjectTemplate = null, bodyTemplate = null,
                    ),
                )
            }
            // Simulate the running key no longer matching what the DB fingerprint recorded ⇒ state() = MISMATCH.
            QuarkusTransaction.requiringNew().run { instance.setConfigSecretFingerprint("not-the-current-key") }

            val delivery = mailer.sendInvite(domain, "x@elsewhere.example", "TOKEN-k", Instant.parse("2026-07-31T12:00:00Z"))

            assertEquals(DeliveryResult.FALLBACK_COPYLINK, delivery, "a MISMATCH key ⇒ cannot-send, not a send")
            assertFalse(greenMail.waitForIncomingEmail(1_000, 1), "a MISMATCH key must not send SMTP")
            assertEquals(0, greenMail.receivedMessages.size)
        } finally {
            keyState.recordReseal() // restore OK for the rest of the JVM-shared suite
            greenMail.stop()
        }
    }

    private fun seedTenant(domain: String) {
        QuarkusTransaction.requiringNew().run {
            TenantEntity().apply {
                id = UUID.randomUUID().toString(); name = "Fam"; this.domain = domain
                ownerEmail = "owner@$domain"; status = TenantEntity.ACTIVE; createdAt = Instant.now()
            }.persist()
        }
    }

    private fun tenantIdByDomain(domain: String): String =
        QuarkusTransaction.requiringNew().call { TenantEntity.find("domain", domain).firstResult()!!.id }

    private companion object {
        const val SMTP_PORT = 13925
        const val SMTP_PORT_2 = 13926
        const val DEAD_SMTP_PORT = 13927 // nothing ever binds this — the send must fail fast (connection refused)
        const val MISMATCH_SMTP_PORT = 13928 // a live server that must NOT receive anything (the MISMATCH gate falls back first)
        const val AUTHLESS_SMTP_PORT = 13929 // an anonymous (no-AUTH) MTA — an auth-less host must still send here
    }
}
