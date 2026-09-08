import { describe, it, expect, vi, beforeEach } from 'vitest'

// Mock the contracts client so the facade's network calls are controllable; assert it maps args onto the
// generated tenants client correctly. The **accept** half of this facade (and its error decoder) moved to
// `features/tenant/` with the surfaces an ordinary user reaches — see `tenantInvite.test.ts` (ADR 0065).
vi.mock('../../../api/client', () => ({ tenantsApi: vi.fn() }))

import { createTenant, listTenants, reassignInvite, resendInvite, revokeTenant } from './tenants'
import { tenantsApi } from '../../../api/client'

describe('tenants facade (uc.admin.create-tenant, web Slice A)', () => {
  const api = {
    createTenant: vi.fn().mockResolvedValue({ tenantId: 't1', status: 'PENDING' }),
    listTenants: vi.fn().mockResolvedValue([]),
    resendTenantInvite: vi.fn().mockResolvedValue({}),
    reassignTenantInvite: vi.fn().mockResolvedValue({}),
    revokeTenant: vi.fn().mockResolvedValue(undefined),
  }

  beforeEach(() => {
    vi.mocked(tenantsApi).mockReturnValue(api as never)
    Object.values(api).forEach((m) => m.mockClear())
  })

  it('create wraps name/domain/email in a CreateTenantRequest', async () => {
    await createTenant('Fam', 'family.example.com', 'k@example.com')
    expect(api.createTenant).toHaveBeenCalledWith({
      createTenantRequest: { tenantName: 'Fam', domain: 'family.example.com', ownerEmail: 'k@example.com' },
    })
  })

  it('list delegates to listTenants', async () => {
    await listTenants()
    expect(api.listTenants).toHaveBeenCalledOnce()
  })

  it('resend passes the tenant id', async () => {
    await resendInvite('t1')
    expect(api.resendTenantInvite).toHaveBeenCalledWith({ tenantId: 't1' })
  })

  it('reassign passes id + new owner email', async () => {
    await reassignInvite('t1', 'new@example.com')
    expect(api.reassignTenantInvite).toHaveBeenCalledWith({
      tenantId: 't1',
      reassignOwnerRequest: { ownerEmail: 'new@example.com' },
    })
  })

  it('revoke passes the tenant id', async () => {
    await revokeTenant('t1')
    expect(api.revokeTenant).toHaveBeenCalledWith({ tenantId: 't1' })
  })
})
