// @vitest-environment happy-dom
// uc.platform.support-the-project in the shell: the signed-in app draws the donation banner as a MEMBER's (it reads the
// account's flag) once, above the route, and its footer carries veyq's own two links; the public layout — the login,
// invite, join and recovery pages — draws no banner (A1), and neither does the plain layout, whose share page brings
// its own guest's banner (`ShareView.vue`). The banner and the footer are the real components.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'

// The unconfirmed-address banner reads the account's address; its own place is AppShell.unconfirmedEmail.test.ts.
vi.mock('../../features/account/email/UnconfirmedEmailBanner.vue', () => ({ default: { render: () => null } }))
vi.mock('./AppSidebar.vue', () => ({ default: { render: () => null } }))
vi.mock('./AppTopbar.vue', () => ({ default: { render: () => null } }))
vi.mock('../../features/account/profile/FinishProfilePrompt.vue', () => ({ default: { render: () => null } }))
vi.mock('../../features/account/profile/profile', () => ({ getMyProfile: vi.fn(), updateMyProfile: vi.fn() }))
vi.mock('../../core/operator/operatorDetails', () => ({ loadPublishedOperator: async () => ({ identification: null, contact: null }) }))
vi.mock('../../app/activeSpace', async (orig) => ({
  ...(await orig<typeof import('../../app/activeSpace')>()),
  enterScope: vi.fn(),
}))

import AppShell from './AppShell.vue'
import { clearActiveSpace } from '../../app/activeSpace'
import { setTokens, clearTokens } from '../../app/session'
import { setUnlocked, clearVault } from '../../app/vault'
import { getMyProfile } from '../../features/account/profile/profile'
import { forgetDonationDismissalForTest } from '../../core/project/donationDismissal'
import { memberProfile } from '../../test/models'
import { realI18n } from '../../test/i18n'

const Page = { template: '<div class="page" />' }
const BANNER = '[data-testid="donation-banner"]'
const mounted: { unmount: () => void }[] = []

async function mountAt(path: string) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'home', component: Page, meta: { layout: 'app' } },
      { path: '/login', name: 'login', component: Page },
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
  localStorage.clear()
  forgetDonationDismissalForTest()
  clearActiveSpace()
  setTokens('access', null)
  setUnlocked(new Uint8Array(32), { kemSeed: new Uint8Array(32), sign: new Uint8Array(32) }, 'device')
  vi.mocked(getMyProfile).mockReset().mockResolvedValue(memberProfile({ donationBannerDismissed: false }))
})
afterEach(() => {
  mounted.splice(0).forEach((w) => w.unmount())
  clearVault()
  clearTokens()
})

describe('AppShell — the donation banner and the footer links (uc.platform.support-the-project)', () => {
  it('the signed-in app shows one member banner above the route, and the footer links the project', async () => {
    const w = await mountAt('/')
    expect(w.findAll(BANNER)).toHaveLength(1)
    expect(getMyProfile).toHaveBeenCalledOnce() // a member's banner: the account is asked
    expect(w.find('main.content').element.firstElementChild?.matches(BANNER)).toBe(true)
    expect(w.findAll('.app-operator-footer .operator-identification__project').map((l) => l.attributes('href'))).toEqual([
      'https://veyq.app/privacy/is-my-data-safe/',
      'https://veyq.app/donate/',
    ])
  })

  it('the login page (the public layout) shows no banner and asks no account', async () => {
    const w = await mountAt('/login')
    expect(w.find('.public-shell .page').exists()).toBe(true) // the route is drawn: the positive control
    expect(w.find(BANNER).exists()).toBe(false)
    expect(getMyProfile).not.toHaveBeenCalled()
  })

  it('the plain layout adds none of its own (the share page brings the guest one)', async () => {
    const w = await mountAt('/s/abc')
    expect(w.find('.plain-shell .page').exists()).toBe(true)
    expect(w.find(BANNER).exists()).toBe(false)
  })
})
