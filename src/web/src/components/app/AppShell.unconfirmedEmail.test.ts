// @vitest-environment happy-dom
// Where the unconfirmed-address banner is drawn (ADR 0129 decision 5): on every page of the signed-in app, above the
// route — and on no share page (the plain layout) and no public page (login, the join page), which never ask for the
// address at all. The banner is the real component; what it shows is `UnconfirmedEmailBanner.test.ts`'s. While it stands,
// and until its read has answered, the donation banner and the finish-your-profile prompt wait (owner 2026-11-13); they
// appear on a confirmed or unreadable address, and once a confirmation is seen.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'

vi.mock('./AppSidebar.vue', () => ({ default: { render: () => null } }))
vi.mock('./AppTopbar.vue', () => ({ default: { render: () => null } }))
vi.mock('../../features/account/profile/FinishProfilePrompt.vue', async () => {
  const { h } = await import('vue')
  return { default: { render: () => h('p', { 'data-testid': 'finish-profile' }) } }
})
vi.mock('../../core/project/DonationBanner.vue', async () => {
  const { h } = await import('vue')
  return { default: { render: () => h('p', { 'data-testid': 'donation-banner' }) } }
})
vi.mock('../../core/operator/operatorDetails', () => ({ loadPublishedOperator: async () => ({ identification: null, contact: null }) }))
vi.mock('../../features/account/email/emailConfirmation', async (orig) => ({
  ...(await orig<typeof import('../../features/account/email/emailConfirmation')>()),
  getAccountEmail: vi.fn(),
}))
vi.mock('../../app/activeSpace', async (orig) => ({
  ...(await orig<typeof import('../../app/activeSpace')>()),
  enterScope: vi.fn(),
}))

import AppShell from './AppShell.vue'
import { clearActiveSpace } from '../../app/activeSpace'
import { setTokens, clearTokens } from '../../app/session'
import { setUnlocked, clearVault } from '../../app/vault'
import { getAccountEmail } from '../../features/account/email/emailConfirmation'
import { realI18n } from '../../test/i18n'

const Page = { template: '<div class="page" />' }
const BANNER = '[data-testid="unconfirmed-email-banner"]'
const DONATION = '[data-testid="donation-banner"]'
const FINISH = '[data-testid="finish-profile"]'
const mounted: { unmount: () => void }[] = []

async function mountAt(path: string) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'home', component: Page, meta: { layout: 'app' } },
      { path: '/settings', name: 'settings', component: Page, meta: { layout: 'app' } },
      { path: '/login', name: 'login', component: Page },
      { path: '/tenant/accept', name: 'tenant-accept', component: Page },
      { path: '/s/:shareId', name: 'share', component: Page, meta: { layout: 'plain' } },
    ],
  })
  await router.replace(path)
  await router.isReady()
  const w = mount(AppShell, { global: { plugins: [router, realI18n()] } })
  mounted.push(w)
  await flushPromises()
  return w
}

beforeEach(() => {
  clearActiveSpace()
  setTokens('access', null)
  setUnlocked(new Uint8Array(32), { kemSeed: new Uint8Array(32), sign: new Uint8Array(32) }, 'device')
  vi.mocked(getAccountEmail).mockReset().mockResolvedValue({ email: 'bea@example.org', confirmed: false })
})
afterEach(() => {
  mounted.splice(0).forEach((w) => w.unmount())
  clearVault()
  clearTokens()
})

describe('AppShell — the unconfirmed-address banner', () => {
  it.each(['/', '/settings'])('the signed-in app draws it above the route on %s', async (path) => {
    const w = await mountAt(path)
    expect(w.findAll(BANNER)).toHaveLength(1)
    expect(w.find('main.content').element.firstElementChild?.matches(BANNER)).toBe(true)
  })

  it.each([
    ['a share page (the plain layout)', '/s/abc', '.plain-shell .page'],
    ['the login page', '/login', '.public-shell .page'],
    ['the join page', '/tenant/accept', '.public-shell .page'],
  ])('%s draws none and asks for no address', async (_name, path, drawn) => {
    const w = await mountAt(path)
    expect(w.find(drawn).exists()).toBe(true) // the route is drawn: the positive control
    expect(w.find(BANNER).exists()).toBe(false)
    expect(getAccountEmail).not.toHaveBeenCalled()
  })
})

describe('AppShell — the other notices wait while the address is unconfirmed', () => {
  it('an unconfirmed address shows the banner alone: no donation banner, no finish-your-profile prompt', async () => {
    const w = await mountAt('/')
    expect(w.find(BANNER).exists()).toBe(true)
    expect(w.find(DONATION).exists()).toBe(false)
    expect(w.find(FINISH).exists()).toBe(false)
  })

  it('a confirmed address shows both, and no banner (the positive control)', async () => {
    vi.mocked(getAccountEmail).mockResolvedValue({ email: 'bea@example.org', confirmed: true })
    const w = await mountAt('/')
    expect(w.find(BANNER).exists()).toBe(false)
    expect(w.findAll(DONATION)).toHaveLength(1)
    expect(w.findAll(FINISH)).toHaveLength(1)
  })

  it('an address that cannot be read holds nothing back, as the banner shows nothing', async () => {
    vi.mocked(getAccountEmail).mockRejectedValue(new Error('offline'))
    const w = await mountAt('/')
    expect(w.find(BANNER).exists()).toBe(false)
    expect(w.find(DONATION).exists()).toBe(true)
    expect(w.find(FINISH).exists()).toBe(true)
  })

  it('while the read is on its way neither shows, so none flashes and vanishes', async () => {
    let answer!: (v: { email: string; confirmed: boolean }) => void
    vi.mocked(getAccountEmail).mockReturnValue(new Promise((r) => { answer = r }))
    const w = await mountAt('/')
    expect(w.find(DONATION).exists()).toBe(false)
    expect(w.find(FINISH).exists()).toBe(false)
    answer({ email: 'bea@example.org', confirmed: true })
    await flushPromises()
    expect(w.find(DONATION).exists()).toBe(true) // the same shell, once answered: the positive control
    expect(w.find(FINISH).exists()).toBe(true)
  })

  it('a confirmation seen when the tab comes back lets both appear and takes the banner away', async () => {
    const w = await mountAt('/')
    expect(w.find(DONATION).exists()).toBe(false)
    vi.mocked(getAccountEmail).mockResolvedValue({ email: 'bea@example.org', confirmed: true })
    document.dispatchEvent(new Event('visibilitychange'))
    await flushPromises()
    expect(w.find(BANNER).exists()).toBe(false)
    expect(w.find(DONATION).exists()).toBe(true)
    expect(w.find(FINISH).exists()).toBe(true)
  })
})
