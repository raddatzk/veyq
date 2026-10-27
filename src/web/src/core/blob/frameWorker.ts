// Page-side front door to core's frame worker (`frames.worker.ts`, inbox #536): every chunk frame core's blob transport
// seals or opens goes through here. The shape is the other front doors' (`features/memories/listingOpener.ts`,
// `shared/image/mediaDeriveWorker.ts`): a lazily created module-level Worker, a `workerUnavailable` latch, a `seq`-keyed
// map of the jobs not yet answered, each with its own way to finish on the page thread, and a one-time warning when a
// Worker that existed could not run. The Worker is an optimisation, never a requirement:
//   - an infrastructure failure (the Worker does not load, `error`/`messageerror`, a `postMessage` that throws) latches
//     it off for the session and finishes every outstanding job on the page thread, through the same `keys.ts` call the
//     Worker runs: the same `@noble` code on another thread, so no second implementation;
//   - a `source` answer (a frame the AEAD refuses) rejects, with no page-thread retry: the same bytes fail the same way;
//   - an abort rejects with an `AbortError` at once, and the answer of a job already in the Worker is dropped by its id.
// Where there is no `Worker` at all (node: vitest) every job runs on the page thread, silently: that is the designed path
// there. That the build emitted the Worker is `verify:frame-worker` (`scripts/assert-frame-worker-in-dist.mjs`).
//
// What crosses: `K_blob` by structured clone in each job (the Worker zeroes its copy once it answered); the plaintext and
// the frames in the transfer list, never copied, so the caller's buffers are DETACHED after a post — every caller hands
// over bytes it does not read again. A view that does not span its whole buffer is copied first (`ownBuffer`).
//
// Why the front door waits for the Worker's `ready` before it posts anything: a module Worker that fails to load (a
// mis-bundled chunk, a CSP refusal) fires `error` only after the first posts were made, and a post transfers its bytes at
// once, so the jobs it would have to finish on the page thread would have nothing left to finish with. Until `ready`, a
// job waits here with its bytes intact; after it, a load failure cannot happen any more, and what is left is a Worker
// that dies mid-job: a seal is then read again from its source (`reread`), and an open, whose fetched frames are gone,
// rejects `frame_worker_lost`.
import * as v1 from '@veyq/crypto/v1'
import { openFrameRun, sealFrameOf } from './keys'
import { buffersOf, ownBuffer, type FrameMessage, type FrameRequest } from './frameOps'

/** Frames per open job: 16 frames, 4 MiB of plaintext, ≈ 70 ms at `@noble`'s measured rate. The seal and the open
 *  share one Worker, which runs one job at a time, so a whole-blob open is cut into runs: an upload's seals wait at most
 *  one run behind a lightbox, and an abort wastes at most the run already in the Worker. */
export const OPEN_RUN_FRAMES = 16

type Job = {
  req: FrameRequest
  /** Posted, so its bytes were transferred: the page no longer holds them. */
  posted: boolean
  resolve: (bytes: Uint8Array) => void
  reject: (reason: unknown) => void
  /** Finish this job on the page thread (the Worker is gone). */
  onPage: () => void
}

let worker: Worker | null = null
let workerUnavailable = false
let ready = false
let warned = false
let seq = 0
const jobs = new Map<number, Job>()

const aborted = () => new DOMException('Read cancelled', 'AbortError')

/** Say once per page that a Worker which EXISTED could not run: without it a mis-bundled or refused Worker puts every
 *  frame back on the page thread, and the only symptom is long tasks. Carries no file name and no error text. */
function warnOnce(): void {
  if (warned) return
  warned = true
  console.warn('[veyq] frame worker unavailable — sealing and opening blob frames on the page thread')
}

/** Retire the Worker for the rest of the session and finish every outstanding job on the page thread. */
function latch(): void {
  workerUnavailable = true
  warnOnce()
  worker?.terminate()
  worker = null
  ready = false
  const outstanding = [...jobs.values()]
  jobs.clear()
  outstanding.forEach((job) => job.onPage())
}

function post(w: Worker, job: Job): void {
  const { req } = job
  try {
    w.postMessage(req, buffersOf(req.op === 'seal-frame' ? [req.plaintext] : req.frames))
    job.posted = true
  } catch {
    latch() // a post that throws transferred nothing (the clone fails first), so the job finishes here intact
  }
}

