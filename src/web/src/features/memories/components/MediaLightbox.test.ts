// @vitest-environment happy-dom
import { audioPlayer } from '../../../core/audio/player'
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { createRouter, createMemoryHistory } from 'vue-router'

vi.mock('../../../api/client', () => ({
  blobApi: vi.fn(),
  loginApi: vi.fn(),
  tokenApi: vi.fn(),
  sessionApi: vi.fn(),
}))
vi.mock('../api', () => ({ mediaApi: vi.fn() }))

// The EXIF reader (`shared/image/exifTags`) does the real parse in the browser; here we drive it so the sealed EXIF tier is deterministic.
vi.mock('../../../shared/image/exifTags', () => ({ readExifTags: vi.fn(async () => undefined) }))

// The map/geocoding consent gate (uc.memory.place, ADR 0016). Default: consented WITH a geocoder, so the
// place-name path is exercised; the consent bite flips `geocodingAvailable` off and asserts silence.
const mapConfig = vi.hoisted(() => ({ maps: true, geocoder: 'https://geo.example' as string | null }))
vi.mock('../../../shared/state/useMapConfig', async () => {
  const { computed } = await import('vue')
  return {
    useMapConfig: () => ({
      mapsAvailable: computed(() => mapConfig.maps),
      tileUrl: computed(() => 'https://tiles.example/{z}/{x}/{y}.png'),
      tileAttribution: computed(() => '© OSM'),
      tileMaxZoom: computed(() => 19),
      geocoderUrl: computed(() => mapConfig.geocoder),
      geocodingAvailable: computed(() => mapConfig.maps && Boolean(mapConfig.geocoder)),
    }),
  }
})

// The SW registrar (there is no real Service Worker in the test env). Default: no usable SW → every play
// takes the whole-blob fallback; individual tests override registerPlayback to simulate a controlling SW.
vi.mock('../../../sw/register', () => ({
  registerPlayback: vi.fn(async () => null),
  revokePlayback: vi.fn(),
  handleFromUrl: (url: string) => url.replace('/__media/', ''),
  // No SW download route either: a download streams into a picked file when a test stubs `showSaveFilePicker`, else
  // into the capped in-memory Blob (`shared/saveStream.ts`, inbox #371).
  registerDownload: vi.fn(async () => null),
}))

// A share guest's tier reads (the zoom's guest case): recorded at the share helpers, the link's gated presign behind them.
vi.mock('../share', async (orig) => ({ ...(await orig<typeof import('../share')>()), openShareLightbox: vi.fn(), openShareOriginal: vi.fn() }))

// The toast channel (the layer's `shared/ui/toast.ts`, mocked here at `useToast`): the too-large download reports through it.
// A selection's capture-time shift reports through it too: its refusals, and its Undo bar (`showUndo`, captured to press it).
const { toastError, showUndo } = vi.hoisted(() => ({ toastError: vi.fn(), showUndo: vi.fn() }))
vi.mock('../../../shared/state/useToast', () => ({
  useToast: () => ({ success: vi.fn(), error: toastError, showUndo, dismissUndo: vi.fn() }),
}))

import { reactive, defineComponent, h } from 'vue'
import { mount, flushPromises } from '@vue/test-utils'
import MediaLightbox from './MediaLightbox.vue'
import VeyqDateField from '../../../shared/ui/VeyqDateField.vue'
import { addMedia, listMedia, unsealMeta, type MediaDeriver, type MediaExif, type MediaItem } from '../media'
import { useCaptureTimeShift } from '../useCaptureTimeShift'
import { readExifTags, type ExifTags } from '../../../shared/image/exifTags'
import { blobApi } from '../../../api/client'
import { mediaApi } from '../api'
import { registerPlayback, revokePlayback } from '../../../sw/register'
import * as v1 from '@veyq/crypto/v1'
import { b64std } from '@veyq/crypto/base64'
import { galleryFakeStore } from './galleryFakeStore'
import { fakeApi } from '../../../test/apiMock'
import { resetPlaceNameCache } from '../placeName'
import { realI18n, t } from '../../../test/i18n'
import { memberMemorySource, guestMemorySource } from '../source'
import { openShareLightbox, openShareOriginal } from '../share'
import { ZOOM_ORIGINAL_MAX_BYTES } from '../lightboxZoom'
import { ShareRole } from '../../../api/generated/sharing'
import type { PublicShareHead } from '../../../core/sharing/resolvePublicShare'
import { guestAccessFor } from '../../../test/models'

const SCOPE_ID = '11111111-1111-1111-1111-111111111111'
const NODE_ID = '22222222-2222-2222-2222-222222222222'

function stubDeriver(): MediaDeriver {
  return {
    deriveImage: vi.fn(async (_b: Blob, { maxEdge }: { maxEdge: number; quality: number }) => ({
      bytes: new Uint8Array([0xff, 0xd8, maxEdge & 0xff, (maxEdge >> 8) & 0xff]),
      source: null,
    })),
    deriveVideoPoster: vi.fn(async () => new Uint8Array([0xff, 0xd8, 0x07, 0x09])),
  }
}

/** Seed two image media and return the listed items + the live fake store. */
async function seedTwo(memoryKey: Uint8Array) {
  const store = galleryFakeStore()
  fakeApi(mediaApi, store.api)
  fakeApi(blobApi, store.blob)
  vi.stubGlobal('fetch', store.fetchStub)
  await addMedia(SCOPE_ID, NODE_ID, memoryKey, { blob: new Blob([new Uint8Array(8).fill(1) as BlobPart]), mediaType: 'image/png' }, stubDeriver())
  await addMedia(SCOPE_ID, NODE_ID, memoryKey, { blob: new Blob([new Uint8Array(8).fill(2) as BlobPart]), mediaType: 'image/jpeg' }, stubDeriver())
  const items = await listMedia(SCOPE_ID, NODE_ID)
  return { store, items }
}

/** Stub the File System Access picker and collect every name it is offered — the download's name, before any byte. */
function pickerNames(): string[] {
  const names: string[] = []
  vi.stubGlobal('showSaveFilePicker', vi.fn(async (o?: { suggestedName?: string }) => {
    names.push(o?.suggestedName ?? '')
    return { createWritable: async () => ({ write: async () => {}, close: async () => {}, abort: async () => {} }) }
  }))
  return names
}

/** What `MemoryDetail` hands the lightbox for a MEMBER: the member's source's loaders under the node's key, its media-meta
 *  door, every write offered (the lightbox has no default of its own, inbox #401 S5). */
function memberProps(memoryKey: Uint8Array) {
  const source = memberMemorySource({ scopeId: () => SCOPE_ID })
  const l = source.loaders
  return {
    canDelete: () => true,
    previewLoader: (it: MediaItem) => l.preview(it, memoryKey),
    originalLoader: (it: MediaItem) => l.original(it, memoryKey),
    videoChunkProvider: (it: MediaItem) => l.chunk(it, memoryKey),
    metaResolver: (it: MediaItem) => l.meta(it, memoryKey),
    blobMetaResolver: (it: MediaItem) => l.blobMeta(it, memoryKey),
    exifLoader: (it: MediaItem) => l.exif!(it, memoryKey),
    mediaMeta: source.mediaMeta,
    memoryNode: { id: NODE_ID, epoch: 0 },
  }
}
/** Seed `n` image media and return the listed items + the live fake store. */
async function seedImages(memoryKey: Uint8Array, n: number) {
  const store = galleryFakeStore()
  fakeApi(mediaApi, store.api)
  fakeApi(blobApi, store.blob)
  vi.stubGlobal('fetch', store.fetchStub)
  for (let i = 0; i < n; i++) {
    await addMedia(SCOPE_ID, NODE_ID, memoryKey, { blob: new Blob([new Uint8Array(8).fill(i + 1) as BlobPart]), mediaType: 'image/png' }, stubDeriver())
  }
  return { store, items: await listMedia(SCOPE_ID, NODE_ID) }
}

/** The member's props with the preview loader recorded, and how often it read a medium's preview. */
function recordedPreview(memoryKey: Uint8Array) {
  const p = memberProps(memoryKey)
  const previewLoader = vi.fn(p.previewLoader)
  const readsOf = (item: MediaItem) => previewLoader.mock.calls.filter(([it]) => it.mediaId === item.mediaId).length
  return { ...p, previewLoader, readsOf }
}

/** What a share guest's source leaves out: no media-meta door, no EXIF, no delete of this item. */
const GUEST_WRITES = { mediaMeta: undefined, exifLoader: undefined, canDelete: () => false }

function mountLightbox(
  memoryKey: Uint8Array,
  items: MediaItem[],
  startIndex = 0,
  extraProps: Record<string, unknown> = {},
) {
  return mount(MediaLightbox, {
    props: { memoryKey, items, startIndex, memoryName: 'Rom 2026', ...memberProps(memoryKey), ...extraProps },
    global: { plugins: [realI18n()] },
  })
}

