// @vitest-environment happy-dom
// The shell opens a scope for an app page that is not the home: only the home enters one (from its #fragment), so a
// reload on /settings ran with none — no tenant sections, no terms gate, no tenant capabilities. The shell enters the
// token-less tenant home there, once, and nowhere it must not: not on the home (its fragment decides), not while
// locked or signed out, not over a scope that is open or opening, not on a suspended tenant's domain.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'

vi.mock('./AppSidebar.vue', () => ({ default: { render: () => null } }))
vi.mock('./AppTopbar.vue', () => ({ default: { render: () => null } }))
vi.mock('../../features/account/profile/FinishProfilePrompt.vue', () => ({ default: { render: () => null } }))
vi.mock('../../core/operator/OperatorIdentification.vue', () => ({ default: { render: () => null } }))
vi.mock('../../core/project/DonationBanner.vue', () => ({ default: { render: () => null } }))
vi.mock('../../app/activeSpace', async (orig) => ({
  ...(await orig<typeof import('../../app/activeSpace')>()),
  enterScope: vi.fn(),
}))

import AppShell from './AppShell.vue'
import { activeSpace, clearActiveSpace, enterScope } from '../../app/activeSpace'
import { session, setTokens, clearTokens } from '../../app/session'
import { setUnlocked, clearVault } from '../../app/vault'
import { serverState } from '../../shared/state/serverState'
import { TENANT_SCOPE } from '../../core/node/location'
import { realI18n } from '../../test/i18n'

const Page = { template: '<div />' }
const mounted: { unmount: () => void }[] = []

async function mountAt(path: string) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'home', component: Page, meta: { layout: 'app' } },
      { path: '/settings/:section?', name: 'settings', component: Page, meta: { layout: 'app' } },
      { path: '/login', name: 'login', component: Page },
    ],
  })
  await router.replace(path)
  await router.isReady()
  const w = mount(AppShell, { global: { plugins: [router, realI18n()] } })
  mounted.push(w)
  await flushPromises()
  return { w, router }
}

const unlock = () => {
  setTokens('access', null)
  setUnlocked(new Uint8Array(32), { kemSeed: new Uint8Array(32), sign: new Uint8Array(32) }, 'device')
}

beforeEach(() => {
  vi.mocked(enterScope).mockReset()
  clearActiveSpace()
  serverState.suspended = false
  unlock()
})
afterEach(() => {
  mounted.splice(0).forEach((w) => w.unmount())
  clearVault()
  clearTokens()
})

describe('AppShell — a scope for an app page loaded straight', () => {
  it('a reload on /settings enters the token-less tenant home, once', async () => {
    await mountAt('/settings/tenant-members')
    expect(enterScope).toHaveBeenCalledTimes(1)
    expect(enterScope).toHaveBeenCalledWith(TENANT_SCOPE)
  })

  it('leaves the home to itself: its fragment names the scope', async () => {
    await mountAt('/')
    expect(enterScope).not.toHaveBeenCalled()
  })

  it('enters when the home is left for /settings with nothing open yet', async () => {
    const { router } = await mountAt('/')
    await router.push('/settings')
    await flushPromises()
    expect(enterScope).toHaveBeenCalledWith(TENANT_SCOPE)
  })

  it('does not enter over a scope that is open or opening', async () => {
    activeSpace.status = 'ready'
    await mountAt('/settings')
    activeSpace.status = 'loading'
    await flushPromises()
    expect(enterScope).not.toHaveBeenCalled()
  })

  it('does not enter while locked', async () => {
    clearVault()
    await mountAt('/settings')
    expect(enterScope).not.toHaveBeenCalled()
  })

  it('does not enter while signed out', async () => {
    clearTokens()
    await mountAt('/settings')
    expect(session.accessToken).toBeNull()
    expect(enterScope).not.toHaveBeenCalled()
  })

  it('does not enter outside the app layout', async () => {
    await mountAt('/login')
    expect(enterScope).not.toHaveBeenCalled()
  })

  it('does not enter on a suspended tenant\'s domain', async () => {
    serverState.suspended = true
    await mountAt('/settings')
    expect(enterScope).not.toHaveBeenCalled()
  })

  it('a re-lock on /settings does not re-enter: the cleared space is idle, but the vault is closed', async () => {
    await mountAt('/settings')
    vi.mocked(enterScope).mockReset()
    activeSpace.status = 'ready'
    await flushPromises()
    clearActiveSpace()
    clearVault()
    await flushPromises()
    expect(enterScope).not.toHaveBeenCalled()
  })
})
