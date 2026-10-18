// @vitest-environment happy-dom
// Web data-layer + gate-logic tests for tenant terms (uc.admin.tenant-terms + uc.account.accept-terms,
// cr-0114): the facade request shaping over the generated tenants client — read, owner AUTHORING and member
// accept, all three of which stay in `web/` because authoring is a tenant-owner action (see tenantTerms.ts)
// — plus the member accept-gate condition (`termsStale()` / `markTermsAccepted()`) the router guard reads.
// Operator-blind: terms are public operational content, acceptance is coarse metadata — no crypto here.
import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('../../api/client', () => ({
  tenantsApi: vi.fn(),
}))

import { getTenantTerms, updateTenantTerms, acceptTenantTerms } from './tenantTerms'
import { tenantsApi } from '../../api/client'
import { stubApi } from '../../test/apiMock'
import { activeSpace, termsStale, markTermsAccepted, clearActiveSpace } from '../../app/activeSpace'

const TENANT = '7c3f0e2a-9b1d-4a6e-8b2c-1f2d3e4a5b6c'

const SECTION = { revision: 1, variant: 'FULL' as const, text: { de: '## Auf dieser Instanz', en: '## On this instance' } }

function stubTerms() {
  return stubApi(tenantsApi, {
    getTenantTerms: { termsRevision: 2, accepted: false, terms: { de: 'DE', en: 'EN' }, operatorSection: SECTION },
    updateTenantTerms: { termsRevision: 3, accepted: false, terms: { de: 'DE2', en: 'EN2' }, operatorSection: SECTION },
    acceptTenantTerms: { termsRevision: 3, accepted: true, terms: { de: 'DE2', en: 'EN2' }, operatorSection: SECTION },
  })
}

describe('tenant terms facade (cr-0114)', () => {
  beforeEach(() => vi.clearAllMocks())

  it('getTenantTerms addresses the tenant', async () => {
    const api = stubTerms()
    const t = await getTenantTerms(TENANT)
    expect(api.getTenantTerms).toHaveBeenCalledWith({ tenantId: TENANT })
    expect(t.termsRevision).toBe(2)
    expect(t.accepted).toBe(false)
    expect(t.operatorSection.revision).toBe(1)
  })

  it('updateTenantTerms sends the per-locale markdown map', async () => {
    const api = stubTerms()
    await updateTenantTerms(TENANT, { de: 'DE2', en: 'EN2' })
    expect(api.updateTenantTerms).toHaveBeenCalledWith({
      tenantId: TENANT,
      tenantTermsUpdate: { terms: { de: 'DE2', en: 'EN2' } },
    })
  })

  it('acceptTenantTerms sends the whole triple being accepted (ADR 0035 decision 6)', async () => {
    const api = stubTerms()
    await acceptTenantTerms(TENANT, { revision: 3, operatorRevision: 1, variant: 'REDUCED' })
    expect(api.acceptTenantTerms).toHaveBeenCalledWith({
      tenantId: TENANT,
      acceptTermsRequest: { revision: 3, operatorRevision: 1, variant: 'REDUCED' },
    })
  })
})

describe('member accept-gate — termsStale / markTermsAccepted (cr-0114, the triple of ADR 0035 decision 6)', () => {
  beforeEach(() => clearActiveSpace())

  it('fires when the tenant has terms and the caller has not accepted the current triple', () => {
    activeSpace.tenantId = TENANT
    activeSpace.termsRevision = 2
    activeSpace.termsAccepted = false
    expect(termsStale()).toBe(true)
  })

  it('does not fire when no terms are set (revision 0 = nothing to accept, the section shows without a gate)', () => {
    activeSpace.tenantId = TENANT
    activeSpace.termsRevision = 0
    activeSpace.termsAccepted = false
    expect(termsStale()).toBe(false)
  })

  it('clears once the current triple is accepted', () => {
    activeSpace.tenantId = TENANT
    activeSpace.termsRevision = 2
    activeSpace.termsAccepted = false
    markTermsAccepted()
    expect(activeSpace.termsAccepted).toBe(true)
    expect(termsStale()).toBe(false)
  })

  it('never fires outside a tenant', () => {
    activeSpace.tenantId = null
    activeSpace.termsRevision = 2
    activeSpace.termsAccepted = false
    expect(termsStale()).toBe(false)
  })
})
