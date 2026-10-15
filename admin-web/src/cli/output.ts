// How the CLI speaks: one JSON document on stdout for a success, one line on stderr for a failure. Both halves
// exist so a script can consume the first and a human can read the second — the CLI's own code is argument
// parsing and output formatting, and this is the formatting half (cr-admin-cli). The one exception is a command
// whose output is the served words in the operator's language (`capabilities`): it prints text through its own
// `Command.render`, because those words are for a person and a script reads nothing from them.
import { decodeHttpError } from '../shared/httpError'

/** A failure the CLI itself diagnosed (bad usage, missing credential) — already phrased for the operator. */
export class CliError extends Error {}

/**
 * One line naming a failure, never the server's response body.
 *
 * A failed Admin API call is reported as its status plus the server's machine-readable `code` and nothing
 * else — the rule `shared/httpError.ts` states for the whole surface. That is also what keeps the sign-in
 * failure non-enumerable: a wrong password and an unknown account are the same `401 authentication_failed`
 * here exactly as they are in the browser, because the backend already made them the same answer.
 */
export async function describeFailure(e: unknown): Promise<string> {
  if (e instanceof CliError) return e.message
  const { status, code } = await decodeHttpError(e)
  if (status === 0) return 'the backend could not be reached (check BACKEND_URL)'
  return `request failed: HTTP ${status}${code ? ` ${code}` : ''}`
}

/**
 * The success document. A 204 operation (change-domain, revoke, suspend, reactivate) resolves to `undefined`,
 * which `JSON.stringify` renders as no output at all — a silent success is indistinguishable from a silent
 * failure to whatever reads this, so it gets an explicit `{"ok": true}` instead.
 */
export function renderResult(value: unknown): string {
  return JSON.stringify(value === undefined ? { ok: true } : value, null, 2)
}
