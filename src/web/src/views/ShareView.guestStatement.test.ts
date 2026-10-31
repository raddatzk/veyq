// @vitest-environment happy-dom
// A guest uploader told of a moderator's measure on the link it came through (ADR 0093 § 7, inbox #813;
// uc.moderation.object-to-a-measure step 5): on its blocked link — the page answers everyone else as unavailable — the
// identity whose session this device holds reads the statement of reasons in the template's guest words, without the
// facts (where to ask for them instead) and with the objection through the instance contact, no form. Its control on the
// same page: a device holding no session for the link asks nothing and shows nothing. Guest mode: no authenticated
// client is called.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory, type Router } from 'vue-router'

vi.mock('leaflet', () => ({ default: {} }))
vi.mock('../api/client', async (orig) => (await import('../test/guestMode')).guardedClients(await orig<object>()))
vi.mock('../features/memories/api', async (orig) => (await import('../test/guestMode')).guardedClients(await orig<object>()))
vi.mock('../features/depot/api', async (orig) => (await import('../test/guestMode')).guardedClients(await orig<object>()))
vi.mock('../core/sharing/resolvePublicShare', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../core/sharing/resolvePublicShare')>()
  return { ...actual, resolvePublicShare: vi.fn(), fetchShareTerms: vi.fn() }
})
// The real read: this test is about what the link tells the guest (src/test/setup.ts stands in for it elsewhere).
vi.unmock('../core/sharing/guestNoticeRead')

import ShareView from './ShareView.vue'
import { publicSharesApi } from '../api/client'
import { resolvePublicShare } from '../core/sharing/resolvePublicShare'
import { saveGuestSession } from '../core/sharing/guestIdentity'
import type { ShareGuestNotice } from '../api/generated/sharing'
import { stubApi } from '../test/apiMock'
import { httpError } from '../test/httpError'
import { realI18n } from '../test/i18n'
import { authenticatedCalls } from '../test/guestMode'
import { TEMPLATE } from '../features/moderation/statement/template'

const SHARE_ID = 'Sh4reIdSh4reIdSh4reIdSh4reIdSh4reIdSh4re-_g'
const IDENTITY = '6f7a8b9c-0d1e-2f3a-4b5c-6d7e8f9a0b1c'

const told: ShareGuestNotice = {
  removals: [],
  statements: [
    {
      id: '5b6c7d8e-9f0a-4b1c-8d2e-3f4a5b6c7d8e',
      measureKind: 'GUEST_IDENTITY',
      targetId: IDENTITY,
      shareId: SHARE_ID,
      covers: [],
      ground: 'TERMS_BREACH',
      provision: 'Space terms, clause 4',
      takenAt: new Date('2026-10-18T09:00:00Z'),
    },
  ],
  contactAddress: 'abuse@example.org',
}

let router: Router
async function mountPage() {
  vi.mocked(resolvePublicShare).mockRejectedValue(httpError(404, 'share_not_found'))
  const w = mount(ShareView, { global: { plugins: [router, realI18n()] } })
  await flushPromises()
  await vi.waitFor(() => expect(w.find('.share-view-unavailable').exists()).toBe(true))
  await flushPromises()
  return w
}

beforeEach(async () => {
  localStorage.clear()
  authenticatedCalls.length = 0
  router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/s/:shareId', component: ShareView }] })
  await router.push(`/s/${SHARE_ID}#k=a2V5`)
  await router.isReady()
})

describe('a guest uploader’s statement of reasons on its blocked link (ADR 0093 § 7)', () => {
  it('shows the statement in the guest’s words, with no facts and no objection form, asked with the session’s own token', async () => {
    saveGuestSession(SHARE_ID, { identityId: IDENTITY, contributorToken: 'tok-1' })
    const { getShareGuestNotice } = stubApi(publicSharesApi, { getShareGuestNotice: told })
    const w = await mountPage()

    expect(getShareGuestNotice).toHaveBeenCalledWith({ shareId: SHARE_ID, xContributorToken: 'tok-1' })
    const notice = w.find('[data-testid="guest-statement"]')
    expect(notice.exists()).toBe(true)
    const passage = (id: string) => notice.find(`[data-passage="${id}"]`)
    // The guest's own words for the measure — never the creator's "a guest who uploaded through your link".
    expect(passage('measure').text()).toContain(TEMPLATE.guest.measures.GUEST_IDENTITY.en.measure)
    expect(passage('measure').text()).not.toContain(TEMPLATE.measures.GUEST_IDENTITY.en.measure)
    expect(passage('roles').text()).toContain(TEMPLATE.roles.UPLOADER.en)
    expect(passage('provision').text()).toBe('It relies on Space terms, clause 4.')
    // The facts open only with a member's identity key: where to ask for them stands in their place.
    expect(passage('facts').text()).toBe(TEMPLATE.guest.facts.en.replace('{contact}', 'abuse@example.org'))
    expect(notice.find('.statement-facts').exists()).toBe(false)
    // The objection goes to the instance contact; no form in veyq.
    expect(passage('objectionByContact').exists()).toBe(true)
    expect(passage('objection').exists()).toBe(false)
    expect(notice.find('form').exists()).toBe(false)
    // No removal order in the answer: the § 12 notice says nothing.
    expect(w.find('[data-testid="order-notice"]').exists()).toBe(false)
    expect(authenticatedCalls).toEqual([])
  })

  it('asks nothing and shows nothing on a device that holds no session for the link', async () => {
    const { getShareGuestNotice } = stubApi(publicSharesApi, { getShareGuestNotice: told })
    const w = await mountPage()
    expect(getShareGuestNotice).not.toHaveBeenCalled()
    expect(w.find('[data-testid="guest-statement"]').exists()).toBe(false)
    expect(authenticatedCalls).toEqual([])
  })
})
