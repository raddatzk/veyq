// @vitest-environment happy-dom
import { describe, it, expect, vi, beforeEach, afterEach, type Mock } from 'vitest'

vi.mock('../../../api/client', () => ({
  blobApi: vi.fn(),
  contentApi: vi.fn(),
  loginApi: vi.fn(),
  tokenApi: vi.fn(),
  sessionApi: vi.fn(),
}))
vi.mock('../api', () => ({ mediaApi: vi.fn() }))

// exifr drives the sealed capture-time tier; default = no date, tests override per media (cr-0066).
vi.mock('exifr', () => ({ default: { parse: vi.fn(async () => undefined) } }))
// the reverse-geocode seam is mocked — the self-heal that materializes a pending auto name is under test.
// Keep the real defaultLevel/nameAtLevel helpers (the self-heal picks a level from the mocked hierarchy).
vi.mock('../geocode', async (orig) => ({ ...(await orig<typeof import('../geocode')>()), reverseGeocode: vi.fn() }))

import { mount, flushPromises } from '@vue/test-utils'
import exifr from 'exifr'
import PrimeVue from 'primevue/config'
import MemoryDetail from './MemoryDetail.vue'
import { memberMemorySource } from '../source'
import { addMedia, MEDIA_PAGE_LIMIT, type MediaDeriver } from '../media'
import { GETS_IN_FLIGHT, PRESIGN_BATCH_MAX } from '../../../core/blob/readScheduler'
import { sealMemoryDate, unsealMemoryDate, type MemoryDate } from '../memoryDate'
import { unsealMemoryLocation, type MemoryLocation } from '../memoryLocation'
import { sealNodeMeta } from '../../../core/node/nodeMeta'
import { setMapsConsent } from '../../../shared/state/useMapConfig'
import { setGeocoderUrl, setTileUrl } from '../../../test/services'
import MemoryMap from './MemoryMap.vue'
import { reverseGeocode } from '../geocode'
import { epochMsToIsoDate } from '../../../shared/isoDate'
import { blobApi, contentApi } from '../../../api/client'
import { mediaApi } from '../api'
import * as v1 from '@veyq/crypto/v1'
import type { ContentApi } from '../../../api/generated/content'
import { MEMORY_KIND, CHAPTER_KIND } from '../kinds'
import { fakeApi } from '../../../test/apiMock'
import type { TreeNode } from '../../../core/node/tree'
import { galleryFakeStore } from './galleryFakeStore'
import { realI18n, t } from '../../../test/i18n'
import { treeNode, memberMemoryRights } from '../../../test/models'

const SCOPE_ID = '11111111-1111-1111-1111-111111111111'
const NODE_ID = '22222222-2222-2222-2222-222222222222'
/** The node every meta fixture here belongs to — node-meta seals are bound to it (ADR 0072). */
const THIS_NODE = { scopeId: SCOPE_ID, nodeId: NODE_ID }

const node = (name: string, wrappedMeta: string | null = null): TreeNode => treeNode({
  id: NODE_ID, kind: MEMORY_KIND, name, parentId: null, orderIndex: 0, epoch: 0, wrappedContentKey: 'eA==', wrappedMeta, metaRevision: 0, placementRevision: 0,
})

/** A stub deriver: a tiny "JPEG" tagged by the requested maxEdge so each tier's bytes are distinct. */
function stubDeriver(): MediaDeriver {
  return {
    deriveImage: vi.fn(async (_b: Blob, { maxEdge }: { maxEdge: number; quality: number }) => ({
      bytes: new Uint8Array([0xff, 0xd8, maxEdge & 0xff, (maxEdge >> 8) & 0xff]),
      source: null,
    })),
    deriveVideoPoster: vi.fn(async () => new Uint8Array([0xff, 0xd8, 0x07, 0x09])),
  }
}

function mountDetail(memoryKey: Uint8Array, props: Partial<Record<string, unknown>> = {}) {
  return mount(MemoryDetail, {
    props: { source: memberMemorySource({ scopeId: () => SCOPE_ID }), node: node('Rom 2026'), scopeId: SCOPE_ID, memoryKey, rights: memberMemoryRights({ publicShares: false }), ...props },
    global: { plugins: [realI18n(), PrimeVue], directives: { tooltip: {} } },
  })
}

// happy-dom does no layout, so the justified grid (cr-0059) can't measure its container; stub a width + a
// no-op ResizeObserver so the layout produces tiles (the layout math itself is covered in mediaGridLayout.test).
let updateNode: Mock<ContentApi['updateNode']>
beforeEach(() => {
  vi.clearAllMocks()
  // default: a no-op content client so the open-time auto-reconcile (cr-0067) never throws; date tests assert on it
  updateNode = vi.fn<ContentApi['updateNode']>(async () => {})
  fakeApi(contentApi, { updateNode })
  vi.spyOn(Element.prototype, 'getBoundingClientRect').mockReturnValue({ width: 960, height: 0, top: 0, left: 0, right: 960, bottom: 0, x: 0, y: 0, toJSON: () => ({}) })
  vi.stubGlobal('ResizeObserver', class { observe() {} unobserve() {} disconnect() {} })
})

