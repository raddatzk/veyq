// When the lightbox's zoom fetches the ORIGINAL (cr-lightbox-zoom-slideshow, uc.memory.gallery 3a): the stage shows the
// PREVIEW tier (2048 px edge, `TIER_SPECS.PREVIEW`), and zooming past what the preview's pixels can carry swaps in the
// original - fetched as ciphertext and opened on this device, as a download is. Four gates, each one a bite:
//
// - **the displayed resolution exceeds the preview's**: the fitted width times the zoom, in DEVICE pixels, is more
//   pixels than the preview has - before that the original adds nothing a screen can show;
// - **a type `<img>` decodes**: the original's SEALED media type is a raster format every current browser draws (HEIC is
//   left out: only Safari decodes it, so everywhere else it would be a large fetch that ends on the preview anyway);
// - **the preview was scaled down**: a preview whose longer edge is under the preview tier's own edge
//   (`TIER_SPECS.PREVIEW.maxEdge`) was derived at the original's size, so the original has no more pixels to show;
// - **under the cap**: the whole original is held in memory while it is shown, so it is fetched only below
//   [ZOOM_ORIGINAL_MAX_BYTES] of ciphertext.
//
// The original is asked for once per shown item; a failure (fetch, decrypt or decode) keeps the preview, silently.

import { TIER_SPECS } from '../../shared/image/mediaDeriver'

/** The largest original (ciphertext bytes, the listed `byteSize`) the zoom fetches whole. */
export const ZOOM_ORIGINAL_MAX_BYTES = 50 * 1024 * 1024

/** The sealed types an `<img>` decodes in every browser the client supports. */
export const ZOOM_DECODABLE_TYPES: ReadonlySet<string> = new Set([
  'image/jpeg', 'image/png', 'image/gif', 'image/webp', 'image/avif', 'image/bmp',
])

/** Whether an original of this sealed `type` and ciphertext `byteSize` may be fetched for the zoom. */
export function originalZoomable(type: string | undefined, byteSize: number): boolean {
  return !!type && ZOOM_DECODABLE_TYPES.has(type.toLowerCase()) && byteSize > 0 && byteSize < ZOOM_ORIGINAL_MAX_BYTES
}

/** Whether the preview, drawn `fittedWidth` CSS px wide at `scale` on a `dpr` screen, shows more pixels than it has. */
export function exceedsPreview(fittedWidth: number, scale: number, dpr: number, naturalWidth: number): boolean {
  return naturalWidth > 0 && fittedWidth > 0 && fittedWidth * scale * (dpr > 0 ? dpr : 1) > naturalWidth
}

/** Whether a preview of this natural size was scaled down from its original - else the original has no more pixels. */
export function previewDownscaled(naturalWidth: number, naturalHeight: number): boolean {
  return Math.max(naturalWidth, naturalHeight) >= TIER_SPECS.PREVIEW.maxEdge
}
