// @vitest-environment happy-dom
// The engine's TEXT LAYER wiring (inbox #412), with a fake pdf.js runtime: happy-dom has no canvas and the real TextLayer
// measures text on one, so the library itself is proven by `pdfEngine.real.test.ts` (its text content) and by a real browser.
// What is pinned here is OUR side: that the layer is built in the host the caller passes, at the page's CSS scale (not the
// canvas' device scale), only when a host is passed, emptied first, closed with its end marker, cancelled with the drawing,
// and that a page whose text cannot be read still draws.
import { describe, it, expect, vi, beforeEach } from 'vitest'

type LayerArgs = { textContentSource: unknown; container: HTMLElement; viewport: { scale: number } }
const layers: { args: LayerArgs; cancel: ReturnType<typeof vi.fn>; render: ReturnType<typeof vi.fn> }[] = []
let cleanup = vi.fn()
let layerBehaviour: 'ok' | 'fails' = 'ok'

function fakeTextLayer(this: { args: LayerArgs; cancel: ReturnType<typeof vi.fn>; render: ReturnType<typeof vi.fn> }, args: LayerArgs): void {
  this.args = args
  this.cancel = vi.fn()
  this.render = vi.fn(async () => {
    if (layerBehaviour === 'fails') throw new Error('unreadable text')
    const span = document.createElement('span')
    span.textContent = 'Hello'
    args.container.append(span)
  })
  layers.push(this)
}

const renderTask = { promise: Promise.resolve(), cancel: vi.fn() }
let rotated = false
const page = {
  // A page 200 x 100 points; rotated, it is SHOWN 100 x 200 but its raw (unrotated) dimensions stay 200 x 100.
  getViewport: ({ scale }: { scale: number }) => ({
    scale,
    width: (rotated ? 100 : 200) * scale,
    height: (rotated ? 200 : 100) * scale,
    rawDims: { pageWidth: 200, pageHeight: 100 },
  }),
  render: vi.fn((_: { viewport: { scale: number } }) => renderTask),
  streamTextContent: vi.fn(() => 'stream'),
  cleanup: vi.fn(),
}
vi.mock('./pdfjsRuntime', () => ({ TextLayer: vi.fn(), getDocument: vi.fn() }))

import { openPdf, PDF_TEXT_END_CLASS } from './pdfEngine'
import { getDocument, TextLayer } from './pdfjsRuntime'

beforeEach(() => {
  layers.length = 0
  layerBehaviour = 'ok'
  rotated = false
  vi.clearAllMocks()
  cleanup = vi.fn()
  ;(TextLayer as unknown as { cleanup: unknown }).cleanup = cleanup // pdf.js' static, which the engine calls on destroy
  vi.mocked(TextLayer as unknown as (this: unknown, a: LayerArgs) => void).mockImplementation(fakeTextLayer as unknown as (a: LayerArgs) => void)
  vi.mocked(getDocument).mockReturnValue({
    promise: Promise.resolve({ numPages: 1, getPage: async () => page }),
    destroy: async () => {},
  } as unknown as ReturnType<typeof getDocument>)
})

const canvas = () => document.createElement('canvas')

describe('the engine builds the text layer with the page', () => {
  it('builds it in the host, at the CSS scale (not the device scale), closed with the end marker', async () => {
    const doc = await openPdf(new Uint8Array(1))
    const host = document.createElement('div')
    host.append(document.createElement('span')) // what an earlier layout left behind
    const job = doc.render(1, canvas(), 400, 2, host)
    expect(await job.done).toBe(true)
    expect(layers).toHaveLength(1)
    expect(layers[0].args.container).toBe(host)
    expect(layers[0].args.viewport.scale).toBe(2) // 400 CSS px over a 200-point page; the canvas is drawn at 4
    expect(vi.mocked(page.render).mock.calls[0]![0].viewport.scale).toBe(4)
    expect(host.style.getPropertyValue('--total-scale-factor')).toBe('2')
    expect(host.querySelectorAll('span')).toHaveLength(1) // the old one is gone, the new text is in
    expect(host.textContent).toBe('Hello')
    expect(host.lastElementChild?.className).toBe(PDF_TEXT_END_CLASS)
    expect(page.streamTextContent).toHaveBeenCalledWith({ includeMarkedContent: true })
    await doc.destroy()
  })

  it('sizes the host as the raw page at the CSS scale, not the (rotated) page box: the stylesheet turns it into place', async () => {
    const doc = await openPdf(new Uint8Array(1))
    const host = document.createElement('div')
    await doc.render(1, canvas(), 400, 1, host).done
    expect([host.style.width, host.style.height]).toEqual(['400px', '200px'])
    rotated = true
    const turned = document.createElement('div')
    await doc.render(1, canvas(), 200, 1, turned).done // shown 200 px wide: scale 2 over a 100-point-wide rotated page
    expect([turned.style.width, turned.style.height]).toEqual(['400px', '200px']) // still the raw 200 x 100 points, at scale 2
    await doc.destroy()
  })

  it('without a host there is no text layer at all', async () => {
    const doc = await openPdf(new Uint8Array(1))
    expect(await doc.render(1, canvas(), 400, 1).done).toBe(true)
    expect(await doc.render(1, canvas(), 400, 1, null).done).toBe(true)
    expect(layers).toHaveLength(0)
    expect(page.streamTextContent).not.toHaveBeenCalled()
    await doc.destroy()
  })

  it('one layer per render: drawing the same page again builds one more, in the same host', async () => {
    const doc = await openPdf(new Uint8Array(1))
    const host = document.createElement('div')
    await doc.render(1, canvas(), 400, 1, host).done
    await doc.render(1, canvas(), 800, 1, host).done
    expect(layers).toHaveLength(2)
    expect(layers[1].args.viewport.scale).toBe(4)
    expect(host.querySelectorAll('span')).toHaveLength(1)
    expect(host.querySelectorAll(`.${PDF_TEXT_END_CLASS}`)).toHaveLength(1)
    await doc.destroy()
  })

  it('a page whose text cannot be read still draws, with an empty layer and no error', async () => {
    layerBehaviour = 'fails'
    const doc = await openPdf(new Uint8Array(1))
    const host = document.createElement('div')
    expect(await doc.render(1, canvas(), 400, 1, host).done).toBe(true)
    expect(host.querySelector('span')).toBeNull()
    await doc.destroy()
  })

  it('cancelling the job cancels the text layer too, and the job is not a success', async () => {
    const doc = await openPdf(new Uint8Array(1))
    const host = document.createElement('div')
    const job = doc.render(1, canvas(), 400, 1, host)
    await Promise.resolve()
    await Promise.resolve()
    job.cancel()
    expect(await job.done).toBe(false)
    expect(layers[0].cancel).toHaveBeenCalled()
    expect(renderTask.cancel).toHaveBeenCalled()
    await doc.destroy()
  })

  it('destroying the document drops pdf.js\' text measuring canvases (TextLayer.cleanup)', async () => {
    const doc = await openPdf(new Uint8Array(1))
    await doc.destroy()
    expect(cleanup).toHaveBeenCalledTimes(1)
    await doc.destroy()
    expect(cleanup).toHaveBeenCalledTimes(1)
  })
})
