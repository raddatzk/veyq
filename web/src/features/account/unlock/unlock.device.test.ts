import { describe, it, expect, vi, beforeEach } from 'vitest'

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
import * as device from '../trustDevice/deviceKey'
import { isUnlocked, masterKey, clearVault } from '../../../app/vault'
import * as v1 from '../../../shared/crypto/v1'
import { b64std } from '../../../shared/base64'
import { TEST_ARGON } from '../../../test/argon'

const PASSWORD = 'correct horse battery'
const DEVICE_FACTOR = new Uint8Array(32).fill(0x44)
const EMAIL = 'user@example.com'
const WRAP_ID = 'device-wrap-id-1'

/** Build server material with a DEVICE wrap (id = WRAP_ID) whose KEK = kekDevicePassword(factor, pw, salt).
 *
 *  Cost params: TEST_ARGON, carried in the wrap's own `kdfParams` — see the note on unlock.test.ts's
 *  buildMaterial and src/test/argon.ts. Real Argon2id over the real device-unlock path, ~1000× cheaper. */
function buildDeviceMaterial(password: string, deviceFactor: Uint8Array) {
  const id = v1.generateIdentity()
  const mk = v1.randomBytes(32)
  const wrappedPriv = v1.symWrap(mk, v1.serializeIdentityPrivate(id), 'identity-priv', v1.randomBytes(12))
  const salt = v1.randomBytes(16)
  const kek = v1.kekDevicePassword(deviceFactor, password, salt, TEST_ARGON)
  const deviceWrap = v1.symWrap(kek, mk, 'master-key', v1.randomBytes(12))
  const kdfParams = b64std(v1.encodeKdfParams(TEST_ARGON, salt))
  const material = {
    suiteId: 'v1',
    wraps: [{ id: WRAP_ID, method: 'DEVICE', wrappedKey: b64std(deviceWrap), kdfParams }],
    wrappedIdentityPrivateKey: b64std(wrappedPriv),
  }
  return { material, mk }
}

function mockMaterial(material: unknown) {
  vi.mocked(accountApi).mockReturnValue({
    getAccountKeyMaterial: () => Promise.resolve(material),
  } as unknown as ReturnType<typeof accountApi>)
}

beforeEach(() => {
  clearVault()
  vi.clearAllMocks()
})

describe('unlockWithDevice (uc.account.trust-device)', () => {
  it('opens the Master Key with only the password on a trusted device', async () => {
    const { material, mk } = buildDeviceMaterial(PASSWORD, DEVICE_FACTOR)
    mockMaterial(material)
    vi.mocked(device.loadTrust).mockResolvedValue({ cryptoKey: {} as CryptoKey, wrapId: WRAP_ID })
    vi.mocked(device.deviceFactor).mockResolvedValue(DEVICE_FACTOR)

    const ok = await unlockWithDevice(EMAIL, PASSWORD)

    expect(ok).toBe(true)
    expect(isUnlocked()).toBe(true)
    expect(masterKey()).toEqual(mk)
  })

  it('returns false (no throw) when the device is not trusted', async () => {
    vi.mocked(device.loadTrust).mockResolvedValue(null)
    expect(await unlockWithDevice(EMAIL, PASSWORD)).toBe(false)
    expect(isUnlocked()).toBe(false)
  })

  it('falls back (returns false) without destroying the local key when no wrap matches (orphan)', async () => {
    const { material } = buildDeviceMaterial(PASSWORD, DEVICE_FACTOR)
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
    const { material } = buildDeviceMaterial(PASSWORD, DEVICE_FACTOR)
    mockMaterial(material)
    vi.mocked(device.loadTrust).mockResolvedValue({ cryptoKey: {} as CryptoKey, wrapId: WRAP_ID })
    vi.mocked(device.deviceFactor).mockResolvedValue(DEVICE_FACTOR)

    await expect(unlockWithDevice(EMAIL, 'wrong password')).rejects.toThrow()
    expect(isUnlocked()).toBe(false)
  })
})
