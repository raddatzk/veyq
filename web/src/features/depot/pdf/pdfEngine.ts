// The PDF engine the viewer talks to (inbox #412): `openPdf(bytes)` -> a `PdfDocument` that reports its page count, the size
// of a page and draws a page onto a canvas, and is destroyed when the viewer lets go. It is the seam: the viewer and its
// tests know this interface, only this file knows pdf.js, and pdf.js itself is loaded on the first PDF, never before
// (`pdfjsRuntime.ts`, a dynamic `import()` - the lazy chunk `npm run verify:pdf-worker` checks).
//
// No pdf.js type is exported: the interface below is structural, so nothing here pulls pdf.js into a bundle by its types.
// Rendering draws the page's content and the annotations that have an appearance (a stamp, a form field's text, a link's
// border) onto the canvas, and - when the caller gives a host element - pdf.js' TEXT layer over it: transparent, absolutely
// positioned <span>s holding the page's text so it can be selected and copied. There is NO annotation layer: no link, button or
// field is a live element of the page, and the text layer is spans only (no <a>, no handler), so a PDF cannot navigate the
// app, open a URL or run anything (the options in `pdfOptions.ts`). A page without extractable text (a scan) has an empty layer.
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
  /**
   * Draw page `page` onto `canvas`, `cssWidth` CSS pixels wide at `pixelRatio` device pixels per CSS pixel (sets the canvas'
   * pixel size). With `textHost` the page's text layer is built in it (emptied first), sized by the page's CSS box; the caller
   * empties it again when the page is freed. The job's `cancel` stops both.
   */
  render(page: number, canvas: HTMLCanvasElement, cssWidth: number, pixelRatio: number, textHost?: HTMLElement | null): PdfRenderJob
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

/** pdf.js' `PasswordResponses.INCORRECT_PASSWORD` (1 is NEED_PASSWORD); `pdfEngine.real.test.ts` pins both against the library. */
const INCORRECT_PASSWORD = 2

/**
 * Asks for the document's password; `wrong` is true when the one given before was refused. Resolves the password, or `null`
 * when the user declines. The engine hands the answer to pdf.js and keeps no copy: it never stores, logs or sends it.
 */
export type PdfPasswordPrompt = (wrong: boolean) => Promise<string | null>

/**
 * Open `bytes` (taken over: pdf.js transfers the buffer to its worker). Throws {@link PdfOpenError} for a password, an
 * unreadable document or more than {@link PDF_MAX_PAGES} pages, the signal's reason when aborted, and whatever the loader
 * threw (a chunk that did not load) otherwise. A protected document asks `onPassword` (again after a wrong answer) while the
 * same loading task waits - the buffer stays in pdf.js, no second copy is made; without a prompt, or when it answers `null`,
 * the failure is `PdfOpenError('password')`.
 */
export async function openPdf(bytes: Uint8Array, signal?: AbortSignal, onPassword?: PdfPasswordPrompt): Promise<PdfDocument> {
  const { getDocument, TextLayer } = await import('./pdfjsRuntime')
  if (signal?.aborted) throw abortError(signal)
  const task = getDocument(bytes)
  let declined = false
  // Without a handler pdf.js rejects with a PasswordException, which is the "protected" statement and the download.
  if (onPassword) {
    task.onPassword = (update: (password: string) => void, reason: number) => {
      onPassword(reason === INCORRECT_PASSWORD).then(
        (password) => {
          if (signal?.aborted) return
          if (password === null) {
            declined = true
            void task.destroy().catch(() => {})
          } else update(password)
        },
        () => {
          declined = true
          void task.destroy().catch(() => {})
        },
      )
    }
  }
  const onAbort = () => void task.destroy().catch(() => {})
  signal?.addEventListener('abort', onAbort, { once: true })
  let doc
  try {
    doc = await task.promise
  } catch (e) {
    if (signal?.aborted) throw abortError(signal)
    if (declined) throw new PdfOpenError('password')
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
  type Page = Awaited<ReturnType<typeof doc.getPage>>
  /**
   * Build `page`'s text layer in `host` at `cssScale` (CSS pixels per PDF point). Resolves when laid out, cancelled or failed -
   * never rejects: a page whose text cannot be extracted simply has an empty layer.
   */
  const buildText = async (page: Page, host: HTMLElement, cssScale: number, setLayer: (l: { cancel: () => void }) => void, isCancelled: () => boolean): Promise<void> => {
    host.replaceChildren()
    host.style.setProperty('--total-scale-factor', String(cssScale))
    if (isCancelled()) return
    try {
      const viewport = page.getViewport({ scale: cssScale })
      const layer = new TextLayer({ textContentSource: page.streamTextContent({ includeMarkedContent: true }), container: host, viewport })
      setLayer(layer)
      // The layer is laid out in the page's UNROTATED space (`rawDims`) and turned into place by `[data-main-rotation]` in
      // Depot's `depot.css`, so its box is the raw page at this scale - not the (rotated) page box. pdf.js writes the same size
      // with the CSS `round()` function, which not every browser has; this is the plain px the page needs.
      const { pageWidth, pageHeight } = viewport.rawDims as { pageWidth: number; pageHeight: number } // typed as `object` upstream
      host.style.width = `${pageWidth * cssScale}px`
      host.style.height = `${pageHeight * cssScale}px`
      await layer.render()
      // The element pdf.js' own viewer appends so a drag that leaves the text does not select the whole page.
      const end = document.createElement('div')
      end.className = PDF_TEXT_END_CLASS
      host.append(end)
    } catch {
      // cancelled or unreadable text: what was laid out so far stays; nothing is shown as an error
    }
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
    render(n, canvas, cssWidth, pixelRatio, textHost) {
      let current: { cancel: () => void } | null = null
      let text: { cancel: () => void } | null = null
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
        // The text layer is laid out at CSS size (the canvas' pixel ratio and cap do not apply to it) and is independent of the
        // bitmap: its failure leaves the page drawn with an empty layer, never a failed page.
        const textDone = textHost ? buildText(page, textHost, cssWidth / base.width, (l) => (text = l), () => cancelled) : Promise.resolve()
        try {
          await task.promise
          await textDone
          return !cancelled
        } catch (e) {
          if (nameOf(e) === 'RenderingCancelledException') return false
          throw e
        } finally {
          await textDone
          page.cleanup()
        }
      })()
      return {
        done,
        cancel: () => {
          cancelled = true
          current?.cancel()
          text?.cancel()
        },
      }
    },
    async destroy() {
      if (destroyed) return
      destroyed = true
      TextLayer.cleanup()
      await task.destroy().catch(() => {})
    },
  }
}

/** The class of the element closing a text layer, styled in Depot's `depot.css` with the `.pdf-text-layer` the page template puts on the host. */
export const PDF_TEXT_END_CLASS = 'pdf-text-end'