beforeEach(() => vi.clearAllMocks())
afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('MediaLightbox (preview, prev/next, download)', () => {
  it('fetches the PREVIEW tier (not the original) for the stage and shows it', async () => {
    const memoryKey = v1.randomBytes(32)
    const { store, items } = await seedTwo(memoryKey)
    store.blob.presignBlobDownloads.mockClear()

    const wrapper = mountLightbox(memoryKey, items)
    await flushPromises()

    // media 0: original=up-0, THUMBNAIL=up-1, PREVIEW=up-2 → the lightbox opens the PREVIEW, never the original
    const presigned = store.blob.presignBlobDownloads.mock.calls.flatMap(
      (c) => c[0].blobDownloadsBatchRequest.items.map((it) => it.blobId),
    )
    expect(presigned).toContain(store.blobIdOfUpload('up-2'))
    expect(presigned).not.toContain(store.blobIdOfUpload('up-0'))
    expect(wrapper.find('.media-lightbox-stage img').attributes('src')).toBeTruthy()
  })

  it('a medium a moderator blocked is the placeholder: no preview read, no download (uc.moderation.take-a-measure)', async () => {
    const memoryKey = v1.randomBytes(32)
    const { store, items } = await seedTwo(memoryKey)
    store.blob.presignBlobDownloads.mockClear()

    const wrapper = mountLightbox(memoryKey, [{ ...items[0], blocked: true }, items[1]])
    await flushPromises()

    expect(wrapper.find('.media-lightbox-blocked').text()).toBe(t('node.blockedByOperator'))
    expect(wrapper.find('.media-lightbox-stage img').exists()).toBe(false)
    expect(wrapper.find(`button[aria-label="${t('media.download')}"]`).exists()).toBe(false)
    const presigned = store.blob.presignBlobDownloads.mock.calls.flatMap((c) => c[0].blobDownloadsBatchRequest.items.map((it) => it.blobId))
    expect(presigned, 'not one byte of the blocked medium is asked for').not.toContain(store.blobIdOfUpload('up-2'))
  })

  // The gallery's live poll adopts a block on the item the lightbox holds, in place (useMediaGalleryLive): the stage turns
  // to the placeholder and the decrypted preview is revoked at once; a lift reads the preview again.
  it('a block that arrives while the medium is open revokes its preview and shows the placeholder; a lift shows it again', async () => {
    const memoryKey = v1.randomBytes(32)
    const { items } = await seedTwo(memoryKey)
    const held = reactive(items.map((it) => ({ ...it }))) // the gallery's items: reactive, patched in place by its poll
    const revoke = vi.spyOn(URL, 'revokeObjectURL')
    const wrapper = mountLightbox(memoryKey, held)
    await flushPromises()
    const shown = wrapper.find('.media-lightbox-stage img').attributes('src')
    expect(shown).toMatch(/^blob:/)

    held[0].blocked = true
    await flushPromises()
    expect(wrapper.find('.media-lightbox-blocked').text()).toBe(t('node.blockedByOperator'))
    expect(wrapper.find('.media-lightbox-stage img').exists()).toBe(false)
    expect(revoke, 'the decrypted preview of a blocked medium is revoked').toHaveBeenCalledWith(shown)

    delete held[0].blocked
    await flushPromises()
    expect(wrapper.find('.media-lightbox-blocked').exists()).toBe(false)
    expect(wrapper.find('.media-lightbox-stage img').attributes('src')).toMatch(/^blob:/)
  })

  // A cover and a banner are set only by an upload in their own dialogs: the lightbox offers neither.
  it('offers no “Set as cover” and no “Use as banner”', async () => {
    const memoryKey = v1.randomBytes(32)
    const { items } = await seedTwo(memoryKey)
    const wrapper = mountLightbox(memoryKey, items, 0)
    await flushPromises()
    const labels = wrapper.findAll('.media-lightbox-actions button').map((b) => b.attributes('aria-label'))
    expect(labels).toContain(t('media.download')) // control: the actions are drawn
    expect(labels.filter((l) => /cover|banner/i.test(l ?? ''))).toEqual([])
    expect(wrapper.emitted('setCover')).toBeFalsy()
  })

  it('pages prev/next through the grid order, reading the next item’s preview ahead, and goes round at the ends', async () => {
    const memoryKey = v1.randomBytes(32)
    const { store, items } = await seedTwo(memoryKey)
    store.blob.presignBlobDownloads.mockClear()
    const presigned = () =>
      store.blob.presignBlobDownloads.mock.calls.flatMap((c) => c[0].blobDownloadsBatchRequest.items.map((it) => it.blobId))

    const wrapper = mountLightbox(memoryKey, items, 0)
    await flushPromises()

    // at the first item both go somewhere: paging goes round (owner 2026-11-26)
    expect(wrapper.find('.lightbox-nav-previous').attributes('disabled')).toBeUndefined()
    expect(wrapper.find('.lightbox-nav-next').attributes('disabled')).toBeUndefined()
    // media 1: PREVIEW=up-5 → read ahead once media 0's preview is shown, never its original (up-3)
    expect(presigned()).toContain(store.blobIdOfUpload('up-5'))
    expect(presigned()).not.toContain(store.blobIdOfUpload('up-3'))

    store.blob.presignBlobDownloads.mockClear()
    await wrapper.find('.lightbox-nav-next').trigger('click')
    await flushPromises()

    // next paged to it, shown from what was read ahead; at the last item next is still offered
    expect(presigned(), 'nothing read again').toEqual([])
    expect(wrapper.find('.media-lightbox-stage img').attributes('src')).toMatch(/^blob:/)
    const position = () => wrapper.find('.media-lightbox-title small').text()
    expect(position()).toBe(t('media.itemPosition', { index: 2, count: 2 }))
    expect(wrapper.find('.lightbox-nav-next').attributes('disabled')).toBeUndefined()

    // past the last comes the first, before the first the last - on the buttons and the arrow keys alike
    await wrapper.find('.lightbox-nav-next').trigger('click')
    await flushPromises()
    expect(position(), 'next on the last goes round to the first').toBe(t('media.itemPosition', { index: 1, count: 2 }))
    await wrapper.find('.lightbox-nav-previous').trigger('click')
    await flushPromises()
    expect(position(), 'previous on the first goes round to the last').toBe(t('media.itemPosition', { index: 2, count: 2 }))
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight' }))
    await flushPromises()
    expect(position(), 'ArrowRight on the last').toBe(t('media.itemPosition', { index: 1, count: 2 }))
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowLeft' }))
    await flushPromises()
    expect(position(), 'ArrowLeft on the first').toBe(t('media.itemPosition', { index: 2, count: 2 }))
    expect(presigned(), 'both were held: going round reads nothing again').toEqual([])
  })

  it('a single item has nowhere to page: both buttons off, no slideshow, the arrow keys stay put', async () => {
    const memoryKey = v1.randomBytes(32)
    const { items } = await seedTwo(memoryKey)
    const wrapper = mountLightbox(memoryKey, [items[0]], 0)
    await flushPromises()
    expect(wrapper.find('.media-lightbox-stage img').attributes('src'), 'control: the item shows').toMatch(/^blob:/)
    expect(wrapper.find('.lightbox-nav-previous').attributes('disabled')).toBeDefined()
    expect(wrapper.find('.lightbox-nav-next').attributes('disabled')).toBeDefined()
    expect(wrapper.find('[data-slideshow-toggle]').exists()).toBe(false)
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight' }))
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowLeft' }))
    await flushPromises()
    expect(wrapper.find('.media-lightbox-title small').text()).toBe(t('media.itemPosition', { index: 1, count: 1 }))
  })

  it('removing the shown last item shows the first (the advance goes round, as paging does)', async () => {
    const memoryKey = v1.randomBytes(32)
    const { items } = await seedImages(memoryKey, 3)
    const held = reactive(items.map((it) => ({ ...it })))
    const wrapper = mountLightbox(memoryKey, held, 2)
    await flushPromises()
    expect(wrapper.find('.media-lightbox-title small').text(), 'control').toBe(t('media.itemPosition', { index: 3, count: 3 }))
    held.splice(2, 1)
    await flushPromises()
    expect(wrapper.find('.media-lightbox-title small').text()).toBe(t('media.itemPosition', { index: 1, count: 2 }))
    expect(wrapper.emitted('close'), 'one is left: it stays open').toBeFalsy()
  })

  // The previews read while the lightbox is open stay in memory (`../previewCache.ts`): a step back is instant, the
  // neighbours of a shown item are read ahead, and a block, a removal or a new key drops what is held.
  describe('the previews it holds while open', () => {
    /** The shown stage: an image, the loading state, or neither yet. */
    const stage = (w: ReturnType<typeof mountLightbox>) => {
      const img = w.find('.media-lightbox-stage img')
      return { src: img.exists() ? img.attributes('src') ?? null : null, loading: w.find('.media-original-loading').exists() }
    }
    const position = (w: ReturnType<typeof mountLightbox>) => w.find('.media-lightbox-title small').text()

    it('a step back shows the item at once: no second read, no loading state', async () => {
      const memoryKey = v1.randomBytes(32)
      const { items } = await seedImages(memoryKey, 3)
      const props = recordedPreview(memoryKey)
      const w = mountLightbox(memoryKey, items, 0, props)
      await flushPromises()
      await w.find('.lightbox-nav-next').trigger('click')
      await flushPromises()
      expect(position(w)).toBe(t('media.itemPosition', { index: 2, count: 3 }))

      // Every node the stage gains on the way back: the loading state must not be among them, not even for a microtask.
      const added: Element[] = []
      let srcChanges = 0
      const observer = new MutationObserver((records) => {
        for (const r of records) {
          if (r.type === 'attributes') srcChanges++
          r.addedNodes.forEach((n) => n instanceof Element && added.push(n))
        }
      })
      observer.observe(w.element, { childList: true, subtree: true, attributes: true, attributeFilter: ['src'] })
      await w.find('.lightbox-nav-previous').trigger('click')
      await flushPromises()
      observer.disconnect()
      expect(position(w)).toBe(t('media.itemPosition', { index: 1, count: 3 }))
      expect(srcChanges + added.filter((n) => n.matches('img')).length, 'control: the observer sees the stage change').toBeGreaterThan(0)
      expect(added.filter((n) => n.matches('.media-original-loading')), 'no loading state').toEqual([])
      expect(stage(w)).toEqual({ src: expect.stringMatching(/^blob:/), loading: false })
      expect(props.readsOf(items[0]), 'read once').toBe(1)
      w.unmount()
    })

    it("reads both neighbours ahead, after the shown item's own preview", async () => {
      const memoryKey = v1.randomBytes(32)
      const { items } = await seedImages(memoryKey, 4)
      const props = recordedPreview(memoryKey)
      const w = mountLightbox(memoryKey, items, 1, props)
      await flushPromises()
      expect(items.map(props.readsOf), 'the shown one and its two neighbours, nothing further').toEqual([1, 1, 1, 0])
      expect(props.previewLoader.mock.calls[0][0].mediaId, 'the shown one first').toBe(items[1].mediaId)
      w.unmount()
    })

    it('reads ahead round the ends: on the last item the first, on the first the last', async () => {
      const memoryKey = v1.randomBytes(32)
      const { items } = await seedImages(memoryKey, 4)
      const last = recordedPreview(memoryKey)
      const w = mountLightbox(memoryKey, items, 3, last)
      await flushPromises()
      expect(items.map(last.readsOf), 'the last, the one before it, and the first').toEqual([1, 0, 1, 1])
      w.unmount()
      const first = recordedPreview(memoryKey)
      const v = mountLightbox(memoryKey, items, 0, first)
      await flushPromises()
      expect(items.map(first.readsOf), 'the first, the one after it, and the last').toEqual([1, 1, 0, 1])
      v.unmount()
    })

    it('reads nothing ahead of a blocked neighbour (every byte of it answers 451)', async () => {
      const memoryKey = v1.randomBytes(32)
      const { items } = await seedImages(memoryKey, 2)
      const props = recordedPreview(memoryKey)
      const w = mountLightbox(memoryKey, [items[0], { ...items[1], blocked: true }], 0, props)
      await flushPromises()
      expect(props.readsOf(items[0]), 'control: the shown one is read').toBe(1)
      expect(props.readsOf(items[1])).toBe(0)
      w.unmount()
    })

    it('a block drops the bytes held for the medium: after the lift it is read again', async () => {
      const memoryKey = v1.randomBytes(32)
      const { items } = await seedImages(memoryKey, 2)
      const held = reactive(items.map((it) => ({ ...it })))
      const props = recordedPreview(memoryKey)
      const w = mountLightbox(memoryKey, held, 0, props)
      await flushPromises()
      expect(props.readsOf(items[1]), 'control: read ahead').toBe(1)

      held[1].blocked = true
      await flushPromises()
      delete held[1].blocked
      await flushPromises()
      await w.find('.lightbox-nav-next').trigger('click')
      expect(stage(w).loading, 'nothing held for it any more').toBe(true)
      await flushPromises()
      expect(props.readsOf(items[1])).toBe(2)
      expect(stage(w).src).toMatch(/^blob:/)
      w.unmount()
    })

    it('a medium that leaves the list loses its bytes: restored, it is read again', async () => {
      const memoryKey = v1.randomBytes(32)
      const { items } = await seedImages(memoryKey, 2)
      const held = reactive(items.map((it) => ({ ...it })))
      const props = recordedPreview(memoryKey)
      const w = mountLightbox(memoryKey, held, 0, props)
      await flushPromises()
      expect(props.readsOf(items[1]), 'control: read ahead').toBe(1)

      const [gone] = held.splice(1, 1) // trashed, then restored by the gallery's poll
      await flushPromises()
      held.push(gone)
      await flushPromises()
      await w.find('.lightbox-nav-next').trigger('click')
      await flushPromises()
      expect(props.readsOf(items[1])).toBe(2)
      w.unmount()
    })

    it('a new key drops everything held under the old one', async () => {
      const memoryKey = v1.randomBytes(32)
      const { items } = await seedImages(memoryKey, 2)
      const props = recordedPreview(memoryKey)
      const w = mountLightbox(memoryKey, items, 0, props)
      await flushPromises()
      expect(props.readsOf(items[1]), 'control: read ahead').toBe(1)

      await w.setProps({ memoryKey: new Uint8Array(memoryKey) }) // the same bytes, another key object: a re-opened chain
      await w.find('.lightbox-nav-next').trigger('click')
      await flushPromises()
      expect(props.readsOf(items[1])).toBe(2)
      w.unmount()
    })
  })

  it('revokes the preview object URL on navigation and on close', async () => {
    const memoryKey = v1.randomBytes(32)
    const { items } = await seedTwo(memoryKey)
    const revoke = vi.spyOn(URL, 'revokeObjectURL')

    const wrapper = mountLightbox(memoryKey, items, 0)
    await flushPromises()

    await wrapper.find('.lightbox-nav-next').trigger('click')
    await flushPromises()
    expect(revoke).toHaveBeenCalled() // the first preview URL is freed before the next loads

    revoke.mockClear()
    wrapper.unmount()
    expect(revoke).toHaveBeenCalled() // the last preview URL is freed on unmount
  })

  it('closes on the close button and on Escape', async () => {
    const memoryKey = v1.randomBytes(32)
    const { items } = await seedTwo(memoryKey)

    const wrapper = mountLightbox(memoryKey, items, 0)
    await flushPromises()

    await wrapper.find(`[aria-label="${t('media.close')}"]`).trigger('click')
    expect(wrapper.emitted('close')).toHaveLength(1)

    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
    expect(wrapper.emitted('close')).toHaveLength(2)
  })

  it('download streams the ORIGINAL, byte for byte, under the name its blob meta type derives', async () => {
    const memoryKey = v1.randomBytes(32)
    const { store, items } = await seedTwo(memoryKey)
    const anchors: HTMLAnchorElement[] = []
    const realCreate = document.createElement.bind(document)
    vi.spyOn(document, 'createElement').mockImplementation((tag: string) => {
      const el = realCreate(tag)
      if (tag === 'a') anchors.push(el as HTMLAnchorElement)
      return el
    })
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
    const saved: Blob[] = []
    vi.spyOn(URL, 'createObjectURL').mockImplementation((b) => (saved.push(b as Blob), 'blob:fake'))
    vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {})

    const wrapper = mountLightbox(memoryKey, items, 0)
    await flushPromises()
    store.blob.presignBlobDownloads.mockClear()
    saved.length = 0 // the preview's own object URL

    await wrapper.find(`[aria-label="${t('media.download')}"]`).trigger('click')
    await flushPromises()

    // the original (up-0), never a derivative, is fetched for download
    const presigned = store.blob.presignBlobDownloads.mock.calls.flatMap(
      (c) => c[0].blobDownloadsBatchRequest.items.map((it) => it.blobId),
    )
    expect(presigned).toEqual([store.blobIdOfUpload('up-0')])
    // seedTwo uploads without a file name, so the fallback names it — its extension from the type sealed in the
    // original's blob meta (image/png); the bytes themselves (8 × 0x01) would sniff as nothing
    expect(anchors.at(-1)!.getAttribute('download')).toBe('Rom 2026 1.png')
    // no streaming target in this env → the capped Blob, holding exactly the original's bytes
    expect(saved).toHaveLength(1)
    expect(new Uint8Array(await saved[0].arrayBuffer())).toEqual(new Uint8Array(8).fill(1))
    expect(toastError).not.toHaveBeenCalled()
  })

  it('opens the save picker synchronously in the click when the sealed type names the file — nothing awaited first', async () => {
    const memoryKey = v1.randomBytes(32)
    const { store, items } = await seedTwo(memoryKey)
    const written: Uint8Array[] = []
    let ended = ''
    const picker = vi.fn(async (_o?: { suggestedName?: string }) => ({
      createWritable: async () => ({
        write: async (b: Uint8Array) => void written.push(b.slice()),
        close: async () => void (ended = 'close'),
        abort: async () => void (ended = 'abort'),
      }),
    }))
    vi.stubGlobal('showSaveFilePicker', picker)
    const wrapper = mountLightbox(memoryKey, items, 1)
    await flushPromises()
    store.blob.presignBlobDownloads.mockClear()

    // a real DOM click, and the assertion in the SAME task: the picker ran before the handler's first await yielded
    ;(wrapper.find(`[aria-label="${t('media.download')}"]`).element as HTMLButtonElement).click()
    expect(picker).toHaveBeenCalledTimes(1)
    expect(picker.mock.calls[0][0]).toEqual({ suggestedName: 'Rom 2026 2.jpg' })
    expect(store.blob.presignBlobDownloads).not.toHaveBeenCalled()

    await flushPromises()
    expect(ended).toBe('close')
    expect(Array.from(written.flatMap((b) => Array.from(b)))).toEqual(Array(8).fill(2))
  })

  // inbox #395 F10, the owner's repro: two photos, then a third upload shifts the gallery. The download name must not
  // move with it. Since inbox #397 the name is the original's blob meta (the EXIF tier carries none — the reader finds
  // nothing here). Lever: hand `originalDownloadName` null instead of the blob meta (the Memory name + position again).
  it('downloads an original under its own sealed file name, and the name holds when a newer upload shifts the gallery', async () => {
    const memoryKey = v1.randomBytes(32)
    const store = galleryFakeStore()
    fakeApi(mediaApi, store.api)
    fakeApi(blobApi, store.blob)
    vi.stubGlobal('fetch', store.fetchStub)
    const upload = (fill: number, name: string) =>
      addMedia(SCOPE_ID, NODE_ID, memoryKey, { blob: new Blob([new Uint8Array(8).fill(fill) as BlobPart]), mediaType: 'image/jpeg', name }, stubDeriver())
    const suggested = pickerNames()

    await upload(1, 'IMG_0001.JPG')
    await upload(2, 'IMG_0002.JPG')
    const two = await listMedia(SCOPE_ID, NODE_ID)
    const first = mountLightbox(memoryKey, two, 0)
    await flushPromises()
    await first.find(`[aria-label="${t('media.download')}"]`).trigger('click')
    await flushPromises()
    first.unmount()

    await upload(3, 'IMG_0003.JPG') // the newer photo goes in front of it: the same photo is now at position 2
    const three = await listMedia(SCOPE_ID, NODE_ID)
    const shifted = [three[2], ...three.slice(0, 2)]
    expect(shifted[1].mediaId).toBe(two[0].mediaId)
    const second = mountLightbox(memoryKey, shifted, 1)
    await flushPromises()
    await second.find(`[aria-label="${t('media.download')}"]`).trigger('click')
    await flushPromises()

    expect(suggested).toEqual(['IMG_0001.JPG', 'IMG_0001.JPG']) // was 'Rom 2026 1.jpg', then 'Rom 2026 2.jpg'
  })

  // Only a media whose original carries no name falls back to the Memory name + its position (cr-0049) — `seedTwo`
  // uploads without a name.
  it('a media without a sealed name falls back to the Memory name and its gallery position', async () => {
    const memoryKey = v1.randomBytes(32)
    const { items } = await seedTwo(memoryKey)
    const suggested = pickerNames()
    const wrapper = mountLightbox(memoryKey, items, 1)
    await flushPromises()
    await wrapper.find(`[aria-label="${t('media.download')}"]`).trigger('click')
    await flushPromises()
    expect(suggested).toEqual(['Rom 2026 2.jpg'])
  })

  // Since inbox #397 the name rides the listing (the original's blob meta), so the download no longer waits for the lazy
  // EXIF tier (#395 F10 disabled it until that tier was in). Lever: disable the button while the details load.
  it('is enabled at once and names the file from the listing while the EXIF tier is still loading', async () => {
    const memoryKey = v1.randomBytes(32)
    const store = galleryFakeStore()
    fakeApi(mediaApi, store.api)
    fakeApi(blobApi, store.blob)
    vi.stubGlobal('fetch', store.fetchStub)
    await addMedia(SCOPE_ID, NODE_ID, memoryKey, { blob: new Blob([new Uint8Array(8).fill(1) as BlobPart]), mediaType: 'image/jpeg', name: 'Kolosseum.jpg' }, stubDeriver())
    const items = await listMedia(SCOPE_ID, NODE_ID)
    const suggested = pickerNames()
    const exifLoader = vi.fn(() => new Promise<MediaExif | null>(() => {})) // never settles
    const wrapper = mountLightbox(memoryKey, items, 0, { exifLoader })
    await flushPromises()

    const button = wrapper.find(`[aria-label="${t('media.download')}"]`)
    expect(button.attributes('disabled')).toBeUndefined()
    await button.trigger('click')
    await flushPromises()
    expect(suggested).toEqual(['Kolosseum.jpg'])
  })

  // A foreign ref: the server serves a derivative's meta in the original's slot. It does not open under the original's
  // K_blob, so the reader gets null and the fallback names the file — never the other blob's facts. Lever: open the
  // original's meta under the derivative's key.
  it('a blob meta that does not open for the original names the file by the fallback', async () => {
    const memoryKey = v1.randomBytes(32)
    const store = galleryFakeStore()
    fakeApi(mediaApi, store.api)
    fakeApi(blobApi, store.blob)
    vi.stubGlobal('fetch', store.fetchStub)
    await addMedia(SCOPE_ID, NODE_ID, memoryKey, { blob: new Blob([new Uint8Array(8).fill(1) as BlobPart]), mediaType: 'video/mp4', name: 'clip.mp4' }, stubDeriver())
    const [item] = await listMedia(SCOPE_ID, NODE_ID)
    const foreign: MediaItem = { ...item, original: { ...item.original, wrappedBlobMeta: item.derivatives[0].ref.wrappedBlobMeta } }
    const suggested = pickerNames()
    const wrapper = mountLightbox(memoryKey, [foreign], 0)
    await flushPromises()
    expect(wrapper.find(`[aria-label="${t('media.playVideo')}"]`).exists()).toBe(false) // no type → no video branch
    await wrapper.find(`[aria-label="${t('media.download')}"]`).trigger('click')
    await flushPromises()
    expect(suggested).toEqual(['Rom 2026 1.bin'])
  })

  it('a cancelled picker is silent: no error, no toast, nothing fetched', async () => {
    const memoryKey = v1.randomBytes(32)
    const { store, items } = await seedTwo(memoryKey)
    vi.stubGlobal('showSaveFilePicker', vi.fn(async () => { throw new DOMException('dismissed', 'AbortError') }))
    const wrapper = mountLightbox(memoryKey, items, 0)
    await flushPromises()
    store.blob.presignBlobDownloads.mockClear()

    await wrapper.find(`[aria-label="${t('media.download')}"]`).trigger('click')
    await flushPromises()
    expect(store.blob.presignBlobDownloads).not.toHaveBeenCalled()
    expect(toastError).not.toHaveBeenCalled()
    expect(wrapper.find('.media-lightbox-error').exists()).toBe(false)
  })

  it('an original above the Blob cap with no streaming target is refused with a toast, and no chunk is read', async () => {
    const memoryKey = v1.randomBytes(32)
    const { items } = await seedTwo(memoryKey)
    // a 1 GiB original (the share viewer's injection point is the same provider seam the authed default fills)
    const chunks = 4096
    const huge = { chunkCount: chunks, byteSize: chunks * (256 * 1024 + v1.FRAME_OVERHEAD), getPlaintextChunk: vi.fn(async () => new Uint8Array(1)) }
    const wrapper = mountLightbox(memoryKey, items, 0, { videoChunkProvider: () => huge })
    await flushPromises()
    const saveSpy = vi.spyOn(URL, 'createObjectURL') // after the preview minted its own URL

    await wrapper.find(`[aria-label="${t('media.download')}"]`).trigger('click')
    await flushPromises()
    expect(toastError.mock.calls).toEqual([[t('media.downloadTooLarge')]])
    expect(huge.getPlaintextChunk).not.toHaveBeenCalled()
    expect(saveSpy).not.toHaveBeenCalled()
    expect(wrapper.find('.media-lightbox-error').exists()).toBe(false) // not the stage's "couldn't load"
  })

  it('a chunk that fails mid-download aborts the picked file (never closes it) and shows the error', async () => {
    const memoryKey = v1.randomBytes(32)
    const { items } = await seedTwo(memoryKey)
    let ended = ''
    vi.stubGlobal('showSaveFilePicker', vi.fn(async () => ({
      createWritable: async () => ({
        write: async () => {},
        close: async () => void (ended = 'close'),
        abort: async () => void (ended = 'abort'),
      }),
    })))
    const failing = {
      chunkCount: 3, byteSize: 3 * (16 + v1.FRAME_OVERHEAD),
      getPlaintextChunk: vi.fn(async (i: number) => { if (i === 1) throw new Error('chunk_get_failed:500'); return new Uint8Array(16) }),
    }
    const wrapper = mountLightbox(memoryKey, items, 0, { videoChunkProvider: () => failing })
    await flushPromises()

    await wrapper.find(`[aria-label="${t('media.download')}"]`).trigger('click')
    await flushPromises()
    expect(ended).toBe('abort')
    expect(wrapper.find('.media-lightbox-error').exists()).toBe(true)
    expect(toastError).not.toHaveBeenCalled()
  })
})

