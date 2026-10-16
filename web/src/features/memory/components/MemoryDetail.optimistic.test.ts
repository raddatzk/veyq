// @vitest-environment happy-dom
// uc.memory.upload (web, cr-0074) — the optimistic in-grid upload tiles: the queue's in-flight files for this
// node render immediately as preview tiles (progress / failed-with-retry), and reconcile into the real media by
// mediaId (never a duplicate). The pure selection/merge model is covered in optimisticUpload.test.ts; here we
// prove the component wiring — render from the queue, the reconcile handoff, and preview-url revoke on unmount.
//
// The second describe covers the step BEFORE a tile exists: the picker. It belongs here rather than in its own
// file because what it proves is when the first tile can appear at all, and it reuses this file's mount harness.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'

vi.mock('../../../api/client', () => ({
  blobApi: vi.fn(), contentApi: vi.fn(), loginApi: vi.fn(), tokenApi: vi.fn(), sessionApi: vi.fn(),
}))
vi.mock('../api', () => ({ mediaApi: vi.fn() }))
vi.mock('exifr', () => ({ default: { parse: vi.fn(async () => undefined) } }))

// Control the upload queue the component subscribes to: a reactive batches array the test drives directly, so
// we don't depend on the real sequential runner/timers. retryFile is a spy.
const hoisted = vi.hoisted(() => ({ ref: { batches: null as unknown as unknown[] } }))
vi.mock('../uploadQueue', async (orig) => {
  const actual = await orig<typeof import('../uploadQueue')>()
  const { reactive, computed } = await import('vue')
  const batches = reactive<unknown[]>([])
  hoisted.ref.batches = batches
  return {
    ...actual,
    useUploadQueue: () => ({ batches: computed(() => batches), activeFileCount: computed(() => 0) }),
    retryFile: vi.fn(),
    // Spied, not stubbed away in spirit: `mergeFiles` / `isSupportedMediaFile` stay REAL through `...actual`,
    // so the picker's de-dupe is exercised rather than mocked out.
    enqueueUploads: vi.fn(),
  }
})

import { mount, flushPromises } from '@vue/test-utils'
import PrimeVue from 'primevue/config'
import MemoryDetail from './MemoryDetail.vue'
import { memberMemorySource } from '../source'
import { addMedia, type MediaDeriver } from '../media'
import { retryFile, enqueueUploads } from '../uploadQueue'
import { blobApi, contentApi } from '../../../api/client'
import { mediaApi } from '../api'
import * as v1 from '@veyq/crypto/v1'
import { MEMORY_KIND } from '../kinds'
import type { TreeNode } from '../../../core/node/tree'
import { galleryFakeStore } from './galleryFakeStore'
import { fakeApi, stubApi } from '../../../test/apiMock'
import { realI18n, t } from '../../../test/i18n'
import { treeNode, memberMemoryRights } from '../../../test/models'

const SCOPE_ID = '11111111-1111-1111-1111-111111111111'
const NODE_ID = '22222222-2222-2222-2222-222222222222'
/** Two capture moments, far apart, so nothing below depends on a tie-break it did not mean to test. */
const OLD = Date.UTC(2019, 2, 3)
const NEW = Date.UTC(2024, 8, 9)

const node = (name: string): TreeNode => treeNode({
  id: NODE_ID, kind: MEMORY_KIND, name, parentId: null, orderIndex: 0, epoch: 0, wrappedContentKey: 'eA==', wrappedMeta: null, metaRevision: 0, placementRevision: 0,
})

function stubDeriver(): MediaDeriver {
  return {
    deriveImage: vi.fn(async (_b: Blob, { maxEdge }: { maxEdge: number; quality: number }) => ({ bytes: new Uint8Array([0xff, 0xd8, maxEdge & 0xff]), source: null })),
    deriveVideoPoster: vi.fn(async () => new Uint8Array([0xff, 0xd8])),
  }
}

/** A queued/uploading/failed/done UploadFile for NODE_ID, minimal shape the component reads. */
function uploadFile(id: string, status: string, opts: { progress?: number; mediaId?: string; lastModified?: number } = {}) {
  return {
    id, name: `${id}.jpg`, sizeBytes: 3, mediaType: 'image/jpeg', status,
    progress: opts.progress ?? (status === 'done' ? 1 : 0), attempts: 1,
    controller: new AbortController(),
    file: new File([new Uint8Array([1, 2, 3])], `${id}.jpg`, { type: 'image/jpeg', lastModified: opts.lastModified }),
    mediaId: opts.mediaId,
  }
}

