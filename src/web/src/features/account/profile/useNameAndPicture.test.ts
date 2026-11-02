// @vitest-environment happy-dom
// The one editor model for name + picture (ADR 0084 decision 6). The picture is sealed under K_tenant at the member's OWN address
// (the blob the PUT carries is opened here under the test key, not compared to a string); a kept picture sends nothing, a removed
// one sends DELETE; afterwards the directory is re-read so every avatar follows the bumped version.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import * as v1 from '@veyq/crypto/v1'
import { effectScope } from 'vue'
import { memberProfile, ownNameKey } from '../../../test/models'

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
import {
  getMyProfile, openOwnNameKey, setMyAvatar, clearMyAvatar, sealName, openMemberName, type OwnNameKey, type SealedMemberName,
} from './profile'
import { openMemberAvatar, type SealedMemberAvatar } from './memberAvatar'
import { httpError } from '../../../test/httpError'
import { avatarUrlOf, refreshPeopleDirectory } from '../../../core/people/directory'

const K = v1.randomBytes(32)
const ADDRESS = { scopeId: '5c09e000-0000-4000-8000-0000000000f1', userId: '5c09e000-0000-4000-8000-0000000000f2' }
/** A name or a picture the editor sent, opened as every reader opens it: through its value key's wrap under [key] at [epoch]. */
const nameUnder = (key: Uint8Array, n: SealedMemberName, epoch = 0) => openMemberName({ epoch, key }, ADDRESS, n.sealedName, n.wrappedNameValueKey)
const pictureUnder = (key: Uint8Array, p: SealedMemberAvatar, epoch = 0) =>
  openMemberAvatar({ epoch, key }, ADDRESS, p.sealedAvatar, p.wrappedAvatarValueKey)

function model() {
  return effectScope().run(() => useNameAndPicture())!
}

beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(openOwnNameKey).mockImplementation(async () => ownNameKey(K, ADDRESS))
  vi.mocked(getMyProfile).mockResolvedValue(memberProfile())
  vi.stubGlobal('URL', Object.assign(URL, { createObjectURL: vi.fn(() => 'blob:preview'), revokeObjectURL: vi.fn() }))
})

