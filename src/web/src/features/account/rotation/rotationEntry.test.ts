// uc.account.rotate-keys (web) — the ceremony's entry facts (cr-secure-the-account decision 11, § Order `factInvites`):
// the spaces that cannot get their new lock yet come from the PLAN (every tenant) and from the stage's own dry run on
// this device; the invitations line shows only when an open invite of a tenant the account is in was signed by it.
import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('./finishRotation', () => ({ readRotationPlan: vi.fn() }))
vi.mock('./rotateKeys', () => ({ unreadableOnThisDevice: vi.fn() }))
vi.mock('../../../api/client', () => ({ scopesApi: vi.fn() }))
vi.mock('../../../app/sessionRefresh', () => ({ withRefresh: (f: () => unknown) => f() }))

import { readRotationEntry } from './rotationEntry'
import { readRotationPlan } from './finishRotation'
import { unreadableOnThisDevice } from './rotateKeys'
import { scopesApi } from '../../../api/client'
import { account } from '../../../app/account'
import type { RotationPlanScope } from '../../../api/generated/account'
import type { OpenInvite, ScopesApi } from '../../../api/generated/scopes'
import { fakeApi } from '../../../test/apiMock'

const row = (o: Partial<RotationPlanScope> & Pick<RotationPlanScope, 'scopeId'>): RotationPlanScope => ({
  kind: 'GROUP', tenantId: 't1', currentEpoch: 0, wrappedGrant: 'AA==', grantedEpoch: 0, ...o,
})
const invite = (signerUserId: string): OpenInvite => ({
  inviteId: `i-${signerUserId}`, invitePublicKey: 'AA==', inviteKeySignature: 'AA==', signerUserId, signerIdentityPublicKey: 'AA==',
  signerKeyChainHead: 'AA==', expiresAt: new Date(0),
})
const listOpenInvites = vi.fn<ScopesApi['listOpenInvites']>()

beforeEach(() => {
  vi.clearAllMocks()
  account.userId = 'me'
  vi.mocked(unreadableOnThisDevice).mockResolvedValue([])
  listOpenInvites.mockResolvedValue([])
  fakeApi(scopesApi, { listOpenInvites })
})

describe('readRotationEntry', () => {
  it('names each blocked space with the plan’s reason; one this device cannot open reads unreadable, whatever the plan says', async () => {
    vi.mocked(readRotationPlan).mockResolvedValue([
      row({ scopeId: 'paused', blocked: 'tenant_suspended' }),
      row({ scopeId: 'going', blocked: 'scope_deleting' }),
      row({ scopeId: 'closed' }),
      row({ scopeId: 'closed-and-paused', blocked: 'tenant_suspended' }),
      row({ scopeId: 'fine' }),
    ])
    vi.mocked(unreadableOnThisDevice).mockResolvedValue(['closed', 'closed-and-paused'])
    const { blocked } = await readRotationEntry()
    expect(blocked.map((b) => [b.scopeId, b.reason])).toEqual([
      ['paused', 'tenant_suspended'],
      ['going', 'scope_deleting'],
      ['closed', 'unreadable'],
      ['closed-and-paused', 'unreadable'],
    ])
  })

  it('a dry run that fails lists the plan’s reasons alone, never a guess', async () => {
    vi.mocked(readRotationPlan).mockResolvedValue([row({ scopeId: 'paused', blocked: 'tenant_suspended' }), row({ scopeId: 'fine' })])
    vi.mocked(unreadableOnThisDevice).mockRejectedValue(new Error('offline'))
    expect((await readRotationEntry()).blocked.map((b) => b.scopeId)).toEqual(['paused'])
  })

  it('signed open invitations: an open invite of a tenant this account is in, signed by it', async () => {
    vi.mocked(readRotationPlan).mockResolvedValue([row({ scopeId: 't1', kind: 'TENANT' }), row({ scopeId: 't2', kind: 'TENANT', tenantId: 't2' }), row({ scopeId: 'g' })])
    listOpenInvites.mockImplementation(async ({ scopeId }) => (scopeId === 't2' ? [invite('someone'), invite('me')] : [invite('someone')]))
    expect((await readRotationEntry()).signedOpenInvites).toBe(true)
    expect(listOpenInvites.mock.calls.map(([r]) => r.scopeId).sort()).toEqual(['t1', 't2']) // tenants only
  })

  it('control: open invites signed by someone else, or none, are not this account’s to lose', async () => {
    vi.mocked(readRotationPlan).mockResolvedValue([row({ scopeId: 't1', kind: 'TENANT' })])
    listOpenInvites.mockResolvedValue([invite('someone')])
    expect((await readRotationEntry()).signedOpenInvites).toBe(false)
  })
})
