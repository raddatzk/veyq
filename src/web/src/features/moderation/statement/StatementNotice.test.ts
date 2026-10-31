// @vitest-environment happy-dom
// uc.moderation.object-to-a-measure steps 5, 8, 10 (web) — the statement of reasons on a blocked item: shown only where a
// statement names the member, rendered from the one template in its form with the facts opened, and the objection: its
// form once, then its state and answer. A guest — no statement — sees nothing beyond the placeholder.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { httpError } from '../../../test/httpError'
import type { ModerationStatement } from '../../../api/generated/moderation'

vi.mock('./statements', () => ({
  loadStatements: vi.fn(),
  loadGuestStatements: vi.fn(),
  statementFor: vi.fn(),
  guestStatementFor: vi.fn(),
  guestStatements: vi.fn(),
  openFacts: vi.fn(),
  objectTo: vi.fn(),
  objectionFailure: vi.fn(),
}))

import StatementNotice from './StatementNotice.vue'
import { loadStatements, objectTo, objectionFailure, openFacts, statementFor } from './statements'
import { decodeHttpError } from '../../../shared/httpError'

const state = { list: [] as ModerationStatement[] }
import { realI18n, t } from '../../../test/i18n'
import { TEMPLATE } from './template'

const NODE = '4f2c9a1e-7b3d-4e5f-9a8b-1c2d3e4f5a6b'
const statement = (over: Partial<ModerationStatement> = {}): ModerationStatement => ({
  id: '5b6c7d8e-9f0a-4b1c-8d2e-3f4a5b6c7d8e',
  caseId: '1c2d3e4f-5a6b-4f2c-9a1e-7b3d4e5f9a8b',
  measureKind: 'CONTENT',
  targetId: NODE,
  shareId: undefined,
  scopeId: '7c3f0e2a-9b1d-4a6e-8b2c-1f2d3e4a5b6c',
  ground: 'TERMS_BREACH',
  provision: '§ 86a StGB',
  basis: 'REPORT',
  sentAt: new Date('2026-10-18T09:00:00Z'),
  roles: ['SCOPE_OWNER'],
  sealedFacts: 'c2VhbGVk',
  inForce: true,
  liftedAt: undefined,
  endedWithTarget: false,
  contactAddress: 'abuse@example.org',
  objection: undefined,
  canObject: true,
  ...over,
})

const stubs = {
  VeyqButton: { props: ['label'], template: '<button :class="$attrs.class" :type="$attrs.type ?? \'button\'">{{ label }}</button>' },
  VeyqMessage: { template: '<div class="msg"><slot /></div>' },
}

async function mountNotice(props: Record<string, string>): Promise<VueWrapper> {
  const w = mount(StatementNotice, { props, global: { plugins: [realI18n()], stubs } })
  await flushPromises()
  return w
}

