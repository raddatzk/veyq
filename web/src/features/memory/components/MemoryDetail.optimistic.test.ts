// @vitest-environment happy-dom
// uc.memory.upload (web, cr-0074) — the optimistic in-grid upload tiles: the queue's in-flight files for this
// node render immediately as preview tiles (progress / failed-with-retry), and reconcile into the real media by
// mediaId (never a duplicate). The pure selection/merge model is covered in optimisticUpload.test.ts; here we
// prove the component wiring — render from the queue, the reconcile handoff, and preview-url revoke on unmount.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'

vi.mock('../../../api/client', () => ({
  mediaApi: vi.fn(), contentApi: vi.fn(), loginApi: vi.fn(), tokenApi: vi.fn(), sessionApi: vi.fn(),
}))
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
  }
})

import { mount, flushPromises } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import PrimeVue from 'primevue/config'
import MemoryDetail from './MemoryDetail.vue'
import { addMedia, type MediaDeriver } from '../media'
import { retryFile } from '../uploadQueue'
import { mediaApi, contentApi } from '../../../api/client'
import * as v1 from '@veyq/crypto/v1'
import { NodeKind } from '../../../api/generated/content'
import type { TreeNode } from '../tree'
import { galleryFakeStore } from './galleryFakeStore'

const SCOPE_ID = '11111111-1111-1111-1111-111111111111'
const NODE_ID = '22222222-2222-2222-2222-222222222222'

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  messages: {
    en: {
      common: {},
      memory: { favorite: 'Favorite', unfavorite: 'Remove favorite', rename: 'Rename', delete: 'Delete', chapters: 'Chapters', gallery: 'Gallery',
        date: { edit: 'Edit date', noDate: 'No date yet', since: 'Since {day}' },
        place: { edit: 'Edit location', set: 'Set location', pending: 'Location pending…' },
        cover: { set: 'Set cover', edit: 'Edit cover' } },
      media: {
        upload: 'Upload', gallery: 'Memory gallery', itemCount: 'No items | {count} item | {count} items',
        openItem: 'Open item {index}', empty: 'No photos in this Memory yet.', unavailable: 'Unavailable', loading: 'Loading…',
        sortNewest: 'Newest first', sortOldest: 'Oldest first', uploadRetry: 'Retry',
        uploadStatus: { queued: 'Queued', uploading: 'Uploading…', done: 'Done', failed: 'Failed', cancelled: 'Cancelled' },
        uploadTilePreview: 'Uploading {name}', uploadTileFailed: 'Upload failed',
      },
    },
  },
})

const node = (name: string): TreeNode => ({
  id: NODE_ID, kind: NodeKind.Memory, name, parentId: null, orderIndex: 0, epoch: 0, wrappedContentKey: null, wrappedMeta: null,
})

function stubDeriver(): MediaDeriver {
  return {
    deriveImage: vi.fn(async (_b: Blob, { maxEdge }: { maxEdge: number; quality: number }) => new Uint8Array([0xff, 0xd8, maxEdge & 0xff])),
    deriveVideoPoster: vi.fn(async () => new Uint8Array([0xff, 0xd8])),
  }
}

/** A queued/uploading/failed/done UploadFile for NODE_ID, minimal shape the component reads. */
function uploadFile(id: string, status: string, opts: { progress?: number; mediaId?: string } = {}) {
  return {
    id, name: `${id}.jpg`, sizeBytes: 3, mediaType: 'image/jpeg', status,
    progress: opts.progress ?? (status === 'done' ? 1 : 0), attempts: 1,
    controller: new AbortController(), file: new File([new Uint8Array([1, 2, 3])], `${id}.jpg`, { type: 'image/jpeg' }),
    mediaId: opts.mediaId,
  }
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
    props: { node: node('Rom 2026'), scopeId: SCOPE_ID, memoryKey, favorite: false },
    global: { plugins: [i18n, PrimeVue], directives: { tooltip: {} } },
  })
}

