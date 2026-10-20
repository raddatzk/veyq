// @vitest-environment happy-dom
// A member with a Memory open learns that the Memory, or a folder above it, was blocked (inbox #503). A node block moves
// the scope's tree revision and no item revision (`CaseDecisions.announce`), so the gallery's own poll stays a `304`: the
// host's tree poll, which runs on every opened node's page, is the read that carries it. The real host
// (`MemoryHomeView`), the real Memory page and gallery, the real tree store and crypto; the content API fakes a tree
// revision like the server's (ADR 0081), the media and blob reads are the gallery fake store's. What this pins, each with
// its control: the open Memory's page turns to *Blocked by the operator* on the poll that brings the block, every object
// URL its gallery had decrypted is revoked and its own poll ends; a lift brings the page back; and a tree change that is
// not the Memory's (a peer's new folder) reloads nothing and resets no tab — the Memory's key stays the same object
// (`sameKeyOr`), and the page's watchers compare the node's id and key one by one, not a fresh array per run.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'

const SCOPE_ID = '11111111-1111-1111-1111-111111111111'

vi.mock('../app/activeSpace', async () => {
  const { reactive } = await import('vue')
  return {
    activeSpace: reactive({
      status: 'ready', scopeId: '11111111-1111-1111-1111-111111111111', tenantId: 't-1', kind: 'PERSONAL', name: null, epoch: 0,
      deletedAt: null, purgeAfter: null, trustRefusal: null,
    }),
    enterScope: vi.fn(async () => {}),
    activeEpochKeys: () => ({ current: 0, keys: new Map([[0, new Uint8Array(32).fill(7)]]) }),
  }
})
vi.mock('../api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../api/client')>()),
  contentApi: vi.fn(),
  blobApi: vi.fn(),
}))
vi.mock('../features/memories/api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../features/memories/api')>()),
  mediaApi: vi.fn(),
}))
vi.mock('../core/favorites/favoritesStore', () => ({
  favoritesStore: { loaded: true, ids: new Set<string>() },
  loadFavoritesFor: vi.fn(),
  isFavorite: vi.fn(),
  toggleFavorite: vi.fn(async () => {}),
}))
vi.mock('../features/spaces/scopes', () => ({ listMyTenants: vi.fn() }))
vi.mock('exifr', () => ({ default: { parse: vi.fn(async () => undefined) } }))

import { mount, flushPromises } from '@vue/test-utils'
import { nextTick } from 'vue'
import { createRouter, createMemoryHistory } from 'vue-router'
import PrimeVue from 'primevue/config'
import * as v1 from '@veyq/crypto/v1'
import MemoryHomeView from './MemoryHomeView.vue'
import { loadTree, resetTree, treeStore } from '../core/node/treeStore'
import { LIVE_POLL } from '../core/node/useLevelLive'
import { ITEMS_POLL } from '../core/items/itemsLive'
import { sealName, wrapContentKey, type NodeAddress } from '../core/node/tree'
import { hashForNode } from '../core/node/location'
import { contentApi, blobApi } from '../api/client'
import { mediaApi } from '../features/memories/api'
import { listMyTenants } from '../features/spaces/scopes'
import { addMedia, type MediaDeriver } from '../features/memories/media'
import { MEMORY_KIND } from '../features/memories/kinds'
import { galleryFakeStore } from '../features/memories/components/galleryFakeStore'
import { fakeApi } from '../test/apiMock'
import { apiResponse } from '../test/apiResponse'
import { httpError } from '../test/httpError'
import { realI18n, t } from '../test/i18n'
import type { ContentApi, NodeSummary } from '../api/generated/content'

