// @vitest-environment happy-dom
// The *Open in the app* banner as rendered and clicked (ADR 0150 § O2), over the real router and the real i18n, as a guest
// (every authenticated client throws and is recorded): its link's href is the constant and nothing of the page — the share
// page's address holds a key in its fragment, and no key may reach a `veyq:` link —; on the share page the click puts the
// page's link on the clipboard, normalized without `&report`, before the link's own default opens the app; a mailed page
// shows it only after its step; the dismissal holds across mounts, and storage that throws still renders it.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { defineComponent, h, ref } from 'vue'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { RouterView, createRouter, createWebHistory, type Router } from 'vue-router'

vi.mock('../../api/client', async (orig) => (await import('../../test/guestMode')).guardedClients(await orig<object>()))

import OpenInAppBanner from './OpenInAppBanner.vue'
import { afterTheStep, showsPlace, type AppPlace } from './openInApp'
import { forgetOpenInAppDismissalForTest } from './openInAppDismissal'
import { authenticatedCalls } from '../../test/guestMode'
import { realI18n, t } from '../../test/i18n'

const SHARE_ID = 'Sh4reIdSh4reIdSh4reIdSh4reIdSh4reIdSh4re-_2'
const KEY = 'S2V5S2V5S2V5S2V5S2V5S2V5S2V5S2V5S2V5S2V5S2U'
const BANNER = '[data-testid="open-in-app"]'
const LINK = '[data-testid="open-in-app-link"]'

const done = ref(false)
const MailedPage = defineComponent({
  setup() {
    afterTheStep(done)
    return () => h('p', 'mailed page')
  },
})

const SCOPE = '0b9e2a52-6a3e-4f3c-9d55-0d1f7e6f2a10'
const NODE = '7c1d8e4f-2b3a-4c5d-8e9f-0a1b2c3d4e5f'
const shown = ref<AppPlace | null>({ scopeId: SCOPE, nodeId: NODE })
const PlacePage = defineComponent({
  setup() {
    showsPlace(shown)
    return () => h('p', 'a place')
  },
})

const mounted: VueWrapper[] = []

function routerAt(): Router {
  const blank = { render: () => null }
  return createRouter({
    history: createWebHistory(),
    routes: [
      { path: '/', name: 'home', component: blank },
      { path: '/s/:shareId', name: 'share', component: blank },
      { path: '/confirm-email-change', name: 'confirm-email-change', component: MailedPage },
      { path: '/moderation', name: 'moderation', component: blank },
      { path: '/place', name: 'place', component: PlacePage },
    ],
  })
}

/** The banner (and the route's page beside it) on [path], whose address the browser shows as it is. */
async function mountAt(path: string) {
  const router = routerAt()
  await router.push(path)
  await router.isReady()
  const Page = defineComponent({ render: () => h('div', [h(OpenInAppBanner), h(RouterView)]) })
  const w = mount(Page, { global: { plugins: [router, realI18n()] }, attachTo: document.body })
  mounted.push(w)
  await flushPromises()
  return w
}

const writeText = vi.fn<(text: string) => Promise<void>>()
/** The test's stand-in for the browser leaving for the app: the click's default stops at the document, after the link saw it. */
const stayOnThePage = (e: Event) => e.preventDefault()

beforeEach(() => {
  localStorage.clear()
  forgetOpenInAppDismissalForTest()
  authenticatedCalls.length = 0
  done.value = false
  writeText.mockReset().mockResolvedValue(undefined)
  Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true })
  document.addEventListener('click', stayOnThePage)
})
afterEach(() => {
  mounted.splice(0).forEach((w) => w.unmount())
  document.removeEventListener('click', stayOnThePage)
  vi.restoreAllMocks()
  expect(authenticatedCalls).toEqual([])
})

