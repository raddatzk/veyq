// en.memory.node-meta (web) — a Memory's curated **cover**, the `cover` sub-object of the sealed node-meta blob
// (uc.memory.cover, cr-0072 + cr-0073). One deliberately chosen image plus a crop, from one of two **sources**:
// an existing Memory media (referenced by `mediaId`), or a **brand-new uploaded image** committed as a standalone
// cover blob with no gallery row (`{ kind: 'upload', ref }`, cr-0073). Either source is framed by a **normalized
// `0..1` crop** and rendered by the same display formula. Sealed on the NODE under its `K_mem` beside the
// `date`/`location` (operator-blind, ADR 0012) — the server only ever holds ciphertext, never which image, the
// source, or the crop.
//
// **Core's since inbox #411.** A cover is no longer a Memory-only thing: the crop model, the display formula, the blob ref,
// the upload and the write (`setNodeCover` / `removeNodeCover`, blob and meta in one request) are `core/node/cover/`, the
// same mechanism a folder's and a drop box's cover use. What stays here is what only Memories has: its SECOND SOURCE, one of
// its own photos (`{ kind: 'media', mediaId }`), and the reading of it (`coverLoad.ts`, registered as the kinds'
// `cover.load`). The stored shape is unchanged, byte for byte.
//
// The seal is **read-modify-write** (core's `nodeMeta.ts`): the write unseals the current `{ v, date?, location?, cover? }`,
// swaps in (or drops) only the `cover`, and re-seals — so it never clobbers a sibling `date`/`location`. `null` removes the
// cover while preserving them.
//
// A cover is **never auto-derived** — it is a deliberate per-Memory "set cover" choice; unset ⇒ the placeholder.
// The `crop` is normalized `0..1` so **one stored crop renders identically at any size** — the browse card, the
// detail header, the map pin and the sidebar's Favorites tile — via the pure `coverImageStyle` display formula.
import type { AddMediaSource, MediaDeriver } from '../../shared/image/mediaDeriver'
import {
  FULL_FRAME,
  coverImageStyle,
  normalizeCoverBlobRef,
  normalizeCoverCrop,
  sameCrop,
  uploadedCover,
  type CoverCrop,
  type CoverImageStyle,
  type DecryptedCover,
  type LoadedCover,
} from '../../core/node/cover/coverModel'
import { uploadCoverImage, writeNodeCover } from '../../core/node/cover/coverWrite'
import { unsealNodeMeta } from '../../core/node/nodeMeta'
import type { SealedMetaState } from '../../core/node/sealedMetaWrite'
import type { BlobRef } from './media'

export { FULL_FRAME, coverImageStyle, normalizeCoverCrop }
export type { CoverCrop, CoverImageStyle, DecryptedCover, LoadedCover }

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
      const ref = normalizeCoverBlobRef(s.ref, nodeId)
      return ref ? { kind: 'upload', ref } : null
    }
    return null
  }
  // cr-0072 back-compat: a flat `{ mediaId, crop }` seal predates the `source` discriminant.
  if (typeof c.mediaId === 'string' && c.mediaId) return { kind: 'media', mediaId: c.mediaId }
  return null
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
 * The cover EDITOR's meta save: **read-modify-write** the node-meta blob — set (or, for `null`, **remove**) only the
 * `cover`, re-seal, send with the `metaVersion` it was built from (cr-0072, ADR 0077). **Preserves the sibling
 * `date`/`location`** in every case, including ones a peer wrote meanwhile. A peer's change to the COVER itself
 * throws `MetaConflictError` — unless `surfaceConflict` is false. Returns the state the server confirmed, and the cover the
 * landed write `replaced` — read from the blob the server accepted the write over (`current`, or the 409's current
 * blob on a re-run), so the caller decides from the server's state, not from its own copy (#284).
 *
 * Core's `writeNodeCover` does the blob step in the same request when the replaced cover was an UPLOAD the new one does not
 * keep (a switch to a photo, or no cover): the old blob is freed with the meta write, atomically (inbox #411) — there is no
 * second call to forget.
 */
export async function setMemoryCover(
  scopeId: string,
  nodeId: string,
  nodeKey: Uint8Array,
  cover: MemoryCover | null,
  current: SealedMetaState,
  surfaceConflict = true,
): Promise<SealedMetaState & { replaced: MemoryCover | null }> {
  const { replaced, ...state } = await writeNodeCover({ scopeId, nodeId, nodeKey, current, cover, surfaceConflict })
  return { ...state, replaced: normalizeMemoryCover(replaced, nodeId) }
}

/**
 * Upload a brand-new image as the Memory's **standalone cover blob** and make it the cover, framed by `crop`
 * (cr-0073, uc.memory.cover second source): downscale on device to one cover-sized JPEG, seal it under a fresh `K_blob`
 * (wrapped under the node's key), upload the chunks direct-to-store and attach it with the meta that names it in one
 * request (core, inbox #411). The replaced uploaded cover, if any, is freed in the same write. An undecodable source seals
 * its pristine bytes rather than fail (uc.memory.upload E4). `surfaceConflict` is always false here: this write moves the
 * node's one cover blob, so it re-applies its own cover over a peer's change.
 */
export async function setMemoryCoverUpload(
  scopeId: string,
  nodeId: string,
  nodeKey: Uint8Array,
  source: AddMediaSource,
  crop: CoverCrop,
  current: SealedMetaState,
  deriver?: MediaDeriver,
): Promise<SealedMetaState & { replaced: MemoryCover | null; cover: MemoryCover }> {
  const up = await uploadCoverImage(scopeId, nodeId, nodeKey, source, deriver)
  const cover = uploadedCover(up.plan, crop) as MemoryCover
  const { replaced, ...state } = await writeNodeCover({
    scopeId, nodeId, nodeKey, current, cover, uploadId: up.uploadId, surfaceConflict: false,
  })
  return { ...state, replaced: normalizeMemoryCover(replaced, nodeId), cover }
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
