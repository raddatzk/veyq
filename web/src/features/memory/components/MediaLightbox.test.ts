// @vitest-environment happy-dom
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'

vi.mock('../../../api/client', () => ({
  mediaApi: vi.fn(),
  loginApi: vi.fn(),
  tokenApi: vi.fn(),
  sessionApi: vi.fn(),
}))

// exifr does the real parse in the browser; here we drive it so the sealed EXIF tier is deterministic.
vi.mock('exifr', () => ({ default: { parse: vi.fn(async () => undefined) } }))

// The Slice-2 SW registrar (there is no real Service Worker in the test env). Default: no usable SW → every play
// takes the Slice-1 fallback; individual tests override registerPlayback to simulate a controlling SW.
vi.mock('../../../sw/register', () => ({
  registerPlayback: vi.fn(async () => null),
  revokePlayback: vi.fn(),
  handleFromUrl: (url: string) => url.replace('/__media/', ''),
}))

import { mount, flushPromises } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import MediaLightbox from './MediaLightbox.vue'
import { addMedia, listMedia, unsealMeta, type MediaDeriver, type MediaItem } from '../media'
import exifr from 'exifr'
import { mediaApi } from '../../../api/client'
import { registerPlayback, revokePlayback } from '../../../sw/register'
import * as v1 from '../../../shared/crypto/v1'
import { galleryFakeStore } from './galleryFakeStore'

const SCOPE_ID = '11111111-1111-1111-1111-111111111111'
const NODE_ID = '22222222-2222-2222-2222-222222222222'

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  messages: {
    en: {
      common: { save: 'Save', cancel: 'Cancel' },
      memory: { cover: { setFromPhoto: 'Set as cover', isCover: 'Current cover' } },
      media: {
        itemPosition: '{index} of {count}',
        download: 'Download original',
        playVideo: 'Play video',
        close: 'Close',
        delete: 'Move to trash',
        previous: 'Previous',
        next: 'Next',
        loading: 'Loading…',
        loadError: 'This item couldn’t be loaded.',
        information: 'Details',
        toggleInfo: 'Toggle details',
        noDetail: 'No details for this item.',
        captured: 'Taken',
        camera: 'Camera',
        lens: 'Lens',
        focalLength: 'Focal length',
        aperture: 'Aperture',
        exposure: 'Exposure',
        iso: 'ISO',
        dimensions: 'Dimensions',
        location: 'Location',
        status: 'Status',
        editCapturedAt: 'Edit capture time',
        resetCapturedAt: 'Reset to original',
        capturedAtCorrected: 'Corrected',
        capturedAtSaveFailed: 'Could not save the capture time.',
      },
    },
  },
})

function stubDeriver(): MediaDeriver {
  return {
    deriveImage: vi.fn(async (_b: Blob, { maxEdge }: { maxEdge: number; quality: number }) =>
      new Uint8Array([0xff, 0xd8, maxEdge & 0xff, (maxEdge >> 8) & 0xff])),
    deriveVideoPoster: vi.fn(async () => new Uint8Array([0xff, 0xd8, 0x07, 0x09])),
  }
}

/** Seed two image media and return the listed items + the live fake store. */
async function seedTwo(memoryKey: Uint8Array) {
  const store = galleryFakeStore()
  vi.mocked(mediaApi).mockReturnValue(store.api as unknown as ReturnType<typeof mediaApi>)
  vi.stubGlobal('fetch', store.fetchStub)
  await addMedia(SCOPE_ID, NODE_ID, memoryKey, { bytes: new Uint8Array(8).fill(1), mediaType: 'image/png' }, stubDeriver())
  await addMedia(SCOPE_ID, NODE_ID, memoryKey, { bytes: new Uint8Array(8).fill(2), mediaType: 'image/jpeg' }, stubDeriver())
  const items = await listMedia(SCOPE_ID, NODE_ID)
  return { store, items }
}

