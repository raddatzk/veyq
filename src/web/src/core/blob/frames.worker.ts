// Core's frame worker (inbox #536, ADR 0017 decision 1): seals and opens a blob's chunk frames off the page's main
// thread, through the same `keys.ts` functions the page thread runs (`sealFrameOf`, `openFrameRun`, i.e. `@noble`'s
// `v1.sealFrame` / `v1.openFrame` unchanged), so not one byte differs from where it ran before. The front door, with
// its fall-back to the page thread, is `frameWorker.ts`.
//
// It holds no state between requests: each one brings its `K_blob`, which is zeroed here once it is answered, so there
// is no key registry to forget and nothing a stale request could find. It is the page's own origin and code, so it sits
// at the page's trust tier (ADR 0026), and it imports the pure crypto alone, never the app.
import { openFrameRun, sealFrameOf } from './keys'
import { buffersOf, ownBuffer, type FrameMessage, type FrameRequest, type FrameResponse } from './frameOps'

/** The refusal of an op this worker has no branch for. Spelled here and in no other `src/` module: it is also the
 *  sentinel `verify:frame-worker` finds in the emitted worker chunk. */
const UNKNOWN_OP = 'frame_worker_unknown_op'

const ctx = self as unknown as { postMessage(message: FrameMessage, transfer?: Transferable[]): void }

function answer(req: FrameRequest): FrameResponse {
  try {
    let bytes: Uint8Array
    if (req.op === 'seal-frame') bytes = sealFrameOf(req.blobKey, req.index, req.total, req.plaintext)
    else if (req.op === 'open-frames') bytes = openFrameRun(req.blobKey, req.total, req.from, req.frames)
    else throw new Error(UNKNOWN_OP) // explicit: a new op is wired here (frameOps.ts; frames.worker.test.ts sends each)
    return { id: req.id, ok: true, bytes: ownBuffer(bytes) }
  } catch (e) {
    return { id: req.id, ok: false, kind: 'source', error: String(e) }
  } finally {
    req.blobKey?.fill(0) // this worker's copy; the page's is its own
  }
}

self.addEventListener('message', (event: MessageEvent<FrameRequest>) => {
  const response = answer(event.data)
  ctx.postMessage(response, response.ok ? buffersOf([response.bytes]) : [])
})

ctx.postMessage({ ready: true })
