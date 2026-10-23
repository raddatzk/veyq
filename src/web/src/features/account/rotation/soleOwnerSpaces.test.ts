// uc.account.rotate-keys E4 (inbox #519): the spaces listed before the button are exactly the live shared ones this
// account owns alone while someone else is in them — each case a negative beside its positive on the same rows.
import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('../../spaces/scopes', () => ({ listMyScopes: vi.fn(), listMyGroups: vi.fn(), listMyTenants: vi.fn() }))
vi.mock('../../spaces/members', () => ({ listScopeMembers: vi.fn() }))

import { soleOwnerSpaces } from './soleOwnerSpaces'
import { listMyGroups, listMyScopes, listMyTenants, type GroupSpace } from '../../spaces/scopes'
import { listScopeMembers } from '../../spaces/members'
import { ScopeKind, type ScopeMember } from '../../../api/generated/scopes'
import { myTenant, scopeMember, scopeSummary } from '../../../test/models'

const owner = scopeMember({ userId: 'me', role: 'OWNER' })
const other = (role: ScopeMember['role'] = 'MEMBER') => scopeMember({ userId: crypto.randomUUID(), role })
const group = (scopeId: string, name: string) => ({ scopeId, name }) as unknown as GroupSpace

beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(listMyGroups).mockResolvedValue([group('g-alone-owner', 'Urlaub'), group('g-two-owners', 'Team')])
  vi.mocked(listMyTenants).mockResolvedValue([myTenant({ tenantId: 't', name: 'Rekey Test' })])
})

describe('soleOwnerSpaces', () => {
  it('lists a shared space this account owns alone, by its name, and none it shares ownership of or is alone in', async () => {
    vi.mocked(listMyScopes).mockResolvedValue([
      scopeSummary({ id: 't', tenantId: 't', kind: ScopeKind.Tenant }),
      scopeSummary({ id: 'g-alone-owner', kind: ScopeKind.Group }),
      scopeSummary({ id: 'g-two-owners', kind: ScopeKind.Group }),
      scopeSummary({ id: 'g-only-me', kind: ScopeKind.Group }),
      scopeSummary({ id: 'p', kind: ScopeKind.Personal }),
      scopeSummary({ id: 'g-member', kind: ScopeKind.Group, role: 'MEMBER' }),
      scopeSummary({ id: 'g-deleted', kind: ScopeKind.Group, deletedAt: new Date() }),
    ])
    vi.mocked(listScopeMembers).mockImplementation(async (id) => ({
      't': [owner, other()],
      'g-alone-owner': [owner, other(), other()],
      'g-two-owners': [owner, other('OWNER')],
      'g-only-me': [owner],
      'p': [owner, other()],
      'g-member': [owner, other()],
      'g-deleted': [owner, other()],
    })[id]!)
    expect(await soleOwnerSpaces()).toEqual([
      { scopeId: 't', name: 'Rekey Test' },
      { scopeId: 'g-alone-owner', name: 'Urlaub' },
    ])
  })

  it('names a space this device cannot open as null, and leaves out one whose member list does not load', async () => {
    vi.mocked(listMyScopes).mockResolvedValue([
      scopeSummary({ id: 'g-sealed', kind: ScopeKind.Group }),
      scopeSummary({ id: 'g-unread', kind: ScopeKind.Group }),
    ])
    vi.mocked(listScopeMembers).mockImplementation(async (id) => {
      if (id === 'g-unread') throw new Error('offline')
      return [owner, other()]
    })
    expect(await soleOwnerSpaces()).toEqual([{ scopeId: 'g-sealed', name: null }])
  })
})