/** The MemoryDate sealed into the most recent updateNode PATCH (cr-0067), unsealed under `key` for assertions. */
function lastPatchedDate(key: Uint8Array): MemoryDate | null {
  const calls = updateNode.mock.calls
  if (!calls.length) return null
  const wrappedMeta = calls[calls.length - 1][0].updateNodeRequest.wrappedMeta as string
  return unsealMemoryDate(SCOPE_ID, key, { id: NODE_ID, wrappedMeta })
}
/** The MemoryLocation sealed into the most recent updateNode PATCH (cr-0069), unsealed under `key`. */
function lastPatchedLocation(key: Uint8Array): MemoryLocation | null {
  const calls = updateNode.mock.calls
  if (!calls.length) return null
  const wrappedMeta = calls[calls.length - 1][0].updateNodeRequest.wrappedMeta as string
  return unsealMemoryLocation(SCOPE_ID, key, { id: NODE_ID, wrappedMeta })
}
afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe('MemoryDetail (gallery grid + header)', () => {
  /**
   * **The bite (uc.memory.gallery E4).** In a personal scope there is nobody to share a mark with, so the
   * "everyone's / only mine" filter is **absent**, not disabled. Rendering it `:disabled` is the one-line
   * change the obvious implementation reaches for, and it reddens here — a greyed-out control asks "how do I
   * turn this on?", a question a personal scope has no answer to.
   *
   * The filter's presence is also half of how a user learns that marks are shared at all (ADR 0068), which is
   * why its gate is the scope KIND and not "has anyone marked yet": a group nobody has marked in yet still
   * shows it, because the next mark will be seen.
   */
  it('hides the mark filter in a personal scope — absent, never disabled', async () => {
    const store = galleryFakeStore()
    fakeApi(mediaApi, store.api)
    fakeApi(blobApi, store.blob)
    vi.stubGlobal('fetch', store.fetchStub)
    const memoryKey = v1.randomBytes(32)
    await addMedia(SCOPE_ID, NODE_ID, memoryKey, { blob: new Blob([new Uint8Array(8).fill(1) as BlobPart]), mediaType: 'image/png' }, stubDeriver())

    const personal = mountDetail(memoryKey, { scopeKey: v1.randomBytes(32), personalScope: true })
    await flushPromises()
    await flushPromises()
    expect(personal.find('.gallery-mark-filter').exists()).toBe(false)
    expect(personal.findAll('.gallery-mark-filter-option')).toHaveLength(0)
    // and specifically NOT the disabled spelling
    expect(personal.findAll('[disabled]').length).toBe(0)

    const group = mountDetail(memoryKey, { scopeKey: v1.randomBytes(32), personalScope: false })
    await flushPromises()
    await flushPromises()
    expect(group.find('.gallery-mark-filter').exists()).toBe(true)
    expect(group.findAll('.gallery-mark-filter-option').map((o) => o.text())).toEqual([t('media.marks.filter.all'), t('media.marks.filter.everyone'), t('media.marks.filter.mine')])
  })

  /**
   * The mark toggle never renders an identity, because it is never given one: the store holds only "is this
   * mine" and a count. This asserts the rendered result carries no uuid-shaped attribute — the outside-in
   * version of the store's own "holds no member identity" case.
   */
  it('renders the mark toggle without ever naming a member', async () => {
    const store = galleryFakeStore()
    fakeApi(mediaApi, store.api)
    fakeApi(blobApi, store.blob)
    vi.stubGlobal('fetch', store.fetchStub)
    const memoryKey = v1.randomBytes(32)
    await addMedia(SCOPE_ID, NODE_ID, memoryKey, { blob: new Blob([new Uint8Array(8).fill(1) as BlobPart]), mediaType: 'image/png' }, stubDeriver())

    const wrapper = mountDetail(memoryKey, { scopeKey: v1.randomBytes(32), personalScope: false })
    await flushPromises()
    await flushPromises()

    const mark = wrapper.find('.media-tile-mark')
    expect(mark.exists()).toBe(true)
    expect(mark.attributes('aria-label')).toBe(t('media.marks.mark'))
    expect(mark.attributes('aria-pressed')).toBe('false')
    // no member id anywhere in the rendered gallery
    expect(wrapper.html()).not.toMatch(/member-?id/i)
  })

  it('lists the Memory media → renders the decrypted title, item count and a thumbnail tile per item', async () => {
    const store = galleryFakeStore()
    fakeApi(mediaApi, store.api)
    fakeApi(blobApi, store.blob)
    vi.stubGlobal('fetch', store.fetchStub)
    const memoryKey = v1.randomBytes(32)
    await addMedia(SCOPE_ID, NODE_ID, memoryKey, { blob: new Blob([new Uint8Array(8).fill(1) as BlobPart]), mediaType: 'image/png' }, stubDeriver())
    await addMedia(SCOPE_ID, NODE_ID, memoryKey, { blob: new Blob([new Uint8Array(8).fill(2) as BlobPart]), mediaType: 'image/jpeg' }, stubDeriver())

    const wrapper = mountDetail(memoryKey)
    await flushPromises()
    await flushPromises()

    expect(wrapper.find('.veyq-node-header-title').text()).toBe('Rom 2026')
    expect(wrapper.find('.veyq-node-header-subtitle').text()).toBe('2 items')
    // two derivable items → two <img> thumbnail tiles, each backed by a decrypted object URL
    const imgs = wrapper.findAll('.media-tile img')
    expect(imgs).toHaveLength(2)
    expect(imgs[0].attributes('src')).toBeTruthy()
    // operator-blind: each thumbnail came back through a presigned GET + on-device decrypt
    expect(store.api.listNodeMedia).toHaveBeenCalledWith({ scopeId: SCOPE_ID, nodeId: NODE_ID, limit: MEDIA_PAGE_LIMIT })
    expect(store.blob.presignBlobDownloads).toHaveBeenCalled()
  })

  it('draws a play badge only on a video tile — from the original\'s blob meta type (#104/#105, inbox #397)', async () => {
    const store = galleryFakeStore()
    fakeApi(mediaApi, store.api)
    fakeApi(blobApi, store.blob)
    vi.stubGlobal('fetch', store.fetchStub)
    const memoryKey = v1.randomBytes(32)
    await addMedia(SCOPE_ID, NODE_ID, memoryKey, { blob: new Blob([new Uint8Array(8).fill(1) as BlobPart]), mediaType: 'image/png' }, stubDeriver())
    await addMedia(SCOPE_ID, NODE_ID, memoryKey, { blob: new Blob([new Uint8Array(8).fill(2) as BlobPart]), mediaType: 'video/mp4' }, stubDeriver())

    const wrapper = mountDetail(memoryKey)
    await flushPromises()
    await flushPromises()

    // exactly one tile carries the play badge — the video; the image tile has none
    const badged = wrapper.findAll('.media-tile').filter((t) => t.find('.media-tile-play').exists())
    expect(badged).toHaveLength(1)
    expect(wrapper.findAll('.media-tile-play')).toHaveLength(1)
  })

  it('sorts the grid by capture time — newest first by default, and toggles to oldest first (cr-0066)', async () => {
    const store = galleryFakeStore()
    fakeApi(mediaApi, store.api)
    fakeApi(blobApi, store.blob)
    vi.stubGlobal('fetch', store.fetchStub)
    const memoryKey = v1.randomBytes(32)
    // media 0 = 2020, media 1 = 2022, media 2 = 2021 (creation order); the exifr date drives the capturedAt tier
    vi.mocked(exifr.parse)
      .mockResolvedValueOnce({ DateTimeOriginal: new Date(Date.UTC(2020, 0, 1)) })
      .mockResolvedValueOnce({ DateTimeOriginal: new Date(Date.UTC(2022, 0, 1)) })
      .mockResolvedValueOnce({ DateTimeOriginal: new Date(Date.UTC(2021, 0, 1)) })
    await addMedia(SCOPE_ID, NODE_ID, memoryKey, { blob: new Blob([new Uint8Array(8).fill(1) as BlobPart]), mediaType: 'image/jpeg' }, stubDeriver())
    await addMedia(SCOPE_ID, NODE_ID, memoryKey, { blob: new Blob([new Uint8Array(8).fill(2) as BlobPart]), mediaType: 'image/jpeg' }, stubDeriver())
    await addMedia(SCOPE_ID, NODE_ID, memoryKey, { blob: new Blob([new Uint8Array(8).fill(3) as BlobPart]), mediaType: 'image/jpeg' }, stubDeriver())

    const wrapper = mountDetail(memoryKey)
    await flushPromises()
    await flushPromises()

    // the client mints each media id — read them back in creation order
    const [m0, m1, m2] = store.media.map((m) => m.mediaId)
    const order = () => wrapper.findAll('.media-tile').map((t) => t.attributes('data-media-id'))
    // newest first (default): 2022 (media 1), 2021 (media 2), 2020 (media 0)
    expect(order()).toEqual([m1, m2, m0])

    // toggle → oldest first
    await wrapper.find('.gallery-sort-toggle').trigger('click')
    await flushPromises()
    expect(order()).toEqual([m0, m2, m1])
  })

  it('a lightbox date correction persists on close + reopen — no stale date (cr-0066 regression guard)', async () => {
    const store = galleryFakeStore()
    fakeApi(mediaApi, store.api)
    fakeApi(blobApi, store.blob)
    vi.stubGlobal('fetch', store.fetchStub)
    const memoryKey = v1.randomBytes(32)
    vi.mocked(exifr.parse).mockResolvedValue({ DateTimeOriginal: new Date(Date.UTC(2021, 4, 1, 10, 0, 0)) })
    await addMedia(SCOPE_ID, NODE_ID, memoryKey, { blob: new Blob([new Uint8Array(8).fill(1) as BlobPart]), mediaType: 'image/jpeg' }, stubDeriver())

    const wrapper = mountDetail(memoryKey)
    await flushPromises()
    await flushPromises()

    // open the lightbox, correct the capture time
    await wrapper.find('.media-tile-open').trigger('click')
    await flushPromises()
    expect(wrapper.text()).not.toContain(t('media.capturedAtCorrected'))
    await wrapper.find(`[aria-label="${t('media.editCapturedAt')}"]`).trigger('click')
    await wrapper.find('#captured-at-input').setValue('2019-08-15T08:30')
    await wrapper.find('.media-lightbox-date-form').trigger('submit')
    await flushPromises()
    expect(wrapper.text()).toContain(t('media.capturedAtCorrected'))

    // close the lightbox
    await wrapper.find(`.media-lightbox [aria-label="${t('media.close')}"]`).trigger('click')
    await flushPromises()
    expect(wrapper.find('.media-lightbox').exists()).toBe(false)

    // reopen the SAME tile → the correction persisted (the grid item was patched, not just a transient sort key).
    // The regression: an in-lightbox edit updated only the sort order, so reopening unsealed the stale tier.
    await wrapper.find('.media-tile-open').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain(t('media.capturedAtCorrected'))
  })

  it('shows the empty state for a Memory with no media', async () => {
    const store = galleryFakeStore()
    fakeApi(mediaApi, store.api)
    fakeApi(blobApi, store.blob)
    vi.stubGlobal('fetch', store.fetchStub)

    const wrapper = mountDetail(v1.randomBytes(32))
    await flushPromises()

    expect(wrapper.find('.veyq-empty').text()).toBe(t('media.empty'))
    expect(wrapper.findAll('.media-tile')).toHaveLength(0)
  })

  it('renders a non-image/video item (E1) as an icon tile, fetching no thumbnail for it', async () => {
    const store = galleryFakeStore()
    fakeApi(mediaApi, store.api)
    fakeApi(blobApi, store.blob)
    vi.stubGlobal('fetch', store.fetchStub)
    const memoryKey = v1.randomBytes(32)
    await addMedia(SCOPE_ID, NODE_ID, memoryKey, { blob: new Blob([new Uint8Array([1, 2, 3]) as BlobPart]), mediaType: 'application/pdf' }, stubDeriver())

    const wrapper = mountDetail(memoryKey)
    await flushPromises()
    await flushPromises()

    expect(wrapper.findAll('.media-tile')).toHaveLength(1)
    expect(wrapper.find('.media-tile img').exists()).toBe(false)
    expect(wrapper.find('.media-tile .media-grid-icon').exists()).toBe(true)
    // a non-derivable item is never fetched (no preview/thumbnail tier exists)
    expect(store.blob.presignBlobDownloads).not.toHaveBeenCalled()
  })

  it('shows the unavailable state when K_node is absent (E2: locked / not openable)', async () => {
    const wrapper = mountDetail(null as unknown as Uint8Array, { memoryKey: null })
    await flushPromises()
    expect(wrapper.find('.veyq-empty').text()).toBe(t('media.unavailable'))
  })

  it('renders the Memory’s chapters and emits open/create (cr-0060)', async () => {
    const store = galleryFakeStore()
    fakeApi(mediaApi, store.api)
    fakeApi(blobApi, store.blob)
    vi.stubGlobal('fetch', store.fetchStub)
    const chapters: TreeNode[] = [
      treeNode({ id: 'c1', kind: CHAPTER_KIND, name: 'Day 1', parentId: NODE_ID, orderIndex: 0, epoch: 0, wrappedContentKey: 'd3JhcA==', wrappedMeta: null, metaRevision: 0, placementRevision: 0 }),
    ]

    const wrapper = mountDetail(v1.randomBytes(32), { chapters })
    await flushPromises()

    // switch to the Chapters tab (the gallery is the default tab)
    const chaptersTab = wrapper.findAll('[role="tab"]').find((tab) => tab.text().includes(t('memory.chapters')))
    expect(chaptersTab).toBeTruthy()
    await chaptersTab!.trigger('click')
    await flushPromises()

    // the chapter shows as a MemoryCard (not a chip, cr-0062), open-only (no favorite pin)
    const card = wrapper.find('.memory-card')
    expect(card.exists()).toBe(true)
    expect(card.text()).toContain('Day 1')
    expect(card.find('.memory-card-pin').exists()).toBe(false) // the card is open-only: the heart is core's
    // clicking the card asks the host to open that chapter
    await card.find('.memory-card-main').trigger('click')
    expect(wrapper.emitted('openChapter')?.[0]?.[0]).toMatchObject({ id: 'c1' })

    // the create card asks the host to start creation
    await wrapper.find('.memory-create-card-memory').trigger('click')
    expect(wrapper.emitted('createChapter')).toBeTruthy()
  })

  it('opens the lightbox when a tile is clicked', async () => {
    const store = galleryFakeStore()
    fakeApi(mediaApi, store.api)
    fakeApi(blobApi, store.blob)
    vi.stubGlobal('fetch', store.fetchStub)
    const memoryKey = v1.randomBytes(32)
    await addMedia(SCOPE_ID, NODE_ID, memoryKey, { blob: new Blob([new Uint8Array(8).fill(1) as BlobPart]), mediaType: 'image/png' }, stubDeriver())

    const wrapper = mountDetail(memoryKey)
    await flushPromises()
    await flushPromises()

    expect(wrapper.find('.media-lightbox').exists()).toBe(false)
    await wrapper.find('.media-tile-open').trigger('click')
    await flushPromises()
    expect(wrapper.find('.media-lightbox').exists()).toBe(true)
  })
})

