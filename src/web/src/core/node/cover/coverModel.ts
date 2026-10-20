// A node's COVER as the web models it (inbox #411): one image attached to the node itself (core's `COVER` attachment slot)
// plus a normalized crop, named by the `cover` field of the node's sealed `wrappedMeta` (`../nodeMeta.ts`, purpose
// `node-meta`, bound to scope and node - ADR 0072). The field is core's since #411 and its shape is the one a Memory's cover
// has always had, so every stored cover opens byte for byte:
//
//   cover = { source: { kind: 'upload', ref: { blobId, chunkCount, byteSize, wrappedBlobKey, ownerId, role } }, crop }
//
// Core understands ONE source, the upload: a cover is set only by an upload in core's dialog. Its blob may be a row on an
// object the node already stores (a Memory's PREVIEW of an equal photo, ADR 0013 § 5) - to a reader it is an upload like
// any other. Any other source (a stored `{ kind: 'media' }` of an older client) is carried untouched by every writer and read
// as no cover - the icon - never a throw.
//
// Pure and side-effect free: the sealing is `nodeMeta.ts`, the network is `coverWrite.ts`, the decrypt `coverStore.ts`.
import type { BlobRef } from '../../blob/types'

/** The slot and the blob role of a node's cover: the wire's `COVER`, core's own token (the upload is started with it). */
export const COVER_ROLE = 'COVER'

/**
 * A crop rectangle in the source image's **normalized `0..1`** coordinates: `x,y` is the top-left corner, `width,height`
 * the extent, all fractions of the source dimensions. `{ 0, 0, 1, 1 }` is the full frame. One stored crop renders
 * identically at any container size.
 */
export type CoverCrop = { x: number; y: number; width: number; height: number }

/** The full-frame crop - the fallback when a crop is absent or malformed (never a throw). */
export const FULL_FRAME: CoverCrop = { x: 0, y: 0, width: 1, height: 1 }

const clamp = (v: number, min: number, max: number): number => Math.min(max, Math.max(min, v))
const finiteNum = (v: unknown): number | undefined => (typeof v === 'number' && Number.isFinite(v) ? v : undefined)

/**
 * Normalize a loosely-typed crop payload to a valid [CoverCrop] kept **on-image**: `width`/`height` are clamped to `(0..1]`
 * (a non-positive/absent extent falls back to the full frame), then `x`/`y` are clamped so the rectangle never runs off the
 * image. Best-effort - a malformed crop yields the full frame.
 */
export function normalizeCoverCrop(raw: unknown): CoverCrop {
  if (!raw || typeof raw !== 'object') return { ...FULL_FRAME }
  const c = raw as { x?: unknown; y?: unknown; width?: unknown; height?: unknown }
  const w = finiteNum(c.width)
  const h = finiteNum(c.height)
  const width = w !== undefined && w > 0 ? clamp(w, 0, 1) : 1
  const height = h !== undefined && h > 0 ? clamp(h, 0, 1) : 1
  const x = clamp(finiteNum(c.x) ?? 0, 0, 1 - width)
  const y = clamp(finiteNum(c.y) ?? 0, 0, 1 - height)
  return { x, y, width, height }
}

/** Whether two crops are equal (all four fields). */
export const sameCrop = (a: CoverCrop, b: CoverCrop): boolean => a.x === b.x && a.y === b.y && a.width === b.width && a.height === b.height

/**
 * Normalize a loosely-typed cover-blob ref (the opaque read-back shape) - null when any required field is off. The blob's
 * owner is the node whose meta names it, in the `COVER` role: taken from the node the caller addressed, never from the
 * sealed payload (ADR 0072), so a cover ref copied into another node's meta refuses to open there.
 */
export function normalizeCoverBlobRef(raw: unknown, nodeId: string): BlobRef | null {
  if (!raw || typeof raw !== 'object') return null
  const r = raw as { blobId?: unknown; chunkCount?: unknown; byteSize?: unknown; wrappedBlobKey?: unknown }
  const chunkCount = finiteNum(r.chunkCount)
  const byteSize = finiteNum(r.byteSize)
  if (typeof r.blobId !== 'string' || !r.blobId) return null
  if (typeof r.wrappedBlobKey !== 'string' || !r.wrappedBlobKey) return null
  if (chunkCount === undefined || byteSize === undefined) return null
  return { blobId: r.blobId, chunkCount, byteSize, wrappedBlobKey: r.wrappedBlobKey, ownerId: nodeId, role: COVER_ROLE }
}

