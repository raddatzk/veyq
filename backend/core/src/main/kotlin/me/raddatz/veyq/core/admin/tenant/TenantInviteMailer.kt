package com.veyq.core.admin.tenant

import com.veyq.core.generated.model.DeliveryResult
import io.quarkus.runtime.LaunchMode
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import java.time.Instant

/**
 * Delivers a tenant-owner invite. A **port**: the structural slice (cr-0009) only needs the link minted and
 * handed off; real SMTP is an infra/runbook concern, deliberately out of scope. The invite link carries the
 * raw bootstrap secret in the URL **fragment** (never sent to the server until acceptance — ADR 0006) and
 * targets the tenant's own domain, where the invited owner accepts and cold-births the scope (Slice B).
 */
interface TenantInviteMailer {
    /**
     * Delivers the invite and reports **how it went** as a [DeliveryResult]: `SENT` (SMTP accepted it),
     * `FALLBACK_COPYLINK` (no sendable SMTP — the copy-link is the channel), or `FAILED` (SMTP configured but the
     * send errored). The result is **request-scoped only, never persisted** (operator-blind untouched — ADR 0049).
     *
     * **Which of the three a caller can actually observe depends on the invite (ADR 0055 §7).** The port keeps all
     * three because one impl still needs them; the endpoints do not:
     *  - **Member invite** (`POST /api/tenants/{tenantId}/member-invites` and `.../{inviteId}/reissue`): `SENT`
     *    only. [canSend] refuses before any mint, so `FALLBACK_COPYLINK` never arises here (`503
     *    mail_unavailable`, ADR 0055 §2), and a `FAILED` send compensates the mint and answers `503
     *    mail_send_failed` rather than a success body (§6). The response carries no `bootstrapSecret` — there is
     *    no copy-link to hand back.
     *  - **Server-Owner tenant-bootstrap invite** (`POST /api/tenants` plus the resend/reassign of that same
     *    invite): all three stay reachable. A `PENDING` tenant has no mail settings by construction, so this is
     *    the one surviving copy-link (§3), and the returned `bootstrapSecret` is the channel on
     *    `FALLBACK_COPYLINK` / `FAILED`.
     */
    fun sendInvite(tenantId: String, domain: String, ownerEmail: String, token: String, expiresAt: Instant): DeliveryResult

    /**
     * Whether this tenant can actually put mail on the wire — asked **before** anything is minted.
     *
     * A member invite travels by mail or not at all (ADR 0055), so the caller needs this answer up front rather
     * than discovering it from a `FALLBACK_COPYLINK` after the fact: minting an invitation that reaches nobody
     * leaves a live secret with no recipient. It sits on the port rather than on `TenantMailSender` so the
     * handler keeps depending on the port alone; `RecoveryMailer.canSend` is the same question for the other
     * mail kind.
     */
    fun canSend(tenantId: String): Boolean
}

/**
 * Dev/test impl: does not send mail. Replace with an SMTP-backed impl in infra.
 *
 * Two safeguards so this dev convenience never becomes a prod liability:
 *  - the raw bootstrap token (a redeemable secret that must live only in the URL fragment, ADR 0006) is
 *    **never logged**, in any launch mode (cr-invite-token-log-redaction): a production runtime logs a
 *    secret-free warning that nothing was delivered, and a dev/test runtime logs a secret-free info line —
 *    so the invite secret never lands in server logs regardless of `LaunchMode`. The token reaches those who
 *    need it via the API response, not the console;
 *  - the owner-supplied `domain` is validated as a bare hostname up front, so once a real mailer builds a
 *    link from it, it can't be tricked into a different scheme/host/path (phishing / link-injection).
 */
@ApplicationScoped
class LoggingTenantInviteMailer : TenantInviteMailer {
    private val log = Logger.getLogger(LoggingTenantInviteMailer::class.java)

    /** This bean *is* the no-SMTP path, so it can never send. Only the owner-bootstrap invite may still use it
     *  (ADR 0055 §3); every other caller must refuse before it gets here. */
    override fun canSend(tenantId: String): Boolean = false

    override fun sendInvite(tenantId: String, domain: String, ownerEmail: String, token: String, expiresAt: Instant): DeliveryResult {
        require(HOSTNAME.matches(domain)) { "invalid tenant domain" }
        if (LaunchMode.current() == LaunchMode.NORMAL) {
            // Production: never write the bootstrap secret to logs. A real SMTP-backed mailer must be wired.
            log.warnf(
                "No SMTP mailer configured: tenant invite for %s (expires %s) was NOT delivered.",
                ownerEmail, expiresAt,
            )
            // No mail sent — the copy-link is the channel (this bean is the fallback / SMTP-less path).
            return DeliveryResult.FALLBACK_COPYLINK
        }
        // The raw bootstrap token is a redeemable secret (cr-invite-token-log-redaction): never emit it — or
        // the accept link that embeds it — to a log, in any launch mode. Whoever needs it gets it from the API
        // response, never the console; TenantInviteMailerLogTest pins it.
        log.infof(
            "Tenant invite minted for %s (expires %s); secret delivered via the API response, not logged.",
            ownerEmail, expiresAt,
        )
        return DeliveryResult.FALLBACK_COPYLINK
    }

    private companion object {
        /** A bare DNS hostname (labels of letters/digits/hyphens, dot-separated) — no scheme, port, path or
         *  userinfo, so the interpolated link can only ever point at the host the Server-Owner registered. */
        val HOSTNAME = Regex("^(?=.{1,253}$)([A-Za-z0-9](-?[A-Za-z0-9])*)(\\.[A-Za-z0-9](-?[A-Za-z0-9])*)+$")
    }
}
