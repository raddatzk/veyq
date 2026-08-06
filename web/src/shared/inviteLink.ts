// Invite-link handoff (cr-0080). The mailer is optional (a dev/log stub in this build), so an owner needs a way
// to hand off an invite themselves: build the acceptance link — the bootstrap secret rides the URL **fragment**
// (never sent to the server; matched by hash) — and copy it to the clipboard. `domain` targets a tenant on a
// different host than the current one (the Server Owner creating a tenant); omit it for a same-host invite (a
// tenant owner inviting into the tenant they're already in).
export function buildInviteLink(secret: string, domain?: string | null): string {
  const base = domain ? `${window.location.protocol}//${domain}` : window.location.origin
  return `${base}/tenant/accept#${secret}`
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
