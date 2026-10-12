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
import type { CoverExtraSource } from '../../core/applications/registry'
import {
  FULL_FRAME,
  coverImageStyle,
  normalizeCoverBlobRef,
  normalizeCoverCrop,
  type CoverCrop,
  type CoverImageStyle,
  type DecryptedCover,
  type LoadedCover,
} from '../../core/node/cover/coverModel'
import { unsealNodeMeta } from '../../core/node/nodeMeta'
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
 * Memories' SECOND cover source, registered as the Memory and Chapter kinds' `cover.extraSource` (`register.ts`): core's cover
 * dialog crops a photo of the Memory the gallery hands it and asks this for the value to store (the shape above, unchanged byte
 * for byte), and for which photo a stored cover already is, so the dialog restores that photo's stored crop and no other's.
 */
export const memoryPhotoSource: CoverExtraSource = {
  hintKey: 'memory.cover.galleryHint',
  seal: (mediaId, crop) => ({ source: { kind: 'media', mediaId }, crop }) satisfies MemoryCover,
  idOf: (cover) => {
    const read = normalizeMemoryCover(cover, '')
    return read?.source.kind === 'media' ? read.source.mediaId : null
  },
}
