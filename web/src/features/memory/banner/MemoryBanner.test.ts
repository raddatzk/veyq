// @vitest-environment happy-dom
// uc.memory.banner - the banner on the page: framed as stored (fill around the focal point, fit with blurred sides), nothing
// drawn for a banner that failed, and a video that NEVER plays on its own: no <video> and no service-worker registration
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
const image: Banner = { medium: 'image', blob: ref('i', 'BANNER'), frame: 'fill', focal: { x: 0.2, y: 0.85 }, alt: 'The bay' }
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
  it('fills the frame around the focal point, the description as its text', () => {
    const { wrapper } = mountBanner(image)
    const img = wrapper.get('img.banner-frame-media')
    expect(img.attributes('style')).toContain('object-position: 20% 85%')
    expect(img.attributes('alt')).toBe('The bay')
    expect(wrapper.find('.banner-frame-backdrop').exists()).toBe(false)
  })

  it('fits a portrait medium whole, its sides the same picture blurred and hidden from assistive technology', () => {
    const { wrapper } = mountBanner({ ...image, frame: 'fit' })
    expect(wrapper.get('.banner-frame-backdrop').attributes('aria-hidden')).toBe('true')
    expect(wrapper.get('img.banner-frame-media').classes()).toContain('is-fit')
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
