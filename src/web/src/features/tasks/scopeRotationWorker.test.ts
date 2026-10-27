import { describe, it, expect, vi, beforeEach, type Mock } from 'vitest'
import { httpError } from '../../test/httpError'

vi.mock('../../api/client', () => ({ tasksApi: vi.fn() }))
// withRefresh just runs the call once in these unit tests (no token refresh under test).
vi.mock('../../app/sessionRefresh', () => ({ withRefresh: (f: () => unknown) => f() }))
// The rotation crypto + directory fetch are covered by their own suites (scopes.test.ts / members.test.ts); the
// worker just orchestrates claim → rotate → complete, so stub them and assert the wiring + arguments. The
// `stale_epoch` predicate stays real: which error the worker declines on is the thing under test.
vi.mock('../spaces/scopes', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../spaces/scopes')>()),
  commitRotation: vi.fn(),
  listMyScopes: vi.fn(),
}))
// The re-key (ADR 0109) is `rekeyScope`'s own suite; here only when the worker runs it and what it does with its answer.
vi.mock('../../core/node/rekey/rekeyScope', () => ({ rekeyScope: vi.fn() }))
vi.mock('../spaces/members', () => ({ listScopeMembers: vi.fn() }))
// Hoisted so the (hoisted) vi.mock factory can close over the same mutable object the tests reach into.
const { activeSpace } = vi.hoisted(() => ({ activeSpace: { scopeId: null as string | null } }))
vi.mock('../../app/activeSpace', () => ({ activeSpace, refreshActiveScope: vi.fn() }))

import { runScopeRotationWorker, LEASE_RENEW_MS } from './scopeRotationWorker'
import { SCOPE_ROTATION, GRANT_DELIVERY } from './capabilityTags'
import { tasksApi } from '../../api/client'
import { fakeApi } from '../../test/apiMock'
import type { TasksApi } from '../../api/generated/tasks'
import type { ScopeMember } from '../../api/generated/scopes'
import { commitRotation, listMyScopes } from '../spaces/scopes'
import { rekeyScope } from '../../core/node/rekey/rekeyScope'
import type { ScopeSummary } from '../../api/generated/scopes'
import { listScopeMembers } from '../spaces/members'
import { refreshActiveScope } from '../../app/activeSpace'

function mockTasks(
  claimTask: Mock<TasksApi['claimTask']>,
  completeTask = vi.fn<TasksApi['completeTask']>(),
  failTask = vi.fn<TasksApi['failTask']>(),
  declineTask = vi.fn<TasksApi['declineTask']>(),
) {
  fakeApi(tasksApi, { claimTask, completeTask, failTask, declineTask })
}

/** The surviving directory the worker fetches post-cut and rotates forward. */
const SURVIVORS: ScopeMember[] = [{ userId: 'u2', username: 'u2', avatarRevision: 0, role: 'MEMBER', identityPublicKey: 'AA==', hasGrant: true }]

beforeEach(() => {
  vi.clearAllMocks()
  activeSpace.scopeId = null
  vi.mocked(commitRotation).mockResolvedValue(2)
  vi.mocked(listScopeMembers).mockResolvedValue(SURVIVORS)
  vi.mocked(listMyScopes).mockResolvedValue([])
  vi.mocked(rekeyScope).mockResolvedValue({ done: true, committed: 1 })
})

/** A scope row as `listMyScopes` serves it, re-keying or not (every member's row carries the flag, ADR 0111 decision 11). */
const row = (id: string, rekeying: boolean) => ({ id, rekeying }) as unknown as ScopeSummary

