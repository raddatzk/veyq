import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('../../../api/client', () => ({ loginApi: vi.fn(), tokenApi: vi.fn(), sessionApi: vi.fn() }))

import { login, refreshSession, logout } from './login'
import { loginApi, tokenApi, sessionApi } from '../../../api/client'
import { session } from '../../../app/session'
import { setUnlocked, isUnlocked } from '../../../app/vault'
import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'
import { PRODUCTION_ARGON_TIMEOUT_MS } from '@veyq/crypto/testArgon'

const INSTANCE_SALT = new Uint8Array(16).fill(3)
const NONCE = new Uint8Array(32).fill(9)

beforeEach(() => {
  session.accessToken = null
  session.refreshToken = null
  session.familyId = null
})

describe('login (uc.account.login)', () => {
  it('signs the nonce so the server can verify, and stores the issued session', async () => {
    let captured = ''
    vi.mocked(loginApi).mockReturnValue({
      requestLoginChallenge: () =>
        Promise.resolve({ nonce: b64std(NONCE), instanceSalt: b64std(INSTANCE_SALT) }),
      verifyLogin: (req: { loginVerifyRequest: { signature: string } }) => {
        captured = req.loginVerifyRequest.signature
        return Promise.resolve({ accessToken: 'access', refreshToken: 'refresh', family: { id: 'fam-1' } })
      },
    } as unknown as ReturnType<typeof loginApi>)

    await login('User@Example.com', 'correct horse battery')

    // the issued session is stored
    expect(session.accessToken).toBe('access')
    expect(session.refreshToken).toBe('refresh')
    expect(session.familyId).toBe('fam-1')

    // the signature is a valid Ed25519 signature over the nonce for the auth key derived from this
    // password + the challenge's instance salt — exactly what the backend verifies (wire-correct, and the
    // same derivation setup used to register authPublicKey).
    const authSalt = v1.deriveAuthSalt(INSTANCE_SALT, 'User@Example.com')
    const pub = v1.deriveAuthKeypair('correct horse battery', authSalt).publicKey
    expect(v1.authVerify(pub, NONCE, fromB64std(captured))).toBe(true)
    // a wrong password derives a different key → the same signature would not verify (generic 401 server-side)
    const wrongPub = v1.deriveAuthKeypair('wrong password', authSalt).publicKey
    expect(v1.authVerify(wrongPub, NONCE, fromB64std(captured))).toBe(false)
    // Three production-params Argon2id derivations: `login` picks the params itself, so the two expected
    // keypairs must match it — the cost is inherent here, unlike the wraps whose params ride on the wire.
    // Only this test gets the raised ceiling; the three below stay on the 5s default. See packages/crypto/src/testArgon.ts.
  }, PRODUCTION_ARGON_TIMEOUT_MS)

  it('re-locks the vault on logout (every sign-out path drops the unlocked keys)', async () => {
    vi.mocked(sessionApi).mockReturnValue({
      logout: () => Promise.resolve(),
    } as unknown as ReturnType<typeof sessionApi>)
    setUnlocked(
      new Uint8Array(32).fill(7),
      { kemX: new Uint8Array(1), mlkemSeed: new Uint8Array(1), sign: new Uint8Array(1) },
      'cold',
    )
    expect(isUnlocked()).toBe(true)

    await logout()

    expect(isUnlocked()).toBe(false)
  })

  it('clears the dead tokens when a refresh is rejected (no wedged session)', async () => {
    session.accessToken = 'a1'
    session.refreshToken = 'r1'
    session.familyId = 'f1'
    vi.mocked(tokenApi).mockReturnValue({
      refreshSession: () => Promise.reject(new Error('refresh_rejected')),
    } as unknown as ReturnType<typeof tokenApi>)

    await expect(refreshSession()).rejects.toThrow()

    // tokens dropped → next authed call won't loop 401→refresh→throw
    expect(session.accessToken).toBeNull()
    expect(session.refreshToken).toBeNull()
  })

  it('rotates the tokens on refresh', async () => {
    session.accessToken = 'a1'
    session.refreshToken = 'r1'
    vi.mocked(tokenApi).mockReturnValue({
      refreshSession: () => Promise.resolve({ accessToken: 'a2', refreshToken: 'r2' }),
    } as unknown as ReturnType<typeof tokenApi>)

    await refreshSession()

    expect(session.accessToken).toBe('a2')
    expect(session.refreshToken).toBe('r2')
  })
})
