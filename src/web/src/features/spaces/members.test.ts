import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('../../api/client', () => ({
  scopesApi: vi.fn(),
  tenantsApi: vi.fn(),
}))
// The admission tag is computed under the scope's epoch key (scopes.ts, tested there); here only its threading.
vi.mock('./scopes', () => ({ admissionFor: vi.fn() }))

import {
  addGroupMember,
  listScopeMembers,
  setMemberRole,
  removeGroupMember,
  removeTenantMember,
  SetMemberRoleRequestRoleEnum,
} from './members'
import { scopesApi, tenantsApi } from '../../api/client'
import { TenantsApi, Configuration as TenantsConfiguration } from '../../api/generated/tenants'
import { admissionFor } from './scopes'
import { stubApi, fakeApi } from '../../test/apiMock'
import type { ScopeMember } from '../../api/generated/scopes'

const ADMISSION = { admissionId: 'ad000000-0000-4000-8000-000000000001', admissionEpoch: 2, admissionTag: 'dGFn' }
beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(admissionFor).mockResolvedValue(ADMISSION)
})

describe('addGroupMember (cr-0086)', () => {
  it('posts the username to the scope members endpoint and returns the grant-less directory row', async () => {
    // The added member comes back awaiting access — the key is delivered later by grant-delivery, not here.
    const row: ScopeMember = { userId: 'u1', username: 'partner', avatarRevision: 0, role: 'MEMBER', identityPublicKey: 'cGs=', hasGrant: false }
    const { addGroupMember: addGroupMemberFn } = stubApi(scopesApi, { addGroupMember: row })

    const result = await addGroupMember('g1', 'partner', 'u1')

    // The admission is tagged for the resolved target (ADR 0020 move 6) and rides the add.
    expect(admissionFor).toHaveBeenCalledWith('g1', 'u1')
    expect(addGroupMemberFn).toHaveBeenCalledWith({ scopeId: 'g1', addGroupMemberRequest: { username: 'partner', sealedNameKey: undefined, ...ADMISSION } })
    expect(result).toEqual(row)
    expect(result.hasGrant).toBe(false)
  })
})

describe('setMemberRole (cr-0088)', () => {
  it('promotes a member by patching the member endpoint with the role', async () => {
    const { setMemberRole: setRole } = stubApi(scopesApi, {
      setMemberRole: { userId: 'u1', username: 'partner', avatarRevision: 0, role: 'OWNER', identityPublicKey: 'cGs=', hasGrant: true },
    })

    const result = await setMemberRole('g1', 'u1', SetMemberRoleRequestRoleEnum.Owner)

    expect(setRole).toHaveBeenCalledWith({ scopeId: 'g1', userId: 'u1', setMemberRoleRequest: { role: 'OWNER' } })
    expect(result.role).toBe('OWNER')
  })
})

describe('removeGroupMember — the consent rescind (cr-0094)', () => {
  it('deletes a pending proposal via the member endpoint (a keyed member is removed by the removal-rotation instead)', async () => {
    const { removeMember: removeFn } = stubApi(scopesApi, { removeMember: undefined })

    await removeGroupMember('g1', 'u1')

    expect(removeFn).toHaveBeenCalledWith({ scopeId: 'g1', userId: 'u1' })
  })
})

describe('removeTenantMember (cr-0091)', () => {
  it('deletes the member via the tenant members endpoint (the server runs the whole cascade)', async () => {
    // A tenant-level cascade (cut shared, orphan rule, own-scope grace) — distinct from removeGroupMember. No
    // key crosses the wire; the tenant key is not rotated (ADR 0025). The client just fires it by (tenant, user).
    const { removeTenantMember: removeFn } = stubApi(tenantsApi, { removeTenantMember: undefined })

    await removeTenantMember('t1', 'u1')

    expect(removeFn).toHaveBeenCalledWith({ tenantId: 't1', userId: 'u1' })
  })

  // W2 (ADR 0075 point 5), asserted on the request the generated client actually sends — a `toHaveBeenCalledWith`
  // treats an `undefined` key as absent, so only the URL proves "no parameter".
  it('sends revokeLinks=true only when asked, and no parameter at all otherwise', async () => {
    const urls: string[] = []
    const fetchApi = vi.fn(async (url: RequestInfo | URL) => {
      urls.push(String(url))
      return new Response(null, { status: 204 })
    })
    const real = new TenantsApi(new TenantsConfiguration({ basePath: 'http://x', fetchApi }))
    // The real generated method, so the URL it builds is what is asserted.
    fakeApi(tenantsApi, { removeTenantMember: real.removeTenantMember.bind(real) })

    await removeTenantMember('t1', 'u1', true)
    await removeTenantMember('t1', 'u2')

    expect(urls).toEqual(['http://x/api/tenants/t1/members/u1?revokeLinks=true', 'http://x/api/tenants/t1/members/u2'])
  })
})

describe('listScopeMembers', () => {
  it('reads a scope’s member directory by id (operator-blind operational metadata only)', async () => {
    const rows: ScopeMember[] = [
      { userId: 'o', username: 'owner', avatarRevision: 0, role: 'OWNER', identityPublicKey: 'cGs=', hasGrant: true },
      { userId: 'u1', username: 'partner', avatarRevision: 0, role: 'MEMBER', identityPublicKey: 'cGs=', hasGrant: false },
    ]
    const { listScopeMembers: listFn } = stubApi(scopesApi, { listScopeMembers: rows })

    expect(await listScopeMembers('g1')).toEqual(rows)
    expect(listFn).toHaveBeenCalledWith({ scopeId: 'g1' })
  })
})
