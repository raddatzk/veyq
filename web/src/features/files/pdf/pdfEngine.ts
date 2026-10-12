// The PDF engine the viewer talks to (inbox #412): `openPdf(bytes)` -> a `PdfDocument` that reports its page count, the size
// of a page and draws a page onto a canvas, and is destroyed when the viewer lets go. It is the seam: the viewer and its
// tests know this interface, only this file knows pdf.js, and pdf.js itself is loaded on the first PDF, never before
// (`pdfjsRuntime.ts`, a dynamic `import()` - the lazy chunk `npm run verify:pdf-worker` checks).
//
// No pdf.js type is exported: the interface below is structural, so nothing here pulls pdf.js into a bundle by its types.
// Rendering draws the page's content and the annotations that have an appearance (a stamp, a form field's text, a link's
// border) onto the canvas. There is no annotation layer and no text layer, so no link, button or field is a live element of
// the page: a PDF cannot navigate the app, open a URL or run anything (the options in `pdfOptions.ts`).
import { PDF_MAX_CANVAS_PIXELS, PDF_MAX_PAGES } from './pdfOptions'

/** Why a PDF is not shown, known only after trying: it is protected by a password, it is not a readable PDF, or it is too long. */
export type PdfOpenFailure = 'password' | 'broken' | 'pages'

export class PdfOpenError extends Error {
  constructor(readonly reason: PdfOpenFailure) {
    super(`pdf: ${reason}`)
    this.name = 'PdfOpenError'
  }
}

/** A page's size in PDF points at scale 1 (its rotation applied). */
export type PdfPageSize = { width: number; height: number }

/** A page being drawn: `done` resolves `true` once drawn, `false` when it was cancelled, and rejects when drawing failed. */
export type PdfRenderJob = { done: Promise<boolean>; cancel: () => void }

export interface PdfDocument {
  readonly numPages: number
  /** The size of page `page` (1-based). */
  pageSize(page: number): Promise<PdfPageSize>
  /** Draw page `page` onto `canvas`, `cssWidth` CSS pixels wide at `pixelRatio` device pixels per CSS pixel (sets the canvas' pixel size). */
  render(page: number, canvas: HTMLCanvasElement, cssWidth: number, pixelRatio: number): PdfRenderJob
  /** Free the document, its pages and its worker. Safe to call twice. */
  destroy(): Promise<void>
}

/** pdf.js names its failures; the name is the contract (a minified bundle would rename a class). */
const PASSWORD = new Set(['PasswordException'])
const BROKEN = new Set(['InvalidPDFException', 'FormatError', 'XRefParseException', 'UnknownErrorException', 'MissingPDFException'])

function nameOf(e: unknown): string {
  return typeof e === 'object' && e !== null && 'name' in e ? String((e as { name: unknown }).name) : ''
}

function abortError(signal: AbortSignal): unknown {
  return signal.reason ?? new DOMException('Cancelled', 'AbortError')
}

/**
 * Open `bytes` (taken over: pdf.js transfers the buffer to its worker). Throws {@link PdfOpenError} for a password, an
 * unreadable document or more than {@link PDF_MAX_PAGES} pages, the signal's reason when aborted, and whatever the loader
 * threw (a chunk that did not load) otherwise.
 */
export async function openPdf(bytes: Uint8Array, signal?: AbortSignal): Promise<PdfDocument> {
  const { getDocument } = await import('./pdfjsRuntime')
  if (signal?.aborted) throw abortError(signal)
  // No `onPassword`: pdf.js then rejects with a PasswordException, which is the "protected" message (no prompt in this cut).
  const task = getDocument(bytes)
  const onAbort = () => void task.destroy().catch(() => {})
  signal?.addEventListener('abort', onAbort, { once: true })
  let doc
  try {
    doc = await task.promise
  } catch (e) {
    if (signal?.aborted) throw abortError(signal)
    await task.destroy().catch(() => {})
    if (PASSWORD.has(nameOf(e))) throw new PdfOpenError('password')
    if (BROKEN.has(nameOf(e))) throw new PdfOpenError('broken')
    throw e
  } finally {
    signal?.removeEventListener('abort', onAbort)
  }
  if (signal?.aborted) {
    await task.destroy().catch(() => {})
    throw abortError(signal)
  }
  if (doc.numPages > PDF_MAX_PAGES) {
    await task.destroy().catch(() => {})
    throw new PdfOpenError('pages')
  }
  let destroyed = false
  return {
    numPages: doc.numPages,
    async pageSize(n) {
      const page = await doc.getPage(n)
      const v = page.getViewport({ scale: 1 })
      page.cleanup()
      return { width: v.width, height: v.height }
    },
    render(n, canvas, cssWidth, pixelRatio) {
      let current: { cancel: () => void } | null = null
      let cancelled = false
      const done = (async () => {
        const page = await doc.getPage(n)
        if (cancelled) return false
        const base = page.getViewport({ scale: 1 })
        let scale = (cssWidth / base.width) * pixelRatio
        const pixels = base.width * base.height * scale * scale
        if (pixels > PDF_MAX_CANVAS_PIXELS) scale *= Math.sqrt(PDF_MAX_CANVAS_PIXELS / pixels)
        const viewport = page.getViewport({ scale })
        canvas.width = Math.max(1, Math.floor(viewport.width))
        canvas.height = Math.max(1, Math.floor(viewport.height))
        const task = page.render({ canvas, viewport })
        current = task
        try {
          await task.promise
          return true
        } catch (e) {
          if (nameOf(e) === 'RenderingCancelledException') return false
          throw e
        } finally {
          page.cleanup()
        }
      })()
      return {
        done,
        cancel: () => {
          cancelled = true
          current?.cancel()
        },
      }
    },
    async destroy() {
      if (destroyed) return
      destroyed = true
      await task.destroy().catch(() => {})
    },
  }
}
