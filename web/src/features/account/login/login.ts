// uc.account.login (web) — operator-blind challenge-response login on the Model B data layer. The password
// never leaves the device: the client derives the Ed25519 auth keypair (ADR 0003), signs the server nonce,
// and stores only the opaque session tokens it gets back. Auth ≠ unlock — this gates a session, not content.
import * as v1 from '@veyq/crypto/v1'
import { deriveAuthKeypairAsync } from '@veyq/crypto/argonWorker'
import { b64std, fromB64std } from '@veyq/crypto/base64'
import { loginApi, sessionApi, tokenApi } from '../../../api/client'
import { clearTokens, session, setTokens } from '../../../app/session'
import { clearVault } from '../../../app/vault'
import { setAccount, clearAccount } from '../../../app/account'
import { clearDeviceKey } from '../rememberMe/deviceKeyWrap'
import { clearSessionVault } from '../rememberMe/sessionVault'
import { LoginVerifyRequestSessionScopeEnum } from '../../../api/generated/auth'
import { isUnauthorized } from '../../../shared/httpError'

/**
 * Log in with email + password. Requests a challenge, derives the auth keypair with the instance salt the
 * challenge carries, signs the nonce, and stores the issued session. Throws on failure — a wrong password
 * or unknown account both surface as the same generic 401 (non-enumerable).
 *
 * The web is always cookie-based (cr-0055): `scope` picks the refresh-cookie lifetime — `SESSION` (the
 * default — survives reloads, dropped on browser close) or `PERSISTENT` ("remember me" — survives a restart).
 * Either way the body carries no refresh token, so none is kept in memory; refresh rides the cookie.
 */
export async function login(
  email: string,
  password: string,
  scope: LoginVerifyRequestSessionScopeEnum = LoginVerifyRequestSessionScopeEnum.Session,
): Promise<void> {
  const challenge = await loginApi().requestLoginChallenge({ loginChallengeRequest: { email } })
  const authSalt = v1.deriveAuthSalt(fromB64std(challenge.instanceSalt), email)
  const auth = await deriveAuthKeypairAsync(password, authSalt)
  const signature = v1.authSign(auth.privateKey, fromB64std(challenge.nonce))
  const result = await loginApi().verifyLogin({
    loginVerifyRequest: { email, signature: b64std(signature), sessionScope: scope },
  })
  setTokens(result.accessToken, result.refreshToken ?? null, result.family.id)
  setAccount(email)
}

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

/** Revoke the current session family, drop local tokens, and re-lock the vault. Idempotent. Centralising
 *  the vault clear here means every sign-out path (this view, a future silent logout on refresh failure, a
 *  route guard) drops the unlocked keys — auth and unlock can never drift out of sync. */
export async function logout(): Promise<void> {
  // Drop the convenience vaults FIRST, while the token is still live, so an explicit sign-out can't leave the
  // device opening straight in: the session vault (cr-0055, sessionStorage) and the server DEVICE_KEY wrap +
  // local device key (cr-0054). The DEVICE trust wrap (if any) is intentionally left — it survives logout
  // (next login stays password-only, never the Secret Key).
  await clearSessionVault().catch(() => {})
  await clearDeviceKey().catch(() => {})
  try {
    await sessionApi().logout()
  } finally {
    clearTokens()
    clearVault()
    clearAccount()
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
