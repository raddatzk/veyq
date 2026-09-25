import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('../features/account/instance/instance', () => ({ getSetupStatus: vi.fn() }))

import { boot } from './boot'
import { getSetupStatus } from '../features/account/instance/instance'
import { serverState } from '../shared/state/serverState'

beforeEach(() => {
  vi.clearAllMocks()
  serverState.suspended = false
  serverState.unreachable = false
})

// Boot's one question to the instance is its public setup route, which a suspended tenant's own domain refuses
// like every API call (uc.spaces.scope-access A7): that is the offline notice, not "unreachable".
describe('boot on a suspended tenant’s own domain', () => {
  it('a 403 tenant_suspended marks the domain suspended, not unreachable', async () => {
    vi.mocked(getSetupStatus).mockRejectedValue({
      response: new Response(JSON.stringify({ code: 'tenant_suspended' }), { status: 403 }),
    })
    await boot()
    expect(serverState.suspended).toBe(true)
    expect(serverState.unreachable).toBe(false)
  })

  it('control: a failure without that code is still "unreachable"', async () => {
    vi.mocked(getSetupStatus).mockRejectedValue(new TypeError('Failed to fetch'))
    await boot()
    expect(serverState.unreachable).toBe(true)
    expect(serverState.suspended).toBe(false)
  })
})