const SCOPE_KEY = new Uint8Array(32).fill(7)
const TRIPS = '5c09e000-0000-4000-8000-0000000000f3'
const ROME = '5c09e000-0000-4000-8000-0000000000b3'
const OTHER = '5c09e000-0000-4000-8000-0000000000f4'
const tripsKey = v1.randomBytes(32)
const romeKey = v1.randomBytes(32)
const at = (nodeId: string): NodeAddress => ({ scopeId: SCOPE_ID, nodeId, epoch: 0, placementRevision: 0 })
const base = { orderIndex: 0, epoch: 0, metaRevision: 0, placementRevision: 0, createdAt: new Date(0), slugToken: 'c2x1Zw==' }
const folder = (id: string, name: string, key: Uint8Array, blockedByOperator = false): NodeSummary => ({
  ...base, id, kind: 'FOLDER', blockedByOperator, encryptedName: sealName(SCOPE_KEY, at(id), name), wrappedContentKey: wrapContentKey(SCOPE_KEY, at(id), key),
})
// Sealed once: the same row twice must fingerprint equal, as the server serves it.
const tripsRow = folder(TRIPS, 'Trips', tripsKey)
const tripsBlocked = { ...tripsRow, blockedByOperator: true }
const otherRow = folder(OTHER, 'Other', v1.randomBytes(32))
const romeRow: NodeSummary = {
  ...base, id: ROME, parentId: TRIPS, kind: MEMORY_KIND, blockedByOperator: false,
  encryptedName: sealName(tripsKey, at(ROME), 'Rome'), wrappedContentKey: wrapContentKey(tripsKey, at(ROME), romeKey),
}
/** The server marks every node below a blocked one (`NodeSummary.blockedByOperator`): the folder's block marks the Memory. */
const romeBlocked = { ...romeRow, blockedByOperator: true }

let served: NodeSummary[]
let revision: number
let listNodes: ReturnType<typeof vi.fn<ContentApi['listNodesRaw']>>
const change = (rows: NodeSummary[]) => {
  served = rows
  revision++
}
const settle = async (ms: number) => {
  await vi.advanceTimersByTimeAsync(ms)
  await nextTick()
}
const OVER_ONE_INTERVAL = Math.ceil(LIVE_POLL.intervalMs * (1 + LIVE_POLL.jitter)) + 500

function stubDeriver(): MediaDeriver {
  return {
    deriveImage: vi.fn(async (_b: Blob, { maxEdge }: { maxEdge: number; quality: number }) => ({ bytes: new Uint8Array([0xff, 0xd8, maxEdge & 0xff, 1]), source: null })),
    deriveVideoPoster: vi.fn(async () => new Uint8Array([0xff, 0xd8, 0x07, 0x09])),
  }
}

let created: string[]
let revoked: string[]
let store: ReturnType<typeof galleryFakeStore>