function mountLightbox(memoryKey: Uint8Array, items: MediaItem[], startIndex = 0, coverMediaId: string | null = null) {
  return mount(MediaLightbox, {
    props: { scopeId: SCOPE_ID, memoryKey, items, startIndex, memoryName: 'Rom 2026', coverMediaId },
    global: { plugins: [i18n] },
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
    store.api.presignBlobDownloads.mockClear()

    const wrapper = mountLightbox(memoryKey, items)
    await flushPromises()

    // media 0: original=up-0, THUMBNAIL=up-1, PREVIEW=up-2 → the lightbox opens the PREVIEW, never the original
    const presigned = store.api.presignBlobDownloads.mock.calls.flatMap(
      (c: any) => c[0].blobDownloadsBatchRequest.items.map((it: any) => it.blobId),
    )
    expect(presigned).toContain('up-2')
    expect(presigned).not.toContain('up-0')
    expect(wrapper.find('.media-lightbox-stage img').attributes('src')).toBeTruthy()
  })

  it('“Set as cover” emits setCover for the current item (cr-0073 gallery source)', async () => {
    const memoryKey = v1.randomBytes(32)
    const { items } = await seedTwo(memoryKey)
    const wrapper = mountLightbox(memoryKey, items, 0)
    await flushPromises()

    const setCover = wrapper.findAll('.media-lightbox-actions button').find((b) => b.attributes('aria-label') === 'Set as cover')
    expect(setCover).toBeTruthy()
    await setCover!.trigger('click')
    expect(wrapper.emitted('setCover')?.[0]?.[0]).toEqual({ mediaId: items[0].mediaId })
  })

  it('marks the current cover photo (aria-pressed + "Current cover") and re-clicking it is a no-op', async () => {
    const memoryKey = v1.randomBytes(32)
    const { items } = await seedTwo(memoryKey)
    const wrapper = mountLightbox(memoryKey, items, 0, items[0].mediaId) // item 0 IS the cover
    await flushPromises()

    const current = wrapper.findAll('.media-lightbox-actions button').find((b) => b.attributes('aria-label') === 'Current cover')
    expect(current).toBeTruthy()
    expect(current!.attributes('aria-pressed')).toBe('true')
    await current!.trigger('click')
    expect(wrapper.emitted('setCover')).toBeFalsy() // clicking the current cover does nothing
  })

  it('pages prev/next through the grid order, fetching the next item’s preview, and clamps at the ends', async () => {
    const memoryKey = v1.randomBytes(32)
    const { store, items } = await seedTwo(memoryKey)

    const wrapper = mountLightbox(memoryKey, items, 0)
    await flushPromises()

    // at the first item: previous is disabled (clamp), next enabled
    expect(wrapper.find('.lightbox-nav-previous').attributes('disabled')).toBeDefined()
    expect(wrapper.find('.lightbox-nav-next').attributes('disabled')).toBeUndefined()

    store.api.presignBlobDownloads.mockClear()
    await wrapper.find('.lightbox-nav-next').trigger('click')
    await flushPromises()

    // media 1: PREVIEW=up-5 → next paged to it; now next is disabled at the end
    const presigned = store.api.presignBlobDownloads.mock.calls.flatMap(
      (c: any) => c[0].blobDownloadsBatchRequest.items.map((it: any) => it.blobId),
    )
    expect(presigned).toContain('up-5')
    expect(wrapper.find('.media-lightbox-title small').text()).toBe('2 of 2')
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

    await wrapper.find('[aria-label="Close"]').trigger('click')
    expect(wrapper.emitted('close')).toHaveLength(1)

    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
    expect(wrapper.emitted('close')).toHaveLength(2)
  })

  it('download fetches the ORIGINAL and saves it with the derived name', async () => {
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
    vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:fake')
    vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {})

    const wrapper = mountLightbox(memoryKey, items, 0)
    await flushPromises()
    store.api.presignBlobDownloads.mockClear()

    await wrapper.find('[aria-label="Download original"]').trigger('click')
    await flushPromises()

    // the original (up-0), never a derivative, is fetched for download
    const presigned = store.api.presignBlobDownloads.mock.calls.flatMap(
      (c: any) => c[0].blobDownloadsBatchRequest.items.map((it: any) => it.blobId),
    )
    expect(presigned).toEqual(['up-0'])
    // the original decrypts to the stub's 8 bytes filled with 1 → not a known signature → .bin
    expect(anchors.at(-1)!.getAttribute('download')).toBe('Rom 2026 1.bin')
  })
})

