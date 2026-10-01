// en.memory.derivatives (web) — the image pixel stage itself: the longest-edge math, the progressive-halving
// plan, and the draw + encode that executes it. It is the ONE implementation both threads run: the page deriver
// (`mediaDeriver.ts`) drives it on `HTMLCanvasElement` + `toBlob`, the media Worker (`mediaDerive.worker.ts`) on
// `OffscreenCanvas` + `convertToBlob`. Only the canvas port differs, so the tiers cannot drift between the two.
//
// The Worker imports this module, so nothing here may touch the DOM at module top level, and nothing here may
// import `mediaDeriver.ts` (which constructs the Worker — the Worker importing it would bundle a Worker into
// itself). The page port's `document.createElement` lives in `mediaDeriver.ts`, at use.

/** Either canvas kind a port hands out; both are drawable sources for the next halving step. */
export type PixelCanvas = HTMLCanvasElement | OffscreenCanvas
type Context2D = CanvasRenderingContext2D | OffscreenCanvasRenderingContext2D

/**
 * Where the pixel stage draws and encodes: `create` hands out a blank canvas of the given size, `encode` turns
 * the final one into image bytes. The page port is `HTMLCanvasElement` + `toBlob` (a paint-gated encode in a
 * hidden tab — the reason the Worker exists); the Worker port is `OffscreenCanvas` + `convertToBlob`.
 */
export interface CanvasPort<C extends PixelCanvas = PixelCanvas> {
  create(width: number, height: number): C
  encode(canvas: C, mimeType: string, quality: number): Promise<Blob | null>
}

/**
 * Longest-edge target math (pure): scale `width`×`height` so the longest edge is ≤ `maxEdge`, preserving the
 * aspect ratio and **never upscaling**. Each side is at least 1px.
 */
export function scaleToMaxEdge(width: number, height: number, maxEdge: number): { width: number; height: number } {
  const longest = Math.max(width, height)
  if (longest <= maxEdge || longest === 0) return { width, height }
  const scale = maxEdge / longest
  return { width: Math.max(1, Math.round(width * scale)), height: Math.max(1, Math.round(height * scale)) }
}

/**
 * The progressive-halving plan (pure) for a `w`×`h` source at `maxEdge`: the intermediate sizes to draw through,
 * then the final size that is encoded. A single large `drawImage` (e.g. 4032→512 ≈ 8×) stays soft even at
 * `imageSmoothingQuality:'high'` — the browser applies ONE bilinear pass, not an iterative filter, so it drops
 * high-frequency detail. Halving in ≤2× steps (each with high smoothing) preserves that detail, approximating the
 * multi-tap resample the legacy server did with libvips (operator-blind forces it on-device here). An
 * intermediate step is taken only while the current size is MORE than twice the target on some side, so the
 * last draw is always ≤2×; `scaleToMaxEdge` never upscales, so a small source has no steps at all.
 */
export function halvingPlan(
  w: number,
  h: number,
  maxEdge: number,
): { steps: { w: number; h: number }[]; final: { w: number; h: number } } {
  const { width, height } = scaleToMaxEdge(w, h, maxEdge)
  const steps: { w: number; h: number }[] = []
  let curW = w
  let curH = h
  while (curW > width * 2 || curH > height * 2) {
    const nextW = Math.max(Math.round(curW / 2), width)
    const nextH = Math.max(Math.round(curH / 2), height)
    steps.push({ w: nextW, h: nextH })
    curW = nextW
    curH = nextH
  }
  return { steps, final: { w: width, h: height } }
}

/**
 * Downscale `src` (intrinsically `w`×`h`) to `maxEdge` along [halvingPlan] on `port`'s canvases, then encode the
 * final canvas. The intermediate steps keep alpha (no fill); `background` is applied only on the FINAL canvas.
 */
export async function drawEncoded<C extends PixelCanvas>(
  port: CanvasPort<C>,
  src: CanvasImageSource,
  w: number,
  h: number,
  maxEdge: number,
  quality: number,
  mimeType = 'image/jpeg',
  background?: string,
): Promise<Uint8Array> {
  const plan = halvingPlan(w, h, maxEdge)
  let curSrc: CanvasImageSource = src
  for (const step of plan.steps) curSrc = drawStep(port, curSrc, step.w, step.h)
  const canvas = drawStep(port, curSrc, plan.final.w, plan.final.h, background)
  const blob = await port.encode(canvas, mimeType, quality)
  if (!blob) throw new Error('encode_failed')
  return new Uint8Array(await blob.arrayBuffer())
}

/** One high-quality `drawImage` onto a fresh `port` canvas at `width`×`height` (each step of [drawEncoded]).
 *  `background` (a CSS colour) fills the canvas before drawing, so a transparent source lands on it rather than
 *  the transparent-black default; omit it to preserve alpha (only meaningful with an alpha-capable encode). */
function drawStep<C extends PixelCanvas>(
  port: CanvasPort<C>,
  src: CanvasImageSource,
  width: number,
  height: number,
  background?: string,
): C {
  const canvas = port.create(width, height)
  const ctx = (canvas as { getContext(id: '2d'): Context2D | null }).getContext('2d')
  if (!ctx) throw new Error('canvas_unavailable')
  if (background) {
    ctx.fillStyle = background
    ctx.fillRect(0, 0, width, height)
  }
  ctx.imageSmoothingEnabled = true
  ctx.imageSmoothingQuality = 'high'
  ctx.drawImage(src, 0, 0, width, height)
  return canvas
}
