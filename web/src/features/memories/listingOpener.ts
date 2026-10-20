// Page-side front door to the listing opener Worker (`listingOpener.worker.ts`, ADR 0105 decision 3, inbox #489). The
// shape is `shared/image/mediaDeriveWorker.ts`'s: a lazily created module-level Worker, a `workerUnavailable` latch, a
// `seq`-keyed pending map with a per-request fall-back closure, and a one-time console warning when a Worker that
// existed could not run. The Worker is an optimisation, never a requirement:
//   - an infrastructure failure (an `infra` answer, `error`/`messageerror`, a `postMessage` that throws) latches the
//     Worker off for the session and finishes every outstanding request on the page thread;
//   - a `refused` answer (the Memory is not open in the Worker — the page closed it while the read was on its way)
//     rejects with an `AbortError`: the read is stale, and nobody is waiting for it;
//   - a `source` answer rejects a frame read (the same frames would fail the same AEAD on the page) and sends a listing
//     batch to the page thread.
// Where there is no `Worker` at all (node: vitest) every request takes its fall-back; that the build emitted the Worker
// is `verify:listing-worker` (`scripts/assert-listing-worker-in-dist.mjs`).
import { openFrames } from '../../core/blob/keys'
import type { OpenItem, OpenedItem, OpenerRequest, OpenerResponse } from './listingOpenerCore'

type Pending = {
  resolve: (value: OpenerResponse & { ok: true }) => void
  reject: (reason: unknown) => void
  /** Finish this request on the page thread (the Worker is gone), or reject it. */
  fallback: () => void
  /** A `source` answer: reject (a frame) or finish on the page (a listing batch). */
  onSource: () => void
}

let worker: Worker | null = null
let workerUnavailable = false
let warned = false
let seq = 0
let memories = 0
const pending = new Map<number, Pending>()

const stale = () => new DOMException('The Memory was closed', 'AbortError')

function warnOnce(): void {
  if (warned) return
  warned = true
  console.warn('[veyq] listing opener worker unavailable — opening the gallery on the page thread')
}

function latch(): void {
  workerUnavailable = true
  warnOnce()
  worker?.terminate()
  worker = null
  const outstanding = [...pending.values()]
  pending.clear()
  outstanding.forEach((p) => p.fallback())
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
    // spelled together (`verify:listing-worker` turns a split constructor red).
    worker = new Worker(new URL('./listingOpener.worker.ts', import.meta.url), { type: 'module' })
    worker.addEventListener('message', (event: MessageEvent<OpenerResponse>) => {
      const msg = event.data
      const p = pending.get(msg.id)
      if (!p) return
      pending.delete(msg.id)
      if (msg.ok) p.resolve(msg)
      else if (msg.kind === 'refused') p.reject(stale())
      else if (msg.kind === 'source') p.onSource()
      else {
        p.fallback()
        latch()
      }
    })
    worker.addEventListener('error', latch)
    worker.addEventListener('messageerror', latch)
    return worker
  } catch {
    workerUnavailable = true
    warnOnce()
    return null
  }
}

/** Post `req` to the Worker, or run `fallback` where there is none (or once it fails). */
function send<T>(
  req: OpenerRequest,
  done: (msg: OpenerResponse & { ok: true }) => T,
  fallback: () => T,
  onSource: 'reject' | 'fallback',
): Promise<T> {
  const w = getWorker()
  if (!w) return Promise.resolve().then(fallback)
  return new Promise<T>((resolve, reject) => {
    const finish = () => {
      try {
        resolve(fallback())
      } catch (e) {
        reject(e)
      }
    }
    const p: Pending = {
      resolve: (msg) => {
        try {
          resolve(done(msg))
        } catch (e) {
          reject(e)
        }
      },
      reject,
      fallback: finish,
      onSource: onSource === 'reject' ? () => reject(new Error('frame_open_failed')) : finish,
    }
    pending.set(req.id, p)
    try {
      w.postMessage(req)
    } catch {
      pending.delete(req.id)
      finish()
      latch()
    }
  })
}

/** A fresh handle for a Memory the page opens — the Worker keeps that Memory's keys under it until [closeMemory]. */
export function newMemoryHandle(): number {
  return (memories += 1)
}

/** Hand the Worker keys of an open Memory (`[keyId, key]`; `K_node` and its uploaders' keys). Fire-and-forget: the
 *  Worker answers in order, so a later request for this Memory finds them. Nothing to do without a Worker. */
export function openMemory(memory: number, keys: [string, Uint8Array][]): void {
  const w = getWorker()
  if (!w) return
  void send({ id: (seq += 1), op: 'open-memory', memory, keys }, () => undefined, () => undefined, 'fallback').catch(() => {})
}

/** The Worker forgets a Memory's keys; every later request for it is refused. */
export function closeMemory(memory: number): void {
  if (!worker) return
  void send({ id: (seq += 1), op: 'close-memory', memory }, () => undefined, () => undefined, 'fallback').catch(() => {})
}

/** Open a batch of listed media in the Worker, or by `onPage` (the page thread's open of the same batch). */
export function openItems(memory: number, scopeId: string, items: OpenItem[], onPage: () => OpenedItem[]): Promise<OpenedItem[]> {
  return send({ id: (seq += 1), op: 'open-items', memory, scopeId, items }, (msg) => msg.items ?? [], onPage, 'fallback')
}

/** Open a blob's frames under its `K_blob` in the Worker, else on the page thread. Rejects as `openFrames` would, and
 *  with an `AbortError` when the Memory was closed in the Worker meanwhile. */
export function openFramesFor(memory: number, blobKey: Uint8Array, frames: Uint8Array[]): Promise<Uint8Array> {
  return send(
    { id: (seq += 1), op: 'open-frames', memory, blobKey, frames },
    (msg) => {
      if (!msg.bytes) throw new Error('frame_open_failed')
      return msg.bytes
    },
    () => openFrames(blobKey, frames),
    'reject',
  )
}
