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
import { b64std, b64url, fromB64url } from '@veyq/crypto/base64'
import type { BlobMeta } from './meta'
import { blobKeyContext, unwrapBlobKey, type WrappedBlobKey } from './keys'
import { digestHere } from './digest'
import { checksumsOff, digestOff } from './digestWorker'
import type { BlobRef, BlobRole, BlobSource } from './types'

/** Plaintext bytes per frame (the contract's frame size). */
const FRAME = v1.CHUNK_FRAME_SIZE.plaintextBytes

/** The sealed size of `plaintextBytes` as a blob - the `byteSize` a stored blob of the same plaintext carries. */
export function sealedByteSize(plaintextBytes: number): number {
  const chunkCount = Math.max(1, Math.ceil(plaintextBytes / FRAME))
  return plaintextBytes + chunkCount * v1.FRAME_OVERHEAD
}

/** The digests core computed itself over the bytes of a `Blob` it handed out (inbox #555), keyed by that very object. */
const remembered = new WeakMap<Blob, string>()

/**
 * Remember `sha256` - the raw 32 bytes core's own `v1.sha256Stream` produced over `file`'s plaintext - as `file`'s digest,
 * so {@link contentDigest} answers that object without reading it again. The caller vouches that the bytes are that
 * object's own and that core's hasher read them: never a claim (a manifest's `sha256`, a stored `blob-meta`), because what
 * is remembered here is sealed into a new blob's `blob-meta`, and a wrong value there silently makes a later cover or banner
 * a row on another stored object (`storedMatch.ts`). So the one caller is core's import check (`importSubtree.ts`
 * `archivePayload`), after its pass ({@link payloadChecksums}) ended and a ZIP entry's CRC-32 and the manifest's `sha256`
 * compared. Keyed by the object:
 * a slice or a re-wrapped copy misses and is read, which fails safe. Throws on anything but 32 bytes.
 */
export function rememberDigest(file: Blob, sha256: Uint8Array): void {
  if (sha256.length !== 32) throw new Error('digest_not_sha256')
  remembered.set(file, b64url(sha256))
}

/**
 * The SHA-256 of a source's plaintext as `blob-meta`'s `sha256` carries it (base64url, no padding), read frame by frame:
 * the one door production code hashes a file through (inbox #554). A `Blob` core remembered the digest of (an imported
 * download's payload, {@link rememberDigest}) is answered from it, without a read; any other `Blob` (a picked `File`, a
 * slice) is hashed in core's digest worker (`digestWorker.ts`), any other source on the page thread, by the same
 * `digest.ts` loop either way. The one other source today is a copy's payload, whose slices open frames through the chunk
 * provider and cannot be structured-cloned (`core/export/copySource.ts`). Rejects with an `AbortError` when `signal`
 * aborts - at once when it already has, remembered or not.
 */
export function contentDigest(source: BlobSource, signal?: AbortSignal): Promise<string> {
  if (!(source instanceof Blob)) return digestHere(source, signal)
  const known = remembered.get(source)
  if (known === undefined) return digestOff(source, signal)
  return signal?.aborted ? Promise.reject(new DOMException('Upload cancelled', 'AbortError')) : Promise.resolve(known)
}

/**
 * An import's payload check over `blob` (inbox #557): the raw 32 bytes of its SHA-256 - what {@link rememberDigest} takes -
 * and, with `withCrc32` (a ZIP entry), the CRC-32 of the same bytes, from one pass in core's digest worker, else on the
 * page thread by the same loop. Rejects with an `AbortError` when `signal` aborts.
 */
export async function payloadChecksums(blob: Blob, withCrc32: boolean, signal?: AbortSignal): Promise<{ sha256: Uint8Array; crc32?: number }> {
  const { digest, crc32 } = await checksumsOff(blob, withCrc32, signal)
  return crc32 === undefined ? { sha256: fromB64url(digest) } : { sha256: fromB64url(digest), crc32 }
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
