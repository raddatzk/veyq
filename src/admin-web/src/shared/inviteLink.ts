// Invite-link handoff (cr-0080). The Server Owner's invite to a tenant's first owner is the one surviving
// copy-link (ADR 0055 §3): a PENDING tenant has no mail settings yet, so the owner hands the link off themselves.
// The bootstrap secret rides the URL **fragment** (never sent to the server; matched by hash).
//
// Always `https://`, never this page's own scheme: admin-web listens on http://localhost by default, and a link
// that inherited that would make the secret's first hop plaintext. The mailer builds its links the same way
// (`SmtpTenantInviteMailer`), so a dev instance serving a tenant over plain http rewrites the scheme by hand for
// both — the same trade, made once.
export function buildInviteLink(secret: string, domain: string): string {
  return `https://${domain}/tenant/accept#${secret}`
}

/** Copy text to the clipboard; resolves true on success, false if the browser blocked it. */
export async function copyText(text: string): Promise<boolean> {
  try {
    await navigator.clipboard.writeText(text)
    return true
  } catch {
    return false
  }
}