/** The grid's tiles in DOM order, each named: a real media by its id, an optimistic tile by its file name. */
function gridOrder(wrapper: ReturnType<typeof mountDetail>): string[] {
  const grid = wrapper.find('.media-grid').element
  return Array.from(grid.children).map(
    (el) => el.getAttribute('data-media-id') ?? el.querySelector('img')?.getAttribute('alt') ?? '?',
  )
}
/** Mark a queued file settled onto a real media, the way the queue does on `done`. */
function settle(batchIndex: number, fileIndex: number, mediaId: string) {
  const batch = hoisted.ref.batches[batchIndex] as { files: { status: string; mediaId?: string }[] }
  batch.files[fileIndex].status = 'done'
  batch.files[fileIndex].mediaId = mediaId
}

function pushBatch(files: unknown[], nodeId = NODE_ID) {
  hoisted.ref.batches.push({
    id: `b-${hoisted.ref.batches.length}`, label: `${files.length}`,
    target: { scopeId: SCOPE_ID, nodeId, memoryKey: new Uint8Array(32) },
    files, seq: hoisted.ref.batches.length, finishedAt: null, acknowledged: false,
  })
}

function mountDetail(memoryKey: Uint8Array) {
  return mount(MemoryDetail, {
    props: { source: memberMemorySource({ scopeId: () => SCOPE_ID }), node: node('Rom 2026'), scopeId: SCOPE_ID, memoryKey, rights: memberMemoryRights() },
    global: { plugins: [realI18n(), PrimeVue], directives: { tooltip: {} } },
  })
}

