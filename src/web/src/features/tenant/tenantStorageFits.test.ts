import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('../../api/client', async (orig) => ({ ...(await orig<typeof import('../../api/client')>()), tenantsApi: vi.fn() }))

import { tenantsApi } from '../../api/client'
import { tenantStorageFits } from './tenantStorage'
import { fakeApi } from '../../test/apiMock'
import { httpError } from '../../test/httpError'

beforeEach(() => vi.resetAllMocks())

describe('tenantStorageFits', () => {
  it('sends the tenant and the byte count and returns the server\'s yes or no', async () => {
    const api = fakeApi(tenantsApi, { checkTenantStorageFit: async () => ({ fits: false }) })
    expect(await tenantStorageFits('t1', 123)).toBe(false)
    expect(api.checkTenantStorageFit).toHaveBeenCalledWith({ tenantId: 't1', tenantStorageFitRequest: { bytes: 123 } })
  })

  it.each([429, 404, 500])('answers null - not a no - when the server says %i, so a limit or an outage never blocks an import', async (status) => {
    fakeApi(tenantsApi, { checkTenantStorageFit: async () => { throw httpError(status, 'rate_limited') } })
    expect(await tenantStorageFits('t1', 1)).toBeNull()
  })
})
