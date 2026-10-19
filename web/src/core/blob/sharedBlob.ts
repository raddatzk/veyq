// A blob that shares a stored object instead of being uploaded again (ADR 0013 § 5): the client finds the file it is
// about to upload already stored for the node - same sealed size, same SHA-256 of the plaintext, read from the stored
// blob's own `blob-meta` - and asks the server for a new blob ROW on that object (the contract's `SharedBlob`). The row
// has its own client-minted id, the same `K_blob` wrapped again under the new owner's key bound to the new row's (scope,
// owner, blob, role) (ADR 0072, no new seal purpose) and its own `blob-meta`; the server copies the object's storage key,
// shape and manifest, compares nothing and reclaims the object only when no row names it.
//
// Operator-blind as an upload is: the digest is sealed under the blob's own key, so only a holder of that key - who can
// read the file anyway - can confirm a file by it. What the server learns is which existing blob the new row shares,
// the accepted residual of ADR 0102 § What the server sees.
import * as v1 from '@veyq/crypto/v1'
import { b64std, b64url } from '@veyq/crypto/base64'
import type { BlobMeta } from './meta'
import { blobKeyContext, unwrapBlobKey, type WrappedBlobKey } from './keys'
import type { BlobRef, BlobRole, BlobSource } from './types'

/** Plaintext bytes per frame (the contract's frame size): the unit a digest reads a source in, never the whole. */
const FRAME = v1.CHUNK_FRAME_SIZE.plaintextBytes

/** The sealed size of `plaintextBytes` as a blob - the `byteSize` a stored blob of the same plaintext carries. */
export function sealedByteSize(plaintextBytes: number): number {
  const chunkCount = Math.max(1, Math.ceil(plaintextBytes / FRAME))
  return plaintextBytes + chunkCount * v1.FRAME_OVERHEAD
}

/** The SHA-256 of a source's plaintext as `blob-meta`'s `sha256` carries it (base64url, no padding), read frame by frame. */
export async function contentDigest(source: BlobSource, signal?: AbortSignal): Promise<string> {
  const hasher = v1.sha256Stream()
  for (let at = 0; at < source.size; at += FRAME) {
    if (signal?.aborted) throw new DOMException('Upload cancelled', 'AbortError')
    hasher.update(new Uint8Array(await source.slice(at, Math.min(source.size, at + FRAME)).arrayBuffer()))
  }
  return b64url(hasher.digest())
}

/** The wire's `SharedBlob`: in place of an upload id. */
export type SharedBlobRequest = { sourceBlobId: string; blobId: string; wrappedBlobKey: string; wrappedBlobMeta: string | null }

/** A shared row as the caller needs it: the request to send, and the new row's ref (to name in a sealed meta). */
export type SharedBlob = { request: SharedBlobRequest; ref: BlobRef }

/** A stored blob a new row may share: its ref, and the key its `K_blob` is wrapped under. */
export type StoredBlob = { key: Uint8Array; ref: WrappedBlobKey & Pick<BlobRef, 'chunkCount' | 'byteSize'> }

/**
 * A new row on the stored object of `source`, owned by `owner` in `role` under `key`: the source's `K_blob` unwrapped
 * (bound to its own row) and wrapped again bound to a fresh blob id, owner and role; `meta`, when given, sealed under the
 * same `K_blob` as the new row's `blob-meta`. Throws `OpenRefused` when the source's wrap does not open (a moved ref).
 */
export function shareBlob(
  scopeId: string, source: StoredBlob, key: Uint8Array, owner: { ownerId: string; role: BlobRole }, meta?: BlobMeta,
): SharedBlob {
  const blobKey = unwrapBlobKey(scopeId, source.key, source.ref)
  const blobId = crypto.randomUUID()
  const wrappedBlobKey = b64std(v1.seal(blobKeyContext(scopeId, { blobId, ...owner }), key, blobKey))
  const wrappedBlobMeta = meta ? b64std(v1.sealBlobMeta(blobKey, meta)) : null
  return {
    request: { sourceBlobId: source.ref.blobId, blobId, wrappedBlobKey, wrappedBlobMeta },
    ref: { blobId, chunkCount: source.ref.chunkCount, byteSize: source.ref.byteSize, wrappedBlobKey, ...owner, wrappedBlobMeta },
  }
}

/**
 * The first of `candidates` that holds `file`'s plaintext: a candidate's `byteSize` is compared first, and only when one
 * matches is the file read for its digest (once), then compared with each such candidate's `sha256()` (its opened
 * `blob-meta`'s; null for a blob stored without one, which never matches). Null when none does - the caller uploads.
 */
export async function findEqual<T extends { byteSize: number; sha256(): string | null }>(
  file: BlobSource, candidates: readonly T[], signal?: AbortSignal,
): Promise<T | null> {
  const size = sealedByteSize(file.size)
  const sameSize = candidates.filter((c) => c.byteSize === size)
  if (sameSize.length === 0) return null
  const withDigest = sameSize.map((c) => ({ c, digest: c.sha256() })).filter((x) => x.digest !== null)
  if (withDigest.length === 0) return null
  const digest = await contentDigest(file, signal)
  return withDigest.find((x) => x.digest === digest)?.c ?? null
}
