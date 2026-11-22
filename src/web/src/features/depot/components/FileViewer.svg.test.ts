// @vitest-environment happy-dom
// The SVG half of the file viewer (inbox #1059), mounted as the file page does: real DOM, real catalog, real UI layer, the
// DECODE mocked (`svgFakes.ts`: happy-dom draws no canvas; the real decode and paint are `svgRaster.test.ts`'s and the
// browser pass's, ADR 0155 § The file facts' slice D14). Pinned here: a zoom paints the picture again at the zoom's actual
// scale (zoom x the screen's ratio), debounced, within the canvas cap; the fitted size needs no second canvas; a refused
// paint is "cannot be shown" with the download; everything painted is given back when the viewer goes.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'

vi.mock('../svgRaster', async (orig) => ({ ...(await orig<typeof import('../svgRaster')>()), decodeSvg: vi.fn() }))

import FileViewer from './FileViewer.vue'
import { decodeSvg, SVG_MAX_EDGE, SVG_MAX_PIXELS } from '../svgRaster'
import { fakePicture, type FakePicture } from './svgFakes'
import { providerOf, utf8 } from '../../../test/fileChunks'
import { realI18n, t } from '../../../test/i18n'

const DELAY = 150 // SvgView's REPAINT_DELAY
let picture: FakePicture
const mounted: VueWrapper[] = []
beforeEach(() => {
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
  vi.stubGlobal('devicePixelRatio', 2)
  picture = fakePicture(200, 100)
  vi.mocked(decodeSvg).mockReset().mockImplementation(async () => picture)
})
afterEach(() => {
  mounted.splice(0).forEach((w) => w.unmount())
  vi.useRealTimers()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

function mountSvg(): VueWrapper {
  const w = mount(FileViewer, {
    props: {
      target: { id: 'blob-1', source: providerOf(utf8('<svg xmlns="http://www.w3.org/2000/svg" width="200" height="100"/>')), hints: { type: 'image/svg+xml', name: 'a.svg' } },
      name: 'a.svg',
      canDownload: true,
    },
    attachTo: document.body,
    global: { plugins: [realI18n()] },
  })
  mounted.push(w)
  return w
}
type W = VueWrapper
const zoomInButton = (w: W) => w.find(`button[aria-label="${t('depot.viewer.image.zoomIn')}"]`)
const zoomOutButton = (w: W) => w.find(`button[aria-label="${t('depot.viewer.image.zoomOut')}"]`)
const fitButton = (w: W) => w.find(`button[aria-label="${t('depot.viewer.image.fit')}"]`)
const baseCanvas = (w: W) => w.find('canvas.svg-base').element as HTMLCanvasElement
const tileCanvas = (w: W) => w.find('canvas.svg-tile').element as HTMLCanvasElement
async function zoomTo(w: W, level: number) {
  while (Number(w.find('.svg-zoom-level').text().replace(/\D/g, '')) < level * 100) await zoomInButton(w).trigger('click')
}
async function settle() {
  vi.advanceTimersByTime(DELAY)
  await flushPromises()
}

describe('an SVG at its fitted size', () => {
  it('is painted once onto the base canvas at the screen ratio, with no tile', async () => {
    const w = mountSvg()
    await flushPromises()
    await settle()
    expect(picture.paints).toHaveLength(1)
    const [base] = picture.paints
    expect(base.canvas).toBe(baseCanvas(w))
    expect(base.deviceRatio).toBe(2)
    expect(base.raster.ratio).toBe(2)
    expect(baseCanvas(w).width).toBe(Math.floor(base.region.width * 2))
    expect(tileCanvas(w).width, 'no second bitmap at the fitted size').toBe(0)
    expect(w.find('.svg-zoom-level').text()).toBe('100 %')
    expect(zoomOutButton(w).attributes('disabled')).toBeDefined()
    expect(fitButton(w).attributes('disabled')).toBeDefined()
  })
})

describe('zoomed', () => {
  it('paints the view again at the zoom\'s actual scale (zoom x screen ratio) - sharp, not an enlarged bitmap', async () => {
    const w = mountSvg()
    await flushPromises()
    await settle()
    const fit = picture.paints[0].scale
    await zoomTo(w, 4)
    expect(w.find('.svg-zoom-level').text()).toBe('400 %')
    await settle()
    const tile = picture.paints.at(-1)!
    expect(tile.canvas).toBe(tileCanvas(w))
    expect(tile.scale, 'the picture laid out at 4x the fitted scale').toBeCloseTo(fit * 4)
    expect(tile.raster.ratio, 'painted at the full screen ratio').toBe(2)
    // device px per CSS px of the tile: the canvas' pixels over its laid-out size
    expect(tileCanvas(w).width / tile.region.width).toBeCloseTo(2, 1)
    expect(tileCanvas(w).style.width).toBe(`${tile.region.width}px`)
  })

  it('is debounced: a run of zoom clicks paints once, after the view stays still', async () => {
    const w = mountSvg()
    await flushPromises()
    await settle()
    const before = picture.paints.length
    await zoomTo(w, 8)
    vi.advanceTimersByTime(DELAY - 1)
    expect(picture.paints, 'nothing painted while zooming').toHaveLength(before)
    await settle()
    expect(picture.paints).toHaveLength(before + 1)
  })

  it('at 16x and a high screen ratio stays within the canvas cap', async () => {
    vi.stubGlobal('devicePixelRatio', 8)
    const w = mountSvg()
    await flushPromises()
    await settle()
    await zoomTo(w, 16)
    expect(w.find('.svg-zoom-level').text()).toBe('1600 %')
    expect(zoomInButton(w).attributes('disabled')).toBeDefined()
    await settle()
    const tile = picture.paints.at(-1)!
    expect(tile.scale).toBeCloseTo(picture.paints[0].scale * 16)
    const canvas = tileCanvas(w)
    expect(canvas.width * canvas.height).toBeLessThanOrEqual(SVG_MAX_PIXELS)
    expect(Math.max(canvas.width, canvas.height)).toBeLessThanOrEqual(SVG_MAX_EDGE)
    expect(tile.raster.ratio, 'the cap, not the screen, set the ratio here').toBeLessThan(8)
    for (const p of picture.paints) expect(p.raster.width * p.raster.height).toBeLessThanOrEqual(SVG_MAX_PIXELS)
  })

  it('fit returns to the base alone: the tile gives its bitmap back', async () => {
    const w = mountSvg()
    await flushPromises()
    await settle()
    await zoomTo(w, 3)
    await settle()
    expect(tileCanvas(w).width).toBeGreaterThan(0)
    await fitButton(w).trigger('click')
    await settle()
    expect(tileCanvas(w).width).toBe(0)
    expect(w.find('.svg-zoom-level').text()).toBe('100 %')
  })
})

describe('what the viewer gives back', () => {
  it('a refused paint (a canvas the browser will not read back) is "cannot be shown" with the download', async () => {
    const w = mountSvg()
    await flushPromises()
    await settle()
    picture.paint.mockImplementationOnce(() => {
      throw new Error('the SVG did not draw')
    })
    await zoomTo(w, 2)
    await settle()
    expect(w.find('canvas').exists()).toBe(false)
    expect(w.text()).toContain(t('depot.viewer.unsupported.format'))
    expect(w.find('button.file-viewer-download').exists()).toBe(true)
    expect(picture.close).toHaveBeenCalled()
  })

  it('on unmount the canvases are emptied and the picture let go', async () => {
    const w = mountSvg()
    await flushPromises()
    await settle()
    await zoomTo(w, 2)
    await settle()
    const base = baseCanvas(w)
    const tile = tileCanvas(w)
    mounted.splice(0)
    w.unmount()
    expect([base.width, base.height, tile.width, tile.height]).toEqual([0, 0, 0, 0])
    expect(picture.close).toHaveBeenCalled()
  })
})
