// Web data-layer tests for the operator's terms **authoring** half (uc.admin.tenant-terms, cr-0114): the
// update request shaping over the generated tenants client. The member read/accept half and the accept-gate
// condition live in `features/tenant/tenantTerms.test.ts` (ADR 0065). Operator-blind: terms are public
// operational content, acceptance is coarse metadata — no crypto here.
import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('../../../api/client', () => ({
  tenantsApi: vi.fn(),
}))

import { getTenantTerms, updateTenantTerms } from './tenants'
import { tenantsApi } from '../../../api/client'

const TENANT = '7c3f0e2a-9b1d-4a6e-8b2c-1f2d3e4a5b6c'

function stubApi() {
  const api = {
    getTenantTerms: vi.fn().mockResolvedValue({ termsVersion: 2, acceptedVersion: 1, terms: { de: 'DE', en: 'EN' } }),
    updateTenantTerms: vi.fn().mockResolvedValue({ termsVersion: 3, acceptedVersion: 1, terms: { de: 'DE2', en: 'EN2' } }),
  }
  vi.mocked(tenantsApi).mockReturnValue(api as unknown as ReturnType<typeof tenantsApi>)
  return api
}

describe('tenant terms facade — authoring side (cr-0114)', () => {
  beforeEach(() => vi.clearAllMocks())

  it('getTenantTerms addresses the tenant (the author reads what they are about to replace)', async () => {
    const api = stubApi()
    const t = await getTenantTerms(TENANT)
    expect(api.getTenantTerms).toHaveBeenCalledWith({ tenantId: TENANT })
    expect(t.termsVersion).toBe(2)
  })

  it('updateTenantTerms sends the per-locale markdown map', async () => {
    const api = stubApi()
    await updateTenantTerms(TENANT, { de: 'DE2', en: 'EN2' })
    expect(api.updateTenantTerms).toHaveBeenCalledWith({
      tenantId: TENANT,
      tenantTermsUpdate: { terms: { de: 'DE2', en: 'EN2' } },
    })
  })
})
