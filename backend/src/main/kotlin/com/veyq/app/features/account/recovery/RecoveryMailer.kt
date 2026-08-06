package com.veyq.app.features.account.recovery

import com.veyq.app.features.admin.tenant.MailContext
import com.veyq.app.features.admin.tenant.TenantMailSender
import com.veyq.app.generated.model.DeliveryResult
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Sends the password-recovery link (cr-account-recovery, ADR 0054).
 *
 * Reuses [TenantMailSender] for settings, sendability and the wire — and differs from the invite mailer in the
 * two ways that matter:
 *
 * **1. There is no fallback.** An invite that cannot be mailed degrades to a copy-link the owner hands over.
 * Recovery has nobody to hand it to: routing a recovery link through the operator would make them a participant
 * in a flow built to exclude them (ADR 0054 § Decision 4). A non-sendable instance reports
 * [DeliveryResult.FALLBACK_COPYLINK] to say "no mail left here" — and the handler treats that as *no recovery*,
 * never as a link to surface.
 *
 * **2. The copy is veyq-fixed, not owner-authorable.** The per-tenant template exists so an owner can word an
 * invitation in their own voice; an owner-authored *recovery* mail is a phishing surface aimed at their own
 * members (ADR 0054 § Decision 5). The text still obeys the ADR 0049 boundary — link, domain, expiry, and
 * nothing that names the person or what is being recovered.
 */
@ApplicationScoped
class RecoveryMailer(private val sender: TenantMailSender) {

    /** Whether this domain can send at all — the handler asks before minting anything. */
    fun canSend(domain: String): Boolean = sender.sendable(sender.settings(domain))

    fun sendRecoveryLink(domain: String, email: String, token: String, expiresAt: Instant): DeliveryResult {
        val settings = sender.settings(domain)
        if (!sender.sendable(settings)) return DeliveryResult.FALLBACK_COPYLINK
        val s = settings!!
        // The secret rides the URL fragment, like every other veyq link (ADR 0020): a fragment is never sent to
        // the server as part of a navigation and never reaches an access log.
        val ctx = MailContext(
            link = "https://$domain/recover#$token",
            domain = domain,
            expiry = EXPIRY_FMT.format(expiresAt),
        )
        return sender.deliver(
            s,
            sender.fromAddress(s, domain),
            email,
            subject(ctx),
            body(ctx),
            "a password-recovery link",
        )
    }

    // Bilingual EN+DE in one message, matching the invite defaults: the instance does not know which language
    // this person reads, and a recovery mail is the wrong place to guess.
    private fun subject(ctx: MailContext) = "Reset your password on ${ctx.domain} / Passwort zurücksetzen"

    private fun body(ctx: MailContext) = """
        Someone asked to reset the password for this address on ${ctx.domain}.

        Open this link to continue: ${ctx.link}
        It stops working after ${ctx.expiry}.

        You will also need the Secret Key from your Emergency Kit — the link alone cannot open your account,
        and without the kit nobody can, including whoever runs ${ctx.domain}.

        If this was not you, nothing has happened yet and you can ignore this mail.

        —

        Jemand möchte das Passwort für diese Adresse auf ${ctx.domain} zurücksetzen.

        Zum Fortfahren diesen Link öffnen: ${ctx.link}
        Er wird nach ${ctx.expiry} ungültig.

        Du brauchst außerdem den Secret Key aus deinem Notfall-Kit — der Link allein öffnet dein Konto nicht,
        und ohne das Kit kann es niemand öffnen, auch nicht der Betrieb von ${ctx.domain}.

        Warst du das nicht, ist bisher nichts passiert und du kannst diese Mail ignorieren.
    """.trimIndent()

    private companion object {
        val EXPIRY_FMT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC)
    }
}
