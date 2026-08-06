import { describe, it, expect, vi, beforeEach } from 'vitest'

// Mock the contracts client so the facade's network calls are controllable; assert it maps args onto the
// generated tenants client correctly. The error decoder uses the real generated ResponseError.
vi.mock('../../../api/client', () => ({ tenantsApi: vi.fn() }))

import {
  createTenant,
  listTenants,
  reassignInvite,
  resendInvite,
  revokeTenant,
  tenantError,
  acceptInvite,
  registerAndAccept,
} from './tenants'
import { tenantsApi } from '../../../api/client'
import { ResponseError } from '../../../api/generated/tenants'

describe('tenants facade (uc.admin.create-tenant, web Slice A)', () => {
  const api = {
    createTenant: vi.fn().mockResolvedValue({ tenantId: 't1', status: 'PENDING' }),
    listTenants: vi.fn().mockResolvedValue([]),
    resendTenantInvite: vi.fn().mockResolvedValue({}),
    reassignTenantInvite: vi.fn().mockResolvedValue({}),
    revokeTenant: vi.fn().mockResolvedValue(undefined),
    acceptTenantInvite: vi.fn().mockResolvedValue({ tenantId: 't1', status: 'ACTIVE' }),
    registerAndAcceptTenantInvite: vi.fn().mockResolvedValue({ tenantId: 't1', status: 'ACTIVE', userId: 'u1' }),
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

  it('acceptInvite threads the optional sealedName onto the accept request (cr-0106)', async () => {
    await acceptInvite('secret', 'kevin', 'AQ==', 'c2VhbGVkLW5hbWU=')
    expect(api.acceptTenantInvite).toHaveBeenCalledWith({
      acceptInviteRequest: { bootstrapSecret: 'secret', username: 'kevin', grant: 'AQ==', sealedName: 'c2VhbGVkLW5hbWU=' },
    })
  })

  it('registerAndAccept threads the sealedName; undefined when the name is skipped (cr-0106)', async () => {
    const material = {
      suiteId: 'v1', identityPublicKey: 'aQ==', wrappedIdentityPrivateKey: 'aQ==',
      authPublicKey: 'aQ==', masterKeyWraps: [], recoveryMasterKeyTag: 'aQ==',
    }
    await registerAndAccept('secret', 'kevin', material as never, 'AQ==') // grant set, no name
    expect(api.registerAndAcceptTenantInvite).toHaveBeenCalledWith(
      expect.objectContaining({
        registerAndAcceptRequest: expect.objectContaining({ username: 'kevin', grant: 'AQ==', sealedName: undefined }),
      }),
    )
  })

  it('tenantError decodes the HTTP status and the body code', async () => {
    const res = {
      status: 409,
      clone: () => ({ json: async () => ({ code: 'domain_taken' }) }),
    } as unknown as Response
    expect(await tenantError(new ResponseError(res, 'conflict'))).toEqual({ status: 409, code: 'domain_taken' })
  })

  it('tenantError returns status 0 for a non-HTTP failure', async () => {
    expect(await tenantError(new Error('network'))).toEqual({ status: 0, code: null })
  })
})
