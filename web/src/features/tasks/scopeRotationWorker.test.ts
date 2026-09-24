import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('../../api/client', () => ({ tasksApi: vi.fn() }))
// withRefresh just runs the call once in these unit tests (no token refresh under test).
vi.mock('../../app/sessionRefresh', () => ({ withRefresh: (f: () => unknown) => f() }))
// The rotation crypto + directory fetch are covered by their own suites (scopes.test.ts / members.test.ts); the
// worker just orchestrates claim → rotate → complete, so stub them and assert the wiring + arguments.
vi.mock('../spaces/scopes', () => ({ rotateGroupForRemoval: vi.fn().mockResolvedValue(2) }))
vi.mock('../spaces/members', () => ({ listScopeMembers: vi.fn().mockResolvedValue([{ userId: 'u2' }]) }))
// Hoisted so the (hoisted) vi.mock factory can close over the same mutable object the tests reach into.
const { activeSpace } = vi.hoisted(() => ({ activeSpace: { scopeId: null as string | null } }))
vi.mock('../../app/activeSpace', () => ({ activeSpace, refreshActiveScope: vi.fn() }))

import { runScopeRotationWorker } from './scopeRotationWorker'
import { SCOPE_ROTATION, GRANT_DELIVERY } from './capabilityTags'
import { tasksApi } from '../../api/client'
import { rotateGroupForRemoval } from '../spaces/scopes'
import { listScopeMembers } from '../spaces/members'
import { refreshActiveScope } from '../../app/activeSpace'

function mockTasks(claimTask: ReturnType<typeof vi.fn>, completeTask = vi.fn(), failTask = vi.fn()) {
  vi.mocked(tasksApi).mockReturnValue({ claimTask, completeTask, failTask } as unknown as ReturnType<typeof tasksApi>)
}

beforeEach(() => {
  vi.clearAllMocks()
  activeSpace.scopeId = null
})

describe('runScopeRotationWorker', () => {
  it('claims a rotation across all held scopes, re-grants the survivors forward, and completes', async () => {
    const claimTask = vi
      .fn()
      .mockResolvedValueOnce({ jobId: 'j1', scopeId: 'g1', capabilityTag: SCOPE_ROTATION, attempts: 1 })
      .mockResolvedValueOnce(null) // drained → stop
    const completeTask = vi.fn().mockResolvedValue(undefined)
    mockTasks(claimTask, completeTask)

    const rotated = await runScopeRotationWorker()

    expect(rotated).toBe(1)
    // The claim is NOT narrowed to a scope — it drains rotations for any group this key-holder holds.
    expect(claimTask).toHaveBeenNthCalledWith(1, { claimRequest: { capabilityTags: [SCOPE_ROTATION] } })
    // Rotate the surviving directory (fetched post-cut) forward, then close the job with no sealedResult.
    expect(listScopeMembers).toHaveBeenCalledWith('g1')
    expect(rotateGroupForRemoval).toHaveBeenCalledWith('g1', [{ userId: 'u2' }])
    expect(completeTask).toHaveBeenCalledWith({ jobId: 'j1', completeRequest: {} })
  })

  it('is a no-op when nothing is claimable (first-online-wins / coalesced away)', async () => {
    const claimTask = vi.fn().mockResolvedValue(null)
    mockTasks(claimTask)

    expect(await runScopeRotationWorker()).toBe(0)
    expect(rotateGroupForRemoval).not.toHaveBeenCalled()
  })

  it('refreshes the active scope when the rotated group is the one being viewed', async () => {
    activeSpace.scopeId = 'g1'
    const claimTask = vi
      .fn()
      .mockResolvedValueOnce({ jobId: 'j1', scopeId: 'g1', capabilityTag: SCOPE_ROTATION, attempts: 1 })
      .mockResolvedValueOnce(null)
    mockTasks(claimTask, vi.fn().mockResolvedValue(undefined))

    await runScopeRotationWorker()
    expect(refreshActiveScope).toHaveBeenCalledTimes(1)
  })

  it('does not refresh when a different scope is active', async () => {
    activeSpace.scopeId = 'other'
    const claimTask = vi
      .fn()
      .mockResolvedValueOnce({ jobId: 'j1', scopeId: 'g1', capabilityTag: SCOPE_ROTATION, attempts: 1 })
      .mockResolvedValueOnce(null)
    mockTasks(claimTask, vi.fn().mockResolvedValue(undefined))

    await runScopeRotationWorker()
    expect(refreshActiveScope).not.toHaveBeenCalled()
  })

  it('fails a mis-tagged claim back for the right worker instead of rotating', async () => {
    const claimTask = vi
      .fn()
      .mockResolvedValueOnce({ jobId: 'j9', scopeId: 'g1', capabilityTag: GRANT_DELIVERY, attempts: 1 })
      .mockResolvedValueOnce(null)
    const failTask = vi.fn().mockResolvedValue(undefined)
    mockTasks(claimTask, vi.fn(), failTask)

    expect(await runScopeRotationWorker()).toBe(0)
    expect(rotateGroupForRemoval).not.toHaveBeenCalled()
    expect(failTask).toHaveBeenCalledWith({ jobId: 'j9', failRequest: { reason: 'not-scope-rotation' } })
  })

  it('fails the job (not left leased) when the rotation throws mid-flight', async () => {
    const claimTask = vi
      .fn()
      .mockResolvedValueOnce({ jobId: 'j1', scopeId: 'g1', capabilityTag: SCOPE_ROTATION, attempts: 1 })
      .mockResolvedValueOnce(null)
    const failTask = vi.fn().mockResolvedValue(undefined)
    mockTasks(claimTask, vi.fn(), failTask)
    vi.mocked(rotateGroupForRemoval).mockRejectedValueOnce(new Error('cannot open scope to rotate'))

    expect(await runScopeRotationWorker()).toBe(0)
    expect(failTask).toHaveBeenCalledWith({ jobId: 'j1', failRequest: { reason: 'rotate-failed' } })
  })
})
