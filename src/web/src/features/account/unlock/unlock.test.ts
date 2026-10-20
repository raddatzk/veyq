import { describe, it, expect, vi, beforeAll, beforeEach } from 'vitest'

vi.mock('../../../api/client', () => ({
  accountApi: vi.fn(),
  loginApi: vi.fn(),
  tokenApi: vi.fn(),
  sessionApi: vi.fn(),
}))

import { unlockMasterKey } from './unlock'
import { accountApi, tokenApi } from '../../../api/client'
import { ResponseError, type AccountKeyMaterial } from '../../../api/generated/account'
import { fakeApi, stubApi } from '../../../test/apiMock'
import { isUnlocked, masterKey, identityPrivate, clearVault } from '../../../app/vault'
import { session } from '../../../app/session'
import * as v1 from '@veyq/crypto/v1'
import { b64std } from '@veyq/crypto/base64'
import { PRODUCTION_ARGON_TIMEOUT_MS } from '@veyq/crypto/testArgon'

const PASSWORD = 'correct horse battery'

/** Build the exact server-stored material setup writes: a PASSWORD wrap (KEK = Argon2id(secretKey+password))
 *  over the Master Key, plus the identity private key wrapped under that Master Key. Mirrors setupServer.ts.
 *
 *  At production params, because the client refuses any served profile other than the one it writes (ADR 0017
 *  rule 5) — the cost is production's to choose, not this test's (src/packages/crypto/src/testArgon.ts). Built ONCE
 *  for the file: nothing here mutates it, and each build is a full Argon2id. */
function buildMaterial(password: string) {
  const id = v1.generateIdentity()
  const masterKey = v1.randomBytes(32)
  const wrappedPriv = v1.seal({ purpose: 'identity-priv' }, masterKey, v1.serializeIdentityPrivate(id))

  const secretKey = v1.randomBytes(32)
  const salt = v1.randomBytes(16)
  const kek = v1.kekPasswordSecret(secretKey, password, salt, v1.ARGON)
  const masterWrap = v1.seal({ purpose: 'master-key' }, kek, masterKey)

  const material: AccountKeyMaterial = {
    userId: 'user-1',
    suiteId: 'v1',
    wraps: [
      { method: 'PASSWORD', wrappedKey: b64std(masterWrap), kdfParams: b64std(v1.encodeKdfParams(v1.ARGON, salt)) },
      { method: 'RECOVERY', wrappedKey: b64std(masterWrap), kdfParams: b64std(v1.encodeKdfParams(v1.ARGON, salt)) },
    ],
    wrappedIdentityPrivateKey: b64std(wrappedPriv),
  }
  return { material, id, masterKey, secretKey }
}

function mockMaterial(material: AccountKeyMaterial) {
  stubApi(accountApi, { getAccountKeyMaterial: material })
}

let built: ReturnType<typeof buildMaterial>
beforeAll(() => {
  built = buildMaterial(PASSWORD)
}, PRODUCTION_ARGON_TIMEOUT_MS)

beforeEach(() => {
  clearVault()
  vi.clearAllMocks()
  session.accessToken = null
  session.refreshToken = null
  session.familyId = null
})

describe('unlockMasterKey (uc.account.unlock)', () => {
  it('opens the Master Key + identity private key with the right password and Secret Key', async () => {
    const { material, id, masterKey: expectedMaster, secretKey } = built
    mockMaterial(material)

    await unlockMasterKey(PASSWORD, b64std(secretKey))

    expect(isUnlocked()).toBe(true)
    // the unwrapped Master Key matches the one the wrap was built from
    expect(masterKey()).toEqual(expectedMaster)
    // and the identity private key round-trips (proves the Master Key actually opened the identity bundle)
    expect(identityPrivate()).toEqual(id.priv)
  }, PRODUCTION_ARGON_TIMEOUT_MS)

  it('hands out key copies so clearVault cannot corrupt a held reference (use-after-clear safety)', async () => {
    const { material, masterKey: expectedMaster, secretKey } = built
    mockMaterial(material)
    await unlockMasterKey(PASSWORD, b64std(secretKey))

    const held = masterKey()!
    clearVault() // zero-fills the vault's OWN buffer in place

    // the previously returned copy still holds the real bytes, not zeroes
    expect(held).toEqual(expectedMaster)
  }, PRODUCTION_ARGON_TIMEOUT_MS)

  it('fails generically on a wrong password — no key material is held', async () => {
    const { material, secretKey } = built
    mockMaterial(material)

    await expect(unlockMasterKey('wrong password', b64std(secretKey))).rejects.toThrow()
    expect(isUnlocked()).toBe(false)
    expect(masterKey()).toBeNull()
  }, PRODUCTION_ARGON_TIMEOUT_MS)

  it('fails generically on a wrong Secret Key — no key material is held', async () => {
    const { material } = built
    mockMaterial(material)

    await expect(unlockMasterKey(PASSWORD, b64std(v1.randomBytes(32)))).rejects.toThrow()
    expect(isUnlocked()).toBe(false)
    expect(masterKey()).toBeNull()
  }, PRODUCTION_ARGON_TIMEOUT_MS)

  it('refreshes the session and retries on a 401 from the account client (cross-package ResponseError)', async () => {
    // Regression: withRefresh must recognise a 401 thrown by the *account* generated client, whose
    // ResponseError is a DIFFERENT class than the auth client's — an `instanceof` check on one is false for
    // the other, which silently disabled the refresh-retry for unlock.
    const { material, masterKey: expectedMaster, secretKey } = built
    session.refreshToken = 'r1'
    let calls = 0
    fakeApi(accountApi, {
      getAccountKeyMaterial: () => {
        calls += 1
        return calls === 1
          ? Promise.reject(new ResponseError({ status: 401 } as unknown as Response, 'unauthorized'))
          : Promise.resolve(material)
      },
    })
    stubApi(tokenApi, { refreshSession: { accessToken: 'a2', refreshToken: 'r2' } })

    await unlockMasterKey(PASSWORD, b64std(secretKey))

    expect(calls).toBe(2) // first 401 → refresh → retry succeeded
    expect(session.accessToken).toBe('a2') // tokens rotated by the refresh
    expect(isUnlocked()).toBe(true)
    expect(masterKey()).toEqual(expectedMaster)
  }, PRODUCTION_ARGON_TIMEOUT_MS)

  it('fails when the account has no PASSWORD wrap (E2)', async () => {
    const { material, secretKey } = built
    const noPassword = { ...material, wraps: material.wraps.filter((w) => w.method !== 'PASSWORD') }
    mockMaterial(noPassword)

    await expect(unlockMasterKey(PASSWORD, b64std(secretKey))).rejects.toThrow()
    expect(isUnlocked()).toBe(false)
  })
})
