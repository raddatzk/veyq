// @vitest-environment happy-dom
// The ROOT Server-Owner gate (ADR 0065) — the artifact this package had to reconstruct rather than lift.
//
// In `web/` there was nothing to lift: `probeServerRole()` ran at data-fetch time and decided whether to OFFER
// a settings entry, so a non-owner simply never saw a link and there was no refusal surface to test. Here `/`
// IS the admin surface, so it has to answer the question, and the answer has to be readable. That is what
// these cases pin — one per verdict the backend can hand back:
//
//   403 -> a refusal a person can read, and NO console behind it
//   401 -> back to sign-in (the session lapsed between boot and mount)
//   200 -> the console
//
// Deliberately asserted through `listTenants` rejecting, not through a stubbed verdict: the gate's whole claim
// is that it mirrors the BACKEND's authorization rather than inventing one, so the fixture has to be the
// backend's own status code.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import { createI18n } from 'vue-i18n'

vi.mock('../features/tenants/tenants', () => ({ listTenants: vi.fn() }))
vi.mock('../features/auth/login', () => ({ logout: vi.fn().mockResolvedValue(undefined) }))
vi.mock('../features/tenants/CreateTenantDialog.vue', () => ({ default: { template: '<div class="stub-create" />' } }))
vi.mock('../features/tenants/TenantDetailPanel.vue', () => ({ default: { template: '<div class="stub-detail" />' } }))
vi.mock('../features/instance/ReleaseVersionPanel.vue', () => ({ default: { template: '<div class="stub-release" />' } }))

import AdminConsoleView from './AdminConsoleView.vue'
import { listTenants } from '../features/tenants/tenants'
import { resetServerRole } from '../app/serverRole'

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  missingWarn: false,
  fallbackWarn: false,
  messages: {
    en: {
      admin: {
        title: 'Server administration', sub: 's', signOut: 'Sign out', tenants: 'Tenants', server: 'Server',
        forbidden: 'This account is not the Server Owner.', forbiddenSub: 'reason', unreachable: 'unreachable',
        noTenants: 'No tenants yet.', loading: 'Loading…',
      },
      tenant: { create: 'Create' },
      release: { navTitle: 'Release' },
    },
  },
})

/** A thrown generated-client error carrying an HTTP response — the shape `httpStatus` reads. */
function httpError(status: number): Error {
  const e = new Error(`http ${status}`)
  ;(e as unknown as { response: Response }).response = new Response('', { status })
  return e
}

async function mountConsole() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'console', component: { template: '<div />' } },
      { path: '/login', name: 'login', component: { template: '<div />' } },
    ],
  })
  await router.replace('/')
  await router.isReady()
  const w = mount(AdminConsoleView, { global: { plugins: [router, i18n] } })
  await flushPromises()
  return { w, router }
}

beforeEach(() => {
  vi.clearAllMocks()
  resetServerRole()
})

describe('AdminConsoleView — the root Server-Owner gate', () => {
  it('refuses a non-Server-Owner session in words, and renders no console behind the refusal', async () => {
    vi.mocked(listTenants).mockRejectedValue(httpError(403))
    const { w } = await mountConsole()
    expect(w.text()).toContain('This account is not the Server Owner.')
    // The point of the case: not a blank page, not a crash, and not a console that merely failed to load —
    // the nav is absent, so there is nothing to click at and nothing that looks like it should have worked.
    expect(w.find('.admin-shell__nav').exists()).toBe(false)
    expect(w.find('.stub-detail').exists()).toBe(false)
  })

  it('sends a lapsed session to sign-in rather than showing it a refusal it cannot act on', async () => {
    vi.mocked(listTenants).mockRejectedValue(httpError(401))
    const { w, router } = await mountConsole()
    expect(router.currentRoute.value.name).toBe('login')
    expect(w.text()).not.toContain('This account is not the Server Owner.')
  })

  it('says the instance is unreachable rather than blaming the account', async () => {
    vi.mocked(listTenants).mockRejectedValue(new Error('network'))
    const { w } = await mountConsole()
    expect(w.text()).toContain('unreachable')
    expect(w.text()).not.toContain('This account is not the Server Owner.')
  })

  it('renders the console for the Server Owner, listing the tenants it administers', async () => {
    vi.mocked(listTenants).mockResolvedValue([
      { tenantId: 'tn-7', name: 'Acme', domain: 'acme.example', ownerEmail: 'o@acme.example', status: 'PENDING' } as never,
    ])
    const { w } = await mountConsole()
    expect(w.find('.admin-shell__nav').exists()).toBe(true)
    expect(w.text()).toContain('Acme')
    expect(w.text()).not.toContain('This account is not the Server Owner.')
  })

  it('opens a tenant’s detail page from the list', async () => {
    vi.mocked(listTenants).mockResolvedValue([
      { tenantId: 'tn-7', name: 'Acme', domain: 'acme.example', ownerEmail: 'o@acme.example', status: 'PENDING' } as never,
    ])
    const { w } = await mountConsole()
    const item = w.findAll('.admin-shell__nav button').find((b) => b.text().includes('Acme'))!
    await item.trigger('click')
    await flushPromises()
    expect(w.find('.stub-detail').exists()).toBe(true)
  })
})
