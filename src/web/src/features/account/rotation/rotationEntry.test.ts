// uc.account.rotate-keys (web) — the ceremony's entry facts (cr-secure-the-account): the spaces that cannot get their new
// lock yet come from the PLAN (every tenant) and from the stage's own dry run on this device. No open invitation is read:
// they survive the securing, re-signed (cr-trust-anchors-survive-rotation decision 8).
import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('./finishRotation', () => ({ readRotationPlan: vi.fn() }))
vi.mock('./rotateKeys', () => ({ unreadableOnThisDevice: vi.fn() }))

import { readRotationEntry } from './rotationEntry'
import { readRotationPlan } from './finishRotation'
import { unreadableOnThisDevice } from './rotateKeys'
import type { RotationPlanScope } from '../../../api/generated/account'

const row = (o: Partial<RotationPlanScope> & Pick<RotationPlanScope, 'scopeId'>): RotationPlanScope => ({
  kind: 'GROUP', tenantId: 't1', currentEpoch: 0, wrappedGrant: 'AA==', grantedEpoch: 0, ...o,
})

beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(unreadableOnThisDevice).mockResolvedValue([])
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
})
