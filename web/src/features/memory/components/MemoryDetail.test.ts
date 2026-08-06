// @vitest-environment happy-dom
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'

vi.mock('../../../api/client', () => ({
  mediaApi: vi.fn(),
  contentApi: vi.fn(),
  loginApi: vi.fn(),
  tokenApi: vi.fn(),
  sessionApi: vi.fn(),
}))

// exifr drives the sealed capture-time tier; default = no date, tests override per media (cr-0066).
vi.mock('exifr', () => ({ default: { parse: vi.fn(async () => undefined) } }))
// the reverse-geocode seam is mocked — the self-heal that materializes a pending auto name is under test.
// Keep the real defaultLevel/nameAtLevel helpers (the self-heal picks a level from the mocked hierarchy).
vi.mock('../geocode', async (orig) => ({ ...(await orig<typeof import('../geocode')>()), reverseGeocode: vi.fn() }))

import { mount, flushPromises } from '@vue/test-utils'
import exifr from 'exifr'
import { createI18n } from 'vue-i18n'
import PrimeVue from 'primevue/config'
import MemoryDetail from './MemoryDetail.vue'
import { addMedia, type MediaDeriver } from '../media'
import { sealMemoryDate, unsealMemoryDate, type MemoryDate } from '../memoryDate'
import { unsealMemoryLocation, type MemoryLocation } from '../memoryLocation'
import { sealNodeMeta } from '../nodeMeta'
import { setMapsConsent, setGeocoderUrl } from '../../../shared/state/useMapConfig'
import { reverseGeocode } from '../geocode'
import { epochMsToIsoDate } from '../../../shared/isoDate'
import { mediaApi, contentApi } from '../../../api/client'
import * as v1 from '../../../shared/crypto/v1'
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
      common: { save: 'Save', cancel: 'Cancel' },
      memory: {
        favorite: 'Favorite', unfavorite: 'Remove favorite', rename: 'Rename', delete: 'Delete', addChapter: 'Add chapter', chapters: 'Chapters', gallery: 'Gallery', noChapters: 'No chapters yet.',
        date: {
          title: 'Date', edit: 'Edit date', noDate: 'No date yet', since: 'Since {day}', autoHint: 'Derived from your photos.', autoEmpty: 'Add dated photos, or set a date manually.',
          day: 'Day', from: 'From', to: 'To', start: 'Start', invalidRange: 'The end can’t be before the start.', saveFailed: 'Could not save the date.',
          mode: { auto: 'Automatic', single: 'Single day', range: 'Range', ongoing: 'Ongoing' },
        },
        place: {
          title: 'Location', edit: 'Edit location', set: 'Set location', noPlace: 'No location yet', name: 'Name',
          pending: 'Location pending…', none: 'No location detected', remove: 'Remove location', consentTitle: 'Enable maps?', consentBody: 'Third-party tiles + geocoder.', consentEnable: 'Enable maps',
        },
      },
      media: {
        upload: 'Upload',
        information: 'Details',
        toggleInfo: 'Toggle details',
        noDetail: 'No details for this item.',
        captured: 'Taken',
        status: 'Status',
        editCapturedAt: 'Edit capture time',
        resetCapturedAt: 'Reset to original',
        capturedAtCorrected: 'Corrected',
        capturedAtSaveFailed: 'Could not save the capture time.',
        gallery: 'Memory gallery',
        itemCount: 'No items | {count} item | {count} items',
        openItem: 'Open item {index}',
        empty: 'No photos in this Memory yet.',
        unavailable: 'This Memory can’t be opened right now.',
        loading: 'Loading…',
        itemPosition: '{index} of {count}',
        download: 'Download original',
        close: 'Close',
        previous: 'Previous',
        next: 'Next',
        loadError: 'This item couldn’t be loaded.',
        sortNewest: 'Newest first',
        sortOldest: 'Oldest first',
      },
    },
  },
})

const node = (name: string, wrappedMeta: string | null = null): TreeNode => ({
  id: NODE_ID, kind: NodeKind.Memory, name, parentId: null, orderIndex: 0, epoch: 0, wrappedContentKey: null, wrappedMeta,
})

