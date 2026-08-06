import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('../../api/client', () => ({
  scopesApi: vi.fn(),
  tenantsApi: vi.fn(),
}))

import {
  addGroupMember,
  listScopeMembers,
  setMemberRole,
  removeGroupMember,
  removeTenantMember,
  SetMemberRoleRequestRoleEnum,
} from './members'
import { scopesApi, tenantsApi } from '../../api/client'

beforeEach(() => vi.clearAllMocks())

describe('addGroupMember (cr-0086)', () => {
  it('posts the username to the scope members endpoint and returns the grant-less directory row', async () => {
    // The added member comes back awaiting access — the key is delivered later by grant-delivery, not here.
    const row = { userId: 'u1', username: 'partner', role: 'MEMBER', identityPublicKey: 'cGs=', hasGrant: false }
    const addGroupMemberFn = vi.fn().mockResolvedValue(row)
    vi.mocked(scopesApi).mockReturnValue({ addGroupMember: addGroupMemberFn } as unknown as ReturnType<typeof scopesApi>)

    const result = await addGroupMember('g1', 'partner')

    expect(addGroupMemberFn).toHaveBeenCalledWith({ scopeId: 'g1', addGroupMemberRequest: { username: 'partner' } })
    expect(result).toEqual(row)
    expect(result.hasGrant).toBe(false)
  })
})

describe('setMemberRole (cr-0088)', () => {
  it('promotes a member by patching the member endpoint with the role', async () => {
    const row = { userId: 'u1', username: 'partner', role: 'OWNER', identityPublicKey: 'cGs=', hasGrant: true }
    const setRole = vi.fn().mockResolvedValue(row)
    vi.mocked(scopesApi).mockReturnValue({ setMemberRole: setRole } as unknown as ReturnType<typeof scopesApi>)

    const result = await setMemberRole('g1', 'u1', SetMemberRoleRequestRoleEnum.Owner)

    expect(setRole).toHaveBeenCalledWith({ scopeId: 'g1', userId: 'u1', setMemberRoleRequest: { role: 'OWNER' } })
    expect(result.role).toBe('OWNER')
  })
})

describe('removeGroupMember (cr-0089)', () => {
  it('deletes the membership via the member endpoint (the rotation is a separate call the caller makes next)', async () => {
    const removeFn = vi.fn().mockResolvedValue(undefined)
    vi.mocked(scopesApi).mockReturnValue({ removeMember: removeFn } as unknown as ReturnType<typeof scopesApi>)

    await removeGroupMember('g1', 'u1')

    expect(removeFn).toHaveBeenCalledWith({ scopeId: 'g1', userId: 'u1' })
  })
})

describe('removeTenantMember (cr-0091)', () => {
  it('deletes the member via the tenant members endpoint (the server runs the whole cascade)', async () => {
    // A tenant-level cascade (cut shared, orphan rule, own-scope grace) — distinct from removeGroupMember. No
    // key crosses the wire; the tenant key is not rotated (ADR 0025). The client just fires it by (tenant, user).
    const removeFn = vi.fn().mockResolvedValue(undefined)
    vi.mocked(tenantsApi).mockReturnValue({ removeTenantMember: removeFn } as unknown as ReturnType<typeof tenantsApi>)

    await removeTenantMember('t1', 'u1')

    expect(removeFn).toHaveBeenCalledWith({ tenantId: 't1', userId: 'u1' })
  })
})

describe('listScopeMembers', () => {
  it('reads a scope’s member directory by id (operator-blind operational metadata only)', async () => {
    const rows = [
      { userId: 'o', username: 'owner', role: 'OWNER', identityPublicKey: 'cGs=', hasGrant: true },
      { userId: 'u1', username: 'partner', role: 'MEMBER', identityPublicKey: 'cGs=', hasGrant: false },
    ]
    const listFn = vi.fn().mockResolvedValue(rows)
    vi.mocked(scopesApi).mockReturnValue({ listScopeMembers: listFn } as unknown as ReturnType<typeof scopesApi>)

    expect(await listScopeMembers('g1')).toEqual(rows)
    expect(listFn).toHaveBeenCalledWith({ scopeId: 'g1' })
  })
})
