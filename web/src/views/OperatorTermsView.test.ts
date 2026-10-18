// @vitest-environment happy-dom
// The public page of the operator section of the terms (ADR 0035 decision 6; DSA Art. 14(1): publicly available and
// easily accessible): read through the PUBLIC client — a guest-mode mount, so any call to an authenticated client is
// red (inbox #401) — with the record's passage at the anchor the first-layer sentence links and the right to object
// apart; a failed read says so.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

vi.mock('../api/client', async (orig) => (await import('../test/guestMode')).guardedClients(await orig<object>()))
const route = { hash: '' }
vi.mock('vue-router', () => ({ useRoute: () => route }))

import OperatorTermsView from './OperatorTermsView.vue'
import { publicOperatorApi } from '../api/client'
import { authenticatedCalls } from '../test/guestMode'
import { fakeApi, stubApi } from '../test/apiMock'
import { realI18n, t } from '../test/i18n'

const stubs = {
  Card: { template: '<div><slot name="title" /><slot name="content" /><slot name="footer" /></div>' },
  Message: { props: ['severity'], template: '<div class="msg"><slot /></div>' },
}

const NO_DETAILS = {
  contactLanguages: [],
}

const SECTION = {
  revision: 1,
  variant: 'FULL' as const,
  text: { en: '## On this instance\nReports go to a person.', de: '## Auf dieser Instanz' },
  record: { en: '### Your IP address when you add something as a guest\nKept 7 days.', de: '### Deine IP-Adresse' },
  objection: { en: '### Your right to object\nWrite to us.', de: '### Dein Widerspruchsrecht' },
}

async function mountIt() {
  const w = mount(OperatorTermsView, { global: { plugins: [realI18n()], stubs }, attachTo: document.body })
  await flushPromises()
  return w
}

describe('OperatorTermsView — the operator section, public', () => {
  beforeEach(() => {
    authenticatedCalls.length = 0
    route.hash = ''
  })

  it('shows the section without an account: the record at its anchor, the objection apart', async () => {
    stubApi(publicOperatorApi, { getOperatorTerms: SECTION, getOperatorDetails: NO_DETAILS })
    const w = await mountIt()
    expect(w.text()).toContain(t('operatorTerms.intro'))
    expect(w.find('[data-testid="operator-section"]').text()).toContain('Reports go to a person.')
    expect(w.find('#guest-write-record').text()).toContain('Kept 7 days.')
    expect(w.find('aside#right-to-object').text()).toContain('Your right to object')
    expect(authenticatedCalls).toEqual([])
    w.unmount()
  })

  it('scrolls to the passage the link named once it is rendered', async () => {
    route.hash = '#guest-write-record'
    const scrolled = vi.fn()
    const original = Element.prototype.scrollIntoView
    Element.prototype.scrollIntoView = scrolled
    stubApi(publicOperatorApi, { getOperatorTerms: SECTION, getOperatorDetails: NO_DETAILS })
    try {
      const w = await mountIt()
      expect(scrolled).toHaveBeenCalledOnce()
      expect(scrolled.mock.contexts[0]).toBe(w.find('#guest-write-record').element)
      w.unmount()
    } finally {
      Element.prototype.scrollIntoView = original
    }
  })

  it('says so when the read fails, and shows no section', async () => {
    fakeApi(publicOperatorApi, {
      getOperatorTerms: () => Promise.reject(new Error('offline')),
      getOperatorDetails: () => Promise.reject(new Error('offline')),
    })
    const w = await mountIt()
    expect(w.find('.msg').text()).toBe(t('operatorTerms.loadFailed'))
    expect(w.find('[data-testid="operator-section"]').exists()).toBe(false)
    w.unmount()
  })
})