/** A stub deriver: a tiny "JPEG" tagged by the requested maxEdge so each tier's bytes are distinct. */
function stubDeriver(): MediaDeriver {
  return {
    deriveImage: vi.fn(async (_b: Blob, { maxEdge }: { maxEdge: number; quality: number }) =>
      new Uint8Array([0xff, 0xd8, maxEdge & 0xff, (maxEdge >> 8) & 0xff])),
    deriveVideoPoster: vi.fn(async () => new Uint8Array([0xff, 0xd8, 0x07, 0x09])),
  }
}

function mountDetail(memoryKey: Uint8Array, props: Partial<Record<string, unknown>> = {}) {
  return mount(MemoryDetail, {
    props: { node: node('Rom 2026'), scopeId: SCOPE_ID, memoryKey, favorite: false, ...props },
    global: { plugins: [i18n, PrimeVue], directives: { tooltip: {} } },
  })
}

// happy-dom does no layout, so the justified grid (cr-0059) can't measure its container; stub a width + a
// no-op ResizeObserver so the layout produces tiles (the layout math itself is covered in mediaGridLayout.test).
let updateNode: ReturnType<typeof vi.fn>
beforeEach(() => {
  vi.clearAllMocks()
  // default: a no-op content client so the open-time auto-reconcile (cr-0067) never throws; date tests assert on it
  updateNode = vi.fn().mockResolvedValue(undefined)
  vi.mocked(contentApi).mockReturnValue({ updateNode } as unknown as ReturnType<typeof contentApi>)
  vi.spyOn(Element.prototype, 'getBoundingClientRect').mockReturnValue({ width: 960, height: 0, top: 0, left: 0, right: 960, bottom: 0, x: 0, y: 0, toJSON: () => ({}) })
  vi.stubGlobal('ResizeObserver', class { observe() {} unobserve() {} disconnect() {} })
})

