// @vitest-environment happy-dom
// A share guest's folder page picks up what the owner or a peer changed WITHOUT a reload (inbox #408), and stores its
// own view preferences. The real shell (`ShareView`), the real page (`ShareTreePage`), the real level view and the real
// guest tree over sealed rows, with the resolve faked and the heavy sections stubbed to print the nodes they are handed.
// Each case has its control: a changed share appears after one poll interval; nothing is requested while hidden; a failed
// resolve leaves the page standing; an unmount stops the loop.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { nextTick } from 'vue'
import { createRouter, createMemoryHistory } from 'vue-router'
import PrimeVue from 'primevue/config'
import * as v1 from '@veyq/crypto/v1'
import { b64std, b64url } from '@veyq/crypto/base64'

vi.mock('../api/client', async (orig) => (await import('../test/guestMode')).guardedClients(await orig<object>()))
vi.mock('../features/memory/api', async (orig) => (await import('../test/guestMode')).guardedClients(await orig<object>()))
vi.mock('../features/files/api', async (orig) => (await import('../test/guestMode')).guardedClients(await orig<object>()))
vi.mock('../core/sharing/resolvePublicShare', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../core/sharing/resolvePublicShare')>()
  return { ...actual, resolvePublicShare: vi.fn(), fetchShareTerms: vi.fn() }
})
vi.mock('../features/memory/components/MemoriesSection.vue', async () => {
  const { defineComponent, h } = await import('vue')
  return { default: defineComponent({ props: ['nodes', 'folder', 'epochs', 'access', 'tree', 'viewMode', 'liveTick'], setup: (p) => () => h('section', { class: 'memories-stub' }, p.nodes.map((n: { name: string }) => n.name).join(',')) }) }
})
vi.mock('../features/files/components/FilesSection.vue', async () => {
  const { defineComponent, h } = await import('vue')
  return {
    default: defineComponent({
      props: ['nodes', 'folder', 'epochs', 'access', 'tree', 'viewMode', 'liveTick'],
      setup: (p) => () => h('section', { class: 'files-stub', 'data-tick': String(p.liveTick) }, p.nodes.map((n: { name: string }) => n.name).join(',')),
    }),
  }
})

import ShareView from './ShareView.vue'
import { resolvePublicShare, fetchShareTerms, type PublicShareHead } from '../core/sharing/resolvePublicShare'
import { sealName, wrapContentKey } from '../core/node/tree'
import { ShareRole, type PublicShare, type ShareNode } from '../api/generated/sharing'
import { FOLDER_KIND } from '../core/node/kind'
import { FILE_KIND } from '../features/files/kinds'
import { authenticatedCalls } from '../test/guestMode'
import { realI18n } from '../test/i18n'
import { viewModeStorageKey, setViewMode } from '../shared/state/useViewMode'
import { levelTabStorageKey } from '../shared/state/useLevelTab'
import { setAccountUserId } from '../app/account'

const SCOPE = '77777777-7777-4777-8777-777777777777'
const SHARE = 'Sh4reIdSh4reIdSh4reIdSh4reIdSh4reIdSh4re-_s'
const ROOT = '6e5a0000-0000-4000-8000-000000000901'
const K_SHARE = v1.randomBytes(32)
const ROOT_KEY = v1.randomBytes(32)
const HASH = `#k=${b64url(K_SHARE)}`

function file(id: string, name: string): ShareNode {
  const address = { scopeId: SCOPE, nodeId: id, epoch: 0, placementVersion: 0 }
  return { id, parentId: ROOT, kind: FILE_KIND, epoch: 0, placementVersion: 0, encryptedName: sealName(ROOT_KEY, address, name), wrappedContentKey: wrapContentKey(ROOT_KEY, address, v1.randomBytes(32)) }
}
// Stable ciphertext per file, as the server serves it: the same rows twice must fingerprint equal.
const fileCache = new Map<string, ShareNode>()
const fileOf = (id: string, name: string) => fileCache.get(id) ?? (fileCache.set(id, file(id, name)), fileCache.get(id)!)
/** A READ_WRITE link rooted at the folder "Fotos" holding `files`. */
function head(files: ShareNode[]): PublicShareHead {
  const nodes = [{ id: ROOT, parentId: null, kind: FOLDER_KIND, epoch: 0 }, ...files]
  const share = { role: ShareRole.ReadWrite, scopeId: SCOPE, rootKind: FOLDER_KIND, wrap: b64std(v1.seal({ purpose: 'share-key' }, K_SHARE, ROOT_KEY)), nodes } as PublicShare
  return { shareId: SHARE, kShare: K_SHARE, scopeId: SCOPE, name: 'Fotos', noteByLocale: {}, role: ShareRole.ReadWrite, rootKind: FOLDER_KIND, share }
}

