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
  VeyqCard: { template: '<div><slot name="title" /><slot /><slot name="footer" /></div>' },
  VeyqMessage: { props: ['tone'], template: '<div class="msg"><slot /></div>' },
}

const NO_DETAILS = {
  contactLanguages: [],
}

const SECTION = {
  revision: 1,
  text: { en: '## On this instance\nReports go to a person.', de: '## Auf dieser Instanz' },
  record: { en: '### Your IP address when you add something as a guest\nIn the privacy notice.', de: '### Deine IP-Adresse' },
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

  it('shows the section without an account: the record’s pointer at its anchor, linking the privacy notice', async () => {
    stubApi(publicOperatorApi, { getOperatorTerms: SECTION, getOperatorDetails: NO_DETAILS })
    const w = await mountIt()
    expect(w.text()).toContain(t('operatorTerms.intro'))
    expect(w.find('[data-testid="operator-section"]').text()).toContain('Reports go to a person.')
    expect(w.find('#guest-write-record').text()).toContain('In the privacy notice.')
    // The record's Art. 13 information is the notice's (Q27): the pointer links its passage, in a tab of its own.
    const link = w.find('#guest-write-record a.operator-section__record-link')
    expect(link.attributes('href')).toBe('/privacy#guest-write-record')
    expect(link.attributes('target')).toBe('_blank')
    expect(w.find('aside').exists()).toBe(false) // no right to object of the section's own any more
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
