import { describe, it, expect, vi, beforeAll, beforeEach } from 'vitest'
import { PRODUCTION_ARGON_TIMEOUT_MS } from '@veyq/crypto/testArgon'
import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'

// The API, vault and account state are stubbed; the crypto is real (changePassword.ts hardcodes production
// ARGON, so a cheap-params trick would derive a DIFFERENT keypair than the code under test — there is no
// params argument to share a lighter cost through, unlike rotateKeys). See packages/crypto/src/testArgon.ts § "production
// owns the derivation".
const requestLoginChallenge = vi.fn()
const changePasswordCall = vi.fn().mockResolvedValue(undefined)
const getAccountKeyMaterial = vi.fn()

vi.mock('../../../api/client', () => ({
  loginApi: () => ({ requestLoginChallenge }),
  accountPasswordApi: () => ({ changePassword: changePasswordCall }),
  accountApi: () => ({ getAccountKeyMaterial }),
}))
vi.mock('../../../app/sessionRefresh', () => ({ withRefresh: (f: () => unknown) => f() }))

const EMAIL = 'change-pw@example.com'
const held: { mk: Uint8Array | null } = vi.hoisted(() => ({ mk: null }))
const IDENTITY_PRIV = vi.hoisted(() => ({ kemX: new Uint8Array(1), mlkemSeed: new Uint8Array(1), sign: new Uint8Array(1) }))
const setUnlocked = vi.hoisted(() => vi.fn())
vi.mock('../../../app/vault', () => ({
  masterKey: () => held.mk,
  identityPrivate: () => IDENTITY_PRIV,
  setUnlocked,
}))
vi.mock('../../../app/account', () => ({ account: { email: 'change-pw@example.com' } }))

import { changePassword, ChangePasswordRefused } from './changePassword'

const INSTANCE_SALT = new Uint8Array(16).fill(7)
const SECRET_KEY = v1.randomBytes(32)
const SECRET_KEY_B64 = b64std(SECRET_KEY)
const MK = v1.randomBytes(32)
const OLD_PASSWORD = 'the old password'
const NEW_PASSWORD = 'a brand new password'

// The stored PASSWORD floor, built exactly as account birth builds it — at production ARGON too, because the
// proof refuses any served profile other than the one the client writes (ADR 0017 rule 5). The default floor is
// built once for the file; nothing here mutates it.
function storedFloor(masterKeyBytes = MK, secret = SECRET_KEY, password = OLD_PASSWORD) {
  const salt = v1.randomBytes(16)
  const kek = v1.kekPasswordSecret(secret, password, salt, v1.ARGON)
  return {
    id: 'w1',
    method: 'PASSWORD',
    wrappedKey: b64std(v1.symWrap(kek, masterKeyBytes, 'master-key', v1.randomBytes(12))),
    kdfParams: b64std(v1.encodeKdfParams(v1.ARGON, salt)),
  }
}

let floor: ReturnType<typeof storedFloor>
beforeAll(() => {
  floor = storedFloor()
}, PRODUCTION_ARGON_TIMEOUT_MS)

