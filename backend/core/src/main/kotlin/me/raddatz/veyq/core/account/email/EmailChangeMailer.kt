package me.raddatz.veyq.core.account.email

import me.raddatz.veyq.core.admin.tenant.MailContext
import me.raddatz.veyq.core.admin.tenant.TenantMailSender
import me.raddatz.veyq.core.generated.model.DeliveryResult
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * The two mails of a change of the sign-in address (uc.account.change-email) — siblings of
 * [EmailConfirmationMailer], under its rules: no copy-link fallback, veyq-fixed copy, rendered over [MailContext]'s
 * closed set (link, domain, expiry — ADR 0049) and nothing else.
 *
 * - **The link, to the NEW address.** Opening it in the account's own session is the proof the address is read.
 * - **The notice, to the OLD address.** It says the sign-in address changed and where to sign in; it cannot name the
 *   new address (it is outside the closed set, and whoever reads the old mailbox has no claim to it).
 */
@ApplicationScoped
class EmailChangeMailer(private val sender: TenantMailSender) {

    /** Whether this tenant can send at all — asked before anything is stored or changed. */
    fun canSend(tenantId: String): Boolean = sender.sendable(sender.settings(tenantId))

    fun sendChangeLink(tenantId: String, domain: String, newEmail: String, token: String, expiresAt: Instant): DeliveryResult {
        val settings = sender.settings(tenantId)
        if (!sender.sendable(settings)) return DeliveryResult.FALLBACK_COPYLINK
        val s = settings!!
        // The secret rides the URL fragment, like every other veyq link (ADR 0020).
        val ctx = MailContext(link = "https://$domain/confirm-email-change#$token", domain = domain, expiry = EXPIRY_FMT.format(expiresAt))
        return sender.deliver(s, sender.fromAddress(s, domain), newEmail, linkSubject(ctx), linkBody(ctx), "an address-change link")
    }

    fun sendChangedNotice(tenantId: String, domain: String, oldEmail: String): DeliveryResult {
        val settings = sender.settings(tenantId)
        if (!sender.sendable(settings)) return DeliveryResult.FALLBACK_COPYLINK
        val s = settings!!
        // No secret and no deadline: the link is the instance itself, and the expiry is not part of this copy.
        val ctx = MailContext(link = "https://$domain/", domain = domain, expiry = "")
        return sender.deliver(s, sender.fromAddress(s, domain), oldEmail, noticeSubject(ctx), noticeBody(ctx), "an address-changed notice")
    }

    // Bilingual EN+DE in one message, like the confirmation mail: the instance does not know which language is read.
    private fun linkSubject(ctx: MailContext) = "Confirm your new sign-in address on ${ctx.domain} / Neue Anmeldeadresse bestätigen"

    private fun linkBody(ctx: MailContext) = """
        An account on ${ctx.domain} asked to sign in with this email address from now on.

        Open this link on a device where you are signed in to that account: ${ctx.link}
        It stops working after ${ctx.expiry}.

        If you did not ask for this, ignore this mail: nothing changes.

        —

        Ein Konto auf ${ctx.domain} möchte sich ab jetzt mit dieser E-Mail-Adresse anmelden.

        Öffne diesen Link auf einem Gerät, auf dem du in diesem Konto angemeldet bist: ${ctx.link}
        Er wird nach ${ctx.expiry} ungültig.

        Wenn du das nicht angefragt hast, ignoriere diese Mail: Es ändert sich nichts.
    """.trimIndent()

    private fun noticeSubject(ctx: MailContext) = "Your sign-in address on ${ctx.domain} was changed / Deine Anmeldeadresse wurde geändert"

    private fun noticeBody(ctx: MailContext) = """
        The account on ${ctx.domain} that signed in with this email address now signs in with another one. Its other
        devices were signed out.

        If this was you, there is nothing to do. If it was not, someone holds your password and your Secret Key:
        contact the owner of ${ctx.domain} (${ctx.link}).

        —

        Das Konto auf ${ctx.domain}, das sich mit dieser E-Mail-Adresse angemeldet hat, meldet sich jetzt mit einer
        anderen an. Seine anderen Geräte wurden abgemeldet.

        Wenn du das warst, ist nichts zu tun. Wenn nicht, kennt jemand dein Passwort und deinen Secret Key: Wende dich
        an die Person, die ${ctx.domain} betreibt (${ctx.link}).
    """.trimIndent()

    private companion object {
        val EXPIRY_FMT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC)
    }
}
