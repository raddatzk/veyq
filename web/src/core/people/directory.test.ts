import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('../../features/spaces/members', () => ({ listScopeMembers: vi.fn() }))
vi.mock('../../features/spaces/scopes', () => ({ openScope: vi.fn() }))
vi.mock('../../features/account/profile/memberName', () => ({ unsealName: vi.fn() }))

import { listScopeMembers as listScopeMembersFn } from '../../features/spaces/members'
import { openScope as openScopeFn } from '../../features/spaces/scopes'
import { unsealName as unsealNameFn } from '../../features/account/profile/memberName'
import { activeSpace } from '../../app/activeSpace'
import { displayNameOf, loadScopePeople, loadTenantPeople, resetPeopleDirectory } from './directory'
import { ScopeKind, type ScopeMember } from '../../api/generated/scopes'
import { openedScope, scopeMember } from '../../test/models'

const listScopeMembers = vi.mocked(listScopeMembersFn)
const openScope = vi.mocked(openScopeFn)
const unsealName = vi.mocked(unsealNameFn)
const member = (userId: string, username: string, extra: Partial<ScopeMember> = {}) => scopeMember({ userId, username, sealedName: `sealed-${userId}`, ...extra })

beforeEach(() => {
  vi.clearAllMocks()
  resetPeopleDirectory()
  activeSpace.kind = ScopeKind.Group
  activeSpace.scopeId = 'group-scope'
  unsealName.mockImplementation((_k, addr: { userId: string }) => (addr.userId === 'u1' ? { firstName: 'Anna', lastName: 'Meyer' } : null))
  openScope.mockResolvedValue(openedScope({ scopeId: 'tenant-scope' }))
})

describe('displayNameOf', () => {
  it('is the real name when it opens, else the username', () => {
    expect(displayNameOf('anna', { firstName: 'Anna', lastName: 'Meyer' })).toBe('Anna Meyer')
    expect(displayNameOf('anna', null)).toBe('anna')
    expect(displayNameOf('anna', { firstName: ' ', lastName: '' })).toBe('anna')
  })
})

describe('loadScopePeople', () => {
  it('names the group\'s members from the TENANT directory and skips a pending proposal', async () => {
    listScopeMembers.mockImplementation(async (scopeId: string) =>
      scopeId === 'group-scope'
        ? [member('u1', 'anna'), member('u2', 'ben'), member('u3', 'carl', { pendingConsent: true })]
        : [member('u1', 'anna'), member('u2', 'ben'), member('u3', 'carl')],
    )
    const people = await loadScopePeople('group-scope')
    expect(people.map((p) => [p.userId, p.displayName])).toEqual([['u1', 'Anna Meyer'], ['u2', 'ben']])
    expect(listScopeMembers).toHaveBeenCalledWith('tenant-scope')
  })

  it('still lists the members by username when the tenant names cannot be read', async () => {
    listScopeMembers.mockImplementation(async (scopeId: string) => {
      if (scopeId === 'tenant-scope') throw new Error('offline')
      return [member('u1', 'anna')]
    })
    expect((await loadScopePeople('group-scope')).map((p) => p.displayName)).toEqual(['anna'])
  })
})

describe('loadTenantPeople', () => {
  it('maps every tenant member by id; an unknown id is simply absent (a former member)', async () => {
    listScopeMembers.mockResolvedValue([member('u1', 'anna'), member('u2', 'ben')])
    const people = await loadTenantPeople()
    expect(people.get('u1')?.displayName).toBe('Anna Meyer')
    expect(people.get('u2')?.displayName).toBe('ben')
    expect(people.has('gone')).toBe(false)
  })

  it('reads the tenant roster once a minute', async () => {
    listScopeMembers.mockResolvedValue([member('u1', 'anna')])
    await loadTenantPeople()
    await loadTenantPeople()
    expect(listScopeMembers).toHaveBeenCalledTimes(1)
  })
})
