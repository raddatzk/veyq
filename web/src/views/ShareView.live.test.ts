// @vitest-environment happy-dom
// A share guest's folder page picks up what the owner or a peer changed WITHOUT a reload (inbox #408), and stores its
// own view preferences. The real shell (`ShareView`), the real page (`ShareTreePage`), the real level view and the real
// guest tree over sealed rows, with the resolve faked and Depot's guest blob listing (`listShareFiles`) a counting stub - its
// call count is how the rows' "look again" cue (the level's live tick) is read.
// The poll is conditional (ADR 0081): the faked resolve keeps a tag like the server's digest, answers `null` (a 304) to an
// echo of it, and the page does nothing with that. Each case has its control: a changed share appears after one poll
// interval; an idle one is a 304 and tells the rows nothing; nothing is requested while hidden; a failed resolve
// leaves the page standing; a link that is GONE (revoked, expired, deleted, its root trashed) flips the page to its
// unavailable state and ends the loop; a changed password flips it to the gate; an unmount stops the loop.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { LIVE_POLL } from '../core/node/useLevelLive'
import { mount, flushPromises } from '@vue/test-utils'
import { nextTick } from 'vue'
import { createRouter, createMemoryHistory } from 'vue-router'
import PrimeVue from 'primevue/config'
import * as v1 from '@veyq/crypto/v1'
import { b64std, b64url } from '@veyq/crypto/base64'

vi.mock('../api/client', async (orig) => (await import('../test/guestMode')).guardedClients(await orig<object>()))
vi.mock('../features/memory/api', async (orig) => (await import('../test/guestMode')).guardedClients(await orig<object>()))
vi.mock('../features/depot/api', async (orig) => (await import('../test/guestMode')).guardedClients(await orig<object>()))
vi.mock('../core/sharing/resolvePublicShare', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../core/sharing/resolvePublicShare')>()
  return { ...actual, resolvePublicShare: vi.fn(), pollPublicShare: vi.fn(), fetchShareTerms: vi.fn() }
})
import ShareView from './ShareView.vue'
import {
  resolvePublicShare,
  pollPublicShare,
  fetchShareTerms,
  ShareUnavailableError,
  SharePasswordIncorrectError,
  type PublicShareHead,
} from '../core/sharing/resolvePublicShare'
import { sealName, wrapContentKey } from '../core/node/tree'
import { ShareRole, type PublicShare, type ShareNode } from '../api/generated/sharing'
import { FOLDER_KIND } from '../core/node/kind'
import { FILE_KIND } from '../features/depot/kinds'
import { authenticatedCalls } from '../test/guestMode'
import { publicShareFilesApi } from '../features/depot/api'
import type { ShareFilesApi } from '../api/generated/depot'
import { fakeApi } from '../test/apiMock'
import { titlesOf } from '../test/levelRows'
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
  const address = { scopeId: SCOPE, nodeId: id, epoch: 0, placementRevision: 0 }
  return { id, parentId: ROOT, kind: FILE_KIND, epoch: 0, placementRevision: 0, encryptedName: sealName(ROOT_KEY, address, name), wrappedContentKey: wrapContentKey(ROOT_KEY, address, v1.randomBytes(32)) }
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
let listShareFiles: ReturnType<typeof vi.fn<ShareFilesApi['listShareFiles']>>
let servedTag: string // the digest the faked server holds for `served`; every change of it moves the tag
const serve = (next: PublicShareHead) => {
  served = next
  servedTag = `"${tagCounter++}"`
}
let tagCounter = 1
const setVisibility = (state: 'visible' | 'hidden') => {
  Object.defineProperty(document, 'visibilityState', { value: state, configurable: true })
  document.dispatchEvent(new Event('visibilitychange'))
}
const settle = async (ms: number) => {
  await vi.advanceTimersByTimeAsync(ms)
  await nextTick()
}
// One poll interval and the jitter's widest draw, so the tests follow the cadence instead of a copied number.
const OVER_ONE_INTERVAL = Math.ceil(LIVE_POLL.intervalMs * (1 + LIVE_POLL.jitter)) + 500

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
  serve(head([]))
  listShareFiles = vi.fn<ShareFilesApi['listShareFiles']>(async () => ({ items: [] }))
  fakeApi(publicShareFilesApi, { listShareFiles })
  vi.mocked(resolvePublicShare).mockReset().mockImplementation(async () => served)
  vi.mocked(pollPublicShare).mockReset().mockImplementation(async (_id, _key, _pw, ifNoneMatch) =>
    ifNoneMatch === servedTag ? null : { head: served, etag: servedTag },
  )
  vi.mocked(fetchShareTerms).mockReset().mockResolvedValue({ tenantId: 't-1', termsRevision: 0, terms: {} })
})
afterEach(() => {
  vi.useRealTimers()
  vi.restoreAllMocks()
})

