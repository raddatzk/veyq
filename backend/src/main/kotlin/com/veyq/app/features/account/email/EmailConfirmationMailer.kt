package com.veyq.app.features.account.email

import com.veyq.app.features.admin.tenant.MailContext
import com.veyq.app.features.admin.tenant.TenantMailSender
import com.veyq.app.generated.model.DeliveryResult
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Sends the address-confirmation link (ADR 0074 Decision 6) — a sibling of `RecoveryMailer`, under the same rules:
 *
 * - **No fallback.** A copy-link would hand the token to the person already looking at the screen and prove
 *   nothing about their mailbox, so a tenant that cannot send is refused before anything is minted.
 * - **veyq-fixed copy.** Not owner-authorable, for the same reason as recovery (ADR 0054 §5).
 * - **The closed set.** Rendered over [MailContext] — link, domain, expiry and nothing else (ADR 0049): no name of
 *   the person and none of the tenant beyond its domain.
 */
@ApplicationScoped
class EmailConfirmationMailer(private val sender: TenantMailSender) {

    /** Whether this tenant can send at all — the handler asks before minting anything. */
    fun canSend(tenantId: String): Boolean = sender.sendable(sender.settings(tenantId))

    /** Mail the link through [tenantId]'s own relay. [domain] is that tenant's registered domain; it shapes the link
     *  and the From address and never selects the relay. */
    fun sendConfirmationLink(tenantId: String, domain: String, email: String, token: String, expiresAt: Instant): DeliveryResult {
        val settings = sender.settings(tenantId)
        if (!sender.sendable(settings)) return DeliveryResult.FALLBACK_COPYLINK
        val s = settings!!
        // The secret rides the URL fragment, like every other veyq link (ADR 0020): never sent with a navigation,
        // never in an access log.
        val ctx = MailContext(
            link = "https://$domain/confirm-email#$token",
            domain = domain,
            expiry = EXPIRY_FMT.format(expiresAt),
        )
        return sender.deliver(s, sender.fromAddress(s, domain), email, subject(ctx), body(ctx), "an address-confirmation link")
    }

    // Bilingual EN+DE in one message, like the recovery mail: the instance does not know which language this person
    // reads.
    private fun subject(ctx: MailContext) = "Confirm your email address on ${ctx.domain} / E-Mail-Adresse bestätigen"

    private fun body(ctx: MailContext) = """
        An account on ${ctx.domain} asked to confirm this email address.

        Open this link on a device where you are signed in: ${ctx.link}
        It stops working after ${ctx.expiry}.

        Confirming shows that a password-reset link sent here will reach you. If you ignore this mail, nothing
        happens and nothing changes.

        —

        Ein Konto auf ${ctx.domain} möchte diese E-Mail-Adresse bestätigen.

        Öffne diesen Link auf einem Gerät, auf dem du angemeldet bist: ${ctx.link}
        Er wird nach ${ctx.expiry} ungültig.

        Die Bestätigung zeigt, dass ein Link zum Zurücksetzen des Passworts dich hier erreicht. Ignorierst du diese
        Mail, passiert nichts und es ändert sich nichts.
    """.trimIndent()

    private companion object {
        val EXPIRY_FMT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC)
    }
}
