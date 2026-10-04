package com.veyq.core.admin.tenant

import com.veyq.app.generated.model.DeliveryResult
import com.veyq.core.crypto.ConfigSecretKeyGuard
import com.veyq.core.crypto.ConfigSecretKeyState
import com.veyq.core.crypto.ConfigSecretSeal
import com.veyq.core.crypto.SealedSecret
import io.vertx.core.Vertx
import io.vertx.ext.mail.LoginOption
import io.vertx.ext.mail.MailClient
import io.vertx.ext.mail.MailConfig
import io.vertx.ext.mail.MailMessage
import io.vertx.ext.mail.StartTLSOptions
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import java.util.concurrent.TimeUnit

/**
 * The per-tenant SMTP transport, extracted from [SmtpTenantInviteMailer] (cr-account-recovery) so a second kind
 * of mail can reuse it instead of copying it.
 *
 * It knows how to resolve one tenant's mail settings (by tenant id), decide whether they are actually sendable, and put a
 * message on the wire. It knows **nothing** about what the message says — subject and body arrive rendered, so
 * each mail kind keeps its own copy rules (an invite's is owner-authorable, a recovery mail's is not; ADR 0054
 * § Decision 5).
 *
 * Best-effort by design: a failure is logged secret-free and reported as [DeliveryResult.FAILED], never thrown.
 * Callers send **after** their mint transaction commits, so the bounded [SEND_TIMEOUT_SECONDS] block never holds
 * a DB connection (cr-email-invite-send-hardening).
 */
@ApplicationScoped
class TenantMailSender(
    private val store: TenantStore,
    private val seal: ConfigSecretSeal,
    private val keyState: ConfigSecretKeyGuard,
    private val vertx: Vertx,
) {
    private val log = Logger.getLogger(TenantMailSender::class.java)

    /** The tenant's settings, or null when it has none. Keyed by tenant **id**, never by a domain string: a domain
     *  can arrive from a request header, and a header value must never be able to pick whose relay sends. */
    fun settings(tenantId: String): MailSettingsRow? = store.mailSettingsFor(tenantId)

    /**
     * Whether [s] can actually put mail on the wire.
     *
     * Mirrors [deliver]: SMTP auth is keyed on `username`, so an auth-less MTA (a trusted LAN relay) sends with
     * no login and needs neither a stored password nor an openable KEK. With a username set, both are required —
     * `state() != OK` means the KEK is ABSENT or MISMATCHed, so the stored password cannot be unsealed
     * (ADR 0050).
     */
    fun sendable(s: MailSettingsRow?): Boolean =
        s != null && s.enabled && !s.host.isNullOrBlank() &&
            (s.username.isNullOrBlank() || (s.passwordCiphertext != null && keyState.state() == ConfigSecretKeyState.OK))

    /** The tenant's own from-address, falling back to a neutral one on its domain. */
    fun fromAddress(s: MailSettingsRow, domain: String): String =
        s.from?.takeUnless { it.isBlank() } ?: "noreply@$domain"

    /**
     * Build a per-tenant SMTP client and send. [what] names the mail kind for the failure log — it must never
     * carry a token, a link, or anything else secret.
     */
    fun deliver(s: MailSettingsRow, from: String, to: String, subject: String, body: String, what: String): DeliveryResult {
        val cfg = MailConfig()
            .setHostname(s.host)
            .setPort(s.port ?: DEFAULT_SMTP_PORT)
            .setStarttls(if (s.tls) StartTLSOptions.REQUIRED else StartTLSOptions.DISABLED)
        if (!s.username.isNullOrBlank()) {
            cfg.setUsername(s.username)
                .setPassword(seal.unseal(SealedSecret(s.passwordCiphertext!!, s.passwordNonce!!)))
                .setLogin(LoginOption.REQUIRED)
        }
        val client = MailClient.create(vertx, cfg)
        return try {
            val message = MailMessage().setFrom(from).setTo(to).setSubject(subject).setText(body)
            client.sendMail(message).toCompletionStage().toCompletableFuture().get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            DeliveryResult.SENT
        } catch (e: Exception) {
            // Never log the token or the link; only the kind, the recipient and a cause message.
            log.warnf("SMTP delivery of %s to %s failed: %s", what, to, e.message)
            DeliveryResult.FAILED
        } finally {
            client.close()
        }
    }

    companion object {
        const val DEFAULT_SMTP_PORT = 587

        /** Bounded, and outside the mint transaction — long enough for a healthy MTA handshake, short enough
         *  that a hung server does not hold the request thread. */
        const val SEND_TIMEOUT_SECONDS = 10L
    }
}
