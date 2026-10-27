// The ops the frame front door (`frameWorker.ts`) sends and the frame worker (`frames.worker.ts`) answers (inbox #536),
// named once, here, because the two files must not import each other: the worker importing the front door would pull
// its `new Worker(…)` call into the worker chunk. The front door sends only a `FrameRequest`, so tsc refuses an op
// outside this list, and `frames.worker.test.ts` sends every op in it: an op added here without a dispatcher branch is
// answered as a refusal, which the front door rejects, and that test goes red.
export const FRAME_OPS = ['seal-frame', 'open-frames'] as const

export type FrameOp = (typeof FRAME_OPS)[number]

/**
 * - `seal-frame`: frame `index` of a blob of `total` frames, sealed under `blobKey` with a nonce the worker draws;
 * - `open-frames`: `frames[k]` opened as frame `from + k` of a blob of `total` frames, joined (`keys.ts` `openFrameRun`).
 *
 * `blobKey` crosses by structured clone and the worker zeroes its copy once it answered; the bytes cross in the
 * transfer list, so the page's own `plaintext` / `frames` are detached after the post.
 */
export type FrameRequest =
  | { id: number; op: 'seal-frame'; blobKey: Uint8Array; index: number; total: number; plaintext: Uint8Array }
  | { id: number; op: 'open-frames'; blobKey: Uint8Array; total: number; from: number; frames: Uint8Array[] }

/** One answer: the bytes (transferred), or `source`, the worker ran the job and it failed on its input (a frame the
 *  AEAD refuses), so it would fail the same way on the page thread. */
export type FrameResponse =
  | { id: number; ok: true; bytes: Uint8Array }
  | { id: number; ok: false; kind: 'source'; error: string }

/** What the worker posts once, when its module has run: from then on a post reaches code that answers it, so the
 *  front door transfers bytes only after it (`frameWorker.ts` says why). */
export type FrameReady = { ready: true }

export type FrameMessage = FrameResponse | FrameReady

/** `view` itself where it spans its whole buffer, else a copy that does: a view's buffer is what a transfer moves, and
 *  moving a buffer that holds more than the view would detach bytes its owner still reads. */
export function ownBuffer(view: Uint8Array): Uint8Array {
  return view.byteOffset === 0 && view.byteLength === view.buffer.byteLength ? view : view.slice()
}

/** The buffers of `views`, once each: a transfer list that names one buffer twice throws `DataCloneError`. */
export function buffersOf(views: readonly Uint8Array[]): Transferable[] {
  return [...new Set(views.map((v) => v.buffer as ArrayBuffer))]
}