describe('MediaLightbox — video playback from the blob meta type (ADR 0047, #104; inbox #397)', () => {
  const FTYP_MP4 = new Uint8Array([0, 0, 0, 0, 0x66, 0x74, 0x79, 0x70, 0x69, 0x73, 0x6f, 0x6d]) // "....ftypisom"

  /** Seed one media with an explicit source type + original bytes. */
  async function seedVideoLike(memoryKey: Uint8Array, mediaType: string, bytes: Uint8Array) {
    const store = galleryFakeStore()
    fakeApi(mediaApi, store.api)
    fakeApi(blobApi, store.blob)
    vi.stubGlobal('fetch', store.fetchStub)
    await addMedia(SCOPE_ID, NODE_ID, memoryKey, { blob: new Blob([bytes as BlobPart]), mediaType }, stubDeriver())
    const items = await listMedia(SCOPE_ID, NODE_ID)
    return { store, items }
  }

  it('a typed video shows a poster + play affordance, then plays the ORIGINAL from a <video> on click', async () => {
    const memoryKey = v1.randomBytes(32)
    const { store, items } = await seedVideoLike(memoryKey, 'video/mp4', FTYP_MP4)

    const wrapper = mountLightbox(memoryKey, items, 0)
    await flushPromises()

    // the video shows its poster (PREVIEW tier) with a play button — not a bare <img>, no <video> yet
    expect(wrapper.find('.media-lightbox-video-poster').exists()).toBe(true)
    const play = wrapper.find(`[aria-label="${t('media.playVideo')}"]`)
    expect(play.exists()).toBe(true)
    expect(wrapper.find('.media-lightbox-stage video').exists()).toBe(false)

    store.blob.presignBlobDownloads.mockClear()
    await play.trigger('click')
    await flushPromises()

    // play fetched the ORIGINAL (up-0), never a derivative, and swapped in a typed <video>
    const presigned = store.blob.presignBlobDownloads.mock.calls.flatMap(
      (c) => c[0].blobDownloadsBatchRequest.items.map((it) => it.blobId),
    )
    expect(presigned).toEqual([store.blobIdOfUpload('up-0')])
    const video = wrapper.find('.media-lightbox-stage video')
    expect(video.exists()).toBe(true)
    expect(video.attributes('src')).toBeTruthy()
  })

  it('an image shows no play affordance (the <video> branch is video-only)', async () => {
    const memoryKey = v1.randomBytes(32)
    const { items } = await seedVideoLike(memoryKey, 'image/png', new Uint8Array(8).fill(1))
    const wrapper = mountLightbox(memoryKey, items, 0)
    await flushPromises()
    expect(wrapper.find(`[aria-label="${t('media.playVideo')}"]`).exists()).toBe(false)
    expect(wrapper.find('.media-lightbox-stage img').exists()).toBe(true)
  })

  // An empty source type (Safari's Photos import): the upload sniffs the original's type and seals it in the blob meta,
  // so the lightbox knows the video at once and the download names it with nothing read first. The download writes
  // nothing back — the lazy mediaType backfill is gone (inbox #397). Lever: skip the upload-time sniff.
  it('an untyped upload is a video from its sniffed blob meta type — play affordance, .mp4 name, no write-back', async () => {
    const memoryKey = v1.randomBytes(32)
    const { store, items } = await seedVideoLike(memoryKey, '', FTYP_MP4)
    const picker = vi.fn(async (_o?: { suggestedName?: string }) => ({
      createWritable: async () => ({ write: async () => {}, close: async () => {}, abort: async () => {} }),
    }))
    vi.stubGlobal('showSaveFilePicker', picker)

    const wrapper = mountLightbox(memoryKey, items, 0)
    await flushPromises()
    expect(wrapper.find(`[aria-label="${t('media.playVideo')}"]`).exists()).toBe(true)
    store.blob.presignBlobDownloads.mockClear()
    ;(wrapper.find(`[aria-label="${t('media.download')}"]`).element as HTMLButtonElement).click()
    expect(picker.mock.calls).toEqual([[{ suggestedName: 'Rom 2026 1.mp4' }]]) // in the click, before any read
    expect(store.blob.presignBlobDownloads).not.toHaveBeenCalled()
    await flushPromises()

    expect(store.api.updateMediaMeta).not.toHaveBeenCalled()
    expect(wrapper.emitted('updated')).toBeFalsy()
  })
})

describe('MediaLightbox — SW switchover (ADR 0047)', () => {
  const FTYP_MP4 = new Uint8Array([0, 0, 0, 0, 0x66, 0x74, 0x79, 0x70, 0x69, 0x73, 0x6f, 0x6d])

  async function seedVideo(memoryKey: Uint8Array) {
    const store = galleryFakeStore()
    fakeApi(mediaApi, store.api)
    fakeApi(blobApi, store.blob)
    vi.stubGlobal('fetch', store.fetchStub)
    await addMedia(SCOPE_ID, NODE_ID, memoryKey, { blob: new Blob([FTYP_MP4 as BlobPart]), mediaType: 'video/mp4' }, stubDeriver())
    return { store, items: await listMedia(SCOPE_ID, NODE_ID) }
  }

  it('plays via the SW handle when a controlling SW answered — no whole-original fetch — and revokes on unmount', async () => {
    const memoryKey = v1.randomBytes(32)
    const { store, items } = await seedVideo(memoryKey)
    vi.mocked(registerPlayback).mockResolvedValueOnce('/__media/handle-1')

    const wrapper = mountLightbox(memoryKey, items, 0)
    await flushPromises()
    store.blob.presignBlobDownloads.mockClear()

    await wrapper.find(`[aria-label="${t('media.playVideo')}"]`).trigger('click')
    await flushPromises()

    // registered a key-free playback with {chunkCount, byteSize, mediaType} + the page's chunk provider
    expect(registerPlayback).toHaveBeenCalledTimes(1)
    const [meta, provider] = vi.mocked(registerPlayback).mock.calls[0]
    expect(meta.mediaType).toBe('video/mp4')
    expect(meta.chunkCount).toBeGreaterThan(0)
    expect(typeof provider.getPlaintextChunk).toBe('function')
    // the <video> src is the synthetic handle URL, and the page never fetched the whole original (the point of the SW path)
    expect(wrapper.find('.media-lightbox-stage video').attributes('src')).toBe('/__media/handle-1')
    expect(store.blob.presignBlobDownloads).not.toHaveBeenCalled()

    wrapper.unmount()
    expect(revokePlayback).toHaveBeenCalledWith('handle-1')
  })

  it('falls back to the full-blob path when no SW is usable', async () => {
    const memoryKey = v1.randomBytes(32)
    const { store, items } = await seedVideo(memoryKey)
    vi.mocked(registerPlayback).mockResolvedValueOnce(null) // no / stale / mismatched SW

    const wrapper = mountLightbox(memoryKey, items, 0)
    await flushPromises()
    store.blob.presignBlobDownloads.mockClear()

    await wrapper.find(`[aria-label="${t('media.playVideo')}"]`).trigger('click')
    await flushPromises()

    // fell back: fetched the ORIGINAL (up-0) and played a decrypted blob: URL — no SW handle
    const presigned = store.blob.presignBlobDownloads.mock.calls.flatMap(
      (c) => c[0].blobDownloadsBatchRequest.items.map((it) => it.blobId),
    )
    expect(presigned).toEqual([store.blobIdOfUpload('up-0')])
    expect(wrapper.find('.media-lightbox-stage video').attributes('src')).toMatch(/^blob:/)
    expect(revokePlayback).not.toHaveBeenCalled()
  })
})

describe('MediaLightbox — delete (cr-0110)', () => {
  it('the trash button emits delete for the current item', async () => {
    const memoryKey = v1.randomBytes(32)
    const { items } = await seedTwo(memoryKey)
    const wrapper = mountLightbox(memoryKey, items, 0)
    await flushPromises()

    const del = wrapper.findAll('.media-lightbox-actions button').find((b) => b.attributes('aria-label') === t('media.delete'))
    expect(del).toBeTruthy()
    await del!.trigger('click')
    expect(wrapper.emitted('delete')?.[0]?.[0]).toEqual({ mediaId: items[0].mediaId })
  })

  it('the Delete key emits delete for the current item (the culling hotkey)', async () => {
    const memoryKey = v1.randomBytes(32)
    const { items } = await seedTwo(memoryKey)
    const wrapper = mountLightbox(memoryKey, items, 0)
    await flushPromises()

    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Delete' }))
    expect(wrapper.emitted('delete')?.[0]?.[0]).toEqual({ mediaId: items[0].mediaId })
  })

  it('a key typed into a dialog over the lightbox (the report dialog) is that dialog\'s, never the culling hotkey', async () => {
    const memoryKey = v1.randomBytes(32)
    const { items } = await seedTwo(memoryKey)
    const wrapper = mountLightbox(memoryKey, items, 0)
    await flushPromises()
    const box = document.createElement('div')
    box.className = 'veyq-dialog'
    const reason = document.createElement('textarea')
    box.append(reason)
    document.body.append(box)
    for (const key of ['Backspace', 'Delete', 'ArrowRight', 'Escape']) reason.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true }))
    expect(wrapper.emitted('delete')).toBeFalsy()
    expect(wrapper.emitted('close')).toBeFalsy()
    box.remove()
    // control: the same key outside any dialog is the hotkey
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Backspace' }))
    expect(wrapper.emitted('delete')?.[0]?.[0]).toEqual({ mediaId: items[0].mediaId })
  })

  it('where canDelete says no (a viewer, or a guest on what it did not send) there is no trash button and the Delete key does nothing', async () => {
    const memoryKey = v1.randomBytes(32)
    const { items } = await seedTwo(memoryKey)
    const wrapper = mountLightbox(memoryKey, items, 0, GUEST_WRITES)
    await flushPromises()

    expect(wrapper.findAll('.media-lightbox-actions button').some((b) => b.attributes('aria-label') === t('media.delete'))).toBe(false)
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Delete' }))
    expect(wrapper.emitted('delete')).toBeFalsy()
  })

  // The delete is per item (inbox #401 S5): a READ_WRITE guest deletes what it sent, only that — the parent purges it.
  it('canDelete is asked per shown item: the trash shows on the one it allows, and the Delete key follows it', async () => {
    const memoryKey = v1.randomBytes(32)
    const { items } = await seedTwo(memoryKey)
    const own = items[1].mediaId
    const wrapper = mountLightbox(memoryKey, items, 0, { ...GUEST_WRITES, canDelete: (it: MediaItem) => it.mediaId === own })
    await flushPromises()
    const hasTrash = () => wrapper.findAll('.media-lightbox-actions button').some((b) => b.attributes('aria-label') === t('media.delete'))

    expect(hasTrash(), "another's photo").toBe(false)
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Delete' }))
    expect(wrapper.emitted('delete')).toBeFalsy()

    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight' }))
    await flushPromises()
    expect(hasTrash(), 'its own').toBe(true)
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Delete' }))
    expect(wrapper.emitted('delete')?.[0]?.[0]).toEqual({ mediaId: own })
  })
})