// inbox #488: a Chapter of many photos used to send one presign POST per tile and every GET at once, which Chromium
// refused past ~1,000 (`ERR_INSUFFICIENT_RESOURCES`) — and a refused tile shimmered forever. Through the real
// transport and its read scheduler: batched presigns, a bounded number of GETs, and a tile that cannot be read shown as
// such.
describe('MemoryDetail (a Chapter of many photos, inbox #488)', () => {
  it('asks its presigns in contract-sized batches, never has more GETs in flight than the cap, and shows a tile it could not read', async () => {
    const store = galleryFakeStore()
    fakeApi(mediaApi, store.api)
    fakeApi(blobApi, store.blob)
    const memoryKey = v1.randomBytes(32)
    vi.stubGlobal('fetch', store.fetchStub)
    const count = PRESIGN_BATCH_MAX + 44
    for (let i = 0; i < count; i++) {
      await addMedia(SCOPE_ID, NODE_ID, memoryKey, { blob: new Blob([new Uint8Array([i & 0xff, i >> 8])]), mediaType: 'image/png' }, stubDeriver())
    }
    const thumbnailUrls = new Set(store.media.map((m) => `mem://${m.derivatives.find((d) => d.kind === 'THUMBNAIL')!.uploadId}/0`))
    const broken = [...thumbnailUrls][0]
    store.objects.delete(broken) // the store answers 404 for this one tile: final, no retry
    const inFlight = { now: 0, max: 0 }
    const gets: string[] = []
    vi.stubGlobal('fetch', async (url: string, init?: RequestInit) => {
      gets.push(url)
      inFlight.max = Math.max(inFlight.max, ++inFlight.now)
      try {
        await new Promise((r) => setTimeout(r, 1))
        return await store.fetchStub(url, init)
      } finally {
        inFlight.now--
      }
    })
    store.blob.presignBlobDownloads.mockClear()

    const wrapper = mountDetail(memoryKey)
    await vi.waitFor(() => expect(wrapper.findAll('.media-tile img').length + wrapper.findAll('.media-tile-failed').length).toBe(count), { timeout: 20_000 })

    const batches = store.blob.presignBlobDownloads.mock.calls.map(([r]) => r.blobDownloadsBatchRequest.items.length)
    expect(batches).toEqual([PRESIGN_BATCH_MAX, 44]) // was one POST per tile
    expect(inFlight.max).toBeLessThanOrEqual(GETS_IN_FLIGHT)
    expect(inFlight.max).toBeGreaterThan(1)
    expect(gets.filter((u) => thumbnailUrls.has(u)).length).toBe(count) // every thumbnail once; the 404 not retried
    expect(wrapper.findAll('.media-tile-failed')).toHaveLength(1)
    expect(wrapper.find('.media-tile-failed').text()).toBe(t('media.thumbnailFailed'))
  }, 30_000)
})

