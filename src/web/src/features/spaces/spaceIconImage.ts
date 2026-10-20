// Space-icon pixel stage (uc.spaces.set-space-icon step 3, web). Bakes the framed 1:1 crop of a source image into
// a fixed square, then re-encodes it to a WebP that fits the constant pad budget — stepping quality down until it
// does. Reuses the Memory cover's `deriveImage` seam (downscale + encode, taught `mimeType`/`background` in
// cr-space-icon); the crop bake is icon-specific (a Memory cover stores a reference + crop and never bakes pixels).
import { type MediaDeriver } from '../../shared/image/mediaDeriver'
import { type CropRect } from '../../shared/ui/ImageCropper.vue'
import { ICON_ENCODED_BUDGET } from './spaceIcon'

/** The fixed pixel box the icon re-encodes to — 256×256 carries ~2× headroom for a ~38px avatar (the use-case's box). */
export const ICON_EDGE = 256
// Quality is stepped down only if the encode overflows the pad budget; at 256px WebP even q≈0.5 is far under it, so
// the first step almost always wins. The ladder is the safety net, not the common path.
const QUALITY_STEPS = [0.9, 0.82, 0.72, 0.62, 0.5, 0.4]

/** Bake a 1:1 `crop` of `img` at native source resolution onto a square canvas → a lossless PNG blob (the crop
 *  bake; the WebP downscale happens in {@link renderSpaceIcon} via the deriver). A 1:1 crop has `width·natW ==
 *  height·natH`, so the region is square; drawing it onto a square canvas preserves it. */
async function bakeSquareCrop(img: HTMLImageElement, crop: CropRect): Promise<Blob> {
  const side = Math.max(1, Math.round(crop.width * img.naturalWidth))
  const canvas = document.createElement('canvas')
  canvas.width = side
  canvas.height = side
  const ctx = canvas.getContext('2d')
  if (!ctx) throw new Error('canvas_unavailable')
  ctx.imageSmoothingEnabled = true
  ctx.imageSmoothingQuality = 'high'
  ctx.drawImage(
    img,
    crop.x * img.naturalWidth, crop.y * img.naturalHeight, crop.width * img.naturalWidth, crop.height * img.naturalHeight,
    0, 0, side, side,
  )
  const blob = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, 'image/png'))
  if (!blob) throw new Error('bake_failed')
  return blob
}

/**
 * Bake the framed crop and re-encode to a WebP ≤ the icon pad budget, stepping quality down until it fits. WebP
 * preserves alpha, so a transparent logo does not flatten onto a black canvas (the deriver's fill would force a
 * background if we passed one — here we keep alpha). Throws only if even the lowest quality overflows the budget,
 * which cannot happen for a 256×256 WebP (far under 16 KiB) — the throw is a guard, not an expected path.
 */
export async function renderSpaceIcon(
  deriver: MediaDeriver, img: HTMLImageElement, crop: CropRect,
  /** The pixel edge and the encoded-byte budget: the space icon's by default; the member avatar (ADR 0084) passes its own. */
  target: { edge: number; budget: number } = { edge: ICON_EDGE, budget: ICON_ENCODED_BUDGET },
): Promise<Uint8Array> {
  const square = await bakeSquareCrop(img, crop)
  for (const quality of QUALITY_STEPS) {
    const { bytes } = await deriver.deriveImage(square, { maxEdge: target.edge, quality, mimeType: 'image/webp' })
    if (bytes.length <= target.budget) return bytes
  }
  throw new Error('icon_encode_too_large')
}
