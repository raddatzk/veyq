// @vitest-environment happy-dom
// The invite secret rides the URL fragment and stays valid until accept — through a register flow that ends on
// the Emergency Kit screen, the one screen people are told to screenshot. So the view strips it from the address
// bar once read, and must keep vue-router's history.state while doing so. Stripping makes a reload land here
// with no secret at all; that case must not claim the (still working) link is invalid.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'

const route = vi.hoisted(() => ({ hash: '' }))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }), useRoute: () => route }))
vi.mock('../features/tenant/tenantInvite', () => ({
  previewInvite: vi.fn(),
  acceptInvite: vi.fn(),
  registerAndAccept: vi.fn(),
  bornTenantScope: vi.fn(),
  tenantError: vi.fn().mockResolvedValue({ status: 0, code: null }),
}))
vi.mock('../features/account/emergencyKit/EmergencyKitSave.vue', () => ({
  default: { template: '<div class="stub-kit" />' },
}))

import TenantJoinView from './TenantJoinView.vue'
import { previewInvite } from '../features/tenant/tenantInvite'

const stubs = {
  Card: { template: '<div><slot name="title" /><slot name="content" /></div>' },
  Button: { props: ['label'], template: '<button>{{ label }}</button>' },
  Message: { template: '<div class="msg"><slot /></div>' },
  InputText: { template: '<input />' },
  Password: { template: '<input type="password" />' },
}

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  missingWarn: false,
  fallbackWarn: false,
  messages: { en: { tenant: { join: { missing: 'MISSING', invalid: 'INVALID', loading: 'LOADING' } } } },
})

function mountView() {
  return mount(TenantJoinView, { global: { plugins: [i18n], stubs } })
}

beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(previewInvite).mockResolvedValue({
    tenantName: 'Acme', domain: 'acme.example', ownerEmail: 'owner@acme.example', hasAccount: false, role: 'OWNER',
  } as never)
})

describe('TenantJoinView — the invite secret in the address bar', () => {
  it('strips the fragment once read, keeping the router\'s history state, and still previews with it', async () => {
    const routerState = { back: null, current: '/tenant/accept', forward: null, position: 1 }
    window.history.replaceState(routerState, '', '/tenant/accept#the-bootstrap-secret')
    route.hash = '#the-bootstrap-secret'
    mountView()
    await flushPromises()
    expect(window.location.hash).toBe('')
    expect(window.location.pathname).toBe('/tenant/accept')
    expect(window.history.state).toEqual(routerState)
    // The control: the secret was read BEFORE the strip — the preview got it.
    expect(previewInvite).toHaveBeenCalledWith('the-bootstrap-secret')
  })

  it('with no secret (a reload after the strip) says to reopen the link — not that the link is invalid', async () => {
    window.history.replaceState(null, '', '/tenant/accept')
    route.hash = ''
    const w = mountView()
    await flushPromises()
    expect(w.text()).toContain('MISSING')
    expect(w.text()).not.toContain('INVALID')
    expect(previewInvite).not.toHaveBeenCalled()
  })

  it('a secret the server rejects is still "invalid"', async () => {
    // The positive control for the negative above: 'invalid' is still reachable, just not by a missing fragment.
    window.history.replaceState(null, '', '/tenant/accept#nope')
    route.hash = '#nope'
    vi.mocked(previewInvite).mockRejectedValue(new Error('404'))
    const w = mountView()
    await flushPromises()
    expect(w.text()).toContain('INVALID')
    expect(w.text()).not.toContain('MISSING')
  })
})
