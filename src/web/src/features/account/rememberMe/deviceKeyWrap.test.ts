// uc.account.remember-this-device / auto-unlock (web "remember me", cr-0054) — the password-less DEVICE_KEY
// wrap: enrol seals the Master Key under a non-extractable device key and uploads the ciphertext as a server
// wrap; boot-restore decrypts it back; re-enrol replaces the prior wrap; clear removes both halves.
import 'fake-indexeddb/auto'
import { describe, it, expect, vi, beforeEach, type Mock } from 'vitest'
import { IDBFactory } from 'fake-indexeddb'

vi.mock('../../../api/client', () => ({
  accountWrapsApi: vi.fn(),
  accountApi: vi.fn(),
  loginApi: vi.fn(),
  tokenApi: vi.fn(),
  sessionApi: vi.fn(),
}))

import {
  enrolDeviceKey,
  restoreDeviceKey,
  clearDeviceKey,
  isRemembered,
  rememberedWrapId,
  rememberedEmail,
  readdressRemembered,
} from './deviceKeyWrap'
import { accountWrapsApi } from '../../../api/client'
import type { AccountKeyMaterial, AccountWrapsApi, MasterKeyWrap } from '../../../api/generated/account'
import { fakeApi } from '../../../test/apiMock'

const masterKey = () => new Uint8Array(Array.from({ length: 32 }, (_, i) => i + 1))

type AddWrap = AccountWrapsApi['addAccountWrap']
type DeleteWrap = AccountWrapsApi['deleteAccountWrap']

function mockWrapsApi(add: Mock<AddWrap>, del: Mock<DeleteWrap>) {
  fakeApi(accountWrapsApi, { addAccountWrap: add, deleteAccountWrap: del })
}

/** The key material the server serves, carrying `wraps`; only the wraps matter to the device-key restore. */
const material = (wraps: MasterKeyWrap[] = []): AccountKeyMaterial => ({
  userId: 'user-1',
  suiteId: 'v1',
  wrappedIdentityPrivateKey: '',
  wraps,
})

/** Build the key material the server would serve from the ciphertext + iv enrol just uploaded. */
function materialFrom(add: Mock<AddWrap>, id: string): AccountKeyMaterial {
  const req = add.mock.calls[0][0].addWrapRequest
  return material([{ id, method: 'DEVICE_KEY', wrappedKey: req.wrappedKey, kdfParams: req.kdfParams }])
}

beforeEach(() => {
  vi.clearAllMocks()
  globalThis.indexedDB = new IDBFactory() // a fresh, empty local store per test
})