describe('runScopeRotationWorker and the re-key (ADR 0109 point 7)', () => {
  it('resumes a scope an earlier run left re-keying before it claims anything', async () => {
    vi.mocked(listMyScopes).mockResolvedValue([row('g1', true), row('g2', false)])
    mockTasks(vi.fn<TasksApi['claimTask']>().mockResolvedValue(null))
    await runScopeRotationWorker()
    expect(rekeyScope).toHaveBeenCalledWith('g1')
    expect(rekeyScope).not.toHaveBeenCalledWith('g2') // control: a scope that does not lag is left alone
  })

  it('a plain member\'s device resumes a scope its row reads re-keying too (ADR 0111 decision 11), with no job to claim', async () => {
    const memberRow = (id: string, rekeying: boolean) => ({ id, rekeying, role: 'MEMBER' }) as unknown as ScopeSummary
    vi.mocked(listMyScopes).mockResolvedValue([memberRow('g1', true), memberRow('g2', false)])
    mockTasks(vi.fn<TasksApi['claimTask']>().mockResolvedValue(null))
    vi.mocked(rekeyScope).mockResolvedValue({ done: false, committed: 3 }) // what lags beyond is an owner's drop box
    await runScopeRotationWorker()
    expect(rekeyScope).toHaveBeenCalledWith('g1')
    expect(rekeyScope).not.toHaveBeenCalledWith('g2')
  })

  it('re-keys after its own rotation and completes only then', async () => {
    const order: string[] = []
    vi.mocked(commitRotation).mockImplementation(async () => { order.push('commit'); return 2 })
    vi.mocked(rekeyScope).mockImplementation(async () => { order.push('rekey'); return { done: true, committed: 3 } })
    const completeTask = vi.fn<TasksApi['completeTask']>().mockImplementation(async () => { order.push('complete') })
    mockTasks(vi.fn<TasksApi['claimTask']>().mockResolvedValueOnce({ jobId: 'j1', scopeId: 'g1', capabilityTag: SCOPE_ROTATION, attempts: 1 }).mockResolvedValueOnce(null), completeTask)
    expect(await runScopeRotationWorker()).toBe(1)
    expect(order).toEqual(['commit', 'rekey', 'complete'])
  })

  it('leaves the job leased, uncompleted, when the re-key does not finish', async () => {
    vi.mocked(rekeyScope).mockResolvedValue({ done: false, committed: 0 })
    const completeTask = vi.fn<TasksApi['completeTask']>()
    const failTask = vi.fn<TasksApi['failTask']>()
    mockTasks(vi.fn<TasksApi['claimTask']>().mockResolvedValueOnce({ jobId: 'j1', scopeId: 'g1', capabilityTag: SCOPE_ROTATION, attempts: 1 }).mockResolvedValueOnce(null), completeTask, failTask)
    expect(await runScopeRotationWorker()).toBe(0)
    expect(completeTask).not.toHaveBeenCalled()
    expect(failTask).not.toHaveBeenCalled()
  })

  it('completes a job whose rotation an earlier lease committed without rotating again', async () => {
    // the resume pass sees the scope re-keying, finishes it; the claimed job's rotation is the one already committed
    vi.mocked(listMyScopes).mockResolvedValue([row('g1', true)])
    const completeTask = vi.fn<TasksApi['completeTask']>().mockResolvedValue(undefined)
    mockTasks(vi.fn<TasksApi['claimTask']>().mockResolvedValueOnce({ jobId: 'j1', scopeId: 'g1', capabilityTag: SCOPE_ROTATION, attempts: 2 }).mockResolvedValueOnce(null), completeTask)
    expect(await runScopeRotationWorker()).toBe(1)
    expect(commitRotation).not.toHaveBeenCalled()
    expect(completeTask).toHaveBeenCalledWith({ jobId: 'j1', completeRequest: {} })
  })
})

describe('runScopeRotationWorker keeps the lease of the job it works on (ADR 0021 §6, N1b)', () => {
  it('renews the lease while the job is in hand and stops once it lets the job go', async () => {
    // A re-key that runs longer than a lease's worth of renewals: the server's lease is short (PT2M), so a worker
    // that never renewed would lose a job it is still working on — and one that keeps renewing after it is done
    // would hold a job nobody works on.
    vi.useFakeTimers()
    try {
      let finishRekey = () => {}
      vi.mocked(rekeyScope).mockImplementation(() => new Promise((resolve) => { finishRekey = () => resolve({ done: true, committed: 1 }) }))
      const heartbeatTask = vi.fn<TasksApi['heartbeatTask']>().mockResolvedValue({ leaseExpiresAt: new Date() })
      fakeApi(tasksApi, {
        claimTask: vi.fn<TasksApi['claimTask']>().mockResolvedValueOnce({ jobId: 'j1', scopeId: 'g1', capabilityTag: SCOPE_ROTATION, attempts: 1 }).mockResolvedValueOnce(null),
        completeTask: vi.fn<TasksApi['completeTask']>().mockResolvedValue(undefined),
        heartbeatTask,
      })
      const run = runScopeRotationWorker()
      await vi.advanceTimersByTimeAsync(LEASE_RENEW_MS * 4)
      expect(heartbeatTask).toHaveBeenCalledTimes(4)
      expect(heartbeatTask).toHaveBeenLastCalledWith({ jobId: 'j1' })

      finishRekey()
      expect(await run).toBe(1)
      await vi.advanceTimersByTimeAsync(LEASE_RENEW_MS * 4)
      expect(heartbeatTask).toHaveBeenCalledTimes(4) // the job is done: nothing renews it any more
    } finally {
      vi.useRealTimers()
    }
  })

  it('stops renewing a job it fails back', async () => {
    vi.useFakeTimers()
    try {
      const heartbeatTask = vi.fn<TasksApi['heartbeatTask']>().mockResolvedValue({ leaseExpiresAt: new Date() })
      fakeApi(tasksApi, {
        claimTask: vi.fn<TasksApi['claimTask']>().mockResolvedValueOnce({ jobId: 'j1', scopeId: 'g1', capabilityTag: SCOPE_ROTATION, attempts: 1 }).mockResolvedValueOnce(null),
        failTask: vi.fn<TasksApi['failTask']>().mockResolvedValue({ status: 'pending', attempts: 1 }),
        heartbeatTask,
      })
      vi.mocked(commitRotation).mockRejectedValueOnce(new Error('cannot open scope to rotate'))
      expect(await runScopeRotationWorker()).toBe(0)
      await vi.advanceTimersByTimeAsync(LEASE_RENEW_MS * 4)
      expect(heartbeatTask).not.toHaveBeenCalled()
    } finally {
      vi.useRealTimers()
    }
  })
})