describe('OpenInAppBanner', () => {
  it('on a share page: Copy link and open the app, its href the constant and no key of the page', async () => {
    const w = await mountAt(`/s/${SHARE_ID}#k=${KEY}&report`)
    expect(window.location.href).toContain(KEY) // the page's address holds the key …
    const banner = w.find(BANNER)
    expect(banner.attributes('role')).toBe('region')
    expect(banner.attributes('aria-label')).toBe(t('openInApp.label'))
    expect(banner.text()).toContain(t('openInApp.line'))
    const link = w.find(LINK)
    expect(link.text()).toBe(t('openInApp.copyAndOpen'))
    expect(link.attributes('href')).toBe('veyq://link') // … and the link holds none of it
    expect(banner.html()).not.toContain(KEY)
    expect(banner.html()).not.toContain(SHARE_ID)
  })

  it("on a share page the click copies the page's link without &report, then the link's default opens the app", async () => {
    const w = await mountAt(`/s/${SHARE_ID}#k=${KEY}&report`)
    const order: string[] = []
    writeText.mockImplementation(async () => void order.push('copied'))
    const anchor = w.find(LINK).element as HTMLAnchorElement
    anchor.addEventListener('click', (e) => order.push(e.defaultPrevented ? 'prevented' : 'opens'))
    anchor.click()
    expect(writeText).toHaveBeenCalledWith(`${window.location.origin}/s/${SHARE_ID}#k=${KEY}`)
    expect(order).toEqual(['copied', 'opens'])
  })

  it('a clipboard that refuses still opens the app', async () => {
    writeText.mockRejectedValue(new Error('denied'))
    const w = await mountAt(`/s/${SHARE_ID}#k=${KEY}`)
    let prevented: boolean | null = null
    const anchor = w.find(LINK).element as HTMLAnchorElement
    anchor.addEventListener('click', (e) => (prevented = e.defaultPrevented))
    anchor.click()
    await flushPromises()
    expect(prevented).toBe(false)
  })

  it('in a review (#moderation) and on the moderation surface: none', async () => {
    expect((await mountAt(`/s/${SHARE_ID}#moderation`)).find(BANNER).exists()).toBe(false)
    expect((await mountAt('/moderation')).find(BANNER).exists()).toBe(false)
  })

  it('on any other page: Open in the veyq app, veyq://open, and the click copies nothing', async () => {
    const w = await mountAt('/#~me/reisen')
    const link = w.find(LINK)
    expect(link.text()).toBe(t('openInApp.open'))
    expect(link.attributes('href')).toBe('veyq://open')
    ;(link.element as HTMLAnchorElement).click()
    expect(writeText).not.toHaveBeenCalled()
  })

  it('on a page that shows a place: veyq://open with the origin and its ids, never the fragment (inbox #1415)', async () => {
    shown.value = { scopeId: SCOPE, nodeId: NODE }
    const w = await mountAt('/place#~me/reisen-2026/memories/rom')
    const href = w.find(LINK).attributes('href')
    expect(href).toBe(`veyq://open?i=${encodeURIComponent(window.location.origin)}&s=${SCOPE}&n=${NODE}`)
    expect(href).not.toMatch(/reisen|rom|~me|#/)
    shown.value = null
    await flushPromises()
    expect(w.find(LINK).attributes('href')).toBe('veyq://open')
    w.unmount()
    mounted.splice(mounted.indexOf(w), 1)
    expect((await mountAt('/')).find(LINK).attributes('href')).toBe('veyq://open') // the place left with its page
  })

  it('a mailed page: nothing before its step, veyq://open once it is done', async () => {
    const w = await mountAt('/confirm-email-change')
    expect(w.text()).toContain('mailed page')
    expect(w.find(BANNER).exists()).toBe(false)
    done.value = true
    await flushPromises()
    expect(w.find(LINK).attributes('href')).toBe('veyq://open')
  })

  it('dismissed, it stays dismissed on the next mount', async () => {
    const w = await mountAt('/')
    await w.find('button[data-testid="open-in-app-dismiss"]').trigger('click')
    expect(w.find(BANNER).exists()).toBe(false)
    expect(localStorage.getItem('veyq.openInAppDismissed')).toBe('1')
    forgetOpenInAppDismissalForTest()
    expect((await mountAt(`/s/${SHARE_ID}#k=${KEY}`)).find(BANNER).exists()).toBe(false)
  })

  it('storage that throws still renders it, and a dismissal still hides it for the visit', async () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('blocked')
    })
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('blocked')
    })
    const w = await mountAt('/')
    expect(w.find(BANNER).exists()).toBe(true)
    await w.find('button[data-testid="open-in-app-dismiss"]').trigger('click')
    expect(w.find(BANNER).exists()).toBe(false)
    expect((await mountAt('/')).find(BANNER).exists()).toBe(false)
  })
})
