// uc.memory.gallery (cr-0059) — the justified-layout geometry, ported from the design source. Pure math:
// aspect ratios are bounded for display, portrait/landscape keep visibly different tile shapes, and a justified
// row exactly fills the container width.
import { describe, expect, it } from 'vitest'
import { calculateJustifiedMediaLayout, galleryDisplayAspectRatio } from './mediaGridLayout'

describe('galleryDisplayAspectRatio', () => {
  it('bounds extreme aspect ratios for gallery thumbnails', () => {
    expect(galleryDisplayAspectRatio(16, 9)).toBe(4 / 3)
    expect(galleryDisplayAspectRatio(9, 16)).toBe(3 / 4)
  })

  it('keeps normal aspect ratios and falls back when dimensions are missing', () => {
    expect(galleryDisplayAspectRatio(4, 3)).toBe(4 / 3)
    expect(galleryDisplayAspectRatio(3, 4)).toBe(3 / 4)
    expect(galleryDisplayAspectRatio(1, 1)).toBe(1)
    expect(galleryDisplayAspectRatio(null, 9)).toBe(4 / 3)
    expect(galleryDisplayAspectRatio(undefined, undefined)).toBe(4 / 3)
  })
})

describe('calculateJustifiedMediaLayout', () => {
  it('keeps portrait and landscape media in visibly different tile shapes', () => {
    const layout = calculateJustifiedMediaLayout({
      items: [
        { id: 'portrait', aspectRatio: 3 / 4 },
        { id: 'landscape', aspectRatio: 16 / 9 },
      ],
      containerWidth: 600,
      targetHeight: 200,
      gap: 8,
      showWidows: true,
    })

    const portrait = layout.tiles.find((tile) => tile.id === 'portrait')
    const landscape = layout.tiles.find((tile) => tile.id === 'landscape')

    expect(portrait).toBeDefined()
    expect(landscape).toBeDefined()
    expect(portrait!.renderedWidth).toBeLessThan(portrait!.renderedHeight)
    expect(landscape!.renderedWidth).toBeGreaterThan(landscape!.renderedHeight)
  })

  it('justifies a full row to exactly the container width', () => {
    const containerWidth = 900
    // five square tiles overflow the target row aspect (900/200 = 4.5), so the row is force-justified to fill
    const layout = calculateJustifiedMediaLayout({
      items: ['a', 'b', 'c', 'd', 'e'].map((id) => ({ id, aspectRatio: 1 })),
      containerWidth,
      targetHeight: 200,
      gap: 8,
      showWidows: true,
    })

    const lastTile = layout.tiles[layout.tiles.length - 1]
    expect(Math.round(lastTile.left + lastTile.renderedWidth)).toBe(containerWidth)
    expect(layout.tiles.every((t) => t.top === 0)).toBe(true)
  })

  it('returns an empty layout when the container has no width yet', () => {
    const layout = calculateJustifiedMediaLayout({
      items: [{ id: 'a', aspectRatio: 1 }],
      containerWidth: 0,
      targetHeight: 200,
      gap: 8,
    })
    expect(layout).toEqual({ tiles: [], height: 0 })
  })
})