describe('runScopeRotationWorker', () => {
  it('claims a rotation across all held scopes, re-grants the survivors forward, and completes', async () => {
    const claimTask = vi
      .fn<TasksApi['claimTask']>()
      .mockResolvedValueOnce({ jobId: 'j1', scopeId: 'g1', capabilityTag: SCOPE_ROTATION, attempts: 1 })
      .mockResolvedValueOnce(null) // drained → stop
    const completeTask = vi.fn<TasksApi['completeTask']>().mockResolvedValue(undefined)
    mockTasks(claimTask, completeTask)

    const rotated = await runScopeRotationWorker()

    expect(rotated).toBe(1)
    // The claim is NOT narrowed to a scope — it drains rotations for any group this key-holder holds.
    expect(claimTask).toHaveBeenNthCalledWith(1, { claimRequest: { capabilityTags: [SCOPE_ROTATION] } })
    // Rotate the surviving directory (fetched post-cut) forward, then close the job with no sealedResult.
    expect(listScopeMembers).toHaveBeenCalledWith('g1')
    expect(commitRotation).toHaveBeenCalledWith('g1', SURVIVORS)
    expect(completeTask).toHaveBeenCalledWith({ jobId: 'j1', completeRequest: {} })
  })

  it('is a no-op when nothing is claimable (first-online-wins / coalesced away)', async () => {
    const claimTask = vi.fn<TasksApi['claimTask']>().mockResolvedValue(null)
    mockTasks(claimTask)

    expect(await runScopeRotationWorker()).toBe(0)
    expect(commitRotation).not.toHaveBeenCalled()
  })

  it('refreshes the active scope when the rotated group is the one being viewed', async () => {
    activeSpace.scopeId = 'g1'
    const claimTask = vi
      .fn<TasksApi['claimTask']>()
      .mockResolvedValueOnce({ jobId: 'j1', scopeId: 'g1', capabilityTag: SCOPE_ROTATION, attempts: 1 })
      .mockResolvedValueOnce(null)
    mockTasks(claimTask, vi.fn<TasksApi['completeTask']>().mockResolvedValue(undefined))

    await runScopeRotationWorker()
    expect(refreshActiveScope).toHaveBeenCalledTimes(1)
  })

  it('does not refresh when a different scope is active', async () => {
    activeSpace.scopeId = 'other'
    const claimTask = vi
      .fn<TasksApi['claimTask']>()
      .mockResolvedValueOnce({ jobId: 'j1', scopeId: 'g1', capabilityTag: SCOPE_ROTATION, attempts: 1 })
      .mockResolvedValueOnce(null)
    mockTasks(claimTask, vi.fn<TasksApi['completeTask']>().mockResolvedValue(undefined))

    await runScopeRotationWorker()
    expect(refreshActiveScope).not.toHaveBeenCalled()
  })

  it('fails a mis-tagged claim back for the right worker instead of rotating', async () => {
    const claimTask = vi
      .fn<TasksApi['claimTask']>()
      .mockResolvedValueOnce({ jobId: 'j9', scopeId: 'g1', capabilityTag: GRANT_DELIVERY, attempts: 1 })
      .mockResolvedValueOnce(null)
    const failTask = vi.fn<TasksApi['failTask']>().mockResolvedValue({ status: 'pending', attempts: 2 })
    mockTasks(claimTask, vi.fn<TasksApi['completeTask']>(), failTask)

    expect(await runScopeRotationWorker()).toBe(0)
    expect(commitRotation).not.toHaveBeenCalled()
    expect(failTask).toHaveBeenCalledWith({ jobId: 'j9', failRequest: { reason: 'not-scope-rotation' } })
  })

  it('fails the job (not left leased) when the rotation throws mid-flight', async () => {
    const claimTask = vi
      .fn<TasksApi['claimTask']>()
      .mockResolvedValueOnce({ jobId: 'j1', scopeId: 'g1', capabilityTag: SCOPE_ROTATION, attempts: 1 })
      .mockResolvedValueOnce(null)
    const failTask = vi.fn<TasksApi['failTask']>().mockResolvedValue({ status: 'pending', attempts: 2 })
    mockTasks(claimTask, vi.fn<TasksApi['completeTask']>(), failTask)
    vi.mocked(commitRotation).mockRejectedValueOnce(new Error('cannot open scope to rotate'))

    expect(await runScopeRotationWorker()).toBe(0)
    expect(failTask).toHaveBeenCalledWith({ jobId: 'j1', failRequest: { reason: 'rotate-failed' } })
  })

  it('declines, not fails, a job whose rotation is still stale — this worker\'s grant lags the scope', async () => {
    // ADR 0024 §4: the rotation already re-opened and rebuilt once; still stale means this worker cannot perform
    // it at all. Declining releases it for a current key-holder without spending an attempt (the control is the
    // `rotate-failed` case above: any other error still fails).
    const claimTask = vi
      .fn<TasksApi['claimTask']>()
      .mockResolvedValueOnce({ jobId: 'j1', scopeId: 'g1', capabilityTag: SCOPE_ROTATION, attempts: 1 })
      .mockResolvedValueOnce(null)
    const failTask = vi.fn<TasksApi['failTask']>().mockResolvedValue({ status: 'pending', attempts: 2 })
    const declineTask = vi.fn<TasksApi['declineTask']>().mockResolvedValue({ status: 'pending', attempts: 1 })
    mockTasks(claimTask, vi.fn<TasksApi['completeTask']>(), failTask, declineTask)
    vi.mocked(commitRotation).mockRejectedValueOnce(httpError(409, 'stale_epoch'))

    expect(await runScopeRotationWorker()).toBe(0)
    expect(declineTask).toHaveBeenCalledWith({ jobId: 'j1', declineRequest: { reason: 'stale_epoch' } })
    expect(failTask).not.toHaveBeenCalled()
  })

  it.each([
    // An owner demoted between claim and commit (the claim offers rotations to owners only).
    [403, 'not_scope_owner'],
    // The one lease an account rotation leaves to the rotator: the one they held when they rotated (ADR 0060 D4).
    [403, 'rotation_excluded'],
  ])('declines, not fails, a rotation refused %i %s — this worker may not perform it at all', async (status, code) => {
    // Each refusal is about this key-holder, never about the job: declining releases it to the scope's other
    // owners without spending an attempt, where failing would burn attempts until the job dead-letters. The
    // control is the `rotate-failed` case above: any other error still fails.
    const claimTask = vi
      .fn<TasksApi['claimTask']>()
      .mockResolvedValueOnce({ jobId: 'j1', scopeId: 'g1', capabilityTag: SCOPE_ROTATION, attempts: 1 })
      .mockResolvedValueOnce(null)
    const failTask = vi.fn<TasksApi['failTask']>().mockResolvedValue({ status: 'pending', attempts: 2 })
    const declineTask = vi.fn<TasksApi['declineTask']>().mockResolvedValue({ status: 'pending', attempts: 1 })
    mockTasks(claimTask, vi.fn<TasksApi['completeTask']>(), failTask, declineTask)
    vi.mocked(commitRotation).mockRejectedValueOnce(httpError(status, code))

    expect(await runScopeRotationWorker()).toBe(0)
    expect(declineTask).toHaveBeenCalledWith({ jobId: 'j1', declineRequest: { reason: code } })
    expect(failTask).not.toHaveBeenCalled()
  })
})
