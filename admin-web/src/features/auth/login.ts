// Server-Owner sign-in for `admin-web` — operator-blind challenge-response on the Model B data layer, the
// same endpoints and the same derivation `web/` uses (ADR 0003; ADR 0065: "the existing Server-Owner session,
// unchanged"). The password never leaves the device: the client derives the Ed25519 auth keypair from
// @veyq/crypto — the shared package, so there is one derivation, not two that agree today.
//
// Deliberately NARROWER than web/'s login, and the omissions are the design: no passkey, no "remember me"
// device-key wrap, no session vault, no password recovery. This surface holds no content key and no vault to
// unlock — a session is all it ever needs — and every one of those features is another place that would touch
// key material. A 401 lands on /login (`shared/handle401.ts`) rather than triggering a silent refresh from a
// view, and the ONLY refresh this surface performs is the one at boot — there is no retry-on-401 wrapper here
// and no view that could produce two refreshes at once. `web/` needs both because its views fan out authed
// calls that 401 as a group; this one has a single authed screen. If that ever changes, copy `web/`'s
// `refreshSession` WITH its shared in-flight promise, rather than reviving a bare wrapper: the two behave
// differently under concurrency and only one of them is safe to fan out.
import * as v1 from '@veyq/crypto/v1'
import { deriveAuthKeypairAsync } from '@veyq/crypto/argonWorker'
import { b64std, fromB64std } from '@veyq/crypto/base64'
import { loginApi, sessionApi, tokenApi } from '../../api/client'
import { clearTokens, session, setTokens } from '../../app/session'
import { resetServerRole } from '../../app/serverRole'

/**
 * Log in with email + password. Requests a challenge, derives the auth keypair with the instance salt the
 * challenge carries, signs the nonce, and stores the issued session. Throws on failure — a wrong password or
 * unknown account both surface as the same generic 401 (non-enumerable).
 *
 * The refresh token rides an httpOnly cookie (cr-0055), which is exactly why this surface reverse-proxies
 * `/api/*` from its own origin instead of calling the app's domain cross-origin.
 */
export async function login(email: string, password: string): Promise<void> {
  const challenge = await loginApi().requestLoginChallenge({ loginChallengeRequest: { email } })
  const authSalt = v1.deriveAuthSalt(fromB64std(challenge.instanceSalt), email)
  const auth = await deriveAuthKeypairAsync(password, authSalt)
  const signature = v1.authSign(auth.privateKey, fromB64std(challenge.nonce))
  const result = await loginApi().verifyLogin({
    loginVerifyRequest: { email, signature: b64std(signature) },
  })
  setTokens(result.accessToken, result.refreshToken ?? null, result.family.id)
  resetServerRole()
}

/** Rotate the session tokens (silent refresh). On rejection the dead tokens are dropped, so the session lands
 *  in a clean signed-out state instead of looping 401 → refresh → throw on every subsequent authed call. */
export async function refreshSession(): Promise<void> {
  try {
    const pair = await tokenApi().refreshSession({
      refreshRequest: { refreshToken: session.refreshToken ?? undefined },
    })
    setTokens(pair.accessToken, pair.refreshToken ?? null)
  } catch (e) {
    clearTokens()
    throw e
  }
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