/** The Worker's module ran: post every job that waited for it, in the order asked. */
function flush(w: Worker): void {
  for (const job of [...jobs.values()]) {
    if (worker !== w) return // latched meanwhile: the rest finished on the page
    if (!job.posted && jobs.has(job.req.id)) post(w, job)
  }
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
    // spelled together (`verify:frame-worker` turns a split constructor red).
    const w = new Worker(new URL('./frames.worker.ts', import.meta.url), { type: 'module' })
    worker = w
    w.addEventListener('message', (event: MessageEvent<FrameMessage>) => {
      const msg = event.data
      if ('ready' in msg) {
        ready = true
        flush(w)
        return
      }
      const job = jobs.get(msg.id)
      if (!job) return // aborted: nobody waits for it
      jobs.delete(msg.id)
      if (msg.ok) job.resolve(msg.bytes)
      else job.reject(new Error(job.req.op === 'seal-frame' ? 'frame_seal_failed' : 'frame_open_failed'))
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

/** Run `req` in the Worker, or `here` on the page thread where there is none (or once it failed before taking the job);
 *  `recover` where the Worker took the job's bytes and then died. */
function submit(
  req: FrameRequest,
  here: () => Uint8Array,
  recover: () => Promise<Uint8Array>,
  signal?: AbortSignal,
): Promise<Uint8Array> {
  if (signal?.aborted) return Promise.reject(aborted())
  const w = getWorker()
  if (!w) return Promise.resolve().then(here)
  return new Promise<Uint8Array>((resolve, reject) => {
    const onAbort = () => {
      jobs.delete(req.id) // never posted if it still waited; its answer dropped if it was
      reject(aborted())
    }
    const done = () => signal?.removeEventListener('abort', onAbort)
    const job: Job = {
      req,
      posted: false,
      resolve: (bytes) => {
        done()
        resolve(bytes)
      },
      reject: (reason) => {
        done()
        reject(reason)
      },
      onPage: () => {
        done()
        ;(job.posted ? recover() : Promise.resolve().then(here)).then(resolve, reject)
      },
    }
    signal?.addEventListener('abort', onAbort, { once: true })
    jobs.set(req.id, job)
    if (ready) post(w, job)
  })
}

/**
 * Seal frame `index` of a blob of `total` frames under its `K_blob`, in the frame worker, else on the page thread.
 * `plaintext` is handed over (detached after the call); `reread` reads the same bytes again, for a job the Worker took
 * and could not answer. Resolves to the sealed frame, a buffer the caller owns.
 */
export function sealFrameOff(
  blobKey: Uint8Array,
  index: number,
  total: number,
  plaintext: Uint8Array,
  reread?: () => Promise<Uint8Array>,
): Promise<Uint8Array> {
  const own = ownBuffer(plaintext)
  return submit(
    { id: (seq += 1), op: 'seal-frame', blobKey, index, total, plaintext: own },
    () => sealFrameOf(blobKey, index, total, own),
    async () => {
      if (!reread) throw new Error('frame_worker_lost')
      return sealFrameOf(blobKey, index, total, await reread())
    },
  )
}

/**
 * Open a run of a blob's frames, `frames[k]` being frame `from + k` of a blob of `total` frames, in the frame worker,
 * else on the page thread (`keys.ts` `openFrameRun`). The frames are handed over (detached after the call). Rejects as
 * the open would, with an `AbortError` when `signal` aborts first.
 */
export function openFrameRunOff(
  blobKey: Uint8Array,
  total: number,
  from: number,
  frames: readonly Uint8Array[],
  signal?: AbortSignal,
): Promise<Uint8Array> {
  const own = frames.map(ownBuffer)
  return submit(
    { id: (seq += 1), op: 'open-frames', blobKey, total, from, frames: own },
    () => openFrameRun(blobKey, total, from, own),
    () => Promise.reject(new Error('frame_worker_lost')),
    signal,
  )
}

/**
 * A whole blob's frames, in chunk order, opened under its `K_blob` and joined: `keys.ts` `openFrames`, sent to the
 * frame worker as runs of at most [OPEN_RUN_FRAMES], one after the other, with `signal` checked between runs. The
 * frames are handed over (detached after the call).
 */
export async function openFramesOff(blobKey: Uint8Array, frames: readonly Uint8Array[], signal?: AbortSignal): Promise<Uint8Array> {
  const runs: Uint8Array[] = []
  for (let from = 0; from < frames.length; from += OPEN_RUN_FRAMES) {
    runs.push(await openFrameRunOff(blobKey, frames.length, from, frames.slice(from, from + OPEN_RUN_FRAMES), signal))
  }
  return runs.length === 1 ? runs[0] : v1.concat(...runs)
}
