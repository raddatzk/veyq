// @vitest-environment happy-dom
// uc.memory.banner - the banner on the page: the whole medium, never cropped, with blur where it leaves the frame (ADR 0102),
// nothing drawn for a banner that failed, and a video that NEVER plays on its own: no <video> and no service-worker registration
// before the click (bite 7), then the BANNER blob's chunks through the worker, or the whole copy without one.
import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('../../../sw/register', () => ({
  registerPlayback: vi.fn(async () => null),
  revokePlayback: vi.fn(),
  handleFromUrl: (url: string) => url.replace('/__media/', ''),
}))

import { mount, flushPromises } from '@vue/test-utils'
import { registerPlayback, revokePlayback } from '../../../sw/register'
import { realI18n, t } from '../../../test/i18n'
import MemoryBanner from './MemoryBanner.vue'
import type { Banner } from './bannerModel'

const NODE = 'n1'
const ref = (blobId: string, role: string) => ({ blobId, chunkCount: 3, byteSize: 300, wrappedBlobKey: 'k', ownerId: NODE, role })
const image: Banner = { medium: 'image', blob: ref('i', 'BANNER'), frame: 'fill', focal: { x: 0.2, y: 0.85 } }
const video: Banner = {
  medium: 'video', blob: ref('v', 'BANNER'), poster: ref('p', 'BANNER_POSTER'), type: 'video/quicktime', frame: 'fit', focal: { x: 0.5, y: 0.5 },
}
const provider = { chunkCount: 3, byteSize: 300, getPlaintextChunk: vi.fn() }

function mountBanner(banner: Banner | null, over: Record<string, unknown> = {}) {
  const videoChunks = vi.fn(() => provider)
  const videoBytes = vi.fn(async () => new Uint8Array([1, 2]))
  const wrapper = mount(MemoryBanner, {
    props: { banner, pictureUrl: 'blob:picture', status: 'ready', videoChunks, videoBytes, ...over },
    global: { plugins: [realI18n()] },
  })
  return { wrapper, videoChunks, videoBytes }
}

beforeEach(() => {
  vi.clearAllMocks()
  Object.assign(URL, { createObjectURL: vi.fn(() => 'blob:whole'), revokeObjectURL: vi.fn() })
})