describe('MediaLightbox — video playback + mediaType backfill (cr-inline-video-playback, #104/#105)', () => {
  const FTYP_MP4 = new Uint8Array([0, 0, 0, 0, 0x66, 0x74, 0x79, 0x70, 0x69, 0x73, 0x6f, 0x6d]) // "....ftypisom"

  /** Seed one media with an explicit source type + original bytes. */
  async function seedVideoLike(memoryKey: Uint8Array, mediaType: string, bytes: Uint8Array) {
    const store = galleryFakeStore()
    vi.mocked(mediaApi).mockReturnValue(store.api as unknown as ReturnType<typeof mediaApi>)
    vi.stubGlobal('fetch', store.fetchStub)
    await addMedia(SCOPE_ID, NODE_ID, memoryKey, { bytes, mediaType }, stubDeriver())
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
    const play = wrapper.find('[aria-label="Play video"]')
    expect(play.exists()).toBe(true)
    expect(wrapper.find('.media-lightbox-stage video').exists()).toBe(false)

    store.api.presignBlobDownloads.mockClear()
    await play.trigger('click')
    await flushPromises()

    // play fetched the ORIGINAL (up-0), never a derivative, and swapped in a typed <video>
    const presigned = store.api.presignBlobDownloads.mock.calls.flatMap(
      (c: any) => c[0].blobDownloadsBatchRequest.items.map((it: any) => it.blobId),
    )
    expect(presigned).toEqual(['up-0'])
    const video = wrapper.find('.media-lightbox-stage video')
    expect(video.exists()).toBe(true)
    expect(video.attributes('src')).toBeTruthy()
  })

  it('an image shows no play affordance (the <video> branch is video-only)', async () => {
    const memoryKey = v1.randomBytes(32)
    const { items } = await seedVideoLike(memoryKey, 'image/png', new Uint8Array(8).fill(1))
    const wrapper = mountLightbox(memoryKey, items, 0)
    await flushPromises()
    expect(wrapper.find('[aria-label="Play video"]').exists()).toBe(false)
    expect(wrapper.find('.media-lightbox-stage img').exists()).toBe(true)
  })

  it('lazily back-fills the sealed mediaType on the download of a pre-field media, then emits updated', async () => {
    const memoryKey = v1.randomBytes(32)
    // pre-field: an EMPTY source type → nothing sealed at upload, but the original sniffs as mp4
    const { store, items } = await seedVideoLike(memoryKey, '', FTYP_MP4)
    expect(unsealMeta(memoryKey, items[0])?.mediaType).toBeUndefined() // no signal yet
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})

    const wrapper = mountLightbox(memoryKey, items, 0)
    await flushPromises()
    await wrapper.find('[aria-label="Download original"]').trigger('click')
    await flushPromises()

    // the download sniffed the original and wrote the type back — opaque on the wire, video/mp4 once unsealed
    const sent = store.api.updateMediaMeta.mock.calls.at(-1)![0].mediaMetaUpdate.wrappedMeta
    expect(typeof sent).toBe('string')
    expect(sent).not.toContain('video/mp4')
    expect(unsealMeta(memoryKey, { wrappedMeta: sent } as MediaItem)?.mediaType).toBe('video/mp4')
    expect(wrapper.emitted('updated')?.at(-1)?.[0]).toMatchObject({ mediaId: items[0].mediaId })
  })

  it('does NOT back-fill in the read-only public viewer (no authed write path)', async () => {
    const memoryKey = v1.randomBytes(32)
    const { store, items } = await seedVideoLike(memoryKey, '', FTYP_MP4)
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})

    const wrapper = mount(MediaLightbox, {
      props: { scopeId: SCOPE_ID, memoryKey, items, startIndex: 0, memoryName: 'Rom 2026', coverMediaId: null, readonly: true },
      global: { plugins: [i18n] },
    })
    await flushPromises()
    await wrapper.find('[aria-label="Download original"]').trigger('click')
    await flushPromises()

    expect(store.api.updateMediaMeta).not.toHaveBeenCalled()
  })
})

