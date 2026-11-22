// @vitest-environment happy-dom
// uc.memory.buckets through the member's whole detail (inbox #1065): photos and videos dropped onto a bucket of the
// Beiträge view go into that bucket — uploaded through the one queue as the files dropped, each filed into the bucket once
// it is in (an upload lands in its uploader's primary and a move files it, en.memory.buckets). The drop used to be answered
// by opening the picker: the files and the bucket were both lost. The organizer's own rule (which drops it hands up) is
// MemoryBuckets.test.ts'.
import { describe, it, expect, vi, beforeEach, afterEach, type Mock } from 'vitest'

vi.mock('leaflet', () => ({ default: {} }))
vi.mock('../../../api/client', () => ({ blobApi: vi.fn(), contentApi: vi.fn(), loginApi: vi.fn(), tokenApi: vi.fn(), sessionApi: vi.fn() }))
vi.mock('../api', () => ({ mediaApi: vi.fn(), itemFavoritesApi: vi.fn(), ownItemFavoritesApi: vi.fn(), bucketsApi: vi.fn() }))
vi.mock('../uploadQueue', async (orig) => ({ ...(await orig<typeof import('../uploadQueue')>()), enqueueUploads: vi.fn() }))

import { flushPromises } from '@vue/test-utils'
import * as v1 from '@veyq/crypto/v1'
import MemoryDetail from './MemoryDetail.vue'
import MemoryBuckets from './MemoryBuckets.vue'
import { memberMemorySource } from '../source'
import { MEMORY_KIND } from '../kinds'
import { blobApi, contentApi } from '../../../api/client'
import { mediaApi, itemFavoritesApi, bucketsApi } from '../api'
import { enqueueUploads, type UploadFile } from '../uploadQueue'
import { fakeApi, stubApi } from '../../../test/apiMock'
import type { ContentApi } from '../../../api/generated/content'
import type { BucketsApi } from '../../../api/generated/media'
import { galleryFakeStore } from './galleryFakeStore'
import { realI18n, t } from '../../../test/i18n'
import { treeNode, memberMemoryRights } from '../../../test/models'
import { mountWithHeader } from '../../../test/nodeHeader'
import { coreActionsStandIn, coverStandIn } from '../../../test/nodePage'

const SCOPE_ID = '11111111-1111-1111-1111-111111111111'
const NODE_ID = '22222222-2222-2222-2222-222222222222'

let moveBucketMedia: Mock<BucketsApi['moveBucketMedia']>

beforeEach(() => {
  vi.clearAllMocks()
  fakeApi(contentApi, { updateNode: vi.fn(async () => ({ metaRevision: 1 })) as unknown as Mock<ContentApi['updateNode']> })
  stubApi(itemFavoritesApi, { getItemFavorites: [] })
  const store = galleryFakeStore()
  fakeApi(mediaApi, store.api)
  fakeApi(blobApi, store.blob)
  moveBucketMedia = vi.fn<BucketsApi['moveBucketMedia']>(async () => undefined)
  fakeApi(bucketsApi, { listNodeBuckets: vi.fn<BucketsApi['listNodeBuckets']>(async () => ({ buckets: [] })), moveBucketMedia })
  vi.stubGlobal('fetch', store.fetchStub)
  vi.spyOn(Element.prototype, 'getBoundingClientRect').mockReturnValue({ width: 960, height: 0, top: 0, left: 0, right: 960, bottom: 0, x: 0, y: 0, toJSON: () => ({}) })
  vi.stubGlobal('ResizeObserver', class { observe() {} unobserve() {} disconnect() {} })
})
afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

async function mountOnBeitraege() {
  const w = mountWithHeader(MemoryDetail, {
    props: {
      source: memberMemorySource({ scopeId: () => SCOPE_ID }), node: treeNode({ id: NODE_ID, kind: MEMORY_KIND, name: 'Alps', parentId: null }),
      scopeId: SCOPE_ID, memoryKey: v1.randomBytes(32), scopeKey: { epoch: 0, key: v1.randomBytes(32) }, chapters: [],
      rights: memberMemoryRights(), canReport: true,
    },
    global: { plugins: [realI18n()], provide: coreActionsStandIn([], coverStandIn({ canSet: false })) },
    attachTo: document.body,
  })
  await flushPromises()
  const tab = w.findAll('[role="tab"]').find((el) => el.text().includes(t('memory.buckets.title')))
  expect(tab, 'the Beiträge tab is drawn for a member').toBeDefined()
  await tab!.trigger('mousedown') // Reka's tabs switch on the press
  await flushPromises()
  return w
}

describe('MemoryDetail — a drop on the Beiträge view (inbox #1065)', () => {
  // Levers: answer `dropFiles` with the picker again, drop the files, or skip the move.
  it('uploads the dropped files themselves and files each into the bucket it landed on — the picker stays shut', async () => {
    const w = await mountOnBeitraege()
    const pick = vi.spyOn(HTMLInputElement.prototype, 'click')
    const photo = new File([new Uint8Array([1])], 'a.jpg', { type: 'image/jpeg' })

    w.findComponent(MemoryBuckets).vm.$emit('dropFiles', [photo], 's-me')
    await flushPromises()

    expect(pick, 'the picker is not opened').not.toHaveBeenCalled()
    expect(enqueueUploads).toHaveBeenCalledTimes(1)
    const [target, selections, opts] = vi.mocked(enqueueUploads).mock.calls[0]
    expect(target.nodeId).toBe(NODE_ID)
    expect(selections.map((s) => [s.file, s.mediaType])).toEqual([[photo, 'image/jpeg']])

    opts!.onFileDone?.({ mediaId: 'm-1' } as unknown as UploadFile) // the queue's settle names only the id it read
    await flushPromises()
    expect(moveBucketMedia).toHaveBeenCalledTimes(1)
    expect(moveBucketMedia.mock.calls[0][0]).toEqual({ scopeId: SCOPE_ID, nodeId: NODE_ID, moveMediaRequest: { mediaIds: ['m-1'], targetBucketId: 's-me' } })
    w.unmount()
  })

  it("moves nothing for a drop onto the caller's own primary: an upload lands there", async () => {
    const w = await mountOnBeitraege()
    w.findComponent(MemoryBuckets).vm.$emit('dropFiles', [new File([new Uint8Array([1])], 'a.jpg', { type: 'image/jpeg' })], null)
    await flushPromises()
    expect(enqueueUploads).toHaveBeenCalledTimes(1)
    vi.mocked(enqueueUploads).mock.calls[0][2]!.onFileDone?.({ mediaId: 'm-1' } as unknown as UploadFile)
    await flushPromises()
    expect(moveBucketMedia).not.toHaveBeenCalled()
    w.unmount()
  })
})
