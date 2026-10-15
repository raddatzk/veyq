import { describe, it, expect, vi, beforeEach, type Mock } from 'vitest'
import { httpError } from '../../test/httpError'

vi.mock('../../api/client', () => ({ tasksApi: vi.fn() }))
// withRefresh just runs the call once in these unit tests (no token refresh under test).
vi.mock('../../app/sessionRefresh', () => ({ withRefresh: (f: () => unknown) => f() }))
// The TOFU pin store is exercised in its own test; here we drive checkAndPin's outcome directly.
vi.mock('../account/identityPins', () => ({ checkAndPin: vi.fn() }))
// The worker's own identity — its signing key signs every grant it posts (ADR 0028 Decision 6). A fresh copy per
// call, like the real vault, because the worker zeroes what it is handed.
vi.mock('../../app/vault', () => ({ identityPrivate: vi.fn() }))

import { runGrantDeliveryWorker, wrapScopeKeyToMember, ADMISSION_UNVERIFIED } from './grantDeliveryWorker'
import { GRANT_DELIVERY } from './capabilityTags'
import { tasksApi } from '../../api/client'
import { fakeApi } from '../../test/apiMock'
import type { ClaimedJob, TasksApi } from '../../api/generated/tasks'
import { checkAndPin } from '../account/identityPins'
import { TrustStateRefused } from '../account/trustMarks'
import { identityPrivate } from '../../app/vault'
import { grantSignatureVerifies, tagAdmission } from '../spaces/grant'
import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'

// A real *foreign* identity: the pending member the worker delivers to. Its public bundle is what the server
// hands the worker in the claim; only the member holds the matching private, so the wrap must open with it.
const target = v1.generateIdentity()
const targetPubB64 = b64std(v1.serializeIdentityPublic(target.pub))
const TARGET = '7a26e000-0000-4000-8000-0000000000b1'
const worker = v1.generateIdentity()
// The scope the worker holds a key for — a canonical id, since the grant is sealed bound to it (ADR 0072).
const S1 = '5c09e000-0000-4000-8000-0000000000a1'
/** Open a delivered grant as the target would: on `S1`'s row, at `epoch`. */
const openAsTarget = (wrap: string, epoch: number) =>
  v1.openAs({ purpose: 'scope-grant', scopeId: S1, epoch }, v1.hybridSecret(target.priv), fromB64std(wrap))

/** The target's admission, tagged by a genuine key-holder of `key` at `epoch` (a group add: bound to the target). */
const admitted = (key: Uint8Array, epoch: number) => tagAdmission(key, { scopeId: S1, kind: 'GROUP' }, epoch, TARGET)

/** A claimed grant-delivery job for the target, carrying `admission` (or none). */
const job = (admission: Partial<ReturnType<typeof admitted>> = {}): ClaimedJob => ({
  jobId: 'j1', scopeId: S1, capabilityTag: GRANT_DELIVERY, attempts: 1,
  grantDelivery: { targetUserId: TARGET, targetMembershipId: 'm1', identityPublicKey: targetPubB64, ...admission },
})

function mockTasks(
  claimTask: Mock<TasksApi['claimTask']>,
  completeTask: Mock<TasksApi['completeTask']>,
  failTask = vi.fn<TasksApi['failTask']>(),
  declineTask = vi.fn<TasksApi['declineTask']>(),
) {
  fakeApi(tasksApi, { claimTask, completeTask, failTask, declineTask })
}

/** One job, then drained; returns the claim, completion + decline mocks. */
function oneJob(claimed: ClaimedJob) {
  const claimTask = vi.fn<TasksApi['claimTask']>().mockResolvedValueOnce(claimed).mockResolvedValueOnce(null)
  const completeTask = vi.fn<TasksApi['completeTask']>().mockResolvedValue(undefined)
  const declineTask = vi.fn<TasksApi['declineTask']>().mockResolvedValue({ status: 'pending', attempts: 1 })
  mockTasks(claimTask, completeTask, vi.fn<TasksApi['failTask']>(), declineTask)
  return { claimTask, completeTask, declineTask }
}

