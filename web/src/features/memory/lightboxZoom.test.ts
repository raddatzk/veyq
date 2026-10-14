// The zoom's gates for fetching the original (cr-lightbox-zoom-slideshow); the lightbox's use of them is
// `components/MediaLightbox.test.ts` § zoom.
import { describe, it, expect } from 'vitest'
import { exceedsPreview, originalZoomable, ZOOM_ORIGINAL_MAX_BYTES } from './lightboxZoom'

describe('lightboxZoom', () => {
  it('counts DEVICE pixels: on a 2x screen the preview runs out at half the zoom', () => {
    expect(exceedsPreview(1000, 2, 1, 2048)).toBe(false) // 2000 of 2048
    expect(exceedsPreview(1000, 1.1, 2, 2048)).toBe(true) // 2200 of 2048
    expect(exceedsPreview(1000, 3, 0, 2048), 'an unknown ratio counts as 1x').toBe(true)
  })

  it('an image not laid out or not decoded yet never asks', () => {
    expect(exceedsPreview(0, 8, 2, 2048)).toBe(false)
    expect(exceedsPreview(1000, 8, 2, 0)).toBe(false)
  })

  it('a raster type every browser decodes, under the cap', () => {
    expect(originalZoomable('image/jpeg', 1)).toBe(true)
    expect(originalZoomable('IMAGE/PNG', 1), 'a type is case-insensitive').toBe(true)
    expect(originalZoomable('image/heic', 1)).toBe(false)
    expect(originalZoomable('image/svg+xml', 1)).toBe(false)
    expect(originalZoomable(undefined, 1)).toBe(false)
    expect(originalZoomable('image/jpeg', ZOOM_ORIGINAL_MAX_BYTES - 1)).toBe(true)
    expect(originalZoomable('image/jpeg', ZOOM_ORIGINAL_MAX_BYTES)).toBe(false)
    expect(originalZoomable('image/jpeg', 0), 'an empty original').toBe(false)
  })
})
