import { describe, it, expect, vi, beforeAll, beforeEach } from 'vitest'

vi.mock('../../../api/client', () => ({
  accountApi: vi.fn(),
  accountWrapsApi: vi.fn(),
  loginApi: vi.fn(),
  tokenApi: vi.fn(),
  sessionApi: vi.fn(),
}))
vi.mock('../trustDevice/deviceKey', () => ({
  loadTrust: vi.fn(),
  deviceFactor: vi.fn(),
  clearTrust: vi.fn(),
  generateDeviceKey: vi.fn(),
  saveTrust: vi.fn(),
  isTrustSupported: () => true,
}))

import { unlockWithDevice } from './unlock'
import { accountApi } from '../../../api/client'
import type { AccountKeyMaterial } from '../../../api/generated/account'
import { stubApi } from '../../../test/apiMock'
import * as device from '../trustDevice/deviceKey'
import { isUnlocked, masterKey, clearVault } from '../../../app/vault'
import * as v1 from '@veyq/crypto/v1'
import { b64std } from '@veyq/crypto/base64'
import { PRODUCTION_ARGON_TIMEOUT_MS } from '@veyq/crypto/testArgon'

const PASSWORD = 'correct horse battery'
const DEVICE_FACTOR = new Uint8Array(32).fill(0x44)
const EMAIL = 'user@example.com'
const WRAP_ID = 'device-wrap-id-1'

/** Build server material with a DEVICE wrap (id = WRAP_ID) whose KEK = kekDevicePassword(factor, pw, salt).
 *
 *  Cost params: production's, carried in the wrap's own `kdfParams` — see the note on unlock.test.ts's
 *  buildMaterial. Built once for the file; nothing here mutates it. */
function buildDeviceMaterial(password: string, deviceFactor: Uint8Array) {
  const id = v1.generateIdentity()
  const mk = v1.randomBytes(32)
  const wrappedPriv = v1.seal({ purpose: 'identity-priv' }, mk, v1.serializeIdentityPrivate(id))
  const salt = v1.randomBytes(16)
  const kek = v1.kekDevicePassword(deviceFactor, password, salt, v1.ARGON)
  const deviceWrap = v1.seal({ purpose: 'master-key' }, kek, mk)
  const kdfParams = b64std(v1.encodeKdfParams(v1.ARGON, salt))
  const material: AccountKeyMaterial = {
    userId: 'user-1',
    suiteId: 'v1',
    wraps: [{ id: WRAP_ID, method: 'DEVICE', wrappedKey: b64std(deviceWrap), kdfParams }],
    wrappedIdentityPrivateKey: b64std(wrappedPriv),
  }
  return { material, mk }
}

function mockMaterial(material: AccountKeyMaterial) {
  stubApi(accountApi, { getAccountKeyMaterial: material })
}

let built: ReturnType<typeof buildDeviceMaterial>
beforeAll(() => {
  built = buildDeviceMaterial(PASSWORD, DEVICE_FACTOR)
}, PRODUCTION_ARGON_TIMEOUT_MS)

beforeEach(() => {
  clearVault()
  vi.clearAllMocks()
})

describe('unlockWithDevice (uc.account.trust-device)', () => {
  it('opens the Master Key with only the password on a trusted device', async () => {
    const { material, mk } = built
    mockMaterial(material)
    vi.mocked(device.loadTrust).mockResolvedValue({ cryptoKey: {} as CryptoKey, wrapId: WRAP_ID })
    vi.mocked(device.deviceFactor).mockResolvedValue(DEVICE_FACTOR)

    const ok = await unlockWithDevice(EMAIL, PASSWORD)

    expect(ok).toBe(true)
    expect(isUnlocked()).toBe(true)
    expect(masterKey()).toEqual(mk)
  }, PRODUCTION_ARGON_TIMEOUT_MS)

  it('returns false (no throw) when the device is not trusted', async () => {
    vi.mocked(device.loadTrust).mockResolvedValue(null)
    expect(await unlockWithDevice(EMAIL, PASSWORD)).toBe(false)
    expect(isUnlocked()).toBe(false)
  })

  it('falls back (returns false) without destroying the local key when no wrap matches (orphan)', async () => {
    const { material } = built
    mockMaterial(material)
    // a stored trust whose wrapId matches no server wrap → orphan
    vi.mocked(device.loadTrust).mockResolvedValue({ cryptoKey: {} as CryptoKey, wrapId: 'no-such-id' })
    vi.mocked(device.deviceFactor).mockResolvedValue(DEVICE_FACTOR)

    expect(await unlockWithDevice(EMAIL, PASSWORD)).toBe(false)
    // the local device key is NOT wiped on a single server response (no operator-forced key destruction)
    expect(device.clearTrust).not.toHaveBeenCalled()
    expect(isUnlocked()).toBe(false)
  })

  it('throws a generic failure on a wrong password — no key held', async () => {
    const { material } = built
    mockMaterial(material)
    vi.mocked(device.loadTrust).mockResolvedValue({ cryptoKey: {} as CryptoKey, wrapId: WRAP_ID })
    vi.mocked(device.deviceFactor).mockResolvedValue(DEVICE_FACTOR)

    await expect(unlockWithDevice(EMAIL, 'wrong password')).rejects.toThrow()
    expect(isUnlocked()).toBe(false)
  }, PRODUCTION_ARGON_TIMEOUT_MS)
})
