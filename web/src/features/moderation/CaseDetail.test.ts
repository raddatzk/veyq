// @vitest-environment happy-dom
// uc.moderation.review-a-case steps 2–3, uc.moderation.take-a-measure (web) — a case's details (the reports and the log's
// notes opened in the browser, the hold, the measures, the proposals), and the decision panel: each decision is
// confirmed, calls its own `cases.ts` function with the case id, the target and the note, and the case it answers with
// replaces the one shown. Every error code an operation names reads as its own message.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { httpError } from '../../test/httpError'

const ask = vi.hoisted(() => vi.fn())
vi.mock('../../shared/state/useConfirm', () => ({ useConfirm: () => ({ ask }) }))
vi.mock('./cases', () => ({
  getCase: vi.fn(),
  dismissCase: vi.fn(),
  takeMeasure: vi.fn(),
  liftMeasure: vi.fn(),
  proposeStep: vi.fn(),
  extendHold: vi.fn(),
  barReporter: vi.fn(),
  openNote: vi.fn(),
  openReportOf: vi.fn(),
  reviewUrl: vi.fn(),
}))

import CaseDetail from './CaseDetail.vue'
import { barReporter, dismissCase, extendHold, getCase, liftMeasure, openNote, openReportOf, proposeStep, reviewUrl, takeMeasure } from './cases'
import { realI18n, t } from '../../test/i18n'
import type { ModerationCase } from '../../api/generated/moderation'

const CASE = '1c2d3e4f-5a6b-4f2c-9a1e-7b3d4e5f9a8b'
const ITEM = '4f2c9a1e-7b3d-4e5f-9a8b-1c2d3e4f5a6b'
const TENANT = '5b6c7c3f-0e2a-4b1d-8a6e-8b2c1f2d3e4a'
const MEASURE = '9a8b1c2d-3e4f-4a6b-8f2c-9a1e7b3d4e5f'
const REPORT = '4e5f9a8b-1c2d-4e4f-8a6b-4f2c9a1e7b3d'
const REPORT_2 = '7e5f9a8b-1c2d-4e4f-8a6b-4f2c9a1e7b3d'
const GUEST = '6e8b2c1f-2d3e-4a5b-8c7c-3f0e2a9b1d4a'
const MEMBER = '8e8b2c1f-2d3e-4a5b-8c7c-3f0e2a9b1d4a'
const MODERATOR = '2c1f2d3e-4a5b-4c7c-8f0e-2a9b1d4a6e8b'
const BLOCKED_LINK = 'blockedblockedblockedblockedblockedblocked1'
const OPEN_LINK = 'reachesreachesreachesreachesreachesreache1'

const caseOf = (over: Partial<ModerationCase> = {}): ModerationCase => ({
  id: CASE,
  itemKind: 'ITEM',
  itemId: ITEM,
  scopeId: '7c3f0e2a-9b1d-4a6e-8b2c-1f2d3e4a5b6c',
  tenantId: TENANT,
  tenantDomain: 'club.example.org',
  category: 'TERMS_BREACH',
  state: 'OPEN',
  decidedAt: null,
  closedAt: null,
  hold: { endsAt: new Date('2027-01-15T09:00:00Z'), trigger: 'CASE_OPEN' },
  reports: [
    { id: REPORT, epoch: 2, receivedAt: new Date('2026-10-17T09:00:00Z'), category: 'TERMS_BREACH', sealedReport: 'AQ==', reporter: 'LINK', barred: false },
    { id: REPORT_2, epoch: 1, receivedAt: new Date('2026-10-17T10:00:00Z'), category: 'ILLEGAL_CONTENT', sealedReport: 'Ag==', reporter: 'ACCOUNT', barred: true },
  ],
  measures: [{ id: MEASURE, kind: 'SHARE', targetId: BLOCKED_LINK, takenBy: MODERATOR, takenAt: new Date('2026-10-17T12:00:00Z'), liftedAt: null, liftedBy: null }],
  proposals: [],
  targets: {
    links: [
      { shareId: BLOCKED_LINK, role: 'READ', relation: 'REPORTED_THROUGH', blocked: true },
      { shareId: OPEN_LINK, role: 'READ_WRITE', relation: 'REACHES', blocked: false },
    ],
    uploaders: [
      { kind: 'GUEST_IDENTITY', id: GUEST, shareId: OPEN_LINK, blocked: false },
      { kind: 'ACCOUNT', id: MEMBER, shareId: null, blocked: false },
    ],
  },
  events: [
    { event: 'RECEIVED', at: new Date('2026-10-17T09:00:00Z'), moderatorId: null, detail: null, epoch: null, sealedText: null, until: null },
    { event: 'HOLD_EXTENDED', at: new Date('2026-10-17T13:00:00Z'), moderatorId: MODERATOR, detail: null, epoch: 2, sealedText: 'c2VhbGVk', until: new Date('2027-03-01T00:00:00Z') },
  ],
  ...over,
})

