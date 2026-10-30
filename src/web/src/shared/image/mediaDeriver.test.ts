// cr-0105 follow-up — the on-device derive stage must never HANG the upload. Safari fires neither the media
// event nor `error` for an offscreen, never-played <video>, so `onceEvent` is timeout-guarded and `deriveTiers`
// degrades a failed/timed-out video decode to original-only instead of blocking before the first PUT. The real
// browser <video> decode isn't reproducible in this env — these cover the guard + the graceful fallback; the
// actual Safari frame-grab is covered by live verification.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  onceEvent, firstPresentedFrame, VIDEO_EVENT_TIMEOUT_MS, deriveTiers, createBrowserDeriver, videoPosterOf, tileVideoPosterOf, TIER_SPECS, type AddMediaSource, type MediaDeriver,
} from './mediaDeriver'

afterEach(() => vi.useRealTimers())

describe('onceEvent — the Safari silent-no-op timeout guard', () => {
  it('rejects after the timeout when neither the event nor `error` ever fires (the Safari hang)', async () => {
    vi.useFakeTimers()
    const el = new EventTarget() as unknown as HTMLMediaElement
    const settled = onceEvent(el, 'seeked', 1000)
    const rejects = expect(settled).rejects.toThrow('video_seeked_timeout')
    await vi.advanceTimersByTimeAsync(1000)
    await rejects
  })

  it('resolves on the first event and cancels the timeout', async () => {
    const el = new EventTarget()
    const settled = onceEvent(el as unknown as HTMLMediaElement, 'loadedmetadata', 1000)
    el.dispatchEvent(new Event('loadedmetadata'))
    await expect(settled).resolves.toBeUndefined()
  })

  it('rejects on the media `error` event (a genuine decode failure, not a hang)', async () => {
    const el = new EventTarget()
    const settled = onceEvent(el as unknown as HTMLMediaElement, 'seeked', 1000)
    el.dispatchEvent(new Event('error'))
    await expect(settled).rejects.toThrow('video_seeked_failed')
  })

  it('has a positive default cap', () => {
    expect(VIDEO_EVENT_TIMEOUT_MS).toBeGreaterThan(0)
  })
})

describe('firstPresentedFrame — waits for a genuinely decoded frame, timeout-guarded', () => {
  it('rejects on timeout when requestVideoFrameCallback never presents a frame (Safari offscreen)', async () => {
    vi.useFakeTimers()
    const video = { requestVideoFrameCallback: vi.fn() } as unknown as HTMLVideoElement // registered, never invoked
    const settled = firstPresentedFrame(video, 1000)
    const rejects = expect(settled).rejects.toThrow('video_frame_timeout')
    await vi.advanceTimersByTimeAsync(1000)
    await rejects
  })

  it('resolves when requestVideoFrameCallback presents a frame', async () => {
    const video = { requestVideoFrameCallback: (cb: () => void) => { cb(); return 1 } } as unknown as HTMLVideoElement
    await expect(firstPresentedFrame(video, 1000)).resolves.toBeUndefined()
  })

  it('falls back to `seeked` when requestVideoFrameCallback is unavailable (older Safari / Firefox)', async () => {
    const el = new EventTarget()
    const settled = firstPresentedFrame(el as unknown as HTMLVideoElement, 1000)
    el.dispatchEvent(new Event('seeked'))
    await expect(settled).resolves.toBeUndefined()
  })
})

describe('deriveTiers — an undecodable/hanging video degrades to original-only (no upload hang)', () => {
  const videoSource: AddMediaSource = { blob: new Blob([new Uint8Array([0, 1, 2]) as BlobPart]), mediaType: 'video/mp4' }

  it('returns [] (original only) when the poster derive rejects — via the catch, never re-thrown', async () => {
    const deriver: MediaDeriver = {
      deriveImage: vi.fn(async () => ({ bytes: new Uint8Array(), source: null })),
      deriveVideoPoster: vi.fn(async () => { throw new Error('video_seeked_timeout') }),
      probeDimensions: vi.fn(async () => null),
    }
    await expect(deriveTiers(videoSource, deriver)).resolves.toEqual({ derivatives: [], dimensions: null })
    expect(deriver.deriveVideoPoster).toHaveBeenCalledTimes(1)
  })
})

