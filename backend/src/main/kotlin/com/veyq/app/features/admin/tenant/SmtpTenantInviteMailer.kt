package com.veyq.app.features.admin.tenant

import com.veyq.app.generated.model.DeliveryResult
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * The real, SMTP-backed [TenantInviteMailer] (cr-email-smtp-transport, ADR 0048/0049/0050). A per-tenant transport:
 * it resolves the tenant's own mail settings by the `domain` it is handed, and when that tenant has **enabled +
 * fully configured** SMTP (and the KEK is present so the password can be unsealed), it renders the invite over
 * the closed variable set ([InviteMailRenderer]) and sends it from the tenant's own address. Otherwise it
 * **delegates to [LoggingTenantInviteMailer]** (copy-link + the cr-0046 dev-file) — so SMTP-less tenants, the
 * owner-bootstrap invite (a PENDING tenant has no settings), and the whole dev/seed loop are unchanged.
 *
 * Settings lookup, the sendability rule and the wire itself now live on the shared [TenantMailSender]
 * (cr-account-recovery), so a second mail kind reuses them rather than copying them. What stays here is what is
 * specific to an invite: its link shape and its **owner-authorable** copy.
 *
 * **CDI:** a `@Priority`-enabled `@Alternative` so it wins the `TenantInviteMailer` injection point globally over
 * the plain [LoggingTenantInviteMailer] bean (which it injects **by concrete type** for the fallback — a concrete
 * bean type is never ambiguous, ADR 0048). No call site changes.
 *
 * **Best-effort, and first-class about it:** a send failure is surfaced as [DeliveryResult.FAILED], not swallowed.
 * The invite is already minted and its raw token is returned to the owner for the copy-link fallback, so a
 * transient SMTP error must not roll back the mint — the caller sends **after** the mint transaction commits
 * (cr-email-invite-send-hardening). The raw token / accept link is **never** logged, in any mode.
 */
@Alternative
@Priority(1)
@ApplicationScoped
class SmtpTenantInviteMailer(
    private val sender: TenantMailSender,
    private val renderer: InviteMailRenderer,
    private val fallback: LoggingTenantInviteMailer,
) : TenantInviteMailer {

    override fun canSend(domain: String): Boolean = sender.sendable(sender.settings(domain))

    override fun sendInvite(domain: String, ownerEmail: String, token: String, expiresAt: Instant): DeliveryResult {
        val settings = sender.settings(domain)
        if (!sender.sendable(settings)) return fallback.sendInvite(domain, ownerEmail, token, expiresAt)
        val s = settings!!
        // The accept link mirrors web/src/shared/inviteLink.ts: the secret rides the URL fragment (ADR 0020). The
        // domain is a bare hostname validated at tenant creation (CreateTenantRequest pattern), so it is safe here.
        val ctx = MailContext(
            link = "https://$domain/tenant/accept#$token",
            domain = domain,
            expiry = EXPIRY_FMT.format(expiresAt),
        )
        val subject = renderer.render(s.subjectTemplate ?: renderer.defaultSubject(), ctx)
        val body = renderer.render(s.bodyTemplate ?: renderer.defaultBody(), ctx)
        return sender.deliver(s, sender.fromAddress(s, domain), ownerEmail, subject, body, "a tenant invite")
    }

    private companion object {
        val EXPIRY_FMT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC)
    }
}
