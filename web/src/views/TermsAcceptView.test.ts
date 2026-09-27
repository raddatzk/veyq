// @vitest-environment happy-dom
// uc.account.accept-terms's way out, as a uc.account.sign-out entry point. Declining the terms signs out, and it
// is the one sign-out sequence rather than this screen's own: this view carried a copy of it once, which reset
// less than the real one did.
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
import { i18n } from '../i18n'

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

const t = (key: string) => i18n.global.t(key)

async function mountReady() {
  const w = mount(TermsAcceptView, { global: { plugins: [i18n], stubs } })
  await flushPromises()
  return w
}

describe('TermsAcceptView — declining signs out (uc.account.sign-out)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    i18n.global.locale.value = 'en'
    vi.mocked(signOut).mockResolvedValue(undefined)
    vi.mocked(getTenantTerms).mockResolvedValue({
      termsVersion: 3,
      terms: { en: 'Be kind.', de: 'Sei nett.' },
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
    vi.mocked(acceptTenantTerms).mockResolvedValue({ acceptedVersion: 3 } as Awaited<ReturnType<typeof acceptTenantTerms>>)
    const wrapper = await mountReady()
    const accept = wrapper.findAll('button').find((b) => b.text() === t('terms.accept'))!

    await accept.trigger('click')
    await flushPromises()

    expect(acceptTenantTerms).toHaveBeenCalledWith('tenant-1', 3)
    expect(signOut).not.toHaveBeenCalled()
  })
})