describe('MediaLightbox — lazy EXIF detail panel (cr-0064)', () => {
  it('lazily loads the EXIF tier via getMediaExif and renders the decoded detail rows', async () => {
    const memoryKey = v1.randomBytes(32)
    // the source carries readable EXIF → addMedia seals it under K_node into the (lazy) Tier-2 blob
    vi.mocked(readExifTags).mockResolvedValue({
      DateTimeOriginal: Date.UTC(2021, 4, 1, 10, 0, 0),
      Make: 'Canon', Model: 'EOS R', LensModel: 'RF 50mm', FocalLength: 50, FNumber: 1.8, ExposureTime: 0.008, ISOSpeedRatings: 200,
      latitude: 48.137, longitude: 11.575,
    })

    const store = galleryFakeStore()
    fakeApi(mediaApi, store.api)
    fakeApi(blobApi, store.blob)
    vi.stubGlobal('fetch', store.fetchStub)
    await addMedia(SCOPE_ID, NODE_ID, memoryKey, { blob: new Blob([new Uint8Array(8).fill(3) as BlobPart]), mediaType: 'image/jpeg' }, stubDeriver())
    const items = await listMedia(SCOPE_ID, NODE_ID)

    // the list stays constant-small — no EXIF rode along
    expect((items[0] as Record<string, unknown>).wrappedExif).toBeUndefined()

    const wrapper = mountLightbox(memoryKey, items, 0)
    await flushPromises()

    // the panel fetched the exif endpoint once and unsealed it under K_node
    expect(store.api.getMediaExif).toHaveBeenCalledWith({ scopeId: SCOPE_ID, mediaId: items[0].mediaId })
    const dds = wrapper.findAll('.media-lightbox-metadata dd').map((d) => d.text())
    expect(dds).toContain('Canon EOS R')
    expect(dds).toContain('RF 50mm')
    expect(dds).toContain('50 mm')
    expect(dds).toContain('f/1.8')
    expect(dds).toContain('1/125 s')
    expect(dds).toContain('200')
    expect(dds).toContain('48.137000, 11.575000') // the coordinate as text; the map beside it is asserted below
  })

  // B1.2 — the rows this CR adds. Asserted on the VALUES, so a label typo is a separate failure from a field
  // that never reached the tier, and the second half asserts the omissions: a phone snapshot must not render
  // twenty empty rows.
  it('renders the file, exposure, provenance and geo rows, and omits the ones the photo does not carry', async () => {
    const memoryKey = v1.randomBytes(32)
    vi.mocked(readExifTags).mockResolvedValue({
      Make: 'Canon', Model: 'EOS R', FocalLength: 50, FocalLengthIn35mmFilm: 75,
      ExposureBiasValue: -0.7, Flash: 9, Orientation: 6, ISOSpeedRatings: 200,
      Software: 'veyq fixture 1.0', Artist: 'A. Photographer', Copyright: '(c) 2021 A. Photographer',
      Rating: 4,
      xmpTitle: 'Marienplatz',
      xmpDescription: 'Marienplatz at golden hour',
      GPSAltitude: 519.4, GPSAltitudeRef: 0,
      GPSImgDirection: 215.3, GPSImgDirectionRef: 'T', GPSHPositioningError: 4.8,
      latitude: 48.137, longitude: 11.575,
    })

    const store = galleryFakeStore()
    fakeApi(mediaApi, store.api)
    fakeApi(blobApi, store.blob)
    vi.stubGlobal('fetch', store.fetchStub)
    await addMedia(SCOPE_ID, NODE_ID, memoryKey, { blob: new Blob([new Uint8Array(8).fill(3) as BlobPart]), mediaType: 'image/jpeg', name: 'IMG_0042.HEIC' }, stubDeriver())
    const items = await listMedia(SCOPE_ID, NODE_ID)

    const wrapper = mountLightbox(memoryKey, items, 0)
    await flushPromises()
    const dds = wrapper.findAll('.media-lightbox-metadata dd').map((d) => d.text())

    expect(dds).toContain('IMG_0042.HEIC') // the filename, from the original's blob meta (inbox #397) and nowhere else
    expect(dds).toContain('image/jpeg')
    expect(dds).toContain('75 mm') // the 35 mm equivalent, beside the native 50 mm
    expect(dds).toContain('-0.7 EV')
    expect(dds).toContain(t('media.flashFired')) // Flash 9 → bit 0 set
    expect(dds).toContain('★★★★☆')
    expect(dds).toContain('veyq fixture 1.0')
    expect(dds).toContain('A. Photographer')
    expect(dds).toContain('Marienplatz')
    expect(dds).toContain('Marienplatz at golden hour')
    expect(dds).toContain('519 m')
    expect(dds).toContain(t('media.directionTrue', { deg: 215 }))
    expect(dds).toContain('4.8 m')
  })

  it('a flash value of 16 reads as "did not fire" — the bitfield, not a truthiness test', async () => {
    const memoryKey = v1.randomBytes(32)
    vi.mocked(readExifTags).mockResolvedValue({ Make: 'Canon', Flash: 16 })

    const store = galleryFakeStore()
    fakeApi(mediaApi, store.api)
    fakeApi(blobApi, store.blob)
    vi.stubGlobal('fetch', store.fetchStub)
    await addMedia(SCOPE_ID, NODE_ID, memoryKey, { blob: new Blob([new Uint8Array(8).fill(5) as BlobPart]), mediaType: 'image/jpeg' }, stubDeriver())
    const items = await listMedia(SCOPE_ID, NODE_ID)

    const wrapper = mountLightbox(memoryKey, items, 0)
    await flushPromises()
    const dds = wrapper.findAll('.media-lightbox-metadata dd').map((d) => d.text())

    expect(dds).toContain(t('media.flashNotFired'))
    // …and the rows this photo carries nothing for are absent, not blank
    expect(dds).not.toContain('★★★★☆')
    expect(dds.some((v) => v.includes('EV'))).toBe(false)
    expect(dds.some((v) => v.includes('north'))).toBe(false)
  })

  // --- the resolved place name (uc.memory.place, ADR 0016) ------------------------------------------------
  /** One geotagged photo, listed and ready to open. */
  async function seedGeotagged(memoryKey: Uint8Array) {
    vi.mocked(readExifTags).mockResolvedValue({ Make: 'Canon', latitude: 48.137, longitude: 11.575 })
    const store = galleryFakeStore()
    fakeApi(mediaApi, store.api)
    fakeApi(blobApi, store.blob)
    const geo = vi.fn(async (url: string, init?: RequestInit) =>
      String(url).includes('geo.example')
        ? ({ ok: true, status: 200, json: async () => ({ address: { city: 'München', country: 'Deutschland' } }) } as unknown as Response)
        : store.fetchStub(url, init),
    )
    vi.stubGlobal('fetch', geo)
    await addMedia(SCOPE_ID, NODE_ID, memoryKey, { blob: new Blob([new Uint8Array(8).fill(7) as BlobPart]), mediaType: 'image/jpeg' }, stubDeriver())
    return { store, geo, items: await listMedia(SCOPE_ID, NODE_ID) }
  }
  const geocoderCalls = (geo: ReturnType<typeof vi.fn>) =>
    geo.mock.calls.filter((c) => String(c[0]).includes('geo.example'))

  // B2.6 — the name is shown, and the coordinate stays visible beneath it rather than being replaced by it.
  it('resolves the place name once, seals it, and shows it above the coordinate', async () => {
    resetPlaceNameCache()
    mapConfig.maps = true
    mapConfig.geocoder = 'https://geo.example'
    const memoryKey = v1.randomBytes(32)
    const { store, geo, items } = await seedGeotagged(memoryKey)

    const wrapper = mountLightbox(memoryKey, items, 0)
    await flushPromises()
    await flushPromises()

    expect(geocoderCalls(geo)).toHaveLength(1)
    const dds = wrapper.findAll('.media-lightbox-metadata dd').map((d) => d.text())
    expect(dds).toContain('München')
    expect(dds).toContain('48.137000, 11.575000') // the coordinate is not replaced by the name
    // …and it was sealed, so the next open reads it from the tier instead of asking again
    const sent = store.api.updateMediaMeta.mock.calls.at(-1)![0].mediaMetaUpdate.wrappedMeta
    expect(typeof sent).toBe('string')
    expect(sent).not.toContain('München') // opaque — the operator learns no place
  })

  // B2.1 — the consent gate. With maps off, a geotagged photo must put NO coordinate on the wire.
  // Lever: pass `geocoderUrl` to the resolver regardless of `geocodingAvailable`.
  it('makes no geocoder request when consent is off, however many coordinates the photo carries', async () => {
    resetPlaceNameCache()
    mapConfig.maps = false
    mapConfig.geocoder = 'https://geo.example'
    const memoryKey = v1.randomBytes(32)
    const { geo, items } = await seedGeotagged(memoryKey)

    const wrapper = mountLightbox(memoryKey, items, 0)
    await flushPromises()
    await flushPromises()

    expect(geocoderCalls(geo)).toHaveLength(0)
    expect(wrapper.findAll('.media-lightbox-metadata dd').map((d) => d.text())).toContain('48.137000, 11.575000')
  })

  // B2.5 — a session without the medium's meta door (a share guest) must never write, consent or not.
  // Lever: remove the `mediaMeta` check in `resolvePlaceForCurrent`.
  it('never writes from a read-only viewer, even with consent on and a coordinate present', async () => {
    resetPlaceNameCache()
    mapConfig.maps = true
    mapConfig.geocoder = 'https://geo.example'
    const memoryKey = v1.randomBytes(32)
    const { store, geo, items } = await seedGeotagged(memoryKey)

    mountLightbox(memoryKey, items, 0, GUEST_WRITES)
    await flushPromises()
    await flushPromises()

    expect(store.api.updateMediaMeta).not.toHaveBeenCalled()
    expect(geocoderCalls(geo)).toHaveLength(0) // and it does not even ask, since it could not use the answer
  })

  // B3.1 / B3.2 — the map is an illustration of the coordinate, and consent is what decides it exists.
  it('renders the map on the photo’s coordinate with consent, and no map at all without it', async () => {
    resetPlaceNameCache()
    mapConfig.maps = true
    mapConfig.geocoder = 'https://geo.example'
    const memoryKey = v1.randomBytes(32)
    const { items } = await seedGeotagged(memoryKey)

    const withConsent = mountLightbox(memoryKey, items, 0)
    await flushPromises()
    await flushPromises()
    expect(withConsent.find('.media-lightbox-map').exists()).toBe(true)
    // the needle drawn from phase 1's GPSImgDirection is absent on a fix that carried none
    expect(withConsent.find('.media-lightbox-view-direction').exists()).toBe(false)

    // Lever for B3.2: drop the `mapsAvailable` gate on `showMiniMap` and this reddens.
    mapConfig.maps = false
    const withoutConsent = mountLightbox(memoryKey, items, 0)
    await flushPromises()
    await flushPromises()
    expect(withoutConsent.find('.media-lightbox-map').exists()).toBe(false)
    expect(withoutConsent.findAll('.media-lightbox-metadata dd').map((d) => d.text())).toContain('48.137000, 11.575000')
  })

  it('draws the view-direction needle when the photo’s fix carried one', async () => {
    resetPlaceNameCache()
    mapConfig.maps = true
    mapConfig.geocoder = null // no geocoder: this test is about the needle, not the name
    vi.mocked(readExifTags).mockResolvedValue({
      Make: 'Canon', latitude: 48.137, longitude: 11.575, GPSImgDirection: 215.3, GPSImgDirectionRef: 'T',
    })
    const store = galleryFakeStore()
    fakeApi(mediaApi, store.api)
    fakeApi(blobApi, store.blob)
    vi.stubGlobal('fetch', store.fetchStub)
    const memoryKey = v1.randomBytes(32)
    await addMedia(SCOPE_ID, NODE_ID, memoryKey, { blob: new Blob([new Uint8Array(8).fill(2) as BlobPart]), mediaType: 'image/jpeg' }, stubDeriver())
    const items = await listMedia(SCOPE_ID, NODE_ID)

    const wrapper = mountLightbox(memoryKey, items, 0)
    await flushPromises()
    await flushPromises()

    const needle = wrapper.find('.media-lightbox-view-direction')
    expect(needle.exists()).toBe(true)
    expect(needle.attributes('style')).toContain('215.3deg')
  })

  // Since inbox #397 the file's own facts — name, size, format — come from the listing (the original's blob meta and
  // its ref), so a media without EXIF still lists them; only the camera rows are missing.
  it('lists the file\'s own facts, and no camera rows, when the item carries no sealed EXIF', async () => {
    const memoryKey = v1.randomBytes(32)
    vi.mocked(readExifTags).mockResolvedValue(undefined) // no readable EXIF → nothing sealed

    const store = galleryFakeStore()
    fakeApi(mediaApi, store.api)
    fakeApi(blobApi, store.blob)
    vi.stubGlobal('fetch', store.fetchStub)
    await addMedia(SCOPE_ID, NODE_ID, memoryKey, { blob: new Blob([new Uint8Array(8).fill(4) as BlobPart]), mediaType: 'image/png', name: 'Screenshot.png' }, stubDeriver())
    const items = await listMedia(SCOPE_ID, NODE_ID)

    const wrapper = mountLightbox(memoryKey, items, 0)
    await flushPromises()

    expect(wrapper.find('.media-lightbox-no-detail').exists()).toBe(false)
    const dts = wrapper.findAll('.media-lightbox-metadata dt').map((d) => d.text())
    expect(dts).toEqual([t('media.fileName'), t('media.fileSize'), t('media.fileFormat')])
    const dds = wrapper.findAll('.media-lightbox-metadata dd').map((d) => d.text())
    expect(dds[0]).toBe('Screenshot.png')
    expect(dds[2]).toBe('image/png')
  })
})

