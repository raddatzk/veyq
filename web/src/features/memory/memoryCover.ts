// en.memory.node-meta (web) — a Memory's curated **cover**, the `cover` sub-object of the sealed node-meta blob
// (uc.memory.cover, cr-0072 + cr-0073). One deliberately chosen image plus a crop, from one of two **sources**:
// an existing Memory media (referenced by `mediaId`), or a **brand-new uploaded image** committed as a standalone
// cover blob with no gallery row (`{ kind: 'upload', ref }`, cr-0073). Either source is framed by a **normalized
// `0..1` crop** and rendered by the same display formula. Sealed on the NODE under its `K_mem` beside the
// `date`/`location` (operator-blind, ADR 0012) — the server only ever holds ciphertext, never which image, the
// source, or the crop.
//
// The seal is **read-modify-write** (shared `nodeMeta.ts`): `setMemoryCover` unseals the current
// `{ v, date?, location?, cover? }`, swaps in (or drops) only the `cover`, and re-seals — so it never clobbers a
// sibling `date`/`location`. `null` removes the cover while preserving them.
//
// A cover is **never auto-derived** — it is a deliberate per-Memory "set cover" choice; unset ⇒ the placeholder.
// The `crop` is normalized `0..1` so **one stored crop renders identically at any size** — the browse card, the
// detail header, the map pin and the sidebar's Favorites tile — via the pure `coverImageStyle` display formula.
// A comment that lists three of four surfaces is the next reader's wrong map, which is why this one counts.
// This module owns the model +
// seal + display math + the on-device cover-thumbnail load (`loadNodeCover`); the media picker and the 16:9
// pan/zoom crop editor live in the UI layer (cr-0072 phase 3).
import { hasDerivatives, listMedia, openBlob, openGrid, type BlobRef } from './media'
import { editField, unsealNodeMeta } from './nodeMeta'
import { writeNodeMeta } from './nodeMetaWrite'
import type { SealedMetaState } from '../../core/node/sealedMetaWrite'

/**
 * A crop rectangle in the source image's **normalized `0..1`** coordinates (the pre-E2EE design source's
 * `CoverCrop`): `x,y` is the top-left corner, `width,height` the extent, all fractions of the source dimensions.
 * A crop of `{ x: 0, y: 0, width: 1, height: 1 }` is the full frame. Normalizing to `0..1` means one stored crop
 * renders identically at any container size, with no per-surface crop and no cropped-image upload.
 */
export type CoverCrop = { x: number; y: number; width: number; height: number }

/**
 * A Memory cover's **image source** (cr-0073 widens cr-0072's bare `mediaId`): either one of the Memory's own
 * media (referenced by `mediaId`), or a **standalone uploaded cover blob** (`ref`) — a blob attached to the node
 * with no `veyq_media` row (never in the gallery). The crop + display formula are identical for both.
 */
export type MemoryCoverSource =
  | { kind: 'media'; mediaId: string }
  | { kind: 'upload'; ref: BlobRef }

/** A Memory's chosen cover: an image `source` framed by a normalized `crop`. */
export type MemoryCover = { source: MemoryCoverSource; crop: CoverCrop }

/**
 * What the (crypto/network-free) cover dialog emits on save — the parent turns it into a sealed [MemoryCover]:
 * - `media` — an existing Memory media was chosen (re-seal only, no blob work);
 * - `upload` — a brand-new image file (the parent uploads + commits it as a standalone cover blob, cr-0073);
 * - `keep` — the current source is unchanged, only the crop moved (re-seal the stored source, no re-upload);
 * - `remove` — drop the cover.
 */
export type CoverSaveIntent =
  | { kind: 'media'; mediaId: string; crop: CoverCrop }
  | { kind: 'upload'; file: File; crop: CoverCrop }
  | { kind: 'keep'; crop: CoverCrop }
  | { kind: 'remove' }

/** The full-frame crop — the fallback when a crop is absent or malformed (never a throw, E4). */
export const FULL_FRAME: CoverCrop = { x: 0, y: 0, width: 1, height: 1 }

const clamp = (v: number, min: number, max: number): number => Math.min(max, Math.max(min, v))
const finiteNum = (v: unknown): number | undefined => (typeof v === 'number' && Number.isFinite(v) ? v : undefined)

/**
 * Normalize a loosely-typed crop payload to a valid [CoverCrop] kept **on-image**: `width`/`height` are clamped to
 * `(0..1]` (a non-positive/absent extent falls back to the full frame), then `x`/`y` are clamped so the rectangle
 * never runs off the image (`x ∈ [0, 1-width]`). Best-effort (E4) — a malformed crop yields the full frame, never
 * a throw.
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

/**
 * Normalize a loosely-typed `cover` payload to a valid [MemoryCover], or **null** for the "no cover" state.
 * **Backward-compatible** (cr-0073): a cr-0072 seal (`{ mediaId, crop }`, no `source`) opens as a `media`
 * source; the widened `{ source: { kind }, crop }` opens on its discriminant. A valid source is mandatory (a
 * cover always points at an image); the crop is normalized on-image (defaulting to the full frame). Best-effort
 * (E4): any other shape ⇒ null ⇒ the placeholder.
 */
