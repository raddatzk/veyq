package com.veyq.app.features.admin.tenant

import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.GreenMailUtil
import com.icegreen.greenmail.util.ServerSetup
import io.quarkus.narayana.jta.QuarkusTransaction

/**
 * A hermetic in-JVM SMTP server plus the tenant mail settings that point at it — the channel a slice test has to
 * use now that a member invite no longer hands its secret back over HTTP (ADR 0055 §2).
 *
 * This is deliberately **not** a stubbed mailer. The tests that need the token read it out of a real delivered
 * message, the way the invited person does, so they exercise the whole gate → mint → render → send path rather
 * than a seam that happens to record a string. GreenMail already ships as a test dependency for
 * [SmtpInviteMailSendTest]; this only makes it reusable.
 *
 * **Auth-less on purpose.** No `setUser`, and the saved settings carry no `username` — `TenantMailSender.sendable`
 * keys SMTP auth on the username, so an auth-less relay needs neither a stored password nor an openable config
 * KEK. That keeps a test that only wants a *sendable* tenant from dragging in the whole ADR 0050 key setup.
 *
 * **Ports are per-instance and must not collide.** The suite shares one JVM, so allocate a fresh constant rather
 * than reusing another test's. Find the taken ones with `grep -rhoE '139[0-9]{2}' backend/src/test | sort -u`, not
 * from a comment's list: the list `RecoverySliceTest` kept had already missed `TenantOwnerGateTest`'s 13938.
 *
 * **Trap — declare `: Unit` when a test's whole body is `use { … }`.** `use` returns its block's last value, so
 * `@Test fun x() = TestMailbox(p).start().use { … }` infers a non-`Unit` return type, and JUnit then **silently
 * skips** the method: a `[WARNING] … must not return a value` on stdout, the test absent from the run, and the
 * build green. That is the exact failure this class exists to prevent one level up, so it is worth the one word:
 * write `fun x(): Unit = TestMailbox(p).start().use { … }` (it compiles to `void`). Caught here by bite-testing a
 * *different* guard and finding that neither of them had ever executed.
 */
class TestMailbox(private val port: Int) : AutoCloseable {

    private val greenMail = GreenMail(ServerSetup(port, "127.0.0.1", "smtp"))

    fun start(): TestMailbox = apply { greenMail.start() }

    override fun close() = greenMail.stop()

    /**
     * Point [domain]'s tenant at this mailbox, so the tenant counts as sendable and its mail arrives here.
     * [subjectTemplate]/[bodyTemplate] stay null for recovery mail, whose copy is veyq-fixed (ADR 0054 §5).
     */
    fun receiveFor(store: TenantStore, tenantId: String, domain: String) {
        QuarkusTransaction.requiringNew().run {
            store.saveMailSettings(
                tenantId,
                MailSettingsRow(
                    host = "127.0.0.1", port = port, from = "noreply@$domain",
                    username = null, passwordCiphertext = null, passwordNonce = null,
                    tls = false, enabled = true, subjectTemplate = null, bodyTemplate = null,
                ),
            )
        }
    }

    /**
     * Wait for a message to [recipient] and return the one-time secret from its link's URL **fragment** — the
     * only place a veyq link ever carries one (ADR 0020), for invites and recovery alike.
     *
     * Fails loudly rather than returning null: a test that silently proceeds without a token would assert against
     * the next test's mail in this shared JVM.
     */
    fun awaitToken(recipient: String, timeoutMs: Long = 5_000): String {
        // Unfold quoted-printable soft breaks first. A veyq accept link runs past 76 characters, so the transport
        // splits it mid-token with a trailing `=` — matching the raw body yields a truncated secret that then
        // 404s at the accept endpoint, which is a confusing way to learn this.
        val body = awaitBody(recipient, timeoutMs).replace(SOFT_BREAK, "")
        return FRAGMENT.find(body)?.groupValues?.get(1)
            ?: error("mail to $recipient carries no #fragment token:\n$body")
    }

    /** The plain-text body of the (first) message to [recipient]. */
    fun awaitBody(recipient: String, timeoutMs: Long = 5_000): String {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            messageTo(recipient)?.let { return it }
            Thread.sleep(50)
        }
        error("no mail for $recipient within ${timeoutMs}ms (delivered: ${recipients()})")
    }

    /** Whether anything at all was delivered to [recipient] — for the "must NOT send" assertions. */
    fun deliveredTo(recipient: String): Boolean = messageTo(recipient) != null

    private fun messageTo(recipient: String): String? =
        greenMail.receivedMessages
            .firstOrNull { m -> m.allRecipients.any { it.toString().equals(recipient, ignoreCase = true) } }
            ?.let { GreenMailUtil.getBody(it) }

    private fun recipients(): List<String> =
        greenMail.receivedMessages.flatMap { it.allRecipients.map(Any::toString) }

    private companion object {
        /** A quoted-printable soft line break: `=` immediately before the newline the transport inserted. */
        val SOFT_BREAK = Regex("=\r?\n")

        /** A URL fragment of base64url characters — `Tokens.random()` yields 32 bytes unpadded, i.e. 43 of them.
         *  The `{40,}` floor is what makes a still-truncated match fail here instead of at the accept endpoint. */
        val FRAGMENT = Regex("""#([A-Za-z0-9_-]{40,})""")
    }
}