describe('MediaLightbox — Slice 2 SW switchover (cr-inline-video-playback, ADR 0047)', () => {
  const FTYP_MP4 = new Uint8Array([0, 0, 0, 0, 0x66, 0x74, 0x79, 0x70, 0x69, 0x73, 0x6f, 0x6d])

  async function seedVideo(memoryKey: Uint8Array) {
    const store = galleryFakeStore()
    vi.mocked(mediaApi).mockReturnValue(store.api as unknown as ReturnType<typeof mediaApi>)
    vi.stubGlobal('fetch', store.fetchStub)
    await addMedia(SCOPE_ID, NODE_ID, memoryKey, { bytes: FTYP_MP4, mediaType: 'video/mp4' }, stubDeriver())
    return { store, items: await listMedia(SCOPE_ID, NODE_ID) }
  }

  it('plays via the SW handle when a controlling SW answered — no whole-original fetch — and revokes on unmount', async () => {
    const memoryKey = v1.randomBytes(32)
    const { store, items } = await seedVideo(memoryKey)
    vi.mocked(registerPlayback).mockResolvedValueOnce('/__media/handle-1')

    const wrapper = mountLightbox(memoryKey, items, 0)
    await flushPromises()
    store.api.presignBlobDownloads.mockClear()

    await wrapper.find('[aria-label="Play video"]').trigger('click')
    await flushPromises()

    // registered a key-free playback with {chunkCount, byteSize, mediaType} + the page's chunk provider
    expect(registerPlayback).toHaveBeenCalledTimes(1)
    const [meta, provider] = vi.mocked(registerPlayback).mock.calls[0]
    expect(meta.mediaType).toBe('video/mp4')
    expect(meta.chunkCount).toBeGreaterThan(0)
    expect(typeof provider.getPlaintextChunk).toBe('function')
    // the <video> src is the synthetic handle URL, and the page never fetched the whole original (the point of the SW path)
    expect(wrapper.find('.media-lightbox-stage video').attributes('src')).toBe('/__media/handle-1')
    expect(store.api.presignBlobDownloads).not.toHaveBeenCalled()

    wrapper.unmount()
    expect(revokePlayback).toHaveBeenCalledWith('handle-1')
  })

  it('falls back to the Slice-1 full-blob path when no SW is usable', async () => {
    const memoryKey = v1.randomBytes(32)
    const { store, items } = await seedVideo(memoryKey)
    vi.mocked(registerPlayback).mockResolvedValueOnce(null) // no / stale / mismatched SW

    const wrapper = mountLightbox(memoryKey, items, 0)
    await flushPromises()
    store.api.presignBlobDownloads.mockClear()

    await wrapper.find('[aria-label="Play video"]').trigger('click')
    await flushPromises()

    // fell back: fetched the ORIGINAL (up-0) and played a decrypted blob: URL — no SW handle
    const presigned = store.api.presignBlobDownloads.mock.calls.flatMap(
      (c: any) => c[0].blobDownloadsBatchRequest.items.map((it: any) => it.blobId),
    )
    expect(presigned).toEqual(['up-0'])
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

    const del = wrapper.findAll('.media-lightbox-actions button').find((b) => b.attributes('aria-label') === 'Move to trash')
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

  it('a readonly viewer (public share) has no trash button and ignores the Delete key', async () => {
    const memoryKey = v1.randomBytes(32)
    const { items } = await seedTwo(memoryKey)
    const wrapper = mount(MediaLightbox, {
      props: { scopeId: SCOPE_ID, memoryKey, items, startIndex: 0, memoryName: 'Rom 2026', coverMediaId: null, readonly: true },
      global: { plugins: [i18n] },
    })
    await flushPromises()

    expect(wrapper.findAll('.media-lightbox-actions button').some((b) => b.attributes('aria-label') === 'Move to trash')).toBe(false)
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Delete' }))
    expect(wrapper.emitted('delete')).toBeFalsy()
  })
})

describe('MediaLightbox — lazy EXIF detail panel (cr-0064)', () => {
  it('lazily loads the EXIF tier via getMediaExif and renders the decoded detail rows', async () => {
    const memoryKey = v1.randomBytes(32)
    // the source carries readable EXIF → addMedia seals it under K_mem into the (lazy) Tier-2 blob
    vi.mocked(exifr.parse).mockResolvedValue({
      DateTimeOriginal: new Date(Date.UTC(2021, 4, 1, 10, 0, 0)),
      Make: 'Canon', Model: 'EOS R', LensModel: 'RF 50mm', FocalLength: 50, FNumber: 1.8, ExposureTime: 0.008, ISO: 200,
      latitude: 48.137, longitude: 11.575,
    } as never)

    const store = galleryFakeStore()
    vi.mocked(mediaApi).mockReturnValue(store.api as unknown as ReturnType<typeof mediaApi>)
    vi.stubGlobal('fetch', store.fetchStub)
    await addMedia(SCOPE_ID, NODE_ID, memoryKey, { bytes: new Uint8Array(8).fill(3), mediaType: 'image/jpeg' }, stubDeriver())
    const items = await listMedia(SCOPE_ID, NODE_ID)

    // the list stays constant-small — no EXIF rode along
    expect((items[0] as Record<string, unknown>).wrappedExif).toBeUndefined()

    const wrapper = mountLightbox(memoryKey, items, 0)
    await flushPromises()

    // the panel fetched the exif endpoint once and unsealed it under K_mem
    expect(store.api.getMediaExif).toHaveBeenCalledWith({ scopeId: SCOPE_ID, mediaId: 'media-0' })
    const dds = wrapper.findAll('.media-lightbox-metadata dd').map((d) => d.text())
    expect(dds).toContain('Canon EOS R')
    expect(dds).toContain('RF 50mm')
    expect(dds).toContain('50 mm')
    expect(dds).toContain('f/1.8')
    expect(dds).toContain('1/125 s')
    expect(dds).toContain('200')
    expect(dds).toContain('48.137000, 11.575000') // coordinates as text (a map is a later CR)
  })

  it('shows the no-detail message when the item carries no sealed EXIF', async () => {
    const memoryKey = v1.randomBytes(32)
    vi.mocked(exifr.parse).mockResolvedValue(undefined as never) // no readable EXIF → nothing sealed

    const store = galleryFakeStore()
    vi.mocked(mediaApi).mockReturnValue(store.api as unknown as ReturnType<typeof mediaApi>)
    vi.stubGlobal('fetch', store.fetchStub)
    await addMedia(SCOPE_ID, NODE_ID, memoryKey, { bytes: new Uint8Array(8).fill(4), mediaType: 'image/png' }, stubDeriver())
    const items = await listMedia(SCOPE_ID, NODE_ID)

    const wrapper = mountLightbox(memoryKey, items, 0)
    await flushPromises()

    expect(wrapper.find('.media-lightbox-metadata').exists()).toBe(false)
    expect(wrapper.find('.media-lightbox-no-detail').text()).toBe('No details for this item.')
  })
})

describe('MediaLightbox — correct the capture time (cr-0065/0066)', () => {
  async function seedOne(memoryKey: Uint8Array, exifRaw: unknown) {
    vi.mocked(exifr.parse).mockResolvedValue(exifRaw as never)
    const store = galleryFakeStore()
    vi.mocked(mediaApi).mockReturnValue(store.api as unknown as ReturnType<typeof mediaApi>)
    vi.stubGlobal('fetch', store.fetchStub)
    await addMedia(SCOPE_ID, NODE_ID, memoryKey, { bytes: new Uint8Array(8).fill(9), mediaType: 'image/jpeg' }, stubDeriver())
    const items = await listMedia(SCOPE_ID, NODE_ID)
    return { store, items }
  }

  it('corrects the time (re-seals opaque, shows the badge), then resets to the original', async () => {
    const memoryKey = v1.randomBytes(32)
    const { store, items } = await seedOne(memoryKey, { DateTimeOriginal: new Date(Date.UTC(2021, 4, 1, 10, 0, 0)) })

    const wrapper = mountLightbox(memoryKey, items, 0)
    await flushPromises()
    expect(wrapper.text()).not.toContain('Corrected')

    // open the editor, set a corrected date, save
    await wrapper.find('[aria-label="Edit capture time"]').trigger('click')
    await wrapper.find('#captured-at-input').setValue('2019-08-15T08:30')
    await wrapper.find('.media-lightbox-date-form').trigger('submit')
    await flushPromises()

    // the correction went out only as opaque ciphertext, and the badge now shows
    const sent = store.api.updateMediaMeta.mock.calls.at(-1)![0].mediaMetaUpdate.wrappedMeta
    expect(typeof sent).toBe('string')
    expect(sent).not.toContain('2019')
    expect(wrapper.text()).toContain('Corrected')

    // reset back to the original — override removed, badge gone
    await wrapper.find('[aria-label="Edit capture time"]').trigger('click')
    const resetBtn = wrapper.findAll('.media-lightbox-date-actions button').find((b) => b.text() === 'Reset to original')!
    await resetBtn.trigger('click')
    await flushPromises()

    expect(store.api.updateMediaMeta).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).not.toContain('Corrected')
  })

  it('lets a user SET a date on a media that has no EXIF at all', async () => {
    const memoryKey = v1.randomBytes(32)
    const { store, items } = await seedOne(memoryKey, undefined) // no readable EXIF

    const wrapper = mountLightbox(memoryKey, items, 0)
    await flushPromises()
    // the panel has no detail, but the edit affordance is still there (set a fresh date)
    expect(wrapper.find('.media-lightbox-no-detail').exists()).toBe(true)

    await wrapper.find('[aria-label="Edit capture time"]').trigger('click')
    await wrapper.find('#captured-at-input').setValue('2019-08-15T08:30')
    await wrapper.find('.media-lightbox-date-form').trigger('submit')
    await flushPromises()

    const sent = store.api.updateMediaMeta.mock.calls.at(-1)![0].mediaMetaUpdate.wrappedMeta
    expect(typeof sent).toBe('string') // a fresh sealed tier
    expect(wrapper.text()).toContain('Corrected')
  })
})
