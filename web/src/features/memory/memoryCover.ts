// en.memory.node-meta (web) — a Memory's curated **cover**, the `cover` sub-object of the sealed node-meta blob
// (uc.memory.cover). One deliberately chosen image plus a crop: an image uploaded in core's cover dialog, committed as the
// node's own cover blob with no gallery row (`{ kind: 'upload', ref }`), framed by a **normalized `0..1` crop** and
// rendered by the same display formula. Sealed on the NODE under its `K_node` beside the `date`/`location`
// (operator-blind, ADR 0012) — the server only ever holds ciphertext, never the image or the crop. Where the picked
// image equals a gallery medium's original, the cover blob is a row on that medium's PREVIEW object (ADR 0013 § 5,
// `storedMatch.ts`) - to every reader it is an upload like any other.
//
// **Core's since inbox #411.** The crop model, the display formula, the blob ref, the upload and the write
// (`setNodeCover` / `removeNodeCover`, blob and meta in one request) are `core/node/cover/`, the same mechanism a folder's
// and a drop box's cover use. A cover is set only by an upload in that dialog: a stored `{ kind: 'media', mediaId }` (an
// older client's photo reference) reads as no cover - the placeholder - and is preserved by every other meta writer.
//
// The seal is **read-modify-write** (core's `nodeMeta.ts`): the write unseals the current `{ v, date?, location?, cover? }`,
// swaps in (or drops) only the `cover`, and re-seals — so it never clobbers a sibling `date`/`location`. `null` removes the
// cover while preserving them.
//
// A cover is **never auto-derived** — it is a deliberate per-Memory "set cover" choice; unset ⇒ the placeholder.
// The `crop` is normalized `0..1` so **one stored crop renders identically at any size** — the browse card, the
// detail header, the map pin and the sidebar's Favorites tile — via the pure `coverImageStyle` display formula.
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

/** A Memory's chosen cover: its uploaded image (`ref`, the node's `COVER` blob) framed by a normalized `crop`. */
export type MemoryCover = { source: { kind: 'upload'; ref: BlobRef }; crop: CoverCrop }

/**
 * Normalize a loosely-typed `cover` payload to a valid [MemoryCover], or **null** for the "no cover" state: an uploaded
 * source is mandatory (a cover always points at its image); the crop is normalized on-image (defaulting to the full frame).
 * Best-effort (E4): any other shape - a stored photo reference included - ⇒ null ⇒ the placeholder.
 */
export function normalizeMemoryCover(raw: unknown, nodeId: string): MemoryCover | null {
  if (!raw || typeof raw !== 'object') return null
  const c = raw as { source?: unknown; crop?: unknown }
  const s = c.source as { kind?: unknown; ref?: unknown } | null | undefined
  if (!s || typeof s !== 'object' || s.kind !== 'upload') return null
  const ref = normalizeCoverBlobRef(s.ref, nodeId)
  return ref ? { source: { kind: 'upload', ref }, crop: normalizeCoverCrop(c.crop) } : null
}

/**
 * Unseal a Memory's cover from a node's `wrappedMeta` under `nodeKey` (`K_node`). Best-effort (E3/E4): null when
 * the node carries no meta/cover, the wrap doesn't open, or the payload is malformed — every surface then shows
 * the placeholder rather than break.
 */
export function unsealMemoryCover(
  scopeId: string, nodeKey: Uint8Array, node: { id: string; wrappedMeta: string | null },
): MemoryCover | null {
  return normalizeMemoryCover(unsealNodeMeta(nodeKey, { scopeId, nodeId: node.id }, node.wrappedMeta).cover, node.id)
}
