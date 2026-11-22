// An SVG file's picture, drawn once into pixels (inbox #1059, `docs/apps/depot.md` § The viewer). The page shows an SVG only
// as an image — the SVG-as-image mode runs no script and loads nothing it names — but the `<img>`'s blob URL is the app's
// origin: opened as a document of its own (the browser's "Open image in new tab", a URL pasted into the address bar), the
// SVG's script ran there in Chrome, beside the keys, the page's CSP not inherited (measured 2026-11-22, headless Chrome, the
// file facts' browser pass). So the SVG's own URL never reaches the page: it is decoded off the DOM, in image mode, drawn
// onto a canvas and handed back as a PNG; its URL is revoked as soon as it is drawn. What the page shows, and what a tab can
// open, is the PNG.

/** The longest side, in pixels, an SVG is drawn at (the apps' `FileView.IMAGE_EDGE`). */
export const SVG_EDGE = 4096
/** The size a browser gives an SVG that names none (a replaced element's default). */
const DEFAULT_WIDTH = 300
const DEFAULT_HEIGHT = 150

/**
 * [svg] (a blob the sniff said is an SVG) as a PNG: its own size times the screen's pixel ratio (at most 2), at most
 * [SVG_EDGE] on its longer side. Throws where the browser cannot decode it (the viewer then says so and offers the download).
 */
export async function rasterizeSvg(svg: Blob, signal?: AbortSignal): Promise<Blob> {
  const url = URL.createObjectURL(svg)
  try {
    const image = new Image()
    image.src = url
    await image.decode()
    signal?.throwIfAborted()
    const width = image.naturalWidth || DEFAULT_WIDTH
    const height = image.naturalHeight || DEFAULT_HEIGHT
    const ratio = Math.min(2, Math.max(1, globalThis.devicePixelRatio || 1))
    const scale = Math.min(ratio, SVG_EDGE / Math.max(width, height))
    const canvas = document.createElement('canvas')
    canvas.width = Math.max(1, Math.round(width * scale))
    canvas.height = Math.max(1, Math.round(height * scale))
    const context = canvas.getContext('2d')
    if (!context) throw new Error('no 2d canvas')
    context.drawImage(image, 0, 0, canvas.width, canvas.height)
    return await new Promise<Blob>((resolve, reject) => canvas.toBlob((b) => (b ? resolve(b) : reject(new Error('the SVG did not draw'))), 'image/png'))
  } finally {
    URL.revokeObjectURL(url)
  }
}
