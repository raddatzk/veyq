// Page-side front door to core's digest worker (`digest.worker.ts`, inbox #554): a `Blob`'s SHA-256 is read there, off
// the page's main thread. `sharedBlob.ts` `contentDigest` is the one caller, so no production caller names this module.
// The shape is the other front doors' (`frameWorker.ts`, `shared/image/mediaDeriveWorker.ts`): a lazily created
// module-level Worker, a `workerUnavailable` latch, a `seq`-keyed map of the digests not yet answered, and a one-time
// warning when a Worker that existed could not run. The Worker is an optimisation, never a requirement:
//   - an infrastructure failure (the Worker does not load, `error`/`messageerror`, a `postMessage` that throws) latches
//     it off for the session and finishes every outstanding digest on the page thread from byte 0, through the same
//     `digest.ts` loop the Worker runs, so the result is the same digest;
//   - a `source` answer (a read the browser refused, e.g. a `NotReadableError` for a file changed on disk) rejects with a
//     `DOMException` of that name, with no page-thread retry: the same file fails the same way there;
//   - an abort rejects with an `AbortError` at once, sends `cancel` so the Worker stops at its next slice, and drops the
//     late answer by its id.
// Where there is no `Worker` at all (node: vitest) every digest runs on the page thread, silently: that is the designed
// path there. That the build emitted the Worker is `verify:digest-worker` (`scripts/assert-digest-worker-in-dist.mjs`).
//
// What crosses: the `Blob`, by structured clone, which hands the Worker the same bytes and copies none; nothing is
// transferred, so the page still holds the `Blob` after any post. That is why there is no `ready` handshake as the frame
// worker has: a Worker that fails to load, or dies mid-digest, leaves the page everything it needs to start again.
import { digestHere, type DigestRequest, type DigestResponse } from './digest'

type Job = {
  blob: Blob
  signal?: AbortSignal
  resolve: (digest: string) => void
  reject: (reason: unknown) => void
}

let worker: Worker | null = null
let workerUnavailable = false
let warned = false
let seq = 0
const jobs = new Map<number, Job>()

const aborted = () => new DOMException('Upload cancelled', 'AbortError')

/** Say once per page that a Worker which EXISTED could not run: without it a mis-bundled or refused Worker puts every
 *  file's hash back on the page thread, and the only symptom is long tasks. Carries no file name and no error text. */
function warnOnce(): void {
  if (warned) return
  warned = true
  console.warn('[veyq] digest worker unavailable — hashing files on the page thread')
}

/** Finish `job` on the page thread, from byte 0 of the `Blob` it still holds. */
function onPage(job: Job): void {
  digestHere(job.blob, job.signal).then(job.resolve, job.reject)
}

/** Retire the Worker for the rest of the session and finish every outstanding digest on the page thread. */
function latch(): void {
  workerUnavailable = true
  warnOnce()
  worker?.terminate()
  worker = null
  const outstanding = [...jobs.values()]
  jobs.clear()
  outstanding.forEach(onPage)
}

function getWorker(): Worker | null {
  if (workerUnavailable) return null
  if (worker) return worker
  if (typeof Worker === 'undefined') {
    workerUnavailable = true
    return null
  }
  try {
    // ONE expression, on purpose: Vite emits the Worker chunk only for `new Worker(new URL('…', import.meta.url))`
    // spelled together (`verify:digest-worker` turns a split constructor red).
    const w = new Worker(new URL('./digest.worker.ts', import.meta.url), { type: 'module' })
    worker = w
    w.addEventListener('message', (event: MessageEvent<DigestResponse>) => {
      const msg = event.data
      const job = jobs.get(msg.id)
      if (!job) return // aborted: nobody waits for it
      jobs.delete(msg.id)
      if (msg.ok) job.resolve(msg.digest)
      else job.reject(new DOMException(msg.message, msg.name))
    })
    w.addEventListener('error', latch)
    w.addEventListener('messageerror', latch)
    return w
  } catch {
    workerUnavailable = true
    warnOnce()
    return null
  }
}

/** Post `req`; a post that throws latches the Worker off, which finishes every outstanding digest on the page. */
function post(w: Worker, req: DigestRequest): void {
  try {
    w.postMessage(req, [])
  } catch {
    latch()
  }
}

/**
 * The SHA-256 of `blob` as `blob-meta`'s `sha256` carries it (base64url), read in core's digest worker, else on the
 * page thread by the same loop. Rejects with an `AbortError` at once when `signal` aborts, and with a `DOMException` of
 * the read's own name when the browser refuses to read the file.
 */
export function digestOff(blob: Blob, signal?: AbortSignal): Promise<string> {
  if (signal?.aborted) return Promise.reject(aborted())
  const w = getWorker()
  if (!w) return digestHere(blob, signal)
  const id = (seq += 1)
  return new Promise<string>((resolve, reject) => {
    // At once, wherever the digest runs: one the Worker holds is cancelled there, and one a latch moved to the page
    // stops at its next slice, its late rejection a no-op on a settled promise.
    const onAbort = () => {
      const inWorker = jobs.delete(id)
      reject(aborted())
      if (inWorker && worker) post(worker, { id, op: 'cancel' })
    }
    const done = () => signal?.removeEventListener('abort', onAbort)
    jobs.set(id, {
      blob,
      signal,
      resolve: (digest) => {
        done()
        resolve(digest)
      },
      reject: (reason) => {
        done()
        reject(reason)
      },
    })
    signal?.addEventListener('abort', onAbort, { once: true })
    post(w, { id, op: 'digest', blob })
  })
}