export function normalizeMemoryCover(raw: unknown, nodeId: string): MemoryCover | null {
  if (!raw || typeof raw !== 'object') return null
  const c = raw as { source?: unknown; mediaId?: unknown; crop?: unknown }
  const source = normalizeCoverSource(c, nodeId)
  if (!source) return null
  return { source, crop: normalizeCoverCrop(c.crop) }
}

/** Resolve a cover's source from either the widened `source` discriminant or the legacy flat `mediaId`. */
function normalizeCoverSource(c: { source?: unknown; mediaId?: unknown }, nodeId: string): MemoryCoverSource | null {
  if (c.source && typeof c.source === 'object') {
    const s = c.source as { kind?: unknown; mediaId?: unknown; ref?: unknown }
    if (s.kind === 'media' && typeof s.mediaId === 'string' && s.mediaId) return { kind: 'media', mediaId: s.mediaId }
    if (s.kind === 'upload') {
      const ref = normalizeBlobRef(s.ref, nodeId)
      return ref ? { kind: 'upload', ref } : null
    }
    return null
  }
  // cr-0072 back-compat: a flat `{ mediaId, crop }` seal predates the `source` discriminant.
  if (typeof c.mediaId === 'string' && c.mediaId) return { kind: 'media', mediaId: c.mediaId }
  return null
}

/** Normalize a loosely-typed cover-blob ref (the opaque read-back shape) — null when any required field is off. The
 *  blob's owner is the node whose meta names it, in the COVER role: taken from the node the caller addressed, never
 *  from the sealed payload (ADR 0072), so a cover ref copied into another node's meta refuses to open there. */
function normalizeBlobRef(raw: unknown, nodeId: string): BlobRef | null {
  if (!raw || typeof raw !== 'object') return null
  const r = raw as { blobId?: unknown; chunkCount?: unknown; byteSize?: unknown; wrappedBlobKey?: unknown }
  const chunkCount = finiteNum(r.chunkCount)
  const byteSize = finiteNum(r.byteSize)
  if (typeof r.blobId !== 'string' || !r.blobId) return null
  if (typeof r.wrappedBlobKey !== 'string' || !r.wrappedBlobKey) return null
  if (chunkCount === undefined || byteSize === undefined) return null
  return { blobId: r.blobId, chunkCount, byteSize, wrappedBlobKey: r.wrappedBlobKey, ownerId: nodeId, role: 'COVER' }
}

/**
 * Unseal a Memory's cover from a node's `wrappedMeta` under `nodeKey` (`K_mem`). Best-effort (E3/E4): null when
 * the node carries no meta/cover, the wrap doesn't open, or the payload is malformed — every surface then shows
 * the placeholder rather than break.
 */
export function unsealMemoryCover(
  scopeId: string, nodeKey: Uint8Array, node: { id: string; wrappedMeta: string | null },
): MemoryCover | null {
  return normalizeMemoryCover(unsealNodeMeta(nodeKey, { scopeId, nodeId: node.id }, node.wrappedMeta).cover, node.id)
}

/**
 * The cover EDITOR's save: **read-modify-write** the node-meta blob — set (or, for `null`, **remove**) only the
 * `cover`, re-seal, PATCH with the `metaVersion` it was built from (cr-0072, ADR 0077). **Preserves the sibling
 * `date`/`location`** in every case, including ones a peer wrote meanwhile. A peer's change to the COVER itself
 * throws `MetaConflictError` — unless `surfaceConflict` is false: once the save has moved the node's one cover
 * blob (an upload, or a switch away from an uploaded cover), its own cover is re-applied instead, because the
 * peer's cover may point at the blob it just replaced. Returns the state the server confirmed, and the cover the
 * landed write `replaced` — read from the blob the server accepted the write over (`current`, or the 409's current
 * blob on a re-run), so the caller decides what to reclaim from the server's state, not from its own copy (#284).
 */
export async function setMemoryCover(
  scopeId: string,
  nodeId: string,
  nodeKey: Uint8Array,
  cover: MemoryCover | null,
  current: SealedMetaState,
  surfaceConflict = true,
): Promise<SealedMetaState & { replaced: MemoryCover | null }> {
  const openedWith = unsealNodeMeta(nodeKey, { scopeId, nodeId }, current.wrappedMeta).cover
  const edit = editField('cover', cover, openedWith, surfaceConflict)
  let replaced: MemoryCover | null = null
  const { state } = await writeNodeMeta(scopeId, nodeId, nodeKey, current, (meta, ctx) => {
    replaced = normalizeMemoryCover(meta.cover, nodeId) // before the edit drops or swaps it in place
    return edit(meta, ctx)
  })
  return { ...state, replaced }
}