beforeEach(async () => {
  vi.spyOn(Math, 'random').mockReturnValue(0.5)
  Object.defineProperty(document, 'visibilityState', { value: 'visible', configurable: true })
  vi.spyOn(Element.prototype, 'getBoundingClientRect').mockReturnValue({ width: 960, height: 600, top: 0, left: 0, right: 960, bottom: 600, x: 0, y: 0, toJSON: () => ({}) })
  vi.stubGlobal('ResizeObserver', class { observe() {} unobserve() {} disconnect() {} })
  created = []
  revoked = []
  let n = 0
  vi.spyOn(URL, 'createObjectURL').mockImplementation(() => {
    const url = `blob:test/${n++}`
    created.push(url)
    return url
  })
  vi.spyOn(URL, 'revokeObjectURL').mockImplementation((url: string) => void revoked.push(url))
  vi.mocked(listMyTenants).mockResolvedValue([])
  store = galleryFakeStore()
  fakeApi(mediaApi, store.api)
  fakeApi(blobApi, store.blob)
  vi.stubGlobal('fetch', store.fetchStub)
  await addMedia(SCOPE_ID, ROME, romeKey, { blob: new Blob([new Uint8Array(8).fill(1) as BlobPart]), mediaType: 'image/png' }, stubDeriver())
  await addMedia(SCOPE_ID, ROME, romeKey, { blob: new Blob([new Uint8Array(8).fill(2) as BlobPart]), mediaType: 'image/png' }, stubDeriver())

  resetTree()
  served = [tripsRow, romeRow]
  revision = 1
  listNodes = vi.fn<ContentApi['listNodesRaw']>(async ({ ifNoneMatch }) => {
    if (ifNoneMatch === `"${revision}"`) throw httpError(304)
    return apiResponse(served, { ETag: `"${revision}"` })
  })
  fakeApi(contentApi, { listNodesRaw: listNodes, updateNode: vi.fn(async () => {}) })
  await loadTree()
  vi.useFakeTimers()
}, 30_000) // real crypto and two uploads ahead of the fake clock: generous under a loaded machine
afterEach(() => {
  vi.useRealTimers()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

async function openRome() {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', name: 'home', component: { template: '<div />' } }] })
  await router.replace({ name: 'home', hash: hashForNode(treeStore.nodes, ROME, '~me') })
  await router.isReady()
  const w = mount(MemoryHomeView, { global: { plugins: [router, realI18n(), PrimeVue], directives: { tooltip: {} } } })
  for (let i = 0; i < 5; i++) {
    await settle(100)
    await flushPromises()
  }
  return w
}

describe('a member with a Memory open, live', () => {
  it('turns the page to the placeholder when a folder above it is blocked, revokes every decrypted URL, and a lift brings it back', async () => {
    const w = await openRome()
    expect(w.find('.memory-detail').exists(), "the Memory's page is on screen").toBe(true)
    expect(created.length, 'control: the gallery decrypted its thumbnails into object URLs').toBeGreaterThan(0)
    expect(revoked).toEqual([])
    listNodes.mockClear()

    change([tripsBlocked, romeBlocked])
    await settle(OVER_ONE_INTERVAL)
    await flushPromises()
    expect(listNodes).toHaveBeenCalled()
    expect(w.find('.node-blocked').text()).toBe(t('node.blockedByOperator'))
    expect(w.find('.memory-detail').exists(), "the Memory's page is gone").toBe(false)
    expect(created.filter((url) => !revoked.includes(url)), 'every object URL the page held is revoked').toEqual([])

    const listed = store.api.listNodeMedia.mock.calls.length
    await settle(ITEMS_POLL.intervalMs * 3)
    expect(store.api.listNodeMedia.mock.calls.length, "the gallery's own poll ended with its page").toBe(listed)

    change([tripsRow, romeRow])
    await settle(OVER_ONE_INTERVAL)
    await flushPromises()
    expect(w.find('.node-blocked').exists(), 'the lift reaches the page: it kept polling under the placeholder').toBe(false)
    expect(w.find('.memory-detail').exists()).toBe(true)
    w.unmount()
  })

  it("reloads nothing when the tree moves elsewhere: the Memory's key stays the same object", async () => {
    const w = await openRome()
    const listed = store.api.listNodeMedia.mock.calls.length
    expect(listed, 'control: the gallery listed once on open').toBeGreaterThan(0)
    const tabs = () => w.findAll('.memory-tabs [role="tab"]')
    await tabs()[1].trigger('click') // the member moves off the gallery tab: a reset would take them back
    await flushPromises()
    expect(tabs()[1].attributes('aria-selected')).toBe('true')
    change([tripsRow, romeRow, otherRow]) // a peer's new folder: the tree is re-read and re-opened
    await settle(OVER_ONE_INTERVAL)
    await flushPromises()
    expect(treeStore.nodes.map((n) => n.id)).toContain(OTHER)
    expect(store.api.listNodeMedia.mock.calls.length, 'no gallery reload').toBe(listed)
    expect(revoked, 'no thumbnail revoked').toEqual([])
    expect(tabs()[1].attributes('aria-selected'), 'the tab the member chose stays').toBe('true')
    w.unmount()
  })
})