describe('StatementNotice', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    state.list = [statement()]
    vi.mocked(loadStatements).mockResolvedValue()
    vi.mocked(statementFor).mockImplementation((t) =>
      state.list.find((s) => s.targetId === t.targetId || (t.shareId != null && s.shareId === t.shareId)),
    )
    vi.mocked(openFacts).mockReturnValue('the photo shows a threat')
    vi.mocked(objectionFailure).mockImplementation(async (e) => (await decodeHttpError(e)).code)
  })

  it('reads the statements once it mounts, and shows nothing where none names this item', async () => {
    const w = await mountNotice({ targetId: 'another-node' })
    expect(loadStatements).toHaveBeenCalled()
    expect(w.find('.statement-notice').exists()).toBe(false)
  })

  it('renders the one form from the template with the facts opened, and offers the objection', async () => {
    const w = await mountNotice({ targetId: NODE })
    const ids = w.findAll('.statement-passage').map((p) => p.attributes('data-passage'))
    expect(ids).toEqual(['measure', 'roles', 'facts', 'basis', 'automation', 'ground', 'provision', 'scope', 'objection', 'contact', 'courts'])
    expect(w.text()).toContain(TEMPLATE.measures.CONTENT.en.reach)
    expect(w.text()).toContain('It relies on § 86a StGB.')
    expect(w.find('.statement-facts').text()).toBe('the photo shows a threat')
    expect(w.text()).toContain('Questions go to abuse@example.org.')
    expect(w.find('.statement-objection').exists()).toBe(true)
  })

  it('one form on every instance: no contact set shows the gap where the contact goes', async () => {
    state.list = [statement({ contactAddress: undefined })]
    const w = await mountNotice({ targetId: NODE })
    expect(w.findAll('.statement-passage').map((p) => p.attributes('data-passage'))).toEqual(
      ['measure', 'roles', 'facts', 'basis', 'automation', 'ground', 'provision', 'scope', 'objection', 'contact', 'courts'],
    )
    expect(w.text()).toContain('Questions go to not stated by this operator.')
  })

  it('a blocked link finds its statement by the share id', async () => {
    state.list = [statement({ measureKind: 'SHARE', targetId: 'link', shareId: 'link', roles: ['SHARE_CREATOR'] })]
    const w = await mountNotice({ shareId: 'link' })
    expect(w.text()).toContain('took a measure: a link you created was blocked.')
  })

  it('a blocked link is worded by the reader’s own relation to it, as its statement lists it (inbox #821)', async () => {
    state.list = [statement({ measureKind: 'SHARE', targetId: 'link', shareId: 'link', roles: ['UPLOADER'] })]
    const uploader = await mountNotice({ shareId: 'link' })
    expect(uploader.text()).toContain('took a measure: a link to something you uploaded was blocked.')
    expect(uploader.text()).not.toContain('you created')
    expect(uploader.text()).toContain('You are told as the person who uploaded it.')
    // Control: the reader with no relation of its own reads the neutral words.
    state.list = [statement({ measureKind: 'SHARE', targetId: 'link', shareId: 'link', roles: ['SCOPE_OWNER'] })]
    const other = await mountNotice({ shareId: 'link' })
    expect(other.text()).toContain('took a measure: a link was blocked.')
    expect(other.text()).not.toContain('you created')
  })

  it('sends the objection once, and an empty one is not sent', async () => {
    vi.mocked(objectTo).mockImplementation(async (s) => {
      state.list = [{ ...s, canObject: false, objection: { receivedAt: new Date(), mine: true, outcome: undefined, ownReview: false, decidedAt: undefined } }]
      return state.list[0]
    })
    const w = await mountNotice({ targetId: NODE })
    await w.find('.statement-objection').trigger('submit')
    await flushPromises()
    expect(objectTo).not.toHaveBeenCalled()
    expect(w.text()).toContain(t('statement.objectEmpty'))
    await w.find('.statement-objection-text').setValue('it is my own address')
    await w.find('.statement-objection').trigger('submit')
    await flushPromises()
    expect(objectTo).toHaveBeenCalledWith(expect.objectContaining({ id: statement().id }), 'it is my own address')
    expect(w.text()).toContain(t('statement.objectSent'))
  })

  it.each([
    ['objection_exists', 'statement.errors.objectionExists'],
    ['measure_not_in_force', 'statement.errors.measureNotInForce'],
    [undefined, 'statement.errors.failed'],
  ])('a refused objection (%s) says why', async (code, key) => {
    vi.mocked(objectTo).mockRejectedValue(httpError(409, code))
    const w = await mountNotice({ targetId: NODE })
    await w.find('.statement-objection-text').setValue('no')
    await w.find('.statement-objection').trigger('submit')
    await flushPromises()
    expect(w.find('.msg').text()).toBe(t(key))
  })

  it('shows the objection waiting, then its answer and who reviewed it', async () => {
    const received = new Date('2026-10-18T10:00:00Z')
    state.list = [statement({ canObject: false, objection: { receivedAt: received, mine: true, outcome: undefined, ownReview: false, decidedAt: undefined } })]
    let w = await mountNotice({ targetId: NODE })
    expect(w.find('.statement-objection').exists()).toBe(false)
    expect(w.find('.statement-objection-state').text()).toContain(received.toLocaleDateString('en', { year: 'numeric', month: 'long', day: 'numeric' }))
    state.list = [statement({ canObject: false, objection: { receivedAt: received, mine: true, outcome: 'UPHELD', ownReview: true, decidedAt: received } })]
    w = await mountNotice({ targetId: NODE })
    expect(w.find('.statement-objection-review').text()).toBe(t('statement.ownReview'))
    state.list = [statement({ canObject: false, inForce: false, objection: { receivedAt: received, mine: false, outcome: 'LIFTED', ownReview: false, decidedAt: received } })]
    w = await mountNotice({ targetId: NODE })
    expect(w.find('.statement-objection-review').text()).toBe(t('statement.otherReview'))
  })

  it('a measure that ended with its erased target says so, and one a moderator lifted says lifted (ADR 0093 § 6)', async () => {
    const lifted = new Date('2026-10-18T10:00:00Z')
    const time = lifted.toLocaleDateString('en', { year: 'numeric', month: 'long', day: 'numeric' })
    state.list = [statement({ canObject: false, inForce: false, liftedAt: lifted, endedWithTarget: true })]
    let w = await mountNotice({ targetId: NODE })
    expect(w.find('.statement-lifted').text()).toBe(t('statement.endedWithTarget', { time }))
    state.list = [statement({ canObject: false, inForce: false, liftedAt: lifted, endedWithTarget: false })]
    w = await mountNotice({ targetId: NODE })
    expect(w.find('.statement-lifted').text()).toBe(t('statement.liftedAt', { time }))
  })
})
