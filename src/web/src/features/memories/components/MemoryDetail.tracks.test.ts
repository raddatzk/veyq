// @vitest-environment happy-dom
// uc.memory.tracks (ADR 0130) through the member's whole detail: a GPX file stored as a medium of the Memory - sealed and
// uploaded by the real upload code into the in-memory store, listed back through the member's source - is no photo (not
// in the gallery, not in the count) and is the Map tab's track; the tab's add sends a GPX file through the one upload queue
// as a GPX-typed medium of the node on screen, grouped apart from the gallery's tiles; its removal is a photo's trash.
import { describe, it, expect, vi, beforeEach, afterEach, type Mock } from 'vitest'

vi.mock('leaflet', () => ({ default: {} }))
vi.mock('../../../api/client', () => ({ blobApi: vi.fn(), contentApi: vi.fn(), loginApi: vi.fn(), tokenApi: vi.fn(), sessionApi: vi.fn() }))
vi.mock('../api', () => ({ mediaApi: vi.fn(), itemFavoritesApi: vi.fn(), ownItemFavoritesApi: vi.fn() }))
vi.mock('../uploadQueue', async (orig) => ({ ...(await orig<typeof import('../uploadQueue')>()), enqueueUploads: vi.fn() }))

import { flushPromises } from '@vue/test-utils'
import * as v1 from '@veyq/crypto/v1'
import MemoryDetail from './MemoryDetail.vue'
import { memberMemorySource } from '../source'
import { addMedia, type MediaDeriver } from '../media'
import { MEMORY_KIND } from '../kinds'
import { blobApi, contentApi } from '../../../api/client'
import { mediaApi, itemFavoritesApi } from '../api'
import { enqueueUploads } from '../uploadQueue'
import { fakeApi, stubApi } from '../../../test/apiMock'
import type { ContentApi } from '../../../api/generated/content'
import type { MediaApi } from '../../../api/generated/media'
import { galleryFakeStore } from './galleryFakeStore'
import { realI18n, t } from '../../../test/i18n'
import { treeNode, memberMemoryRights } from '../../../test/models'
import { mountWithHeader } from '../../../test/nodeHeader'
import { coreActionsStandIn, coverStandIn } from '../../../test/nodePage'
import { setMapsConsent } from '../../../shared/state/useMapConfig'
import { GPX_MEDIA_TYPE } from '../track/gpx'

const SCOPE_ID = '11111111-1111-1111-1111-111111111111'
const NODE_ID = '22222222-2222-2222-2222-222222222222'
const GPX = '<gpx><trk><name>Summit</name><trkseg><trkpt lat="47" lon="11"/><trkpt lat="47.01" lon="11.01"/></trkseg></trk></gpx>'
const deriver: MediaDeriver = {
  deriveImage: vi.fn(async () => ({ bytes: new Uint8Array([0xff, 0xd8, 1, 2]), source: null })),
  deriveVideoPoster: vi.fn(async () => new Uint8Array([0xff, 0xd8, 3, 4])),
}

let deleteMedia: Mock<MediaApi['deleteMedia']>
beforeEach(() => {
  vi.clearAllMocks()
  setMapsConsent(false) // the list works without the map (decision 6); the map is MemoryTracks' own test
  fakeApi(contentApi, { updateNode: vi.fn(async () => ({ metaRevision: 1 })) as unknown as Mock<ContentApi['updateNode']> })
  stubApi(itemFavoritesApi, { getItemFavorites: [] })
  const store = galleryFakeStore()
  deleteMedia = vi.fn<MediaApi['deleteMedia']>(async () => undefined)
  fakeApi(mediaApi, { ...store.api, deleteMedia })
  fakeApi(blobApi, store.blob)
  vi.stubGlobal('fetch', store.fetchStub)
  vi.spyOn(Element.prototype, 'getBoundingClientRect').mockReturnValue({ width: 960, height: 0, top: 0, left: 0, right: 960, bottom: 0, x: 0, y: 0, toJSON: () => ({}) })
  vi.stubGlobal('ResizeObserver', class { observe() {} unobserve() {} disconnect() {} })
})
afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

