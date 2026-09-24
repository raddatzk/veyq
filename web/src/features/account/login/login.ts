// uc.account.login (web) — operator-blind challenge-response login on the Model B data layer. The password
// never leaves the device: the client derives the Ed25519 auth keypair (ADR 0003), signs the server nonce,
// and stores only the opaque session tokens it gets back. Auth ≠ unlock — this gates a session, not content.
import * as v1 from '@veyq/crypto/v1'
import { deriveAuthKeypairAsync } from '@veyq/crypto/argonWorker'
import { b64std, fromB64std } from '@veyq/crypto/base64'
import { loginApi, sessionApi } from '../../../api/client'
import { clearTokens, setTokens } from '../../../app/session'
import { clearVault } from '../../../app/vault'
import { setAccount, clearAccount } from '../../../app/account'
import { clearDeviceKey } from '../rememberMe/deviceKeyWrap'
import { clearSessionVault } from '../rememberMe/sessionVault'
import { LoginVerifyRequestSessionScopeEnum } from '../../../api/generated/auth'

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