describe('MediaLightbox — correct the capture time (cr-0065/0066)', () => {
  /** The capture-time field is the layer's date field at minute granularity, bound as `YYYY-MM-DDTHH:mm`. */
  const capturedAt = (w: ReturnType<typeof mountLightbox>) => w.findComponent(VeyqDateField)
  async function typeCapturedAt(w: ReturnType<typeof mountLightbox>, value: string) {
    capturedAt(w).vm.$emit('update:modelValue', value)
    await flushPromises()
  }

  async function seedOne(memoryKey: Uint8Array, exifTags: ExifTags | undefined) {
    vi.mocked(readExifTags).mockResolvedValue(exifTags)
    const store = galleryFakeStore()
    fakeApi(mediaApi, store.api)
    fakeApi(blobApi, store.blob)
    vi.stubGlobal('fetch', store.fetchStub)
    await addMedia(SCOPE_ID, NODE_ID, memoryKey, { blob: new Blob([new Uint8Array(8).fill(9) as BlobPart]), mediaType: 'image/jpeg' }, stubDeriver())
    const items = await listMedia(SCOPE_ID, NODE_ID)
    return { store, items }
  }

  it('corrects the time (re-seals opaque, shows the badge), then resets to the original', async () => {
    const memoryKey = v1.randomBytes(32)
    const { store, items } = await seedOne(memoryKey, { DateTimeOriginal: Date.UTC(2021, 4, 1, 10, 0, 0) })

    const wrapper = mountLightbox(memoryKey, items, 0)
    await flushPromises()
    expect(wrapper.text()).not.toContain(t('media.capturedAtCorrected'))

    // open the editor, set a corrected date, save
    await wrapper.find(`[aria-label="${t('media.editCapturedAt')}"]`).trigger('click')
    await typeCapturedAt(wrapper, '2019-08-15T08:30')
    await wrapper.find('.media-lightbox-date-form').trigger('submit')
    await flushPromises()

    // the correction went out only as opaque ciphertext, and the badge now shows
    const sent = store.api.updateMediaMeta.mock.calls.at(-1)![0].mediaMetaUpdate.wrappedMeta
    expect(typeof sent).toBe('string')
    expect(sent).not.toContain('2019')
    expect(wrapper.text()).toContain(t('media.capturedAtCorrected'))

    // reset back to the original — override removed, badge gone
    await wrapper.find(`[aria-label="${t('media.editCapturedAt')}"]`).trigger('click')
    const resetBtn = wrapper.findAll('.media-lightbox-date-actions button').find((b) => b.text() === t('media.resetCapturedAt'))!
    await resetBtn.trigger('click')
    await flushPromises()

    expect(store.api.updateMediaMeta).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).not.toContain(t('media.capturedAtCorrected'))
  })

  // The date field takes the arrows (segment to segment) and Backspace (clear a segment): on the window they page and
  // cull, so a key typed in the field is the field's, as a key in a dialog over the lightbox is that dialog's.
  it('a key typed into the capture-time field is the field\'s, never the culling hotkey or paging', async () => {
    const memoryKey = v1.randomBytes(32)
    const { items } = await seedOne(memoryKey, { DateTimeOriginal: Date.UTC(2021, 4, 1, 10, 0, 0) })
    const wrapper = mount(MediaLightbox, {
      props: { memoryKey, items, startIndex: 0, memoryName: 'Rom 2026', ...memberProps(memoryKey) },
      global: { plugins: [realI18n()] },
      attachTo: document.body,
    })
    await flushPromises()
    await wrapper.find(`[aria-label="${t('media.editCapturedAt')}"]`).trigger('click')
    const segment = wrapper.find('.veyq-date-field__segment').element
    for (const key of ['Backspace', 'Delete', 'ArrowRight', 'ArrowLeft', 'Escape']) segment.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true }))
    expect(wrapper.emitted('delete')).toBeFalsy()
    expect(wrapper.emitted('close')).toBeFalsy()
    // control: the same key outside the field is the hotkey
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Backspace' }))
    expect(wrapper.emitted('delete')?.[0]?.[0]).toEqual({ mediaId: items[0].mediaId })
    wrapper.unmount()
  })

  it('lets a user SET a date on a media that has no EXIF at all', async () => {
    const memoryKey = v1.randomBytes(32)
    const { store, items } = await seedOne(memoryKey, undefined) // no readable EXIF

    const wrapper = mountLightbox(memoryKey, items, 0)
    await flushPromises()
    // no capture date and no EXIF — the panel lists only the file's own facts (inbox #397) — but the edit affordance is
    // still there (set a fresh date)
    expect(wrapper.findAll('.media-lightbox-metadata dt').map((d) => d.text())).not.toContain(t('media.captured'))

    await wrapper.find(`[aria-label="${t('media.editCapturedAt')}"]`).trigger('click')
    await typeCapturedAt(wrapper, '2019-08-15T08:30')
    await wrapper.find('.media-lightbox-date-form').trigger('submit')
    await flushPromises()

    const sent = store.api.updateMediaMeta.mock.calls.at(-1)![0].mediaMetaUpdate.wrappedMeta
    expect(typeof sent).toBe('string') // a fresh sealed tier
    expect(wrapper.text()).toContain(t('media.capturedAtCorrected'))
  })

  /**
   * uc.memory.concurrent-curation E1 (ADR 0077): a peer corrected the time meanwhile. The save does not overwrite
   * it — the editor stays open on the PEER's time, says so, and the grid learns the peer's tier. Control: the
   * first save was sent (and refused), so the notice is about a real conflict, not a write never attempted.
   */
  it('a peer\'s correction written meanwhile re-opens the editor on theirs, with the conflict notice', async () => {
    const memoryKey = v1.randomBytes(32)
    const { store, items } = await seedOne(memoryKey, { DateTimeOriginal: Date.UTC(2021, 4, 1, 10, 0, 0) })
    const PEER = Date.UTC(2020, 1, 2, 3, 4, 0)
    const peers = b64std(v1.sealField(
      { purpose: 'media-meta', scopeId: SCOPE_ID, mediaId: items[0].mediaId }, memoryKey,
      new TextEncoder().encode(JSON.stringify({ v: 1, capturedAt: Date.UTC(2021, 4, 1, 10, 0, 0), capturedAtOverride: PEER })),
    ))
    store.api.updateMediaMeta.mockRejectedValueOnce({
      response: new Response(JSON.stringify({ code: 'stale_revision', wrappedMeta: peers, metaRevision: 1 }), { status: 409 }),
    })

    const wrapper = mountLightbox(memoryKey, items, 0)
    await flushPromises()
    await wrapper.find(`[aria-label="${t('media.editCapturedAt')}"]`).trigger('click')
    await typeCapturedAt(wrapper, '2019-08-15T08:30')
    await wrapper.find('.media-lightbox-date-form').trigger('submit')
    await flushPromises()

    expect(store.api.updateMediaMeta).toHaveBeenCalledTimes(1) // no retry over the peer's own field
    expect(wrapper.text()).toContain(t('media.capturedAtConflict'))
    expect(wrapper.find('#captured-at-input').exists()).toBe(true) // the editor is still open…
    expect(new Date(capturedAt(wrapper).props('modelValue') as string).getTime()).toBe(PEER) // …on theirs
    expect(wrapper.emitted('updated')?.at(-1)?.[0]).toEqual({ mediaId: items[0].mediaId, wrappedMeta: peers, metaRevision: 1 })
  })

  /**
   * inbox #290 (ADR 0077 point 4, no reset): a listed tier that does not open is never rebuilt from empty. The save
   * is refused before anything is sent, and the editor says in words that these details cannot be changed.
   */
  it('a details tier that does not open refuses the save with a message, and sends nothing', async () => {
    const memoryKey = v1.randomBytes(32)
    const { store, items } = await seedOne(memoryKey, { DateTimeOriginal: Date.UTC(2021, 4, 1, 10, 0, 0) })
    const damaged = { ...items[0], wrappedMeta: b64std(v1.randomBytes(64)) } // present, and opens under no key

    const wrapper = mountLightbox(memoryKey, [damaged], 0)
    await flushPromises()
    await wrapper.find(`[aria-label="${t('media.editCapturedAt')}"]`).trigger('click')
    await typeCapturedAt(wrapper, '2019-08-15T08:30')
    await wrapper.find('.media-lightbox-date-form').trigger('submit')
    await flushPromises()

    expect(store.api.updateMediaMeta).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain(t('media.metaUnreadable'))
    expect(wrapper.text()).not.toContain(t('media.capturedAtSaveFailed'))
  })
})

// Who added the shown photo (ADR 0083 decision 7), in the detail panel: a member uploader's name LINKS to their profile, a
// share contributor's and a former member's do not, and a session that carries no resolver (a share guest's) shows nothing.
describe('MediaLightbox: the uploader', () => {
  const ANNA = 'aaaaaaaa-0000-4000-8000-000000000001'
  async function mountWith(uploaderOf: ((it: MediaItem) => { name: string; userId: string | null } | null) | undefined) {
    const memoryKey = v1.randomBytes(32)
    const { items } = await seedTwo(memoryKey)
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', component: { template: '<div />' } }] })
    await router.push('/')
    const wrapper = mount(MediaLightbox, {
      props: { memoryKey, items, startIndex: 0, memoryName: 'Rom 2026', ...memberProps(memoryKey), ...(uploaderOf ? { uploaderOf } : {}) },
      global: { plugins: [realI18n(), router] },
    })
    await flushPromises()
    return wrapper
  }

  it('a member uploader links to the profile and closes the lightbox on the way', async () => {
    const w = await mountWith(() => ({ name: 'Anna Meyer', userId: ANNA }))
    const cell = w.get('[data-testid="lightbox-uploader"]')
    expect(w.text()).toContain('Added by')
    const link = cell.get('a')
    expect(link.text()).toBe('Anna Meyer')
    expect(link.attributes('href')).toBe(`/#people/${ANNA}`)
    await link.trigger('click')
    expect(w.emitted('close')).toBeTruthy()
  })

  it('a share contributor / a former member is a plain name: no link', async () => {
    const w = await mountWith(() => ({ name: 'Oma', userId: null }))
    const cell = w.get('[data-testid="lightbox-uploader"]')
    expect(cell.text()).toBe('Oma')
    expect(cell.find('a').exists()).toBe(false)
  })

  it('shows nothing where the session resolves no uploader (a share guest) - and nothing for an unresolved photo', async () => {
    expect((await mountWith(undefined)).find('[data-testid="lightbox-uploader"]').exists()).toBe(false)
    expect((await mountWith(() => null)).find('[data-testid="lightbox-uploader"]').exists()).toBe(false)
  })
})

// The zoom (cr-lightbox-zoom-slideshow, uc.memory.gallery 3a): the gestures move the stage <img>'s transform, paging resets
// it, and past the preview's resolution the ORIGINAL replaces the preview - once, for a decodable type under the cap, through
// the session's own `originalLoader`. happy-dom lays nothing out, so each test lays the <img> out itself: 1000 CSS px wide
// from a 2048 px preview on a 1x screen - 2x shows 2000 px (no original), 3x shows 3000 (the original).
describe('MediaLightbox — zoom (cr-lightbox-zoom-slideshow)', () => {
  beforeEach(() => {
    Object.defineProperty(window, 'devicePixelRatio', { value: 1, configurable: true })
    // A double-tap is two taps within 300 ms of `performance.now()`: frozen here, so a loaded machine's scheduling stall
    // between two synchronous dispatches cannot split one double-click into two single taps.
    vi.useFakeTimers({ toFake: ['performance'] })
  })
  afterEach(() => vi.useRealTimers())

  const stage = (w: ReturnType<typeof mountLightbox>) => w.find('.media-lightbox-stage')
  const shown = (w: ReturnType<typeof mountLightbox>) => w.find('.media-lightbox-stage img')
  const scaleOf = (w: ReturnType<typeof mountLightbox>) => Number(/scale\(([\d.]+)\)/.exec(shown(w).attributes('style') ?? '')?.[1] ?? 1)
  function layOut(w: ReturnType<typeof mountLightbox>, fitted = 1000, natural = 2048) {
    const img = shown(w).element
    for (const [k, v] of Object.entries({ clientWidth: fitted, clientHeight: fitted * 0.75, naturalWidth: natural, naturalHeight: natural * 0.75 })) {
      Object.defineProperty(img, k, { value: v, configurable: true })
    }
  }
  function pointer(w: ReturnType<typeof mountLightbox>, type: string) {
    stage(w).element.dispatchEvent(new PointerEvent(type, { bubbles: true, pointerId: 1, pointerType: 'mouse', button: 0, clientX: 10, clientY: 10 }))
  }
  async function doubleClick(w: ReturnType<typeof mountLightbox>) {
    for (let i = 0; i < 2; i++) {
      pointer(w, 'pointerdown')
      pointer(w, 'pointerup')
    }
    await flushPromises()
  }
  async function ctrlWheel(w: ReturnType<typeof mountLightbox>, deltaY: number) {
    const e = new WheelEvent('wheel', { bubbles: true, cancelable: true, deltaY })
    Object.defineProperty(e, 'ctrlKey', { value: true }) // happy-dom's WheelEvent is no MouseEvent: it drops ctrlKey
    stage(w).element.dispatchEvent(e)
    await flushPromises()
  }
  async function key(k: string) {
    window.dispatchEvent(new KeyboardEvent('keydown', { key: k }))
    await flushPromises()
  }
  /** The member's props with the original's loader recorded. */
  function recordedOriginal(memoryKey: Uint8Array) {
    const p = memberProps(memoryKey)
    const originalLoader = vi.fn(p.originalLoader)
    return { ...p, originalLoader }
  }

  it('double-click toggles 2x and back, ctrl+wheel zooms continuously, + / - / 0 are the keyboard path', async () => {
    const memoryKey = v1.randomBytes(32)
    const { items } = await seedTwo(memoryKey)
    const w = mountLightbox(memoryKey, items, 0)
    await flushPromises()
    expect(stage(w).classes()).toContain('media-lightbox-stage-zoomable')
    expect(scaleOf(w)).toBe(1)
    await doubleClick(w)
    expect(scaleOf(w)).toBe(2)
    expect(stage(w).classes()).toContain('media-lightbox-stage-zoomed')
    await doubleClick(w)
    expect(scaleOf(w)).toBe(1)
    await ctrlWheel(w, -200)
    expect(scaleOf(w)).toBeCloseTo(Math.E, 3)
    await ctrlWheel(w, 10000)
    expect(scaleOf(w), 'never below 1x').toBe(1)
    await key('+')
    expect(scaleOf(w)).toBe(1.5)
    await key('0')
    expect(scaleOf(w)).toBe(1)
    w.unmount()
  })

  it('paging resets the zoom; the arrows pan instead of paging while zoomed; Escape un-zooms first, then closes', async () => {
    const memoryKey = v1.randomBytes(32)
    const { items } = await seedTwo(memoryKey)
    const w = mountLightbox(memoryKey, items, 0)
    await flushPromises()
    layOut(w)
    await doubleClick(w)
    const before = shown(w).attributes('style')
    await key('ArrowRight')
    expect(w.find('.media-lightbox-title small').text(), 'no paging while zoomed').toBe(t('media.itemPosition', { index: 1, count: 2 }))
    expect(shown(w).attributes('style'), 'the arrow panned').not.toBe(before)
    expect(scaleOf(w)).toBe(2)
    await key('Escape')
    expect(scaleOf(w), 'Escape un-zooms').toBe(1)
    expect(w.emitted('close'), 'and does not close').toBeFalsy()
    await key('ArrowRight')
    expect(w.find('.media-lightbox-title small').text()).toBe(t('media.itemPosition', { index: 2, count: 2 }))
    await doubleClick(w)
    expect(scaleOf(w)).toBe(2)
    await w.find('.lightbox-nav-previous').trigger('click')
    await flushPromises()
    expect(scaleOf(w), 'paging resets').toBe(1)
    await key('Escape')
    expect(w.emitted('close')).toHaveLength(1)
    w.unmount()
  })

  it('a video does not zoom', async () => {
    const memoryKey = v1.randomBytes(32)
    const store = galleryFakeStore()
    fakeApi(mediaApi, store.api)
    fakeApi(blobApi, store.blob)
    vi.stubGlobal('fetch', store.fetchStub)
    await addMedia(SCOPE_ID, NODE_ID, memoryKey, { blob: new Blob([new Uint8Array([0, 0, 0, 0, 0x66, 0x74, 0x79, 0x70]) as BlobPart]), mediaType: 'video/mp4' }, stubDeriver())
    const w = mountLightbox(memoryKey, await listMedia(SCOPE_ID, NODE_ID), 0)
    await flushPromises()
    expect(w.find('.media-lightbox-video-poster').exists()).toBe(true)
    expect(stage(w).classes()).not.toContain('media-lightbox-stage-zoomable')
    await ctrlWheel(w, -100)
    await key('+')
    expect(w.find('.media-lightbox-video-poster img').attributes('style') ?? '').not.toContain('scale')
    w.unmount()
  })

  it('past the preview’s resolution the original replaces it once, and its URL is revoked on paging', async () => {
    const memoryKey = v1.randomBytes(32)
    const { items } = await seedTwo(memoryKey)
    const props = recordedOriginal(memoryKey)
    const created: string[] = []
    const create = URL.createObjectURL.bind(URL)
    vi.spyOn(URL, 'createObjectURL').mockImplementation((b) => { const u = create(b); created.push(u); return u })
    const revoke = vi.spyOn(URL, 'revokeObjectURL')
    const w = mountLightbox(memoryKey, items, 0, props)
    await flushPromises()
    const preview = shown(w).attributes('src')
    layOut(w)
    await doubleClick(w) // 2x: 2000 px of a 2048 px preview
    expect(props.originalLoader, 'not yet past the preview').not.toHaveBeenCalled()
    await key('+') // 3x: 3000 px
    expect(props.originalLoader).toHaveBeenCalledTimes(1)
    expect(props.originalLoader).toHaveBeenCalledWith(items[0])
    const original = shown(w).attributes('src')
    expect(original).not.toBe(preview)
    expect(created).toContain(original)
    expect(scaleOf(w), 'in place, at the same transform').toBe(3)
    expect(shown(w).attributes('style')).toContain('max-width: min(100%, 2048px)')
    await key('+')
    await key('-')
    await ctrlWheel(w, -200)
    expect(props.originalLoader, 'once per item').toHaveBeenCalledTimes(1)
    await key('Escape')
    await key('ArrowRight')
    expect(revoke).toHaveBeenCalledWith(original)
    w.unmount()
  })

  it('never otherwise: below the threshold, an undecodable type, over the cap; a failed fetch keeps the preview, silently', async () => {
    const memoryKey = v1.randomBytes(32)
    const { items } = await seedTwo(memoryKey)
    const zoomTo3x = async (extra: Record<string, unknown>, list = items, fitted = 1000, natural = 2048) => {
      const props = { ...recordedOriginal(memoryKey), ...extra }
      const w = mountLightbox(memoryKey, list, 0, props)
      await flushPromises()
      const preview = shown(w).attributes('src')
      layOut(w, fitted, natural)
      await doubleClick(w)
      await key('+')
      return { w, preview, originalLoader: props.originalLoader as ReturnType<typeof vi.fn> }
    }
    // a small preview on screen: 3x of 600 px is 1800 of 2048
    let r = await zoomTo3x({}, items, 600)
    expect(r.originalLoader, 'below the threshold').not.toHaveBeenCalled()
    r.w.unmount()
    // a preview drawn at the original's own size (1600 px, under the 2048 px tier): the original has no more pixels
    r = await zoomTo3x({}, items, 1000, 1600)
    expect(r.originalLoader, 'preview not scaled down').not.toHaveBeenCalled()
    r.w.unmount()
    // HEIC: only Safari decodes it
    r = await zoomTo3x({ blobMetaResolver: () => ({ v: 1, type: 'image/heic' }) })
    expect(r.originalLoader, 'undecodable').not.toHaveBeenCalled()
    r.w.unmount()
    // no sealed type at all
    r = await zoomTo3x({ blobMetaResolver: () => null })
    expect(r.originalLoader, 'untyped').not.toHaveBeenCalled()
    r.w.unmount()
    // at the cap
    const big = [{ ...items[0], original: { ...items[0].original, byteSize: ZOOM_ORIGINAL_MAX_BYTES } }]
    r = await zoomTo3x({}, big)
    expect(r.originalLoader, 'at the cap').not.toHaveBeenCalled()
    r.w.unmount()
    // control: one byte under the cap is fetched
    const under = [{ ...items[0], original: { ...items[0].original, byteSize: ZOOM_ORIGINAL_MAX_BYTES - 1 } }]
    r = await zoomTo3x({ originalLoader: vi.fn(async () => new Uint8Array([1])) }, under)
    expect(r.originalLoader, 'control: under the cap').toHaveBeenCalledTimes(1)
    r.w.unmount()
    // a failed fetch: the preview stays, no error, and it is not asked again on this item
    r = await zoomTo3x({ originalLoader: vi.fn(async () => { throw new Error('gone') }) })
    expect(r.originalLoader).toHaveBeenCalledTimes(1)
    expect(shown(r.w).attributes('src')).toBe(r.preview)
    expect(r.w.find('.media-lightbox-error').exists()).toBe(false)
    await key('+')
    expect(r.originalLoader).toHaveBeenCalledTimes(1)
    r.w.unmount()
  })

  // A share guest's lightbox is this one, handed the guest source's loaders (inbox #401): its zoom fetches the original
  // through the link's gated read (`openShareOriginal`), never the member's media API.
  it("a guest's lightbox zooms through its own loaders", async () => {
    const memoryKey = v1.randomBytes(32)
    const { store, items } = await seedTwo(memoryKey)
    const head = { shareId: 'share-1', scopeId: SCOPE_ID, role: ShareRole.Read, kShare: v1.randomBytes(32), name: 'Rom', noteByLocale: {} } as unknown as PublicShareHead
    const l = guestMemorySource(() => guestAccessFor(head), { rootId: null }).loaders
    vi.mocked(openShareLightbox).mockResolvedValue(new Uint8Array([0xff, 0xd8, 1]))
    vi.mocked(openShareOriginal).mockResolvedValue(new Uint8Array([0xff, 0xd8, 2]))
    store.blob.presignBlobDownloads.mockClear()
    const w = mountLightbox(memoryKey, items, 0, {
      ...GUEST_WRITES,
      previewLoader: (it: MediaItem) => l.preview(it, memoryKey),
      originalLoader: (it: MediaItem) => l.original(it, memoryKey),
      videoChunkProvider: (it: MediaItem) => l.chunk(it, memoryKey),
      metaResolver: (it: MediaItem) => l.meta(it, memoryKey),
      blobMetaResolver: (it: MediaItem) => l.blobMeta(it, memoryKey),
    })
    await flushPromises()
    layOut(w)
    await doubleClick(w)
    await key('+')
    expect(openShareOriginal).toHaveBeenCalledTimes(1)
    expect(vi.mocked(openShareOriginal).mock.calls[0][0]).toEqual({ shareId: 'share-1', apiBase: '' }) // the link where it lives (ADR 0121)
    expect(store.blob.presignBlobDownloads, "no member read").not.toHaveBeenCalled()
    expect(scaleOf(w)).toBe(3)
    w.unmount()
  })
})

