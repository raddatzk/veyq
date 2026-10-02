// The owner-bootstrap invite, read out of the backend's dev-invite file. `bootstrap-user.mts` is its one caller.
//
// The file is the logging mailer's `veyq.tenant.invite.dev-file` (JSON lines); `.run/Run Backend.run.xml` sets it
// through `VEYQ_TENANT_INVITE_DEV_FILE`. A backend started without it never writes the file, and the poll below
// throws after its timeout. Only the owner-bootstrap invite travels this way: a member invite is minted for the
// invited person and goes by mail only (ADR 0055 §2).
import { readFileSync, existsSync } from 'node:fs'

/**
 * Poll the dev-invite file (the mailer's `veyq.tenant.invite.dev-file`, JSON lines) for the latest bootstrap
 * secret for `email`. Deterministic — replaces the fragile console-log scrape (ANSI + flush race).
 */
export async function awaitInvite(file: string, email: string, timeoutMs = 15000): Promise<string> {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    if (existsSync(file)) {
      const lines = readFileSync(file, 'utf8').split('\n').filter((l) => l.trim())
      for (let i = lines.length - 1; i >= 0; i--) {
        try {
          const rec = JSON.parse(lines[i]) as { ownerEmail: string; token: string }
          if (rec.ownerEmail === email) return rec.token
        } catch { /* skip a partially-written line */ }
      }
    }
    await new Promise((r) => setTimeout(r, 200))
  }
  throw new Error(`no invite for ${email} in ${file} within ${timeoutMs}ms — is the backend writing veyq.tenant.invite.dev-file?`)
}
