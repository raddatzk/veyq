import { describe, it, expect, vi, beforeEach, type Mock } from 'vitest'
import { fakeApi } from '../../../test/apiMock'
import type { AccountWrapsApi } from '../../../api/generated/account'

vi.mock('../../../api/client', () => ({
  accountWrapsApi: vi.fn(),
  accountApi: vi.fn(),
  loginApi: vi.fn(),
  tokenApi: vi.fn(),
  sessionApi: vi.fn(),
}))
vi.mock('./deviceKey', () => ({
  loadTrust: vi.fn(),
  saveTrust: vi.fn(),
  clearTrust: vi.fn(),
  generateDeviceKey: vi.fn(),
  deviceFactor: vi.fn(),
  isTrustSupported: () => true,
}))

import { trustThisDevice, untrustThisDevice } from './trustDevice'
import * as device from './deviceKey'
import { accountWrapsApi } from '../../../api/client'
import { PRODUCTION_ARGON_TIMEOUT_MS } from '@veyq/crypto/testArgon'

const EMAIL = 'user@example.com'

type AddWrap = AccountWrapsApi['addAccountWrap']
type DeleteWrap = AccountWrapsApi['deleteAccountWrap']

function mockWrapsApi(add: Mock<AddWrap>, del: Mock<DeleteWrap>) {
  fakeApi(accountWrapsApi, { addAccountWrap: add, deleteAccountWrap: del })
}

beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(device.generateDeviceKey).mockResolvedValue({} as CryptoKey)
  vi.mocked(device.deviceFactor).mockResolvedValue(new Uint8Array(32).fill(7))
})

// `trustThisDevice` derives the DEVICE-wrap KEK at production params inside the code under test, so this block
// is the same inherent cost as login/share — it just measures ~1.2s here rather than ~3.7s and so has not yet
// crossed the 5s default on this machine. It is the same defect, and a machine with fewer cores is where it
// surfaces; the ceiling belongs on the class, not on the files that happened to fail first. See packages/crypto/src/testArgon.ts.
describe('trustThisDevice / untrustThisDevice', () => {
  it('enrols a DEVICE wrap and saves the local trust record', async () => {
    vi.mocked(device.loadTrust).mockResolvedValue(null)
    const add = vi.fn<AddWrap>().mockResolvedValue({ id: 'wrap-123' })
    mockWrapsApi(add, vi.fn<DeleteWrap>())

    // The returned id is the ONLY moment the new wrap id exists anywhere (every enrolment mints a fresh one,
    // ADR 0005 §9). Its caller records the device↔wrap join from it; this module stays ignorant of the register.
    expect(await trustThisDevice(EMAIL, 'pw', new Uint8Array(32).fill(1))).toBe('wrap-123')

    expect(add).toHaveBeenCalledOnce()
    const req = add.mock.calls[0][0].addWrapRequest
    expect(req.method).toBe('DEVICE')
    expect(req.wrappedKey.length).toBeGreaterThan(0)
    expect(req.kdfParams.length).toBeGreaterThan(0)
    expect(device.saveTrust).toHaveBeenCalledWith(EMAIL, expect.objectContaining({ wrapId: 'wrap-123' }))
  })

  it('re-trust replaces: deletes the existing wrap before adding the new one', async () => {
    vi.mocked(device.loadTrust).mockResolvedValue({ cryptoKey: {} as CryptoKey, wrapId: 'old-1' })
    const del = vi.fn<DeleteWrap>().mockResolvedValue(undefined)
    const add = vi.fn<AddWrap>().mockResolvedValue({ id: 'new-1' })
    mockWrapsApi(add, del)

    expect(await trustThisDevice(EMAIL, 'pw', new Uint8Array(32).fill(1))).toBe('new-1')

    expect(del).toHaveBeenCalledWith({ id: 'old-1' })
    expect(device.clearTrust).toHaveBeenCalledWith(EMAIL)
    expect(add).toHaveBeenCalledOnce()
    expect(device.saveTrust).toHaveBeenCalledWith(EMAIL, expect.objectContaining({ wrapId: 'new-1' }))
  })

  it('rolls back the server wrap when the local save fails (no orphan)', async () => {
    vi.mocked(device.loadTrust).mockResolvedValue(null)
    vi.mocked(device.saveTrust).mockRejectedValue(new Error('idb_write_failed'))
    const add = vi.fn<AddWrap>().mockResolvedValue({ id: 'wrap-x' })
    const del = vi.fn<DeleteWrap>().mockResolvedValue(undefined)
    mockWrapsApi(add, del)

    await expect(trustThisDevice(EMAIL, 'pw', new Uint8Array(32).fill(1))).rejects.toThrow()
    expect(del).toHaveBeenCalledWith({ id: 'wrap-x' }) // the just-added wrap is rolled back
  })

  it('untrust removes the server wrap and the local key', async () => {
    vi.mocked(device.loadTrust).mockResolvedValue({ cryptoKey: {} as CryptoKey, wrapId: 'w1' })
    const del = vi.fn<DeleteWrap>().mockResolvedValue(undefined)
    mockWrapsApi(vi.fn<AddWrap>(), del)

    await untrustThisDevice(EMAIL)

    expect(del).toHaveBeenCalledWith({ id: 'w1' })
    expect(device.clearTrust).toHaveBeenCalledWith(EMAIL)
  })

  it('untrust is a no-op when the device is not trusted', async () => {
    vi.mocked(device.loadTrust).mockResolvedValue(null)
    const del = vi.fn<DeleteWrap>()
    mockWrapsApi(vi.fn<AddWrap>(), del)

    await untrustThisDevice(EMAIL)

    expect(del).not.toHaveBeenCalled()
    expect(device.clearTrust).not.toHaveBeenCalled()
  })
}, PRODUCTION_ARGON_TIMEOUT_MS)
