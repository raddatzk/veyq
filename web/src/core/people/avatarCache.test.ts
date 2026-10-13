// @vitest-environment happy-dom
// The profile-picture cache (ADR 0084, uc.spaces.set-profile-picture): fetch ONLY for a non-zero, changed `avatarVersion`, send
// If-None-Match, open under K_tenant, one object URL per user revoked on a version change and on reset, concurrent syncs share a
// request, 404 clears (E4), an unopenable blob or a non-image shows the letter (E3). Sealing is REAL (the blob is opened under the
// test key at the member's address); only the generated client is mocked.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { httpError } from '../../test/httpError'
import { fakeApi, type ApiMocks } from '../../test/apiMock'
import { tenantsApi } from '../../api/client'
import type { TenantsApi } from '../../api/generated/tenants'

vi.mock('../../api/client', () => ({ tenantsApi: vi.fn() }))
vi.mock('../../app/sessionRefresh', () => ({ withRefresh: (f: () => unknown) => f() }))

import { syncAvatars, avatarUrlOf, resetAvatars, type AvatarSource } from './avatarCache'
import { sealAvatar } from '../../features/account/profile/memberAvatar'

const KEY = new Uint8Array(32).fill(5)
const SCOPE = '5c09e000-0000-4000-8000-0000000000c1'
const U1 = 'aaaaaaaa-0000-4000-8000-000000000001'
const U2 = 'aaaaaaaa-0000-4000-8000-000000000002'
const U3 = 'aaaaaaaa-0000-4000-8000-000000000003'
const SOURCE: AvatarSource = { tenantId: 'tenant-1', scopeId: SCOPE, key: KEY }
// a minimal PNG header: what `sniffImage` recognises
const PNG = new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 0])
const sealedFor = (userId: string, bytes: Uint8Array = PNG) => ({ sealedAvatar: sealAvatar(KEY, { scopeId: SCOPE, userId }, bytes) })

let n = 0
let getMemberAvatar: ApiMocks<TenantsApi, 'getMemberAvatar'>['getMemberAvatar']
beforeEach(() => {
  vi.clearAllMocks()
  getMemberAvatar = fakeApi(tenantsApi, { getMemberAvatar: vi.fn<TenantsApi['getMemberAvatar']>() }).getMemberAvatar
  resetAvatars()
  n = 0
  vi.stubGlobal('URL', Object.assign(URL, {
    createObjectURL: vi.fn(() => `blob:avatar-${++n}`),
    revokeObjectURL: vi.fn(),
  }))
})