const stubs = {
  Button: {
    props: ['label'],
    emits: ['click'],
    template: '<button :class="$attrs.class" :type="$attrs.type ?? \'button\'" @click="$emit(\'click\')">{{ label }}</button>',
  },
  Message: { template: '<div class="msg"><slot /></div>' },
  DatePicker: { name: 'DatePicker', props: ['modelValue'], emits: ['update:modelValue'], template: '<input class="datepicker" />' },
}

async function mountCase(c: ModerationCase = caseOf()): Promise<VueWrapper> {
  vi.mocked(getCase).mockResolvedValue(c)
  const w = mount(CaseDetail, { props: { caseId: CASE }, global: { plugins: [realI18n()], stubs } })
  await flushPromises()
  return w
}

async function click(w: VueWrapper, selector: string) {
  await w.find(selector).trigger('click')
  await flushPromises()
}

describe('CaseDetail', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    ask.mockResolvedValue(true)
    vi.mocked(openReportOf).mockImplementation((epoch) =>
      epoch === 2 ? { reportKey: new Uint8Array(32), reason: 'it breaks the terms', contact: '', context: 'seen through a link' } : null,
    )
    vi.mocked(openNote).mockReturnValue('an authority asked')
  })

  it('reads the case and opens its reports and notes in the browser', async () => {
    const w = await mountCase()
    expect(getCase).toHaveBeenCalledWith(CASE)
    const [first, second] = w.findAll('.moderation-report')
    expect(first.text()).toContain('it breaks the terms')
    expect(first.text()).toContain(t('moderation.case.noContact'))
    expect(first.text()).toContain('seen through a link')
    expect(second.text()).toContain(t('moderation.case.cannotOpen'))
    expect(second.text()).toContain(t('moderation.case.barred'))
    expect(openNote).toHaveBeenCalledWith(2, CASE, 'c2VhbGVk')
    expect(w.find('.moderation-event-note').text()).toBe('an authority asked')
    expect(w.text()).toContain(t('moderation.case.holdTriggers.CASE_OPEN'))
  })

  it('offers each step only on a target not yet blocked', async () => {
    const w = await mountCase()
    expect(w.findAll('.moderation-block-link')).toHaveLength(1)
    expect(w.find('.moderation-block-link').text()).toContain(OPEN_LINK.slice(0, 8))
    expect(w.findAll('.moderation-bar')).toHaveLength(1)
    expect(w.findAll('.moderation-lift')).toHaveLength(1)
    expect(w.find('.moderation-block-content').exists()).toBe(true)
    expect(w.findAll('.moderation-block-guest')).toHaveLength(1)
    expect(w.findAll('.moderation-propose-sign-in')).toHaveLength(1)
    expect(w.find('.moderation-propose-offline').exists()).toBe(true)
  })

  const decisions: [string, () => unknown, unknown[]][] = [
    ['.moderation-dismiss', () => dismissCase, [CASE, 'a sealed note']],
    ['.moderation-block-link', () => takeMeasure, [CASE, 'SHARE', { shareId: OPEN_LINK }, 'a sealed note']],
    ['.moderation-block-content', () => takeMeasure, [CASE, 'CONTENT', { targetId: ITEM }, 'a sealed note']],
    ['.moderation-block-guest', () => takeMeasure, [CASE, 'GUEST_IDENTITY', { targetId: GUEST }, 'a sealed note']],
    ['.moderation-propose-sign-in', () => proposeStep, [CASE, 'MEMBER_SIGN_IN', MEMBER, 'a sealed note']],
    ['.moderation-propose-offline', () => proposeStep, [CASE, 'TENANT_OFFLINE', null, 'a sealed note']],
    ['.moderation-lift', () => liftMeasure, [CASE, MEASURE, 'a sealed note']],
    ['.moderation-bar', () => barReporter, [CASE, REPORT, 'a sealed note']],
  ]

  it.each(decisions)('%s is confirmed, then called with the case, its target and the note', async (selector, fn, args) => {
    const call = fn() as ReturnType<typeof vi.fn>
    vi.mocked(call).mockResolvedValue(caseOf({ state: 'DECIDED' }))
    const w = await mountCase()
    await w.find('.moderation-decision-note').setValue('a sealed note')
    await click(w, selector)
    expect(ask).toHaveBeenCalledTimes(1)
    expect(call).toHaveBeenCalledWith(...args)
    // The case it answers with replaces the shown one, and the note is spent.
    expect(w.text()).toContain(t('moderation.states.DECIDED'))
    expect((w.find('.moderation-decision-note').element as HTMLTextAreaElement).value).toBe('')
  })

  it('a declined confirmation takes no step', async () => {
    ask.mockResolvedValue(false)
    const w = await mountCase()
    await click(w, '.moderation-block-content')
    expect(takeMeasure).not.toHaveBeenCalled()
  })

  it('extends the hold with a new end and a reason', async () => {
    const until = new Date('2027-03-01T00:00:00Z')
    vi.mocked(extendHold).mockResolvedValue(caseOf({ hold: { endsAt: until, trigger: 'EXTENDED' } }))
    const w = await mountCase()
    w.findComponent({ name: 'DatePicker' }).vm.$emit('update:modelValue', until)
    await w.find('.moderation-extend-reason').setValue('an authority asked')
    await w.find('.moderation-extend').trigger('submit')
    await flushPromises()
    expect(extendHold).toHaveBeenCalledWith(CASE, until, 'an authority asked')
    expect(w.text()).toContain(t('moderation.case.holdTriggers.EXTENDED'))
  })

  it('an extension without a reason is not sent', async () => {
    const w = await mountCase()
    w.findComponent({ name: 'DatePicker' }).vm.$emit('update:modelValue', new Date('2027-03-01T00:00:00Z'))
    await w.find('.moderation-extend').trigger('submit')
    await flushPromises()
    expect(extendHold).not.toHaveBeenCalled()
    expect(w.text()).toContain(t('moderation.errors.reasonRequired'))
  })

  it.each([
    [400, 'extension_out_of_bounds', 'moderation.errors.extensionOutOfBounds'],
    [409, 'nothing_held', 'moderation.errors.nothingHeld'],
    [409, 'moderation_epoch_changed', 'moderation.errors.reload'],
  ])('an extension refused with %i %s says so', async (status, code, key) => {
    vi.mocked(extendHold).mockRejectedValue(httpError(status, code))
    const w = await mountCase()
    w.findComponent({ name: 'DatePicker' }).vm.$emit('update:modelValue', new Date('2027-03-01T00:00:00Z'))
    await w.find('.moderation-extend-reason').setValue('an authority asked')
    await w.find('.moderation-extend').trigger('submit')
    await flushPromises()
    expect(w.find('.moderation-case-error').text()).toBe(t(key))
  })

  it.each([
    [403, 'moderation_proof_invalid', 'moderation.errors.reload'],
    [409, 'moderation_epoch_changed', 'moderation.errors.reload'],
    [403, 'not_moderator', 'moderation.errors.notModerator'],
    [404, 'case_not_found', 'moderation.errors.caseNotFound'],
    [404, 'moderation_target_unknown', 'moderation.errors.targetUnknown'],
    [409, 'moderation_measure_exists', 'moderation.errors.measureExists'],
    [409, 'case_closed', 'moderation.errors.caseClosed'],
    [400, 'invalid_request', 'moderation.errors.invalidRequest'],
    [500, undefined, 'moderation.errors.failed'],
  ])('a step refused with %i %s says so', async (status, code, key) => {
    vi.mocked(takeMeasure).mockRejectedValue(httpError(status, code))
    const w = await mountCase()
    await click(w, '.moderation-block-content')
    expect(w.find('.moderation-case-error').text()).toBe(t(key))
  })

  it.each([
    ['.moderation-dismiss', () => dismissCase, 409, 'case_not_open', 'moderation.errors.caseNotOpen'],
    ['.moderation-lift', () => liftMeasure, 404, 'measure_not_found', 'moderation.errors.measureNotFound'],
    ['.moderation-propose-offline', () => proposeStep, 409, 'moderation_proposal_exists', 'moderation.errors.proposalExists'],
    ['.moderation-bar', () => barReporter, 404, 'report_not_found', 'moderation.errors.reportNotFound'],
  ])('%s refused with %s %s says so', async (selector, fn, status, code, key) => {
    vi.mocked(fn() as ReturnType<typeof vi.fn>).mockRejectedValue(httpError(status, code))
    const w = await mountCase()
    await click(w, selector)
    expect(w.find('.moderation-case-error').text()).toBe(t(key))
  })

  it('a decided case is not dismissed; an upload link offers no content block', async () => {
    const w = await mountCase(caseOf({ state: 'DECIDED', itemKind: 'LINK' }))
    expect(w.find('.moderation-dismiss').exists()).toBe(false)
    expect(w.find('.moderation-block-content').exists()).toBe(false)
  })

  it('a closed case takes no new step, but a measure in force can still be lifted', async () => {
    const w = await mountCase(caseOf({ state: 'CLOSED', hold: null }))
    expect(w.find('.moderation-decide-closed').exists()).toBe(true)
    expect(w.find('.moderation-decide').exists()).toBe(false)
    expect(w.find('.moderation-extend').exists()).toBe(false)
    expect(w.find('.moderation-lift').exists()).toBe(true)
  })

  it('an open proposal is shown as such and not offered again', async () => {
    const w = await mountCase(
      caseOf({
        proposals: [
          { id: 'p1', step: 'TENANT_OFFLINE', targetId: TENANT, proposedBy: MODERATOR, proposedAt: new Date(), outcome: 'OPEN', settledAt: null },
        ],
      }),
    )
    expect(w.find('.moderation-proposal').text()).toContain(t('moderation.case.proposalOutcomes.OPEN'))
    expect(w.find('.moderation-propose-offline').exists()).toBe(false)
  })

  describe('review', () => {
    const assign = vi.fn()
    beforeEach(() => {
      vi.spyOn(window.location, 'assign').mockImplementation(assign)
    })
    afterEach(() => vi.restoreAllMocks())

    it('navigates the browser to the reported tenant’s host', async () => {
      vi.mocked(reviewUrl).mockReturnValue(`https://club.example.org/s/${CASE}#moderation`)
      const w = await mountCase()
      await click(w, '.moderation-case-review')
      expect(reviewUrl).toHaveBeenCalledWith(CASE, 'club.example.org')
      expect(assign).toHaveBeenCalledWith(`https://club.example.org/s/${CASE}#moderation`)
    })
  })
})