describe('deviceKeyWrap (web remember-me, cr-0054)', () => {
  it('enrols a DEVICE_KEY wrap (ciphertext + iv, no password) and round-trips on restore', async () => {
    const add = vi.fn<AddWrap>().mockResolvedValue({ id: 'wrap-1' })
    mockWrapsApi(add, vi.fn<DeleteWrap>())

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
    const add = vi.fn<AddWrap>().mockResolvedValue({ id: 'wrap-1' })
    mockWrapsApi(add, vi.fn<DeleteWrap>())

    expect(await enrolDeviceKey('User@Example.com', masterKey())).toBe('wrap-1')
    expect(await rememberedWrapId('user@example.com')).toBe('wrap-1')
    // One remembered account per browser profile — a different email is not this device's wrap.
    expect(await rememberedWrapId('someone@else.com')).toBeNull()
  })

  it('a device that was never enrolled holds no wrap id', async () => {
    expect(await rememberedWrapId('a@b.com')).toBeNull()
  })

  // inbox #450: the sign-in screen prefills its email from the record, so it reads the record's one plaintext.
  it('names the email of its record, normalised, and none once the record is cleared', async () => {
    expect(await rememberedEmail()).toBeNull()
    mockWrapsApi(vi.fn<AddWrap>().mockResolvedValue({ id: 'wrap-1' }), vi.fn<DeleteWrap>().mockResolvedValue(undefined))
    await enrolDeviceKey('User@Example.com', masterKey())
    expect(await rememberedEmail()).toBe('user@example.com')
    await clearDeviceKey()
    expect(await rememberedEmail()).toBeNull()
  })

  it('re-enrol replaces: uploads the new wrap, then deletes the prior one', async () => {
    const add1 = vi.fn<AddWrap>().mockResolvedValue({ id: 'old-1' })
    const del = vi.fn<DeleteWrap>().mockResolvedValue(undefined)
    mockWrapsApi(add1, del)
    expect(await enrolDeviceKey('a@b.com', masterKey())).toBe('old-1')

    const add2 = vi.fn<AddWrap>().mockResolvedValue({ id: 'new-1' })
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
    const add = vi.fn<AddWrap>().mockImplementation(async () => {
      globalThis.indexedDB = undefined as unknown as IDBFactory
      return { id: 'wrap-x' }
    })
    const del = vi.fn<DeleteWrap>().mockResolvedValue(undefined)
    mockWrapsApi(add, del)

    await expect(enrolDeviceKey('a@b.com', masterKey())).rejects.toThrow()
    expect(del).toHaveBeenCalledWith({ id: 'wrap-x' })
  })

  it('restore returns null when this device is not enrolled', async () => {
    mockWrapsApi(vi.fn<AddWrap>(), vi.fn<DeleteWrap>())
    expect(await restoreDeviceKey(material())).toBeNull()
  })

  it('restore returns null (keeps the local key) when the matching wrap is gone server-side', async () => {
    const add = vi.fn<AddWrap>().mockResolvedValue({ id: 'wrap-1' })
    mockWrapsApi(add, vi.fn<DeleteWrap>())
    await enrolDeviceKey('a@b.com', masterKey())
    // material no longer carries wrap-1 (e.g. withdrawn) → fall back, but the device stays enrolled locally.
    expect(await restoreDeviceKey(material())).toBeNull()
    expect(await isRemembered()).toBe(true)
  })

  it('restore keeps the local record when the served wrap will not open', async () => {
    const add = vi.fn<AddWrap>().mockResolvedValue({ id: 'wrap-1' })
    mockWrapsApi(add, vi.fn<DeleteWrap>())
    await enrolDeviceKey('a@b.com', masterKey())
    // a wrap with our id but tampered ciphertext → AEAD open fails → fall back to the floor, and keep the key:
    // one server answer never deletes local key material (uc.account.remember-this-device E2).
    const tampered = material([
      { id: 'wrap-1', method: 'DEVICE_KEY', wrappedKey: 'dGFtcGVyZWQ=', kdfParams: 'aXYtMTJieXRlcw==' },
    ])
    expect(await restoreDeviceKey(tampered)).toBeNull()
    expect(await isRemembered()).toBe(true)
  })

  // ADR 0088 decision 5: `core.remember-device.passwordless` off withholds the DEVICE_KEY wrap from the
  // key material, deletes nothing, and serves it again once switched back on. The PASSWORD wrap stays served.
  it('a withheld wrap keeps the record, and the same wrap served again opens it with no new enrolment', async () => {
    const add = vi.fn<AddWrap>().mockResolvedValue({ id: 'wrap-1' })
    const del = vi.fn<DeleteWrap>()
    mockWrapsApi(add, del)
    await enrolDeviceKey('a@b.com', masterKey())
    const served = materialFrom(add, 'wrap-1')
    const withheld = material([{ id: 'pw-1', method: 'PASSWORD', wrappedKey: 'AA==', kdfParams: 'AA==' }])

    expect(await restoreDeviceKey(withheld)).toBeNull()
    expect(await rememberedWrapId('a@b.com')).toBe('wrap-1')

    expect((await restoreDeviceKey(served))?.masterKey).toEqual(masterKey())
    expect(add).toHaveBeenCalledOnce() // the enrolment above, nothing since
    expect(del).not.toHaveBeenCalled()
  })

  it('an explicit forget still clears a record whose wrap is withheld', async () => {
    const add = vi.fn<AddWrap>().mockResolvedValue({ id: 'wrap-1' })
    const del = vi.fn<DeleteWrap>().mockResolvedValue(undefined)
    mockWrapsApi(add, del)
    await enrolDeviceKey('a@b.com', masterKey())
    expect(await restoreDeviceKey(material())).toBeNull() // withheld, kept

    await clearDeviceKey()

    // The server still holds the withheld wrap and lets it be deleted, so the forget reaches both halves.
    expect(del).toHaveBeenCalledWith({ id: 'wrap-1' })
    expect(await isRemembered()).toBe(false)
  })

  it('clear removes the server wrap and drops the local key', async () => {
    const add = vi.fn<AddWrap>().mockResolvedValue({ id: 'wrap-1' })
    const del = vi.fn<DeleteWrap>().mockResolvedValue(undefined)
    mockWrapsApi(add, del)
    await enrolDeviceKey('a@b.com', masterKey())

    await clearDeviceKey()

    expect(del).toHaveBeenCalledWith({ id: 'wrap-1' })
    expect(await isRemembered()).toBe(false)
  })

  it('clear with deleteServerWrap=false drops only the local key (no session)', async () => {
    const add = vi.fn<AddWrap>().mockResolvedValue({ id: 'wrap-1' })
    const del = vi.fn<DeleteWrap>()
    mockWrapsApi(add, del)
    await enrolDeviceKey('a@b.com', masterKey())

    await clearDeviceKey(false)

    expect(del).not.toHaveBeenCalled()
    expect(await isRemembered()).toBe(false)
  })
})

// uc.account.change-email: the record's email is the one thing boot learns the account's address from.
describe('readdressRemembered (uc.account.change-email)', () => {
  it('moves the record to the new address, keeping its key and its wrap', async () => {
    const add = vi.fn<AddWrap>().mockResolvedValue({ id: 'wrap-1' })
    mockWrapsApi(add, vi.fn<DeleteWrap>())
    await enrolDeviceKey('old@example.com', masterKey())

    await readdressRemembered(' Old@Example.com', 'New@Example.com ')

    expect(await rememberedWrapId('new@example.com')).toBe('wrap-1')
    expect(await rememberedWrapId('old@example.com')).toBeNull()
    const restored = await restoreDeviceKey(materialFrom(add, 'wrap-1'))
    expect(restored?.email).toBe('new@example.com')
    expect(restored?.masterKey).toEqual(masterKey())
  })

  it('leaves another account’s record alone', async () => {
    mockWrapsApi(vi.fn<AddWrap>().mockResolvedValue({ id: 'wrap-2' }), vi.fn<DeleteWrap>())
    await enrolDeviceKey('someone@example.com', masterKey())
    await readdressRemembered('old@example.com', 'new@example.com')
    expect(await rememberedWrapId('someone@example.com')).toBe('wrap-2')
  })
})
