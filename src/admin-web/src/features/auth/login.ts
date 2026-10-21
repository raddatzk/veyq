// Server-Owner sign-in for `admin-web` — operator-blind challenge-response on the Model B data layer, the
// same endpoints and the same derivation `src/web/` uses (ADR 0003; ADR 0065: "the existing Server-Owner session,
// unchanged"). The password never leaves the device: the client derives the Ed25519 auth keypair from
// @veyq/crypto — the shared package, so there is one derivation, not two that agree today.
//
// Deliberately NARROWER than src/web/'s login, and the omissions are the design: no passkey, no "remember me"
// device-key wrap, no session vault, no password recovery. This surface holds no content key and no vault to
// unlock — a session is all it ever needs — and every one of those features is another place that would touch
// key material. The session's own upkeep is not here: the rotation and the retry-on-401 every authed call runs
// through live in `app/sessionRefresh.ts` (inbox #449), and the end of a session the server refused in
// `app/sessionEnd.ts`.
import * as v1 from '@veyq/crypto/v1'
import { deriveAuthKeypairAsync } from '@veyq/crypto/argonWorker'
import { b64std, fromB64std } from '@veyq/crypto/base64'
import { loginApi, sessionApi } from '../../api/client'
import { LoginVerifyRequestSessionScopeEnum } from '../../api/generated/auth'
import { clearTokens, setTokens } from '../../app/session'
import { resetServerRole } from '../../app/serverRole'

/**
 * Where the refresh token lives, which only the caller knows — so it is a required argument, never a default:
 * leaving the scope out is what made every console reload a re-sign-in (the server then answers in the body).
 *   - `cookie` (the console): `sessionScope: SESSION`, so the refresh token rides an httpOnly session cookie — it
 *     survives a reload (`app/boot.ts` revives the session from it) and is dropped when the browser closes. Never
 *     `PERSISTENT`: this surface offers no "remember me". This is exactly why it reverse-proxies `/api/*` from its
 *     own origin instead of calling the app's domain cross-origin (cr-0055).
 *   - `body` (the CLI): no scope, so the refresh token comes back in the body and stays in memory. Node keeps no
 *     cookie jar, so a cookie would leave the CLI no way to rotate a run that outlives its access token.
 */
export type RefreshTransport = 'cookie' | 'body'

/**
 * Log in with email + password. Requests a challenge, derives the auth keypair with the instance salt the
 * challenge carries, signs the nonce, and stores the issued session. Throws on failure — a wrong password or
 * unknown account both surface as the same generic 401 (non-enumerable).
 */
export async function login(email: string, password: string, transport: RefreshTransport): Promise<void> {
  const challenge = await loginApi().requestLoginChallenge({ loginChallengeRequest: { email } })
  const authSalt = v1.deriveAuthSalt(fromB64std(challenge.instanceSalt), email)
  const auth = await deriveAuthKeypairAsync(password, authSalt)
  const signature = v1.authSign(auth.privateKey, fromB64std(challenge.nonce))
  const sessionScope = transport === 'cookie' ? LoginVerifyRequestSessionScopeEnum.Session : undefined
  const result = await loginApi().verifyLogin({
    loginVerifyRequest: { email, nonce: challenge.nonce, signature: b64std(signature), sessionScope },
  })
  setTokens(result.accessToken, result.refreshToken ?? null, result.family.id)
  resetServerRole()
}

/** Revoke the current session family and drop the local tokens. Idempotent. Also resets the Server-Owner
 *  probe, so the next account re-probes instead of inheriting this one's verdict. */
export async function logout(): Promise<void> {
  try {
    await sessionApi().logout()
  } finally {
    clearTokens()
    resetServerRole()
  }
}
