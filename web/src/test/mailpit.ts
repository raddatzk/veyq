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

type Envelope = { ID: string; Created: string; To?: { Address: string }[] }

/**
 * Every message currently addressed to `to`, **newest first**.
 *
 * The sort is not defensive padding: Mailpit is ONE mailbox for a whole run, and a re-issue sends a second mail
 * to the same address — so a first-match read hands back the superseded token and the suite then fails far
 * downstream, or worse, passes. `v1.20.0` does return newest-first already (measured), but that is an
 * undocumented property of the image, and one line here means nobody has to re-check it on a version bump.
 *
 * `limit` is raised off Mailpit's default of 50 so a long run cannot silently truncate a recipient out of view.
 */
async function inbox(to: string): Promise<Envelope[]> {
  const list = await fetch(`${MAILPIT}/api/v1/messages?limit=200`).then((r) => r.json())
  return ((list.messages ?? []) as Envelope[])
    .filter((m) => m.To?.some((t) => t.Address.toLowerCase() === to.toLowerCase()))
    .sort((a, b) => Date.parse(b.Created) - Date.parse(a.Created))
}

/** Poll until a message to `to` exists, and return the newest one's plain-text body. */
export async function waitForMail(to: string, timeoutMs = 20_000): Promise<string> {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    const [newest] = await inbox(to)
    if (newest) {
      const full = await fetch(`${MAILPIT}/api/v1/message/${newest.ID}`).then((r) => r.json())
      return String(full.Text ?? '')
    }
    await new Promise((r) => setTimeout(r, 500))
  }
  throw new Error(`no mail for ${to} within ${timeoutMs}ms (mailbox ${MAILPIT})`)
}

/**
 * Delete exactly the messages addressed to `to`, so the next `waitForMail` cannot resolve to an older one.
 *
 * **Recipient-scoped, never global.** A developer's `veyq-mailpit` is `restart: unless-stopped` and holds their
 * own manual smoke mails; wiping it from a test run would be destructive on a real machine, not hypothetically.
 * E2E recipients are stamped per run (`VEYQ_STAMP`), so one recipient is both safe and sufficient.
 */
export async function clearMailbox(to: string): Promise<void> {
  const ids = (await inbox(to)).map((m) => m.ID)
  if (ids.length === 0) return
  await fetch(`${MAILPIT}/api/v1/messages`, {
    method: 'DELETE',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ IDs: ids }),
  })
}