describe('useNameAndPicture', () => {
  it('loads the name from the sealed profile and knows whether the server holds a picture', async () => {
    const m = model()
    await m.load(memberProfile({ ...sealName({ epoch: 0, key: K }, ADDRESS, { firstName: 'Anna', lastName: 'Meyer' }), avatarRevision: 4 }))
    expect([m.firstName, m.lastName, m.hasKey, m.hasPicture, m.meId]).toEqual(['Anna', 'Meyer', true, true, ADDRESS.userId])
  })

  it('a chosen picture is sealed under a fresh K_v, wrapped under K_tenant, at (tenant scope, own id) and PUT; the directory is re-read afterwards', async () => {
    const m = model()
    await m.load(memberProfile())
    m.choose(new Uint8Array([1, 2, 3]))
    expect(m.draftKind).toBe('set')
    expect(m.pictureUrl).toBe('blob:preview')
    expect(m.hasPicture).toBe(true)
    await m.savePicture()
    expect(setMyAvatar).toHaveBeenCalledTimes(1)
    expect([...pictureUnder(K, vi.mocked(setMyAvatar).mock.calls[0][0])!]).toEqual([1, 2, 3])
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

  describe('the tenant keys it holds (inbox #574)', () => {
    const zeroed = (own: OwnNameKey) => own.epochs.key.every((b) => b === 0)
    let handed: OwnNameKey[]
    beforeEach(() => {
      handed = []
      vi.mocked(setMyAvatar).mockResolvedValue(undefined) // an earlier case leaves it failing
      vi.mocked(openOwnNameKey).mockImplementation(async () => {
        const own = ownNameKey(K, ADDRESS)
        handed.push(own)
        return own
      })
    })

    it('keeps them while the editor is open — a save still seals — and zeroes them when the editor goes away', async () => {
      const scope = effectScope()
      const m = scope.run(() => useNameAndPicture())!
      await m.load(memberProfile())
      m.choose(new Uint8Array([7]))
      await m.savePicture()
      expect([...pictureUnder(K, vi.mocked(setMyAvatar).mock.calls[0][0])!]).toEqual([7])
      expect(m.sealedName()).toBeTruthy()
      expect(zeroed(handed[0])).toBe(false)
      scope.stop()
      expect(zeroed(handed[0])).toBe(true)
    })

    it('a second load zeroes the keys the first one opened', async () => {
      const m = model()
      await m.load(memberProfile())
      await m.load(memberProfile())
      expect(handed.map(zeroed)).toEqual([true, false])
    })

    it('keys that arrive after the editor went away are zeroed at once, and fill nothing', async () => {
      let arrive!: () => void
      vi.mocked(openOwnNameKey).mockImplementation(
        () =>
          new Promise((resolve) => {
            arrive = () => {
              const own = ownNameKey(K, ADDRESS)
              handed.push(own)
              resolve(own)
            }
          }),
      )
      const scope = effectScope()
      const m = scope.run(() => useNameAndPicture())!
      const loading = m.load(memberProfile({ ...sealName({ epoch: 0, key: K }, ADDRESS, { firstName: 'Anna', lastName: 'Meyer' }) }))
      await Promise.resolve()
      scope.stop()
      arrive()
      await loading
      expect(zeroed(handed[0])).toBe(true)
      expect(m.firstName).toBe('')
    })
  })

  describe('a write states the tenant epoch and seals once more after `409 stale_epoch` (ADR 0115 decision 3)', () => {
    const K1 = v1.randomBytes(32)
    const K2 = v1.randomBytes(32)
    /** The tenant key as `openOwnNameKey` hands it at `epoch` — a fresh copy per open, as its caller zeroes it. */
    const at = (key: Uint8Array, epoch: number): OwnNameKey => {
      const k = key.slice()
      return { key: k, epochs: { epoch, key: k }, tenantId: ADDRESS.scopeId, address: ADDRESS }
    }
    let handed: OwnNameKey[]
    beforeEach(() => {
      handed = []
      // The editor opens at epoch 1; a rotation then replaces the key with epoch 2's, which every later open hands over.
      const opens = [at(K1, 1)]
      vi.mocked(openOwnNameKey).mockImplementation(async () => {
        const own = opens.shift() ?? at(K2, 2)
        handed.push(own)
        return own
      })
    })
    const stale = () => httpError(409, 'stale_epoch')

    it('the name: the first send states epoch 1 under its key, the second — after a re-open — epoch 2 under the new key', async () => {
      const m = model()
      await m.load(memberProfile())
      m.firstName = 'Anna'
      m.lastName = 'Meyer'
      const send = vi.fn(async (_name: (SealedMemberName & { epoch: number }) | undefined) => 'saved')
      send.mockRejectedValueOnce(stale())
      expect(await m.saveName(send)).toBe('saved')
      expect(send).toHaveBeenCalledTimes(2)
      const [first, second] = send.mock.calls.map(([n]) => n!)
      expect(first.epoch).toBe(1)
      expect(nameUnder(K1, first, 1)).toEqual({ firstName: 'Anna', lastName: 'Meyer' })
      expect(second.epoch).toBe(2)
      expect(nameUnder(K2, second, 2)).toEqual({ firstName: 'Anna', lastName: 'Meyer' })
      expect(nameUnder(K1, second, 1), 'not under the replaced key').toBeNull()
      expect(second.wrappedNameValueKey, 'a fresh value key per write').not.toBe(first.wrappedNameValueKey)
      expect(openOwnNameKey).toHaveBeenCalledTimes(2) // the load, and the one re-open
      expect(handed[0].key.every((b) => b === 0), 'the replaced key is zeroed').toBe(true)
    })

    it('the picture: the same — the second PUT states epoch 2 and opens under the new key only', async () => {
      vi.mocked(setMyAvatar).mockReset()
      vi.mocked(setMyAvatar).mockRejectedValueOnce(stale()).mockResolvedValue(undefined)
      const m = model()
      await m.load(memberProfile())
      m.choose(new Uint8Array([9, 9]))
      await m.savePicture()
      expect(setMyAvatar).toHaveBeenCalledTimes(2)
      const [[firstBlob, firstEpoch], [secondBlob, secondEpoch]] = vi.mocked(setMyAvatar).mock.calls
      expect(firstEpoch).toBe(1)
      expect([...pictureUnder(K1, firstBlob, 1)!]).toEqual([9, 9])
      expect(secondEpoch).toBe(2)
      expect([...pictureUnder(K2, secondBlob, 2)!]).toEqual([9, 9])
      expect(pictureUnder(K1, secondBlob, 1)).toBeNull()
      expect(m.draftKind).toBe('keep')
    })

    it('bounded: a second `stale_epoch` is thrown after two sends; any other refusal is never re-sealed', async () => {
      const m = model()
      await m.load(memberProfile())
      const always = vi.fn(async (): Promise<string> => {
        throw stale()
      })
      await expect(m.saveName(always)).rejects.toThrow('http 409 stale_epoch')
      expect(always).toHaveBeenCalledTimes(2)

      const other = vi.fn(async (): Promise<string> => {
        throw httpError(409, 'username_taken')
      })
      const opened = vi.mocked(openOwnNameKey).mock.calls.length
      await expect(m.saveName(other)).rejects.toThrow('username_taken')
      expect(other).toHaveBeenCalledTimes(1)
      expect(vi.mocked(openOwnNameKey).mock.calls.length).toBe(opened) // no re-open
    })
  })
})