async function mountWithOneTrackAndOnePhoto() {
  const memoryKey = v1.randomBytes(32)
  await addMedia(SCOPE_ID, NODE_ID, memoryKey, { blob: new Blob([new Uint8Array(8).fill(1) as BlobPart]), mediaType: 'image/jpeg', name: 'IMG_1.jpg' }, deriver)
  await addMedia(SCOPE_ID, NODE_ID, memoryKey, { blob: new Blob([GPX]), mediaType: GPX_MEDIA_TYPE, name: 'summit.gpx' }, deriver)
  const w = mountWithHeader(MemoryDetail, {
    props: {
      source: memberMemorySource({ scopeId: () => SCOPE_ID }), node: treeNode({ id: NODE_ID, kind: MEMORY_KIND, name: 'Alps', parentId: null }),
      scopeId: SCOPE_ID, memoryKey, scopeKey: { epoch: 0, key: v1.randomBytes(32) }, chapters: [], rights: memberMemoryRights(), canReport: true,
    },
    global: { plugins: [realI18n()], provide: coreActionsStandIn([], coverStandIn({ canSet: false })) },
    attachTo: document.body,
  })
  await flushPromises()
  await flushPromises()
  return w
}
const mapTab = (w: Awaited<ReturnType<typeof mountWithOneTrackAndOnePhoto>>) => w.find('[data-testid="memory-tab-map"]')

describe('MemoryDetail — GPX tracks (uc.memory.tracks)', () => {
  it('keeps a track out of the gallery and its count, and lists it on the Map tab by its own name', async () => {
    const w = await mountWithOneTrackAndOnePhoto()
    expect(w.findAll('.media-tile')).toHaveLength(1)
    expect(w.text()).toContain(t('media.itemCount', { count: 1 }, 1))
    expect(mapTab(w).text()).toContain('1')

    await mapTab(w).trigger('mousedown') // Reka's tabs switch on the press
    await flushPromises()
    await flushPromises()
    const rows = w.findAll('[data-testid="track-row"]')
    expect(rows).toHaveLength(1)
    expect(rows[0].find('.memory-track-name').text()).toBe('Summit')
    expect(rows[0].find('.memory-track-facts').text()).toMatch(/km/)
    w.unmount()
  })

  it('removes a track as a photo is removed: into the media trash, with Undo', async () => {
    const w = await mountWithOneTrackAndOnePhoto()
    await mapTab(w).trigger('mousedown')
    await flushPromises()
    await w.find('[data-testid="track-more"]').trigger('click')
    await flushPromises()
    const remove = Array.from(document.querySelectorAll('[role="menuitem"]')).find((el) => el.textContent?.includes(t('memory.tracks.remove')))
    expect(remove, 'the row menu offers the removal').toBeDefined()
    ;(remove as HTMLElement).click()
    await flushPromises()
    expect(deleteMedia).toHaveBeenCalledTimes(1)
    expect(deleteMedia.mock.calls[0][0]).toMatchObject({ scopeId: SCOPE_ID })
    expect(w.findAll('[data-testid="track-row"]')).toHaveLength(0)
    w.unmount()
  })

  it('sends an added GPX file through the upload queue as a GPX medium of this node, apart from the gallery tiles', async () => {
    const w = await mountWithOneTrackAndOnePhoto()
    await mapTab(w).trigger('mousedown')
    await flushPromises()
    const input = w.find('[data-testid="track-file-input"]')
    Object.defineProperty(input.element, 'files', { value: [new File([GPX], 'second.gpx')], configurable: true })
    await input.trigger('change')
    await flushPromises()
    expect(enqueueUploads).toHaveBeenCalledTimes(1)
    const [target, selections] = vi.mocked(enqueueUploads).mock.calls[0]
    expect(target.nodeId).toBe(NODE_ID)
    expect(target.key).toBe(`${NODE_ID}#tracks`)
    expect(selections.map((s) => [s.file.name, s.mediaType])).toEqual([['second.gpx', GPX_MEDIA_TYPE]])
    w.unmount()
  })
})