describe('MemoryDetail (Memory date — header + auto-reconcile, cr-0067)', () => {
  /** Add one EXIF-dated media (trustworthy source) at `epoch`, into a fresh gallery store. */
  async function seedDatedMedia(memoryKey: Uint8Array, epoch: number) {
    const store = galleryFakeStore()
    fakeApi(mediaApi, store.api)
    fakeApi(blobApi, store.blob)
    vi.stubGlobal('fetch', store.fetchStub)
    vi.mocked(exifr.parse).mockResolvedValue({ DateTimeOriginal: new Date(epoch) })
    await addMedia(SCOPE_ID, NODE_ID, memoryKey, { blob: new Blob([new Uint8Array(8).fill(1) as BlobPart]), mediaType: 'image/jpeg' }, stubDeriver())
    return store
  }
  const emptyStore = () => {
    const store = galleryFakeStore()
    fakeApi(mediaApi, store.api)
    fakeApi(blobApi, store.blob)
    vi.stubGlobal('fetch', store.fetchStub)
    return store
  }

  it('shows the neutral “no date yet” chip for a Memory with no trustworthy date', async () => {
    emptyStore()
    const wrapper = mountDetail(v1.randomBytes(32))
    await flushPromises()
    expect(wrapper.find('.memory-date-chip').text()).toContain(t('memory.date.noDate'))
    expect(updateNode).not.toHaveBeenCalled() // absent meta already == empty auto; nothing to seal
  })

  it('renders a sealed manual single date in the header chip (locale-formatted)', async () => {
    emptyStore()
    const memoryKey = v1.randomBytes(32)
    const sealed = sealMemoryDate(memoryKey, THIS_NODE, { mode: 'single', from: '2026-06-15', to: '2026-06-15' })
    const wrapper = mountDetail(memoryKey, { node: node('Rom 2026', sealed) })
    await flushPromises()
    expect(wrapper.find('.memory-date-chip').text()).toContain('06/15/2026')
    expect(updateNode).not.toHaveBeenCalled() // a manual date is never auto-reconciled
  })

  it('materializes the auto range from EXIF-dated media on open (fresh Memory)', async () => {
    const memoryKey = v1.randomBytes(32)
    const epoch = Date.UTC(2021, 4, 1, 10, 0, 0)
    await seedDatedMedia(memoryKey, epoch)
    const wrapper = mountDetail(memoryKey) // node.wrappedMeta = null
    await flushPromises()
    await flushPromises()
    const day = epochMsToIsoDate(epoch)
    expect(lastPatchedDate(memoryKey)).toEqual({ mode: 'auto', from: day, to: day })
    expect(wrapper.emitted('metaUpdated')?.[0]?.[0]).toMatchObject({ nodeId: NODE_ID })
  })

  it('self-heals a stale stored auto range on open', async () => {
    const memoryKey = v1.randomBytes(32)
    const epoch = Date.UTC(2021, 4, 1, 10, 0, 0)
    await seedDatedMedia(memoryKey, epoch)
    const stale = sealMemoryDate(memoryKey, THIS_NODE, { mode: 'auto', from: '2000-01-01', to: '2000-01-02' })
    mountDetail(memoryKey, { node: node('Rom 2026', stale) })
    await flushPromises()
    await flushPromises()
    const day = epochMsToIsoDate(epoch)
    expect(lastPatchedDate(memoryKey)).toEqual({ mode: 'auto', from: day, to: day })
  })

  it('does not overwrite a manual date when reconciling on open', async () => {
    const memoryKey = v1.randomBytes(32)
    await seedDatedMedia(memoryKey, Date.UTC(2021, 4, 1))
    const manual = sealMemoryDate(memoryKey, THIS_NODE, { mode: 'single', from: '2026-06-15', to: '2026-06-15' })
    mountDetail(memoryKey, { node: node('Rom 2026', manual) })
    await flushPromises()
    await flushPromises()
    expect(updateNode).not.toHaveBeenCalled()
  })

  it('saves an edited date — re-seals via updateNode + emits metaUpdated for the tree', async () => {
    emptyStore()
    const memoryKey = v1.randomBytes(32)
    const DateEditorStub = {
      props: ['date', 'autoRange'],
      emits: ['save', 'close'],
      template: `<button class="stub-save" @click="$emit('save', { mode: 'single', from: '2026-06-15', to: '2026-06-15' })" />`,
    }
    const wrapper = mount(MemoryDetail, {
      props: { source: memberMemorySource({ scopeId: () => SCOPE_ID }), node: node('Rom 2026'), scopeId: SCOPE_ID, memoryKey, rights: memberMemoryRights({ publicShares: false }) },
      global: { plugins: [realI18n(), PrimeVue], directives: { tooltip: {} }, stubs: { MemoryDateEditor: DateEditorStub } },
    })
    await flushPromises()
    await wrapper.find('.memory-date-chip').trigger('click') // open the editor
    await flushPromises()
    await wrapper.find('.stub-save').trigger('click')
    await flushPromises()
    expect(lastPatchedDate(memoryKey)).toEqual({ mode: 'single', from: '2026-06-15', to: '2026-06-15' })
    expect(wrapper.emitted('metaUpdated')?.[0]?.[0]).toMatchObject({ nodeId: NODE_ID })
  })
})