beforeEach(() => {
  vi.clearAllMocks()
  hoisted.ref.batches.length = 0
  vi.mocked(contentApi).mockReturnValue({ updateNode: vi.fn().mockResolvedValue(undefined) } as unknown as ReturnType<typeof contentApi>)
  vi.spyOn(Element.prototype, 'getBoundingClientRect').mockReturnValue({ width: 960, height: 0, top: 0, left: 0, right: 960, bottom: 0, x: 0, y: 0, toJSON: () => ({}) })
  vi.stubGlobal('ResizeObserver', class { observe() {} unobserve() {} disconnect() {} })
})
afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe('MemoryDetail — optimistic in-grid upload tiles (cr-0074)', () => {
  it('renders an in-flight upload as a preview tile with a progress ring, ahead of the listed media', async () => {
    const store = galleryFakeStore()
    vi.mocked(mediaApi).mockReturnValue(store.api as unknown as ReturnType<typeof mediaApi>)
    vi.stubGlobal('fetch', store.fetchStub)
    const memoryKey = v1.randomBytes(32)
    await addMedia(SCOPE_ID, NODE_ID, memoryKey, { bytes: new Uint8Array(8).fill(1), mediaType: 'image/png' }, stubDeriver())

    const wrapper = mountDetail(memoryKey)
    await flushPromises()
    await flushPromises()
    // one real tile so far
    expect(wrapper.findAll('.gallery-tile-optimistic')).toHaveLength(0)
    expect(wrapper.findAll('.media-tile')).toHaveLength(1)

    // an upload starts → an optimistic tile appears immediately, pinned first, with a local preview + a ring
    pushBatch([uploadFile('u1', 'uploading', { progress: 0.4 })])
    await flushPromises()
    const optimistic = wrapper.findAll('.gallery-tile-optimistic')
    expect(optimistic).toHaveLength(1)
    expect(optimistic[0].find('img').attributes('src')).toBeTruthy() // a local objectURL preview
    expect(optimistic[0].find('.upload-ring').exists()).toBe(true)
    expect(optimistic[0].find('.upload-tile-failed').exists()).toBe(false)
    // pinned ahead of the listed media (first tile in DOM order)
    expect(wrapper.find('.gallery-tile').classes()).toContain('gallery-tile-optimistic')
  })

  it('a failed upload shows an inline retry that re-runs it', async () => {
    const store = galleryFakeStore()
    vi.mocked(mediaApi).mockReturnValue(store.api as unknown as ReturnType<typeof mediaApi>)
    vi.stubGlobal('fetch', store.fetchStub)
    const wrapper = mountDetail(v1.randomBytes(32))
    await flushPromises()

    pushBatch([uploadFile('u1', 'failed')])
    await flushPromises()
    const failed = wrapper.find('.gallery-tile-optimistic.is-failed')
    expect(failed.exists()).toBe(true)
    expect(failed.text()).toContain('Upload failed')

    await failed.find('.upload-tile-retry').trigger('click')
    expect(retryFile).toHaveBeenCalledWith('u1')
  })

  it('reconciles by mediaId: a done upload already listed shows only the real tile — no duplicate', async () => {
    const store = galleryFakeStore()
    vi.mocked(mediaApi).mockReturnValue(store.api as unknown as ReturnType<typeof mediaApi>)
    vi.stubGlobal('fetch', store.fetchStub)
    const memoryKey = v1.randomBytes(32)
    await addMedia(SCOPE_ID, NODE_ID, memoryKey, { bytes: new Uint8Array(8).fill(1), mediaType: 'image/png' }, stubDeriver())

    // the queue still holds the done file, but its media (media-0) is already listed → the optimistic tile drops
    pushBatch([uploadFile('u1', 'done', { mediaId: 'media-0' })])
    const wrapper = mountDetail(memoryKey)
    await flushPromises()
    await flushPromises()

    expect(wrapper.findAll('.gallery-tile-optimistic')).toHaveLength(0) // reconciled away
    expect(wrapper.findAll('.media-tile')).toHaveLength(1) // exactly one tile — the real media, no duplicate
    expect(wrapper.find('.media-tile').attributes('data-media-id')).toBe('media-0')
  })

  it('keeps the optimistic tile while a done upload’s media is not yet listed (no gap before reconcile)', async () => {
    const store = galleryFakeStore()
    vi.mocked(mediaApi).mockReturnValue(store.api as unknown as ReturnType<typeof mediaApi>)
    vi.stubGlobal('fetch', store.fetchStub)
    const wrapper = mountDetail(v1.randomBytes(32))
    await flushPromises()

    pushBatch([uploadFile('u1', 'done', { mediaId: 'media-99' })]) // not in the (empty) store yet
    await flushPromises()
    expect(wrapper.findAll('.gallery-tile-optimistic')).toHaveLength(1)
  })

  it('revokes the preview objectURL on unmount', async () => {
    const store = galleryFakeStore()
    vi.mocked(mediaApi).mockReturnValue(store.api as unknown as ReturnType<typeof mediaApi>)
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
