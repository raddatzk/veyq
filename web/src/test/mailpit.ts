// The throwaway mailbox the live e2e suites (`VEYQ_E2E=1`) read, and the ONE place that knows its address.
//
// It is shared rather than file-local because mail stopped being one suite's concern: a member invite's token is
// minted for the invited person and travels by mail only (ADR 0055 §2), so `e2eBootstrap.onboardMember` reads a
// mailbox for the same reason `recovery.e2e` does. Both ports live here — the API we poll and the SMTP port a
// tenant is pointed at — because "where the mailbox is" is one fact, and while it was two it had already drifted
// (the compose comment documents host `localhost`, the recovery suite configured `veyq.localhost`).
//
// `scripts/run_web_e2e.py` starts Mailpit (`--profile mail`) and exports `VEYQ_MAILPIT`; the default below is for
// a hand run against a locally-composed stack.
export const MAILPIT = process.env.VEYQ_MAILPIT ?? 'http://localhost:18025'

/** Mailpit's SMTP port as published on the host — `infra/docker-compose.yml` maps `11025 -> 1025`. */
export const MAILPIT_SMTP_PORT = 11025

/** Whether a Mailpit answers at [MAILPIT] at all — `bootstrap:user` points a tenant at it only when one does. */
export async function mailpitReachable(): Promise<boolean> {
  return fetch(`${MAILPIT}/api/v1/info`).then((r) => r.ok).catch(() => false)
}

type Envelope ={ ID: string; Created: string; To?: { Address: string }[] }

/**
 * Every message currently addressed to `to`, **newest first**.
 *
 * The sort is not defensive padding: Mailpit is ONE mailbox for a whole run, and a re-issue sends a second mail
 * to the same address — so a first-match read hands back the superseded token and the suite then fails far
 * downstream, or worse, passes. `v1.20.0` does return newest-first already (measured), but that is an
 * undocumented property of the image, and one line here means nobody has to re-check it on a version bump.
 *
 * `limit` is raised off Mailpit's default of 50 so a long run cannot silently truncate a recipient out of view.
 * Nothing deletes mail any more ([mailCursor]), so the mailbox fills up to Mailpit's own cap; the newest 200 still
 * hold every mail a suite is waiting for, because that mail is the newest one.
 */
async function inbox(to: string): Promise<Envelope[]> {
  const list = await fetch(`${MAILPIT}/api/v1/messages?limit=200`).then((r) => r.json())
  return ((list.messages ?? []) as Envelope[])
    .filter((m) => m.To?.some((t) => t.Address.toLowerCase() === to.toLowerCase()))
    .sort((a, b) => Date.parse(b.Created) - Date.parse(a.Created))
}

/**
 * Where the mailbox stands for `to` BEFORE an action that sends mail: every message already addressed there.
 *
 * Taken first so the next [waitForMail] cannot resolve to an older message — a re-issue sends a second mail to
 * the same address, and an earlier hand run (which shares the default `VEYQ_STAMP`, hence the address) leaves one
 * behind. A cursor rather than a delete: `scripts/run_web_e2e.py` sweeps EVERY mail the run sent for leaked
 * plaintext, so a suite that deleted its mail would delete the evidence — nothing in a run deletes mail.
 */
export async function mailCursor(to: string): Promise<MailCursor> {
  return { seen: new Set((await inbox(to)).map((m) => m.ID)) }
}

export type MailCursor = { readonly seen: ReadonlySet<string> }

/** Poll until a message to `to` arrives that the cursor had not seen, and return the newest one's plain-text body. */
export async function waitForMail(to: string, { after }: { after: MailCursor }, timeoutMs = 20_000): Promise<string> {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    const [newest] = (await inbox(to)).filter((m) => !after.seen.has(m.ID))
    if (newest) {
      const full = await fetch(`${MAILPIT}/api/v1/message/${newest.ID}`).then((r) => r.json())
      return String(full.Text ?? '')
    }
    await new Promise((r) => setTimeout(r, 500))
  }
  throw new Error(`no new mail for ${to} within ${timeoutMs}ms (mailbox ${MAILPIT})`)
}
