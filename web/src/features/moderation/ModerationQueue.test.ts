// @vitest-environment happy-dom
// uc.moderation.review-a-case step 1 (web) — the queue: each case in clear fields (category, item kind, times, count,
// tenant domain, state, hold), in the server's order; the closed cases on request; opening a case shows its details and
// coming back reads the queue again.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { httpError } from '../../test/httpError'

vi.mock('./cases', () => ({ listCases: vi.fn() }))

import ModerationQueue from './ModerationQueue.vue'
import { listCases } from './cases'
import { realI18n, t } from '../../test/i18n'
import type { ModerationCaseSummary } from '../../api/generated/moderation'

const stubs = {
  Button: { props: ['label'], emits: ['click'], template: '<button :class="$attrs.class" @click="$emit(\'click\')">{{ label }}</button>' },
  Message: { template: '<div class="msg"><slot /></div>' },
  CaseDetail: { props: ['caseId'], emits: ['back'], template: '<div class="case-detail-stub">{{ caseId }}<button class="stub-back" @click="$emit(\'back\')" /></div>' },
}

const summary = (over: Partial<ModerationCaseSummary> = {}): ModerationCaseSummary => ({
  id: 'case-1',
  itemKind: 'ITEM',
  category: 'THREAT_TO_LIFE',
  state: 'OPEN',
  firstReportedAt: new Date('2026-10-17T09:00:00Z'),
  lastReportedAt: new Date('2026-10-17T11:00:00Z'),
  reportCount: 2,
  tenantDomain: 'club.example.org',
  objectionOpen: false,
  holdEndsAt: new Date('2027-01-15T09:00:00Z'),
  ...over,
})

async function mountQueue() {
  const w = mount(ModerationQueue, { global: { plugins: [realI18n()], stubs } })
  await flushPromises()
  return w
}

describe('ModerationQueue', () => {
  beforeEach(() => vi.clearAllMocks())

  it('lists each case in clear fields, in the server’s order', async () => {
    vi.mocked(listCases).mockResolvedValue([
      summary(),
      summary({ id: 'case-2', category: 'TERMS_BREACH', itemKind: 'LINK', state: 'DECIDED', reportCount: 1, tenantDomain: null, holdEndsAt: null }),
    ])
    const w = await mountQueue()
    expect(listCases).toHaveBeenCalledWith(undefined)
    const rows = w.findAll('.moderation-case-row')
    expect(rows).toHaveLength(2)
    expect(rows[0].text()).toContain(t('report.categories.THREAT_TO_LIFE'))
    expect(rows[0].text()).toContain(t('moderation.itemKinds.ITEM'))
    expect(rows[0].text()).toContain('club.example.org')
    expect(rows[0].text()).toContain(t('moderation.caseRow.reports', { count: 2 }, 2))
    expect(rows[0].text()).toContain(t('moderation.states.OPEN'))
    expect(rows[1].text()).toContain(t('report.categories.TERMS_BREACH'))
    expect(rows[1].text()).toContain(t('moderation.caseRow.noDomain'))
    expect(rows[1].text()).toContain(t('moderation.caseRow.notHeld'))
    expect(rows[1].text()).toContain(t('moderation.states.DECIDED'))
  })

  it('marks a case whose objection waits for a decision, and only that one', async () => {
    vi.mocked(listCases).mockResolvedValue([summary({ objectionOpen: true }), summary({ id: 'case-2' })])
    const w = await mountQueue()
    const rows = w.findAll('.moderation-case-row')
    expect(rows[0].find('.moderation-objection-badge').text()).toBe(t('moderation.caseRow.objection'))
    expect(rows[1].find('.moderation-objection-badge').exists()).toBe(false)
  })

  it('an empty queue says nothing was reported', async () => {
    vi.mocked(listCases).mockResolvedValue([])
    const w = await mountQueue()
    expect(w.text()).toContain(t('moderation.queueEmpty'))
  })

  it('shows the closed cases on request', async () => {
    vi.mocked(listCases).mockResolvedValue([])
    const w = await mountQueue()
    await w.find('.moderation-queue-filter').trigger('click')
    await flushPromises()
    expect(listCases).toHaveBeenLastCalledWith('CLOSED')
    expect(w.text()).toContain(t('moderation.queueClosedEmpty'))
  })

  it('opens a case, and reads the queue again on the way back', async () => {
    vi.mocked(listCases).mockResolvedValue([summary()])
    const w = await mountQueue()
    await w.find('.moderation-case-open').trigger('click')
    expect(w.find('.case-detail-stub').text()).toContain('case-1')
    await w.find('.stub-back').trigger('click')
    await flushPromises()
    expect(listCases).toHaveBeenCalledTimes(2)
    expect(w.find('.moderation-case-row').exists()).toBe(true)
  })

  it('a refused proof asks to reload and unlock again', async () => {
    vi.mocked(listCases).mockRejectedValue(httpError(403, 'moderation_proof_invalid'))
    const w = await mountQueue()
    expect(w.text()).toContain(t('moderation.errors.reload'))
  })
})
