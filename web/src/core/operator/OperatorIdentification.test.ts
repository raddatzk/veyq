// @vitest-environment happy-dom
// uc.admin.operator-details `the-identification-appears-on-the-login-and-share-pages` (ADR 0035 decision 7): the
// provider identification behind a footer link, read through the PUBLIC client — a guest-mode mount, so any call to an
// authenticated client is red (inbox #401) — and fail-open (A5): no identification published, or a failed read, shows
// nothing and throws nothing. The values open only on the click, as the link promises.
import { describe, it, expect, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { vi } from 'vitest'

vi.mock('../../api/client', async (orig) => (await import('../../test/guestMode')).guardedClients(await orig<object>()))

import OperatorIdentification from './OperatorIdentification.vue'
import { publicOperatorApi } from '../../api/client'
import { authenticatedCalls } from '../../test/guestMode'
import { fakeApi, stubApi } from '../../test/apiMock'
import { i18n } from '../../i18n'
import type { OperatorDetails } from '../../api/generated/operator'

function details(over: Partial<OperatorDetails> = {}): OperatorDetails {
  return {
    contactEmail: 'contact@example.org', contactLanguages: ['de'], statementEmail: null, providerName: null,
    providerAddress: null, providerEmail: null, providerRepresentative: null, supervisoryAuthority: null, ...over,
  }
}

const identified = details({
  providerName: 'Example e. V.', providerAddress: 'Musterstraße 1\n12345 Musterstadt', providerEmail: 'info@example.org',
  providerRepresentative: 'Erika Mustermann',
})

async function mountIt() {
  const w = mount(OperatorIdentification, { global: { plugins: [i18n] } })
  await flushPromises()
  return w
}

describe('OperatorIdentification (uc.admin.operator-details, web)', () => {
  beforeEach(() => {
    authenticatedCalls.length = 0
    i18n.global.locale.value = 'en'
  })

  it('shows a footer link that opens the published identification, read through the public client only', async () => {
    const api = stubApi(publicOperatorApi, { getOperatorDetails: identified })
    const w = await mountIt()
    expect(api.getOperatorDetails).toHaveBeenCalledOnce()
    expect(w.find('.operator-identification__link').text()).toBe('Legal notice')
    expect(w.find('address').exists()).toBe(false) // closed until clicked

    await w.find('.operator-identification__link').trigger('click')
    const address = w.find('address')
    expect(address.text()).toContain('Example e. V.')
    expect(address.find('.operator-identification__address').text()).toBe('Musterstraße 1\n12345 Musterstadt')
    expect(address.find('a').attributes('href')).toBe('mailto:info@example.org')
    expect(address.text()).toContain('Represented by Erika Mustermann')
    expect(authenticatedCalls).toEqual([])
  })

  it('speaks the page’s language', async () => {
    stubApi(publicOperatorApi, { getOperatorDetails: identified })
    i18n.global.locale.value = 'de'
    const w = await mountIt()
    expect(w.find('.operator-identification__link').text()).toBe('Impressum')
  })

  it('shows nothing while no part of the identification is published — a contact alone is not one', async () => {
    stubApi(publicOperatorApi, { getOperatorDetails: details() })
    const w = await mountIt()
    expect(w.find('.operator-identification').exists()).toBe(false)
    expect(authenticatedCalls).toEqual([])
  })

  it('a failed read shows nothing and blocks nothing (fail-open)', async () => {
    fakeApi(publicOperatorApi, { getOperatorDetails: () => Promise.reject(new Error('offline')) })
    const w = await mountIt()
    expect(w.find('.operator-identification').exists()).toBe(false)
    expect(w.html()).not.toContain('Legal notice')
  })
})
