// @vitest-environment happy-dom
// The public page of the instance privacy notice (ADR 0035 decision 8, GDPR Art. 13; uc.admin.privacy-notice): read
// through the PUBLIC client — a guest-mode mount, so any call to an authenticated client is red (inbox #401) — each
// passage at its anchor, each right to object apart (Art. 21(4)); the passage a link names is
// scrolled into view once rendered; a failed read says so and names the instance contact where it loads.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

vi.mock('../api/client', async (orig) => (await import('../test/guestMode')).guardedClients(await orig<object>()))
const route = { hash: '' }
vi.mock('vue-router', () => ({ useRoute: () => route }))

import PrivacyNoticeView from './PrivacyNoticeView.vue'
import { publicOperatorApi } from '../api/client'
import { authenticatedCalls } from '../test/guestMode'
import { fakeApi, stubApi } from '../test/apiMock'
import { realI18n, t } from '../test/i18n'
import { PRIVACY_ANCHOR, privacyLink } from '../core/privacy/privacyNotice'

const stubs = {
  VeyqCard: { template: '<div><slot name="title" /><slot /><slot name="footer" /></div>' },
  VeyqMessage: { props: ['tone'], template: '<div class="msg"><slot /></div>' },
}

const NOTICE = {
  revision: 1,
  passages: [
    { id: 'account', separate: false, text: { en: '## Your account\nThe server keeps your email address.', de: '## Dein Konto\nDer Server speichert deine E-Mail-Adresse.' } },
    { id: 'guest-write-record', separate: false, text: { en: '## Your IP address when you add something as a guest\nKept 7 days.', de: '## Deine IP-Adresse' } },
    { id: 'guest-write-record-objection', separate: true, text: { en: '### Your right to object to this record\nGive your guest id.', de: '### Dein Widerspruchsrecht' } },
  ],
}

async function mountIt(locale: 'en' | 'de' = 'en') {
  const i18n = realI18n()
  i18n.global.locale.value = locale
  const w = mount(PrivacyNoticeView, { global: { plugins: [i18n], stubs }, attachTo: document.body })
  await flushPromises()
  return w
}

describe('PrivacyNoticeView — the instance privacy notice, public', () => {
  beforeEach(() => {
    authenticatedCalls.length = 0
    route.hash = ''
  })

  it('shows every passage at its anchor without an account, in order, and the right to object apart', async () => {
    stubApi(publicOperatorApi, { getPrivacyNotice: NOTICE, getOperatorDetails: { contactLanguages: [] } })
    const w = await mountIt()
    const notice = w.find('[data-testid="privacy-notice"]')
    expect(notice.text().startsWith('Your account')).toBe(true)
    expect(w.find('div#account').text()).toContain('The server keeps your email address.')
    expect(w.find('div#guest-write-record').text()).toContain('Kept 7 days.')
    expect(w.find('aside#guest-write-record-objection').text()).toContain('Give your guest id.')
    expect(w.find('div#guest-write-record-objection').exists()).toBe(false) // set apart, never inline
    expect(authenticatedCalls).toEqual([])
    w.unmount()
  })

  it('speaks the page’s language', async () => {
    stubApi(publicOperatorApi, { getPrivacyNotice: NOTICE, getOperatorDetails: { contactLanguages: [] } })
    const w = await mountIt('de')
    expect(w.text()).toContain('Datenschutzerklärung')
    expect(w.find('div#account').text()).toContain('Der Server speichert deine E-Mail-Adresse.')
    w.unmount()
  })

  it('scrolls to the passage the link named once it is rendered', async () => {
    route.hash = `#${PRIVACY_ANCHOR.guestWriteRecord}`
    const scrolled = vi.fn()
    const original = Element.prototype.scrollIntoView
    Element.prototype.scrollIntoView = scrolled
    stubApi(publicOperatorApi, { getPrivacyNotice: NOTICE, getOperatorDetails: { contactLanguages: [] } })
    try {
      const w = await mountIt()
      expect(scrolled).toHaveBeenCalledOnce()
      expect(scrolled.mock.contexts[0]).toBe(w.find('#guest-write-record').element)
      w.unmount()
    } finally {
      Element.prototype.scrollIntoView = original
    }
  })

  it('says so when the read fails, and names the instance contact where it loads', async () => {
    fakeApi(publicOperatorApi, {
      getPrivacyNotice: () => Promise.reject(new Error('offline')),
      getOperatorDetails: () => Promise.resolve({ contactEmail: 'contact@example.org', contactLanguages: [] }),
    })
    const w = await mountIt()
    expect(w.find('.msg').text()).toBe(t('privacyNotice.loadFailed'))
    expect(w.find('[data-testid="privacy-notice"]').exists()).toBe(false)
    expect(w.find('[data-testid="privacy-notice-contact"] a').attributes('href')).toBe('mailto:contact@example.org')
    expect(authenticatedCalls).toEqual([])
    w.unmount()
  })

  it('names no contact when none loads either, and blocks nothing', async () => {
    fakeApi(publicOperatorApi, {
      getPrivacyNotice: () => Promise.reject(new Error('offline')),
      getOperatorDetails: () => Promise.reject(new Error('offline')),
    })
    const w = await mountIt()
    expect(w.find('.msg').text()).toBe(t('privacyNotice.loadFailed'))
    expect(w.find('[data-testid="privacy-notice-contact"]').exists()).toBe(false)
    w.unmount()
  })
})

describe('privacyLink — the addresses the collecting surfaces link', () => {
  it('names the page, and a passage by its anchor', () => {
    expect(privacyLink()).toBe('/privacy')
    expect(privacyLink(PRIVACY_ANCHOR.account)).toBe('/privacy#account')
    expect(privacyLink(PRIVACY_ANCHOR.reporterContact)).toBe('/privacy#reporter-contact')
    expect(privacyLink(PRIVACY_ANCHOR.guestWriteRecord)).toBe('/privacy#guest-write-record')
  })
})