/** Whether two covers are equal (same source + all four crop fields) — the editor re-seals only when this is false. */
export function sameMemoryCover(a: MemoryCover | null, b: MemoryCover | null): boolean {
  if (a === b) return true
  if (!a || !b) return false
  return sameCoverSource(a.source, b.source) && sameCrop(a.crop, b.crop)
}

/** Two sources are equal when they name the same media, or the same committed cover blob. */
function sameCoverSource(a: MemoryCoverSource, b: MemoryCoverSource): boolean {
  if (a.kind === 'media' && b.kind === 'media') return a.mediaId === b.mediaId
  if (a.kind === 'upload' && b.kind === 'upload') return a.ref.blobId === b.ref.blobId
  return false
}

const sameCrop = (a: CoverCrop, b: CoverCrop): boolean =>
  a.x === b.x && a.y === b.y && a.width === b.width && a.height === b.height

/** A resolved cover ready to display: a decrypted thumbnail object URL for the chosen media + its crop. */
export type LoadedCover = { url: string; crop: CoverCrop }

/** A node's decrypted cover image as **bytes** (not yet an object URL) + its crop. The caller mints the object
 *  URL via `useObjectUrls`, which owns its lifecycle — so no URL is ever handed off by contract from here. */
export type DecryptedCover = { blob: Blob; crop: CoverCrop }

/**
 * Resolve a node's chosen cover for every surface that shows one — the browse card, the detail header, the map
 * pin, the sidebar's Favorites tile. Callers go through `coverStore`, which is the ONLY caller of this: unseal
 * `cover` under `nodeKey`
 * (`K_mem`), then decrypt its image on device — a **media** source opens the chosen media's THUMBNAIL tier
 * (`listMedia` → `openGrid`), an **upload** source opens the standalone cover blob directly (`openBlob`, cr-0073)
 * — the same operator-blind path (ciphertext + K_blob wraps, no plaintext crosses). Returns the decrypted image
 * **bytes** + crop, or **null** when the node has no cover, the chosen media is gone (E2 — a dangling `mediaId`
 * ⇒ placeholder), or any decrypt fails. The caller mints + owns the object URL (via `useObjectUrls`).
 */
export async function loadNodeCover(
  scopeId: string,
  nodeKey: Uint8Array,
  node: { id: string; wrappedMeta: string | null },
): Promise<DecryptedCover | null> {
  const cover = unsealMemoryCover(scopeId, nodeKey, node)
  if (!cover) return null
  try {
    const bytes =
      cover.source.kind === 'upload'
        ? await openBlob(scopeId, nodeKey, cover.source.ref)
        : await openMediaGrid(scopeId, nodeKey, node.id, cover.source.mediaId)
    if (!bytes) return null // E2: the chosen media was deleted ⇒ fall back to the placeholder, never a broken tile
    return { blob: new Blob([bytes as BlobPart]), crop: cover.crop }
  } catch {
    return null // best-effort: locked blob, network hiccup, malformed tier ⇒ placeholder
  }
}

/** Decrypt a media cover's THUMBNAIL tier, or null when the chosen media (E2) is gone / has no derivable frame. */
async function openMediaGrid(scopeId: string, nodeKey: Uint8Array, nodeId: string, mediaId: string): Promise<Uint8Array | null> {
  const media = await listMedia(scopeId, nodeId)
  const pick = media.find((m) => m.mediaId === mediaId && hasDerivatives(m))
  return pick ? openGrid(scopeId, nodeKey, pick) : null
}

/** The `<img>` styles produced by [coverImageStyle] — mirrors the design source's inline style object. */
export type CoverImageStyle = {
  width: string
  height: string
  left: string
  top: string
  objectFit: 'cover' | 'fill'
}

/**
 * The pure display formula (ported from the design source's `MemoryCoverImage`): given a normalized crop, return
 * the `<img>` styles that show exactly that crop inside an `overflow:hidden` box **at any container size** — the
 * image is scaled to `1/width × 1/height` of the box and offset so the crop's top-left aligns with the box.
 * `objectFit: 'fill'` preserves the exact crop (the box is a matching 16:9). No crop — or a **full-frame** crop
 * (`{0,0,1,1}`, e.g. a cover set from a photo with one click, no manual framing) ⇒ a plain `object-fit: cover`
 * center-crop, so a non-16:9 image is **never distorted**. The same helper drives the card, the rail tile, the
 * detail header,
 * and the map pin, so a crop looks identical everywhere.
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
