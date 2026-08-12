// uc.account.remember-this-device / auto-unlock (web "remember me", cr-0054) — the password-less DEVICE_KEY
// wrap: enrol seals the Master Key under a non-extractable device key and uploads the ciphertext as a server
// wrap; boot-restore decrypts it back; re-enrol replaces the prior wrap; clear removes both halves.
import 'fake-indexeddb/auto'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { IDBFactory } from 'fake-indexeddb'

vi.mock('../../../api/client', () => ({
  accountWrapsApi: vi.fn(),
  accountApi: vi.fn(),
  loginApi: vi.fn(),
  tokenApi: vi.fn(),
  sessionApi: vi.fn(),
}))

import { enrolDeviceKey, restoreDeviceKey, clearDeviceKey, isRemembered, rememberedWrapId } from './deviceKeyWrap'
import { accountWrapsApi } from '../../../api/client'
import type { AccountKeyMaterial } from '../../../api/generated/account'

const masterKey = () => new Uint8Array(Array.from({ length: 32 }, (_, i) => i + 1))

function mockWrapsApi(add: ReturnType<typeof vi.fn>, del: ReturnType<typeof vi.fn>) {
  vi.mocked(accountWrapsApi).mockReturnValue({
    addAccountWrap: add,
    deleteAccountWrap: del,
  } as unknown as ReturnType<typeof accountWrapsApi>)
}

/** Build the key material the server would serve from the ciphertext + iv enrol just uploaded. */
function materialFrom(add: ReturnType<typeof vi.fn>, id: string): AccountKeyMaterial {
  const req = add.mock.calls[0][0].addWrapRequest
  return {
    suiteId: 'v1',
    wrappedIdentityPrivateKey: '',
    wraps: [{ id, method: 'DEVICE_KEY', wrappedKey: req.wrappedKey, kdfParams: req.kdfParams }],
  } as AccountKeyMaterial
}

beforeEach(() => {
  vi.clearAllMocks()
  globalThis.indexedDB = new IDBFactory() // a fresh, empty local store per test
})