// A swipe pages (inbox #863), round the ends as the buttons do: a finger on the stage of any item, never a mouse, never
// while a photo is zoomed (a drag pans there). The gesture's rules are `shared/ui/useSwipe.test.ts`'s; this is the wiring.
describe('MediaLightbox — swipe (#863)', () => {
  const position = (w: ReturnType<typeof mountLightbox>) => w.find('.media-lightbox-title small').text()
  const shown = (w: ReturnType<typeof mountLightbox>) => w.find('.media-lightbox-stage img')
  const scaleOf = (w: ReturnType<typeof mountLightbox>) => Number(/scale\(([\d.]+)\)/.exec(shown(w).attributes('style') ?? '')?.[1] ?? 1)
  /** A one-finger travel across the stage from x 400 by `dx`. */
  async function swipe(w: ReturnType<typeof mountLightbox>, dx: number, pointerType = 'touch') {
    const stage = w.find('.media-lightbox-stage').element
    const at = (type: string, x: number) =>
      stage.dispatchEvent(new PointerEvent(type, { bubbles: true, pointerId: 7, pointerType, button: 0, clientX: x, clientY: 300 }))
    at('pointerdown', 400)
    at('pointermove', 400 + dx / 2)
    at('pointerup', 400 + dx)
    await flushPromises()
  }

  it('left brings the next, right the previous, round the ends', async () => {
    const memoryKey = v1.randomBytes(32)
    const { items } = await seedImages(memoryKey, 3)
    const w = mountLightbox(memoryKey, items, 0)
    await flushPromises()
    await swipe(w, -150)
    expect(position(w)).toBe(t('media.itemPosition', { index: 2, count: 3 }))
    await swipe(w, 150)
    expect(position(w)).toBe(t('media.itemPosition', { index: 1, count: 3 }))
    await swipe(w, 150)
    expect(position(w), 'right on the first goes round to the last').toBe(t('media.itemPosition', { index: 3, count: 3 }))
    await swipe(w, -150)
    expect(position(w), 'left on the last goes round to the first').toBe(t('media.itemPosition', { index: 1, count: 3 }))
    w.unmount()
  })

  it('a short travel and a mouse drag page nothing', async () => {
    const memoryKey = v1.randomBytes(32)
    const { items } = await seedImages(memoryKey, 3)
    const w = mountLightbox(memoryKey, items, 0)
    await flushPromises()
    await swipe(w, -20)
    await swipe(w, -300, 'mouse')
    expect(position(w)).toBe(t('media.itemPosition', { index: 1, count: 3 }))
    w.unmount()
  })

  it('pages nothing while the photo is zoomed - the drag pans - and pages again once it is back at 1x', async () => {
    const memoryKey = v1.randomBytes(32)
    const { items } = await seedImages(memoryKey, 3)
    const w = mountLightbox(memoryKey, items, 0)
    await flushPromises()
    window.dispatchEvent(new KeyboardEvent('keydown', { key: '+' }))
    await flushPromises()
    expect(scaleOf(w), 'control: zoomed').toBe(1.5)
    await swipe(w, -150)
    expect(position(w)).toBe(t('media.itemPosition', { index: 1, count: 3 }))
    window.dispatchEvent(new KeyboardEvent('keydown', { key: '0' }))
    await flushPromises()
    await swipe(w, -150)
    expect(position(w)).toBe(t('media.itemPosition', { index: 2, count: 3 }))
    w.unmount()
  })

  it('pages on a stage that shows no zoomable photo too (a blocked medium)', async () => {
    const memoryKey = v1.randomBytes(32)
    const { items } = await seedImages(memoryKey, 2)
    const w = mountLightbox(memoryKey, [{ ...items[0], blocked: true }, items[1]], 0)
    await flushPromises()
    expect(w.find('.media-lightbox-stage').classes(), 'control: nothing to zoom').not.toContain('media-lightbox-stage-zoomable')
    await swipe(w, -150)
    expect(position(w)).toBe(t('media.itemPosition', { index: 2, count: 2 }))
    w.unmount()
  })
})

