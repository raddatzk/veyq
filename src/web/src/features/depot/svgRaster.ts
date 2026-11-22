// An SVG file's picture, drawn into pixels at the scale it is looked at (inbox #1059, `docs/apps/depot.md` § The viewer,
// ADR 0155 § The file facts' slice D11 and D14). The page shows an SVG only as pixels: the SVG-as-image mode runs no script
// and loads nothing it names, but an image's blob URL is the app's origin, and opened as a document of its own (the
// browser's "Open image in new tab", a URL pasted into the address bar) the SVG's script ran there in Chrome, beside the
// keys, the page's CSP not inherited (measured 2026-11-22, headless Chrome, the file facts' browser pass). So the SVG's own
// URL never reaches the page: it is decoded off the DOM, in image mode, into an `Image` no document holds, and its URL is
// revoked the moment it is decoded. What the page shows is a `<canvas>` this module paints from that decoded image — once
// at the size that fits, and again, debounced, at each zoom (`SvgView.vue`): drawing a decoded SVG image onto a canvas
// renders its vectors at the target's scale, so a zoomed picture is sharp, never an enlarged bitmap.
//
// What holds each guarantee (D9's must-nots):
// - no script, no fetch, no XXE: the image mode (an `Image` decoding a blob, never in the DOM) - the same mode as before;
// - the SVG's URL is no document: it is revoked before `decodeSvg` returns, and nothing but this module ever held it;
// - a `foreignObject` is refused: it taints a canvas it is drawn onto (Chrome, Safari), and `decodeSvg` reads one pixel
//   back before it answers - a tainted canvas throws, so the SVG is "cannot be shown" with the download, exactly as the
//   PNG's `toBlob` refused it before. Every later paint reads one pixel back too and clears the canvas if it throws;
// - memory: no canvas this module paints holds more than [SVG_MAX_PIXELS] pixels or [SVG_MAX_EDGE] on a side
//   ([planRaster]); a zoomed view paints only the part in view (`SvgView.vue`), so the zoom does not raise the bound.

/** The most pixels one painted canvas holds: 4096 x 4096 (64 MiB RGBA; iOS Safari's own canvas area limit). */
export const SVG_MAX_PIXELS = 4096 * 4096
/** The longest side, in pixels, of one painted canvas. */
export const SVG_MAX_EDGE = 8192
/** The size a browser gives an SVG that names none (a replaced element's default). */
const DEFAULT_WIDTH = 300
const DEFAULT_HEIGHT = 150

/** A part of the picture as it is laid out: CSS pixels, the picture `scale` CSS px per SVG unit. */
export type SvgRegion = { x: number; y: number; width: number; height: number }

/** What one paint makes: the canvas' pixel size and the device pixels per CSS pixel it was painted at. */
export type SvgRaster = { width: number; height: number; ratio: number }

/**
 * The canvas for [region] (CSS px) at [deviceRatio] device px per CSS px: the ratio itself where the result fits
 * [SVG_MAX_PIXELS] and [SVG_MAX_EDGE], else the largest ratio that does (the cap wins over sharpness).
 */
export function planRaster(region: Pick<SvgRegion, 'width' | 'height'>, deviceRatio: number): SvgRaster {
  const w = Math.max(1, region.width)
  const h = Math.max(1, region.height)
  const wanted = Number.isFinite(deviceRatio) && deviceRatio > 0 ? deviceRatio : 1
  const ratio = Math.min(wanted, Math.sqrt(SVG_MAX_PIXELS / (w * h)), SVG_MAX_EDGE / w, SVG_MAX_EDGE / h)
  // floor, not round: a rounded-up side could carry the area one row over the cap
  return { width: Math.max(1, Math.floor(w * ratio)), height: Math.max(1, Math.floor(h * ratio)), ratio }
}

/** A decoded SVG, painted again at each scale; its URL is already gone. */
export interface SvgPicture {
  /** The SVG's own size (its `width`/`height`, else the browser's 300 x 150), in SVG units. */
  readonly width: number
  readonly height: number
  /**
   * Paint [region] of the picture laid out at [scale] CSS px per SVG unit onto [canvas], at [deviceRatio] device px per
   * CSS px within the cap ([planRaster]). Throws (the canvas emptied) where the browser refuses to read the result back.
   */
  paint(canvas: HTMLCanvasElement, region: SvgRegion, scale: number, deviceRatio: number): SvgRaster
  /** Let the decoded image go; a later paint throws. */
  close(): void
}

/** One pixel read back: throws a `SecurityError` on a tainted canvas (a `foreignObject`, Chrome and Safari). */
function readBack(context: CanvasRenderingContext2D): void {
  context.getImageData(0, 0, 1, 1)
}

/**
 * [svg] (a blob the sniff said is an SVG) decoded in image mode, its URL revoked, refused where it taints a canvas.
 * Throws where the browser cannot decode or draw it (the viewer then says so and offers the download).
 */
export async function decodeSvg(svg: Blob, signal?: AbortSignal): Promise<SvgPicture> {
  const url = URL.createObjectURL(svg)
  let image: HTMLImageElement | null = new Image()
  try {
    image.src = url
    await image.decode()
  } finally {
    URL.revokeObjectURL(url)
  }
  signal?.throwIfAborted()
  const width = image.naturalWidth || DEFAULT_WIDTH
  const height = image.naturalHeight || DEFAULT_HEIGHT
  // The gate: draw it once, small, and read a pixel back - before anything is shown.
  const probe = document.createElement('canvas')
  probe.width = 1
  probe.height = 1
  const probeContext = probe.getContext('2d')
  if (!probeContext) throw new Error('no 2d canvas')
  probeContext.drawImage(image, 0, 0, 1, 1)
  try {
    readBack(probeContext)
  } catch {
    throw new Error('the SVG did not draw')
  } finally {
    probe.width = 0
    probe.height = 0
  }
  return {
    width,
    height,
    paint(canvas, region, scale, deviceRatio) {
      if (!image) throw new Error('the SVG is closed')
      const raster = planRaster(region, deviceRatio)
      canvas.width = raster.width
      canvas.height = raster.height
      const context = canvas.getContext('2d')
      if (!context) throw new Error('no 2d canvas')
      const k = raster.ratio
      // The whole picture at its laid-out size, shifted so [region]'s corner is the canvas' origin: the browser renders
      // the vectors at this scale and only the part on the canvas is painted.
      context.drawImage(image, -region.x * k, -region.y * k, width * scale * k, height * scale * k)
      try {
        readBack(context)
      } catch {
        canvas.width = 0
        canvas.height = 0
        throw new Error('the SVG did not draw')
      }
      return raster
    },
    close() {
      image = null
    },
  }
}
