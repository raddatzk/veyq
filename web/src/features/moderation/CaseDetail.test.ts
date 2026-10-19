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
  decideObjection: vi.fn(),
  openObjectionOf: vi.fn(),
  reporterNoticesOf: vi.fn(),
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
  renewDeferral: vi.fn(),
}))

import CaseDetail from './CaseDetail.vue'
import {
  barReporter,
  decideObjection,
  dismissCase,
  extendHold,
  getCase,
  liftMeasure,
  openNote,
  openObjectionOf,
  openReportOf,
  proposeStep,
  renewDeferral,
  reporterNoticesOf,
  reviewUrl,
  takeMeasure,
} from './cases'
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
const STATEMENT = '5b6c7d8e-9f0a-4b1c-8d2e-3f4a5b6c7d8e'
const OBJECTION = '6c7d8e9f-0a1b-4c2d-8e3f-4a5b6c7d8e9f'
const FACTS = { ground: 'TERMS_BREACH', provision: 'Space terms, clause 4', facts: 'the facts', deferral: null }

const caseOf = (over: Partial<ModerationCase> = {}): ModerationCase => ({
  id: CASE,
  itemKind: 'ITEM',
  itemId: ITEM,
  scopeId: '7c3f0e2a-9b1d-4a6e-8b2c-1f2d3e4a5b6c',
  tenantId: TENANT,
  tenantDomain: 'club.example.org',
  category: 'TERMS_BREACH',
  state: 'OPEN',
  decidedAt: undefined,
  closedAt: undefined,
  hold: { endsAt: new Date('2027-01-15T09:00:00Z'), trigger: 'CASE_OPEN', floorUntil: undefined },
  reports: [
    { id: REPORT, epoch: 2, receivedAt: new Date('2026-10-17T09:00:00Z'), category: 'TERMS_BREACH', sealedReport: 'AQ==', reporter: 'LINK', barred: false },
    { id: REPORT_2, epoch: 1, receivedAt: new Date('2026-10-17T10:00:00Z'), category: 'ILLEGAL_CONTENT', sealedReport: 'Ag==', reporter: 'ACCOUNT', barred: true },
  ],
  measures: [
    {
      id: MEASURE,
      kind: 'SHARE',
      targetId: BLOCKED_LINK,
      takenBy: MODERATOR,
      takenAt: new Date('2026-10-17T12:00:00Z'),
      liftedAt: undefined,
      liftedBy: undefined,
      statement: {
        id: STATEMENT,
        ground: 'TERMS_BREACH',
        basis: 'REPORT',
        sentAt: new Date('2026-10-17T12:00:00Z'),
        epoch: 2,
        sealedFacts: 'ZmFjdHM=',
        recipients: 2,
        guests: 0,
        deferredUntil: undefined,
        renewed: false,
      },
    },
  ],
  statementForm: 'FULL',
  origin: 'REPORT',
  orders: [],
  objections: [],
  proposals: [],
  targets: {
    links: [
      { shareId: BLOCKED_LINK, role: 'READ', relation: 'REPORTED_THROUGH', blocked: true },
      { shareId: OPEN_LINK, role: 'READ_WRITE', relation: 'REACHES', blocked: false },
    ],
    uploaders: [
      { kind: 'GUEST_IDENTITY', id: GUEST, shareId: OPEN_LINK, blocked: false },
      { kind: 'ACCOUNT', id: MEMBER, shareId: undefined, blocked: false },
    ],
  },
  events: [
    { event: 'RECEIVED', at: new Date('2026-10-17T09:00:00Z'), moderatorId: undefined, detail: null, epoch: null, sealedText: undefined, until: undefined },
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
  AuthoritySection: { props: ['current', 'busy'], template: '<div class="authority-section-stub" />' },
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
    vi.mocked(reporterNoticesOf).mockReturnValue([])
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
    ['.moderation-dismiss', () => dismissCase, [CASE, 'a sealed note', []]],
    ['.moderation-block-link', () => takeMeasure, [CASE, 'SHARE', { shareId: OPEN_LINK }, 'a sealed note', FACTS, []]],
    ['.moderation-block-content', () => takeMeasure, [CASE, 'CONTENT', { targetId: ITEM }, 'a sealed note', FACTS, []]],
    ['.moderation-block-guest', () => takeMeasure, [CASE, 'GUEST_IDENTITY', { targetId: GUEST }, 'a sealed note', FACTS, []]],
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
    await w.find('.moderation-statement-facts').setValue('the facts')
    await w.find('.moderation-statement-provision').setValue('Space terms, clause 4')
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
    vi.mocked(extendHold).mockResolvedValue(caseOf({ hold: { endsAt: until, trigger: 'EXTENDED', floorUntil: undefined } }))
    const w = await mountCase()
    w.findComponent({ name: 'DatePicker' }).vm.$emit('update:modelValue', until)
    await w.find('.moderation-extend-reason').setValue('an authority asked')
    await w.find('.moderation-extend').trigger('submit')
    await flushPromises()
    expect(extendHold).toHaveBeenCalledWith(CASE, until, 'an authority asked', null)
    expect(w.text()).toContain(t('moderation.case.holdTriggers.EXTENDED'))
  })

  it('on a child-sexual-abuse case the extension names an authority or an Art. 18 report, and nothing else is offered', async () => {
    const until = new Date('2027-03-01T00:00:00Z')
    vi.mocked(extendHold).mockResolvedValue(caseOf({ category: 'CHILD_SEXUAL_ABUSE', hold: { endsAt: until, trigger: 'EXTENDED', floorUntil: undefined } }))
    const w = await mountCase(caseOf({ category: 'CHILD_SEXUAL_ABUSE' }))
    const offered = w.findAll('.moderation-extend-type option').map((o) => o.attributes('value')).filter((v) => v)
    expect(offered).toEqual(['AUTHORITY_REFERENCE', 'ART18_REPORT_IN_PROGRESS'])
    w.findComponent({ name: 'DatePicker' }).vm.$emit('update:modelValue', until)
    await w.find('.moderation-extend-reason').setValue('BKA asked to keep it')
    // Without the kind it is not sent, and says why.
    await w.find('.moderation-extend').trigger('submit')
    await flushPromises()
    expect(extendHold).not.toHaveBeenCalled()
    expect(w.find('.moderation-case-error').text()).toBe(t('moderation.errors.extendTypeRequired'))
    await w.find('.moderation-extend-type').setValue('AUTHORITY_REFERENCE')
    await w.find('.moderation-extend').trigger('submit')
    await flushPromises()
    expect(extendHold).toHaveBeenCalledWith(CASE, until, 'BKA asked to keep it', 'AUTHORITY_REFERENCE')
  })

  it('on another case every kind of reason is offered, and the kind is optional', async () => {
    const w = await mountCase()
    const offered = w.findAll('.moderation-extend-type option').map((o) => o.attributes('value')).filter((v) => v)
    expect(offered).toEqual(['AUTHORITY_REFERENCE', 'ART18_REPORT_IN_PROGRESS', 'OBJECTION', 'LAWYERS_LETTER', 'LAWSUIT'])
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
    [409, 'extension_reason_not_allowed', 'moderation.errors.extendTypeNotAllowed'],
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
    [400, 'statement_facts_required', 'moderation.errors.factsRequired'],
    [400, 'statement_provision_required', 'moderation.errors.provisionRequired'],
    [409, 'statement_recipients_changed', 'moderation.errors.recipientsChanged'],
    [422, 'statement_recipients_too_many', 'moderation.errors.recipientsTooMany'],
    [500, undefined, 'moderation.errors.failed'],
  ])('a step refused with %i %s says so', async (status, code, key) => {
    vi.mocked(takeMeasure).mockRejectedValue(httpError(status, code))
    const w = await mountCase()
    await w.find('.moderation-statement-facts').setValue('the facts')
    await w.find('.moderation-statement-provision').setValue('Space terms, clause 4')
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

  describe('the statement of reasons', () => {
    it('in the full form a block without facts is not sent, and says why', async () => {
      const w = await mountCase()
      expect(w.find('label[for="moderation-statement-facts"]').text()).toBe(t('moderation.decide.facts'))
      await click(w, '.moderation-block-content')
      expect(ask).not.toHaveBeenCalled()
      expect(takeMeasure).not.toHaveBeenCalled()
      expect(w.find('.moderation-case-error').text()).toBe(t('moderation.errors.factsRequired'))
    })

    it('in the full form a block without the provision it relies on is not sent, and says why', async () => {
      const w = await mountCase()
      expect(w.find('label[for="moderation-statement-provision"]').text()).toBe(t('moderation.decide.provision'))
      await w.find('.moderation-statement-facts').setValue('the facts')
      await click(w, '.moderation-block-content')
      expect(ask).not.toHaveBeenCalled()
      expect(takeMeasure).not.toHaveBeenCalled()
      expect(w.find('.moderation-case-error').text()).toBe(t('moderation.errors.provisionRequired'))
    })

    it('in the reduced form a block goes without facts, on the ground the moderator chose, telling the reporters', async () => {
      vi.mocked(takeMeasure).mockResolvedValue(caseOf({ state: 'DECIDED', statementForm: 'REDUCED' }))
      vi.mocked(reporterNoticesOf).mockReturnValue([{ reportId: REPORT, address: 'r@example.org' }])
      const w = await mountCase(caseOf({ statementForm: 'REDUCED' }))
      expect(w.find('label[for="moderation-statement-facts"]').text()).toBe(t('moderation.decide.factsOptional'))
      expect((w.find('.moderation-statement-ground').element as HTMLSelectElement).value).toBe('TERMS_BREACH')
      await w.find('.moderation-statement-ground').setValue('ILLEGAL_CONTENT')
      await click(w, '.moderation-block-content')
      expect(takeMeasure).toHaveBeenCalledWith(CASE, 'CONTENT', { targetId: ITEM }, '', { ground: 'ILLEGAL_CONTENT', provision: '', facts: '', deferral: null }, [
        { reportId: REPORT, address: 'r@example.org' },
      ])
    })

    it('a measure shows its statement: the ground, whom it reached, and the facts opened', async () => {
      vi.mocked(openNote).mockImplementation((_e, _c, sealed) => (sealed === 'ZmFjdHM=' ? 'what was found' : 'an authority asked'))
      const w = await mountCase()
      const statement = w.find('.moderation-measure-statement').text()
      expect(statement).toContain(t('moderation.case.statementGround', { ground: t('report.categories.TERMS_BREACH') }))
      expect(w.find('.moderation-measure-facts').text()).toContain('what was found')
      const nobody = caseOf()
      nobody.measures[0].statement = { ...nobody.measures[0].statement!, sentAt: undefined, recipients: 0, sealedFacts: undefined, epoch: undefined }
      const w2 = await mountCase(nobody)
      expect(w2.find('.moderation-measure-statement').text()).toContain(t('moderation.case.statementNobody'))
      expect(w2.find('.moderation-measure-facts').exists()).toBe(false)
    })
  })

  describe('an objection', () => {
    const objection = (over: Partial<ModerationCase['objections'][number]> = {}) => ({
      id: OBJECTION,
      statementId: STATEMENT,
      measureId: MEASURE,
      receivedAt: new Date('2026-10-18T09:00:00Z'),
      epoch: 2,
      sealedObjection: 'b2JqZWN0aW9u',
      decidedAt: undefined,
      decidedBy: undefined,
      outcome: undefined,
      ownReview: false,
      mayDecide: true,
      ...over,
    })

    it('is shown opened, and the moderator who may decide upholds it or lifts the measure', async () => {
      vi.mocked(openObjectionOf).mockReturnValue('it is my own address')
      vi.mocked(decideObjection).mockResolvedValue(caseOf({ objections: [objection({ outcome: 'UPHELD', decidedAt: new Date() })] }))
      const w = await mountCase(caseOf({ state: 'DECIDED', objections: [objection()] }))
      expect(openObjectionOf).toHaveBeenCalledWith(2, STATEMENT, 'b2JqZWN0aW9u')
      expect(w.find('.moderation-objection-text').text()).toBe('it is my own address')
      await w.find('.moderation-decision-note').setValue('a sealed note')
      await click(w, '.moderation-objection-uphold')
      expect(decideObjection).toHaveBeenCalledWith(CASE, OBJECTION, 'UPHOLD', 'a sealed note')
      expect(w.find('.moderation-objection').text()).toContain(t('moderation.case.objectionOutcomes.UPHELD'))
      expect(w.find('.moderation-objection-uphold').exists()).toBe(false)
    })

    it('lifts the measure on request', async () => {
      vi.mocked(decideObjection).mockResolvedValue(caseOf())
      const w = await mountCase(caseOf({ state: 'DECIDED', objections: [objection()] }))
      await click(w, '.moderation-objection-lift')
      expect(decideObjection).toHaveBeenCalledWith(CASE, OBJECTION, 'LIFT', '')
    })

    it('is not offered to the moderator who took the measure while another one may decide it', async () => {
      vi.mocked(openObjectionOf).mockReturnValue(null)
      const w = await mountCase(caseOf({ state: 'DECIDED', objections: [objection({ mayDecide: false })] }))
      expect(w.find('.moderation-objection-text').text()).toBe(t('moderation.case.objectionSealed'))
      expect(w.find('.moderation-objection-another').text()).toBe(t('moderation.case.anotherDecides'))
      expect(w.find('.moderation-objection-uphold').exists()).toBe(false)
      expect(w.find('.moderation-objection-lift').exists()).toBe(false)
    })

    it('a decision by the only moderator says so', async () => {
      const w = await mountCase(caseOf({ objections: [objection({ outcome: 'LIFTED', ownReview: true, decidedAt: new Date(), mayDecide: false })] }))
      expect(w.find('.moderation-objection').text()).toContain(t('moderation.case.ownReview'))
      expect(w.find('.moderation-objection-another').exists()).toBe(false)
    })

    it.each([
      [409, 'objection_needs_another_moderator', 'moderation.errors.needsAnotherModerator'],
      [409, 'objection_decided', 'moderation.errors.objectionDecided'],
      [404, 'objection_not_found', 'moderation.errors.objectionNotFound'],
    ])('refused with %i %s says so', async (status, code, key) => {
      vi.mocked(decideObjection).mockRejectedValue(httpError(status, code))
      const w = await mountCase(caseOf({ state: 'DECIDED', objections: [objection()] }))
      await click(w, '.moderation-objection-uphold')
      expect(w.find('.moderation-case-error').text()).toBe(t(key))
    })
  })

  it('a decided case is not dismissed; an upload link offers no content block', async () => {
    const w = await mountCase(caseOf({ state: 'DECIDED', itemKind: 'LINK' }))
    expect(w.find('.moderation-dismiss').exists()).toBe(false)
    expect(w.find('.moderation-block-content').exists()).toBe(false)
  })

  it('a closed case takes no new step, but a measure in force can still be lifted', async () => {
    const w = await mountCase(caseOf({ state: 'CLOSED', hold: undefined }))
    expect(w.find('.moderation-decide-closed').exists()).toBe(true)
    expect(w.find('.moderation-decide').exists()).toBe(false)
    expect(w.find('.moderation-extend').exists()).toBe(false)
    expect(w.find('.moderation-lift').exists()).toBe(true)
  })

  it('a measure that ended with its erased target says so, where one a moderator lifted says lifted (ADR 0093 § 6)', async () => {
    const at = new Date('2026-10-18T08:00:00Z')
    const measure = caseOf().measures[0]
    const liftEvent = (moderatorId: string | undefined) =>
      ({ event: 'MEASURE_LIFTED' as const, at, moderatorId, detail: `SHARE ${BLOCKED_LINK}`, epoch: null, sealedText: undefined, until: undefined })
    // Lifted by no one: the eraser ended it (`MeasureTargets.erased`).
    const ended = await mountCase(caseOf({ measures: [{ ...measure, liftedAt: at, liftedBy: undefined }], events: [liftEvent(undefined)] }))
    expect(ended.find('.moderation-measure-ended').exists()).toBe(true)
    expect(ended.find('.moderation-measure').text()).not.toContain(t('moderation.case.liftedAt', { time: '' }).trim())
    expect(ended.find('.moderation-event .veyq-row-title').text()).toBe(t('moderation.case.measureEnded'))
    expect(ended.find('.moderation-lift').exists()).toBe(false)
    // Control: a moderator lifted it.
    const lifted = await mountCase(caseOf({ measures: [{ ...measure, liftedAt: at, liftedBy: MODERATOR }], events: [liftEvent(MODERATOR)] }))
    expect(lifted.find('.moderation-measure-ended').exists()).toBe(false)
    expect(lifted.find('.moderation-measure').text()).toContain(t('moderation.case.liftedAt', { time: '' }).trim())
    expect(lifted.find('.moderation-event .veyq-row-title').text()).toBe(t('moderation.case.events.MEASURE_LIFTED'))
  })

  it('an open proposal is shown as such and not offered again', async () => {
    const w = await mountCase(
      caseOf({
        proposals: [
          { id: 'p1', step: 'TENANT_OFFLINE', targetId: TENANT, proposedBy: MODERATOR, proposedAt: new Date(), outcome: 'OPEN', settledAt: undefined },
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

describe('CaseDetail — an authority (uc.moderation.authority-order, uc.moderation.report-to-an-authority)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    ask.mockResolvedValue(true)
    vi.mocked(openReportOf).mockReturnValue(null)
    vi.mocked(openNote).mockReturnValue(null)
    vi.mocked(reporterNoticesOf).mockReturnValue([])
  })

  const picker = (w: VueWrapper, id: string) => w.findAllComponents({ name: 'DatePicker' }).find((c) => c.attributes('input-id') === id)!

  it('a block carries the authority’s deferral only when asked for, with its end and its request', async () => {
    vi.mocked(takeMeasure).mockResolvedValue(caseOf({ state: 'DECIDED' }))
    const until = new Date('2026-11-20T00:00:00Z')
    const w = await mountCase()
    await w.find('.moderation-statement-facts').setValue('the facts')
    await w.find('.moderation-statement-provision').setValue('Space terms, clause 4')
    await w.find('.moderation-defer-toggle').setValue(true)
    // Asked for but incomplete: refused here, nothing sent.
    await click(w, '.moderation-block-content')
    expect(w.find('.moderation-case-error').text()).toBe(t('moderation.errors.reasonRequired'))
    expect(takeMeasure).not.toHaveBeenCalled()
    picker(w, 'moderation-defer-until').vm.$emit('update:modelValue', until)
    await w.find('.moderation-defer-reason').setValue('BKA: not before 20 November')
    await click(w, '.moderation-block-content')
    expect(takeMeasure).toHaveBeenCalledWith(CASE, 'CONTENT', { targetId: ITEM }, '', {
      ground: 'TERMS_BREACH', provision: 'Space terms, clause 4', facts: 'the facts', deferral: { until, reason: 'BKA: not before 20 November' },
    }, [])
  })

  it('shows a held statement’s end and renews it once with the authority’s renewed request', async () => {
    const held = new Date('2026-11-20T00:00:00Z')
    const c = caseOf()
    const measure = c.measures[0]
    const deferred = caseOf({ measures: [{ ...measure, statement: { ...measure.statement!, sentAt: undefined, deferredUntil: held, renewed: false } }] })
    vi.mocked(renewDeferral).mockResolvedValue(
      caseOf({ measures: [{ ...measure, statement: { ...measure.statement!, sentAt: undefined, deferredUntil: new Date('2026-12-20T00:00:00Z'), renewed: true } }] }),
    )
    const w = await mountCase(deferred)
    expect(w.find('.moderation-measure-statement').text()).toContain(t('moderation.deferral.deferredUntil', { time: held.toLocaleString('en') }))
    const until = new Date('2026-12-20T00:00:00Z')
    picker(w, `moderation-renew-until-${STATEMENT}`).vm.$emit('update:modelValue', until)
    await w.find('.moderation-renew-reason').setValue('BKA renewed')
    await w.find('.moderation-renew').trigger('submit')
    await flushPromises()
    expect(renewDeferral).toHaveBeenCalledWith(CASE, STATEMENT, until, 'BKA renewed')
    // Renewed once: no second renewal is offered.
    expect(w.find('.moderation-renew').exists()).toBe(false)
    expect(w.find('.moderation-measure-statement').text()).toContain(t('moderation.deferral.renewed'))
  })

  it('on child sexual abuse material, "Block the content" leads the decision panel as the proposed first step', async () => {
    const w = await mountCase(caseOf({ category: 'CHILD_SEXUAL_ABUSE' }))
    const first = w.find('.moderation-decide .veyq-list .veyq-list-row')
    expect(first.classes()).toContain('moderation-proposed-first')
    expect(first.text()).toContain(t('moderation.csam.proposed'))
    expect(w.findAll('.moderation-block-content')).toHaveLength(1)
    // Control: another category keeps the panel's order, with nothing proposed.
    const other = await mountCase(caseOf())
    expect(other.find('.moderation-proposed-first').exists()).toBe(false)
    expect(other.findAll('.moderation-block-content')).toHaveLength(1)
  })

  it('an order’s case has no review to open, and every case offers the report to an authority', async () => {
    const order = await mountCase(caseOf({ origin: 'ORDER', reports: [] }))
    expect(order.find('.moderation-case-review').exists()).toBe(false)
    expect(order.find('.moderation-case-order-badge').exists()).toBe(true)
    expect(order.find('.authority-section-stub').exists()).toBe(true)
    const reported = await mountCase(caseOf())
    expect(reported.find('.moderation-case-review').exists()).toBe(true)
    expect(reported.find('.authority-section-stub').exists()).toBe(true)
  })

  it('a case a restore brought back as a stub says so and has no review to open (ADR 0056 decision 8)', async () => {
    const stub = await mountCase(caseOf({ origin: 'RESTORED', reports: [] }))
    expect(stub.find('.moderation-case-restored-badge').text()).toBe(t('moderation.caseRow.restored'))
    expect(stub.find('.moderation-case-review').exists()).toBe(false)
    expect(stub.find('.moderation-case-order-badge').exists()).toBe(false)
    const reported = await mountCase(caseOf())
    expect(reported.find('.moderation-case-restored-badge').exists()).toBe(false)
  })
})
