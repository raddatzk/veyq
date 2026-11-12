// @vitest-environment happy-dom
// The public shell's two widths: a sign-in, join or recovery card sits in the narrow 460 px column, while a page of
// running legal text — the operator terms, the privacy notice, the terms a member accepts — is flagged `meta.reading`
// and gets a reading measure. The real router's three legal routes carry the flag, and no other public route does.
import { describe, it, expect, vi, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'

vi.mock('../../core/operator/operatorDetails', () => ({ loadPublishedOperator: async () => ({ identification: null, contact: null }) }))

import AppShell from './AppShell.vue'
import router from '../../router'
import { realI18n } from '../../test/i18n'

const Page = { template: '<div class="page" />' }
const mounted: { unmount: () => void }[] = []

async function mountAt(path: string) {
  const r = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/login', name: 'login', component: Page },
      { path: '/privacy', name: 'privacy-notice', component: Page, meta: { reading: true } },
    ],
  })
  await r.replace(path)
  await r.isReady()
  const w = mount(AppShell, { global: { plugins: [r, realI18n()] } })
  mounted.push(w)
  await flushPromises()
  return w
}

afterEach(() => mounted.splice(0).forEach((w) => w.unmount()))

describe('AppShell — the public shell reads legal text at a reading width', () => {
  it('a reading route widens the column', async () => {
    const w = await mountAt('/privacy')
    expect(w.find('.public-shell .page').exists()).toBe(true)
    expect(w.find('.veyq-shell-inner').classes()).toContain('veyq-shell-inner--reading')
  })

  it('a sign-in page keeps the narrow card column', async () => {
    const w = await mountAt('/login')
    expect(w.find('.public-shell .page').exists()).toBe(true)
    expect(w.find('.veyq-shell-inner').classes()).not.toContain('veyq-shell-inner--reading')
  })

  it('the real router flags exactly the three legal-text routes', () => {
    const reading = router
      .getRoutes()
      .filter((r) => r.meta.reading === true)
      .map((r) => r.name)
      .sort()
    expect(reading).toEqual(['operator-terms', 'privacy-notice', 'terms'])
  })
})
