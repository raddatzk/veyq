package me.raddatz.veyq.core.admin.tenant

import me.raddatz.veyq.core.generated.model.DeliveryResult
import me.raddatz.veyq.core.mail.SmtpDelivery
import me.raddatz.veyq.core.mail.SmtpTransport
import jakarta.enterprise.context.ApplicationScoped

/**
 * The per-tenant SMTP transport, extracted from [SmtpTenantInviteMailer] (cr-account-recovery) so a second kind
 * of mail can reuse it instead of copying it.
 *
 * It knows how to resolve one tenant's mail settings (by tenant id), decide whether they are actually sendable, and put a
 * message on the wire through the shared [SmtpDelivery] — the same `MailClient` construction instance mail uses
 * (ADR 0097), fed only this tenant's own row: a tenant's mail is never sent with any other settings, and its settings
 * never send anything but its own mail (ADR 0048 §4). It knows **nothing** about what the message says — subject and
 * body arrive rendered, so each mail kind keeps its own copy rules (an invite's is owner-authorable, a recovery mail's
 * is not; ADR 0054 § Decision 5).
 *
 * Best-effort by design: a failure is logged secret-free and reported as [DeliveryResult.FAILED], never thrown.
 * Callers send **after** their mint transaction commits, so the bounded [SEND_TIMEOUT_SECONDS] block never holds
 * a DB connection (cr-email-invite-send-hardening).
 */
@ApplicationScoped
class TenantMailSender(
    private val store: TenantStore,
    private val wire: SmtpDelivery,
) {
    /** The tenant's settings, or null when it has none. Keyed by tenant **id**, never by a domain string: a domain
     *  can arrive from a request header, and a header value must never be able to pick whose relay sends. */
    fun settings(tenantId: String): MailSettingsRow? = store.mailSettingsFor(tenantId)

    /**
     * Whether [s] can actually put mail on the wire: switched on by its owner, and sendable as a transport
     * ([SmtpDelivery.sendable] — an auth-less relay needs no password; with a username, the stored password must be
     * openable under the current config-secret key, ADR 0050).
     */
    fun sendable(s: MailSettingsRow?): Boolean = s != null && s.enabled && wire.sendable(s.transport())

    /** The tenant's own from-address, falling back to a neutral one on its domain. */
    fun fromAddress(s: MailSettingsRow, domain: String): String =
        s.from?.takeUnless { it.isBlank() } ?: "noreply@$domain"

    /**
     * Send through the tenant's own relay. [what] names the mail kind for the failure log — it must never carry a
     * token, a link, or anything else secret.
     */
    fun deliver(s: MailSettingsRow, from: String, to: String, subject: String, body: String, what: String): DeliveryResult =
        wire.deliver(s.transport(), from, to, subject, body, what)

    private fun MailSettingsRow.transport() =
        SmtpTransport(host, port, username, passwordCiphertext, passwordNonce, passwordFingerprint, tls)

    companion object {
        /** The shared wire's bound on one send, named here for the callers that time a request around one. */
        const val SEND_TIMEOUT_SECONDS = SmtpDelivery.SEND_TIMEOUT_SECONDS
    }
}
