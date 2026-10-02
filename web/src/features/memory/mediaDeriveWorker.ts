// Page-side front door to the media Worker (`mediaDerive.worker.ts`): `createBrowserDeriver().deriveImage` sends
// every image derivation here first. The shape is `@veyq/crypto`'s argonWorker — a lazily created module-level
// Worker, a `workerUnavailable` latch, a per-request fallback closure — but NOT its error handling: Argon cannot
// fail on its input, a media decode can. So:
//   - an infrastructure failure (the Worker answers `infra`, fires `error`/`messageerror`, or `postMessage` throws)
//     latches the Worker off for the session and finishes the request on the page thread — the Worker is an
//     optimisation, never a requirement;
//   - a source failure (the Worker answers `source`) REJECTS, with no page-thread retry: the same bytes would fail
//     the same way there, at twice the cost, and `deriveTiers`' catch already makes that upload original-only.
// A Worker that never loads (mis-bundled URL, CSP refusal) therefore degrades silently to today's behaviour; the
// unit tests only see that fallback, so only a live backgrounded-tab measurement proves the Worker path ran.
import type { DeriveOpts, DeriveRequest, DeriveResponse } from './mediaDerive.worker'

export type DeriveResult = { bytes: Uint8Array; source: { w: number; h: number } | null }

type Pending = {
  resolve: (value: DeriveResult) => void
  reject: (reason: unknown) => void
  fallback: () => Promise<DeriveResult>
}

// Module-level, created on the first `deriveImage` — not per deriver: `useOptimisticTiles` constructs a deriver only
// to probe and make posters, and opening a gallery must not spawn a Worker for nothing.
let worker: Worker | null = null
let workerUnavailable = false
let seq = 0
const pending = new Map<number, Pending>()

function finishOnFallback(p: Pending): void {
  p.fallback().then(p.resolve, p.reject)
}

/** Retire the Worker for the rest of the session and finish every outstanding request on the page thread. */
function latch(): void {
  workerUnavailable = true
  worker?.terminate()
  worker = null
  const outstanding = [...pending.values()]
  pending.clear()
  outstanding.forEach(finishOnFallback)
}

function getWorker(): Worker | null {
  if (workerUnavailable) return null
  if (worker) return worker
  if (typeof Worker === 'undefined') {
    workerUnavailable = true
    return null
  }
  try {
    worker = new Worker(new URL('./mediaDerive.worker.ts', import.meta.url), { type: 'module' })
    worker.addEventListener('message', (event: MessageEvent<DeriveResponse>) => {
      const msg = event.data
      const p = pending.get(msg.id)
      if (!p) return
      pending.delete(msg.id)
      if (msg.ok) p.resolve({ bytes: msg.bytes, source: msg.source })
      else if (msg.kind === 'source') p.reject(new Error(msg.error))
      else {
        // `infra`: this Worker cannot derive at all, so neither this request nor any later one should go to it.
        finishOnFallback(p)
        latch()
      }
    })
    // A load error, a runtime crash, an OOM, or an undeserialisable reply: fatal for the Worker, never for the upload.
    worker.addEventListener('error', latch)
    worker.addEventListener('messageerror', latch)
    return worker
  } catch {
    workerUnavailable = true
    return null
  }
}

/**
 * Derive one image tier in the media Worker where the browser offers one, else (or when the Worker cannot run)
 * by `fallback` — the page-thread implementation of this exact request. Rejects on a source failure.
 */
export function deriveImageWorkerFirst(
  source: Blob,
  opts: DeriveOpts,
  fallback: () => Promise<DeriveResult>,
): Promise<DeriveResult> {
  const w = getWorker()
  if (!w) return fallback()
  const id = (seq += 1)
  return new Promise<DeriveResult>((resolve, reject) => {
    const p: Pending = { resolve, reject, fallback }
    pending.set(id, p)
    try {
      // The whole `opts` crosses: a space icon's `mimeType: 'image/webp'` and a `background` fill are part of the
      // request, and dropping them would silently JPEG-flatten every icon. The source Blob is cloned by reference.
      w.postMessage({ id, source, opts } satisfies DeriveRequest)
    } catch {
      pending.delete(id)
      finishOnFallback(p)
      latch()
    }
  })
}
