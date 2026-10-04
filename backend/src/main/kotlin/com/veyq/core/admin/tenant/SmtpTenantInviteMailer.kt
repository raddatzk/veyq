package com.veyq.core.admin.tenant

import com.veyq.core.generated.model.DeliveryResult
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * The real, SMTP-backed [TenantInviteMailer] (cr-email-smtp-transport, ADR 0048/0049/0050). A per-tenant transport:
 * it resolves the tenant's own mail settings by the tenant **id** it is handed (the domain only shapes the link
 * and the From address), and when that tenant has **enabled + fully configured** SMTP (and the KEK is present so
 * the password can be unsealed), it renders the invite over the closed variable set ([InviteMailRenderer]) and sends it from the tenant's own address. Otherwise it
 * **delegates to [LoggingTenantInviteMailer]** (the copy-link). Since ADR 0055 §2 that
 * delegation is reachable **only for the Server-Owner's tenant-bootstrap invite** — a PENDING tenant has no mail
 * settings by construction, which is exactly what bounds the surviving copy-link (§3) — and for the dev/seed loop
 * that rides it. A **member** invite is refused at [canSend] before it is minted, so it never arrives here.
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
 * The invite is already minted by then — the caller sends **after** the mint transaction commits, so a hung SMTP
 * server never holds a pooled DB connection (cr-email-invite-send-hardening) — so the send cannot roll the mint
 * back. What the caller then does with `FAILED` differs per invite (ADR 0055): on the Server-Owner bootstrap the
 * raw token goes back to the owner as the copy-link (§3); a member invite has no such channel, so its handler
 * compensates the mint by soft-revoking it and answers `503 mail_send_failed` (§6). The raw token / accept link
 * is **never** logged, in any mode.
 */
@Alternative
@Priority(1)
@ApplicationScoped
class SmtpTenantInviteMailer(
    private val sender: TenantMailSender,
    private val renderer: InviteMailRenderer,
    private val fallback: LoggingTenantInviteMailer,
) : TenantInviteMailer {

    override fun canSend(tenantId: String): Boolean = sender.sendable(sender.settings(tenantId))

    override fun sendInvite(tenantId: String, domain: String, ownerEmail: String, token: String, expiresAt: Instant): DeliveryResult {
        val settings = sender.settings(tenantId)
        if (!sender.sendable(settings)) return fallback.sendInvite(tenantId, domain, ownerEmail, token, expiresAt)
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
