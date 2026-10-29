// A file's SHA-256 as `blob-meta`'s `sha256` carries it (ADR 0013 § 5), and the ops of core's digest worker (inbox #554).
// Both threads import this module and nothing else of the digest: the worker (`digest.worker.ts`) runs [checksumsHere] on
// the `Blob` it is handed, and the front door (`digestWorker.ts`) runs the same loop on the page where the worker cannot.
// The worker never imports the front door, which would pull its `new Worker(…)` into the worker chunk (`frameOps.ts`
// names the trap). So the digest has one implementation, `@noble`'s through `v1.sha256Stream` (ADR 0017 decision 1),
// whichever thread runs it; `crypto.subtle.digest` has no incremental form and would need the whole file in memory.
// The same pass also sums a ZIP entry's CRC-32 when asked (an import's payload check, inbox #557), by core's one CRC-32
// (`export/crc32.ts`), so the check reads each payload once, and off the page.
import * as v1 from '@veyq/crypto/v1'
import { b64url } from '@veyq/crypto/base64'
import { crc32Finish, crc32Start, crc32Update } from '../export/crc32'
import type { BlobSource } from './types'

/** Plaintext bytes per read (the contract's frame size): the unit a digest reads a source in, never the whole. */
export const DIGEST_SLICE = v1.CHUNK_FRAME_SIZE.plaintextBytes

/** What one pass over a source answers: its SHA-256 (base64url, no padding) and, when it was asked for, its CRC-32. */
export type Checksums = { digest: string; crc32?: number }

/**
 * The SHA-256 of a source's plaintext (base64url, no padding) and, with `withCrc32`, its CRC-32 too, read slice by slice
 * on the thread that calls it, once for both. Production code reaches it through `sharedBlob.ts` (`contentDigest`,
 * `payloadChecksums`), which sends a `Blob` to the digest worker; a direct import runs the hash on the page thread, so it
 * is a deliberate choice, never a habit.
 */
export async function checksumsHere(source: BlobSource, withCrc32: boolean, signal?: AbortSignal): Promise<Checksums> {
  const hasher = v1.sha256Stream()
  let crc = crc32Start()
  for (let at = 0; at < source.size; at += DIGEST_SLICE) {
    if (signal?.aborted) throw new DOMException('Upload cancelled', 'AbortError')
    const bytes = new Uint8Array(await source.slice(at, Math.min(source.size, at + DIGEST_SLICE)).arrayBuffer())
    hasher.update(bytes)
    if (withCrc32) crc = crc32Update(crc, bytes)
  }
  const digest = b64url(hasher.digest())
  return withCrc32 ? { digest, crc32: crc32Finish(crc) } : { digest }
}

/** The SHA-256 of a source's plaintext as `blob-meta`'s `sha256` carries it: {@link checksumsHere} without the CRC. */
export async function digestHere(source: BlobSource, signal?: AbortSignal): Promise<string> {
  return (await checksumsHere(source, false, signal)).digest
}

/** The ops the front door sends and the worker answers, named once, here. `digest.worker.test.ts` sends each. */
export const DIGEST_OPS = ['digest', 'cancel'] as const

export type DigestOp = (typeof DIGEST_OPS)[number]

/**
 * - `digest`: the SHA-256 of `blob` (and its CRC-32 with `crc32: true`), which crosses by structured clone as a handle to
 *   the same bytes, never copied and never transferred, so the page still holds it after any post. Answered once, with
 *   its `id`.
 * - `cancel`: stop the digest `id` at its next slice. Not answered itself; the digest it stops answers `AbortError`.
 */
export type DigestRequest = { id: number; op: 'digest'; blob: Blob; crc32?: true } | { id: number; op: 'cancel' }

/** One answer: the digest (with `crc32` only when it was asked for), or `source`, the read failed on the file itself (a
 *  file changed on disk is a `NotReadableError`), so it would fail the same way on the page thread. `name` is the error's
 *  own name. */
export type DigestResponse =
  | { id: number; ok: true; digest: string; crc32?: number }
  | { id: number; ok: false; kind: 'source'; name: string; message: string }