describe('deviceKeyWrap (web remember-me, cr-0054)', () => {
  it('enrols a DEVICE_KEY wrap (ciphertext + iv, no password) and round-trips on restore', async () => {
    const add = vi.fn().mockResolvedValue({ id: 'wrap-1' })
    mockWrapsApi(add, vi.fn())

    await enrolDeviceKey('User@Example.com', masterKey())

    expect(add).toHaveBeenCalledOnce()
    const req = add.mock.calls[0][0].addWrapRequest
    expect(req.method).toBe('DEVICE_KEY')
    expect(req.wrappedKey.length).toBeGreaterThan(0)
    expect(req.kdfParams.length).toBeGreaterThan(0) // the 12-byte AEAD iv

    const restored = await restoreDeviceKey(materialFrom(add, 'wrap-1'))
    expect(restored?.email).toBe('user@example.com')
    expect(restored?.masterKey).toEqual(masterKey())
  })

  // The returned id is the ONLY moment the new wrap id exists anywhere (ADR 0005 §9 mints a fresh one on every
  // enrolment). Its caller records the device↔wrap join from it; this module stays ignorant of the register.
  it('returns the new wrap id, and the local record answers with it', async () => {
    const add = vi.fn().mockResolvedValue({ id: 'wrap-1' })
    mockWrapsApi(add, vi.fn())

    expect(await enrolDeviceKey('User@Example.com', masterKey())).toBe('wrap-1')
    expect(await rememberedWrapId('user@example.com')).toBe('wrap-1')
    // One remembered account per browser profile — a different email is not this device's wrap.
    expect(await rememberedWrapId('someone@else.com')).toBeNull()
  })

  it('a device that was never enrolled holds no wrap id', async () => {
    expect(await rememberedWrapId('a@b.com')).toBeNull()
  })

  it('re-enrol replaces: uploads the new wrap, then deletes the prior one', async () => {
    const add1 = vi.fn().mockResolvedValue({ id: 'old-1' })
    const del = vi.fn().mockResolvedValue(undefined)
    mockWrapsApi(add1, del)
    expect(await enrolDeviceKey('a@b.com', masterKey())).toBe('old-1')

    const add2 = vi.fn().mockResolvedValue({ id: 'new-1' })
    mockWrapsApi(add2, del)
    expect(await enrolDeviceKey('a@b.com', masterKey())).toBe('new-1')
    expect(await rememberedWrapId('a@b.com')).toBe('new-1') // the join follows the new id

    expect(del).toHaveBeenCalledWith({ id: 'old-1' }) // the prior DEVICE_KEY wrap is removed first
    expect(add2).toHaveBeenCalledOnce()
    // the live record now points at the new wrap
    const restored = await restoreDeviceKey(materialFrom(add2, 'new-1'))
    expect(restored?.masterKey).toEqual(masterKey())
  })

  it('rolls back the server wrap when the local save fails (no orphan)', async () => {
    // The server wrap lands, then the local put fails: nuke IndexedDB as add() resolves, so the prior-record
    // check + upload succeed but the record write throws → rollback.
    const add = vi.fn().mockImplementation(async () => {
      globalThis.indexedDB = undefined as unknown as IDBFactory
      return { id: 'wrap-x' }
    })
    const del = vi.fn().mockResolvedValue(undefined)
    mockWrapsApi(add, del)

    await expect(enrolDeviceKey('a@b.com', masterKey())).rejects.toThrow()
    expect(del).toHaveBeenCalledWith({ id: 'wrap-x' })
  })

  it('restore returns null when this device is not enrolled', async () => {
    mockWrapsApi(vi.fn(), vi.fn())
    const material = { suiteId: 'v1', wrappedIdentityPrivateKey: '', wraps: [] } as unknown as AccountKeyMaterial
    expect(await restoreDeviceKey(material)).toBeNull()
  })

  it('restore returns null (keeps the local key) when the matching wrap is gone server-side', async () => {
    const add = vi.fn().mockResolvedValue({ id: 'wrap-1' })
    mockWrapsApi(add, vi.fn())
    await enrolDeviceKey('a@b.com', masterKey())
    // material no longer carries wrap-1 (e.g. withdrawn) → fall back, but the device stays enrolled locally.
    const material = { suiteId: 'v1', wrappedIdentityPrivateKey: '', wraps: [] } as unknown as AccountKeyMaterial
    expect(await restoreDeviceKey(material)).toBeNull()
    expect(await isRemembered()).toBe(true)
  })

  it('restore drops the local record when the ciphertext will not open under the local key', async () => {
    const add = vi.fn().mockResolvedValue({ id: 'wrap-1' })
    mockWrapsApi(add, vi.fn())
    await enrolDeviceKey('a@b.com', masterKey())
    // a wrap with our id but tampered ciphertext → AEAD open fails → stale record dropped.
    const material = {
      suiteId: 'v1',
      wrappedIdentityPrivateKey: '',
      wraps: [{ id: 'wrap-1', method: 'DEVICE_KEY', wrappedKey: 'dGFtcGVyZWQ=', kdfParams: 'aXYtMTJieXRlcw==' }],
    } as unknown as AccountKeyMaterial
    expect(await restoreDeviceKey(material)).toBeNull()
    expect(await isRemembered()).toBe(false)
  })

  it('clear removes the server wrap and drops the local key', async () => {
    const add = vi.fn().mockResolvedValue({ id: 'wrap-1' })
    const del = vi.fn().mockResolvedValue(undefined)
    mockWrapsApi(add, del)
    await enrolDeviceKey('a@b.com', masterKey())

    await clearDeviceKey()

    expect(del).toHaveBeenCalledWith({ id: 'wrap-1' })
    expect(await isRemembered()).toBe(false)
  })

  it('clear with deleteServerWrap=false drops only the local key (no session)', async () => {
    const add = vi.fn().mockResolvedValue({ id: 'wrap-1' })
    const del = vi.fn()
    mockWrapsApi(add, del)
    await enrolDeviceKey('a@b.com', masterKey())

    await clearDeviceKey(false)

    expect(del).not.toHaveBeenCalled()
    expect(await isRemembered()).toBe(false)
  })
})