beforeEach(() => {
  vi.clearAllMocks()
  // Default: the target is new or unchanged → the worker proceeds with the wrap (TOFU). Overridden per test.
  vi.mocked(checkAndPin).mockResolvedValue('firstUse')
  vi.mocked(identityPrivate).mockImplementation(() => ({
    kemSeed: worker.priv.kemSeed.slice(), sign: worker.priv.sign.slice(),
  }))
})

describe('runGrantDeliveryWorker', () => {
  it('claims for the opened scope, wraps K_SC to the foreign identity, signs it and completes; the wrap opens back to K_SC', async () => {
    const K_SC = v1.randomBytes(32)
    const { claimTask, completeTask } = oneJob(job(admitted(K_SC, 2)))

    const delivered = await runGrantDeliveryWorker({ scopeId: S1, kind: 'GROUP', key: K_SC, epoch: 2 })

    expect(delivered).toBe(1)
    // The claim is narrowed to the opened scope (so the server never hands back a job for a key we don't hold).
    expect(claimTask).toHaveBeenNthCalledWith(1, { claimRequest: { capabilityTags: [GRANT_DELIVERY], scopeId: S1 } })
    expect(completeTask).toHaveBeenCalledTimes(1)
    expect(completeTask.mock.calls[0][0].jobId).toBe('j1')

    // The delivered grant is a REAL foreign wrap: only the target's identity private opens it, back to the same key.
    const { sealedResult, grantSignature, epoch } = completeTask.mock.calls[0][0].completeRequest ?? {}
    if (typeof sealedResult !== 'string' || typeof grantSignature !== 'string') throw new Error('completion carries no grant')
    expect(Array.from(openAsTarget(sealedResult, 2))).toEqual(Array.from(K_SC))
    // …and the completion STATES the epoch it sealed at (ADR 0072), so the server records exactly that one.
    expect(epoch).toBe(2)
    // The grant is bound to that epoch: served as another epoch's grant it does not open.
    expect(() => openAsTarget(sealedResult, 1)).toThrow(new v1.OpenRefused('scope-grant'))
    // It carries the worker's signature over exactly this grant, as the target will check it (ADR 0028 Decision 6).
    const scope = { scopeId: S1, kind: 'GROUP' as const, epoch: 2 }
    const to = { userId: TARGET, identityPublicKeyB64: targetPubB64 }
    expect(grantSignatureVerifies(worker.pub.sign, scope, to, sealedResult, grantSignature)).toBe(true)
    expect(grantSignatureVerifies(target.pub.sign, scope, to, sealedResult, grantSignature)).toBe(false)
  })

  // --- the admission gate (ADR 0020 move 6) ---

  it('declines admission_unverified — and wraps nothing — for a target whose tag is garbage', async () => {
    // The #159 door: an owner session without the vault minted an invite carrying any bytes. The worker must not
    // wrap K_SC to the member it admits.
    const K_SC = v1.randomBytes(32)
    const { completeTask, declineTask } = oneJob(job({ ...admitted(K_SC, 0), admissionTag: b64std(v1.randomBytes(32)) }))

    expect(await runGrantDeliveryWorker({ scopeId: S1, kind: 'GROUP', key: K_SC, epoch: 0 })).toBe(0)
    expect(completeTask).not.toHaveBeenCalled()
    expect(declineTask).toHaveBeenCalledTimes(1)
    expect(declineTask.mock.calls[0][0].declineRequest?.reason).toBe(ADMISSION_UNVERIFIED)
    expect(ADMISSION_UNVERIFIED).toBe('admission_unverified')
    expect(checkAndPin).not.toHaveBeenCalled() // an unadmitted target's key is never pinned either
  })

  it('control: the same target with a genuine tag is delivered', async () => {
    const K_SC = v1.randomBytes(32)
    const { completeTask, declineTask } = oneJob(job(admitted(K_SC, 0)))
    expect(await runGrantDeliveryWorker({ scopeId: S1, kind: 'GROUP', key: K_SC, epoch: 0 })).toBe(1)
    expect(declineTask).not.toHaveBeenCalled()
    expect(completeTask).toHaveBeenCalledTimes(1)
  })

  it('declines a tag made at epoch n when the worker holds n+1 — a pre-rotation tag does not admit', async () => {
    const E3 = v1.randomBytes(32)
    const E4 = v1.randomBytes(32)
    const { completeTask, declineTask } = oneJob(job(admitted(E3, 3)))
    expect(await runGrantDeliveryWorker({ scopeId: S1, kind: 'GROUP', key: E4, epoch: 4 })).toBe(0)
    expect(completeTask).not.toHaveBeenCalled()
    expect(declineTask.mock.calls[0][0].declineRequest?.reason).toBe(ADMISSION_UNVERIFIED)
  })

  it('control: a tag made at n+1 reaching the worker at n+1 is delivered', async () => {
    const E4 = v1.randomBytes(32)
    const { completeTask, declineTask } = oneJob(job(admitted(E4, 4)))
    expect(await runGrantDeliveryWorker({ scopeId: S1, kind: 'GROUP', key: E4, epoch: 4 })).toBe(1)
    expect(declineTask).not.toHaveBeenCalled()
    expect(completeTask.mock.calls[0][0].completeRequest?.epoch).toBe(4)
  })

  it('declines a target that carries no admission at all', async () => {
    const { completeTask, declineTask } = oneJob(job())
    expect(await runGrantDeliveryWorker({ scopeId: S1, kind: 'GROUP', key: v1.randomBytes(32), epoch: 0 })).toBe(0)
    expect(completeTask).not.toHaveBeenCalled()
    expect(declineTask.mock.calls[0][0].declineRequest?.reason).toBe(ADMISSION_UNVERIFIED)
  })

  it('a tenant admission binds no account: a genuine tenant-invite tag admits whoever redeemed it', async () => {
    // The tenant invitee had no account when the owner tagged the invite — the tag cannot name one (ADR 0020
    // § Consequences, the named residual), so the worker verifies it with the target left unbound.
    const K_T = v1.randomBytes(32)
    const invite = tagAdmission(K_T, { scopeId: S1, kind: 'TENANT' }, 0, null)
    const { completeTask } = oneJob(job(invite))
    expect(await runGrantDeliveryWorker({ scopeId: S1, kind: 'TENANT', key: K_T, epoch: 0 })).toBe(1)
    expect(completeTask).toHaveBeenCalledTimes(1)
  })

  it('declines the job (does not wrap) when the target identity key CHANGED — TOFU block (cr-0098)', async () => {
    // The pin store holds a DIFFERENT key for this contact → the human-less worker must NOT seal K_SC to the
    // possibly-substituted key. It declines the job (release to others + never re-offer to this worker) and moves on.
    vi.mocked(checkAndPin).mockResolvedValue('changed')
    const K_SC = v1.randomBytes(32)
    const { completeTask, declineTask } = oneJob(job(admitted(K_SC, 0)))

    const delivered = await runGrantDeliveryWorker({ scopeId: S1, kind: 'GROUP', key: K_SC, epoch: 0 })

    expect(delivered).toBe(0)
    expect(completeTask).not.toHaveBeenCalled() // never wrapped to the changed key
    expect(declineTask).toHaveBeenCalledTimes(1)
    expect(declineTask.mock.calls[0][0].jobId).toBe('j1')
    expect(declineTask.mock.calls[0][0].declineRequest?.reason).toBe('identity_changed')
  })

  it('pauses (neither wraps nor declines) when this device refuses its own pin store (ADR 0028 Decision 7)', async () => {
    // A rolled-back store says nothing about THIS target, so a decline — which excludes this worker from the job
    // for good — would be the wrong answer; nothing may be wrapped either, since no key can be judged.
    vi.mocked(checkAndPin).mockRejectedValue(new TrustStateRefused('trust_state_rolled_back', 'pins'))
    const K_SC = v1.randomBytes(32)
    const { completeTask, declineTask } = oneJob(job(admitted(K_SC, 0)))

    expect(await runGrantDeliveryWorker({ scopeId: S1, kind: 'GROUP', key: K_SC, epoch: 0 })).toBe(0)
    expect(completeTask).not.toHaveBeenCalled()
    expect(declineTask).not.toHaveBeenCalled()
  })

  it('any other pin-store failure still propagates as itself — the pause is for the refusal only', async () => {
    vi.mocked(checkAndPin).mockRejectedValue(new Error('network down'))
    const K_SC = v1.randomBytes(32)
    oneJob(job(admitted(K_SC, 0)))
    await expect(runGrantDeliveryWorker({ scopeId: S1, kind: 'GROUP', key: K_SC, epoch: 0 })).rejects.toThrow('network down')
  })

  it('is a no-op when the member is already granted — nothing is claimable, no completion', async () => {
    const claimTask = vi.fn<TasksApi['claimTask']>().mockResolvedValue(null) // 204 → null: the backend already delivered / no pending member
    const completeTask = vi.fn<TasksApi['completeTask']>()
    mockTasks(claimTask, completeTask)

    const delivered = await runGrantDeliveryWorker({ scopeId: S1, kind: 'GROUP', key: v1.randomBytes(32), epoch: 0 })

    expect(delivered).toBe(0)
    expect(completeTask).not.toHaveBeenCalled()
  })

  it('does not run at all with a locked vault — it could not sign what it delivers', async () => {
    vi.mocked(identityPrivate).mockReturnValue(null)
    const claimTask = vi.fn<TasksApi['claimTask']>()
    mockTasks(claimTask, vi.fn<TasksApi['completeTask']>())
    expect(await runGrantDeliveryWorker({ scopeId: S1, kind: 'GROUP', key: v1.randomBytes(32), epoch: 0 })).toBe(0)
    expect(claimTask).not.toHaveBeenCalled()
  })

  it('wrapScopeKeyToMember produces a distinct ciphertext each call (fresh nonce) that still opens to the same key', async () => {
    const K_SC = v1.randomBytes(32)
    const a = wrapScopeKeyToMember(K_SC, S1, 0, targetPubB64)
    const b = wrapScopeKeyToMember(K_SC, S1, 0, targetPubB64)
    expect(a).not.toBe(b) // randomised KEM + nonce
    for (const wrap of [a, b]) {
      expect(Array.from(openAsTarget(wrap, 0))).toEqual(Array.from(K_SC))
    }
  })

  it('stops draining on a 409 stale_epoch — the server released the job for a key-holder at the current epoch', async () => {
    const K_SC = v1.randomBytes(32)
    const claimTask = vi.fn<TasksApi['claimTask']>().mockResolvedValue(job(admitted(K_SC, 0)))
    const stale = httpError(409, 'stale_epoch')
    const completeTask = vi.fn<TasksApi['completeTask']>().mockRejectedValue(stale)
    mockTasks(claimTask, completeTask)

    expect(await runGrantDeliveryWorker({ scopeId: S1, kind: 'GROUP', key: K_SC, epoch: 0 })).toBe(0)
    expect(claimTask).toHaveBeenCalledTimes(1) // no re-claim spin on the job it was just refused for
    expect(completeTask).toHaveBeenCalledTimes(1)
  })

  it('a non-stale completion failure still propagates', async () => {
    const K_SC = v1.randomBytes(32)
    const claimTask = vi.fn<TasksApi['claimTask']>().mockResolvedValue(job(admitted(K_SC, 0)))
    const forbidden = httpError(409, 'not_lease_holder')
    mockTasks(claimTask, vi.fn<TasksApi['completeTask']>().mockRejectedValue(forbidden))
    await expect(runGrantDeliveryWorker({ scopeId: S1, kind: 'GROUP', key: K_SC, epoch: 0 })).rejects.toBe(forbidden)
  })
})
