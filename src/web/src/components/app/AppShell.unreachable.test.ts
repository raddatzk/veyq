// @vitest-environment happy-dom
// "This server can't be reached right now" offers its way out (ADR 0136 decision 5): a request that failed at start is
// retried by starting again, so the banner carries a Reload button, which reloads the page.
import { describe, it, expect, vi, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'

vi.mock('../../features/account/email/UnconfirmedEmailBanner.vue', () => ({ default: { render: () => null } }))
vi.mock('../../core/operator/operatorDetails', () => ({ loadPublishedOperator: async () => ({ identification: null, contact: null }) }))

import AppShell from './AppShell.vue'
import { realI18n } from '../../test/i18n'
import { serverState } from '../../shared/state/serverState'

const Page = { template: '<div class="page" />' }
const mounted: { unmount: () => void }[] = []

async function mountLogin() {
  const r = createRouter({ history: createMemoryHistory(), routes: [{ path: '/login', name: 'login', component: Page }] })
  await r.replace('/login')
  await r.isReady()
  const w = mount(AppShell, { global: { plugins: [r, realI18n()] } })
  mounted.push(w)
  await flushPromises()
  return w
}

afterEach(() => {
  mounted.splice(0).forEach((w) => w.unmount())
  serverState.unreachable = false
  vi.unstubAllGlobals()
})

describe('AppShell — the unreachable banner', () => {
  it('names the state and offers Reload, which reloads the page', async () => {
    const reload = vi.fn()
    vi.stubGlobal('location', { reload })
    serverState.unreachable = true
    const w = await mountLogin()
    const banner = w.find('[data-testid="unreachable-banner"]')
    expect(banner.attributes('role')).toBe('alert')
    expect(banner.text()).toContain('This server can’t be reached right now.')
    const button = w.find('[data-testid="unreachable-reload"]')
    expect(button.text()).toBe('Reload')
    await button.trigger('click')
    expect(reload).toHaveBeenCalledTimes(1)
  })

  it('is not there while the server answers', async () => {
    const w = await mountLogin()
    expect(w.find('.public-shell .page').exists()).toBe(true)
    expect(w.find('[data-testid="unreachable-banner"]').exists()).toBe(false)
  })
})