/** What core reads of a node's `cover` field: an upload it can open itself, or a source of an application's (opaque). */
export type ReadCover = { source: { kind: 'upload'; ref: BlobRef } | { kind: 'other' }; crop: CoverCrop }

/** Read a `cover` field value (null: none, or not an object). */
export function readCover(raw: unknown, nodeId: string): ReadCover | null {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return null
  const c = raw as { source?: unknown; crop?: unknown }
  const crop = normalizeCoverCrop(c.crop)
  const s = c.source as { kind?: unknown; ref?: unknown } | null | undefined
  if (s && typeof s === 'object' && s.kind === 'upload') {
    const ref = normalizeCoverBlobRef(s.ref, nodeId)
    return ref ? { source: { kind: 'upload', ref }, crop } : null
  }
  return { source: { kind: 'other' }, crop }
}

/** The id of the cover blob a `cover` field names, or null (no cover, or one whose image is no uploaded blob). The compare
 *  value of `setNodeCover` / `removeNodeCover`: the blob the writer read in the slot. */
export function uploadBlobIdOf(raw: unknown): string | null {
  if (!raw || typeof raw !== 'object') return null
  const s = (raw as { source?: { kind?: unknown; ref?: { blobId?: unknown } } }).source
  return s?.kind === 'upload' && typeof s.ref?.blobId === 'string' && s.ref.blobId ? s.ref.blobId : null
}

/**
 * The `cover` value for an uploaded image: the exact shape (and key order) a Memory's uploaded cover has always been
 * sealed in. `plan` is what the upload was planned with - the blob id the client minted, its shape and K_blob wrap.
 */
export function uploadedCover(
  plan: { blobId: string; chunkCount: number; byteSize: number; wrappedBlobKey: string; ownerId: string },
  crop: CoverCrop = FULL_FRAME,
): { source: { kind: 'upload'; ref: BlobRef }; crop: CoverCrop } {
  const { blobId, chunkCount, byteSize, wrappedBlobKey, ownerId } = plan
  return { source: { kind: 'upload', ref: { blobId, chunkCount, byteSize, wrappedBlobKey, ownerId, role: COVER_ROLE } }, crop }
}

/** The `<img>` styles produced by [coverImageStyle]. */
export type CoverImageStyle = {
  width: string
  height: string
  left: string
  top: string
  objectFit: 'cover' | 'fill'
}

/**
 * The pure display formula: given a normalized crop, the `<img>` styles that show exactly that crop inside an
 * `overflow:hidden` box **at any container size** - the image is scaled to `1/width x 1/height` of the box and offset so the
 * crop's top-left aligns with the box. No crop, or a **full-frame** one, is a plain `object-fit: cover` center-crop, so a
 * non-16:9 image is never distorted. The same helper drives every surface (a row, a tile, a header, the sidebar, a map pin).
 */
export function coverImageStyle(crop: CoverCrop | null | undefined): CoverImageStyle {
  if (!crop || (crop.x === 0 && crop.y === 0 && crop.width === 1 && crop.height === 1)) {
    return { width: '100%', height: '100%', left: '0', top: '0', objectFit: 'cover' }
  }
  return {
    width: `${100 / crop.width}%`,
    height: `${100 / crop.height}%`,
    left: `${(-crop.x / crop.width) * 100}%`,
    top: `${(-crop.y / crop.height) * 100}%`,
    objectFit: 'fill',
  }
}

/** A cover resolved for display: a decrypted object URL and its crop. */
export type LoadedCover = { url: string; crop: CoverCrop }

/** A cover's decrypted image as **bytes** (not yet an object URL) and its crop - the store mints and owns the URL. */
export type DecryptedCover = { blob: Blob; crop: CoverCrop }

/**
 * What core's cover dialog emits on save (inbox #411) - the view seals it:
 * - `upload` - a brand-new image file (uploaded as the node's own cover blob - or a row on an equal stored one, the kind's
 *   `cover.findStored` - then framed by `crop`);
 * - `keep` - the current cover's image is unchanged, only the crop moved;
 * - `remove` - drop the cover.
 */
export type CoverSaveIntent =
  | { kind: 'upload'; file: File; crop: CoverCrop }
  | { kind: 'keep'; crop: CoverCrop }
  | { kind: 'remove' }
