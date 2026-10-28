import 'fake-indexeddb/auto'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { IDBFactory } from 'fake-indexeddb'
import * as v1 from '@veyq/crypto/v1'
import { b64std } from '@veyq/crypto/base64'

// The moderation delivery against the REAL trust layer (cr-secure-the-account decision 9, the phase 3 finding taken into
// phase 4): a holder anchors the moderator it delivers to by the head `ModerationPendingDelivery` now serves, through the
// chain the server serves between two accepted moderators — who may share no tenant. The unit suite beside it
// (`unlockModeration.test.ts`) mocks the anchor; this one runs `anchorIdentity` on a real pin store and real chains.
vi.mock('../../api/client', () => ({ moderationApi: vi.fn(), accountIdentityPinsApi: vi.fn(), keyChainApi: vi.fn() }))
vi.mock('../../app/sessionRefresh', () => ({ withRefresh: (f: () => unknown) => f() }))
vi.mock('../account/login/login', () => ({ login: vi.fn() }))
vi.mock('../account/unlock/unlock', () => ({ unlockMasterKey: vi.fn() }))

import { moderationApi } from '../../api/client'
import type { ModerationEpochGrant, ModerationPendingDelivery } from '../../api/generated/moderation'
import { fakeApi } from '../../test/apiMock'
import { fakePinServer } from '../../test/pinStore'
import { bundleB64, chainKeys, fakeKeyChains, headOf, servedIdentity, step } from '../../test/keyChain'
import { setUnlocked } from '../../app/vault'
import { session } from '../../app/session'
import { account } from '../../app/account'
import { unlockMasterKey } from '../account/unlock/unlock'
import { clearModerationKeys } from './moderationKeys'
import { unlockModeration } from './unlockModeration'

const me = v1.generateIdentity()
const MK = new Uint8Array(32).fill(9)
const THEM = '5b6c7c3f-0e2a-4b1d-8a6e-8b2c1f2d3e4a'
const m1 = v1.generateModerationKey()

const grant: ModerationEpochGrant = (() => {
  const bundle = v1.moderationBundle(m1)
  return {
    epoch: 1, kemPublicKey: b64std(bundle.kem), signPublicKey: b64std(bundle.sign),
    sealedGrant: b64std(v1.sealTo({ purpose: 'moderation-grant', epoch: 1 }, me.pub.kem, m1)),
  }
})()

let puts: unknown[]
function serve(pending: ModerationPendingDelivery[]) {
  puts = []
  fakeApi(moderationApi, {
    listMyModerationGrants: async () => ({ currentEpoch: 1, epochs: [grant] }),
    createModerationChallenge: async () => ({ challenge: b64std(new Uint8Array(32).fill(7)) }),
    listPendingModerationDeliveries: async () => ({ deliveries: pending }),
    putModerationDelivery: async (req) => {
      puts.push(req)
    },
  })
}

let chains: ReturnType<typeof fakeKeyChains>
beforeEach(() => {
  globalThis.indexedDB = new IDBFactory()
  clearModerationKeys()
  session.accessToken = 'token'
  account.email = 'holder@example.com'
  fakePinServer()
  chains = fakeKeyChains()
  vi.mocked(unlockMasterKey).mockImplementation(async () => {
    setUnlocked(MK.slice(), { kemSeed: me.priv.kemSeed.slice(), sign: me.priv.sign.slice() }, 'cold')
  })
})

describe('the moderation delivery anchors its recipient by the served head', () => {
  it('delivers to a moderator this holder never met, through the chain the server serves between moderators', async () => {
    const chain = chains.born(THEM)
    serve([{ accountId: THEM, ...servedIdentity(chain), epochs: [1] }])
    expect(await unlockModeration('holder@example.com', 'pw', 'sk')).toEqual({ holdsCurrent: true, delivered: 1, declined: 0 })
    expect(chains.getKeyChain).toHaveBeenCalledWith({ userId: THEM })
  })

  it('control: a moderator whose chain the server does not serve is declined, not delivered to on its bare key', async () => {
    const keys = chainKeys()
    serve([{ accountId: THEM, identityPublicKey: bundleB64(keys), keyChainHead: b64std(new Uint8Array(32).fill(5)), epochs: [1] }])
    expect(await unlockModeration('holder@example.com', 'pw', 'sk')).toEqual({ holdsCurrent: true, delivered: 0, declined: 1 })
    expect(puts).toEqual([])
  })

  it('a retired key served beside the newest head is declined: the chain ends at another key', async () => {
    const born = chains.born(THEM)
    const rotated = step(born, chainKeys())
    chains.serve(rotated)
    serve([{ accountId: THEM, identityPublicKey: bundleB64(born.keys[0]), keyChainHead: headOf(rotated), epochs: [1] }])
    expect(await unlockModeration('holder@example.com', 'pw', 'sk')).toEqual({ holdsCurrent: true, delivered: 0, declined: 1 })
    expect(puts).toEqual([])
  })
})