// The slideshow (cr-lightbox-zoom-slideshow, uc.memory.gallery 3a), on fake interval/timeout/Date clocks (setImmediate stays
// real, so `flushPromises` works): every 5 s one step through the items, a video's poster for one interval and never played,
// round past the last item until paused, a pause on every interaction and a hidden tab, one preview ahead, and no EXIF read or place
// backfill while it runs.
describe('MediaLightbox — slideshow (cr-lightbox-zoom-slideshow)', () => {
  beforeEach(() => vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval', 'setTimeout', 'clearTimeout', 'Date'] }))
  afterEach(() => vi.useRealTimers())

  const FTYP_MP4 = new Uint8Array([0, 0, 0, 0, 0x66, 0x74, 0x79, 0x70, 0x69, 0x73, 0x6f, 0x6d])
  /** Seed media of the given types, in that order. */
  async function seed(memoryKey: Uint8Array, types: string[]) {
    const store = galleryFakeStore()
    fakeApi(mediaApi, store.api)
    fakeApi(blobApi, store.blob)
    vi.stubGlobal('fetch', store.fetchStub)
    for (const [i, mediaType] of types.entries()) {
      const bytes = mediaType.startsWith('video/') ? FTYP_MP4 : new Uint8Array(8).fill(i + 1)
      await addMedia(SCOPE_ID, NODE_ID, memoryKey, { blob: new Blob([bytes as BlobPart]), mediaType }, stubDeriver())
    }
    return { store, items: await listMedia(SCOPE_ID, NODE_ID) }
  }
  const position = (w: ReturnType<typeof mountLightbox>) => w.find('.media-lightbox-title small').text()
  const at = (index: number, count = 3) => t('media.itemPosition', { index, count })
  const toggle = (w: ReturnType<typeof mountLightbox>) => w.find('[data-slideshow-toggle]')
  const status = (w: ReturnType<typeof mountLightbox>) => w.find('[data-testid="slideshow-status"]').text()
  async function advance(ms: number) {
    await vi.advanceTimersByTimeAsync(ms)
    await flushPromises()
  }
  /** The toggle as a pointer presses it: its pointerdown reaches the lightbox's pause listener first. */
  async function press(w: ReturnType<typeof mountLightbox>) {
    toggle(w).element.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true, pointerId: 1, button: 0 }))
    await toggle(w).trigger('click')
    await flushPromises()
  }
  /** The member's props with the preview, original and EXIF loaders recorded. */
  function recorded(memoryKey: Uint8Array) {
    const p = memberProps(memoryKey)
    return { ...p, previewLoader: vi.fn(p.previewLoader), originalLoader: vi.fn(p.originalLoader), exifLoader: vi.fn(p.exifLoader) }
  }

  it('advances every 5 s through the items and goes round past the last, announced', async () => {
    const memoryKey = v1.randomBytes(32)
    const { items } = await seed(memoryKey, ['image/png', 'image/jpeg', 'image/png'])
    const w = mountLightbox(memoryKey, items, 0)
    await flushPromises()
    expect(toggle(w).attributes('aria-label')).toBe(t('media.slideshowStart'))
    expect(status(w)).toBe('')
    await press(w)
    expect(toggle(w).attributes('aria-label')).toBe(t('media.slideshowPause'))
    expect(status(w)).toBe(t('media.slideshowPlaying'))
    await advance(4999)
    expect(position(w)).toBe(at(1))
    await advance(1)
    expect(position(w)).toBe(at(2))
    await press(w) // the toggle's own pointer pauses it - and its click does not start it again
    expect(status(w)).toBe(t('media.slideshowPaused'))
    await advance(10000)
    expect(position(w)).toBe(at(2))
    await press(w)
    expect(status(w)).toBe(t('media.slideshowPlaying'))
    await advance(5000)
    expect(position(w)).toBe(at(3))
    expect(status(w), 'the last item is no end').toBe(t('media.slideshowPlaying'))
    await advance(5000)
    expect(position(w), 'past the last comes the first').toBe(at(1))
    expect(toggle(w).attributes('aria-label')).toBe(t('media.slideshowPause'))
    await advance(5000)
    expect(position(w), 'and on, a second round').toBe(at(2))
    w.unmount()
  })

  // The music plays on beside the slideshow (en.platform.audio-player): core's compact controls sit in the top bar while a
  // queue is loaded, and a press on them is no interaction with the slideshow.
  it('shows the music controls while music plays, and a press on them does not pause the slideshow', async () => {
    const memoryKey = v1.randomBytes(32)
    const { items } = await seed(memoryKey, ['image/png', 'image/jpeg', 'image/png'])
    const player = audioPlayer()
    const w = mountLightbox(memoryKey, items, 0)
    await flushPromises()
    expect(w.find('.media-lightbox-topbar [data-audio-controls]').exists(), 'no queue: no controls').toBe(false)
    expect(w.find('.media-lightbox').classes(), 'no music: one row').not.toContain('has-music')
    player.nowPlaying.value = { key: 'k', trackId: 'a', title: 'Summer Song', playing: true, positionMs: 0, durationMs: 1000 }
    try {
      await flushPromises()
      expect(w.find('.media-lightbox-topbar [data-testid="audio-mini-title"]').text()).toBe('Summer Song')
      expect(w.find('.media-lightbox').classes(), 'music: a narrow screen gives it a row of its own').toContain('has-music')
      await press(w)
      expect(status(w)).toBe(t('media.slideshowPlaying'))
      w.find('[data-testid="audio-mini-toggle"]').element.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true, pointerId: 1, button: 0 }))
      await flushPromises()
      expect(status(w), 'the music control is no interaction with the slideshow').toBe(t('media.slideshowPlaying'))
      await advance(5000)
      expect(position(w)).toBe(at(2))
    } finally {
      player.nowPlaying.value = null
      w.unmount()
    }
  })

  it('a video shows its poster for one interval and is never played', async () => {
    const memoryKey = v1.randomBytes(32)
    const { items } = await seed(memoryKey, ['image/png', 'video/mp4', 'image/png'])
    const props = recorded(memoryKey)
    const w = mountLightbox(memoryKey, items, 0, props)
    await flushPromises()
    await press(w)
    await advance(5000)
    expect(position(w)).toBe(at(2))
    expect(w.find('.media-lightbox-video-poster').exists(), 'the poster').toBe(true)
    expect(w.find('.media-lightbox-stage video').exists(), 'no <video>').toBe(false)
    await advance(4999)
    expect(position(w)).toBe(at(2))
    await advance(1)
    expect(position(w)).toBe(at(3))
    expect(registerPlayback).not.toHaveBeenCalled()
    expect(props.originalLoader).not.toHaveBeenCalled()
    w.unmount()
  })

  // Each interaction on its own lightbox: the slideshow pauses, says so, and the clock no longer moves it.
  it('pauses on a key, a pointer, a wheel, the details panel, a hidden tab and suspendKeys — not on its own toggle', async () => {
    const memoryKey = v1.randomBytes(32)
    const { items } = await seed(memoryKey, ['image/png', 'image/jpeg', 'image/png'])
    const interactions: Record<string, (w: ReturnType<typeof mountLightbox>) => Promise<unknown> | void> = {
      key: () => void window.dispatchEvent(new KeyboardEvent('keydown', { key: 'x' })),
      pointer: (w) => void w.find('.media-lightbox-stage').element.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true, pointerId: 1, button: 0 })),
      wheel: (w) => void w.find('.media-lightbox-stage').element.dispatchEvent(new WheelEvent('wheel', { bubbles: true, deltaY: 10 })),
      details: (w) => void w.find('.media-lightbox-details').element.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true, pointerId: 1, button: 0 })),
      hidden: () => {
        Object.defineProperty(document, 'visibilityState', { value: 'hidden', configurable: true })
        document.dispatchEvent(new Event('visibilitychange'))
      },
      suspendKeys: (w) => w.setProps({ suspendKeys: true }),
    }
    for (const [name, interact] of Object.entries(interactions)) {
      // attached, so a key dispatched on the toggle bubbles up to the window listener as a real one does
      const w = mount(MediaLightbox, {
        props: { memoryKey, items, startIndex: 0, memoryName: 'Rom 2026', ...memberProps(memoryKey) },
        global: { plugins: [realI18n()] },
        attachTo: document.body,
      })
      await flushPromises()
      await press(w)
      expect(status(w), `${name}: the toggle's own press starts it`).toBe(t('media.slideshowPlaying'))
      let reached = false
      const probe = () => { reached = true }
      window.addEventListener('keydown', probe)
      toggle(w).element.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }))
      window.removeEventListener('keydown', probe)
      expect(reached, 'the key reached the window listener').toBe(true)
      await flushPromises()
      expect(status(w), `${name}: Enter on the toggle is the toggle's`).toBe(t('media.slideshowPlaying'))
      await interact(w)
      await flushPromises()
      expect(status(w), name).toBe(t('media.slideshowPaused'))
      expect(toggle(w).attributes('aria-label'), name).toBe(t('media.slideshowStart'))
      await advance(15000)
      expect(position(w), name).toBe(at(1))
      Object.defineProperty(document, 'visibilityState', { value: 'visible', configurable: true })
      w.unmount()
    }
  })

  it('waits for a slow preview: the clock never skips past an item that is still loading', async () => {
    const memoryKey = v1.randomBytes(32)
    const { items } = await seed(memoryKey, ['image/png', 'image/jpeg', 'image/png'])
    const props = recorded(memoryKey)
    let release: () => void = () => {}
    const real = props.previewLoader.getMockImplementation()!
    props.previewLoader.mockImplementation(async (it: MediaItem) => {
      if (it.mediaId === items[1].mediaId) await new Promise<void>((r) => { release = r })
      return real(it)
    })
    const w = mountLightbox(memoryKey, items, 0, props)
    await flushPromises()
    await press(w)
    await advance(5000)
    expect(position(w)).toBe(at(2))
    await advance(15000)
    expect(position(w), 'still loading: no step').toBe(at(2))
    release()
    await flushPromises()
    await advance(5000)
    expect(position(w)).toBe(at(3))
    w.unmount()
  })

  it('stays one preview ahead and shows each without a second fetch, paused or not', async () => {
    const memoryKey = v1.randomBytes(32)
    // Four, so the one two steps on is neither neighbour: paging goes round, the last is the first one's other neighbour.
    const { items } = await seed(memoryKey, ['image/png', 'image/jpeg', 'image/png', 'image/jpeg'])
    const props = recorded(memoryKey)
    const w = mountLightbox(memoryKey, items, 0, props)
    await flushPromises()
    const fetchedFor = (i: number) => props.previewLoader.mock.calls.filter(([it]) => it.mediaId === items[i].mediaId).length
    expect(items.map((_, i) => fetchedFor(i)), 'one ahead each way, nothing further').toEqual([1, 1, 0, 1])
    await press(w)
    await advance(5000)
    expect(position(w)).toBe(at(2, 4))
    expect(w.find('.media-lightbox-stage img').attributes('src')).toBeTruthy()
    expect(fetchedFor(1), 'shown from the read ahead').toBe(1)
    expect(fetchedFor(2), 'and the next one ahead').toBe(1)
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'x' })) // pause: what was read ahead stays
    await flushPromises()
    await w.find('.lightbox-nav-next').trigger('click')
    await flushPromises()
    expect(fetchedFor(2), 'kept while the lightbox is open').toBe(1)
    w.unmount()
  })

  it('reads no EXIF and backfills no place while it runs; pausing on an item runs both', async () => {
    resetPlaceNameCache()
    mapConfig.maps = true
    mapConfig.geocoder = 'https://geo.example'
    const memoryKey = v1.randomBytes(32)
    const store = galleryFakeStore()
    fakeApi(mediaApi, store.api)
    fakeApi(blobApi, store.blob)
    const geo = vi.fn(async (url: string, init?: RequestInit) =>
      String(url).includes('geo.example')
        ? ({ ok: true, status: 200, json: async () => ({ address: { city: 'Ort', country: 'Land' } }) } as unknown as Response)
        : store.fetchStub(url, init),
    )
    vi.stubGlobal('fetch', geo)
    for (const [i, lat] of [48.1, 50.9, 52.5].entries()) { // three places apart, so each is a geocoder request of its own
      vi.mocked(readExifTags).mockResolvedValue({ Make: 'Canon', latitude: lat, longitude: 11.5 })
      await addMedia(SCOPE_ID, NODE_ID, memoryKey, { blob: new Blob([new Uint8Array(8).fill(i + 1) as BlobPart]), mediaType: 'image/jpeg' }, stubDeriver())
    }
    const items = await listMedia(SCOPE_ID, NODE_ID)
    const props = recorded(memoryKey)
    const w = mountLightbox(memoryKey, items, 0, props)
    await advance(2000)
    const geocoded = () => geo.mock.calls.filter((c) => String(c[0]).includes('geo.example')).length
    const exifFor = (i: number) => props.exifLoader.mock.calls.filter(([it]) => it.mediaId === items[i].mediaId).length
    expect([exifFor(0), geocoded()], 'control: the first item, opened, reads and resolves').toEqual([1, 1])
    const writes = store.api.updateMediaMeta.mock.calls.length
    expect(writes, 'control: and backfills').toBeGreaterThan(0)
    await press(w)
    await advance(5000)
    expect(position(w)).toBe(at(2))
    await advance(3000)
    expect(exifFor(1), 'no EXIF read while it runs').toBe(0)
    expect(geocoded(), 'no geocoder request').toBe(1)
    expect(store.api.updateMediaMeta.mock.calls.length, 'no backfill').toBe(writes)
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'x' })) // pause on the second item
    await advance(2000)
    expect(exifFor(1), 'pausing reads it').toBe(1)
    expect(geocoded(), 'and resolves its place').toBe(2)
    expect(store.api.updateMediaMeta.mock.calls.length).toBe(writes + 1)
    w.unmount()
  })

  // The shuffle (inbox #862): rounds of every item once, the slideshow only. `Math.random` pinned to 0 makes the rounds
  // exact: Fisher-Yates swaps each slot with the first, so after the start item (1) the round is 3, 4, 2; the next round
  // would open with 2 - the one the last ended on - so its head is swapped with the second: 3, 2, 4, 1. The grid order
  // (2, 3, 4, 1, 2, …) differs at the first step.
  describe('shuffle (#862)', () => {
    const shuffleToggle = (w: ReturnType<typeof mountLightbox>) => w.find('[data-slideshow-shuffle]')
    async function pressShuffle(w: ReturnType<typeof mountLightbox>) {
      shuffleToggle(w).element.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true, pointerId: 1, button: 0 }))
      await shuffleToggle(w).trigger('click')
      await flushPromises()
    }
    const four = ['image/png', 'image/jpeg', 'image/png', 'image/jpeg']

    it('is offered from three items on, as a pressed toggle that does not pause a running slideshow', async () => {
      const memoryKey = v1.randomBytes(32)
      const { items } = await seed(memoryKey, ['image/png', 'image/jpeg', 'image/png'])
      const two = mountLightbox(memoryKey, items.slice(0, 2), 0)
      await flushPromises()
      expect(toggle(two).exists(), 'control: the slideshow is offered').toBe(true)
      expect(shuffleToggle(two).exists(), 'two items: the grid order or its reverse').toBe(false)
      two.unmount()
      const w = mountLightbox(memoryKey, items, 0)
      await flushPromises()
      expect(shuffleToggle(w).attributes('aria-pressed')).toBe('false')
      expect(shuffleToggle(w).attributes('aria-label')).toBe(t('media.slideshowShuffle'))
      await press(w)
      await pressShuffle(w)
      expect(shuffleToggle(w).attributes('aria-pressed')).toBe('true')
      expect(status(w), 'pressing it is no interaction with the slideshow').toBe(t('media.slideshowPlaying'))
      shuffleToggle(w).element.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }))
      await flushPromises()
      expect(status(w), 'nor is Enter on it').toBe(t('media.slideshowPlaying'))
      w.unmount()
    })

    it('shows each item once per round in a shuffled order, and the next round never opens with the last one', async () => {
      const memoryKey = v1.randomBytes(32)
      const { items } = await seed(memoryKey, four)
      const w = mountLightbox(memoryKey, items, 0)
      await flushPromises()
      const random = vi.spyOn(Math, 'random').mockReturnValue(0)
      try {
        await pressShuffle(w)
        await press(w)
        const seen: string[] = []
        for (let i = 0; i < 7; i++) {
          await advance(5000)
          seen.push(position(w))
        }
        expect(seen).toEqual([3, 4, 2, 3, 2, 4, 1].map((n) => at(n, 4)))
      } finally {
        random.mockRestore()
      }
      w.unmount()
    })

    it('orders the slideshow only: by hand it pages in the grid order, and off again the slideshow does too', async () => {
      const memoryKey = v1.randomBytes(32)
      const { items } = await seed(memoryKey, four)
      const w = mountLightbox(memoryKey, items, 0)
      await flushPromises()
      const random = vi.spyOn(Math, 'random').mockReturnValue(0)
      try {
        await pressShuffle(w)
        window.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight' }))
        await flushPromises()
        expect(position(w), 'by hand: the grid order').toBe(at(2, 4))
        await press(w)
        await advance(5000)
        expect(position(w), 'the slideshow: the round, here 3 after 2').toBe(at(3, 4))
        await pressShuffle(w)
        expect(shuffleToggle(w).attributes('aria-pressed')).toBe('false')
        await advance(5000)
        expect(position(w), 'off: the grid order again').toBe(at(4, 4))
      } finally {
        random.mockRestore()
      }
      w.unmount()
    })

    it("reads the round's next item ahead, though it is no neighbour of the shown one", async () => {
      const memoryKey = v1.randomBytes(32)
      const { items } = await seed(memoryKey, four)
      const props = recorded(memoryKey)
      const w = mountLightbox(memoryKey, items, 0, props)
      await flushPromises()
      const readsOf = (i: number) => props.previewLoader.mock.calls.filter(([it]) => it.mediaId === items[i].mediaId).length
      expect(readsOf(2), 'control: two steps on is no neighbour').toBe(0)
      const random = vi.spyOn(Math, 'random').mockReturnValue(0)
      try {
        await pressShuffle(w)
      } finally {
        random.mockRestore()
      }
      await flushPromises()
      expect(readsOf(2), 'the round opens with it: read ahead').toBe(1)
      w.unmount()
    })

    it('skips an item removed meanwhile', async () => {
      const memoryKey = v1.randomBytes(32)
      const { items } = await seed(memoryKey, four)
      const held = reactive(items.map((it) => ({ ...it })))
      const w = mountLightbox(memoryKey, held, 0)
      await flushPromises()
      const random = vi.spyOn(Math, 'random').mockReturnValue(0)
      try {
        await pressShuffle(w) // the round: 3, 4, 2
        held.splice(2, 1) // item 3 goes; the grid is now 1, 2, 4 (4 at position 3)
        await flushPromises()
        await press(w)
        await advance(5000)
        expect(w.find('.media-lightbox-title small').text(), 'the round goes on with 4').toBe(at(3, 3))
        await advance(5000)
        expect(position(w), 'then 2').toBe(at(2, 3))
      } finally {
        random.mockRestore()
      }
      w.unmount()
    })
  })
})