describe('syncAvatars', () => {
  it('fetches nothing for avatarVersion 0 and leaves the letter (no request at all)', async () => {
    await syncAvatars(SOURCE, [{ userId: U1, avatarVersion: 0 }])
    expect(getMemberAvatar).not.toHaveBeenCalled()
    expect(avatarUrlOf(U1)).toBeNull()
  })

  it('fetches a non-zero version once, opens it and exposes one object URL', async () => {
    getMemberAvatar.mockResolvedValue(sealedFor(U1))
    await syncAvatars(SOURCE, [{ userId: U1, avatarVersion: 3 }])
    expect(getMemberAvatar).toHaveBeenCalledTimes(1)
    expect(getMemberAvatar).toHaveBeenCalledWith({ tenantId: 'tenant-1', userId: U1, ifNoneMatch: undefined })
    expect(avatarUrlOf(U1)).toBe('blob:avatar-1')
  })

  it('does not fetch again while the version is unchanged (version-gated)', async () => {
    getMemberAvatar.mockResolvedValue(sealedFor(U1))
    await syncAvatars(SOURCE, [{ userId: U1, avatarVersion: 3 }])
    await syncAvatars(SOURCE, [{ userId: U1, avatarVersion: 3 }])
    expect(getMemberAvatar).toHaveBeenCalledTimes(1)
    expect(URL.createObjectURL).toHaveBeenCalledTimes(1)
  })

  it('refetches on a new version with If-None-Match of the held one, and revokes the old URL', async () => {
    getMemberAvatar.mockResolvedValue(sealedFor(U1))
    await syncAvatars(SOURCE, [{ userId: U1, avatarVersion: 3 }])
    await syncAvatars(SOURCE, [{ userId: U1, avatarVersion: 4 }])
    expect(getMemberAvatar).toHaveBeenLastCalledWith({ tenantId: 'tenant-1', userId: U1, ifNoneMatch: '"3"' })
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:avatar-1')
    expect(avatarUrlOf(U1)).toBe('blob:avatar-2')
  })

  it('keeps the held picture on a 304 and settles the version', async () => {
    getMemberAvatar.mockResolvedValueOnce(sealedFor(U1))
    await syncAvatars(SOURCE, [{ userId: U1, avatarVersion: 3 }])
    getMemberAvatar.mockRejectedValueOnce(httpError(304))
    await syncAvatars(SOURCE, [{ userId: U1, avatarVersion: 4 }])
    expect(avatarUrlOf(U1)).toBe('blob:avatar-1')
    expect(URL.revokeObjectURL).not.toHaveBeenCalled()
    await syncAvatars(SOURCE, [{ userId: U1, avatarVersion: 4 }]) // settled: no third request
    expect(getMemberAvatar).toHaveBeenCalledTimes(2)
  })

  it('a 404 avatar_not_found clears the entry (E4) and is not asked again for that version', async () => {
    getMemberAvatar.mockResolvedValueOnce(sealedFor(U1))
    await syncAvatars(SOURCE, [{ userId: U1, avatarVersion: 3 }])
    getMemberAvatar.mockRejectedValueOnce(httpError(404, 'avatar_not_found'))
    await syncAvatars(SOURCE, [{ userId: U1, avatarVersion: 4 }])
    expect(avatarUrlOf(U1)).toBeNull()
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:avatar-1')
    await syncAvatars(SOURCE, [{ userId: U1, avatarVersion: 4 }])
    expect(getMemberAvatar).toHaveBeenCalledTimes(2)
  })

  it('drops a held picture when the directory says the member has none any more (version 0)', async () => {
    getMemberAvatar.mockResolvedValue(sealedFor(U1))
    await syncAvatars(SOURCE, [{ userId: U1, avatarVersion: 3 }])
    await syncAvatars(SOURCE, [{ userId: U1, avatarVersion: 0 }])
    expect(avatarUrlOf(U1)).toBeNull()
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:avatar-1')
  })

  it('shows the letter for a blob that does not open (E3: another member\'s blob on this row)', async () => {
    getMemberAvatar.mockResolvedValue(sealedFor(U3))
    await syncAvatars(SOURCE, [{ userId: U1, avatarVersion: 1 }])
    expect(avatarUrlOf(U1)).toBeNull()
    await syncAvatars(SOURCE, [{ userId: U1, avatarVersion: 1 }]) // and it is not retried on every read
    expect(getMemberAvatar).toHaveBeenCalledTimes(1)
  })

  it('shows the letter for opened bytes that are no image (they are never put in an object URL)', async () => {
    getMemberAvatar.mockResolvedValue(sealedFor(U1, new Uint8Array([60, 115, 118, 103, 62, 0, 0, 0, 0, 0, 0, 0])))
    await syncAvatars(SOURCE, [{ userId: U1, avatarVersion: 1 }])
    expect(avatarUrlOf(U1)).toBeNull()
    expect(URL.createObjectURL).not.toHaveBeenCalled()
  })

  it('leaves the entry and retries on the next sync after a network failure', async () => {
    getMemberAvatar.mockRejectedValueOnce(new Error('offline'))
    await syncAvatars(SOURCE, [{ userId: U1, avatarVersion: 1 }])
    expect(avatarUrlOf(U1)).toBeNull()
    getMemberAvatar.mockResolvedValueOnce(sealedFor(U1))
    await syncAvatars(SOURCE, [{ userId: U1, avatarVersion: 1 }])
    expect(avatarUrlOf(U1)).toBe('blob:avatar-1')
  })

  it('shares one request between concurrent syncs of the same (user, version)', async () => {
    let release!: () => void
    getMemberAvatar.mockImplementation(() => new Promise((resolve) => { release = () => resolve(sealedFor(U1)) }))
    const a = syncAvatars(SOURCE, [{ userId: U1, avatarVersion: 2 }])
    const b = syncAvatars(SOURCE, [{ userId: U1, avatarVersion: 2 }])
    await Promise.resolve()
    release()
    await Promise.all([a, b])
    expect(getMemberAvatar).toHaveBeenCalledTimes(1)
    expect(URL.createObjectURL).toHaveBeenCalledTimes(1)
  })

  it('reads no picture without the tenant key (the letter shows)', async () => {
    await syncAvatars({ ...SOURCE, key: null }, [{ userId: U1, avatarVersion: 2 }])
    expect(getMemberAvatar).not.toHaveBeenCalled()
  })
})

describe('resetAvatars (sign-out)', () => {
  it('revokes every object URL and forgets the versions', async () => {
    getMemberAvatar.mockImplementation(async ({ userId }: { userId: string }) => sealedFor(userId))
    await syncAvatars(SOURCE, [{ userId: U1, avatarVersion: 1 }, { userId: U2, avatarVersion: 1 }])
    resetAvatars()
    expect(URL.revokeObjectURL).toHaveBeenCalledTimes(2)
    expect(avatarUrlOf(U1)).toBeNull()
    await syncAvatars(SOURCE, [{ userId: U1, avatarVersion: 1 }])
    expect(getMemberAvatar).toHaveBeenCalledTimes(3) // fetched afresh after the reset
  })

  it('lets a fetch that was in flight at the reset land nowhere', async () => {
    let release!: () => void
    getMemberAvatar.mockImplementation(() => new Promise((resolve) => { release = () => resolve(sealedFor(U1)) }))
    const pending = syncAvatars(SOURCE, [{ userId: U1, avatarVersion: 1 }])
    await Promise.resolve()
    resetAvatars()
    release()
    await pending
    expect(avatarUrlOf(U1)).toBeNull()
    expect(URL.createObjectURL).not.toHaveBeenCalled()
  })
})