let served: PublicShareHead
const setVisibility = (state: 'visible' | 'hidden') => {
  Object.defineProperty(document, 'visibilityState', { value: state, configurable: true })
  document.dispatchEvent(new Event('visibilitychange'))
}
const settle = async (ms: number) => {
  await vi.advanceTimersByTimeAsync(ms)
  await nextTick()
}
const OVER_ONE_INTERVAL = 19_000

async function mountPage() {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/s/:shareId', component: ShareView }] })
  await router.push(`/s/${SHARE}${HASH}`)
  await router.isReady()
  const w = mount(ShareView, { global: { plugins: [router, realI18n(), PrimeVue], directives: { tooltip: {} } }, attachTo: document.body })
  await settle(0)
  await flushPromises()
  return w
}

beforeEach(() => {
  vi.useFakeTimers()
  // The loop's delay is jittered (±20 %): with a random draw the 19 s settles below can land either side of a backoff, so
  // the failure test flaked about once in a few dozen runs. A fixed draw (the middle of the spread) makes the delays exact.
  vi.spyOn(Math, 'random').mockReturnValue(0.5)
  setVisibility('visible')
  localStorage.clear()
  authenticatedCalls.length = 0
  served = head([])
  vi.mocked(resolvePublicShare).mockReset().mockImplementation(async () => served)
  vi.mocked(fetchShareTerms).mockReset().mockResolvedValue({ tenantId: 't-1', termsVersion: 0, terms: {} })
})
afterEach(() => {
  vi.useRealTimers()
  vi.restoreAllMocks()
})

describe("a share guest's folder page, live", () => {
  it("shows a peer's new file after one poll interval, with no reload", async () => {
    const w = await mountPage()
    expect(w.find('.files-stub').text()).toBe('')
    vi.mocked(resolvePublicShare).mockClear()
    served = head([fileOf('6e5a0000-0000-4000-8000-0000000009a1', 'foto.png')])
    await settle(OVER_ONE_INTERVAL)
    expect(resolvePublicShare).toHaveBeenCalled()
    expect(w.find('.files-stub').text()).toBe('foto.png')
    expect(authenticatedCalls).toEqual([])
    w.unmount()
  })

  it('requests nothing while the tab is hidden, and catches up when it shows', async () => {
    const w = await mountPage()
    vi.mocked(resolvePublicShare).mockClear()
    setVisibility('hidden')
    served = head([fileOf('6e5a0000-0000-4000-8000-0000000009a1', 'foto.png')])
    await settle(120_000)
    expect(resolvePublicShare).not.toHaveBeenCalled()
    setVisibility('visible')
    await settle(0)
    expect(resolvePublicShare).toHaveBeenCalledTimes(1)
    expect(w.find('.files-stub').text()).toBe('foto.png')
    w.unmount()
  })

  it('a failed resolve leaves the page standing (no unavailable state), and the loop backs off', async () => {
    const w = await mountPage()
    vi.mocked(resolvePublicShare).mockReset().mockRejectedValue(new Error('down'))
    await settle(OVER_ONE_INTERVAL)
    expect(vi.mocked(resolvePublicShare)).toHaveBeenCalledTimes(1)
    expect(w.find('.share-view-unavailable').exists()).toBe(false)
    expect(w.find('.files-stub').exists()).toBe(true)
    await settle(OVER_ONE_INTERVAL)
    expect(vi.mocked(resolvePublicShare)).toHaveBeenCalledTimes(1)
    w.unmount()
  })

  it('stops with the page', async () => {
    const w = await mountPage()
    vi.mocked(resolvePublicShare).mockClear()
    w.unmount()
    await settle(OVER_ONE_INTERVAL * 3)
    expect(resolvePublicShare).not.toHaveBeenCalled()
  })

  it("keeps the guest's view preferences apart from the account's, and gives them back on leaving", async () => {
    setAccountUserId('user-1')
    setViewMode('list')
    const w = await mountPage()
    expect(viewModeStorageKey()).toBe('veyq:view-mode:guest')
    expect(levelTabStorageKey()).toBe('veyq:level-tab:guest')
    setViewMode('grid')
    expect(localStorage.getItem('veyq:view-mode:guest')).toBe('grid')
    expect(localStorage.getItem('veyq:view-mode:u:user-1')).toBe('list')
    w.unmount()
    expect(viewModeStorageKey()).toBe('veyq:view-mode:u:user-1')
  })
})
