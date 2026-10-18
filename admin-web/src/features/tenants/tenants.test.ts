import { describe, it, expect, vi, beforeEach } from 'vitest'

// Mock the contracts client so the facade's network calls are controllable; assert it maps args onto the
// generated tenants client correctly. The **accept** half of this facade (and its error decoder) moved to
// `features/tenant/` with the surfaces an ordinary user reaches — see `tenantInvite.test.ts` (ADR 0065).
vi.mock('../../api/client', () => ({ tenantsApi: vi.fn() }))

import {
  createTenant, deleteTenant, domainConfirmed, inviteTenantOwner, listTenants, parseQuotaGib, reassignInvite, resendInvite,
  revokeTenant, setTenantStorageQuota, stopTenantDeletion,
} from './tenants'
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
      clearTenantStorageQuota: undefined,
      deleteTenant: { dueAt: new Date('2026-11-17T12:00:00Z') },
      stopTenantDeletion: undefined,
      inviteTenantOwner: INVITE,
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

  it('setTenantStorageQuota wraps the bytes in a TenantStorageQuotaUpdate, and null clears through its own call (#304)', async () => {
    await setTenantStorageQuota('t1', 1024)
    expect(api.setTenantStorageQuota).toHaveBeenCalledWith({ tenantId: 't1', tenantStorageQuotaUpdate: { quotaBytes: 1024 } })
    expect(api.clearTenantStorageQuota).not.toHaveBeenCalled()
    await setTenantStorageQuota('t1', null)
    // A null quota is refused by the server (400): "no quota" is never a field value, only clearTenantStorageQuota.
    expect(api.clearTenantStorageQuota).toHaveBeenCalledWith({ tenantId: 't1' })
    expect(api.setTenantStorageQuota).toHaveBeenCalledTimes(1)
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

  // uc.admin.delete-tenant (ADR 0101 decision 10)
  it('delete sends the typed domain as the confirmation, trimmed and lower-cased, and hands back when it is erased', async () => {
    const deletion = await deleteTenant('t1', '  Family.Example.com ')
    expect(api.deleteTenant).toHaveBeenCalledWith({ tenantId: 't1', deleteTenantRequest: { domain: 'family.example.com' } })
    expect(deletion.dueAt).toEqual(new Date('2026-11-17T12:00:00Z'))
  })

  it('stop-deletion passes the tenant id', async () => {
    await stopTenantDeletion('t1')
    expect(api.stopTenantDeletion).toHaveBeenCalledWith({ tenantId: 't1' })
  })

  // ADR 0101 decision 9: the way out of an ownerless tenant
  it('invite-owner wraps the member address in an InviteTenantOwnerRequest', async () => {
    await inviteTenantOwner('t1', ' member@example.com ')
    expect(api.inviteTenantOwner).toHaveBeenCalledWith({ tenantId: 't1', inviteTenantOwnerRequest: { email: 'member@example.com' } })
  })

  it('domainConfirmed holds only the tenant’s own domain, case-insensitively — never an empty or a near one', () => {
    expect(domainConfirmed('family.example.com', 'family.example.com')).toBe(true)
    expect(domainConfirmed(' FAMILY.example.com ', 'family.example.com')).toBe(true)
    for (const bad of ['', '   ', 'family.example', 'family.example.co', 'other.example.com']) {
      expect(domainConfirmed(bad, 'family.example.com')).toBe(false)
    }
  })
})