describe('MemoryBanner', () => {
  it('shows the whole medium as a decorative picture, the same picture blurred behind it - a stored fill and point ignored', () => {
    // `image` was framed by an earlier editor: `fill` around (0.2, 0.85). It is drawn whole all the same.
    const { wrapper } = mountBanner(image)
    const img = wrapper.get('img.banner-frame-media')
    expect(img.attributes('style') ?? '').not.toContain('object-position')
    expect(img.attributes('alt')).toBe('')
    expect(wrapper.get('.banner-frame-backdrop').attributes('aria-hidden')).toBe('true')
  })

  it('draws nothing for no banner, or for one whose picture failed (the header row moves up)', () => {
    expect(mountBanner(null).wrapper.find('[data-testid="memory-banner"]').exists()).toBe(false)
    expect(mountBanner(image, { status: 'failed' }).wrapper.find('[data-testid="memory-banner"]').exists()).toBe(false)
    // its space is held while the bytes are on their way
    expect(mountBanner(image, { status: 'loading', pictureUrl: null }).wrapper.get('[data-testid="memory-banner"]').classes()).toContain('is-loading')
  })

  it('a video shows its poster and a play button, and nothing plays before the click', async () => {
    const { wrapper, videoChunks } = mountBanner(video)
    await flushPromises()
    expect(wrapper.find('video').exists(), 'no video element before the click').toBe(false)
    expect(registerPlayback).not.toHaveBeenCalled()
    expect(videoChunks).not.toHaveBeenCalled()
    expect(wrapper.get('img.banner-frame-media').attributes('src')).toBe('blob:picture')
    expect(wrapper.get('.memory-banner-play').attributes('aria-label')).toBe(t('memory.banner.play'))
  })

  it('the click plays the BANNER blob through the service worker, with its controls', async () => {
    vi.mocked(registerPlayback).mockResolvedValueOnce('/__media/h1')
    const { wrapper, videoChunks, videoBytes } = mountBanner(video)
    await wrapper.get('.memory-banner-play').trigger('click')
    await flushPromises()
    expect(videoChunks).toHaveBeenCalledOnce()
    expect(registerPlayback).toHaveBeenCalledWith({ chunkCount: 3, byteSize: 300, mediaType: 'video/quicktime' }, provider)
    const v = wrapper.get('video')
    expect(v.attributes('src')).toBe('/__media/h1')
    expect(v.attributes('controls')).toBeDefined()
    expect(videoBytes, 'no whole read with a worker').not.toHaveBeenCalled()
    wrapper.unmount()
    expect(revokePlayback).toHaveBeenCalledWith('h1')
  })

  // Owner 2026-11-01: while a video plays, its blur follows it - each presented frame drawn onto a 32 px canvas, no second
  // decode; paused, nothing is drawn; under reduced motion the blur stays the poster.
  describe('the backdrop of a playing video', () => {
    let frameCallbacks: Array<() => void>
    let drawImage: ReturnType<typeof vi.fn>
    let reduce: boolean
    beforeEach(() => {
      frameCallbacks = []
      drawImage = vi.fn()
      reduce = false
      Object.assign(HTMLVideoElement.prototype, {
        requestVideoFrameCallback: vi.fn((cb: () => void) => frameCallbacks.push(cb)),
        cancelVideoFrameCallback: vi.fn(),
      })
      vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue({ drawImage } as unknown as CanvasRenderingContext2D)
      vi.stubGlobal('matchMedia', vi.fn(() => ({ matches: reduce })))
    })

    async function playing() {
      vi.mocked(registerPlayback).mockResolvedValueOnce('/__media/h1')
      const { wrapper } = mountBanner(video)
      await wrapper.get('.memory-banner-play').trigger('click')
      await flushPromises()
      const v = wrapper.get('video')
      for (const [k, value] of Object.entries({ videoWidth: 1920, videoHeight: 1080, paused: false, ended: false })) {
        Object.defineProperty(v.element, k, { value, configurable: true })
      }
      await v.trigger('play')
      return { wrapper, v }
    }

    it('draws each presented frame onto a 32 px canvas, which replaces the poster behind the video; a pause stops it', async () => {
      const { wrapper, v } = await playing()
      expect(frameCallbacks).toHaveLength(1)
      frameCallbacks.shift()!()
      await flushPromises()
      const canvas = wrapper.get('canvas.memory-banner-backdrop')
      expect(drawImage).toHaveBeenCalledWith(v.element, 0, 0, 32, 18)
      expect([(canvas.element as HTMLCanvasElement).width, (canvas.element as HTMLCanvasElement).height]).toEqual([32, 18])
      expect(canvas.attributes('aria-hidden')).toBe('true')
      expect(wrapper.find('img.memory-banner-backdrop').exists(), 'the poster gives way').toBe(false)
      expect(frameCallbacks, 'the next frame is asked for').toHaveLength(1)

      Object.defineProperty(v.element, 'paused', { value: true, configurable: true })
      await v.trigger('pause')
      frameCallbacks.shift()!() // a frame already asked for draws, and asks for no more
      expect(frameCallbacks).toHaveLength(0)
    })

    it('under reduced motion draws nothing: the blur stays the poster', async () => {
      reduce = true
      const { wrapper } = await playing()
      expect(frameCallbacks).toHaveLength(0)
      expect(drawImage).not.toHaveBeenCalled()
      expect(wrapper.get('img.memory-banner-backdrop').attributes('src')).toBe('blob:picture')
    })
  })

  it('without a service worker the click decrypts the copy whole and plays it typed', async () => {
    const { wrapper, videoBytes } = mountBanner(video)
    await wrapper.get('.memory-banner-play').trigger('click')
    await flushPromises()
    expect(videoBytes).toHaveBeenCalledOnce()
    expect(wrapper.get('video').attributes('src')).toBe('blob:whole')
    const blob = vi.mocked(URL.createObjectURL).mock.calls[0][0] as Blob
    expect(blob.type).toBe('video/quicktime')
  })
})
