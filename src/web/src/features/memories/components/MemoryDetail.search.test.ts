// @vitest-environment happy-dom
// The open Memory's media in core's search (ADR 0125 decision 6): the gallery contributes one document per listed medium,
// with the capture time and place its opened listing holds, for as long as it is on screen under the member's page; a
// document opens the lightbox on its medium; a guest's page (no sink) contributes nothing.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'

vi.mock('../../../api/client', () => ({ blobApi: vi.fn(), contentApi: vi.fn(), loginApi: vi.fn(), tokenApi: vi.fn(), sessionApi: vi.fn() }))
vi.mock('../api', () => ({ mediaApi: vi.fn() }))
vi.mock('../../../shared/state/useToast', () => ({ useToast: () => ({ success: vi.fn(), error: vi.fn(), showUndo: vi.fn(), dismissUndo: vi.fn() }) }))
vi.mock('../../../shared/image/exifTags', () => ({ readExifTags: vi.fn() }))

import { flushPromises } from '@vue/test-utils'
import * as v1 from '@veyq/crypto/v1'
import { mountWithHeader } from '../../../test/nodeHeader'
import MemoryDetail from './MemoryDetail.vue'
import MediaLightbox from './MediaLightbox.vue'
import { memberMemorySource } from '../source'
import { addMedia, type MediaDeriver } from '../media'
import { readExifTags } from '../../../shared/image/exifTags'
import { blobApi, contentApi } from '../../../api/client'
import { mediaApi } from '../api'
import { fakeApi } from '../../../test/apiMock'
import { galleryFakeStore } from './galleryFakeStore'
import { realI18n } from '../../../test/i18n'
import { treeNode, memberMemoryRights } from '../../../test/models'
import { MEMORY_KIND } from '../kinds'
import { searchContribution, searchContributionsKey } from '../../../core/search/contribution'
import { memorySearchFacets, PHOTO_DOCUMENT } from '../search'
import { registry } from '../../../core/applications/registry'
import { CHAPTER_KIND } from '../kinds'

const SCOPE_ID = '11111111-1111-1111-1111-111111111111'
const NODE_ID = '22222222-2222-2222-2222-222222222222'
const TAKEN = Date.UTC(2021, 6, 14, 12)

const deriver = (): MediaDeriver => ({
  deriveImage: vi.fn(async (_b: Blob, { maxEdge }: { maxEdge: number; quality: number }) => ({ bytes: new Uint8Array([0xff, 0xd8, maxEdge & 0xff]), source: null })),
  deriveVideoPoster: vi.fn(async () => new Uint8Array([0xff, 0xd8])),
})

function mountDetail(memoryKey: Uint8Array, member = true) {
  return mountWithHeader(MemoryDetail, {
    props: {
      source: memberMemorySource({ scopeId: () => SCOPE_ID }), node: treeNode({ id: NODE_ID, kind: MEMORY_KIND, name: 'Rom 2021' }),
      scopeId: SCOPE_ID, memoryKey, rights: memberMemoryRights({ publicShares: false }),
    },
    global: { plugins: [realI18n()], provide: member ? { [searchContributionsKey as symbol]: true } : {} },
  })
}

beforeEach(() => {
  vi.clearAllMocks()
  fakeApi(contentApi, { updateNode: vi.fn(async () => {}) })
  vi.spyOn(Element.prototype, 'getBoundingClientRect').mockReturnValue({ width: 960, height: 0, top: 0, left: 0, right: 960, bottom: 0, x: 0, y: 0, toJSON: () => ({}) })
  vi.stubGlobal('ResizeObserver', class { observe() {} unobserve() {} disconnect() {} })
})
afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

async function galleryWithOnePhoto(): Promise<Uint8Array> {
  const store = galleryFakeStore()
  fakeApi(mediaApi, store.api)
  fakeApi(blobApi, store.blob)
  vi.stubGlobal('fetch', store.fetchStub)
  vi.mocked(readExifTags).mockResolvedValue({ DateTimeOriginal: TAKEN })
  const key = v1.randomBytes(32)
  await addMedia(SCOPE_ID, NODE_ID, key, { blob: new Blob([new Uint8Array(8).fill(1) as BlobPart]), mediaType: 'image/jpeg', name: 'IMG_0001.jpg' }, deriver())
  return key
}

describe('Memories in the registry', () => {
  it('names a Memory and a chapter by their nouns and reads their facets from the sealed meta', () => {
    for (const [kind, label] of [[MEMORY_KIND, 'memory.kindMemory'], [CHAPTER_KIND, 'memory.kindChapter']] as const) {
      const d = registry.kind(kind)
      expect(d.supported && d.search?.label).toBe(label)
      expect(d.supported && d.search?.facets).toBe(memorySearchFacets)
      expect(d.supported && d.search?.note).toBe('memory.search.note')
    }
  })
})

describe('the open Memory\'s media in the search', () => {
  it('contributes each listed medium with its name and capture time while the gallery is on screen', async () => {
    const key = await galleryWithOnePhoto()
    const w = mountDetail(key)
    await flushPromises()
    await flushPromises()
    const c = searchContribution()
    expect(c?.scopeId).toBe(SCOPE_ID)
    expect(c?.nodeId).toBe(NODE_ID)
    expect(c?.documents).toHaveLength(1)
    expect(c?.documents[0]).toMatchObject({ kind: PHOTO_DOCUMENT, name: 'IMG_0001.jpg', facets: { capturedAt: TAKEN } })
    w.unmount()
    expect(searchContribution()).toBeNull()
  })

  it('opens the lightbox on the medium a result names', async () => {
    const key = await galleryWithOnePhoto()
    const w = mountDetail(key)
    await flushPromises()
    await flushPromises()
    expect(w.findComponent(MediaLightbox).exists()).toBe(false)
    searchContribution()!.documents[0].open()
    await flushPromises()
    expect(w.findComponent(MediaLightbox).exists()).toBe(true)
  })

  it('contributes nothing without the member\'s page around it (a guest\'s page)', async () => {
    const key = await galleryWithOnePhoto()
    mountDetail(key, false)
    await flushPromises()
    expect(searchContribution()).toBeNull()
  })
})
