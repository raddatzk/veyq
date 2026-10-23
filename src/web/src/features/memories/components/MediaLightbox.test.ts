// @vitest-environment happy-dom
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { createRouter, createMemoryHistory } from 'vue-router'

vi.mock('../../../api/client', () => ({
  blobApi: vi.fn(),
  loginApi: vi.fn(),
  tokenApi: vi.fn(),
  sessionApi: vi.fn(),
}))
vi.mock('../api', () => ({ mediaApi: vi.fn() }))

// exifr does the real parse in the browser; here we drive it so the sealed EXIF tier is deterministic.
vi.mock('exifr', () => ({ default: { parse: vi.fn(async () => undefined) } }))

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

// The Slice-2 SW registrar (there is no real Service Worker in the test env). Default: no usable SW → every play
// takes the Slice-1 fallback; individual tests override registerPlayback to simulate a controlling SW.
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
const toastError = vi.hoisted(() => vi.fn())
vi.mock('../../../shared/state/useToast', () => ({
  useToast: () => ({ success: vi.fn(), error: toastError, showUndo: vi.fn(), dismissUndo: vi.fn() }),
}))

import { reactive } from 'vue'
import { mount, flushPromises } from '@vue/test-utils'
import MediaLightbox from './MediaLightbox.vue'
import { addMedia, listMedia, type MediaDeriver, type MediaExif, type MediaItem } from '../media'
import exifr from 'exifr'
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

  it('pages prev/next through the grid order, fetching the next item’s preview, and clamps at the ends', async () => {
    const memoryKey = v1.randomBytes(32)
    const { store, items } = await seedTwo(memoryKey)

    const wrapper = mountLightbox(memoryKey, items, 0)
    await flushPromises()

    // at the first item: previous is disabled (clamp), next enabled
    expect(wrapper.find('.lightbox-nav-previous').attributes('disabled')).toBeDefined()
    expect(wrapper.find('.lightbox-nav-next').attributes('disabled')).toBeUndefined()

    store.blob.presignBlobDownloads.mockClear()
    await wrapper.find('.lightbox-nav-next').trigger('click')
    await flushPromises()

    // media 1: PREVIEW=up-5 → next paged to it; now next is disabled at the end
    const presigned = store.blob.presignBlobDownloads.mock.calls.flatMap(
      (c) => c[0].blobDownloadsBatchRequest.items.map((it) => it.blobId),
    )
    expect(presigned).toContain(store.blobIdOfUpload('up-5'))
    expect(wrapper.find('.media-lightbox-title small').text()).toBe(t('media.itemPosition', { index: 2, count: 2 }))
    expect(wrapper.find('.lightbox-nav-next').attributes('disabled')).toBeDefined()
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
  // move with it. Since inbox #397 the name is the original's blob meta (the EXIF tier carries none — exifr finds
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

describe('MediaLightbox — video playback from the blob meta type (cr-inline-video-playback, #104/#105; inbox #397)', () => {
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

describe('MediaLightbox — Slice 2 SW switchover (cr-inline-video-playback, ADR 0047)', () => {
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

  it('falls back to the Slice-1 full-blob path when no SW is usable', async () => {
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
    vi.mocked(exifr.parse).mockResolvedValue({
      DateTimeOriginal: new Date(Date.UTC(2021, 4, 1, 10, 0, 0)),
      Make: 'Canon', Model: 'EOS R', LensModel: 'RF 50mm', FocalLength: 50, FNumber: 1.8, ExposureTime: 0.008, ISO: 200,
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
    vi.mocked(exifr.parse).mockResolvedValue({
      Make: 'Canon', Model: 'EOS R', FocalLength: 50, FocalLengthIn35mmFormat: 75,
      ExposureCompensation: -0.7, Flash: 9, Orientation: 6, ISO: 200,
      Software: 'veyq fixture 1.0', Artist: 'A. Photographer', Copyright: '(c) 2021 A. Photographer',
      Rating: 4,
      title: { lang: 'x-default', value: 'Marienplatz' },
      description: { lang: 'x-default', value: 'Marienplatz at golden hour' },
      GPSAltitude: 519.4, GPSAltitudeRef: { 0: 0 },
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
    vi.mocked(exifr.parse).mockResolvedValue({ Make: 'Canon', Flash: 16 })

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
    vi.mocked(exifr.parse).mockResolvedValue({ Make: 'Canon', latitude: 48.137, longitude: 11.575 })
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
    vi.mocked(exifr.parse).mockResolvedValue({
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
    vi.mocked(exifr.parse).mockResolvedValue(undefined) // no readable EXIF → nothing sealed

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
  async function seedOne(memoryKey: Uint8Array, exifRaw: unknown) {
    vi.mocked(exifr.parse).mockResolvedValue(exifRaw)
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
    const { store, items } = await seedOne(memoryKey, { DateTimeOriginal: new Date(Date.UTC(2021, 4, 1, 10, 0, 0)) })

    const wrapper = mountLightbox(memoryKey, items, 0)
    await flushPromises()
    expect(wrapper.text()).not.toContain(t('media.capturedAtCorrected'))

    // open the editor, set a corrected date, save
    await wrapper.find(`[aria-label="${t('media.editCapturedAt')}"]`).trigger('click')
    await wrapper.find('#captured-at-input').setValue('2019-08-15T08:30')
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

  it('lets a user SET a date on a media that has no EXIF at all', async () => {
    const memoryKey = v1.randomBytes(32)
    const { store, items } = await seedOne(memoryKey, undefined) // no readable EXIF

    const wrapper = mountLightbox(memoryKey, items, 0)
    await flushPromises()
    // no capture date and no EXIF — the panel lists only the file's own facts (inbox #397) — but the edit affordance is
    // still there (set a fresh date)
    expect(wrapper.findAll('.media-lightbox-metadata dt').map((d) => d.text())).not.toContain(t('media.captured'))

    await wrapper.find(`[aria-label="${t('media.editCapturedAt')}"]`).trigger('click')
    await wrapper.find('#captured-at-input').setValue('2019-08-15T08:30')
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
    const { store, items } = await seedOne(memoryKey, { DateTimeOriginal: new Date(Date.UTC(2021, 4, 1, 10, 0, 0)) })
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
    await wrapper.find('#captured-at-input').setValue('2019-08-15T08:30')
    await wrapper.find('.media-lightbox-date-form').trigger('submit')
    await flushPromises()

    expect(store.api.updateMediaMeta).toHaveBeenCalledTimes(1) // no retry over the peer's own field
    expect(wrapper.text()).toContain(t('media.capturedAtConflict'))
    expect(wrapper.find('#captured-at-input').exists()).toBe(true) // the editor is still open…
    expect(new Date((wrapper.find('#captured-at-input').element as HTMLInputElement).value).getTime()).toBe(PEER) // …on theirs
    expect(wrapper.emitted('updated')?.at(-1)?.[0]).toEqual({ mediaId: items[0].mediaId, wrappedMeta: peers, metaRevision: 1 })
  })

  /**
   * inbox #290 (ADR 0077 point 4, no reset): a listed tier that does not open is never rebuilt from empty. The save
   * is refused before anything is sent, and the editor says in words that these details cannot be changed.
   */
  it('a details tier that does not open refuses the save with a message, and sends nothing', async () => {
    const memoryKey = v1.randomBytes(32)
    const { store, items } = await seedOne(memoryKey, { DateTimeOriginal: new Date(Date.UTC(2021, 4, 1, 10, 0, 0)) })
    const damaged = { ...items[0], wrappedMeta: b64std(v1.randomBytes(64)) } // present, and opens under no key

    const wrapper = mountLightbox(memoryKey, [damaged], 0)
    await flushPromises()
    await wrapper.find(`[aria-label="${t('media.editCapturedAt')}"]`).trigger('click')
    await wrapper.find('#captured-at-input').setValue('2019-08-15T08:30')
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
    expect(vi.mocked(openShareOriginal).mock.calls[0][0]).toBe('share-1')
    expect(store.blob.presignBlobDownloads, "no member read").not.toHaveBeenCalled()
    expect(scaleOf(w)).toBe(3)
    w.unmount()
  })
})

// The slideshow (cr-lightbox-zoom-slideshow, uc.memory.gallery 3a), on fake interval/timeout/Date clocks (setImmediate stays
// real, so `flushPromises` works): every 5 s one step through the items, a video's poster for one interval and never played,
// a stop ON the last item, a pause on every interaction and a hidden tab, one preview ahead, and no EXIF read or place
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

  it('advances every 5 s through the items and stops on the last, announced', async () => {
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
    expect(status(w), 'it stops ON the last item').toBe(t('media.slideshowEnded'))
    expect(toggle(w).attributes('aria-label')).toBe(t('media.slideshowStart'))
    expect(toggle(w).attributes('disabled'), 'nothing left to play').toBeDefined()
    await advance(15000)
    expect(position(w)).toBe(at(3))
    w.unmount()
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

  it('fetches one preview ahead, shows it without a second fetch, and drops it when it is not shown', async () => {
    const memoryKey = v1.randomBytes(32)
    const { items } = await seed(memoryKey, ['image/png', 'image/jpeg', 'image/png'])
    const props = recorded(memoryKey)
    const w = mountLightbox(memoryKey, items, 0, props)
    await flushPromises()
    const fetchedFor = (i: number) => props.previewLoader.mock.calls.filter(([it]) => it.mediaId === items[i].mediaId).length
    expect([fetchedFor(0), fetchedFor(1), fetchedFor(2)]).toEqual([1, 0, 0])
    await press(w)
    expect(fetchedFor(1), 'the next one, ahead').toBe(1)
    expect(fetchedFor(2), 'only one ahead').toBe(0)
    await advance(5000)
    expect(position(w)).toBe(at(2))
    expect(w.find('.media-lightbox-stage img').attributes('src')).toBeTruthy()
    expect(fetchedFor(1), 'shown from the prefetch').toBe(1)
    expect(fetchedFor(2), 'and the next one ahead').toBe(1)
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'x' })) // pause: the prefetch of the third is dropped
    await flushPromises()
    await w.find('.lightbox-nav-next').trigger('click')
    await flushPromises()
    expect(fetchedFor(2), 'nothing kept for an item that was not on screen').toBe(2)
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
      vi.mocked(exifr.parse).mockResolvedValue({ Make: 'Canon', latitude: lat, longitude: 11.5 })
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
})
