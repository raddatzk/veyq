// A member's own client moves its name and picture forward to the tenant's current epoch (ADR 0023 § The epoch of a sealed
// name, inbox #573). REAL crypto and a REAL epoch chain: what the step writes is opened here under each epoch key, never
// compared to a string. Only the transport is faked (the profile reads and writes, the picture read).
import { describe, it, expect, vi, beforeEach } from 'vitest'
import * as v1 from '@veyq/crypto/v1'
import { b64std } from '@veyq/crypto/base64'
import { stubApi } from '../../../test/apiMock'
import { memberProfile } from '../../../test/models'
import type { MemberProfile } from '../../../api/generated/account'

vi.mock('../../../api/client', async (orig) => ({ ...(await orig<typeof import('../../../api/client')>()), tenantsApi: vi.fn() }))
vi.mock('../../../app/sessionRefresh', () => ({ withRefresh: (f: () => unknown) => f() }))
vi.mock('./profile', async (orig) => ({
  ...(await orig<typeof import('./profile')>()),
  getMyProfile: vi.fn(),
  updateMyProfile: vi.fn(),
  setMyAvatar: vi.fn(),
}))

import { tenantsApi } from '../../../api/client'
import { getMyProfile, setMyAvatar, updateMyProfile } from './profile'
import { deriveEpochKeys, epochLinkContext, type EpochKeys } from '../../spaces/epochs'
import { sealName, unsealName } from './memberName'
import { openAvatar, sealAvatar } from './memberAvatar'
import { carryProfileForward, forgetCarriedProfiles, type HeldTenant } from './carryProfileForward'

const TENANT = '5c09e000-0000-4000-8000-0000000005a3'
const ME = '5c09e000-0000-4000-8000-00000000c001'
const ADDRESS = { scopeId: TENANT, userId: ME }
const E0 = v1.randomBytes(32)
const E1 = v1.randomBytes(32)
const PNG = new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3, 4])
const NAME = { firstName: 'Anna', lastName: 'Meyer' }

const atEpoch1 = (): EpochKeys =>
  deriveEpochKeys(TENANT, E1.slice(), 1, [{ epoch: 1, wrappedPrev: b64std(v1.seal(epochLinkContext(TENANT, 1), E1, E0)) }])
const tenant = (epochs: EpochKeys = atEpoch1()): HeldTenant => ({ tenantId: TENANT, scopeId: TENANT, epochs })

/** The stored profile, read once per `getMyProfile` call in turn (the step reads it, then again right before a write). */
function profiles(...seq: MemberProfile[]) {
  const mock = vi.mocked(getMyProfile)
  for (const p of seq) mock.mockResolvedValueOnce(p)
  mock.mockResolvedValue(seq.at(-1)!)
}

beforeEach(() => {
  vi.clearAllMocks()
  forgetCarriedProfiles()
  vi.mocked(updateMyProfile).mockResolvedValue(memberProfile())
  vi.mocked(setMyAvatar).mockResolvedValue()
  stubApi(tenantsApi, { getMemberAvatar: { sealedAvatar: sealAvatar(E0, ADDRESS, PNG) } })
})

describe('carryProfileForward', () => {
  it('re-seals a name and picture left under an older epoch under the current one - the same name, the same bytes', async () => {
    profiles(memberProfile({ sealedName: sealName(E0, ADDRESS, NAME), avatarRevision: 4 }))

    expect(await carryProfileForward(tenant(), ME)).toEqual({ name: true, picture: true })

    const sealedName = vi.mocked(updateMyProfile).mock.calls[0][0].sealedName!
    expect(vi.mocked(updateMyProfile).mock.calls[0][0]).toEqual({ sealedName }) // the name alone: no username, no preference
    expect(unsealName(E1, ADDRESS, sealedName)).toEqual(NAME)
    expect(unsealName(E0, ADDRESS, sealedName), 'no longer under the old epoch').toBeNull()
    const sealedAvatar = vi.mocked(setMyAvatar).mock.calls[0][0]
    expect(openAvatar(E1, ADDRESS, sealedAvatar)).toEqual(PNG)
    expect(openAvatar(E0, ADDRESS, sealedAvatar)).toBeNull()
  })

  it('writes nothing when the name and picture already open under the current epoch', async () => {
    stubApi(tenantsApi, { getMemberAvatar: { sealedAvatar: sealAvatar(E1, ADDRESS, PNG) } })
    profiles(memberProfile({ sealedName: sealName(E1, ADDRESS, NAME), avatarRevision: 4 }))
    expect(await carryProfileForward(tenant(), ME)).toEqual({ name: false, picture: false })
    expect(updateMyProfile).not.toHaveBeenCalled()
    expect(setMyAvatar).not.toHaveBeenCalled()
  })

  it('reads nothing at epoch 0 (nothing can be older) and nothing for a profile with neither a name nor a picture', async () => {
    await carryProfileForward(tenant({ current: 0, keys: new Map([[0, E0.slice()]]) }), ME)
    expect(getMyProfile).not.toHaveBeenCalled()
    profiles(memberProfile())
    await carryProfileForward(tenant(), ME)
    expect(vi.mocked(tenantsApi)).not.toHaveBeenCalled()
    expect(updateMyProfile).not.toHaveBeenCalled()
  })

  it('a newer save wins: what another device stored after the read is never overwritten', async () => {
    const old = memberProfile({ sealedName: sealName(E0, ADDRESS, NAME), avatarRevision: 4 })
    const newer = memberProfile({ sealedName: sealName(E1, ADDRESS, { firstName: 'Anna', lastName: 'Neu' }), avatarRevision: 5 })
    profiles(old, newer)
    expect(await carryProfileForward(tenant(), ME)).toEqual({ name: false, picture: false })
    expect(updateMyProfile).not.toHaveBeenCalled()
    expect(setMyAvatar).not.toHaveBeenCalled()
  })

  it('runs once per tenant, member and epoch in a document; a failed run is tried again on the next entry', async () => {
    vi.mocked(getMyProfile).mockRejectedValueOnce(new Error('offline'))
    expect(await carryProfileForward(tenant(), ME)).toEqual({ name: false, picture: false })
    profiles(memberProfile({ sealedName: sealName(E0, ADDRESS, NAME) }))
    expect((await carryProfileForward(tenant(), ME)).name, 'retried after the failure').toBe(true)
    vi.mocked(getMyProfile).mockClear()
    await carryProfileForward(tenant(), ME)
    expect(getMyProfile, 'done for this epoch').not.toHaveBeenCalled()
  })

  it('leaves alone what none of the held keys opens (a value it cannot read is not its to move)', async () => {
    const stranger = v1.randomBytes(32)
    profiles(memberProfile({ sealedName: sealName(stranger, ADDRESS, NAME) }))
    expect((await carryProfileForward(tenant(), ME)).name).toBe(false)
    expect(updateMyProfile).not.toHaveBeenCalled()
  })
})
