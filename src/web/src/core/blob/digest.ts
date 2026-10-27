// A file's SHA-256 as `blob-meta`'s `sha256` carries it (ADR 0013 § 5), and the ops of core's digest worker (inbox #554).
// Both threads import this module and nothing else of the digest: the worker (`digest.worker.ts`) runs [digestHere] on
// the `Blob` it is handed, and the front door (`digestWorker.ts`) runs the same loop on the page where the worker cannot.
// The worker never imports the front door, which would pull its `new Worker(…)` into the worker chunk (`frameOps.ts`
// names the trap). So the digest has one implementation, `@noble`'s through `v1.sha256Stream` (ADR 0017 decision 1),
// whichever thread runs it; `crypto.subtle.digest` has no incremental form and would need the whole file in memory.
import * as v1 from '@veyq/crypto/v1'
import { b64url } from '@veyq/crypto/base64'
import type { BlobSource } from './types'

/** Plaintext bytes per read (the contract's frame size): the unit a digest reads a source in, never the whole. */
export const DIGEST_SLICE = v1.CHUNK_FRAME_SIZE.plaintextBytes

/**
 * The SHA-256 of a source's plaintext as `blob-meta`'s `sha256` carries it (base64url, no padding), read slice by slice
 * on the thread that calls it. Production code reaches it through `sharedBlob.ts` `contentDigest`, which sends a `Blob`
 * to the digest worker; a direct import runs the hash on the page thread, so it is a deliberate choice, never a habit.
 */
export async function digestHere(source: BlobSource, signal?: AbortSignal): Promise<string> {
  const hasher = v1.sha256Stream()
  for (let at = 0; at < source.size; at += DIGEST_SLICE) {
    if (signal?.aborted) throw new DOMException('Upload cancelled', 'AbortError')
    hasher.update(new Uint8Array(await source.slice(at, Math.min(source.size, at + DIGEST_SLICE)).arrayBuffer()))
  }
  return b64url(hasher.digest())
}

/** The ops the front door sends and the worker answers, named once, here. `digest.worker.test.ts` sends each. */
export const DIGEST_OPS = ['digest', 'cancel'] as const

export type DigestOp = (typeof DIGEST_OPS)[number]

/**
 * - `digest`: the SHA-256 of `blob`, which crosses by structured clone as a handle to the same bytes, never copied and
 *   never transferred, so the page still holds it after any post. Answered once, with its `id`.
 * - `cancel`: stop the digest `id` at its next slice. Not answered itself; the digest it stops answers `AbortError`.
 */
export type DigestRequest = { id: number; op: 'digest'; blob: Blob } | { id: number; op: 'cancel' }

/** One answer: the digest, or `source`, the read failed on the file itself (a file changed on disk is a
 *  `NotReadableError`), so it would fail the same way on the page thread. `name` is the error's own name. */
export type DigestResponse =
  | { id: number; ok: true; digest: string }
  | { id: number; ok: false; kind: 'source'; name: string; message: string }
