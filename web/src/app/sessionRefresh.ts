// Session refresh (uc.account.login, ADR 0004): rotate the opaque session tokens, and the retry-on-401 wrapper
// every authed call runs through. App infrastructure rather than part of the login feature, so a feature making
// an authed call does not import `login`. Not in `session.ts`: the rotation calls `tokenApi()`, and
// `api/client` imports `session` for the bearer token — putting it there would close `session → client → session`.
import { tokenApi } from '../api/client'
import { clearTokens, session, setTokens } from './session'
import { endSession } from './sessionEnd'
import { isAuthoritativeRejection, isUnauthorized } from '../shared/httpError'
import { handleTenantSuspended } from './tenantSuspension'

/**
 * The refresh this tab already has outstanding, or null. Callers that arrive while one is in flight join it
 * instead of starting a second.
 *
 * Nothing here is a cache: it is cleared the moment the rotation settles, so a later refresh asks the server
 * again. A settled promise kept around would hand back a token pair that is one rotation out of date.
 */
let inFlight: Promise<void> | null = null

/**
 * Rotate the session tokens (silent refresh). Throws if the refresh fails. **Only an authoritative rejection (a
 * 4xx) ends the session** (uc.account.sign-out E1, ADR 0070 § Decision 3): it drops the dead tokens, so no later
 * call loops 401→refresh→throw, and runs the ending in a tab that holds a session — the family was revoked from
 * another device, or expired, and the plaintext this page decrypted must not outlive it. At boot the tab holds no
 * session yet, so a rejection there is just *not signed in* (E2). A network failure or a 5xx is no answer about
 * the session: the tokens stay, and the next call tries again.
 *
 * **Callers that arrive together share one request.** A page load fires many authed calls that do not await
 * each other, and they 401 as a group; each one reaching for its own refresh would present the *same* token
 * concurrently. The server tolerates that (ADR 0004 § the one-generation grace) rather than reading it as a
 * clone — but every extra caller still costs a wasted rotation, and the tolerance is not a licence to lean on
 * it. This is the same idiom `resolvePlaceName` uses for identical coordinates.
 *
 * Per **tab**, deliberately: a second tab has its own module instance and its own in-flight promise. Sharing
 * across tabs would need a lock primitive this client does not have, and with the grace rule the cost of not
 * sharing is a redundant rotation rather than a lost session.
 */
export async function refreshSession(): Promise<void> {
  if (inFlight) return await inFlight
  inFlight = rotateSession().finally(() => {
    inFlight = null
  })
  return await inFlight
}

async function rotateSession(): Promise<void> {
  try {
    // refreshToken null → the httpOnly cookie carries it (remember me): send an empty body and the browser
    // attaches the cookie. Otherwise send the in-memory body token. A boot with neither yields a 400/401 → the
    // catch leaves a clean logged-out state. The new token comes back in the body (in-memory path) or a fresh
    // cookie (remember path, body token absent → keep null).
    const pair = await tokenApi().refreshSession({
      refreshRequest: { refreshToken: session.refreshToken ?? undefined },
    })
    setTokens(pair.accessToken, pair.refreshToken ?? null)
  } catch (e) {
    if (isAuthoritativeRejection(e)) {
      // `endSession` decides whether this tab holds a session before it awaits anything, so the tokens can go
      // right after; it is awaited so no caller acts on the failure while the document load is under way.
      const ended = endSession()
      clearTokens()
      await ended
    }
    throw e
  }
}

/**
 * Run an authed call, transparently refreshing once on a 401 and retrying — the retry-on-401→refresh wiring
 * authed resource calls (e.g. unlock's key-material fetch) use. The 401 detection duck-types the shared
 * response shape ({@link isUnauthorized}) so it fires for every generated client package, not just the auth one.
 */
export async function withRefresh<T>(call: () => Promise<T>): Promise<T> {
  try {
    return await call()
  } catch (e) {
    if (isUnauthorized(e)) {
      // Refresh via the in-memory token OR the httpOnly cookie (we can't see the cookie from JS, so always
      // attempt) and retry once; refreshSession throws (→ propagates as logged-out) when there is no session.
      await refreshSession()
      return await call()
    }
    // A suspended tenant answers every call `403 tenant_suspended` (uc.spaces.scope-access A7): let the app react
    // wherever it arrives mid-session, not only on entering a space. Not awaited — the caller still gets `e`.
    void handleTenantSuspended(e)
    throw e
  }
}
