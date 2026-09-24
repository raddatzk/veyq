import { beforeAll, describe, expect, it, vi, beforeEach } from 'vitest'
import { PRODUCTION_ARGON_TIMEOUT_MS } from '@veyq/crypto/testArgon'
import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'
import { buildAccount } from '@veyq/crypto/accountBirth'
import { openWithSecretKey, completeRecovery, requestRecovery } from './recovery'
import type { RecoveryMaterial } from '../../../api/generated/auth'

// The API is stubbed: this file's job is the crypto round-trip, not the transport. What it must prove is that
// the wrap minted at account birth is openable by the Secret Key ALONE, that the proof the server compares is
// reproducible only that way, and that the replacement floor still needs the password.
const requestPasswordRecovery = vi.fn().mockResolvedValue(undefined)
const completePasswordRecovery = vi.fn().mockResolvedValue(undefined)
const requestLoginChallenge = vi.fn()

vi.mock('../../../api/client', () => ({
  recoveryApi: () => ({ requestPasswordRecovery, completePasswordRecovery, releaseRecoveryMaterial: vi.fn() }),
  loginApi: () => ({ requestLoginChallenge }),
}))

const INSTANCE_SALT = new Uint8Array(16).fill(7)
const EMAIL = 'recover-me@example.com'
const PASSWORD = 'correct horse battery staple'

// Two accounts, born ONCE for the whole file. A real birth costs three production-params Argon2id derivations
// (~2.5s), and minting one per test is what pushed this file to 48s. Nothing here mutates them — every case
// only reads the wraps — so sharing is safe and the fixtures stay real rather than hand-built.
type Born = ReturnType<typeof mintAccount>
let mine: Born
let theirs: Born

beforeAll(() => {
  mine = mintAccount()
  theirs = mintAccount()
}, PRODUCTION_ARGON_TIMEOUT_MS)

/** Mint an account the way a real birth does, then hand back what the server would have stored. */
function mintAccount() {
  const built = buildAccount(EMAIL, PASSWORD, INSTANCE_SALT)
  const rec = built.material.masterKeyWraps.find((w) => w.method === 'RECOVERY')!
  const material: RecoveryMaterial = {
    email: EMAIL,
    suiteId: 'v1',
    wrappedKey: rec.wrappedKey,
    kdfParams: rec.kdfParams,
    wrappedIdentityPrivateKey: built.material.wrappedIdentityPrivateKey,
  }
  return { built, material, secretKey: built.emergencyKit.secretKey }
}

describe('recovery — opening the withheld wrap', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    requestLoginChallenge.mockResolvedValue({ instanceSalt: b64std(INSTANCE_SALT), nonce: b64std(new Uint8Array(32)) })
  })

  it('opens the RECOVERY wrap with the Secret Key and NO password', async () => {
    const { material, secretKey } = mine
    const opened = await openWithSecretKey(material, secretKey)
    expect(opened.masterKey).toHaveLength(32)
  }, PRODUCTION_ARGON_TIMEOUT_MS)

  it('reproduces the commitment the server compares against', async () => {
    const { built, material, secretKey } = mine
    const opened = await openWithSecretKey(material, secretKey)
    expect(opened.recoveryAuthTag).toBe(built.material.recoveryMasterKeyTag)
  }, PRODUCTION_ARGON_TIMEOUT_MS)

  it('recovers the SAME Master Key the password floor holds — so nothing needs re-encrypting', async () => {
    const { built, material, secretKey } = mine
    const opened = await openWithSecretKey(material, secretKey)

    // Open the floor the ordinary way and compare: one Master Key, two locks.
    const floor = built.material.masterKeyWraps.find((w) => w.method === 'PASSWORD')!
    const { params, salt } = v1.decodeKdfParams(fromB64std(floor.kdfParams))
    const viaPassword = v1.symUnwrap(
      v1.kekPasswordSecret(fromB64std(secretKey), PASSWORD, salt, params),
      fromB64std(floor.wrappedKey),
      'master-key',
    )
    expect(b64std(opened.masterKey)).toBe(b64std(viaPassword))
  }, PRODUCTION_ARGON_TIMEOUT_MS)

  it('the identity key opens under the recovered Master Key', async () => {
    const { material, secretKey } = mine
    const opened = await openWithSecretKey(material, secretKey)
    const priv = v1.symUnwrap(opened.masterKey, fromB64std(material.wrappedIdentityPrivateKey), 'identity-priv')
    expect(priv.length).toBeGreaterThan(0)
  }, PRODUCTION_ARGON_TIMEOUT_MS)

  // --- the cases that must fail ---

  it('a WRONG Secret Key fails on the device, with nothing sent', async () => {
    const { material } = mine
    const wrong = b64std(v1.randomBytes(32))
    await expect(openWithSecretKey(material, wrong)).rejects.toThrow()
    expect(completePasswordRecovery).not.toHaveBeenCalled()
  }, PRODUCTION_ARGON_TIMEOUT_MS)

  it('another account\'s Secret Key does not open this wrap', async () => {
    await expect(openWithSecretKey(mine.material, theirs.secretKey)).rejects.toThrow()
  }, PRODUCTION_ARGON_TIMEOUT_MS)
})

