// @vitest-environment happy-dom
// The share viewer's moderation mode (uc.moderation.review-a-case step 3; ADR 0099 § 6): `/s/<caseId>#moderation` renders
// the case's review behind the moderation gate, with the route's id as the CASE id — and none of the link shell's public
// reads runs (no resolve, no terms, no poll): a review is not a link's page.
import { describe, it, expect, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'

const CASE_ID = '7c000000-0000-4000-8000-000000000003'
const route = vi.hoisted(() => ({ hash: '#moderation' }))
vi.mock('vue-router', () => ({ useRoute: () => ({ params: { shareId: CASE_ID }, hash: route.hash }) }))
vi.mock('../api/client', () => ({ sharesApi: vi.fn(), publicSharesApi: vi.fn(), tokenApi: vi.fn() }))
vi.mock('../core/sharing/resolvePublicShare', async (orig) => ({
  ...(await orig<object>()),
  resolvePublicShare: vi.fn(async () => {
    throw new Error('not in this test')
  }),
  fetchShareTerms: vi.fn(async () => ({ tenantId: 't', termsRevision: 0, terms: [] })),
}))
vi.mock('../features/moderation/review/ShareReview.vue', async () => {
  const { defineComponent, h } = await import('vue')
  return { default: defineComponent({ props: { caseId: String }, setup: (p) => () => h('div', { class: 'stub-review', 'data-case': p.caseId }) }) }
})

import ShareView from './ShareView.vue'
import { fetchShareTerms, resolvePublicShare } from '../core/sharing/resolvePublicShare'
import { realI18n } from '../test/i18n'

const mountView = async () => {
  const w = mount(ShareView, { global: { plugins: [realI18n()], stubs: { OperatorIdentification: true } } })
  await flushPromises()
  return w
}

describe('ShareView: the moderation mode', () => {
  it('#moderation renders the review of the route id as a case, and resolves no link', async () => {
    route.hash = '#moderation'
    const w = await mountView()
    expect(w.find('.stub-review').attributes('data-case')).toBe(CASE_ID)
    expect(resolvePublicShare).not.toHaveBeenCalled()
    expect(fetchShareTerms).not.toHaveBeenCalled()
  })

  it("any other fragment is a link's page: it resolves, and renders no review", async () => {
    route.hash = '#k=AAAA'
    const w = await mountView()
    expect(w.find('.stub-review').exists()).toBe(false)
    expect(resolvePublicShare).toHaveBeenCalled()
  })
})
