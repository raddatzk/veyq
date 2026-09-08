import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('../../api/client', () => ({ tasksApi: vi.fn() }))
// withRefresh just runs the call once in these unit tests (no token refresh under test).
vi.mock('../account/login/login', () => ({ withRefresh: (f: () => unknown) => f() }))
// The TOFU pin store is exercised in its own test; here we drive checkAndPin's outcome directly.
vi.mock('../account/identityPins', () => ({ checkAndPin: vi.fn() }))

import { runGrantDeliveryWorker, wrapScopeKeyToMember } from './grantDeliveryWorker'
import { GRANT_DELIVERY } from './capabilityTags'
import { tasksApi } from '../../api/client'
import { checkAndPin } from '../account/identityPins'
import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'

// A real *foreign* identity: the pending member the worker delivers to. Its public bundle is what the server
// hands the worker in the claim; only the member holds the matching private, so the wrap must open with it.
const target = v1.generateIdentity()
const targetPubB64 = b64std(v1.serializeIdentityPublic(target.pub))

function mockTasks(
  claimTask: ReturnType<typeof vi.fn>,
  completeTask: ReturnType<typeof vi.fn>,
  failTask = vi.fn(),
  declineTask = vi.fn(),
) {
  vi.mocked(tasksApi).mockReturnValue(
    { claimTask, completeTask, failTask, declineTask } as unknown as ReturnType<typeof tasksApi>,
  )
}

beforeEach(() => {
  vi.clearAllMocks()
  // Default: the target is new or unchanged → the worker proceeds with the wrap (TOFU). Overridden per test.
  vi.mocked(checkAndPin).mockResolvedValue('firstUse')
})

describe('runGrantDeliveryWorker', () => {
  it('claims for the opened scope, wraps K_SC to the foreign identity, and completes; the wrap opens back to K_SC', async () => {
    const K_SC = v1.randomBytes(32)
    const claimTask = vi
      .fn()
      .mockResolvedValueOnce({
        jobId: 'j1', scopeId: 's1', capabilityTag: GRANT_DELIVERY, attempts: 1,
        grantDelivery: { targetUserId: 'u1', targetMembershipId: 'm1', identityPublicKey: targetPubB64 },
      })
      .mockResolvedValueOnce(null) // drained → stop
    const completeTask = vi.fn().mockResolvedValue(undefined)
    mockTasks(claimTask, completeTask)

    const delivered = await runGrantDeliveryWorker({ scopeId: 's1', key: K_SC })

    expect(delivered).toBe(1)
    // The claim is narrowed to the opened scope (so the server never hands back a job for a key we don't hold).
    expect(claimTask).toHaveBeenNthCalledWith(1, { claimRequest: { capabilityTags: [GRANT_DELIVERY], scopeId: 's1' } })
    expect(completeTask).toHaveBeenCalledTimes(1)
    expect(completeTask.mock.calls[0][0].jobId).toBe('j1')

    // The delivered grant is a REAL foreign wrap: only the target's identity private opens it, back to the same key.
    const wrap: string = completeTask.mock.calls[0][0].completeRequest.sealedResult
    const opened = v1.asymUnwrap(
      v1.hybridSecret(target.priv), v1.hybridPublic(target.priv), fromB64std(wrap), 'scope-grant',
    )
    expect(Array.from(opened)).toEqual(Array.from(K_SC))
  })

  it('declines the job (does not wrap) when the target identity key CHANGED — TOFU block (cr-0098)', async () => {
    // The pin store holds a DIFFERENT key for this contact → the human-less worker must NOT seal K_SC to the
    // possibly-substituted key. It declines the job (release to others + never re-offer to this worker) and moves on.
    vi.mocked(checkAndPin).mockResolvedValue('changed')
    const claimTask = vi
      .fn()
      .mockResolvedValueOnce({
        jobId: 'j1', scopeId: 's1', capabilityTag: GRANT_DELIVERY, attempts: 1,
        grantDelivery: { targetUserId: 'u1', targetMembershipId: 'm1', identityPublicKey: targetPubB64 },
      })
      .mockResolvedValueOnce(null)
    const completeTask = vi.fn()
    const declineTask = vi.fn().mockResolvedValue(undefined)
    mockTasks(claimTask, completeTask, vi.fn(), declineTask)

    const delivered = await runGrantDeliveryWorker({ scopeId: 's1', key: v1.randomBytes(32) })

    expect(delivered).toBe(0)
    expect(completeTask).not.toHaveBeenCalled() // never wrapped to the changed key
    expect(declineTask).toHaveBeenCalledTimes(1)
    expect(declineTask.mock.calls[0][0].jobId).toBe('j1')
    expect(declineTask.mock.calls[0][0].declineRequest.reason).toBe('identity_changed')
  })

  it('is a no-op when the member is already granted — nothing is claimable, no completion', async () => {
    const claimTask = vi.fn().mockResolvedValue(null) // 204 → null: the backend already delivered / no pending member
    const completeTask = vi.fn()
    mockTasks(claimTask, completeTask)

    const delivered = await runGrantDeliveryWorker({ scopeId: 's1', key: v1.randomBytes(32) })

    expect(delivered).toBe(0)
    expect(completeTask).not.toHaveBeenCalled()
  })

  it('wrapScopeKeyToMember produces a distinct ciphertext each call (fresh nonce) that still opens to the same key', async () => {
    const K_SC = v1.randomBytes(32)
    const a = wrapScopeKeyToMember(K_SC, targetPubB64)
    const b = wrapScopeKeyToMember(K_SC, targetPubB64)
    expect(a).not.toBe(b) // randomised KEM + nonce
    for (const wrap of [a, b]) {
      const opened = v1.asymUnwrap(
        v1.hybridSecret(target.priv), v1.hybridPublic(target.priv), fromB64std(wrap), 'scope-grant',
      )
      expect(Array.from(opened)).toEqual(Array.from(K_SC))
    }
  })
})