describe('changePassword', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    held.mk = MK.slice()
    requestLoginChallenge.mockResolvedValue({ instanceSalt: b64std(INSTANCE_SALT), nonce: b64std(new Uint8Array(32)) })
    getAccountKeyMaterial.mockResolvedValue({ suiteId: 'v1', wraps: [floor], wrappedIdentityPrivateKey: 'aWdub3JlZA==' })
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

  it('proves the Secret Key to the server over the auth key it replaces and what it installs', async () => {
    // The half a phished password cannot produce (ADR 0060 Decision 2). The server's check, run here: the
    // statement rebuilt from what was SENT verifies under the proof key the Secret Key derives — and under no
    // other, so it is the Secret Key's signature and not merely a well-formed one.
    await changePassword(NEW_PASSWORD, SECRET_KEY_B64, OLD_PASSWORD)
    const body = changePasswordCall.mock.calls[0][0].changePasswordRequest

    const oldAuth = v1.deriveAuthKeypair(OLD_PASSWORD, v1.deriveAuthSalt(INSTANCE_SALT, EMAIL), v1.ARGON)
    const stmt = v1.passwordChangeProofStatement({
      currentAuthPublicKey: oldAuth.publicKey,
      authPublicKey: fromB64std(body.authPublicKey),
      passwordWrap: { wrappedKey: fromB64std(body.passwordWrap.wrappedKey), kdfParams: fromB64std(body.passwordWrap.kdfParams) },
    })
    const sig = fromB64std(body.secretKeyProofSignature)
    expect(v1.authVerify(v1.deriveSecretKeyProofKeypair(SECRET_KEY).publicKey, stmt, sig)).toBe(true)
    expect(v1.authVerify(v1.deriveSecretKeyProofKeypair(v1.randomBytes(32)).publicKey, stmt, sig)).toBe(false)
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

  // uc.account.unlock's origin is a statement about what has been PROVEN, not about which wrap happened to
  // open the app. A device-opened session that just supplied password + Secret Key is cold from here on —
  // the same fact rotation records for the same reason.
  it('leaves the session marked cold, whatever opened it', async () => {
    await changePassword(NEW_PASSWORD, SECRET_KEY_B64, OLD_PASSWORD)

    expect(setUnlocked).toHaveBeenCalledOnce()
    const [mk, , origin] = setUnlocked.mock.calls[0]
    expect(origin).toBe('cold')
    expect(b64std(mk)).toBe(b64std(MK))
  }, PRODUCTION_ARGON_TIMEOUT_MS)

  it('never sends the password, Secret Key or Master Key onto the wire', async () => {
    await changePassword(NEW_PASSWORD, SECRET_KEY_B64, OLD_PASSWORD)
    const wire = JSON.stringify(changePasswordCall.mock.calls[0][0].changePasswordRequest)

    expect(wire).not.toContain(NEW_PASSWORD)
    expect(wire).not.toContain(OLD_PASSWORD)
    expect(wire).not.toContain(SECRET_KEY_B64)
    expect(wire).not.toContain(b64std(MK))
  }, PRODUCTION_ARGON_TIMEOUT_MS)

  // The gate (ADR 0060 Decision 2). A Secret Key that does not open the account's current floor is the
  // attacker's — a DEVICE_KEY holder typing their OWN kit. Asserted together with "and nothing was posted":
  // a gate that refuses after writing is not a gate, and here the write would wrap the victim's Master Key
  // under a KEK only the attacker holds.
  it('refuses before any write when the Secret Key does not open the current floor', async () => {
    const foreignSecretKey = b64std(v1.randomBytes(32))

    await expect(changePassword(NEW_PASSWORD, foreignSecretKey, OLD_PASSWORD)).rejects.toBeTruthy()
    expect(changePasswordCall).not.toHaveBeenCalled()
  }, PRODUCTION_ARGON_TIMEOUT_MS)

  // The other half of the same gate, and the half AEAD alone does not give: the wrap opens, but it opens a
  // Master Key this session is not holding — a floor rewritten underneath us. Rotation has the same case
  // (`rotateKeys.test.ts`); both must redden together, or the shared proof has stopped reaching one of them.
  it('refuses when the floor opens a DIFFERENT Master Key than this session holds', async () => {
    getAccountKeyMaterial.mockResolvedValue({
      suiteId: 'v1',
      wraps: [storedFloor(v1.randomBytes(32))],
      wrappedIdentityPrivateKey: 'aWdub3JlZA==',
    })

    await expect(changePassword(NEW_PASSWORD, SECRET_KEY_B64, OLD_PASSWORD)).rejects.toBeInstanceOf(ChangePasswordRefused)
    expect(changePasswordCall).not.toHaveBeenCalled()
  }, PRODUCTION_ARGON_TIMEOUT_MS)

  it('refuses before any write when the vault is locked', async () => {
    held.mk = null
    await expect(changePassword(NEW_PASSWORD, SECRET_KEY_B64, OLD_PASSWORD)).rejects.toBeInstanceOf(ChangePasswordRefused)
    expect(changePasswordCall).not.toHaveBeenCalled()
    expect(requestLoginChallenge).not.toHaveBeenCalled()
  })
})
