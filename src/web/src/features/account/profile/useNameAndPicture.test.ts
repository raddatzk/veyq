// @vitest-environment happy-dom
// The one editor model for name + picture (ADR 0084 decision 6). The picture is sealed under K_tenant at the member's OWN address
// (the blob the PUT carries is opened here under the test key, not compared to a string); a kept picture sends nothing, a removed
// one sends DELETE; afterwards the directory is re-read so every avatar follows the bumped version.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import * as v1 from '@veyq/crypto/v1'
import { effectScope } from 'vue'
import { memberProfile } from '../../../test/models'

vi.mock('./profile', async (orig) => ({
  ...(await orig<typeof import('./profile')>()),
  getMyProfile: vi.fn(),
  openOwnNameKey: vi.fn(),
  setMyAvatar: vi.fn(),
  clearMyAvatar: vi.fn(),
}))
vi.mock('../../../core/people/directory', () => ({
  avatarUrlOf: vi.fn(() => null),
  loadTenantPeople: vi.fn(() => Promise.resolve(new Map())),
  refreshPeopleDirectory: vi.fn(() => Promise.resolve()),
}))

import { useNameAndPicture } from './useNameAndPicture'
import { getMyProfile, openOwnNameKey, setMyAvatar, clearMyAvatar, sealName } from './profile'
import { openAvatar } from './memberAvatar'
import { avatarUrlOf, refreshPeopleDirectory } from '../../../core/people/directory'
import { epochsOf } from '../../spaces/epochsTestUtil'

const K = v1.randomBytes(32)
const ADDRESS = { scopeId: '5c09e000-0000-4000-8000-0000000000f1', userId: '5c09e000-0000-4000-8000-0000000000f2' }

function model() {
  return effectScope().run(() => useNameAndPicture())!
}

beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(openOwnNameKey).mockResolvedValue({ key: K, epochs: epochsOf(K), tenantId: ADDRESS.scopeId, address: ADDRESS })
  vi.mocked(getMyProfile).mockResolvedValue(memberProfile())
  vi.stubGlobal('URL', Object.assign(URL, { createObjectURL: vi.fn(() => 'blob:preview'), revokeObjectURL: vi.fn() }))
})

describe('useNameAndPicture', () => {
  it('loads the name from the sealed profile and knows whether the server holds a picture', async () => {
    const m = model()
    await m.load(memberProfile({ sealedName: sealName(K, ADDRESS, { firstName: 'Anna', lastName: 'Meyer' }), avatarRevision: 4 }))
    expect([m.firstName, m.lastName, m.hasKey, m.hasPicture, m.meId]).toEqual(['Anna', 'Meyer', true, true, ADDRESS.userId])
  })

  it('a chosen picture is sealed under K_tenant at (tenant scope, own id) and PUT; the directory is re-read afterwards', async () => {
    const m = model()
    await m.load(memberProfile())
    m.choose(new Uint8Array([1, 2, 3]))
    expect(m.draftKind).toBe('set')
    expect(m.pictureUrl).toBe('blob:preview')
    expect(m.hasPicture).toBe(true)
    await m.savePicture()
    expect(setMyAvatar).toHaveBeenCalledTimes(1)
    expect([...openAvatar(K, ADDRESS, vi.mocked(setMyAvatar).mock.calls[0][0])!]).toEqual([1, 2, 3])
    expect(clearMyAvatar).not.toHaveBeenCalled()
    expect(refreshPeopleDirectory).toHaveBeenCalledTimes(1)
    expect(m.draftKind).toBe('keep')
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:preview')
  })

  it('a removed picture is DELETEd, not PUT; the draft draws the letter meanwhile', async () => {
    const m = model()
    await m.load(memberProfile({ avatarRevision: 2 }))
    vi.mocked(avatarUrlOf).mockReturnValue('blob:held')
    expect(m.pictureUrl).toBe('blob:held')
    m.remove()
    expect(m.pictureUrl).toBeNull()
    expect(m.hasPicture).toBe(false)
    await m.savePicture()
    expect(clearMyAvatar).toHaveBeenCalledTimes(1)
    expect(setMyAvatar).not.toHaveBeenCalled()
  })

  it('an untouched picture sends nothing at all', async () => {
    const m = model()
    await m.load(memberProfile({ avatarRevision: 2 }))
    await m.savePicture()
    expect(setMyAvatar).not.toHaveBeenCalled()
    expect(clearMyAvatar).not.toHaveBeenCalled()
    expect(refreshPeopleDirectory).not.toHaveBeenCalled()
  })

  it('a failed write throws and keeps the draft for a retry (nothing is lost)', async () => {
    vi.mocked(setMyAvatar).mockRejectedValue(new Error('offline'))
    const m = model()
    await m.load(memberProfile())
    m.choose(new Uint8Array([5]))
    await expect(m.savePicture()).rejects.toThrow('offline')
    expect(m.draftKind).toBe('set')
    expect(refreshPeopleDirectory).not.toHaveBeenCalled()
  })

  it('without the tenant key nothing can be sealed: no key, no sealed name, saving a chosen picture throws', async () => {
    vi.mocked(openOwnNameKey).mockResolvedValue(null)
    const m = model()
    await m.load(memberProfile())
    expect(m.hasKey).toBe(false)
    expect(m.sealedName()).toBeUndefined()
    m.choose(new Uint8Array([5]))
    await expect(m.savePicture()).rejects.toThrow('no_tenant_key')
    expect(setMyAvatar).not.toHaveBeenCalled()
  })
})
