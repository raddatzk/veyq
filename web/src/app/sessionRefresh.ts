// Session refresh (uc.account.login, ADR 0004): rotate the opaque session tokens, and the retry-on-401 wrapper
// every authed call runs through. App infrastructure rather than part of the login feature, so a feature making
// an authed call does not import `login`. Not in `session.ts`: the rotation calls `tokenApi()`, and
// `api/client` imports `session` for the bearer token — putting it there would close `session → client → session`.
import { tokenApi } from '../api/client'
import { clearTokens, session, setTokens } from './session'
import { isUnauthorized } from '../shared/httpError'

/**
 * The refresh this tab already has outstanding, or null. Callers that arrive while one is in flight join it
 * instead of starting a second.
 *
 * Nothing here is a cache: it is cleared the moment the rotation settles, so a later refresh asks the server
 * again. A settled promise kept around would hand back a token pair that is one rotation out of date.
 */
let inFlight: Promise<void> | null = null

/**
 * Rotate the session tokens (silent refresh). Throws if the refresh is rejected — and on rejection drops the
 * dead tokens so the session lands in a clean logged-out state instead of looping 401→refresh→throw on every
 * subsequent authed call.
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
    // catch drops to a clean logged-out state. The new token comes back in the body (in-memory path) or a fresh
    // cookie (remember path, body token absent → keep null).
    const pair = await tokenApi().refreshSession({
      refreshRequest: { refreshToken: session.refreshToken ?? undefined },
    })
    setTokens(pair.accessToken, pair.refreshToken ?? null)
  } catch (e) {
    clearTokens()
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
    throw e
  }
}
