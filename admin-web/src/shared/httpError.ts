/**
 * One decoder for failures thrown by the generated OpenAPI clients.
 *
 * Each generated package (`generated/auth`, `generated/account`, `generated/scopes`, …) ships its OWN
 * `ResponseError` class, so `err instanceof ResponseError` from one package is `false` for an error thrown by
 * another (see the note on `withRefresh` in `features/account/login/login.ts`). Every decoder in the app worked
 * around this by duck-typing the shared shape (`.response: Response`) by hand — the same ~12 lines copied ~8×.
 * These helpers are that shape, once.
 *
 * Client error text surfaces in the UI and diagnostics, so — as the retired `apiError.ts` warned — a server
 * response body is only ever read for its machine-readable `code`; it is never surfaced verbatim.
 */

/**
 * Named HTTP status codes the client actually branches on — so a status check reads by name instead of a bare
 * magic number (audit #78 P4b). Only the codes some call site checks live here; add one when a new check needs
 * it. Response *construction* (e.g. the media service worker returning a `404`) keeps its own literal — this is
 * for the branching/decoding side, not for building responses.
 */
export const HttpStatus = {
  notModified: 304,
  /** Distinguished from a refusal only where the remedy differs — e.g. `POST /api/token/adopt` refusing a
   *  browser that already carries a refresh cookie (ADR 0004 § Transport), which is not "start again". */
  badRequest: 400,
  unauthorized: 401,
  forbidden: 403,
  notFound: 404,
  conflict: 409,
  gone: 410,
  unprocessable: 422,
  tooManyRequests: 429,
  /** The tenant cannot send mail, so a mail-only action is refused (ADR 0055). */
  serviceUnavailable: 503,
} as const

/** The `Response` carried by a thrown generated-client error, or `null` for a non-HTTP failure (network, etc.). */
function responseOf(e: unknown): Response | null {
  const res = (e as { response?: Response } | null)?.response
  return res && typeof res.status === 'number' ? res : null
}

/** HTTP status of a failed generated-client call, or `0` for a non-HTTP failure (network, abort, …). */
export function httpStatus(e: unknown): number {
  return responseOf(e)?.status ?? 0
}

/** Whether a failure is exactly this HTTP status. */
export function isHttpStatus(e: unknown, status: number): boolean {
  return httpStatus(e) === status
}

/**
 * Whether a failure is a 401 (session lapsed / re-auth needed). The detection half of the session end the
 * component layer performs (`shared/handle401.ts`). A 401 that reaches a caller has already survived the one
 * rotation every authed call gets (`app/sessionRefresh.ts`, a fetch middleware rather than `web/`'s `withRefresh`).
 */
export function isUnauthorized(e: unknown): boolean {
  return httpStatus(e) === HttpStatus.unauthorized
}

/**
 * Decode a failed call into `{ status, code }` — `status` is the HTTP status (`0` for a non-HTTP failure),
 * `code` is the server's machine-readable error `code` from a JSON body (`null` when absent or non-JSON). The
 * body is cloned and read solely for that `code`; it is never returned verbatim.
 */
export async function decodeHttpError(e: unknown): Promise<{ status: number; code: string | null }> {
  const res = responseOf(e)
  if (!res) return { status: 0, code: null }
  let code: string | null = null
  try {
    code = ((await res.clone().json()) as { code?: string })?.code ?? null
  } catch {
    // no / non-JSON body — leave code null
  }
  return { status: res.status, code }
}

/** Whether a failure is a specific status carrying a specific server `code` (e.g. `409 stale_epoch`). */
export async function isCodedError(e: unknown, status: number, code: string): Promise<boolean> {
  if (httpStatus(e) !== status) return false
  return (await decodeHttpError(e)).code === code
}
