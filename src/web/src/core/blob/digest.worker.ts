// Core's digest worker (inbox #554, ADR 0017 decision 1): a file's SHA-256 off the page's main thread, by the same
// `digest.ts` loop the page thread runs (`digestHere`, i.e. `@noble`'s `v1.sha256Stream`), so not one byte of the digest
// differs from where it ran before. The front door, with its fall-back to the page thread, is `digestWorker.ts`.
//
// It is handed the `Blob` itself and reads its own slices, 256 KiB at a time, as the page did. Its handler is async, so
// two digests interleave slice by slice (a banner picked while the queue uploads) and a long video blocks no other
// request. Its only state is each running digest's `AbortController`, which a `cancel` aborts; an entry is deleted once
// its digest answered, so nothing outlives a request. The worker sees the plaintext, as the frame and media workers do:
// it is the page's own origin and code, at the page's trust tier (ADR 0026), it holds no key and it sends nothing.
import { digestHere, type DigestRequest, type DigestResponse } from './digest'

/** The refusal of an op this worker has no branch for. Spelled here and in no other `src/` module: it is also the
 *  sentinel `verify:digest-worker` finds in the emitted worker chunk. */
const UNKNOWN_OP = 'digest_worker_unknown_op'

const ctx = self as unknown as { postMessage(message: DigestResponse): void }
const running = new Map<number, AbortController>()

async function digest(id: number, blob: Blob): Promise<DigestResponse> {
  const controller = new AbortController()
  running.set(id, controller)
  try {
    return { id, ok: true, digest: await digestHere(blob, controller.signal) }
  } catch (e) {
    const error = e instanceof Error || e instanceof DOMException ? e : new Error(String(e))
    return { id, ok: false, kind: 'source', name: error.name, message: error.message }
  } finally {
    running.delete(id)
  }
}

self.addEventListener('message', (event: MessageEvent<DigestRequest>) => {
  const req = event.data
  if (req.op === 'cancel') {
    running.get(req.id)?.abort()
    return
  }
  if (req.op === 'digest') {
    void digest(req.id, req.blob).then((response) => ctx.postMessage(response))
    return
  }
  // explicit: a new op is wired here (digest.ts DIGEST_OPS; digest.worker.test.ts sends each)
  const unknown = req as { id: number }
  ctx.postMessage({ id: unknown.id, ok: false, kind: 'source', name: 'Error', message: UNKNOWN_OP })
})
