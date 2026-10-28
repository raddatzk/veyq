// The ops the frame front door (`frameWorker.ts`) sends and the frame worker (`frames.worker.ts`) answers (inbox #536,
// #220), named once, here, because the two files must not import each other: the worker importing the front door would
// pull its `new Worker(…)` call into the worker chunk. The front door sends only a `FrameRequest`, so tsc refuses an op
// outside this list, and `frames.worker.test.ts` sends every op in it: an op added here without a dispatcher branch is
// answered as a refusal, which the front door rejects, and that test goes red.
export const FRAME_OPS = ['open-frames', 'seal-put', 'cancel'] as const

export type FrameOp = (typeof FRAME_OPS)[number]

/**
 * - `open-frames`: `frames[k]` opened as frame `from + k` of a blob of `total` frames, joined (`keys.ts` `openFrameRun`);
 * - `seal-put`: chunk `index` of a blob of `total` frames read (from `chunk`: a `Blob` slice the worker reads, or the
 *   slice's bytes where the source is no `Blob`), sealed under `blobKey` with a nonce the worker draws, and `PUT` to
 *   `url` (`framePut.ts`); the answer is the store's status, and the frame never comes back;
 * - `cancel`: abort the `PUT` of the `seal-put` whose id is `target`. No job: it has no answer.
 *
 * `blobKey` crosses by structured clone and the worker zeroes its copy once it answered; bytes cross in the transfer
 * list, so the page's own `frames` / byte `chunk` are detached after the post. A `Blob` crosses as a handle: nothing is
 * read on the page.
 */
export type FrameRequest =
  | { id: number; op: 'open-frames'; blobKey: Uint8Array; total: number; from: number; frames: Uint8Array[] }
  | { id: number; op: 'seal-put'; blobKey: Uint8Array; index: number; total: number; chunk: Blob | Uint8Array; url: string }
  | { id: number; op: 'cancel'; target: number }

/** One answer: the opened bytes (transferred), the store's status of a `seal-put`, or a failure: `source`, the worker ran
 *  the job and it failed on its input (a frame the AEAD refuses, a chunk it could not read), so it would fail the same
 *  way on the page thread; `network`, the `PUT` failed in the network (its `error` is the browser's message). */
export type FrameResponse =
  | { id: number; ok: true; bytes: Uint8Array }
  | { id: number; ok: true; status: number }
  | { id: number; ok: false; kind: 'source' | 'network'; error: string }

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

/** What a request transfers: its frames, or a `seal-put`'s byte chunk; a `Blob` chunk and a `cancel` transfer nothing. */
export function transferOf(req: FrameRequest): Transferable[] {
  if (req.op === 'open-frames') return buffersOf(req.frames)
  if (req.op === 'seal-put' && req.chunk instanceof Uint8Array) return buffersOf([req.chunk])
  return []
}