describe("videoPosterOf — a picked video's one poster, held only until its upload has it (inbox #795)", () => {
  const posterDeriver = (...answers: (() => Promise<Uint8Array>)[]): MediaDeriver => {
    const deriveVideoPoster = vi.fn(async () => (answers.shift() ?? (async () => new Uint8Array([1])))())
    return { deriveImage: vi.fn(), deriveVideoPoster }
  }

  it('draws at the PREVIEW tier, the decode on the re-typed blob, and hands every caller the one draw', async () => {
    const file = new Blob([new Uint8Array([0, 0, 0, 0x18]) as BlobPart])
    const typed = file.slice(0, file.size, 'video/mp4')
    const d = posterDeriver()
    const [a, b] = await Promise.all([videoPosterOf(file, d, typed), videoPosterOf(file, d)])
    expect(a).toBe(b)
    expect(d.deriveVideoPoster).toHaveBeenCalledTimes(1)
    expect(d.deriveVideoPoster).toHaveBeenCalledWith(typed, TIER_SPECS.PREVIEW)
  })

  it('the upload lets go of the poster once it has it, so a later upload of the file draws again', async () => {
    const file = new Blob([new Uint8Array([0, 0, 0, 0x18]) as BlobPart], { type: 'video/mp4' })
    const d = posterDeriver()
    await videoPosterOf(file, d)
    await deriveTiers({ blob: file, mediaType: 'video/mp4' }, d)
    expect(d.deriveVideoPoster, "the upload took the tile's poster").toHaveBeenCalledTimes(1)
    await deriveTiers({ blob: file, mediaType: 'video/mp4' }, d)
    expect(d.deriveVideoPoster, 'and did not keep it').toHaveBeenCalledTimes(2)
  })

  it('a tile wanted once its upload took the poster draws none; one wanted before shares the draw', async () => {
    const early = new Blob([new Uint8Array([0, 0, 0, 0x18]) as BlobPart], { type: 'video/mp4' })
    const late = new Blob([new Uint8Array([0, 0, 0, 0x19]) as BlobPart], { type: 'video/mp4' })
    const d = posterDeriver()
    const tile = tileVideoPosterOf(early, d)
    await deriveTiers({ blob: early, mediaType: 'video/mp4' }, d)
    await expect(tile).resolves.toEqual(new Uint8Array([1]))
    await deriveTiers({ blob: late, mediaType: 'video/mp4' }, d)
    await expect(tileVideoPosterOf(late, d), 'its listed thumbnail comes instead').resolves.toBeNull()
    expect(d.deriveVideoPoster, 'one draw per clip').toHaveBeenCalledTimes(2)
  })

  it('a failed draw is not kept: the next caller tries again', async () => {
    const file = new Blob([new Uint8Array([0]) as BlobPart], { type: 'video/mp4' })
    const d = posterDeriver(async () => { throw new Error('video_frame_timeout') })
    await expect(videoPosterOf(file, d)).rejects.toThrow('video_frame_timeout')
    await expect(videoPosterOf(file, d)).resolves.toEqual(new Uint8Array([1]))
    expect(d.deriveVideoPoster).toHaveBeenCalledTimes(2)
  })
})

