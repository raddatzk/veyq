// @vitest-environment happy-dom
// A guest uploader's notice of an authority's removal order (TCO Art. 11, ADR 0093 § 12; uc.moderation.authority-order
// step 5): asked only when this device holds the guest's session for the link — with that session's contributor token —
// and shown only when the server tells a removal; its ordinary unknown answer, and an answer with only a moderator's
// statement (shown by `StatementNotice`, ADR 0093 § 7), show nothing here.
import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('../../api/client', () => ({ publicSharesApi: vi.fn() }))
// The real read: this file is about what the link tells the guest (src/test/setup.ts stands in for it elsewhere).
vi.unmock('./guestNoticeRead')

import { flushPromises, mount } from '@vue/test-utils'
import { publicSharesApi } from '../../api/client'
import { fakeApi, stubApi } from '../../test/apiMock'
import { httpError } from '../../test/httpError'
import { realI18n, t } from '../../test/i18n'
import { saveGuestSession } from './guestIdentity'
import { closeGuestPage, guestPage, loadGuestNotice, openGuestPage } from './guestNotice'
import OrderNotice from './OrderNotice.vue'

const SHARE_ID = 'Sh4reIdSh4reIdSh4reIdSh4reIdSh4reIdSh4re-_0'
const RECEIVED = new Date('2026-10-18T07:12:00Z')
const stubs = { Message: { template: '<div class="msg"><slot /></div>' } }
const STATEMENT = {
  id: '5b6c7d8e-9f0a-4b1c-8d2e-3f4a5b6c7d8e', measureKind: 'CONTENT' as const, targetId: 'node-1', covers: [],
  ground: 'TERMS_BREACH' as const, provision: 'Space terms, clause 4', basis: 'REPORT' as const, takenAt: new Date('2026-10-17T12:00:00Z'),
}

beforeEach(() => {
  localStorage.clear()
  vi.clearAllMocks()
  closeGuestPage()
})

describe('loadGuestNotice', () => {
  it('asks nothing when this device holds no session for the link', async () => {
    const { getShareGuestNotice } = stubApi(publicSharesApi, { getShareGuestNotice: { removals: [{ orderReceivedAt: RECEIVED }], statements: [], contactAddress: undefined } })
    expect(await loadGuestNotice(SHARE_ID)).toBeNull()
    expect(getShareGuestNotice).not.toHaveBeenCalled()
  })

  it('asks with the session’s own contributor token, and reads the removals and the contact', async () => {
    saveGuestSession(SHARE_ID, { identityId: 'id-1', contributorToken: 'tok-1' })
    const { getShareGuestNotice } = stubApi(publicSharesApi, {
      getShareGuestNotice: { removals: [{ orderReceivedAt: RECEIVED }], statements: [], contactAddress: 'abuse@example.org' },
    })
    expect(await loadGuestNotice(SHARE_ID)).toEqual({ receivedAt: [RECEIVED], statements: [], contact: 'abuse@example.org' })
    expect(getShareGuestNotice).toHaveBeenCalledWith({ shareId: SHARE_ID, xContributorToken: 'tok-1' })
  })

  it('reads once per page, holds the answer for the page’s placeholders, and drops it with the page', async () => {
    saveGuestSession(SHARE_ID, { identityId: 'id-1', contributorToken: 'tok-1' })
    const { getShareGuestNotice } = stubApi(publicSharesApi, {
      getShareGuestNotice: { removals: [], statements: [STATEMENT], contactAddress: 'abuse@example.org' },
    })
    openGuestPage(SHARE_ID)
    await loadGuestNotice(SHARE_ID)
    await loadGuestNotice(SHARE_ID)
    expect(getShareGuestNotice).toHaveBeenCalledTimes(1)
    expect(guestPage.notice?.statements).toEqual([STATEMENT])
    // A fresh read — the § 12 notice's, where it mounts — asks again.
    await loadGuestNotice(SHARE_ID, true)
    expect(getShareGuestNotice).toHaveBeenCalledTimes(2)
    closeGuestPage()
    expect(guestPage.notice).toBeNull()
  })

  it('reads afresh for the next identity on this device: one guest never reads another’s answer', async () => {
    saveGuestSession(SHARE_ID, { identityId: 'id-1', contributorToken: 'tok-1' })
    const { getShareGuestNotice } = stubApi(publicSharesApi, {
      getShareGuestNotice: { removals: [], statements: [STATEMENT], contactAddress: 'abuse@example.org' },
    })
    openGuestPage(SHARE_ID)
    await loadGuestNotice(SHARE_ID)
    expect(guestPage.notice?.statements).toEqual([STATEMENT])
    // "Not you?", and the next visitor names itself on the same device.
    saveGuestSession(SHARE_ID, { identityId: 'id-2', contributorToken: 'tok-2' })
    const second = loadGuestNotice(SHARE_ID)
    expect(guestPage.notice).toBeNull()
    await second
    expect(getShareGuestNotice).toHaveBeenLastCalledWith({ shareId: SHARE_ID, xContributorToken: 'tok-2' })
    expect(getShareGuestNotice).toHaveBeenCalledTimes(2)
  })

  it('reads the link’s ordinary unknown answer as nothing to tell', async () => {
    saveGuestSession(SHARE_ID, { identityId: 'id-1', contributorToken: 'tok-1' })
    fakeApi(publicSharesApi, { getShareGuestNotice: () => Promise.reject(httpError(404, 'share_not_found')) })
    expect(await loadGuestNotice(SHARE_ID)).toBeNull()
  })
})

