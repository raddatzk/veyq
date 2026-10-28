import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('../../features/spaces/members', () => ({ listScopeMembers: vi.fn() }))
vi.mock('../../features/spaces/scopes', () => ({ openScope: vi.fn() }))
vi.mock('../../features/account/profile/memberName', () => ({ unsealNameUnderEpochs: vi.fn() }))
vi.mock('./avatarCache', () => ({ syncAvatars: vi.fn(() => Promise.resolve()), resetAvatars: vi.fn(), avatarUrlOf: vi.fn() }))

import { listScopeMembers as listScopeMembersFn } from '../../features/spaces/members'
import { openScope as openScopeFn } from '../../features/spaces/scopes'
import { unsealNameUnderEpochs as unsealNameFn } from '../../features/account/profile/memberName'
import { activeSpace } from '../../app/activeSpace'
import { displayNameOf, loadScopePeople, loadTenantPeople, personNow, resetPeopleDirectory, refreshPeopleDirectory } from './directory'
import { syncAvatars as syncAvatarsFn, resetAvatars as resetAvatarsFn } from './avatarCache'
import { ScopeKind, type ScopeMember } from '../../api/generated/scopes'
import { openedScope, scopeMember } from '../../test/models'

const listScopeMembers = vi.mocked(listScopeMembersFn)
const openScope = vi.mocked(openScopeFn)
const unsealName = vi.mocked(unsealNameFn)
const syncAvatars = vi.mocked(syncAvatarsFn)
const resetAvatars = vi.mocked(resetAvatarsFn)
const member = (userId: string, username: string, extra: Partial<ScopeMember> = {}) => scopeMember({ userId, username, sealedName: `sealed-${userId}`, ...extra })

beforeEach(() => {
  vi.clearAllMocks()
  resetPeopleDirectory()
  activeSpace.kind = ScopeKind.Group
  activeSpace.scopeId = 'group-scope'
  unsealName.mockImplementation((_e, addr: { userId: string }) => (addr.userId === 'u1' ? { name: { firstName: 'Anna', lastName: 'Meyer' }, epoch: 0 } : null))
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

describe('personNow: the roster already on the device, answered synchronously', () => {
  it('knows nobody before the first read, then every member read, and forgets them on a reset', async () => {
    listScopeMembers.mockResolvedValue([member('u1', 'anna')])
    expect(personNow('u1')).toBeUndefined()
    await loadTenantPeople()
    expect(personNow('u1')?.username).toBe('anna')
    expect(personNow('gone')).toBeUndefined()
    resetPeopleDirectory()
    expect(personNow('u1')).toBeUndefined()
  })
})

describe('the profile pictures follow the roster (ADR 0084)', () => {
  it('hands the cache the tenant, its scope, a COPY of the key and the version integers only', async () => {
    const tenantKey = new Uint8Array(32).fill(9)
    openScope.mockResolvedValue(openedScope({ scopeId: 'tenant-scope', tenantId: 'tenant-9', key: tenantKey }))
    listScopeMembers.mockResolvedValue([member('u1', 'anna', { avatarRevision: 3 }), member('u2', 'ben')])
    await loadTenantPeople()
    expect(syncAvatars).toHaveBeenCalledTimes(1)
    const [source, rows] = syncAvatars.mock.calls[0]
    expect(source.tenantId).toBe('tenant-9')
    expect(source.scopeId).toBe('tenant-scope')
    expect(source.epochs!.keys.get(0)).not.toBe(tenantKey) // the scope's own keys may be zeroed under it; the cache holds a copy
    expect(rows.map((r) => [r.userId, r.avatarRevision])).toEqual([['u1', 3], ['u2', 0]])
  })

  it('zeroes the copy once the fetches settle', async () => {
    listScopeMembers.mockResolvedValue([member('u1', 'anna', { avatarRevision: 1 })])
    await loadTenantPeople()
    await Promise.resolve()
    await Promise.resolve()
    expect([...syncAvatars.mock.calls[0][0].epochs!.keys.values()].every((k) => k.every((b) => b === 0))).toBe(true)
  })

  it('syncs once per roster read (the minute cache), and refreshPeopleDirectory re-reads at once', async () => {
    listScopeMembers.mockResolvedValue([member('u1', 'anna', { avatarRevision: 1 })])
    await loadTenantPeople()
    await loadTenantPeople()
    expect(syncAvatars).toHaveBeenCalledTimes(1)
    await refreshPeopleDirectory()
    expect(listScopeMembers).toHaveBeenCalledTimes(2)
    expect(syncAvatars).toHaveBeenCalledTimes(2)
  })

  it('a group-scope read also syncs (the tenant roster it names from carries the versions)', async () => {
    listScopeMembers.mockResolvedValue([member('u1', 'anna', { avatarRevision: 2 })])
    await loadScopePeople('group-scope')
    expect(syncAvatars).toHaveBeenCalledTimes(1)
  })

  it('does not wait for the pictures: names are returned while a fetch is still pending', async () => {
    syncAvatars.mockReturnValueOnce(new Promise(() => {}))
    listScopeMembers.mockResolvedValue([member('u1', 'anna', { avatarRevision: 2 })])
    expect((await loadTenantPeople()).get('u1')?.displayName).toBe('Anna Meyer')
  })

  it('resetPeopleDirectory (sign-out, re-lock) resets the picture cache so its object URLs are revoked', async () => {
    resetAvatars.mockClear()
    resetPeopleDirectory()
    expect(resetAvatars).toHaveBeenCalledTimes(1)
  })
})
