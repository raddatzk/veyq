// @vitest-environment happy-dom
// The SVG's decode and paint (inbox #1059, ADR 0155 § The file facts' slice D11, D14), with the browser's image decoder and
// 2D context stubbed (happy-dom has neither): the cap every painted canvas keeps, that the SVG's own URL is revoked before
// the picture is handed out (and on a failed decode), that a canvas the browser will not read back (a `foreignObject`
// taints it) refuses the SVG before anything is shown and empties a later paint, and that a paint renders the vectors at
// the asked scale (the whole picture at its laid-out size, shifted to the region) rather than stretching a bitmap. The
// real browser's answers are the browser pass's.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { decodeSvg, planRaster, SVG_MAX_EDGE, SVG_MAX_PIXELS } from './svgRaster'

describe('planRaster', () => {
  it('paints at the asked ratio where it fits the cap', () => {
    expect(planRaster({ width: 800, height: 600 }, 2)).toEqual({ width: 1600, height: 1200, ratio: 2 })
    expect(planRaster({ width: 800, height: 600 }, 1)).toEqual({ width: 800, height: 600, ratio: 1 })
  })

  it('lowers the ratio, never the cap, where the region at that ratio would pass it', () => {
    for (const [w, h, r] of [
      [1920, 1080, 3],
      [3840, 2160, 2],
      [4096, 4096, 1.5],
      [100_000, 10, 4],
      [10, 100_000, 4],
      [100_000, 100_000, 16],
    ]) {
      const p = planRaster({ width: w, height: h }, r)
      expect(p.width * p.height, `${w}x${h}@${r}`).toBeLessThanOrEqual(SVG_MAX_PIXELS)
      expect(Math.max(p.width, p.height), `${w}x${h}@${r}`).toBeLessThanOrEqual(SVG_MAX_EDGE)
      expect(p.ratio).toBeLessThan(r)
    }
  })

  it('reads a nonsense ratio as 1 and an empty region as one pixel', () => {
    expect(planRaster({ width: 10, height: 10 }, Number.NaN).ratio).toBe(1)
    expect(planRaster({ width: 10, height: 10 }, 0).ratio).toBe(1)
    expect(planRaster({ width: 0, height: 0 }, 2)).toEqual({ width: 2, height: 2, ratio: 2 })
  })
})

// --- the browser, stubbed ---------------------------------------------------------------------------------------------------
let events: string[]
let tainted: boolean
let decodeFails: boolean
let draws: { canvas: HTMLCanvasElement; args: number[] }[]
beforeEach(() => {
  events = []
  tainted = false
  decodeFails = false
  draws = []
  vi.spyOn(URL, 'createObjectURL').mockImplementation(() => {
    events.push('url')
    return 'blob:svg-1'
  })
  vi.spyOn(URL, 'revokeObjectURL').mockImplementation((u) => void events.push(`revoke ${u}`))
  class FakeImage {
    src = ''
    naturalWidth = 200
    naturalHeight = 100
    async decode() {
      events.push(`decode ${this.src}`)
      if (decodeFails) throw new Error('EncodingError')
    }
  }
  vi.stubGlobal('Image', FakeImage)
  const fakeContext = function (this: HTMLCanvasElement) {
    return {
      drawImage: (_img: unknown, ...args: number[]) => void draws.push({ canvas: this, args }),
      getImageData: () => {
        events.push('read back')
        if (tainted) throw new DOMException('tainted', 'SecurityError')
        return {}
      },
    } as unknown as CanvasRenderingContext2D
  }
  vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockImplementation(fakeContext as unknown as HTMLCanvasElement['getContext'])
})
afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

const svg = () => new Blob(['<svg xmlns="http://www.w3.org/2000/svg" width="200" height="100"/>'], { type: 'image/svg+xml' })

describe('decodeSvg', () => {
  it('revokes the SVG\'s URL as soon as it is decoded - before the picture is handed out or painted', async () => {
    const picture = await decodeSvg(svg())
    expect(events.slice(0, 3)).toEqual(['url', 'decode blob:svg-1', 'revoke blob:svg-1'])
    expect(picture.width).toBe(200)
    expect(picture.height).toBe(100)
  })

  it('revokes it on a failed decode too, and fails', async () => {
    decodeFails = true
    await expect(decodeSvg(svg())).rejects.toThrow()
    expect(events).toContain('revoke blob:svg-1')
  })

  it('refuses an SVG that taints the canvas (a foreignObject) before anything is shown', async () => {
    tainted = true
    await expect(decodeSvg(svg())).rejects.toThrow('the SVG did not draw')
    expect(events).toContain('read back')
  })

  it('paints the vectors at the asked scale: the whole picture at its laid-out size, shifted to the region', async () => {
    const picture = await decodeSvg(svg())
    const canvas = document.createElement('canvas')
    // the picture laid out at 8 CSS px per unit (1600 x 800), its region 100..500 x 50..350 in view, a screen ratio of 2
    const raster = picture.paint(canvas, { x: 100, y: 50, width: 400, height: 300 }, 8, 2)
    expect(raster).toEqual({ width: 800, height: 600, ratio: 2 })
    expect([canvas.width, canvas.height]).toEqual([800, 600])
    expect(draws.at(-1)!.args).toEqual([-200, -100, 3200, 1600])
  })

  it('empties the canvas and throws where a later paint cannot be read back', async () => {
    const picture = await decodeSvg(svg())
    tainted = true
    const canvas = document.createElement('canvas')
    expect(() => picture.paint(canvas, { x: 0, y: 0, width: 200, height: 100 }, 1, 1)).toThrow('the SVG did not draw')
    expect([canvas.width, canvas.height]).toEqual([0, 0])
  })

  it('paints nothing once closed', async () => {
    const picture = await decodeSvg(svg())
    picture.close()
    expect(() => picture.paint(document.createElement('canvas'), { x: 0, y: 0, width: 1, height: 1 }, 1, 1)).toThrow()
  })
})
