// @vitest-environment happy-dom
// uc.platform.search E3 in the shell: the member's app layout draws the top bar's search field; the plain layout - the
// share page a guest opens (`/s/:shareId`) - draws no field at all (ADR 0125 decision 7). The top bar is the real one.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'

// The unconfirmed-address banner reads the account's address; its own place is AppShell.unconfirmedEmail.test.ts.
vi.mock('../../features/account/email/UnconfirmedEmailBanner.vue', () => ({ default: { render: () => null } }))
vi.mock('./AppSidebar.vue', () => ({ default: { render: () => null } }))
vi.mock('../../features/account/profile/FinishProfilePrompt.vue', () => ({ default: { render: () => null } }))
vi.mock('../../core/operator/OperatorIdentification.vue', () => ({ default: { render: () => null } }))
vi.mock('../../core/project/DonationBanner.vue', () => ({ default: { render: () => null } }))
vi.mock('../../features/spaces/InvitationsBadge.vue', () => ({ default: { render: () => null } }))
vi.mock('../../app/activeSpace', async (orig) => ({ ...(await orig<typeof import('../../app/activeSpace')>()), enterScope: vi.fn() }))

import AppShell from './AppShell.vue'
import { clearActiveSpace } from '../../app/activeSpace'
import { setTokens, clearTokens } from '../../app/session'
import { setUnlocked, clearVault } from '../../app/vault'
import { realI18n } from '../../test/i18n'

const Page = { template: '<div class="page" />' }
const FIELD = '[data-testid="search-field"]'
const mounted: { unmount: () => void }[] = []

async function mountAt(path: string) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'home', component: Page, meta: { layout: 'app' } },
      { path: '/s/:shareId', name: 'share', component: Page, meta: { layout: 'plain' } },
    ],
  })
  await router.replace(path)
  await router.isReady()
  const w = mount(AppShell, { global: { plugins: [router, realI18n()] }, attachTo: document.body })
  mounted.push(w)
  await flushPromises()
  return w
}

beforeEach(() => {
  clearActiveSpace()
  setTokens('access', null)
  setUnlocked(new Uint8Array(32), { kemSeed: new Uint8Array(32), sign: new Uint8Array(32) }, 'device')
})
afterEach(() => {
  mounted.splice(0).forEach((w) => w.unmount())
  clearVault()
  clearTokens()
})

describe('the search field in the shell', () => {
  it('is in the member\'s top bar', async () => {
    const w = await mountAt('/')
    expect(w.find('.page').exists()).toBe(true)
    expect(w.find(FIELD).exists()).toBe(true)
  })

  it('is nowhere on a share page (the plain layout)', async () => {
    const w = await mountAt('/s/abc')
    expect(w.find('.page').exists()).toBe(true)
    expect(w.find(FIELD).exists()).toBe(false)
    expect(document.body.querySelector('input.veyq-tags-input__input')).toBeNull()
  })
})
