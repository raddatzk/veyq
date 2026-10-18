// @vitest-environment happy-dom
// A guest uploader's notice of an authority's removal order (TCO Art. 11, ADR 0093 § 12; uc.moderation.authority-order
// step 5): asked only when this device holds the guest's session for the link — with that session's contributor token —
// and shown only when the server tells something; its ordinary unknown answer shows nothing.
import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('../../api/client', () => ({ publicSharesApi: vi.fn() }))

import { flushPromises, mount } from '@vue/test-utils'
import { publicSharesApi } from '../../api/client'
import { fakeApi, stubApi } from '../../test/apiMock'
import { httpError } from '../../test/httpError'
import { realI18n, t } from '../../test/i18n'
import { saveGuestSession } from './guestIdentity'
import { loadOrderNotice } from './orderNotice'
import OrderNotice from './OrderNotice.vue'

const SHARE_ID = 'Sh4reIdSh4reIdSh4reIdSh4reIdSh4reIdSh4re-_0'
const RECEIVED = new Date('2026-10-18T07:12:00Z')
const stubs = { Message: { template: '<div class="msg"><slot /></div>' } }

beforeEach(() => {
  localStorage.clear()
  vi.clearAllMocks()
})

describe('loadOrderNotice', () => {
  it('asks nothing when this device holds no session for the link', async () => {
    const { getShareOrderNotice } = stubApi(publicSharesApi, { getShareOrderNotice: { removals: [{ orderReceivedAt: RECEIVED }], contactAddress: null } })
    expect(await loadOrderNotice(SHARE_ID)).toBeNull()
    expect(getShareOrderNotice).not.toHaveBeenCalled()
  })

  it('asks with the session’s own contributor token, and reads the removals and the contact', async () => {
    saveGuestSession(SHARE_ID, { identityId: 'id-1', contributorToken: 'tok-1' })
    const { getShareOrderNotice } = stubApi(publicSharesApi, {
      getShareOrderNotice: { removals: [{ orderReceivedAt: RECEIVED }], contactAddress: 'abuse@example.org' },
    })
    expect(await loadOrderNotice(SHARE_ID)).toEqual({ receivedAt: [RECEIVED], contact: 'abuse@example.org' })
    expect(getShareOrderNotice).toHaveBeenCalledWith({ shareId: SHARE_ID, xContributorToken: 'tok-1' })
  })

  it('reads the link’s ordinary unknown answer as nothing to tell', async () => {
    saveGuestSession(SHARE_ID, { identityId: 'id-1', contributorToken: 'tok-1' })
    fakeApi(publicSharesApi, { getShareOrderNotice: () => Promise.reject(httpError(404, 'share_not_found')) })
    expect(await loadOrderNotice(SHARE_ID)).toBeNull()
  })
})

describe('OrderNotice', () => {
  it('says when the order was received and where to ask for the reasons or a copy', async () => {
    saveGuestSession(SHARE_ID, { identityId: 'id-1', contributorToken: 'tok-1' })
    stubApi(publicSharesApi, { getShareOrderNotice: { removals: [{ orderReceivedAt: RECEIVED }], contactAddress: 'abuse@example.org' } })
    const w = mount(OrderNotice, { props: { shareId: SHARE_ID }, global: { plugins: [realI18n()], stubs } })
    await flushPromises()
    expect(w.find('[data-testid="order-notice"]').exists()).toBe(true)
    expect(w.find('.order-notice-removal').text()).toContain('October 18, 2026')
    expect(w.find('.order-notice-ask').text()).toBe(t('share.orderNotice.ask', { contact: 'abuse@example.org' }))
  })

  it('without an instance contact sends the guest to the people who run the instance', async () => {
    saveGuestSession(SHARE_ID, { identityId: 'id-1', contributorToken: 'tok-1' })
    stubApi(publicSharesApi, { getShareOrderNotice: { removals: [{ orderReceivedAt: RECEIVED }], contactAddress: null } })
    const w = mount(OrderNotice, { props: { shareId: SHARE_ID }, global: { plugins: [realI18n()], stubs } })
    await flushPromises()
    expect(w.find('.order-notice-ask').text()).toBe(t('share.orderNotice.askOperator'))
  })

  it('shows nothing when the server has nothing to tell', async () => {
    saveGuestSession(SHARE_ID, { identityId: 'id-1', contributorToken: 'tok-1' })
    fakeApi(publicSharesApi, { getShareOrderNotice: () => Promise.reject(httpError(404, 'share_not_found')) })
    const w = mount(OrderNotice, { props: { shareId: SHARE_ID }, global: { plugins: [realI18n()], stubs } })
    await flushPromises()
    expect(w.find('[data-testid="order-notice"]').exists()).toBe(false)
  })
})
