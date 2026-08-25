import { describe, it, expect, vi, beforeEach } from 'vitest'
import { PRODUCTION_ARGON_TIMEOUT_MS } from '../../../test/argon'
import * as v1 from '../../../shared/crypto/v1'
import { b64std, fromB64std } from '../../../shared/base64'

// The API, vault and account state are stubbed; the crypto is real (changePassword.ts hardcodes production
// ARGON, so a cheap-params trick would derive a DIFFERENT keypair than the code under test — there is no
// params argument to share a lighter cost through, unlike rotateKeys). See src/test/argon.ts § "production
// owns the derivation".
const requestLoginChallenge = vi.fn()
const changePasswordCall = vi.fn().mockResolvedValue(undefined)

vi.mock('../../../api/client', () => ({
  loginApi: () => ({ requestLoginChallenge }),
  accountPasswordApi: () => ({ changePassword: changePasswordCall }),
}))
vi.mock('../login/login', () => ({ withRefresh: (f: () => unknown) => f() }))

const EMAIL = 'change-pw@example.com'
const held: { mk: Uint8Array | null } = vi.hoisted(() => ({ mk: null }))
vi.mock('../../../app/vault', () => ({ masterKey: () => held.mk }))
vi.mock('../../../app/account', () => ({ account: { email: 'change-pw@example.com' } }))

import { changePassword, ChangePasswordRefused } from './changePassword'

const INSTANCE_SALT = new Uint8Array(16).fill(7)
const SECRET_KEY = v1.randomBytes(32)
const SECRET_KEY_B64 = b64std(SECRET_KEY)
const MK = v1.randomBytes(32)
const OLD_PASSWORD = 'the old password'
const NEW_PASSWORD = 'a brand new password'

describe('changePassword', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    held.mk = MK.slice()
    requestLoginChallenge.mockResolvedValue({ instanceSalt: b64std(INSTANCE_SALT), nonce: b64std(new Uint8Array(32)) })
  })

  it('the deliberate path signs the NEW auth key with the OLD keypair', async () => {
    await changePassword(NEW_PASSWORD, SECRET_KEY_B64, OLD_PASSWORD)

    const body = changePasswordCall.mock.calls[0][0].changePasswordRequest
    expect(body.attestationSignature).toBeTruthy()

    const authSalt = v1.deriveAuthSalt(INSTANCE_SALT, EMAIL)
    const oldAuth = v1.deriveAuthKeypair(OLD_PASSWORD, authSalt, v1.ARGON)
    const verified = v1.authVerify(oldAuth.publicKey, fromB64std(body.authPublicKey), fromB64std(body.attestationSignature))
    expect(verified).toBe(true)
  }, PRODUCTION_ARGON_TIMEOUT_MS)

  it('the forgotten path sends no attestation at all', async () => {
    await changePassword(NEW_PASSWORD, SECRET_KEY_B64)

    const body = changePasswordCall.mock.calls[0][0].changePasswordRequest
    expect(body.attestationSignature).toBeUndefined()
  }, PRODUCTION_ARGON_TIMEOUT_MS)

  it('the new floor opens the SAME Master Key under the NEW password only', async () => {
    await changePassword(NEW_PASSWORD, SECRET_KEY_B64, OLD_PASSWORD)
    const body = changePasswordCall.mock.calls[0][0].changePasswordRequest

    const { params, salt } = v1.decodeKdfParams(fromB64std(body.passwordWrap.kdfParams))
    const reopened = v1.symUnwrap(
      v1.kekPasswordSecret(SECRET_KEY, NEW_PASSWORD, salt, params),
      fromB64std(body.passwordWrap.wrappedKey),
      'master-key',
    )
    expect(b64std(reopened)).toBe(b64std(MK))

    expect(() =>
      v1.symUnwrap(
        v1.kekPasswordSecret(SECRET_KEY, OLD_PASSWORD, salt, params),
        fromB64std(body.passwordWrap.wrappedKey),
        'master-key',
      ),
    ).toThrow()
  }, PRODUCTION_ARGON_TIMEOUT_MS)

  it('never sends the password, Secret Key or Master Key onto the wire', async () => {
    await changePassword(NEW_PASSWORD, SECRET_KEY_B64, OLD_PASSWORD)
    const wire = JSON.stringify(changePasswordCall.mock.calls[0][0].changePasswordRequest)

    expect(wire).not.toContain(NEW_PASSWORD)
    expect(wire).not.toContain(OLD_PASSWORD)
    expect(wire).not.toContain(SECRET_KEY_B64)
    expect(wire).not.toContain(b64std(MK))
  }, PRODUCTION_ARGON_TIMEOUT_MS)

  it('refuses before any write when the vault is locked', async () => {
    held.mk = null
    await expect(changePassword(NEW_PASSWORD, SECRET_KEY_B64, OLD_PASSWORD)).rejects.toBeInstanceOf(ChangePasswordRefused)
    expect(changePasswordCall).not.toHaveBeenCalled()
    expect(requestLoginChallenge).not.toHaveBeenCalled()
  })
})