describe('MemoryDetail (Memory location — header chip + read-modify-write, cr-0069)', () => {
  const emptyStore = () => {
    const store = galleryFakeStore()
    fakeApi(mediaApi, store.api)
    fakeApi(blobApi, store.blob)
    vi.stubGlobal('fetch', store.fetchStub)
    return store
  }
  const GEOCODED: MemoryLocation = {
    name: 'Teneriffa', nameSource: 'geocoded', derived: { lat: 28.27, lng: -16.61 }, derivedMode: 'auto',
    nameAnchor: { lat: 28.27, lng: -16.61, level: 'city' },
  }
  // A stub editor: two buttons emit save(location) / save(null) so the detail's RMW wiring is what's under test.
  const LocationEditorStub = {
    props: ['location', 'medoid'],
    emits: ['save', 'close'],
    setup(_: unknown, { emit }: { emit: (e: string, ...args: unknown[]) => void }) {
      return { doSave: () => emit('save', GEOCODED), doRemove: () => emit('save', null) }
    },
    template: `<div class="loc-editor"><button class="loc-save" @click="doSave"></button><button class="loc-remove" @click="doRemove"></button></div>`,
  }
  function mountWithLocationStub(memoryKey: Uint8Array, wrappedMeta: string | null) {
    setMapsConsent(true) // skip the consent dialog — go straight into the (stubbed) editor
    return mount(MemoryDetail, {
      props: { source: memberMemorySource({ scopeId: () => SCOPE_ID }), node: node('Rom 2026', wrappedMeta), scopeId: SCOPE_ID, memoryKey, rights: memberMemoryRights({ publicShares: false }) },
      global: { plugins: [realI18n(), PrimeVue], directives: { tooltip: {} }, stubs: { MemoryLocationEditor: LocationEditorStub } },
    })
  }

  it('shows a “Set location” affordance when the Memory has no place', async () => {
    emptyStore()
    const wrapper = mountWithLocationStub(v1.randomBytes(32), null)
    await flushPromises()
    expect(wrapper.find('.memory-location-chip').text()).toContain(t('memory.place.set'))
  })

  it('renders the stored location name in the chip', async () => {
    emptyStore()
    const memoryKey = v1.randomBytes(32)
    const sealed = sealNodeMeta(memoryKey, THIS_NODE, { v: 1, location: GEOCODED })
    const wrapper = mountWithLocationStub(memoryKey, sealed)
    await flushPromises()
    expect(wrapper.find('.memory-location-chip').text()).toContain('Teneriffa')
  })

  it('saving a location read-modify-writes the blob — the sibling date is preserved', async () => {
    emptyStore()
    const memoryKey = v1.randomBytes(32)
    const date: MemoryDate = { mode: 'single', from: '2026-06-15', to: '2026-06-15' }
    const sealed = sealNodeMeta(memoryKey, THIS_NODE, { v: 1, date }) // node already has a manual date
    const wrapper = mountWithLocationStub(memoryKey, sealed)
    await flushPromises()

    await wrapper.find('.memory-location-chip').trigger('click') // maps consented → editor opens
    await flushPromises()
    await wrapper.find('.loc-save').trigger('click')
    await flushPromises()

    // the PATCH sealed the location AND kept the date
    expect(lastPatchedLocation(memoryKey)).toMatchObject({ name: 'Teneriffa', derivedMode: 'auto' })
    expect(lastPatchedDate(memoryKey)).toEqual(date)
    expect(wrapper.emitted('metaUpdated')?.[0]?.[0]).toMatchObject({ nodeId: NODE_ID })
  })

  it('removing the location clears the place but keeps the date (RMW)', async () => {
    emptyStore()
    const memoryKey = v1.randomBytes(32)
    const date: MemoryDate = { mode: 'ongoing', from: '2026-01-01' }
    const sealed = sealNodeMeta(memoryKey, THIS_NODE, { v: 1, date, location: GEOCODED })
    const wrapper = mountWithLocationStub(memoryKey, sealed)
    await flushPromises()

    await wrapper.find('.memory-location-chip').trigger('click')
    await flushPromises()
    await wrapper.find('.loc-remove').trigger('click')
    await flushPromises()

    expect(lastPatchedLocation(memoryKey)).toBeNull() // place gone
    expect(lastPatchedDate(memoryKey)).toEqual(date) // date intact
  })

  it('self-heals a pre-armed AUTO location (no derived) to the media medoid on open', async () => {
    // an `auto` location pre-armed at creation carries a name but no position yet (cr-0069 phase 3)
    const memoryKey = v1.randomBytes(32)
    const pending: MemoryLocation = { name: 'Boulder gym', nameSource: 'manual', derivedMode: 'auto' }
    const sealed = sealNodeMeta(memoryKey, THIS_NODE, { v: 1, location: pending })

    const store = galleryFakeStore()
    fakeApi(mediaApi, store.api)
    fakeApi(blobApi, store.blob)
    vi.stubGlobal('fetch', store.fetchStub)
    vi.mocked(exifr.parse).mockResolvedValue({ latitude: 48.85, longitude: 2.35 })
    await addMedia(SCOPE_ID, NODE_ID, memoryKey, { blob: new Blob([new Uint8Array(8).fill(1) as BlobPart]), mediaType: 'image/jpeg' }, stubDeriver())

    mountWithLocationStub(memoryKey, sealed)
    await flushPromises()
    await flushPromises()

    // the open-time self-heal filled `derived` from the medoid while keeping the name + auto mode
    expect(lastPatchedLocation(memoryKey)).toEqual({
      name: 'Boulder gym', nameSource: 'manual', derivedMode: 'auto', derived: { lat: 48.85, lng: 2.35 }, nameAnchor: undefined,
    })
  })

  /** A `409 stale_revision` carrying the server's current node blob (ADR 0077), as the generated client throws it. */
  const staleNode = (wrappedMeta: string, metaRevision: number) => ({
    response: new Response(JSON.stringify({ code: 'stale_revision', wrappedMeta, metaRevision }), { status: 409 }),
  })

  /**
   * The CR's own scenario (uc.memory.concurrent-curation E2, the worst writer): B's tree was loaded before A set the
   * place. B merely OPENS the Memory, the auto-date self-heal re-seals — from the stale tree — and meets a 409 whose
   * blob holds A's place. The retry re-runs only the date on it, so the place survives and the tree learns both.
   * Before the revision existed, that open-time PATCH landed and A's place was gone.
   */
  it('the open-time date self-heal from a stale tree keeps a place a peer set meanwhile', async () => {
    const memoryKey = v1.randomBytes(32)
    const store = galleryFakeStore()
    fakeApi(mediaApi, store.api)
    fakeApi(blobApi, store.blob)
    vi.stubGlobal('fetch', store.fetchStub)
    vi.mocked(exifr.parse).mockResolvedValue({ DateTimeOriginal: new Date(Date.UTC(2026, 2, 3, 12)) })
    await addMedia(SCOPE_ID, NODE_ID, memoryKey, { blob: new Blob([new Uint8Array(8).fill(1) as BlobPart]), mediaType: 'image/jpeg' }, stubDeriver())
    const peers = sealNodeMeta(memoryKey, THIS_NODE, { v: 1, location: { ...GEOCODED, derivedMode: 'fixed' } })
    updateNode.mockRejectedValueOnce(staleNode(peers, 1))

    const wrapper = mountWithLocationStub(memoryKey, null) // B's tree: no meta at all, revision 0
    await flushPromises()
    await flushPromises()

    expect(updateNode.mock.calls[0][0].updateNodeRequest.metaRevision).toBe(0)
    expect(updateNode.mock.calls[1][0].updateNodeRequest.metaRevision).toBe(1)
    expect(lastPatchedDate(memoryKey)).toMatchObject({ mode: 'auto', from: '2026-03-03' })
    expect(lastPatchedLocation(memoryKey)).toMatchObject({ name: 'Teneriffa', derivedMode: 'fixed' }) // A's place
    expect(wrapper.emitted('metaUpdated')?.at(-1)?.[0]).toMatchObject({ nodeId: NODE_ID, metaRevision: 2 })
  })

  /**
   * The location self-heal re-decides on the fresh blob: B's stale tree says `auto`, but A pinned the place
   * (`fixed`) meanwhile — the 409's blob says so, and a fixed place is the user's, so nothing more is written.
   */
  it('the location self-heal does not move a place a peer fixed meanwhile', async () => {
    const memoryKey = v1.randomBytes(32)
    const store = galleryFakeStore()
    fakeApi(mediaApi, store.api)
    fakeApi(blobApi, store.blob)
    vi.stubGlobal('fetch', store.fetchStub)
    vi.mocked(exifr.parse).mockResolvedValue({ latitude: 48.85, longitude: 2.35 })
    await addMedia(SCOPE_ID, NODE_ID, memoryKey, { blob: new Blob([new Uint8Array(8).fill(1) as BlobPart]), mediaType: 'image/jpeg' }, stubDeriver())
    const stale = sealNodeMeta(memoryKey, THIS_NODE, { v: 1, location: { name: 'Boulder gym', nameSource: 'manual', derivedMode: 'auto' } })
    const fixed = sealNodeMeta(memoryKey, THIS_NODE, { v: 1, location: { name: 'Lisbon', nameSource: 'manual', derivedMode: 'fixed', derived: { lat: 38.7, lng: -9.1 } } })
    updateNode.mockRejectedValueOnce(staleNode(fixed, 3))

    mountWithLocationStub(memoryKey, stale)
    await flushPromises()
    await flushPromises()

    const locationWrites = updateNode.mock.calls.filter((c) => unsealMemoryLocation(SCOPE_ID, memoryKey, { id: NODE_ID, wrappedMeta: c[0].updateNodeRequest.wrappedMeta ?? null })?.name === 'Boulder gym')
    expect(locationWrites).toHaveLength(1) // the stale attempt, refused — and no retry over the peer's fixed place
    expect(updateNode).toHaveBeenCalledTimes(1)
  })

  it('shows “no location detected” for an armed auto location when there is NO geotagged media (nothing to resolve)', async () => {
    emptyStore()
    const memoryKey = v1.randomBytes(32)
    const pending = sealNodeMeta(memoryKey, THIS_NODE, { v: 1, location: { nameSource: 'geocoded', derivedMode: 'auto' } })
    const wrapper = mountWithLocationStub(memoryKey, pending)
    await flushPromises()
    // No GPS media ⇒ the armed auto place can never resolve, so the chip says so rather than imply an in-progress lookup.
    expect(wrapper.find('.memory-location-chip').text()).toContain(t('memory.place.none'))
  })

  it('shows the “pending” placeholder for an armed auto location while a geotagged medium exists but no name yet', async () => {
    setMapsConsent(false) // a position to resolve exists, but geocoding is not consented ⇒ the name stays deferred
    const memoryKey = v1.randomBytes(32)
    const pending = sealNodeMeta(memoryKey, THIS_NODE, { v: 1, location: { nameSource: 'geocoded', derivedMode: 'auto' } })
    const store = galleryFakeStore()
    fakeApi(mediaApi, store.api)
    fakeApi(blobApi, store.blob)
    vi.stubGlobal('fetch', store.fetchStub)
    vi.mocked(exifr.parse).mockResolvedValue({ latitude: 48.85, longitude: 2.35 })
    await addMedia(SCOPE_ID, NODE_ID, memoryKey, { blob: new Blob([new Uint8Array(8).fill(1) as BlobPart]), mediaType: 'image/jpeg' }, stubDeriver())
    const wrapper = mountWithLocationStub(memoryKey, pending)
    await flushPromises()
    await flushPromises()
    expect(wrapper.find('.memory-location-chip').text()).toContain(t('memory.place.pending'))
  })

  it('materializes a pending auto location — geocodes the medoid name on the first geotagged media (self-heal)', async () => {
    setMapsConsent(true)
    setGeocoderUrl('https://nominatim.example')
    vi.mocked(reverseGeocode).mockResolvedValue([{ key: 'city', name: 'La Orotava' }, { key: 'state', name: 'Kanaren' }])
    const memoryKey = v1.randomBytes(32)
    const pending = sealNodeMeta(memoryKey, THIS_NODE, { v: 1, location: { nameSource: 'geocoded', derivedMode: 'auto' } })

    const store = galleryFakeStore()
    fakeApi(mediaApi, store.api)
    fakeApi(blobApi, store.blob)
    vi.stubGlobal('fetch', store.fetchStub)
    vi.mocked(exifr.parse).mockResolvedValue({ latitude: 48.85, longitude: 2.35 })
    await addMedia(SCOPE_ID, NODE_ID, memoryKey, { blob: new Blob([new Uint8Array(8).fill(1) as BlobPart]), mediaType: 'image/jpeg' }, stubDeriver())

    mountWithLocationStub(memoryKey, pending)
    await flushPromises()
    await flushPromises()

    expect(reverseGeocode).toHaveBeenCalled()
    expect(lastPatchedLocation(memoryKey)).toMatchObject({
      name: 'La Orotava', nameSource: 'geocoded', derivedMode: 'auto', derived: { lat: 48.85, lng: 2.35 },
      nameAnchor: { lat: 48.85, lng: 2.35, level: 'city' },
    })
  })

  it('with maps not consented, the chip opens the consent dialog instead of the editor', async () => {
    emptyStore()
    setMapsConsent(false)
    const DialogStub = { props: ['visible'], template: '<div v-if="visible" class="dlg"><slot /></div>' }
    const wrapper = mount(MemoryDetail, {
      props: { source: memberMemorySource({ scopeId: () => SCOPE_ID }), node: node('Rom 2026'), scopeId: SCOPE_ID, memoryKey: v1.randomBytes(32), rights: memberMemoryRights({ publicShares: false }) },
      global: { plugins: [realI18n(), PrimeVue], directives: { tooltip: {} }, stubs: { MemoryLocationEditor: LocationEditorStub, Dialog: DialogStub } },
    })
    await flushPromises()
    await wrapper.find('.memory-location-chip').trigger('click')
    await flushPromises()
    expect(wrapper.find('.loc-editor').exists()).toBe(false) // no editor
    expect(wrapper.find('.dlg').text()).toContain(t('memory.place.consentBody')) // the consent dialog body
  })
})

