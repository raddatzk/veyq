import 'fake-indexeddb/auto'
// The grant delivery's anchor gate end to end (cr-secure-the-account decisions 9, 10): the REAL pin store and the REAL
// chain walk decide whether the worker wraps to the key a claim serves; only the server is faked — the task endpoints,
// the pin store's endpoint and the key-chain read. A target who secured their account is delivered to their new key
// (the chain reaches it from the pinned head); a "new key" behind a step the retired Secret Key did not sign is declined
// `identity_unanchored` and never wrapped to.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { IDBFactory } from 'fake-indexeddb'
import { fakePinServer, pinOf, pinsOn, sealPinStore } from '../../test/pinStore'
import { bundleB64, chainKeys, fakeKeyChains, headOf, servedIdentity, step, type BuiltChain } from '../../test/keyChain'
import { fakeApi } from '../../test/apiMock'

vi.mock('../../api/client', () => ({ tasksApi: vi.fn(), accountIdentityPinsApi: vi.fn(), keyChainApi: vi.fn() }))
vi.mock('../../app/sessionRefresh', () => ({ withRefresh: (f: () => unknown) => f() }))
vi.mock('../../app/vault', () => ({ identityPrivate: vi.fn(), masterKey: vi.fn() }))

import { runGrantDeliveryWorker } from './grantDeliveryWorker'
import { GRANT_DELIVERY } from './capabilityTags'
import { tasksApi } from '../../api/client'
import { forgetVerifiedPins } from '../account/identityPins'
import { identityPrivate, masterKey } from '../../app/vault'
import type { ClaimedJob, TasksApi } from '../../api/generated/tasks'
import * as v1 from '@veyq/crypto/v1'
import { fromB64std } from '@veyq/crypto/base64'

const MK = v1.randomBytes(32)
const S1 = '5c09e000-0000-4000-8000-0000000000a1'
const TARGET = '7a26e000-0000-4000-8000-0000000000b1'
const worker = v1.generateIdentity()
const OLD = chainKeys()
const NEW = chainKeys()

let chains: ReturnType<typeof fakeKeyChains>

/** One claimable delivery to TARGET, served at `chain`'s newest key and head; then drained. */
function deliveryTo(chain: BuiltChain) {
  const job: ClaimedJob = {
    jobId: 'j1', scopeId: S1, capabilityTag: GRANT_DELIVERY, attempts: 1,
    grantDelivery: { targetUserId: TARGET, targetMembershipId: 'm1', ...servedIdentity(chain) },
  }
  const claimTask = vi.fn<TasksApi['claimTask']>().mockResolvedValueOnce(job).mockResolvedValueOnce(null)
  const completeTask = vi.fn<TasksApi['completeTask']>().mockResolvedValue(undefined)
  const declineTask = vi.fn<TasksApi['declineTask']>().mockResolvedValue({ status: 'pending', attempts: 1 })
  fakeApi(tasksApi, { claimTask, completeTask, declineTask, failTask: vi.fn<TasksApi['failTask']>() })
  return { completeTask, declineTask }
}

const run = (key: Uint8Array) => runGrantDeliveryWorker({ scopeId: S1, kind: 'GROUP', key, epoch: 3 })

beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(masterKey).mockImplementation(() => MK.slice())
  vi.mocked(identityPrivate).mockImplementation(() => ({ kemSeed: worker.priv.kemSeed.slice(), sign: worker.priv.sign.slice() }))
  forgetVerifiedPins()
  globalThis.indexedDB = new IDBFactory()
  chains = fakeKeyChains()
})

describe('the grant delivery wraps only to a key the chain reaches from the pinned head', () => {
  it('advanced: the target secured their account — the scope key is wrapped to the NEW key, and the pin moves', async () => {
    const h0 = chains.born(TARGET, OLD)
    const h1 = step(h0, NEW)
    chains.serve(h1)
    const pins = fakePinServer(sealPinStore(MK, { [TARGET]: pinOf(bundleB64(OLD), 'verified', 1, headOf(h0)) }, 1))
    const { completeTask, declineTask } = deliveryTo(h1)
    const K = v1.randomBytes(32)

    expect(await run(K)).toBe(1)
    expect(declineTask).not.toHaveBeenCalled()
    const sealed = completeTask.mock.calls[0][0].completeRequest!.sealedResult!
    const opened = v1.openAs({ purpose: 'scope-grant', scopeId: S1, epoch: 3 }, v1.hybridSecret(NEW.identity.priv), fromB64std(sealed))
    expect(Array.from(opened)).toEqual(Array.from(K))
    expect(pinsOn(MK, pins.state)[TARGET]).toMatchObject({ head: headOf(h1), state: 'pinned' }) // verified dropped
  })

  it('anomaly: a step the retired Secret Key did not sign is declined identity_unanchored — nothing is wrapped', async () => {
    const h0 = chains.born(TARGET, OLD)
    const thief = chainKeys()
    const forged = step(h0, thief, { proofPrivateKey: chainKeys().proof.privateKey })
    chains.serve(forged)
    const pins = fakePinServer(sealPinStore(MK, { [TARGET]: pinOf(bundleB64(OLD), 'pinned', 1, headOf(h0)) }, 1))
    const { completeTask, declineTask } = deliveryTo(forged)

    expect(await run(v1.randomBytes(32))).toBe(0)
    expect(completeTask).not.toHaveBeenCalled()
    expect(declineTask.mock.calls[0][0].declineRequest?.reason).toBe('identity_unanchored')
    expect(pinsOn(MK, pins.state)[TARGET].head).toBe(headOf(h0))
  })
})
