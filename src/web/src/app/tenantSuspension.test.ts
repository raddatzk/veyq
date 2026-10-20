import { describe, it, expect, vi, beforeEach } from 'vitest'
import { httpError } from '../test/httpError'
import { setupStatus } from '../test/models'

vi.mock('../features/account/instance/instance', () => ({ getSetupStatus: vi.fn() }))

import { handleTenantSuspended, suspensionSignals } from './tenantSuspension'
import { getSetupStatus } from '../features/account/instance/instance'
import { serverState } from '../shared/state/serverState'

beforeEach(() => {
  vi.clearAllMocks()
  serverState.suspended = false
  suspensionSignals.leaveHome = 0
  suspensionSignals.tenantList = 0
})

describe('handleTenantSuspended (uc.spaces.scope-access A7)', () => {
  it('on the suspended tenant’s own domain, marks the domain suspended and asks nobody to leave', async () => {
    // this domain's public setup route is refused too — the Host filter refuses every API call here
    vi.mocked(getSetupStatus).mockRejectedValue(httpError(403, 'tenant_suspended'))
    expect(await handleTenantSuspended(httpError(403, 'tenant_suspended'))).toBe('own-domain')
    expect(serverState.suspended).toBe(true)
    expect(suspensionSignals.leaveHome).toBe(0)
  })

  it('for another tenant, leaves the space for this domain’s home and refreshes the tenant list', async () => {
    vi.mocked(getSetupStatus).mockResolvedValue(setupStatus()) // this domain answers
    expect(await handleTenantSuspended(httpError(403, 'tenant_suspended'))).toBe('other-tenant')
    expect(serverState.suspended).toBe(false)
    expect(suspensionSignals.leaveHome).toBe(1)
    expect(suspensionSignals.tenantList).toBe(1)
  })

  it('leaves any other 403 alone — a missing role is not a suspension', async () => {
    expect(await handleTenantSuspended(httpError(403, 'not_scope_owner'))).toBeNull()
    expect(getSetupStatus).not.toHaveBeenCalled()
    expect(serverState.suspended).toBe(false)
    expect(suspensionSignals.leaveHome).toBe(0)
  })

  it('leaves a 404 alone — an unknown space is the caller’s own error path, with its own remedy', async () => {
    expect(await handleTenantSuspended(httpError(404, 'scope_not_found'))).toBeNull()
    expect(serverState.suspended).toBe(false)
    expect(suspensionSignals.leaveHome + suspensionSignals.tenantList).toBe(0)
  })
})