// inbox #458 (ADR 0088 § The register): with `memories.map` and `memories.places` off a place still works, named by
// hand. The real editor is mounted, so the whole path is under test: the chip opens it without a consent dialog (there
// is no map to consent to), it draws no map, and nothing is fetched from a service the instance switched off.
describe('MemoryDetail (a place on a server without a map, #458)', () => {
  const TILE_HOST = 'tiles.switched-off.example'
  const GEOCODER_HOST = 'geocoder.switched-off.example'
  const DialogStub = { props: ['visible'], template: '<div v-if="visible" class="dlg"><slot /></div>' }
  let requested: string[]
  function servicesOff(): ReturnType<typeof galleryFakeStore> {
    setTileUrl('')
    setGeocoderUrl('')
    setMapsConsent(true) // a consent given earlier must not bring a switched-off service back
    const store = galleryFakeStore()
    fakeApi(mediaApi, store.api)
    fakeApi(blobApi, store.blob)
    requested = []
    vi.stubGlobal('fetch', (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input instanceof Request ? input.url : input)
      requested.push(url)
      return store.fetchStub(url, init)
    })
    return store
  }
  function mountReal(memoryKey: Uint8Array, wrappedMeta: string | null = null) {
    return mount(MemoryDetail, {
      props: { source: memberMemorySource({ scopeId: () => SCOPE_ID }), node: node('Rom 2026', wrappedMeta), scopeId: SCOPE_ID, memoryKey, rights: memberMemoryRights({ publicShares: false }) },
      global: { plugins: [realI18n(), PrimeVue], directives: { tooltip: {} }, stubs: { Dialog: DialogStub } },
    })
  }
  async function nameAndSave(wrapper: ReturnType<typeof mountReal>, name: string) {
    await wrapper.find('.memory-location-chip').trigger('click')
    await flushPromises()
    expect(wrapper.find('.memory-location-editor').exists()).toBe(true) // the editor, not the consent dialog
    expect(wrapper.text()).not.toContain(t('memory.place.consentBody'))
    expect(wrapper.findComponent(MemoryMap).exists()).toBe(false)
    await wrapper.find('.memory-location-editor input').setValue(name)
    await wrapper.find('.memory-location-editor form, form.memory-location-editor').trigger('submit')
    await flushPromises()
  }
  const offHosts = () => requested.filter((u) => u.includes(TILE_HOST) || u.includes(GEOCODER_HOST) || /openstreetmap|nominatim/.test(u))

  it('sets a place from the photos’ GPS with a typed name', async () => {
    servicesOff()
    const memoryKey = v1.randomBytes(32)
    vi.mocked(exifr.parse).mockResolvedValue({ latitude: 48.85, longitude: 2.35 })
    await addMedia(SCOPE_ID, NODE_ID, memoryKey, { blob: new Blob([new Uint8Array(8).fill(1) as BlobPart]), mediaType: 'image/jpeg' }, stubDeriver())
    const wrapper = mountReal(memoryKey)
    await flushPromises()
    await flushPromises()

    await nameAndSave(wrapper, 'Paris trip')

    expect(lastPatchedLocation(memoryKey)).toEqual({
      name: 'Paris trip', nameSource: 'manual', derivedMode: 'auto', derived: { lat: 48.85, lng: 2.35 }, nameAnchor: undefined,
    })
    expect(reverseGeocode).not.toHaveBeenCalled()
    expect(offHosts()).toEqual([])
  })

  it('sets a place that is a name only where no photo carries GPS, and the header shows it', async () => {
    servicesOff()
    const memoryKey = v1.randomBytes(32)
    const wrapper = mountReal(memoryKey)
    await flushPromises()

    await nameAndSave(wrapper, 'At grandma’s')

    const saved = lastPatchedLocation(memoryKey)
    expect(saved).toEqual({ name: 'At grandma’s', nameSource: 'manual', derivedMode: 'auto', derived: undefined, nameAnchor: undefined })
    const wrappedMeta = updateNode.mock.calls.at(-1)![0].updateNodeRequest.wrappedMeta as string
    const reopened = mountReal(memoryKey, wrappedMeta)
    await flushPromises()
    expect(reopened.find('.memory-location-chip').text()).toContain('At grandma’s')
    expect(reverseGeocode).not.toHaveBeenCalled()
    expect(offHosts()).toEqual([])
  })
})

