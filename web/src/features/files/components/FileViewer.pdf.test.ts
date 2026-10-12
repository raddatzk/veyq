// @vitest-environment happy-dom
// The PDF half of the file viewer (inbox #412), mounted as the file page does: real DOM, real catalog, real PrimeVue, the
// PDF ENGINE mocked (happy-dom draws no canvas, and the real library is `pdfEngine.real.test.ts`'s). What is pinned here is
// the wiring: the state machine (loading -> ready / protected / unreadable / too long / error), that pages are drawn only
// while near the viewport (an IntersectionObserver stub), the zoom and page controls, that everything the viewer made is
// undone (the document destroyed, the drawing cancelled) on a new file, on navigation and on unmount, and that the viewer
// puts nothing in the page that a PDF could use to act: no frame/object/embed, no link, no raw HTML.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'

vi.mock('../../../sw/register', () => ({
  registerPlayback: vi.fn(async () => null),
  revokePlayback: vi.fn(),
  handleFromUrl: (url: string) => url.replace('/__media/', ''),
}))
vi.mock('../pdf/pdfEngine', async (orig) => ({ ...(await orig<typeof import('../pdf/pdfEngine')>()), openPdf: vi.fn() }))

import FileViewer from './FileViewer.vue'
import { openPdf, PdfOpenError, type PdfDocument } from '../pdf/pdfEngine'
import { PLAINTEXT_CHUNK_SIZE } from '../../../sw/range'
import { PDF_CAP } from '../view'
import type { ViewerTarget } from '../useFileViewer'
import { PDF_BYTES, providerOf, type FakeProvider } from '../../../test/fileChunks'
import { realI18n, t } from '../../../test/i18n'
import { formatBytes } from '../../../shared/formatBytes'

