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

function stubTerms() {
  return stubApi(tenantsApi, {
    getTenantTerms: { termsVersion: 2, acceptedVersion: 1, terms: { de: 'DE', en: 'EN' } },
    updateTenantTerms: { termsVersion: 3, acceptedVersion: 1, terms: { de: 'DE2', en: 'EN2' } },
    acceptTenantTerms: { termsVersion: 3, acceptedVersion: 3, terms: { de: 'DE2', en: 'EN2' } },
  })
}

describe('tenant terms facade (cr-0114)', () => {
  beforeEach(() => vi.clearAllMocks())

  it('getTenantTerms addresses the tenant', async () => {
    const api = stubTerms()
    const t = await getTenantTerms(TENANT)
    expect(api.getTenantTerms).toHaveBeenCalledWith({ tenantId: TENANT })
    expect(t.termsVersion).toBe(2)
    expect(t.acceptedVersion).toBe(1)
  })

  it('updateTenantTerms sends the per-locale markdown map', async () => {
    const api = stubTerms()
    await updateTenantTerms(TENANT, { de: 'DE2', en: 'EN2' })
    expect(api.updateTenantTerms).toHaveBeenCalledWith({
      tenantId: TENANT,
      tenantTermsUpdate: { terms: { de: 'DE2', en: 'EN2' } },
    })
  })

  it('acceptTenantTerms sends the version being accepted', async () => {
    const api = stubTerms()
    await acceptTenantTerms(TENANT, 3)
    expect(api.acceptTenantTerms).toHaveBeenCalledWith({ tenantId: TENANT, acceptTermsRequest: { version: 3 } })
  })
})

describe('member accept-gate — termsStale / markTermsAccepted (cr-0114)', () => {
  beforeEach(() => clearActiveSpace())

  it('fires when the tenant has terms newer than the caller accepted', () => {
    activeSpace.tenantId = TENANT
    activeSpace.termsVersion = 2
    activeSpace.acceptedVersion = 1
    expect(termsStale()).toBe(true)
  })

  it('does not fire when no terms are set (version 0 = nothing to accept)', () => {
    activeSpace.tenantId = TENANT
    activeSpace.termsVersion = 0
    activeSpace.acceptedVersion = 0
    expect(termsStale()).toBe(false)
  })

  it('clears once the current version is accepted', () => {
    activeSpace.tenantId = TENANT
    activeSpace.termsVersion = 2
    activeSpace.acceptedVersion = 1
    markTermsAccepted(2)
    expect(activeSpace.acceptedVersion).toBe(2)
    expect(termsStale()).toBe(false)
  })

  it('never fires outside a tenant', () => {
    activeSpace.tenantId = null
    activeSpace.termsVersion = 2
    activeSpace.acceptedVersion = 0
    expect(termsStale()).toBe(false)
  })

  it('markTermsAccepted never lowers the recorded version', () => {
    activeSpace.tenantId = TENANT
    activeSpace.termsVersion = 5
    activeSpace.acceptedVersion = 3
    markTermsAccepted(2)
    expect(activeSpace.acceptedVersion).toBe(3)
  })
})
