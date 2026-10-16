// Session refresh (ADR 0004) and the retry-on-401 every authed call runs through (inbox #449).
//
// An access token lives 15 minutes. This surface used to rotate it only at boot, so a console left open past that
// answered the owner's next click with a `401` — and showed nothing. Now every authed client carries
// `refreshOnUnauthorized` (`api/client.ts` puts it on each factory, `api/client.sweep.test.ts` holds that), so an
// expired token is rotated once and the call sent again, and no panel can forget it. A rotation the server refuses
// leaves the `401` standing, and the panel's `handle401` ends the session in words (`app/sessionEnd.ts`).
//
// Its own module, and with its own bare `TokenApi` rather than `api/client`'s: the rotation must not carry the
// middleware (a refresh that 401s would wait on its own in-flight rotation, forever), and `api/client` imports
// this file for the middleware, so the reverse import would close a cycle.
import { Configuration, TokenApi, type Middleware } from '../api/generated/auth'
import { clearTokens, session, setTokens } from './session'

/**
 * The rotation this tab already has outstanding, or null. Calls that 401 together — the console's first load
 * fans out — join it instead of each presenting the same refresh token. Cleared the moment it settles: a settled
 * promise kept around would hand back a token pair one rotation out of date. (`web/src/app/sessionRefresh.ts`
 * holds the same idiom and its reasoning; this is admin-web's own copy, ADR 0065.)
 */
let inFlight: Promise<void> | null = null

/** Rotate the session tokens. On any failure the tokens are dropped, so the session is cleanly signed out instead
 *  of looping 401 → refresh → throw on every later call; the failure is rethrown. */
export async function refreshSession(): Promise<void> {
  if (inFlight) return await inFlight
  inFlight = rotate().finally(() => {
    inFlight = null
  })
  return await inFlight
}

async function rotate(): Promise<void> {
  try {
    // refreshToken null → the httpOnly cookie carries it (cr-0055): an empty body, and the browser attaches it.
    const pair = await new TokenApi(new Configuration({ basePath: session.apiBase })).refreshSession({
      refreshRequest: { refreshToken: session.refreshToken ?? undefined },
    })
    setTokens(pair.accessToken, pair.refreshToken ?? null)
  } catch (e) {
    clearTokens()
    throw e
  }
}

const bearerOf = (init: RequestInit): string | null =>
  new Headers(init.headers).get('Authorization')?.replace(/^Bearer /, '') || null

/**
 * On a `401` to a call that presented a token: rotate once (or reuse the rotation another call already made) and
 * send the call again with the new token. Safe to resend, mutations included: a `401` is answered before any
 * handler runs, so the first attempt changed nothing.
 *
 * Returning nothing leaves the original `401` for the caller — when the call presented no token (the sign-in
 * itself, where a 401 means a wrong password) or the rotation was refused. The retry goes out on the plain
 * `fetch`, not back through the middleware chain, so a second `401` is final rather than another rotation.
 */
export const refreshOnUnauthorized: Middleware = {
  async post({ url, init, response }) {
    if (response.status !== 401) return
    const sent = bearerOf(init)
    if (sent === null) return
    if (session.accessToken === sent) {
      try {
        await refreshSession()
      } catch {
        return
      }
    }
    if (session.accessToken === null) return
    const headers = new Headers(init.headers)
    headers.set('Authorization', `Bearer ${session.accessToken}`)
    return await fetch(url, { ...init, headers })
  },
}
