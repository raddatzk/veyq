// cr-0105 follow-up — the on-device derive stage must never HANG the upload. Safari fires neither the media
// event nor `error` for an offscreen, never-played <video>, so `onceEvent` is timeout-guarded and `deriveTiers`
// degrades a failed/timed-out video decode to original-only instead of blocking before the first PUT. The real
// browser <video> decode isn't reproducible in this env — these cover the guard + the graceful fallback; the
// actual Safari frame-grab is covered by live verification.
import { afterEach, describe, expect, it, vi } from 'vitest'
import { onceEvent, firstPresentedFrame, VIDEO_EVENT_TIMEOUT_MS, deriveTiers, type AddMediaSource, type MediaDeriver } from './mediaDeriver'

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
  const videoSource: AddMediaSource = { bytes: new Uint8Array([0, 1, 2]), mediaType: 'video/mp4' }

  it('returns [] (original only) when the poster derive rejects — via the catch, never re-thrown', async () => {
    const deriver: MediaDeriver = {
      deriveImage: vi.fn(async () => new Uint8Array()),
      deriveVideoPoster: vi.fn(async () => { throw new Error('video_seeked_timeout') }),
      probeDimensions: vi.fn(async () => null),
    }
    await expect(deriveTiers(videoSource, deriver)).resolves.toEqual([])
    expect(deriver.deriveVideoPoster).toHaveBeenCalledTimes(1)
  })
})
