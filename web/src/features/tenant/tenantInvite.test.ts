import { describe, it, expect, vi, beforeEach } from 'vitest'

// Mock the contracts client so the facade's network calls are controllable; assert it maps args onto the
// generated tenants client correctly. The error decoder uses the real generated ResponseError.
vi.mock('../../api/client', () => ({ tenantsApi: vi.fn() }))

import { acceptInvite, registerAndAccept, tenantError } from './tenantInvite'
import { tenantsApi } from '../../api/client'
import { stubApi } from '../../test/apiMock'
import { ResponseError } from '../../api/generated/tenants'

describe('tenant-invite accept facade (uc.admin.create-tenant, Slice B)', () => {
  const stubInvite = () =>
    stubApi(tenantsApi, {
      acceptTenantInvite: { tenantId: 't1', status: 'ACTIVE' },
      registerAndAcceptTenantInvite: { tenantId: 't1', status: 'ACTIVE', userId: 'u1' },
    })
  let api: ReturnType<typeof stubInvite>

  beforeEach(() => {
    api = stubInvite()
  })

  const BIRTH = { grant: 'AQ==', grantSignature: 'c2ln', admissionId: 'ad000000-0000-4000-8000-000000000001', admissionEpoch: 0, admissionTag: 'dGFn' }

  it('acceptInvite threads the birth and the optional sealedName onto the accept request (cr-0106)', async () => {
    await acceptInvite('secret', 'kevin', BIRTH, 'c2VhbGVkLW5hbWU=')
    expect(api.acceptTenantInvite).toHaveBeenCalledWith({
      acceptInviteRequest: { bootstrapSecret: 'secret', username: 'kevin', ...BIRTH, sealedName: 'c2VhbGVkLW5hbWU=' },
    })
  })

  it('registerAndAccept threads the sealedName; undefined when the name is skipped (cr-0106)', async () => {
    const material = {
      suiteId: 'v1', identityPublicKey: 'aQ==', wrappedIdentityPrivateKey: 'aQ==',
      authPublicKey: 'aQ==', masterKeyWraps: [], secretKeyProofPublicKey: 'aQ==',
    }
    const userId = '5c09e000-0000-4000-8000-0000000000a7' // client-minted: a sealed name is bound to it (ADR 0072)
    await registerAndAccept('secret', userId, 'kevin', material as never, BIRTH) // birth set, no name
    expect(api.registerAndAcceptTenantInvite).toHaveBeenCalledWith(
      expect.objectContaining({
        registerAndAcceptRequest: expect.objectContaining({ userId, username: 'kevin', ...BIRTH, sealedName: undefined }),
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