beforeEach(() => {
  vi.clearAllMocks()
  hoisted.ref.batches.length = 0
  stubApi(contentApi, { updateNode: undefined })
  vi.spyOn(Element.prototype, 'getBoundingClientRect').mockReturnValue({ width: 960, height: 0, top: 0, left: 0, right: 960, bottom: 0, x: 0, y: 0, toJSON: () => ({}) })
  vi.stubGlobal('ResizeObserver', class { observe() {} unobserve() {} disconnect() {} })
})
afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe('MemoryDetail — optimistic in-grid upload tiles (cr-0074)', () => {
  it('renders an in-flight upload as a preview tile with a progress ring, at its capture-time place', async () => {
    const store = galleryFakeStore()
    fakeApi(mediaApi, store.api)
    fakeApi(blobApi, store.blob)
    vi.stubGlobal('fetch', store.fetchStub)
    const memoryKey = v1.randomBytes(32)
    // the listed media is the NEWER one, so "the tile appeared" and "the tile is first" are different claims
    await addMedia(SCOPE_ID, NODE_ID, memoryKey, { blob: new Blob([new Uint8Array(8).fill(1) as BlobPart]), mediaType: 'image/png', lastModified: NEW }, stubDeriver())

    const wrapper = mountDetail(memoryKey)
    await flushPromises()
    await flushPromises()
    // one real tile so far
    expect(wrapper.findAll('.gallery-tile-optimistic')).toHaveLength(0)
    expect(wrapper.findAll('.media-tile')).toHaveLength(1)

    // an upload starts → an optimistic tile appears IMMEDIATELY, with a local preview + a ring
    pushBatch([uploadFile('u1', 'uploading', { progress: 0.4, lastModified: OLD })])
    await flushPromises()
    await flushPromises()
    const optimistic = wrapper.findAll('.gallery-tile-optimistic')
    expect(optimistic).toHaveLength(1)
    expect(optimistic[0].find('img').attributes('src')).toBeTruthy() // a local objectURL preview
    expect(optimistic[0].find('.upload-ring').exists()).toBe(true)
    expect(optimistic[0].find('.upload-tile-failed').exists()).toBe(false)
    // …and it appears where its capture time puts it, which here is BEHIND the newer listed media. This
    // assertion replaces the old "pinned ahead of the listed media (first tile in DOM order)": appearing at
    // once and appearing first were conflated, and only the first of the two was ever the promise.
    expect(gridOrder(wrapper)).toEqual([store.media[0].mediaId, 'u1.jpg']) // the client-minted id of the listed media
  })

  // uc.memory.gallery § "One order for every tile — including one that is still uploading". The defect this
  // replaces: a tile left the front block the instant its media listed and reappeared BEHIND every tile still
  // in flight, so finishing an upload moved it. Both halves are asserted here, and on the old two-block layout
  // the one that reddened first was the ORDER (`['u1.jpg','u2.jpg','media-0']` — enqueue order, ahead of the
  // listed media), which is why the position assertion after it was never reached. Keep both: the order is
  // what this CR changed, the position is what it changed it FOR.
  it('a settling upload hands its grid slot to the real tile — the position does not move', async () => {
    const store = galleryFakeStore()
    fakeApi(mediaApi, store.api)
    fakeApi(blobApi, store.blob)
    vi.stubGlobal('fetch', store.fetchStub)
    const memoryKey = v1.randomBytes(32)
    // one listed media, captured OLD; an upload of that same moment is what will settle onto it
    await addMedia(SCOPE_ID, NODE_ID, memoryKey, { blob: new Blob([new Uint8Array(8).fill(1) as BlobPart]), mediaType: 'image/png', lastModified: OLD }, stubDeriver())

    const wrapper = mountDetail(memoryKey)
    await flushPromises()
    await flushPromises()

    // two in flight: u1 of the OLD moment (the one that will settle), u2 of a NEWER one
    pushBatch([uploadFile('u1', 'uploading', { progress: 0.4, lastModified: OLD }), uploadFile('u2', 'uploading', { progress: 0.1, lastModified: NEW })])
    await flushPromises()
    await flushPromises()
    const m0 = store.media[0].mediaId // the listed media's client-minted id
    expect(gridOrder(wrapper)).toEqual(['u2.jpg', 'u1.jpg', m0]) // newest first; u1 ties with its own media and leads it
    const slotBefore = gridOrder(wrapper).indexOf('u1.jpg')

    // u1 settles onto the already-listed media 0 → its optimistic tile reconciles away
    settle(0, 0, m0)
    await flushPromises()

    const after = gridOrder(wrapper)
    expect(after).not.toContain('u1.jpg') // handed off, not duplicated
    expect(after.indexOf(m0)).toBe(slotBefore) // the whole point: finishing changed the CONTENT, not the place
  })

  // A queued file has not sent a byte, and a preparing one is still being decoded on this device. The tile used
  // to assert "Uploading" for both — in the ring's label and, independently, in the container's — so a large
  // batch read as stuck while the device was busy. Both labels are asserted, because fixing one leaves a screen
  // reader saying "Uploading" over a file that has not started.
  it('a queued tile says queued — neither label claims it is uploading', async () => {
    const store = galleryFakeStore()
    fakeApi(mediaApi, store.api)
    fakeApi(blobApi, store.blob)
    vi.stubGlobal('fetch', store.fetchStub)
    const wrapper = mountDetail(v1.randomBytes(32))
    await flushPromises()

    pushBatch([uploadFile('u1', 'queued', { lastModified: OLD })])
    await flushPromises()

    const tile = wrapper.find('.gallery-tile-optimistic')
    expect(tile.attributes('aria-label')).not.toContain(t('media.uploadStatus.uploading'))
    expect(tile.find('.upload-tile-progress').attributes('aria-label')).not.toContain(t('media.uploadStatus.uploading'))
    expect(tile.text()).toContain(t('media.uploadStatus.queued')) // and it says what it IS, visibly
  })

  it('a preparing tile says preparing — the stage where the device is busy and the wire is not', async () => {
    const store = galleryFakeStore()
    fakeApi(mediaApi, store.api)
    fakeApi(blobApi, store.blob)
    vi.stubGlobal('fetch', store.fetchStub)
    const wrapper = mountDetail(v1.randomBytes(32))
    await flushPromises()

    pushBatch([uploadFile('u1', 'preparing', { lastModified: OLD })])
    await flushPromises()

    const tile = wrapper.find('.gallery-tile-optimistic')
    expect(tile.text()).toContain(t('media.uploadStatus.preparing'))
    expect(tile.attributes('aria-label')).not.toContain(t('media.uploadStatus.uploading'))
  })

  it('a failed upload shows an inline retry that re-runs it', async () => {
    const store = galleryFakeStore()
    fakeApi(mediaApi, store.api)
    fakeApi(blobApi, store.blob)
    vi.stubGlobal('fetch', store.fetchStub)
    const wrapper = mountDetail(v1.randomBytes(32))
    await flushPromises()

    pushBatch([uploadFile('u1', 'failed')])
    await flushPromises()
    const failed = wrapper.find('.gallery-tile-optimistic.is-failed')
    expect(failed.exists()).toBe(true)
    expect(failed.text()).toContain(t('media.uploadTileFailed'))

    await failed.find('.upload-tile-retry').trigger('click')
    expect(retryFile).toHaveBeenCalledWith('u1')
  })

  it('reconciles by mediaId: a done upload already listed shows only the real tile — no duplicate', async () => {
    const store = galleryFakeStore()
    fakeApi(mediaApi, store.api)
    fakeApi(blobApi, store.blob)
    vi.stubGlobal('fetch', store.fetchStub)
    const memoryKey = v1.randomBytes(32)
    await addMedia(SCOPE_ID, NODE_ID, memoryKey, { blob: new Blob([new Uint8Array(8).fill(1) as BlobPart]), mediaType: 'image/png' }, stubDeriver())

    // the queue still holds the done file, but its media (media 0) is already listed → the optimistic tile drops
    const m0 = store.media[0].mediaId
    pushBatch([uploadFile('u1', 'done', { mediaId: m0 })])
    const wrapper = mountDetail(memoryKey)
    await flushPromises()
    await flushPromises()

    expect(wrapper.findAll('.gallery-tile-optimistic')).toHaveLength(0) // reconciled away
    expect(wrapper.findAll('.media-tile')).toHaveLength(1) // exactly one tile — the real media, no duplicate
    expect(wrapper.find('.media-tile').attributes('data-media-id')).toBe(m0)
  })

  it('keeps the optimistic tile while a done upload’s media is not yet listed (no gap before reconcile)', async () => {
    const store = galleryFakeStore()
    fakeApi(mediaApi, store.api)
    fakeApi(blobApi, store.blob)
    vi.stubGlobal('fetch', store.fetchStub)
    const wrapper = mountDetail(v1.randomBytes(32))
    await flushPromises()

    pushBatch([uploadFile('u1', 'done', { mediaId: 'media-99' })]) // not in the (empty) store yet
    await flushPromises()
    expect(wrapper.findAll('.gallery-tile-optimistic')).toHaveLength(1)
  })

  it('revokes the preview objectURL on unmount', async () => {
    const store = galleryFakeStore()
    fakeApi(mediaApi, store.api)
    fakeApi(blobApi, store.blob)
    vi.stubGlobal('fetch', store.fetchStub)
    const revoke = vi.spyOn(URL, 'revokeObjectURL')
    const wrapper = mountDetail(v1.randomBytes(32))
    await flushPromises()
    pushBatch([uploadFile('u1', 'uploading', { progress: 0.2 })])
    await flushPromises()
    const src = wrapper.find('.gallery-tile-optimistic img').attributes('src')!

    wrapper.unmount()
    expect(revoke).toHaveBeenCalledWith(src)
  })
})