// uc.memory.buckets § Correct a selection's capture time: opened from the organizer's selection, the save of a selected medium
// offers only it or every selected one by its offset; the shift is the detail's (`useCaptureTimeShift`), run here by a host
// that wires it as `MemoryDetail` does — the member's real media-meta door over the fake store, the grid patched in place.
describe('MediaLightbox — a selection\'s capture-time shift', () => {
  const MIN = 60_000
  const T1 = Date.UTC(2024, 5, 1, 10, 0, 0)
  const T2 = Date.UTC(2024, 5, 1, 10, 20, 0)
  const T3 = Date.UTC(2024, 5, 1, 11, 0, 0)
  /** The date field's value for `ms`: local wall-clock to the minute, as the lightbox writes it. */
  const local = (ms: number) => {
    const d = new Date(ms)
    const p = (n: number) => String(n).padStart(2, '0')
    return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}`
  }

  /** Three photos taken at `times` (undefined: no capture time at all), in that order. */
  async function seedThree(memoryKey: Uint8Array, times: (number | undefined)[]) {
    const store = galleryFakeStore()
    fakeApi(mediaApi, store.api)
    fakeApi(blobApi, store.blob)
    vi.stubGlobal('fetch', store.fetchStub)
    for (const [i, at] of times.entries()) {
      vi.mocked(readExifTags).mockResolvedValueOnce(at === undefined ? undefined : { DateTimeOriginal: at })
      await addMedia(SCOPE_ID, NODE_ID, memoryKey, { blob: new Blob([new Uint8Array(8).fill(i + 1) as BlobPart]), mediaType: 'image/jpeg' }, stubDeriver())
    }
    const items = reactive(await listMedia(SCOPE_ID, NODE_ID)) as MediaItem[]
    return { store, items }
  }

  /** The lightbox as `MemoryDetail` mounts it from the organizer, with the shift wired to the real door. */
  function mountShift(memoryKey: Uint8Array, items: MediaItem[], startIndex: number, selected: string[], extra: Record<string, unknown> = {}) {
    const props = memberProps(memoryKey)
    const Host = defineComponent({
      setup() {
        const { run } = useCaptureTimeShift({
          door: () => props.mediaMeta,
          scopeId: () => SCOPE_ID,
          memoryKey: () => memoryKey,
          node: () => props.memoryNode,
          metaOf: props.metaResolver,
          onUpdated: (p) => { // the gallery's `onCapturedAtUpdated`: the listed item patched in place
            const it = items.find((m) => m.mediaId === p.mediaId)
            if (it) Object.assign(it, { wrappedMeta: p.wrappedMeta ?? undefined, metaRevision: p.metaRevision })
          },
        })
        return () => h(MediaLightbox, {
          memoryKey, items, startIndex, memoryName: 'Rom 2026', ...props, captureTimeShift: { selected: new Set(selected), run }, ...extra,
        })
      },
    })
    return mount(Host, { global: { plugins: [realI18n()] } })
  }
  const field = (w: ReturnType<typeof mountShift>) => w.findComponent(VeyqDateField)
  /** The override sealed in the store for `mediaId` now — what a reload would show. */
  const storedOverride = (store: ReturnType<typeof galleryFakeStore>, memoryKey: Uint8Array, item: MediaItem) => {
    const sealed = store.media.find((m) => m.mediaId === item.mediaId)!.wrappedMeta
    return unsealMeta(SCOPE_ID, memoryKey, { ...item, wrappedMeta: sealed ?? undefined })?.capturedAtOverride
  }
  const writesOf = (store: ReturnType<typeof galleryFakeStore>, mediaId: string) =>
    store.api.updateMediaMeta.mock.calls.filter(([a]) => a.mediaId === mediaId).length

  /**
   * The conflict rule (ADR 0077): a peer corrected the third photo's time after the selection was read, so it is refused and
   * named — never shifted from the peer's value — while the other two are written in the same run (the positive control).
   * Undo then puts those two back, and leaves the refused one alone. Levers: write the conflicting medium anyway (drop the
   * snapshot, `MetaConflictError` swallowed), or let Undo skip a medium.
   */
  it('a peer\'s change on one of three refuses that one and names it; the two others shift, and Undo restores both', async () => {
    const memoryKey = v1.randomBytes(32)
    const { store, items } = await seedThree(memoryKey, [T1, T2, T3])
    const [m1, m2, m3] = items
    const PEER = Date.UTC(2023, 0, 1, 9, 0, 0)
    const peers = b64std(v1.sealField(
      { purpose: 'media-meta', scopeId: SCOPE_ID, mediaId: m3.mediaId }, memoryKey,
      new TextEncoder().encode(JSON.stringify({ v: 1, capturedAt: T3, capturedAtOverride: PEER })),
    ))
    const write = store.api.updateMediaMeta.getMockImplementation()!
    let peerFirst = true
    store.api.updateMediaMeta.mockImplementation(async (args) => {
      if (args.mediaId === m3.mediaId && peerFirst) {
        peerFirst = false
        throw { response: new Response(JSON.stringify({ code: 'stale_revision', wrappedMeta: peers, metaRevision: 1 }), { status: 409 }) }
      }
      return write(args)
    })

    const w = mountShift(memoryKey, items, 1, items.map((m) => m.mediaId))
    await flushPromises()
    await w.find(`[aria-label="${t('media.editCapturedAt')}"]`).trigger('click')
    field(w).vm.$emit('update:modelValue', local(T2 + 72 * MIN))
    await flushPromises()
    await w.find('.media-lightbox-date-form').trigger('submit')
    await flushPromises()
    expect(store.api.updateMediaMeta, 'the save asks first').not.toHaveBeenCalled()
    const all = w.find('[data-testid="capture-time-shift-all"]')
    expect(all.text()).toBe(t('media.shift.all', { count: 3, offset: '+1 h 12 min' }))
    await all.trigger('click')
    await flushPromises()

    expect([storedOverride(store, memoryKey, m1), storedOverride(store, memoryKey, m2)], 'the two shifted').toEqual([T1 + 72 * MIN, T2 + 72 * MIN])
    expect(writesOf(store, m3.mediaId), 'the refused one: one attempt, no retry over the peer').toBe(1)
    expect(toastError.mock.calls.map(([line]) => line)).toEqual([
      t('memory.buckets.shiftRefused', { names: t('memory.buckets.unnamedMedia', { n: 3 }), reason: t('memory.buckets.deleteRefusedReason.changed') }),
    ])
    expect(unsealMeta(SCOPE_ID, memoryKey, m3)?.capturedAtOverride, 'the grid learned the peer\'s time').toBe(PEER)
    expect(w.find('#captured-at-input').exists(), 'the editor closed').toBe(false)
    expect(w.text(), 'the shown photo reads as corrected').toContain(t('media.capturedAtCorrected'))

    expect(showUndo).toHaveBeenCalledTimes(1)
    const undo = showUndo.mock.calls[0][0]
    expect(undo.summary).toBe(t('memory.buckets.shifted', { count: 2 }, 2))
    toastError.mockClear()
    undo.onUndo()
    await flushPromises()
    expect([storedOverride(store, memoryKey, m1), storedOverride(store, memoryKey, m2)], 'Undo: no override again').toEqual([undefined, undefined])
    expect(writesOf(store, m3.mediaId), 'Undo leaves the refused one alone').toBe(1)
    expect(toastError).not.toHaveBeenCalled()
  })

  /**
   * The same rule when the peer's change reaches the grid first: a live poll patches the listed item in place (it is the
   * lightbox's list too) between the save's question and the choice. The shift still writes from what it planned with, so the
   * server's newer revision refuses it, instead of the shift being sealed onto the peer's tier as if it were read. Lever: run
   * the shift from the listed items instead of the plan's reading (`ShiftRead`).
   */
  it('a peer\'s change patched into the grid after the plan was read is still refused, never shifted over', async () => {
    const memoryKey = v1.randomBytes(32)
    const { store, items } = await seedThree(memoryKey, [T1, T2, T3])
    const m3 = items[2]
    const PEER = Date.UTC(2023, 0, 1, 9, 0, 0)
    const peers = b64std(v1.sealField(
      { purpose: 'media-meta', scopeId: SCOPE_ID, mediaId: m3.mediaId }, memoryKey,
      new TextEncoder().encode(JSON.stringify({ v: 1, capturedAt: T3, capturedAtOverride: PEER })),
    ))
    const write = store.api.updateMediaMeta.getMockImplementation()!
    store.api.updateMediaMeta.mockImplementation(async (args) => {
      // the server holds the peer's tier at revision 1: a write from any other revision is stale
      if (args.mediaId === m3.mediaId && args.mediaMetaUpdate.metaRevision !== 1) {
        throw { response: new Response(JSON.stringify({ code: 'stale_revision', wrappedMeta: peers, metaRevision: 1 }), { status: 409 }) }
      }
      return write(args)
    })

    const w = mountShift(memoryKey, items, 1, items.map((m) => m.mediaId))
    await flushPromises()
    await w.find(`[aria-label="${t('media.editCapturedAt')}"]`).trigger('click')
    field(w).vm.$emit('update:modelValue', local(T2 + 72 * MIN))
    await flushPromises()
    await w.find('.media-lightbox-date-form').trigger('submit')
    await flushPromises()
    Object.assign(m3, { wrappedMeta: peers, metaRevision: 1 }) // the live poll's patch, after the plan read the selection
    await w.find('[data-testid="capture-time-shift-all"]').trigger('click')
    await flushPromises()

    const sent = store.api.updateMediaMeta.mock.calls.filter(([a]) => a.mediaId === m3.mediaId)
    expect(sent.map(([a]) => a.mediaMetaUpdate.metaRevision), 'written from the revision the plan read, once').toEqual([0])
    expect(toastError.mock.calls.map(([line]) => line)).toEqual([
      t('memory.buckets.shiftRefused', { names: t('memory.buckets.unnamedMedia', { n: 3 }), reason: t('memory.buckets.deleteRefusedReason.changed') }),
    ])
    expect(storedOverride(store, memoryKey, items[0]), 'control: the first shifted in the same run').toBe(T1 + 72 * MIN)
  })

  it('the offer counts the undated, and "Only this one" corrects the shown photo alone', async () => {
    const memoryKey = v1.randomBytes(32)
    const { store, items } = await seedThree(memoryKey, [T1, T2, undefined])
    const w = mountShift(memoryKey, items, 1, items.map((m) => m.mediaId))
    await flushPromises()
    await w.find(`[aria-label="${t('media.editCapturedAt')}"]`).trigger('click')
    field(w).vm.$emit('update:modelValue', local(T2 - 30 * MIN))
    await flushPromises()
    await w.find('.media-lightbox-date-form').trigger('submit')
    await flushPromises()
    const offer = w.find('.media-lightbox-date-form').text()
    expect(offer).toContain(t('media.shift.all', { count: 3, offset: '−30 min' }))
    expect(offer).toContain(t('media.shift.undated', { count: 1 }, 1))

    await w.findAll('[data-testid="capture-time-shift-offer"] button').find((b) => b.text() === t('media.shift.onlyThis'))!.trigger('click')
    await flushPromises()
    expect(store.api.updateMediaMeta.mock.calls.map(([a]) => a.mediaId)).toEqual([items[1].mediaId])
    expect(storedOverride(store, memoryKey, items[1])).toBe(T2 - 30 * MIN)
    expect(showUndo, 'a single correction has no Undo bar').not.toHaveBeenCalled()
  })

  it('a shift writes no time into an undated photo, and Undo puts back an override a photo already had', async () => {
    const memoryKey = v1.randomBytes(32)
    const { store, items } = await seedThree(memoryKey, [T1, T2, undefined])
    // the first photo already carries a correction: Undo must restore it, not drop it
    const w0 = mountLightbox(memoryKey, items, 0)
    await flushPromises()
    await w0.find(`[aria-label="${t('media.editCapturedAt')}"]`).trigger('click')
    w0.findComponent(VeyqDateField).vm.$emit('update:modelValue', local(T1 + 5 * MIN))
    await flushPromises()
    await w0.find('.media-lightbox-date-form').trigger('submit')
    await flushPromises()
    w0.unmount()
    Object.assign(items[0], { wrappedMeta: store.media[0].wrappedMeta ?? undefined, metaRevision: 1 })
    store.api.updateMediaMeta.mockClear()

    const w = mountShift(memoryKey, items, 1, items.map((m) => m.mediaId))
    await flushPromises()
    await w.find(`[aria-label="${t('media.editCapturedAt')}"]`).trigger('click')
    field(w).vm.$emit('update:modelValue', local(T2 + 60 * MIN))
    await flushPromises()
    await w.find('.media-lightbox-date-form').trigger('submit')
    await flushPromises()
    await w.find('[data-testid="capture-time-shift-all"]').trigger('click')
    await flushPromises()
    expect(writesOf(store, items[2].mediaId), 'the undated photo').toBe(0)
    expect(storedOverride(store, memoryKey, items[2])).toBeUndefined()
    expect(storedOverride(store, memoryKey, items[0]), 'shifted from its corrected time').toBe(T1 + 65 * MIN)

    showUndo.mock.calls[0][0].onUndo()
    await flushPromises()
    expect(storedOverride(store, memoryKey, items[0]), 'its own correction is back').toBe(T1 + 5 * MIN)
    expect(storedOverride(store, memoryKey, items[1])).toBeUndefined()
  })

  it('"Shift capture time" opens with the editor open; a shown photo outside the selection saves alone, without asking', async () => {
    const memoryKey = v1.randomBytes(32)
    const { store, items } = await seedThree(memoryKey, [T1, T2, T3])
    const opened = mountShift(memoryKey, items, 0, [items[0].mediaId, items[1].mediaId], { startEditing: true })
    await flushPromises()
    expect(opened.find('#captured-at-input').exists(), 'the editor is open').toBe(true)
    opened.unmount()

    const w = mountShift(memoryKey, items, 2, [items[0].mediaId, items[1].mediaId]) // the third is not selected
    await flushPromises()
    expect(w.find('#captured-at-input').exists(), 'control: closed without startEditing').toBe(false)
    await w.find(`[aria-label="${t('media.editCapturedAt')}"]`).trigger('click')
    field(w).vm.$emit('update:modelValue', local(T3 + 10 * MIN))
    await flushPromises()
    await w.find('.media-lightbox-date-form').trigger('submit')
    await flushPromises()
    expect(w.find('[data-testid="capture-time-shift-offer"]').exists()).toBe(false)
    expect(store.api.updateMediaMeta.mock.calls.map(([a]) => a.mediaId)).toEqual([items[2].mediaId])
  })
  /**
   * Opened from the organizer's ⋯ over the selection alone: the editor stays open on every photo paged to, so the member
   * shifts through the one whose right time they know; a refused photo is named by its number in the organizer's grid,
   * not its place in the lightbox. Levers: close the editor on paging again, or name by the lightbox's list.
   */
  it('over the selection alone the editor stays open while paging, and a refusal names the grid\'s number', async () => {
    const memoryKey = v1.randomBytes(32)
    const { store, items } = await seedThree(memoryKey, [T1, T2, T3])
    const [, m2, m3] = items
    const PEER = Date.UTC(2023, 0, 1, 9, 0, 0)
    const peers = b64std(v1.sealField(
      { purpose: 'media-meta', scopeId: SCOPE_ID, mediaId: m2.mediaId }, memoryKey,
      new TextEncoder().encode(JSON.stringify({ v: 1, capturedAt: T2, capturedAtOverride: PEER })),
    ))
    const write = store.api.updateMediaMeta.getMockImplementation()!
    let peerFirst = true
    store.api.updateMediaMeta.mockImplementation(async (args) => {
      if (args.mediaId === m2.mediaId && peerFirst) {
        peerFirst = false
        throw { response: new Response(JSON.stringify({ code: 'stale_revision', wrappedMeta: peers, metaRevision: 1 }), { status: 409 }) }
      }
      return write(args)
    })
    const selection = [m2, m3]
    // As `mountShift`, with the organizer's grid numbers on the shift.
    const Host = defineComponent({
      setup() {
        const props = memberProps(memoryKey)
        const { run } = useCaptureTimeShift({
          door: () => props.mediaMeta, scopeId: () => SCOPE_ID, memoryKey: () => memoryKey, node: () => props.memoryNode,
          metaOf: props.metaResolver, onUpdated: () => {},
        })
        return () => h(MediaLightbox, {
          memoryKey, items: selection, startIndex: 0, memoryName: 'Rom 2026', ...props, startEditing: true,
          captureTimeShift: { selected: new Set(selection.map((m) => m.mediaId)), run, numbers: { [m2.mediaId]: 5, [m3.mediaId]: 7 } },
        })
      },
    })
    const box = mount(Host, { global: { plugins: [realI18n()] } })
    await flushPromises()
    expect(box.find('#captured-at-input').exists(), 'open on the first').toBe(true)
    await box.find('.lightbox-nav-next').trigger('click')
    await flushPromises()
    expect(box.find('#captured-at-input').exists(), 'still open on the photo paged to').toBe(true)
    expect(new Date(box.findComponent(VeyqDateField).props('modelValue') as string).getTime(), 'with that photo\'s time')
      .toBe(Math.floor(T3 / MIN) * MIN)
    box.findComponent(VeyqDateField).vm.$emit('update:modelValue', local(Math.floor(T3 / MIN) * MIN + 30 * MIN))
    await flushPromises()
    await box.find('.media-lightbox-date-form').trigger('submit')
    await flushPromises()
    await box.find('[data-testid="capture-time-shift-all"]').trigger('click')
    await flushPromises()
    expect(storedOverride(store, memoryKey, m3), 'the photo shifted through').toBe(T3 + 30 * MIN)
    expect(toastError.mock.calls.map(([line]) => line)).toEqual([
      t('memory.buckets.shiftRefused', { names: t('memory.buckets.unnamedMedia', { n: 5 }), reason: t('memory.buckets.deleteRefusedReason.changed') }),
    ])
    box.unmount()
  })
})
