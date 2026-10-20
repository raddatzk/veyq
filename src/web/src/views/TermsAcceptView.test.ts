// @vitest-environment happy-dom
// uc.account.accept-terms's way out, as a uc.account.sign-out entry point. Declining the terms signs out, and it
// is the one sign-out sequence rather than this screen's own: this view carried a copy of it once, which reset
// less than the real one did. And the member gate's operator section (ADR 0035 decision 6): after the space's text,
// the record's passage at its anchor and the right to object apart, and the accept naming the whole triple.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

vi.mock('../features/account/login/signOut', () => ({ signOut: vi.fn() }))
vi.mock('../features/tenant/tenantTerms', () => ({ getTenantTerms: vi.fn(), acceptTenantTerms: vi.fn() }))
vi.mock('../features/tenant/tenantError', () => ({ tenantError: vi.fn() }))
vi.mock('../app/pendingRoute', () => ({ takeRoute: () => null }))
vi.mock('../app/activeSpace', async () => {
  const { reactive } = await import('vue')
  return { activeSpace: reactive({ tenantId: 'tenant-1' }), markTermsAccepted: vi.fn() }
})
const push = vi.fn()
vi.mock('vue-router', () => ({ useRouter: () => ({ push, replace: vi.fn() }) }))

import TermsAcceptView from './TermsAcceptView.vue'
import { signOut } from '../features/account/login/signOut'
import { getTenantTerms, acceptTenantTerms } from '../features/tenant/tenantTerms'
import { realI18n, t } from '../test/i18n'

const stubs = {
  Card: { template: '<div><slot name="title" /><slot name="content" /></div>' },
  Message: { props: ['severity'], template: '<div class="msg"><slot /></div>' },
  // `emits` is load-bearing: without it the parent's @click also arrives as a native fallthrough listener.
  Button: {
    props: ['label', 'icon', 'severity', 'loading', 'disabled'],
    emits: ['click'],
    template: '<button :disabled="loading || disabled" @click="$emit(\'click\')">{{ label }}</button>',
  },
}

async function mountReady() {
  const w = mount(TermsAcceptView, { global: { plugins: [realI18n()], stubs } })
  await flushPromises()
  return w
}

describe('TermsAcceptView — declining signs out (uc.account.sign-out)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(signOut).mockResolvedValue(undefined)
    vi.mocked(getTenantTerms).mockResolvedValue({
      termsRevision: 3,
      accepted: false,
      terms: { en: 'Be kind.', de: 'Sei nett.' },
      operatorSection: {
        revision: 2,
        variant: 'REDUCED',
        text: { en: '## On this instance\nReports go to a person.', de: '## Auf dieser Instanz\nMeldungen prüft ein Mensch.' },
        record: { en: '### Your IP address\nIn the privacy notice.', de: '### Deine IP-Adresse\nIn der Datenschutzerklärung.' },
      },
    } as Awaited<ReturnType<typeof getTenantTerms>>)
  })

  it('runs the one sign-out sequence, and records nothing', async () => {
    const wrapper = await mountReady()
    const decline = wrapper.findAll('button').find((b) => b.text() === t('terms.signOut'))!

    await decline.trigger('click')
    await flushPromises()

    expect(signOut).toHaveBeenCalledExactlyOnceWith()
    expect(acceptTenantTerms).not.toHaveBeenCalled()
    // No in-app route change: the sign-out's own ending loads the sign-in page as a new document.
    expect(push).not.toHaveBeenCalled()
  })

  it('does not sign out when the member accepts instead', async () => {
    // The control for the one above: the same screen, the other button.
    vi.mocked(acceptTenantTerms).mockResolvedValue({ accepted: true } as Awaited<ReturnType<typeof acceptTenantTerms>>)
    const wrapper = await mountReady()
    const accept = wrapper.findAll('button').find((b) => b.text() === t('terms.accept'))!

    await accept.trigger('click')
    await flushPromises()

    expect(acceptTenantTerms).toHaveBeenCalledWith('tenant-1', { revision: 3, operatorRevision: 2, variant: 'REDUCED' })
    expect(signOut).not.toHaveBeenCalled()
  })
})

describe('TermsAcceptView — the operator section after the space’s terms (ADR 0035 decision 6)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  function served(record: boolean) {
    vi.mocked(getTenantTerms).mockResolvedValue({
      termsRevision: 3,
      accepted: false,
      terms: { en: 'Be kind.', de: 'Sei nett.' },
      operatorSection: {
        revision: 1,
        variant: 'FULL',
        text: { en: '## On this instance\nReports go to a person.', de: '## Auf dieser Instanz' },
        record: record ? { en: '### Your IP address\nIn the privacy notice.', de: '### Deine IP-Adresse' } : null,
      },
    } as Awaited<ReturnType<typeof getTenantTerms>>)
  }

  it('shows the space’s text first, then the section, and the record’s pointer at its anchor linking the notice', async () => {
    served(true)
    const w = await mountReady()
    const body = w.find('.terms-body').text()
    expect(body.indexOf('Be kind.')).toBeGreaterThanOrEqual(0)
    expect(body.indexOf('On this instance')).toBeGreaterThan(body.indexOf('Be kind.'))
    expect(w.find('#guest-write-record').text()).toContain('In the privacy notice.')
    // The record's Art. 13 information is the privacy notice's (Q27 of cr-instance-privacy-notice): linked, not repeated.
    expect(w.find('#guest-write-record a').attributes('href')).toBe('/privacy#guest-write-record')
  })

  it('shows no record passage while the record is off (control: the section itself stays)', async () => {
    served(false)
    const w = await mountReady()
    expect(w.find('#guest-write-record').exists()).toBe(false)
    expect(w.find('[data-testid="operator-section"]').text()).toContain('On this instance')
  })
})
