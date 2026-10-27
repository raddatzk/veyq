// The blob's pure crypto (ADR 0072, inbox #489): the `K_blob` wrap's binding, its unwrap, a listed blob's sealed
// metadata and the frames' seal and open — the part of `transport.ts` that touches no network, no API client and no page
// state, so a Web Worker can import it (core's frame worker, `frames.worker.ts`; Memories' listing opener,
// `features/memories/listingOpener.worker.ts`) without pulling the app in. `transport.ts` re-exports what its callers
// always imported from it. The frame functions below are the only production code that calls `v1.sealFrame` /
// `v1.openFrame` (tests and `media.bench.ts` aside): core's frame worker runs them, the page thread runs the same ones
// only while that worker cannot (`frameWorker.ts`), and Memories' listing worker runs `openFrames` for a grid's
// thumbnails (ADR 0105 decision 3) — so the frames have one implementation wherever they run (ADR 0017 decision 1).
import * as v1 from '@veyq/crypto/v1'
import { fromB64std } from '@veyq/crypto/base64'
import type { BlobRef, BlobRole } from './types'
import { openBlobMetaOf, type OpenedBlobMeta } from './meta'

/**
 * Every `K_blob` wrap is bound to the object it is stored as (ADR 0072): (scope, owner, blob, role). A server that
 * attaches a blob under another owner, or serves one role's blob in another's slot, gets a wrap that does not open
 * there.
 */
export const blobKeyContext = (scopeId: string, ref: { blobId: string; ownerId: string; role: BlobRole }) =>
  ({ purpose: 'blob-key', scopeId, ownerId: ref.ownerId, blobId: ref.blobId, role: ref.role }) as const

/** What an unwrap reads of a ref: its binding and its wrap. */
export type WrappedBlobKey = Pick<BlobRef, 'blobId' | 'ownerId' | 'role' | 'wrappedBlobKey'>

/** Unwrap a ref's `K_blob` under `key`, bound to the ref's (scope, owner, blob, role): `OpenRefused` for a ref the
 *  server moved to another owner or slot. Exported for a report, which hands a medium's `K_blob`s over
 *  (uc.moderation.report). */
export const unwrapBlobKey = (scopeId: string, key: Uint8Array, ref: WrappedBlobKey): Uint8Array =>
  v1.open(blobKeyContext(scopeId, ref), key, fromB64std(ref.wrappedBlobKey))

/**
 * A listed blob's sealed metadata, **for display** (inbox #397): its `K_blob` unwrapped under `key`, then its
 * `wrappedBlobMeta` opened (`meta.ts` `openBlobMetaOf`). Null when the ref carries none, or when either the wrap or the
 * meta does not open — a ref moved to another owner, another blob's meta in its place. Never throws. For a blob about
 * to be read, the chunk provider's `meta()` reuses the key it already unwrapped.
 */
export function readBlobMeta(scopeId: string, key: Uint8Array, ref: WrappedBlobKey & Pick<BlobRef, 'wrappedBlobMeta'>): OpenedBlobMeta | null {
  if (!ref.wrappedBlobMeta) return null
  let blobKey: Uint8Array
  try {
    blobKey = unwrapBlobKey(scopeId, key, ref)
  } catch {
    return null
  }
  return openBlobMetaOf(ref, blobKey)
}

/** A whole blob's ciphertext frames, in chunk order, opened under its `K_blob` and joined: `openFrame` binds
 *  `index‖total`, so a frame served in another's place, or a blob served short, fails to open. */
export function openFrames(blobKey: Uint8Array, frames: readonly Uint8Array[]): Uint8Array {
  return openFrameRun(blobKey, frames.length, 0, frames)
}

/** A run of a blob's frames, `frames[k]` being frame `from + k`, each opened at its own index against the WHOLE blob's
 *  `total` and joined. The AAD binds `index‖total` and never a run's length, so a run opened against its own length
 *  fails every frame (the trap `makeChunkProvider` names). */
export function openFrameRun(blobKey: Uint8Array, total: number, from: number, frames: readonly Uint8Array[]): Uint8Array {
  return v1.concat(...frames.map((frame, k) => v1.openFrame(blobKey, from + k, total, frame)))
}

/** Seal frame `index` of a blob of `total` frames under its `K_blob`, with a fresh random nonce. Outside the typed seal
 *  union on purpose (ADR 0072 Decision 2): the frame key is this blob's own `K_blob`, so the key is the object binding;
 *  the AAD binds only the frame's position (index ‖ total). */
export function sealFrameOf(blobKey: Uint8Array, index: number, total: number, plaintext: Uint8Array): Uint8Array {
  return v1.sealFrame(blobKey, index, total, plaintext, v1.randomBytes(12))
}
