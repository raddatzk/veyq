import 'fake-indexeddb/auto'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { IDBFactory } from 'fake-indexeddb'
import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'

// The last holder's hand-over to the Server Owner (ADR 0101 decision 9, uc.account.erase-account A8) against the REAL
// trust layer and the real suite, as `unlockModeration.anchor.test.ts` runs the moderators' delivery: the Server Owner is
// anchored by the head the erasure preview served, through the chain the server serves to a moderator, and what is
// sealed opens to the very `M_n` this account holds — under the Server Owner's identity and no other.
vi.mock('../../api/client', () => ({ moderationApi: vi.fn(), accountIdentityPinsApi: vi.fn(), keyChainApi: vi.fn() }))
vi.mock('../../app/sessionRefresh', () => ({ withRefresh: (f: () => unknown) => f() }))

import { moderationApi } from '../../api/client'
import type { ModerationEpochGrant } from '../../api/generated/moderation'
import { fakeApi } from '../../test/apiMock'
import { fakePinServer } from '../../test/pinStore'
import { bundleB64, chainKeys, fakeKeyChains, headOf, servedIdentity, step } from '../../test/keyChain'
import { clearVault, setUnlocked } from '../../app/vault'
import { sealForServerOwner, ServerOwnerDeliveryRefused } from './serverOwnerDelivery'

const me = v1.generateIdentity()
const MK = new Uint8Array(32).fill(9)
const OWNER = '5b6c7c3f-0e2a-4b1d-8a6e-8b2c1f2d3e4a'
const m1 = v1.generateModerationKey()
const m2 = v1.generateModerationKey()

const grantOf = (epoch: number, m: Uint8Array): ModerationEpochGrant => {
  const bundle = v1.moderationBundle(m)
  return {
    epoch, kemPublicKey: b64std(bundle.kem), signPublicKey: b64std(bundle.sign),
    sealedGrant: b64std(v1.sealTo({ purpose: 'moderation-grant', epoch }, me.pub.kem, m)),
  }
}

let chains: ReturnType<typeof fakeKeyChains>
beforeEach(() => {
  globalThis.indexedDB = new IDBFactory()
  fakePinServer()
  chains = fakeKeyChains()
  fakeApi(moderationApi, {
    listMyModerationGrants: async () => ({ currentEpoch: 2, epochs: [grantOf(1, m1), grantOf(2, m2)] }),
  })
  setUnlocked(MK.slice(), { kemSeed: me.priv.kemSeed.slice(), sign: me.priv.sign.slice() }, 'device')
})

describe('sealForServerOwner', () => {
  it('seals every epoch it is asked for to the anchored Server Owner, opening to the key this account holds', async () => {
    const ownerKeys = chainKeys()
    const chain = chains.born(OWNER, ownerKeys)
    const upload = await sealForServerOwner({ serverOwnerId: OWNER, ...servedIdentity(chain), epochs: [1, 2] })
    expect(chains.getKeyChain).toHaveBeenCalledWith({ userId: OWNER })
    expect(upload.identityPublicKey).toBe(bundleB64(ownerKeys))
    expect(upload.grants.map((g) => g.epoch)).toEqual([1, 2])
    const opened = upload.grants.map((g) =>
      v1.openAs({ purpose: 'moderation-grant', epoch: g.epoch }, ownerKeys.identity.priv.kemSeed, fromB64std(g.sealedGrant)),
    )
    expect(opened).toEqual([m1, m2])
    // Sealed to the Server Owner, not to this account: its own identity opens none of them.
    expect(() =>
      v1.openAs({ purpose: 'moderation-grant', epoch: 1 }, me.priv.kemSeed, fromB64std(upload.grants[0].sealedGrant)),
    ).toThrow()
  })

  it('only the epochs the preview names', async () => {
    const chain = chains.born(OWNER)
    const upload = await sealForServerOwner({ serverOwnerId: OWNER, ...servedIdentity(chain), epochs: [2] })
    expect(upload.grants.map((g) => g.epoch)).toEqual([2])
  })

  it('a Server Owner whose chain the server does not serve is not sealed to', async () => {
    const keys = chainKeys()
    const head = b64std(new Uint8Array(32).fill(5))
    await expect(
      sealForServerOwner({ serverOwnerId: OWNER, identityPublicKey: bundleB64(keys), keyChainHead: head, epochs: [1] }),
    ).rejects.toEqual(new ServerOwnerDeliveryRefused('unanchored'))
  })

  it('a retired key served beside the newest head is not sealed to', async () => {
    const born = chains.born(OWNER)
    const rotated = step(born, chainKeys())
    chains.serve(rotated)
    await expect(
      sealForServerOwner({ serverOwnerId: OWNER, identityPublicKey: bundleB64(born.keys[0]), keyChainHead: headOf(rotated), epochs: [1] }),
    ).rejects.toMatchObject({ reason: 'unanchored' })
  })

  it('an epoch this account does not hold refuses the whole hand-over', async () => {
    const chain = chains.born(OWNER)
    await expect(
      sealForServerOwner({ serverOwnerId: OWNER, ...servedIdentity(chain), epochs: [1, 3] }),
    ).rejects.toMatchObject({ reason: 'notHeld' })
  })

  it('a locked page seals nothing and asks the server nothing', async () => {
    clearVault()
    const chain = chains.born(OWNER)
    await expect(
      sealForServerOwner({ serverOwnerId: OWNER, ...servedIdentity(chain), epochs: [1] }),
    ).rejects.toMatchObject({ reason: 'locked' })
    expect(chains.getKeyChain).not.toHaveBeenCalled()
  })
})