describe('MemoryDetail — the picker does not make everyone wait for Safari', () => {
  /** Put `files` on the hidden input the way a picker does, then fire the change the component listens for. */
  async function pick(wrapper: ReturnType<typeof mountDetail>, files: File[]) {
    const el = wrapper.find('input[type="file"]')
    Object.defineProperty(el.element, 'files', { value: files, configurable: true })
    await el.trigger('change')
  }
  const jpg = (name: string) => new File([new Uint8Array([1, 2, 3])], name, { type: 'image/jpeg' })

  // The re-read window exists for the Safari Photos picker, which populates `input.files` late. It used to gate
  // the FIRST enqueue too, so every browser paid a quiet period before anything could start. The control is the
  // second half: a picker that simply stopped merging would pass the first assertion and fail the rest.
  it('enqueues what it already has before any re-read, and still catches a late arrival as its own batch', async () => {
    const store = galleryFakeStore()
    fakeApi(mediaApi, store.api)
    fakeApi(blobApi, store.blob)
    vi.stubGlobal('fetch', store.fetchStub)
    const wrapper = mountDetail(v1.randomBytes(32))
    await flushPromises()

    const a = jpg('a.jpg')
    const b = jpg('b.jpg')
    vi.useFakeTimers()
    try {
      await pick(wrapper, [a])
      // no timer has run yet — this is the whole point
      expect(vi.mocked(enqueueUploads)).toHaveBeenCalledTimes(1)
      expect(vi.mocked(enqueueUploads).mock.calls[0][1].map((s) => s.file.name)).toEqual(['a.jpg'])

      // Safari fills the rest in later: a second batch, carrying ONLY what is new (E3's de-dupe still holds).
      Object.defineProperty(wrapper.find('input[type="file"]').element, 'files', { value: [a, b], configurable: true })
      await vi.advanceTimersByTimeAsync(800)

      expect(vi.mocked(enqueueUploads)).toHaveBeenCalledTimes(2)
      expect(vi.mocked(enqueueUploads).mock.calls[1][1].map((s) => s.file.name)).toEqual(['b.jpg'])
    } finally {
      vi.useRealTimers()
    }
  })
})
