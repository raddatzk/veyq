// Core's frame worker (inbox #536, #220, ADR 0017 decision 1): opens a blob's chunk frames, and reads, seals and `PUT`s
// each chunk of an upload, off the page's main thread, through the same `keys.ts` functions the page thread runs
// (`sealFrameOf`, `openFrameRun`, i.e. `@noble`'s `v1.sealFrame` / `v1.openFrame` unchanged) and the same `putFrame`, so
// not one byte differs from where it ran before. The front door, with its fall-back to the page thread, is
// `frameWorker.ts`. A chunk's frame goes from here to its presigned URL and never back to the page: that round trip,
// and the `fetch` it started on the page, held the main thread through most of an upload (inbox #220's reading).
//
// It holds no state between requests but the `PUT`s in flight, by id, so a `cancel` can abort one: each request brings
// its `K_blob`, which is zeroed here once it is answered, so there is no key registry to forget and nothing a stale
// request could find. It is the page's own origin and code, so it sits at the page's trust tier (ADR 0026), its script is
// served under the shell's policy (whose `connect-src` names the store), and it imports the pure crypto and `putFrame`
// alone, never the app.
import { openFrameRun, sealFrameOf } from './keys'
import { putFrame } from './framePut'
import { buffersOf, ownBuffer, type FrameMessage, type FrameRequest, type FrameResponse } from './frameOps'

/** The refusal of an op this worker has no branch for. Spelled here and in no other `src/` module: it is also the
 *  sentinel `verify:frame-worker` finds in the emitted worker chunk. */
const UNKNOWN_OP = 'frame_worker_unknown_op'

const ctx = self as unknown as { postMessage(message: FrameMessage, transfer?: Transferable[]): void }

/** The `PUT`s in flight, by their request's id: what a `cancel` aborts. */
const puts = new Map<number, AbortController>()

function failed(id: number, kind: 'source' | 'network', e: unknown): FrameResponse {
  return { id, ok: false, kind, error: kind === 'network' && e instanceof Error ? e.message : String(e) }
}

async function sealPut(req: Extract<FrameRequest, { op: 'seal-put' }>): Promise<FrameResponse> {
  const controller = new AbortController()
  puts.set(req.id, controller)
  try {
    let frame: Uint8Array
    try {
      const plaintext = req.chunk instanceof Uint8Array ? req.chunk : new Uint8Array(await req.chunk.arrayBuffer())
      frame = sealFrameOf(req.blobKey, req.index, req.total, plaintext)
    } catch (e) {
      return failed(req.id, 'source', e)
    } finally {
      req.blobKey.fill(0) // sealed (or not): this worker's copy is done before anything is sent
    }
    try {
      return { id: req.id, ok: true, status: await putFrame(req.url, frame, controller.signal) }
    } catch (e) {
      return failed(req.id, 'network', e)
    }
  } finally {
    puts.delete(req.id)
  }
}

function answer(req: FrameRequest): FrameResponse {
  try {
    if (req.op === 'open-frames') return { id: req.id, ok: true, bytes: ownBuffer(openFrameRun(req.blobKey, req.total, req.from, req.frames)) }
    throw new Error(UNKNOWN_OP) // explicit: a new op is wired here (frameOps.ts; frames.worker.test.ts sends each)
  } catch (e) {
    return failed(req.id, 'source', e)
  } finally {
    ;(req as { blobKey?: Uint8Array }).blobKey?.fill(0) // this worker's copy; the page's is its own
  }
}

function reply(response: FrameResponse): void {
  ctx.postMessage(response, response.ok && 'bytes' in response ? buffersOf([response.bytes]) : [])
}

self.addEventListener('message', (event: MessageEvent<FrameRequest>) => {
  const req = event.data
  if (req.op === 'cancel') puts.get(req.target)?.abort()
  else if (req.op === 'seal-put') void sealPut(req).then(reply)
  else reply(answer(req))
})

ctx.postMessage({ ready: true })