describe('recovery — setting the new password', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    requestLoginChallenge.mockResolvedValue({ instanceSalt: b64std(INSTANCE_SALT), nonce: b64std(new Uint8Array(32)) })
  })

  it('sends the proof, a re-wrapped floor and a matching auth key — and never the password or the keys', async () => {
    const { material, secretKey } = mine
    const opened = await openWithSecretKey(material, secretKey)

    await completeRecovery('the-token', material, opened, secretKey, 'a brand new password')

    const body = completePasswordRecovery.mock.calls[0][0].recoveryCompleteRequest
    expect(body.token).toBe('the-token')
    expect(body.recoveryAuthTag).toBe(opened.recoveryAuthTag)

    const wire = JSON.stringify(body)
    expect(wire).not.toContain('a brand new password')
    expect(wire).not.toContain(secretKey)
    expect(wire).not.toContain(b64std(opened.masterKey))
  }, PRODUCTION_ARGON_TIMEOUT_MS)

  it('the new floor opens the same Master Key under the NEW password only', async () => {
    const { material, secretKey } = mine
    const opened = await openWithSecretKey(material, secretKey)
    await completeRecovery('the-token', material, opened, secretKey, 'a brand new password')

    const body = completePasswordRecovery.mock.calls[0][0].recoveryCompleteRequest
    const { params, salt } = v1.decodeKdfParams(fromB64std(body.kdfParams))
    const raw = fromB64std(secretKey)

    const reopened = v1.symUnwrap(
      v1.kekPasswordSecret(raw, 'a brand new password', salt, params),
      fromB64std(body.wrappedKey),
      'master-key',
    )
    expect(b64std(reopened)).toBe(b64std(opened.masterKey))

    expect(() =>
      v1.symUnwrap(v1.kekPasswordSecret(raw, PASSWORD, salt, params), fromB64std(body.wrappedKey), 'master-key'),
    ).toThrow()
  }, PRODUCTION_ARGON_TIMEOUT_MS)

  it('mints a fresh salt rather than re-using the recovery wrap\'s', async () => {
    const { material, secretKey } = mine
    const opened = await openWithSecretKey(material, secretKey)
    await completeRecovery('the-token', material, opened, secretKey, 'a brand new password')

    const body = completePasswordRecovery.mock.calls[0][0].recoveryCompleteRequest
    expect(body.kdfParams).not.toBe(material.kdfParams)
  }, PRODUCTION_ARGON_TIMEOUT_MS)

  it('a request reports nothing about whether the address is known', async () => {
    await expect(requestRecovery('anyone@example.com')).resolves.toBeUndefined()
    expect(requestPasswordRecovery).toHaveBeenCalledWith({ recoveryRequest: { email: 'anyone@example.com' } })
  }, PRODUCTION_ARGON_TIMEOUT_MS)
})