/** The MemoryDate sealed into the most recent updateNode PATCH (cr-0067), unsealed under `key` for assertions. */
function lastPatchedDate(key: Uint8Array): MemoryDate | null {
  const calls = updateNode.mock.calls
  if (!calls.length) return null
  const wrappedMeta = calls[calls.length - 1][0].updateNodeRequest.wrappedMeta as string
  return unsealMemoryDate(key, { wrappedMeta })
}
/** The MemoryLocation sealed into the most recent updateNode PATCH (cr-0069), unsealed under `key`. */
function lastPatchedLocation(key: Uint8Array): MemoryLocation | null {
  const calls = updateNode.mock.calls
  if (!calls.length) return null
  const wrappedMeta = calls[calls.length - 1][0].updateNodeRequest.wrappedMeta as string
  return unsealMemoryLocation(key, { wrappedMeta })
}
afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe('MemoryDetail (gallery grid + header)', () => {
  it('lists the Memory media → renders the decrypted title, item count and a thumbnail tile per item', async () => {
    const store = galleryFakeStore()
    vi.mocked(mediaApi).mockReturnValue(store.api as unknown as ReturnType<typeof mediaApi>)
    vi.stubGlobal('fetch', store.fetchStub)
    const memoryKey = v1.randomBytes(32)
    await addMedia(SCOPE_ID, NODE_ID, memoryKey, { bytes: new Uint8Array(8).fill(1), mediaType: 'image/png' }, stubDeriver())
    await addMedia(SCOPE_ID, NODE_ID, memoryKey, { bytes: new Uint8Array(8).fill(2), mediaType: 'image/jpeg' }, stubDeriver())

    const wrapper = mountDetail(memoryKey)
    await flushPromises()
    await flushPromises()

    expect(wrapper.find('.gallery-title').text()).toBe('Rom 2026')
    expect(wrapper.find('.gallery-count').text()).toBe('2 items')
    // two derivable items → two <img> thumbnail tiles, each backed by a decrypted object URL
    const imgs = wrapper.findAll('.media-tile img')
    expect(imgs).toHaveLength(2)
    expect(imgs[0].attributes('src')).toBeTruthy()
    // operator-blind: each thumbnail came back through a presigned GET + on-device decrypt
    expect(store.api.listNodeMedia).toHaveBeenCalledWith({ scopeId: SCOPE_ID, nodeId: NODE_ID })
    expect(store.api.presignBlobDownloads).toHaveBeenCalled()
  })

  it('draws a play badge only on a video tile — from the sealed mediaType (#104/#105)', async () => {
    const store = galleryFakeStore()
    vi.mocked(mediaApi).mockReturnValue(store.api as unknown as ReturnType<typeof mediaApi>)
    vi.stubGlobal('fetch', store.fetchStub)
    const memoryKey = v1.randomBytes(32)
    await addMedia(SCOPE_ID, NODE_ID, memoryKey, { bytes: new Uint8Array(8).fill(1), mediaType: 'image/png' }, stubDeriver())
    await addMedia(SCOPE_ID, NODE_ID, memoryKey, { bytes: new Uint8Array(8).fill(2), mediaType: 'video/mp4' }, stubDeriver())

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
    vi.mocked(mediaApi).mockReturnValue(store.api as unknown as ReturnType<typeof mediaApi>)
    vi.stubGlobal('fetch', store.fetchStub)
    const memoryKey = v1.randomBytes(32)
    // media-0 = 2020, media-1 = 2022, media-2 = 2021 (creation order); the exifr date drives the capturedAt tier
    vi.mocked(exifr.parse)
      .mockResolvedValueOnce({ DateTimeOriginal: new Date(Date.UTC(2020, 0, 1)) } as never)
      .mockResolvedValueOnce({ DateTimeOriginal: new Date(Date.UTC(2022, 0, 1)) } as never)
      .mockResolvedValueOnce({ DateTimeOriginal: new Date(Date.UTC(2021, 0, 1)) } as never)
    await addMedia(SCOPE_ID, NODE_ID, memoryKey, { bytes: new Uint8Array(8).fill(1), mediaType: 'image/jpeg' }, stubDeriver())
    await addMedia(SCOPE_ID, NODE_ID, memoryKey, { bytes: new Uint8Array(8).fill(2), mediaType: 'image/jpeg' }, stubDeriver())
    await addMedia(SCOPE_ID, NODE_ID, memoryKey, { bytes: new Uint8Array(8).fill(3), mediaType: 'image/jpeg' }, stubDeriver())

    const wrapper = mountDetail(memoryKey)
    await flushPromises()
    await flushPromises()

    const order = () => wrapper.findAll('.media-tile').map((t) => t.attributes('data-media-id'))
    // newest first (default): 2022 (media-1), 2021 (media-2), 2020 (media-0)
    expect(order()).toEqual(['media-1', 'media-2', 'media-0'])

    // toggle → oldest first
    await wrapper.find('.gallery-sort-toggle').trigger('click')
    await flushPromises()
    expect(order()).toEqual(['media-0', 'media-2', 'media-1'])
  })

  it('a lightbox date correction persists on close + reopen — no stale date (cr-0066 regression guard)', async () => {
    const store = galleryFakeStore()
    vi.mocked(mediaApi).mockReturnValue(store.api as unknown as ReturnType<typeof mediaApi>)
    vi.stubGlobal('fetch', store.fetchStub)
    const memoryKey = v1.randomBytes(32)
    vi.mocked(exifr.parse).mockResolvedValue({ DateTimeOriginal: new Date(Date.UTC(2021, 4, 1, 10, 0, 0)) } as never)
    await addMedia(SCOPE_ID, NODE_ID, memoryKey, { bytes: new Uint8Array(8).fill(1), mediaType: 'image/jpeg' }, stubDeriver())

    const wrapper = mountDetail(memoryKey)
    await flushPromises()
    await flushPromises()

    // open the lightbox, correct the capture time
    await wrapper.find('.media-tile-open').trigger('click')
    await flushPromises()
    expect(wrapper.text()).not.toContain('Corrected')
    await wrapper.find('[aria-label="Edit capture time"]').trigger('click')
    await wrapper.find('#captured-at-input').setValue('2019-08-15T08:30')
    await wrapper.find('.media-lightbox-date-form').trigger('submit')
    await flushPromises()
    expect(wrapper.text()).toContain('Corrected')

    // close the lightbox
    await wrapper.find('.media-lightbox [aria-label="Close"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('.media-lightbox').exists()).toBe(false)

    // reopen the SAME tile → the correction persisted (the grid item was patched, not just a transient sort key).
    // The regression: an in-lightbox edit updated only the sort order, so reopening unsealed the stale tier.
    await wrapper.find('.media-tile-open').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('Corrected')
  })

  it('shows the empty state for a Memory with no media', async () => {
    const store = galleryFakeStore()
    vi.mocked(mediaApi).mockReturnValue(store.api as unknown as ReturnType<typeof mediaApi>)
    vi.stubGlobal('fetch', store.fetchStub)

    const wrapper = mountDetail(v1.randomBytes(32))
    await flushPromises()

    expect(wrapper.find('.veyq-empty').text()).toBe('No photos in this Memory yet.')
    expect(wrapper.findAll('.media-tile')).toHaveLength(0)
  })

  it('renders a non-image/video item (E1) as an icon tile, fetching no thumbnail for it', async () => {
    const store = galleryFakeStore()
    vi.mocked(mediaApi).mockReturnValue(store.api as unknown as ReturnType<typeof mediaApi>)
    vi.stubGlobal('fetch', store.fetchStub)
    const memoryKey = v1.randomBytes(32)
    await addMedia(SCOPE_ID, NODE_ID, memoryKey, { bytes: new Uint8Array([1, 2, 3]), mediaType: 'application/pdf' }, stubDeriver())

    const wrapper = mountDetail(memoryKey)
    await flushPromises()
    await flushPromises()

    expect(wrapper.findAll('.media-tile')).toHaveLength(1)
    expect(wrapper.find('.media-tile img').exists()).toBe(false)
    expect(wrapper.find('.media-tile .media-grid-icon').exists()).toBe(true)
    // a non-derivable item is never fetched (no preview/thumbnail tier exists)
    expect(store.api.presignBlobDownloads).not.toHaveBeenCalled()
  })

  it('shows the unavailable state when K_mem is absent (E2: locked / not openable)', async () => {
    const wrapper = mountDetail(null as unknown as Uint8Array, { memoryKey: null })
    await flushPromises()
    expect(wrapper.find('.veyq-empty').text()).toBe('This Memory can’t be opened right now.')
  })

  it('renders the Memory’s chapters and emits open/create (cr-0060)', async () => {
    const store = galleryFakeStore()
    vi.mocked(mediaApi).mockReturnValue(store.api as unknown as ReturnType<typeof mediaApi>)
    vi.stubGlobal('fetch', store.fetchStub)
    const chapters: TreeNode[] = [
      { id: 'c1', kind: NodeKind.Chapter, name: 'Day 1', parentId: NODE_ID, orderIndex: 0, epoch: 0, wrappedContentKey: 'd3JhcA==', wrappedMeta: null },
    ]

    const wrapper = mountDetail(v1.randomBytes(32), { chapters })
    await flushPromises()

    // switch to the Chapters tab (the gallery is the default tab)
    const chaptersTab = wrapper.findAll('[role="tab"]').find((t) => t.text().includes('Chapters'))
    expect(chaptersTab).toBeTruthy()
    await chaptersTab!.trigger('click')
    await flushPromises()

    // the chapter shows as a MemoryCard (not a chip, cr-0062), open-only (no favorite pin)
    const card = wrapper.find('.memory-card')
    expect(card.exists()).toBe(true)
    expect(card.text()).toContain('Day 1')
    expect(card.find('.memory-card-pin').exists()).toBe(false) // favoritable=false
    // clicking the card asks the host to open that chapter
    await card.find('.memory-card-main').trigger('click')
    expect(wrapper.emitted('openChapter')?.[0]?.[0]).toMatchObject({ id: 'c1' })

    // the create card asks the host to start creation
    await wrapper.find('.memory-create-card').trigger('click')
    expect(wrapper.emitted('createChapter')).toBeTruthy()
  })

  it('opens the lightbox when a tile is clicked', async () => {
    const store = galleryFakeStore()
    vi.mocked(mediaApi).mockReturnValue(store.api as unknown as ReturnType<typeof mediaApi>)
    vi.stubGlobal('fetch', store.fetchStub)
    const memoryKey = v1.randomBytes(32)
    await addMedia(SCOPE_ID, NODE_ID, memoryKey, { bytes: new Uint8Array(8).fill(1), mediaType: 'image/png' }, stubDeriver())

    const wrapper = mountDetail(memoryKey)
    await flushPromises()
    await flushPromises()

    expect(wrapper.find('.media-lightbox').exists()).toBe(false)
    await wrapper.find('.media-tile-open').trigger('click')
    await flushPromises()
    expect(wrapper.find('.media-lightbox').exists()).toBe(true)
  })
})

describe('MemoryDetail (Memory date — header + auto-reconcile, cr-0067)', () => {
  /** Add one EXIF-dated media (trustworthy source) at `epoch`, into a fresh gallery store. */
  async function seedDatedMedia(memoryKey: Uint8Array, epoch: number) {
    const store = galleryFakeStore()
    vi.mocked(mediaApi).mockReturnValue(store.api as unknown as ReturnType<typeof mediaApi>)
    vi.stubGlobal('fetch', store.fetchStub)
    vi.mocked(exifr.parse).mockResolvedValue({ DateTimeOriginal: new Date(epoch) } as never)
    await addMedia(SCOPE_ID, NODE_ID, memoryKey, { bytes: new Uint8Array(8).fill(1), mediaType: 'image/jpeg' }, stubDeriver())
    return store
  }
  const emptyStore = () => {
    const store = galleryFakeStore()
    vi.mocked(mediaApi).mockReturnValue(store.api as unknown as ReturnType<typeof mediaApi>)
    vi.stubGlobal('fetch', store.fetchStub)
    return store
  }

  it('shows the neutral “no date yet” chip for a Memory with no trustworthy date', async () => {
    emptyStore()
    const wrapper = mountDetail(v1.randomBytes(32))
    await flushPromises()
    expect(wrapper.find('.memory-date-chip').text()).toContain('No date yet')
    expect(updateNode).not.toHaveBeenCalled() // absent meta already == empty auto; nothing to seal
  })

  it('renders a sealed manual single date in the header chip (locale-formatted)', async () => {
    emptyStore()
    const memoryKey = v1.randomBytes(32)
    const sealed = sealMemoryDate(memoryKey, { mode: 'single', from: '2026-06-15', to: '2026-06-15' })
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
    const stale = sealMemoryDate(memoryKey, { mode: 'auto', from: '2000-01-01', to: '2000-01-02' })
    mountDetail(memoryKey, { node: node('Rom 2026', stale) })
    await flushPromises()
    await flushPromises()
    const day = epochMsToIsoDate(epoch)
    expect(lastPatchedDate(memoryKey)).toEqual({ mode: 'auto', from: day, to: day })
  })

  it('does not overwrite a manual date when reconciling on open', async () => {
    const memoryKey = v1.randomBytes(32)
    await seedDatedMedia(memoryKey, Date.UTC(2021, 4, 1))
    const manual = sealMemoryDate(memoryKey, { mode: 'single', from: '2026-06-15', to: '2026-06-15' })
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
      props: { node: node('Rom 2026'), scopeId: SCOPE_ID, memoryKey, favorite: false },
      global: { plugins: [i18n, PrimeVue], directives: { tooltip: {} }, stubs: { MemoryDateEditor: DateEditorStub } },
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
    vi.mocked(mediaApi).mockReturnValue(store.api as unknown as ReturnType<typeof mediaApi>)
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
      props: { node: node('Rom 2026', wrappedMeta), scopeId: SCOPE_ID, memoryKey, favorite: false },
      global: { plugins: [i18n, PrimeVue], directives: { tooltip: {} }, stubs: { MemoryLocationEditor: LocationEditorStub } },
    })
  }

  it('shows a “Set location” affordance when the Memory has no place', async () => {
    emptyStore()
    const wrapper = mountWithLocationStub(v1.randomBytes(32), null)
    await flushPromises()
    expect(wrapper.find('.memory-location-chip').text()).toContain('Set location')
  })

  it('renders the stored location name in the chip', async () => {
    emptyStore()
    const memoryKey = v1.randomBytes(32)
    const sealed = sealNodeMeta(memoryKey, { v: 1, location: GEOCODED })
    const wrapper = mountWithLocationStub(memoryKey, sealed)
    await flushPromises()
    expect(wrapper.find('.memory-location-chip').text()).toContain('Teneriffa')
  })

  it('saving a location read-modify-writes the blob — the sibling date is preserved', async () => {
    emptyStore()
    const memoryKey = v1.randomBytes(32)
    const date: MemoryDate = { mode: 'single', from: '2026-06-15', to: '2026-06-15' }
    const sealed = sealNodeMeta(memoryKey, { v: 1, date }) // node already has a manual date
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
    const sealed = sealNodeMeta(memoryKey, { v: 1, date, location: GEOCODED })
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
    const sealed = sealNodeMeta(memoryKey, { v: 1, location: pending })

    const store = galleryFakeStore()
    vi.mocked(mediaApi).mockReturnValue(store.api as unknown as ReturnType<typeof mediaApi>)
    vi.stubGlobal('fetch', store.fetchStub)
    vi.mocked(exifr.parse).mockResolvedValue({ latitude: 48.85, longitude: 2.35 } as never)
    await addMedia(SCOPE_ID, NODE_ID, memoryKey, { bytes: new Uint8Array(8).fill(1), mediaType: 'image/jpeg' }, stubDeriver())

    mountWithLocationStub(memoryKey, sealed)
    await flushPromises()
    await flushPromises()

    // the open-time self-heal filled `derived` from the medoid while keeping the name + auto mode
    expect(lastPatchedLocation(memoryKey)).toEqual({
      name: 'Boulder gym', nameSource: 'manual', derivedMode: 'auto', derived: { lat: 48.85, lng: 2.35 }, nameAnchor: undefined,
    })
  })

  it('shows “no location detected” for an armed auto location when there is NO geotagged media (nothing to resolve)', async () => {
    emptyStore()
    const memoryKey = v1.randomBytes(32)
    const pending = sealNodeMeta(memoryKey, { v: 1, location: { nameSource: 'geocoded', derivedMode: 'auto' } })
    const wrapper = mountWithLocationStub(memoryKey, pending)
    await flushPromises()
    // No GPS media ⇒ the armed auto place can never resolve, so the chip says so rather than imply an in-progress lookup.
    expect(wrapper.find('.memory-location-chip').text()).toContain('No location detected')
  })

  it('shows the “pending” placeholder for an armed auto location while a geotagged medium exists but no name yet', async () => {
    setMapsConsent(false) // a position to resolve exists, but geocoding is not consented ⇒ the name stays deferred
    const memoryKey = v1.randomBytes(32)
    const pending = sealNodeMeta(memoryKey, { v: 1, location: { nameSource: 'geocoded', derivedMode: 'auto' } })
    const store = galleryFakeStore()
    vi.mocked(mediaApi).mockReturnValue(store.api as unknown as ReturnType<typeof mediaApi>)
    vi.stubGlobal('fetch', store.fetchStub)
    vi.mocked(exifr.parse).mockResolvedValue({ latitude: 48.85, longitude: 2.35 } as never)
    await addMedia(SCOPE_ID, NODE_ID, memoryKey, { bytes: new Uint8Array(8).fill(1), mediaType: 'image/jpeg' }, stubDeriver())
    const wrapper = mountWithLocationStub(memoryKey, pending)
    await flushPromises()
    await flushPromises()
    expect(wrapper.find('.memory-location-chip').text()).toContain('Location pending')
  })

  it('materializes a pending auto location — geocodes the medoid name on the first geotagged media (self-heal)', async () => {
    setMapsConsent(true)
    setGeocoderUrl('https://nominatim.example')
    vi.mocked(reverseGeocode).mockResolvedValue([{ key: 'city', name: 'La Orotava' }, { key: 'state', name: 'Kanaren' }])
    const memoryKey = v1.randomBytes(32)
    const pending = sealNodeMeta(memoryKey, { v: 1, location: { nameSource: 'geocoded', derivedMode: 'auto' } })

    const store = galleryFakeStore()
    vi.mocked(mediaApi).mockReturnValue(store.api as unknown as ReturnType<typeof mediaApi>)
    vi.stubGlobal('fetch', store.fetchStub)
    vi.mocked(exifr.parse).mockResolvedValue({ latitude: 48.85, longitude: 2.35 } as never)
    await addMedia(SCOPE_ID, NODE_ID, memoryKey, { bytes: new Uint8Array(8).fill(1), mediaType: 'image/jpeg' }, stubDeriver())

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
      props: { node: node('Rom 2026'), scopeId: SCOPE_ID, memoryKey: v1.randomBytes(32), favorite: false },
      global: { plugins: [i18n, PrimeVue], directives: { tooltip: {} }, stubs: { MemoryLocationEditor: LocationEditorStub, Dialog: DialogStub } },
    })
    await flushPromises()
    await wrapper.find('.memory-location-chip').trigger('click')
    await flushPromises()
    expect(wrapper.find('.loc-editor').exists()).toBe(false) // no editor
    expect(wrapper.find('.dlg').text()).toContain('Third-party tiles') // the consent dialog body
  })
})