describe("a share guest's folder page, live", () => {
  it("shows a peer's new file after one poll interval, with no reload", async () => {
    const w = await mountPage()
    expect(titlesOf(w)).toEqual([])
    vi.mocked(pollPublicShare).mockClear()
    serve(head([fileOf('6e5a0000-0000-4000-8000-0000000009a1', 'foto.png')]))
    await settle(OVER_ONE_INTERVAL)
    expect(pollPublicShare).toHaveBeenCalled()
    expect(titlesOf(w)).toEqual(['foto.png'])
    expect(authenticatedCalls).toEqual([])
    w.unmount()
  })

  it('requests nothing while the tab is hidden, and catches up when it shows', async () => {
    const w = await mountPage()
    vi.mocked(pollPublicShare).mockClear()
    setVisibility('hidden')
    serve(head([fileOf('6e5a0000-0000-4000-8000-0000000009a1', 'foto.png')]))
    await settle(120_000)
    expect(pollPublicShare).not.toHaveBeenCalled()
    setVisibility('visible')
    await settle(0)
    expect(pollPublicShare).toHaveBeenCalledTimes(1)
    expect(titlesOf(w)).toEqual(['foto.png'])
    w.unmount()
  })

  it('a failed resolve leaves the page standing (no unavailable state), and the loop backs off', async () => {
    const w = await mountPage()
    vi.mocked(pollPublicShare).mockReset().mockRejectedValue(new Error('down'))
    await settle(OVER_ONE_INTERVAL)
    expect(vi.mocked(pollPublicShare)).toHaveBeenCalledTimes(1)
    expect(w.find('.share-view-unavailable').exists()).toBe(false)
    expect(w.find('.veyq-level-view').exists(), 'the level stands').toBe(true)
    await settle(OVER_ONE_INTERVAL)
    expect(vi.mocked(pollPublicShare)).toHaveBeenCalledTimes(1)
    w.unmount()
  })

  it('echoes the last response\'s tag; an idle link is a 304 that swaps nothing and tells the rows nothing', async () => {
    serve(head([fileOf('6e5a0000-0000-4000-8000-0000000009a1', 'foto.png')])) // a level with a file: only then do the rows re-read
    const w = await mountPage()
    expect(titlesOf(w)).toEqual(['foto.png'])
    vi.mocked(pollPublicShare).mockClear()
    await settle(OVER_ONE_INTERVAL) // the load's response carried no tag the page holds: this first poll is a full read
    await settle(OVER_ONE_INTERVAL * 2)
    const calls = vi.mocked(pollPublicShare).mock.calls
    expect(calls.length).toBeGreaterThanOrEqual(3)
    expect(calls[0][3]).toBeNull()
    for (const call of calls.slice(1)) expect(call[3]).toBe(servedTag) // every later poll echoes it
    // only the first, full read told the rows (one more read of the blob listing, after the mount's own); the 304s said nothing
    expect(listShareFiles).toHaveBeenCalledTimes(2)
    w.unmount()
  })

  it('a link revoked, expired or deleted flips the page to its unavailable state, and the loop ends', async () => {
    const w = await mountPage()
    expect(w.find('.share-view-unavailable').exists()).toBe(false) // control: standing before
    vi.mocked(pollPublicShare).mockReset().mockRejectedValue(new ShareUnavailableError('not_found'))
    await settle(OVER_ONE_INTERVAL)
    expect(w.find('.share-view-unavailable').exists()).toBe(true)
    expect(w.find('.veyq-level-view').exists()).toBe(false)
    await settle(OVER_ONE_INTERVAL * 3)
    expect(vi.mocked(pollPublicShare)).toHaveBeenCalledTimes(1) // nothing is asked of a link that is gone
    w.unmount()
  })

  it('a link whose root was trashed (it serves no kind any more) is unavailable too', async () => {
    const w = await mountPage()
    serve({ ...head([]), rootKind: null })
    await settle(OVER_ONE_INTERVAL)
    expect(w.find('.share-view-unavailable').exists()).toBe(true)
    w.unmount()
  })

  it('a link that became password-protected under a guest shows the gate, not the page', async () => {
    const w = await mountPage()
    vi.mocked(pollPublicShare).mockReset().mockRejectedValue(new SharePasswordIncorrectError())
    await settle(OVER_ONE_INTERVAL)
    expect(w.find('#share-password-input').exists()).toBe(true)
    expect(w.find('.share-view-unavailable').exists()).toBe(false)
    w.unmount()
  })

  it('stops with the page', async () => {
    const w = await mountPage()
    vi.mocked(pollPublicShare).mockClear()
    w.unmount()
    await settle(OVER_ONE_INTERVAL * 3)
    expect(pollPublicShare).not.toHaveBeenCalled()
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
