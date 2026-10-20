// @vitest-environment happy-dom
// uc.moderation.report-to-an-authority + uc.moderation.authority-order (web) — a case's dealings with an authority:
//   - the export is fetched only on request and laid out as a text block to copy — no download, no content;
//   - a handover is confirmed, then recorded with its reference, and the case it answers with replaces the shown one;
//   - an order of the case shows its reference opened here and its Annex II text rendered again from it.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

const ask = vi.hoisted(() => vi.fn())
vi.mock('../../../shared/state/useConfirm', () => ({ useConfirm: () => ({ ask }) }))
vi.mock('../cases', () => ({ getExport: vi.fn(), recordHandover: vi.fn() }))
vi.mock('./orders', () => ({ openReferenceOf: vi.fn() }))

import AuthoritySection from './AuthoritySection.vue'
import { getExport, recordHandover } from '../cases'
import { openReferenceOf } from './orders'
import { ORDER_FORMS } from './orderForms'
import { realI18n, t } from '../../../test/i18n'
import type { AuthorityOrder, ModerationCase } from '../../../api/generated/moderation'

const CASE = '1c2d3e4f-5a6b-4f2c-9a1e-7b3d4e5f9a8b'
const SHARE = '3q2-7v8Xk1p0aZ9bQ4rT6yW-uJ5nD2fL8sB1cE0gH3i'

const order: AuthorityOrder = {
  id: '3e4f5a6b-7c8d-4e9f-8a0b-1c2d3e4f5a6b', kind: 'REMOVAL', shareId: SHARE, receivedAt: new Date('2026-10-18T07:12:00Z'),
  enteredAt: new Date('2026-10-18T07:20:11Z'), enteredBy: 'mod', found: true, blockedAt: new Date('2026-10-18T07:20:11Z'),
  caseId: CASE, holdUntil: new Date('2027-04-19T07:20:11Z'), statementDeferredUntil: undefined, epoch: 2, sealedReference: 'c2VhbGVk',
  providerName: undefined, contactPoint: 'abuse@example.org',
}

const caseOf = (over: Partial<ModerationCase> = {}): ModerationCase => ({
  id: CASE, itemKind: 'NODE', itemId: 'node', scopeId: 'scope', tenantId: undefined, tenantDomain: undefined, category: 'TERRORIST_CONTENT',
  state: 'DECIDED', decidedAt: new Date(), closedAt: undefined, hold: undefined, reports: [], measures: [], proposals: [],
  targets: { links: [], uploaders: [] }, events: [], statementForm: 'FULL', objections: [], origin: 'ORDER', orders: [order], ...over,
})

const stubs = {
  Button: { props: ['label'], emits: ['click'], template: '<button :class="$attrs.class" @click="$emit(\'click\')">{{ label }}</button>' },
}

function mountSection(c: ModerationCase = caseOf()) {
  return mount(AuthoritySection, { props: { current: c, busy: false }, global: { plugins: [realI18n()], stubs } })
}

describe('AuthoritySection', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    ask.mockResolvedValue(true)
    vi.mocked(openReferenceOf).mockReturnValue('BKA ST-2026-0417')
  })

  it('shows the order with its reference opened and its Annex II text, the draft banner first', () => {
    const w = mountSection()
    expect(w.find('.moderation-case-order').text()).toContain('BKA ST-2026-0417')
    const text = (w.find('.moderation-case-order-text').element as HTMLTextAreaElement).value
    expect(text.split('\n')[0]).toBe(ORDER_FORMS.en.draftBanner)
    expect(text).toContain('Reference of the removal order: BKA ST-2026-0417')
    expect(text).toContain('Access disabled at: 2026-10-18 07:20:11 UTC')
  })

  it('fetches the export only on request and lays it out as a text block to copy', async () => {
    vi.mocked(getExport).mockResolvedValue({
      caseId: CASE, category: 'TERRORIST_CONTENT', origin: 'ORDER', timeZone: 'UTC', reportedAt: [], measures: [], uploaders: [],
      guestWrites: [{ identityId: 'id', objectKind: 'NODE', objectId: 'obj', address: '203.0.113.77', port: 51234, observedAt: new Date('2026-10-17T08:59:12.345Z') }],
      held: true,
    })
    const w = mountSection()
    expect(getExport).not.toHaveBeenCalled()
    expect(w.find('.moderation-export-text').exists()).toBe(false)
    await w.find('.moderation-export-prepare').trigger('click')
    await flushPromises()
    expect(getExport).toHaveBeenCalledWith(CASE)
    const text = (w.find('.moderation-export-text').element as HTMLTextAreaElement).value
    expect(text).toContain(`${t('moderation.authority.labels.case')}: ${CASE}`)
    expect(text).toContain('203.0.113.77')
    expect(text).toContain('51234')
    expect(text).toContain(`${t('moderation.authority.labels.timeZone')}: UTC`)
    // a text block, never a file
    expect(w.find('a[download]').exists()).toBe(false)
  })

  it('records a handover after confirming, with its reference, and passes the answered case on', async () => {
    const answered = caseOf({ state: 'CLOSED' })
    vi.mocked(recordHandover).mockResolvedValue(answered)
    const w = mountSection()
    await w.find('.moderation-handover-reference').setValue('BKA 2026/17')
    await w.find('.moderation-handover-reported').trigger('click')
    await flushPromises()
    expect(ask).toHaveBeenCalledTimes(1)
    expect(recordHandover).toHaveBeenCalledWith(CASE, 'REPORTED', 'BKA 2026/17')
    expect(w.emitted('updated')![0]).toEqual([answered])
    // Declined: nothing recorded.
    ask.mockResolvedValue(false)
    await w.find('.moderation-handover-information').trigger('click')
    await flushPromises()
    expect(recordHandover).toHaveBeenCalledTimes(1)
  })

  // ADR 0093 § 4: on a child-sexual-abuse case the handover ends the hold — the hint and the confirmation say so before
  // it is recorded; on any other case they say it changes nothing else (the control).
  it('says before a child-sexual-abuse case\'s handover that it ends the hold, and on another case that it does not', async () => {
    vi.mocked(recordHandover).mockResolvedValue(caseOf())
    const csam = mountSection(caseOf({ category: 'CHILD_SEXUAL_ABUSE', origin: 'REPORT', orders: [] }))
    expect(csam.find('.moderation-authority').text()).toContain(t('moderation.authority.handoverHintCsam'))
    await csam.find('.moderation-handover-reported').trigger('click')
    await flushPromises()
    expect(ask).toHaveBeenLastCalledWith(expect.objectContaining({ message: t('moderation.authority.handoverConfirmCsam') }))

    const other = mountSection()
    expect(other.find('.moderation-authority').text()).toContain(t('moderation.authority.handoverHint'))
    expect(other.find('.moderation-authority').text()).not.toContain(t('moderation.authority.handoverHintCsam'))
    await other.find('.moderation-handover-reported').trigger('click')
    await flushPromises()
    expect(ask).toHaveBeenLastCalledWith(expect.objectContaining({ message: t('moderation.authority.handoverConfirm') }))
  })
})