// ADR 0080 Decision 13: a chapter is a share root of its own, so its detail — the same `MemoryDetail` — offers both
// share actions where its rights say so (the instance's public and uploader links, `../rights.ts`), and
// offers none where they do not: the button follows `rights`, never the node's kind.
describe('share actions on a chapter', () => {
  const chapter: TreeNode = treeNode({
    id: 'c1', kind: CHAPTER_KIND, name: 'Day 1', parentId: NODE_ID, orderIndex: 0, epoch: 0, wrappedContentKey: 'd3JhcA==',
    wrappedMeta: null, metaRevision: 0, placementRevision: 0,
  })
  beforeEach(() => {
    const store = galleryFakeStore()
    fakeApi(mediaApi, store.api)
    fakeApi(blobApi, store.blob)
    vi.stubGlobal('fetch', store.fetchStub)
  })

  it('shows the share and the uploader-link buttons on a shareable chapter and emits each', async () => {
    const wrapper = mountDetail(v1.randomBytes(32), { node: chapter, rights: memberMemoryRights({}, CHAPTER_KIND) })
    await flushPromises()
    const share = wrapper.find(`button[aria-label="${t('share.share')}"]`)
    const upload = wrapper.find(`button[aria-label="${t('share.uploadRequest')}"]`)
    expect(share.exists()).toBe(true)
    expect(upload.exists()).toBe(true)
    await share.trigger('click')
    await upload.trigger('click')
    expect(wrapper.emitted('share')).toHaveLength(1)
    expect(wrapper.emitted('shareUpload')).toHaveLength(1)
  })

  it('shows neither when the instance offers no public links (live control above)', async () => {
    const wrapper = mountDetail(v1.randomBytes(32), { node: chapter, rights: memberMemoryRights({ publicShares: false, publicSharesDropBox: false }, CHAPTER_KIND) })
    await flushPromises()
    expect(wrapper.find(`button[aria-label="${t('share.share')}"]`).exists()).toBe(false)
    expect(wrapper.find(`button[aria-label="${t('share.uploadRequest')}"]`).exists()).toBe(false)
  })
})