// --- the stubs ---------------------------------------------------------------------------------------------------------
type Observed = { callback: IntersectionObserverCallback; options?: IntersectionObserverInit; targets: Element[] }
let observers: Observed[]
beforeEach(() => {
  observers = []
  class FakeIntersectionObserver {
    readonly o: Observed
    constructor(callback: IntersectionObserverCallback, options?: IntersectionObserverInit) {
      this.o = { callback, options, targets: [] }
      observers.push(this.o)
    }
    observe(el: Element) { this.o.targets.push(el) }
    unobserve() {}
    disconnect() { this.o.targets = [] }
    takeRecords() { return [] }
  }
  vi.stubGlobal('IntersectionObserver', FakeIntersectionObserver)
  vi.mocked(openPdf).mockReset()
})
afterEach(() => {
  mounted.splice(0).forEach((w) => w.unmount()) // a failed test must not leave its viewer in the document for the next one
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

/** The observer that decides what is drawn (it has a root margin) and the one that decides the current page (thresholds). */
const nearObserver = () => observers.find((o) => o.options?.rootMargin)!
const currentObserver = () => observers.find((o) => o.options?.threshold)!
function intersect(o: Observed, pages: Record<number, boolean | number>): void {
  const entries = Object.entries(pages).map(([n, v]) => {
    const target = o.targets.find((el) => (el as HTMLElement).dataset.page === n)!
    const isIntersecting = v !== false && v !== 0
    return { target, isIntersecting, intersectionRatio: typeof v === 'number' ? v : isIntersecting ? 1 : 0 } as unknown as IntersectionObserverEntry
  })
  o.callback(entries, {} as IntersectionObserver)
}

type FakeJob = { page: number; cssWidth: number; pixelRatio: number; canvas: HTMLCanvasElement; textHost: HTMLElement | null; cancel: ReturnType<typeof vi.fn>; finish: () => void }
function fakeDocument(numPages: number, size = { width: 200, height: 100 }) {
  const jobs: FakeJob[] = []
  const doc = {
    numPages,
    pageSize: vi.fn(async () => size),
    render: vi.fn((page: number, canvas: HTMLCanvasElement, cssWidth: number, pixelRatio: number, textHost?: HTMLElement | null) => {
      // As the engine does: the host is emptied and the page's text goes in it as <span>s (a layer per render).
      if (textHost) {
        textHost.replaceChildren()
        const span = document.createElement('span')
        span.textContent = `text of page ${page}`
        textHost.append(span)
      }
      let finish: (ok: boolean) => void = () => {}
      const done = new Promise<boolean>((r) => (finish = r))
      const cancel = vi.fn(() => finish(false))
      jobs.push({ page, cssWidth, pixelRatio, canvas, textHost: textHost ?? null, cancel, finish: () => finish(true) })
      canvas.width = 50 // as the engine sets it
      canvas.height = 50
      return { done, cancel }
    }),
    destroy: vi.fn(async () => {}),
  }
  return { doc: doc as typeof doc & PdfDocument, jobs }
}

const target = (source: FakeProvider, id = 'blob-1'): ViewerTarget => ({ id, source, hints: { type: 'application/pdf', name: 'Report.pdf' } })
const mounted: { unmount: () => void }[] = []
function mountViewer(tg: ViewerTarget | null, o: { canDownload?: boolean; name?: string } = {}) {
  const w = mount(FileViewer, {
    props: { target: tg, name: o.name ?? 'Report.pdf', canDownload: o.canDownload ?? true },
    attachTo: document.body,
    global: { plugins: [realI18n()] },
  })
  mounted.push(w)
  return w
}
type W = ReturnType<typeof mountViewer>
async function openReady(pages = 5): Promise<{ w: W; fake: ReturnType<typeof fakeDocument> }> {
  const fake = fakeDocument(pages)
  vi.mocked(openPdf).mockResolvedValue(fake.doc)
  const w = mountViewer(target(providerOf(PDF_BYTES)))
  await flushPromises()
  return { w, fake }
}
const downloadButton = (w: W) => w.find('button.file-viewer-download')
const indicator = (w: W) => w.find('.pdf-page-indicator').text()

// --- the state machine ----------------------------------------------------------------------------------------------------
describe('opening a PDF', () => {
  it('shows the busy state while the engine opens it, then every page as a placeholder with the page count', async () => {
    let open: (d: PdfDocument) => void = () => {}
    vi.mocked(openPdf).mockReturnValue(new Promise((r) => (open = r)))
    const w = mountViewer(target(providerOf(PDF_BYTES)))
    await flushPromises()
    expect(w.find('[role="status"]').text()).toContain(t('files.viewer.loading'))
    expect(w.find('.pdf-viewer').exists()).toBe(false)
    open(fakeDocument(4).doc)
    await flushPromises()
    expect(w.find('[role="status"]').exists()).toBe(false)
    expect(w.findAll('.pdf-page')).toHaveLength(4)
    expect(indicator(w)).toBe(t('files.viewer.pdf.pageOf', { page: 1, total: 4 }))
    expect(w.find('canvas').attributes('aria-label')).toBe(t('files.viewer.pdf.page', { page: 1, total: 4 }))
  })

  it('hands the engine the decrypted bytes (the whole file), decided by the bytes - not by the claimed type', async () => {
    const fake = fakeDocument(1)
    vi.mocked(openPdf).mockResolvedValue(fake.doc)
    const w = mountViewer({ id: 'b', source: providerOf(PDF_BYTES), hints: { type: 'image/png', name: 'photo.png' } })
    await flushPromises()
    expect(openPdf).toHaveBeenCalledTimes(1)
    expect([...vi.mocked(openPdf).mock.calls[0][0]]).toEqual([...PDF_BYTES])
    expect(vi.mocked(openPdf).mock.calls[0][1]).toBeInstanceOf(AbortSignal)
    expect(w.find('.pdf-viewer').exists()).toBe(true)
  })

  it('a bytes-only PDF (no claim at all) is still a PDF', async () => {
    vi.mocked(openPdf).mockResolvedValue(fakeDocument(2).doc)
    const w = mountViewer({ id: 'b', source: providerOf(PDF_BYTES), hints: {} })
    await flushPromises()
    expect(w.findAll('.pdf-page')).toHaveLength(2)
  })

  it.each([
    ['password', 'pdf-password'],
    ['broken', 'pdf-broken'],
    ['pages', 'pdf-pages'],
  ] as const)('a %s PDF is a statement and the download, not an error screen', async (reason, key) => {
    vi.mocked(openPdf).mockRejectedValue(new PdfOpenError(reason))
    const w = mountViewer(target(providerOf(PDF_BYTES)))
    await flushPromises()
    expect(w.text()).toContain(t(`files.viewer.unsupported.${key}`))
    expect(w.find('.pdf-viewer').exists()).toBe(false)
    expect(w.find('.p-message-error').exists()).toBe(false)
    await downloadButton(w).trigger('click')
    expect(w.emitted('download')).toHaveLength(1)
  })

  it('names the password in the protected message, and offers no download where the session has none', async () => {
    vi.mocked(openPdf).mockRejectedValue(new PdfOpenError('password'))
    const w = mountViewer(target(providerOf(PDF_BYTES)), { canDownload: false })
    await flushPromises()
    expect(w.text()).toContain('password protected')
    expect(downloadButton(w).exists()).toBe(false)
  })

  it('an engine that fails to load (the lazy chunk, offline) is the error state with Try again, which opens it again', async () => {
    vi.mocked(openPdf).mockRejectedValueOnce(new TypeError('Failed to fetch dynamically imported module'))
    const fake = fakeDocument(2)
    vi.mocked(openPdf).mockResolvedValue(fake.doc)
    const w = mountViewer(target(providerOf(PDF_BYTES)))
    await flushPromises()
    expect(w.find('.p-message-error').text()).toContain(t('files.viewer.error'))
    await w.findAll('button').find((b) => b.text() === t('files.viewer.retry'))!.trigger('click')
    await flushPromises()
    expect(w.findAll('.pdf-page')).toHaveLength(2)
  })

  it('a PDF over the cap is not decrypted past its first chunk: the limit is named and the download offered', async () => {
    const p = providerOf(PDF_BYTES, { byteSize: PDF_CAP + 10 * PLAINTEXT_CHUNK_SIZE, chunkCount: Math.ceil(PDF_CAP / PLAINTEXT_CHUNK_SIZE) + 10 })
    const w = mountViewer(target(p))
    await flushPromises()
    expect(w.text()).toContain(t('files.viewer.tooLarge', { limit: formatBytes(PDF_CAP, 'en') }))
    expect(p.read).toEqual([0])
    expect(openPdf).not.toHaveBeenCalled()
  })

  it('a text that merely mentions the header is text, never handed to the PDF engine', async () => {
    const w = mountViewer({ id: 'b', source: providerOf(new TextEncoder().encode('A note: files start with %PDF- and then a version.')), hints: { type: 'text/plain', name: 'n.txt' } })
    await flushPromises()
    expect(openPdf).not.toHaveBeenCalled()
    expect(w.find('pre').exists()).toBe(true)
  })
})

// --- drawing only what is near --------------------------------------------------------------------------------------------
describe('pages are drawn lazily', () => {
  it('draws nothing until the observer reports a page near the viewport, then exactly those pages', async () => {
    const { w, fake } = await openReady(5)
    expect(fake.doc.render).not.toHaveBeenCalled()
    intersect(nearObserver(), { 1: true, 2: true })
    await flushPromises()
    expect(fake.jobs.map((j) => j.page).sort()).toEqual([1, 2])
    expect(fake.doc.render).toHaveBeenCalledTimes(2)
    const first = fake.jobs.find((j) => j.page === 1)!
    expect(first.cssWidth).toBe(798) // no layout here: the 800 px fallback minus the page's 2 px border allowance
    expect(first.pixelRatio).toBe(1)
    expect(first.canvas).toBe(w.findAll('canvas')[0].element)
  })

  it('observes every page placeholder, with a margin so a page is ready before it scrolls into view', async () => {
    await openReady(7)
    expect(nearObserver().targets).toHaveLength(7)
    expect(nearObserver().options?.rootMargin).toMatch(/100%/)
  })

  it('frees a page that left the neighbourhood: its drawing is cancelled and its bitmap is released', async () => {
    const { w, fake } = await openReady(5)
    intersect(nearObserver(), { 1: true, 2: true })
    await flushPromises()
    const [j1] = fake.jobs.filter((j) => j.page === 1)
    j1.finish()
    await flushPromises()
    expect(j1.canvas.width).toBe(50)
    intersect(nearObserver(), { 1: false })
    await flushPromises()
    expect(j1.canvas.width).toBe(0)
    expect(j1.canvas.height).toBe(0)
    const j2 = fake.jobs.find((j) => j.page === 2)!
    intersect(nearObserver(), { 2: false }) // still drawing: cancelled, not left running
    await flushPromises()
    expect(j2.cancel).toHaveBeenCalled()
    expect(w.findAll('canvas')[1].element.width).toBe(0)
  })

  it('draws a page again when it comes back, and not twice while it is already drawn', async () => {
    const { fake } = await openReady(3)
    intersect(nearObserver(), { 1: true })
    await flushPromises()
    fake.jobs[0].finish()
    await flushPromises()
    intersect(nearObserver(), { 1: true }) // reported again, unchanged
    await flushPromises()
    expect(fake.doc.render).toHaveBeenCalledTimes(1)
    intersect(nearObserver(), { 1: false })
    intersect(nearObserver(), { 1: true })
    await flushPromises()
    expect(fake.doc.render).toHaveBeenCalledTimes(2)
  })

  it('a page that fails to draw says so in its place; the others are untouched', async () => {
    const { w, fake } = await openReady(3)
    fake.doc.render.mockImplementationOnce(() => ({ done: Promise.reject(new Error('bad page')), cancel: vi.fn() }))
    intersect(nearObserver(), { 1: true, 2: true })
    await flushPromises()
    expect(w.findAll('.pdf-page')[0].text()).toContain(t('files.viewer.pdf.pageFailed', { page: 1 }))
    expect(w.findAll('.pdf-page')[1].text()).not.toContain(t('files.viewer.pdf.pageFailed', { page: 2 }))
  })
})

describe('the page indicator and the zoom controls', () => {
  it('"Page n of N" follows the page that is most in view', async () => {
    const { w } = await openReady(9)
    intersect(currentObserver(), { 1: 0.4, 2: 0.6 })
    await flushPromises()
    expect(indicator(w)).toBe(t('files.viewer.pdf.pageOf', { page: 2, total: 9 }))
    intersect(currentObserver(), { 2: 0.1, 3: 0.9 })
    await flushPromises()
    expect(indicator(w)).toBe(t('files.viewer.pdf.pageOf', { page: 3, total: 9 }))
  })

  it('has labelled, real buttons for zoom out, zoom in and fit-width (keyboard and screen reader)', async () => {
    const { w } = await openReady(2)
    for (const key of ['zoomOut', 'zoomIn', 'fitWidth']) {
      const b = w.find(`button[aria-label="${t(`files.viewer.pdf.${key}`)}"]`)
      expect(b.exists(), key).toBe(true)
      expect(b.element.tagName).toBe('BUTTON')
    }
    expect(w.find('[role="toolbar"]').attributes('aria-label')).toBe(t('files.viewer.pdf.toolbar'))
    expect(w.find('.pdf-scroll').attributes('tabindex')).toBe('0') // arrow keys / Page Down scroll the pages
  })

  it('zoom in widens the pages and draws the near ones again at the new width; fit-width returns to 100 %', async () => {
    const { w, fake } = await openReady(3)
    intersect(nearObserver(), { 1: true })
    await flushPromises()
    fake.jobs[0].finish()
    await flushPromises()
    const fit = w.find(`button[aria-label="${t('files.viewer.pdf.fitWidth')}"]`)
    expect(fit.attributes('disabled')).toBeDefined() // already fitted
    await w.find(`button[aria-label="${t('files.viewer.pdf.zoomIn')}"]`).trigger('click')
    await flushPromises()
    expect(w.find('.pdf-zoom-level').text()).toBe('125 %')
    expect(fake.doc.render).toHaveBeenCalledTimes(2)
    expect(fake.jobs[1].cssWidth).toBe(998) // 798 x 1.25
    expect(w.find('.pdf-page').attributes('style')).toContain('width: 998px')
    expect(fit.attributes('disabled')).toBeUndefined()
    await fit.trigger('click')
    await flushPromises()
    expect(w.find('.pdf-zoom-level').text()).toBe('100 %')
    expect(fake.jobs[2].cssWidth).toBe(798)
  })

  it('zoom out narrows them, and each end of the range disables its button', async () => {
    const { w } = await openReady(2)
    const out = w.find(`button[aria-label="${t('files.viewer.pdf.zoomOut')}"]`)
    const inn = w.find(`button[aria-label="${t('files.viewer.pdf.zoomIn')}"]`)
    await out.trigger('click')
    expect(w.find('.pdf-zoom-level').text()).toBe('75 %')
    await out.trigger('click')
    expect(w.find('.pdf-zoom-level').text()).toBe('50 %')
    expect(out.attributes('disabled')).toBeDefined()
    for (let i = 0; i < 10; i++) await inn.trigger('click')
    expect(w.find('.pdf-zoom-level').text()).toBe('400 %')
    expect(inn.attributes('disabled')).toBeDefined()
  })

  it('sizes a placeholder from the page\'s real shape once it is known (a landscape page is not A4)', async () => {
    const { w } = await openReady(2)
    intersect(nearObserver(), { 1: true })
    await flushPromises()
    expect(w.find('.pdf-page').attributes('style')).toContain('height: 399px') // 798 x (100 / 200)
  })
})

// --- undoing ---------------------------------------------------------------------------------------------------------------
describe('everything the viewer made is undone', () => {
  it('unmounting destroys the document and cancels what is being drawn', async () => {
    const { w, fake } = await openReady(3)
    intersect(nearObserver(), { 1: true })
    await flushPromises()
    const job = fake.jobs[0]
    w.unmount()
    expect(fake.doc.destroy).toHaveBeenCalledTimes(1)
    expect(job.cancel).toHaveBeenCalled()
    expect(job.canvas.width).toBe(0)
  })

  it('a replaced file destroys the old document and opens the new one', async () => {
    const first = fakeDocument(2)
    const second = fakeDocument(6)
    vi.mocked(openPdf).mockResolvedValueOnce(first.doc).mockResolvedValueOnce(second.doc)
    const w = mountViewer(target(providerOf(PDF_BYTES), 'blob-1'))
    await flushPromises()
    await w.setProps({ target: target(providerOf(PDF_BYTES), 'blob-2') })
    await flushPromises()
    expect(first.doc.destroy).toHaveBeenCalledTimes(1)
    expect(second.doc.destroy).not.toHaveBeenCalled()
    expect(w.findAll('.pdf-page')).toHaveLength(6)
  })

  it('navigating to a file that is no PDF destroys the document too', async () => {
    const { w, fake } = await openReady(2)
    await w.setProps({ target: null })
    await flushPromises()
    expect(fake.doc.destroy).toHaveBeenCalledTimes(1)
    expect(w.find('.pdf-viewer').exists()).toBe(false)
  })

  it('a document that finishes opening after the page moved on is destroyed at once, never shown', async () => {
    let open: (d: PdfDocument) => void = () => {}
    vi.mocked(openPdf).mockReturnValue(new Promise((r) => (open = r)))
    const w = mountViewer(target(providerOf(PDF_BYTES)))
    await flushPromises()
    w.unmount()
    const late = fakeDocument(2)
    open(late.doc)
    await flushPromises()
    expect(late.doc.destroy).toHaveBeenCalledTimes(1)
  })

  it('unmounting aborts the open in flight: the signal handed to the engine is aborted', async () => {
    vi.mocked(openPdf).mockReturnValue(new Promise(() => {}))
    const w = mountViewer(target(providerOf(PDF_BYTES)))
    await flushPromises()
    const signal = vi.mocked(openPdf).mock.calls[0][1]!
    expect(signal.aborted).toBe(false)
    w.unmount()
    expect(signal.aborted).toBe(true)
  })

  it('Stop while opening cancels it and says so', async () => {
    vi.mocked(openPdf).mockImplementation((_b, signal) => new Promise((_r, reject) => signal?.addEventListener('abort', () => reject(signal.reason))))
    const w = mountViewer(target(providerOf(PDF_BYTES)))
    await flushPromises()
    await w.find('[role="status"] button').trigger('click')
    await flushPromises()
    expect(w.text()).toContain(t('files.viewer.cancelled'))
  })
})

// --- what a PDF can never do here --------------------------------------------------------------------------------------------
describe('the page holds nothing a PDF could act through', () => {
  it('has no frame, object, embed, link or form control inside the pages', async () => {
    const { w } = await openReady(3)
    intersect(nearObserver(), { 1: true, 2: true, 3: true })
    await flushPromises()
    expect(w.find('iframe, object, embed, a, form, input, textarea, select, script').exists()).toBe(false)
    expect(w.find('.pdf-scroll').findAll('*').every((e) => ['DIV', 'CANVAS', 'SPAN'].includes(e.element.tagName))).toBe(true)
  })

  it('the member and the share guest get the very same viewer', async () => {
    const a = await openReady(3)
    const html = a.w.find('.file-viewer').html()
    a.w.unmount()
    const fake = fakeDocument(3)
    vi.mocked(openPdf).mockResolvedValue(fake.doc)
    // A guest's reader is the same shape (a chunk provider); the component takes no role and branches on none.
    const guest = mountViewer(target(providerOf(PDF_BYTES)))
    await flushPromises()
    expect(guest.find('.file-viewer').html()).toBe(html)
  })

  it('never interprets markup: no v-html or innerHTML in the viewer\'s sources', () => {
    const base = join(process.cwd(), 'src/features/files')
    for (const file of ['components/FileViewer.vue', 'components/PdfPages.vue', 'useFileViewer.ts', 'pdf/pdfEngine.ts', 'pdf/pdfjsRuntime.ts']) {
      const src = readFileSync(join(base, file), 'utf8').replace(/\/\/.*$/gm, '')
      expect(src, file).not.toMatch(/v-html|innerHTML|outerHTML|insertAdjacentHTML|document\.write/)
    }
  })

  it('loads pdf.js only through a dynamic import: no source but the runtime itself imports it, none imports the runtime statically', () => {
    const base = join(process.cwd(), 'src/features/files')
    for (const file of ['components/FileViewer.vue', 'components/PdfPages.vue', 'useFileViewer.ts', 'view.ts', 'pdf/pdfEngine.ts', 'pdf/pdfOptions.ts']) {
      const src = readFileSync(join(base, file), 'utf8')
      expect(src, file).not.toMatch(/from\s+['"]pdfjs-dist/)
      expect(src, file).not.toMatch(/import\s+[^(]*from\s+['"][^'"]*pdfjsRuntime/)
    }
    expect(readFileSync(join(base, 'pdf/pdfEngine.ts'), 'utf8')).toMatch(/await import\('\.\/pdfjsRuntime'\)/)
  })
})

// --- the text layer ------------------------------------------------------------------------------------------------------------
describe('the text layer (select and copy)', () => {
  const hostOf = (w: W, n: number) => w.findAll('.pdf-page')[n - 1].find('.pdf-text-layer')

  it('every page has a text host over its canvas, empty until the page is drawn', async () => {
    const { w } = await openReady(3)
    expect(w.findAll('.pdf-text-layer')).toHaveLength(3)
    for (const n of [1, 2, 3]) {
      const page = w.findAll('.pdf-page')[n - 1]
      const kids = page.element.children
      expect([...kids].findIndex((e) => e.tagName === 'CANVAS')).toBeLessThan([...kids].findIndex((e) => e.classList.contains('pdf-text-layer')))
      expect(hostOf(w, n).element.childElementCount).toBe(0)
    }
  })

  it('draws the layer with the page: one text layer per drawn page, handed to the engine with the canvas', async () => {
    const { w, fake } = await openReady(5)
    intersect(nearObserver(), { 1: true, 2: true })
    await flushPromises()
    const drawn = fake.doc.render.mock.calls.length
    expect(drawn).toBe(2)
    expect(fake.doc.render.mock.calls.filter((c) => c[4] instanceof HTMLElement)).toHaveLength(drawn) // as many layers as drawn pages
    expect(fake.jobs.find((j) => j.page === 1)!.textHost).toBe(hostOf(w, 1).element)
    expect(fake.jobs.find((j) => j.page === 2)!.textHost).toBe(hostOf(w, 2).element)
    expect(hostOf(w, 1).text()).toBe('text of page 1')
    expect(hostOf(w, 3).element.childElementCount).toBe(0) // far away: nothing built
  })

  it('tears the layer down with the page: far away, the host is emptied together with the bitmap', async () => {
    const { w, fake } = await openReady(4)
    intersect(nearObserver(), { 1: true })
    await flushPromises()
    fake.jobs[0].finish()
    await flushPromises()
    expect(hostOf(w, 1).element.childElementCount).toBe(1)
    intersect(nearObserver(), { 1: false })
    await flushPromises()
    expect(hostOf(w, 1).element.childElementCount).toBe(0)
    expect(fake.jobs[0].canvas.width).toBe(0)
  })

  it('a page still being drawn when it leaves the neighbourhood is cancelled together with its layer', async () => {
    const { w, fake } = await openReady(3)
    intersect(nearObserver(), { 1: true })
    await flushPromises()
    intersect(nearObserver(), { 1: false })
    await flushPromises()
    expect(fake.jobs[0].cancel).toHaveBeenCalled()
    expect(hostOf(w, 1).element.childElementCount).toBe(0)
  })

  it('zoom lays the layer out again: one more layer per redrawn page, at the new width', async () => {
    const { w, fake } = await openReady(3)
    intersect(nearObserver(), { 1: true })
    await flushPromises()
    fake.jobs[0].finish()
    await flushPromises()
    await w.find(`button[aria-label="${t('files.viewer.pdf.zoomIn')}"]`).trigger('click')
    await flushPromises()
    expect(fake.doc.render).toHaveBeenCalledTimes(2)
    const layers = fake.doc.render.mock.calls.filter((c) => c[4] instanceof HTMLElement)
    expect(layers).toHaveLength(2)
    expect(layers[1][2]).toBe(998)
    expect(layers[1][4]).toBe(hostOf(w, 1).element) // the same host, rebuilt (emptied first), not a second one on top
    expect(hostOf(w, 1).element.childElementCount).toBe(1)
  })

  it('unmounting empties every host and drops the layers', async () => {
    const { w, fake } = await openReady(3)
    intersect(nearObserver(), { 1: true, 2: true })
    await flushPromises()
    fake.jobs.forEach((j) => j.finish())
    await flushPromises()
    const hosts = w.findAll('.pdf-text-layer').map((h) => h.element)
    w.unmount()
    expect(hosts.every((h) => h.childElementCount === 0)).toBe(true)
  })

  it('is spans only: no link, no handler, nothing focusable - a PDF\'s link text is selectable, never clickable', async () => {
    const { w } = await openReady(2)
    intersect(nearObserver(), { 1: true, 2: true })
    await flushPromises()
    const layer = hostOf(w, 1)
    expect(layer.findAll('*').every((e) => e.element.tagName === 'SPAN')).toBe(true)
    expect(layer.find('a, button, [href], [tabindex], [onclick]').exists()).toBe(false)
  })

  it('a drag that starts in a page marks that layer as selecting until the pointer is released anywhere', async () => {
    const { w } = await openReady(2)
    intersect(nearObserver(), { 1: true, 2: true })
    await flushPromises()
    const span = hostOf(w, 1).find('span').element
    span.dispatchEvent(new Event('pointerdown', { bubbles: true }))
    expect(hostOf(w, 1).classes()).toContain('pdf-text-selecting')
    document.body.dispatchEvent(new Event('pointerup', { bubbles: true }))
    expect(hostOf(w, 1).classes()).not.toContain('pdf-text-selecting')
  })

  it('a pointer-down outside any text layer (the toolbar, the canvas margin) marks nothing', async () => {
    const { w } = await openReady(1)
    w.find('.pdf-scroll').element.dispatchEvent(new Event('pointerdown', { bubbles: true }))
    expect(w.find('.pdf-text-selecting').exists()).toBe(false)
  })
})
