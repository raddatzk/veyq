// The blob read's pure crypto (ADR 0072, inbox #489): the `K_blob` wrap's binding, its unwrap, a listed blob's sealed
// metadata and the frames' open — the part of `transport.ts` that touches no network, no API client and no page state,
// so a Web Worker can import it (Memories' listing opener, `features/memory/listingOpener.worker.ts`) without pulling
// the app in. `transport.ts` re-exports what its callers always imported from it.
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
  return v1.concat(...frames.map((frame, i) => v1.openFrame(blobKey, i, frames.length, frame)))
}
