import { describe, it, expect, vi, beforeEach } from 'vitest'

// Mock the contracts client so the facade's network calls are controllable; assert it maps args onto the
// generated tenants client correctly. The **accept** half of this facade (and its error decoder) moved to
// `features/tenant/` with the surfaces an ordinary user reaches — see `tenantInvite.test.ts` (ADR 0065).
vi.mock('../../api/client', () => ({ tenantsApi: vi.fn() }))

import { createTenant, listTenants, parseQuotaGib, reassignInvite, resendInvite, revokeTenant, setTenantStorageQuota } from './tenants'
import { tenantsApi } from '../../api/client'
import type { TenantCreated, TenantInviteState } from '../../api/generated/tenants'
import { stubApi } from '../../test/apiMock'

const INVITE: TenantInviteState = {
  ownerEmail: 'k@example.com',
  inviteStatus: 'ACTIVE',
  inviteExpiresAt: new Date('2026-07-03T12:00:00Z'),
  bootstrapSecret: 'secret',
  delivery: 'SENT',
}
const CREATED: TenantCreated = {
  tenantId: 't1',
  status: 'PENDING',
  inviteExpiresAt: INVITE.inviteExpiresAt,
  bootstrapSecret: INVITE.bootstrapSecret,
  delivery: 'SENT',
}

describe('tenants facade (uc.admin.create-tenant, web Slice A)', () => {
  let api: ReturnType<typeof stubTenants>
  const stubTenants = () =>
    stubApi(tenantsApi, {
      createTenant: CREATED,
      listTenants: [],
      resendTenantInvite: INVITE,
      reassignTenantInvite: INVITE,
      revokeTenant: undefined,
      setTenantStorageQuota: undefined,
    })

  beforeEach(() => {
    api = stubTenants()
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

  it('setTenantStorageQuota wraps the bytes (or null to clear) in a TenantStorageQuotaUpdate', async () => {
    await setTenantStorageQuota('t1', 1024)
    expect(api.setTenantStorageQuota).toHaveBeenCalledWith({ tenantId: 't1', tenantStorageQuotaUpdate: { quotaBytes: 1024 } })
    await setTenantStorageQuota('t1', null)
    expect(api.setTenantStorageQuota).toHaveBeenLastCalledWith({ tenantId: 't1', tenantStorageQuotaUpdate: { quotaBytes: null } })
  })

  it('parseQuotaGib reads GiB as bytes, none as null, and anything else as not a quota', () => {
    expect(parseQuotaGib('2')).toBe(2 * 1024 ** 3)
    expect(parseQuotaGib('2,5')).toBe(2_684_354_560)
    expect(parseQuotaGib(' 0 ')).toBe(0)
    expect(parseQuotaGib('None')).toBeNull()
    for (const bad of ['', '-1', 'ten', 'Infinity']) expect(parseQuotaGib(bad)).toBeUndefined()
  })

  it('revoke passes the tenant id', async () => {
    await revokeTenant('t1')
    expect(api.revokeTenant).toHaveBeenCalledWith({ tenantId: 't1' })
  })
})