describe('OrderNotice', () => {
  it('says when the order was received and where to ask for the reasons or a copy', async () => {
    saveGuestSession(SHARE_ID, { identityId: 'id-1', contributorToken: 'tok-1' })
    stubApi(publicSharesApi, { getShareGuestNotice: { removals: [{ orderReceivedAt: RECEIVED }], statements: [], contactAddress: 'abuse@example.org' } })
    const w = mount(OrderNotice, { props: { shareId: SHARE_ID }, global: { plugins: [realI18n()], stubs } })
    await flushPromises()
    expect(w.find('[data-testid="order-notice"]').exists()).toBe(true)
    expect(w.find('.order-notice-removal').text()).toContain('October 18, 2026')
    expect(w.find('.order-notice-ask').text()).toBe(t('share.orderNotice.ask', { contact: 'abuse@example.org' }))
  })

  it('without an instance contact sends the guest to the people who run the instance', async () => {
    saveGuestSession(SHARE_ID, { identityId: 'id-1', contributorToken: 'tok-1' })
    stubApi(publicSharesApi, { getShareGuestNotice: { removals: [{ orderReceivedAt: RECEIVED }], statements: [], contactAddress: undefined } })
    const w = mount(OrderNotice, { props: { shareId: SHARE_ID }, global: { plugins: [realI18n()], stubs } })
    await flushPromises()
    expect(w.find('.order-notice-ask').text()).toBe(t('share.orderNotice.askOperator'))
  })

  it('shows nothing when the answer holds only a moderator’s statement', async () => {
    saveGuestSession(SHARE_ID, { identityId: 'id-1', contributorToken: 'tok-1' })
    stubApi(publicSharesApi, { getShareGuestNotice: { removals: [], statements: [STATEMENT], contactAddress: 'abuse@example.org' } })
    const w = mount(OrderNotice, { props: { shareId: SHARE_ID }, global: { plugins: [realI18n()], stubs } })
    await flushPromises()
    expect(w.find('[data-testid="order-notice"]').exists()).toBe(false)
  })

  it('shows nothing when the server has nothing to tell', async () => {
    saveGuestSession(SHARE_ID, { identityId: 'id-1', contributorToken: 'tok-1' })
    fakeApi(publicSharesApi, { getShareGuestNotice: () => Promise.reject(httpError(404, 'share_not_found')) })
    const w = mount(OrderNotice, { props: { shareId: SHARE_ID }, global: { plugins: [realI18n()], stubs } })
    await flushPromises()
    expect(w.find('[data-testid="order-notice"]').exists()).toBe(false)
  })
})