describe('probeDimensions reads an image\'s size from its header, never from decoded pixels (inbox #558)', () => {
  // Bites: the old `createImageBitmap` probe turns every case red; reading a JPEG through the element turns the first.
  // A stand-in for the browser's `<img>`: setting `src` answers `load` (or `error`) a task later with the size the
  // header names, as an element that is never drawn does; nothing it does decodes pixels.
  type Probe = { naturalWidth: number; naturalHeight: number; fails?: boolean }
  const made: { src: string | null }[] = []
  function stubImage(probe: Probe) {
    class FakeImage {
      naturalWidth = 0
      naturalHeight = 0
      onload: (() => void) | null = null
      onerror: (() => void) | null = null
      private current: string | null = null
      constructor() {
        made.push(this as unknown as { src: string | null })
      }
      get src(): string | null {
        return this.current
      }
      set src(url: string) {
        this.current = url
        setTimeout(() => {
          if (probe.fails) return this.onerror?.()
          this.naturalWidth = probe.naturalWidth
          this.naturalHeight = probe.naturalHeight
          this.onload?.()
        }, 0)
      }
      removeAttribute(name: string) {
        if (name === 'src') this.current = null
      }
    }
    vi.stubGlobal('Image', FakeImage)
  }
  const decode = vi.fn(async () => ({ width: 1, height: 1, close: () => undefined }))
  const revoked: string[] = []
  let urls = 0

  beforeEach(() => {
    made.length = 0
    revoked.length = 0
    decode.mockClear()
    vi.stubGlobal('createImageBitmap', decode)
    vi.spyOn(URL, 'createObjectURL').mockImplementation(() => `blob:probe-${++urls}`)
    vi.spyOn(URL, 'revokeObjectURL').mockImplementation((u: string) => void revoked.push(u))
  })
  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  // 16 bytes no header reader knows (a HEIC, say): the browser's element is asked
  const photo = new Blob([new Uint8Array(16)], { type: 'image/jpeg' })

  it('a JPEG\'s size comes from its frame header: no element, no decode, a read of its first bytes only', async () => {
    stubImage({ naturalWidth: 1, naturalHeight: 1 })
    const frame = [0xff, 0xc0, 0, 17, 8, 0x0b, 0xd0, 0x0f, 0xc0, 3, 1, 0x22, 0, 2, 0x11, 1, 3, 0x11, 1]
    const jpeg = new Blob([new Uint8Array([0xff, 0xd8, ...frame, 0xff, 0xda, ...new Array(2_000_000).fill(0)])], { type: 'image/jpeg' })
    const slice = vi.spyOn(jpeg, 'slice')
    await expect(createBrowserDeriver().probeDimensions!(jpeg, 'image/jpeg')).resolves.toEqual({ w: 4032, h: 3024 })
    expect(made, 'no <img>: it would load the whole file').toHaveLength(0)
    expect(decode).not.toHaveBeenCalled()
    expect(slice.mock.calls.every(([a = 0, b = jpeg.size]) => b - a <= 64 * 1024), 'only the first 64 KiB are read').toBe(true)
  })

  it('a format the header reader does not know: the size the element reads (EXIF orientation applied by it), no decode', async () => {
    stubImage({ naturalWidth: 3024, naturalHeight: 4032 })
    await expect(createBrowserDeriver().probeDimensions!(photo, 'image/jpeg')).resolves.toEqual({ w: 3024, h: 4032 })
    expect(decode, 'no createImageBitmap: decoding every picked photo at once was the pick-time peak').not.toHaveBeenCalled()
    expect(made).toHaveLength(1)
  })

  it('lets go of the element and its object URL once it answered', async () => {
    stubImage({ naturalWidth: 640, naturalHeight: 480 })
    await createBrowserDeriver().probeDimensions!(photo, 'image/png')
    expect(made[0].src, 'the element no longer holds the file').toBeNull()
    expect(revoked).toEqual([`blob:probe-${urls}`])
  })

  it('a header that names no size answers null; an image the browser cannot read rejects, its URL revoked too', async () => {
    stubImage({ naturalWidth: 0, naturalHeight: 0 })
    await expect(createBrowserDeriver().probeDimensions!(photo, 'image/svg+xml')).resolves.toBeNull()
    stubImage({ naturalWidth: 0, naturalHeight: 0, fails: true })
    await expect(createBrowserDeriver().probeDimensions!(photo, 'image/heic')).rejects.toThrow('image_probe_failed')
    expect(revoked).toHaveLength(2)
    expect(decode).not.toHaveBeenCalled()
  })
})
