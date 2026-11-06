// @vitest-environment happy-dom
// uc.admin.operator-details `the-identification-appears-on-the-login-and-share-pages` (ADR 0035 decision 7) and the
// operator section's public reach (ADR 0035 decision 6): the footer always links the public terms page — no read needed,
// so a failed read leaves it standing — and behind "Legal notice" shows the provider identification and the point of
// contact for users and authorities with its languages (ADR 0093 § 11), read through the PUBLIC client — a guest-mode
// mount, so any call to an authenticated client is red (inbox #401) — and fail-open (A5). The values open only on the
// click, as the link promises.
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
    contactEmail: undefined, contactLanguages: [], statementEmail: undefined, providerName: undefined,
    providerAddress: undefined, providerEmail: undefined, providerRepresentative: undefined, supervisoryAuthority: undefined, ...over,
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

  it('shows the point of contact for users and authorities with its languages, a contact alone included', async () => {
    stubApi(publicOperatorApi, { getOperatorDetails: details({ contactEmail: 'contact@example.org', contactLanguages: ['de', 'en'] }) })
    const w = await mountIt()
    await w.find('.operator-identification__link').trigger('click')
    const contact = w.find('.operator-identification__contact')
    expect(contact.text()).toContain('Contact for users and authorities:')
    expect(contact.find('a').attributes('href')).toBe('mailto:contact@example.org')
    expect(w.find('.operator-identification__languages').text()).toBe('Languages: de, en')
  })

  it('always links the public terms of this instance, in a tab of its own', async () => {
    stubApi(publicOperatorApi, { getOperatorDetails: details() })
    const w = await mountIt()
    const link = w.find('.operator-identification__terms')
    expect(link.text()).toBe('Terms of this server')
    expect(link.attributes('href')).toBe('/operator-terms')
    expect(link.attributes('target')).toBe('_blank')
  })

  it('always links the privacy notice too, in a tab of its own — one click from every page (ADR 0035 decision 8)', async () => {
    fakeApi(publicOperatorApi, { getOperatorDetails: () => Promise.reject(new Error('offline')) })
    const w = await mountIt()
    const link = w.find('.operator-identification__privacy')
    expect(link.text()).toBe('Privacy')
    expect(link.attributes('href')).toBe('/privacy')
    expect(link.attributes('target')).toBe('_blank')
    expect(authenticatedCalls).toEqual([])
  })

  it('speaks the page’s language', async () => {
    stubApi(publicOperatorApi, { getOperatorDetails: identified })
    i18n.global.locale.value = 'de'
    const w = await mountIt()
    expect(w.find('.operator-identification__link').text()).toBe('Impressum')
    expect(w.find('.operator-identification__terms').text()).toBe('Bedingungen dieses Servers')
    expect(w.find('.operator-identification__privacy').text()).toBe('Datenschutz')
  })

  it('shows no legal notice while nothing of it is published (control: the terms link stays)', async () => {
    stubApi(publicOperatorApi, { getOperatorDetails: details() })
    const w = await mountIt()
    expect(w.find('.operator-identification__link').exists()).toBe(false)
    expect(w.find('.operator-identification__terms').exists()).toBe(true)
    expect(authenticatedCalls).toEqual([])
  })

  it('a failed read shows no legal notice and blocks nothing (fail-open), the terms link stays', async () => {
    fakeApi(publicOperatorApi, { getOperatorDetails: () => Promise.reject(new Error('offline')) })
    const w = await mountIt()
    expect(w.find('.operator-identification__link').exists()).toBe(false)
    expect(w.html()).not.toContain('Legal notice')
    expect(w.find('.operator-identification__terms').exists()).toBe(true)
  })
})
