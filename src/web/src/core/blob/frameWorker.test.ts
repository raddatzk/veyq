// @vitest-environment node
//
// The frame worker's front door (inbox #536) against a fake Worker, `listingOpener.test.ts`'s pattern: the fake speaks
// the protocol (`ready` first, then one answer per job) and posts through `structuredClone(msg, { transfer })`, so a
// transfer detaches the page's buffers exactly as a real post does. Its answers are the `keys.ts` calls the real worker
// runs; that the real worker answers so is `frames.worker.test.ts`. Which Worker exists is set per case with
// `vi.stubGlobal`, and the module is re-imported per case: the Worker, its latch and its warning are module-level.
//
// Every negative has a positive control on the same predicate, and the pump's own suite (`transport.test.ts`, page
// thread) stays the oracle of the upload's shape; here the pump runs through the Worker.
//
// Bites: post before the Worker's `ready` and "a Worker that does not load" goes red (the jobs' bytes are gone when
// they have to finish on the page); drop the abort listener and "an abort" goes red; transfer a sub-view's buffer
// instead of copying it and "a sub-view" goes red.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import * as v1 from '@veyq/crypto/v1'
import { openFrameRun, openFrames, sealFrameOf } from './keys'
import type { FrameMessage, FrameRequest, FrameResponse } from './frameOps'

vi.mock('../../api/client', () => ({ blobApi: vi.fn(), tokenApi: vi.fn() }))

type Mode = 'real' | 'no-load' | 'dies-after-post' | 'throw-construct' | 'throw-post'
type Listener = (e: unknown) => void

/** The fake Worker. `hold` keeps answers back until `release()`, so a case can act while a job is in the Worker. */
function stubWorker(mode: Mode = 'real', opts: { hold?: boolean } = {}) {
  const stats = { constructed: 0, posted: [] as FrameRequest[], terminated: 0 }
  const held: (() => void)[] = []
  class FakeWorker {
    private handlers: Record<string, Listener[]> = {}
    constructor() {
      if (mode === 'throw-construct') throw new Error('blocked by CSP')
      stats.constructed += 1
      // A module Worker answers nothing before its module ran: `ready`, or (it failed to load) `error`.
      setTimeout(() => (mode === 'no-load' ? this.emit('error', {}) : this.emit('message', { data: { ready: true } })), 1)
    }
    addEventListener(type: string, cb: Listener): void {
      ;(this.handlers[type] ??= []).push(cb)
    }
    emit(type: string, event: unknown): void {
      this.handlers[type]?.forEach((cb) => cb(event))
    }
    postMessage(msg: FrameRequest, transfer: Transferable[] = []): void {
      if (mode === 'throw-post') throw new DOMException('could not be cloned', 'DataCloneError')
      const copy = structuredClone(msg, { transfer }) // detaches what `transfer` names, on the page
      stats.posted.push(copy)
      if (mode === 'dies-after-post') {
        setTimeout(() => this.emit('error', {}), 1)
        return
      }
      const reply = () => this.emit('message', { data: answer(copy) })
      if (opts.hold) held.push(reply)
      else setTimeout(reply, 1)
    }
    terminate(): void {
      stats.terminated += 1
    }
  }
  vi.stubGlobal('Worker', FakeWorker as unknown as typeof Worker)
  return {
    stats,
    /** Answer the oldest held job. */
    release: () => held.shift()?.(),
  }
}

/** What the real worker answers (`frames.worker.ts`), over the same `keys.ts` calls. */
function answer(req: FrameRequest): FrameMessage {
  try {
    const bytes = req.op === 'seal-frame'
      ? sealFrameOf(req.blobKey, req.index, req.total, req.plaintext)
      : openFrameRun(req.blobKey, req.total, req.from, req.frames)
    return { id: req.id, ok: true, bytes } satisfies FrameResponse
  } catch (e) {
    return { id: req.id, ok: false, kind: 'source', error: String(e) }
  }
}

async function freshDoor() {
  vi.resetModules()
  return import('./frameWorker')
}

const tick = () => new Promise((r) => setTimeout(r, 5))
const plain = (n: number, seed: number) => new Uint8Array(n).map((_, i) => (i * 7 + seed) % 251)
/** A blob of `count` small frames (`n` plaintext bytes each): its key, its plaintext, and fresh copies of its frames. */
function blob(count: number, n = 3) {
  const blobKey = v1.randomBytes(32)
  const parts = Array.from({ length: count }, (_, i) => plain(n, i))
  const sealed = parts.map((p, i) => v1.sealFrame(blobKey, i, count, p, v1.randomBytes(12)))
  return { blobKey, plaintext: v1.concat(...parts), frames: () => sealed.map((f) => f.slice()) }
}

let warn: ReturnType<typeof vi.spyOn>
beforeEach(() => {
  vi.resetModules()
  warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
})
afterEach(() => {
  warn.mockRestore()
  vi.unstubAllGlobals()
  vi.resetModules()
})

describe('the front door, with a healthy Worker', () => {
  it('opens a whole blob in runs of at most OPEN_RUN_FRAMES, each against the whole blob\'s total, to openFrames\'s bytes', async () => {
    const worker = stubWorker()
    const door = await freshDoor()
    const b = blob(2 * door.OPEN_RUN_FRAMES + 3)
    expect(await door.openFramesOff(b.blobKey, b.frames())).toEqual(openFrames(b.blobKey, b.frames()))
    expect(worker.stats.posted.map((r) => r.op === 'open-frames' && [r.from, r.frames.length, r.total])).toEqual([
      [0, 16, 35], [16, 16, 35], [32, 3, 35],
    ])
    expect(warn).not.toHaveBeenCalled() // the control for the latch cases' one warning
  })

  it('seals a frame the page opens, and a frame the AEAD refuses rejects without latching the Worker', async () => {
    const worker = stubWorker()
    const door = await freshDoor()
    const blobKey = v1.randomBytes(32)
    const frame = await door.sealFrameOff(blobKey, 1, 2, plain(9, 1))
    expect(v1.openFrame(blobKey, 1, 2, frame)).toEqual(plain(9, 1))
    await expect(door.openFrameRunOff(v1.randomBytes(32), 2, 1, [frame.slice()])).rejects.toThrow('frame_open_failed')
    // the refusal was the input's: the Worker still takes the next job
    expect(await door.openFrameRunOff(blobKey, 2, 1, [frame])).toEqual(plain(9, 1))
    expect(worker.stats.posted.map((r) => r.op)).toEqual(['seal-frame', 'open-frames', 'open-frames'])
    expect(worker.stats.constructed).toBe(1)
    expect(warn).not.toHaveBeenCalled()
  })

  it('posts nothing before the Worker said it is ready', async () => {
    const worker = stubWorker()
    const door = await freshDoor()
    const sealed = door.sealFrameOff(v1.randomBytes(32), 0, 1, plain(4, 0))
    expect(worker.stats.posted).toHaveLength(0) // the Worker exists, its module has not run yet
    await sealed
    expect(worker.stats.posted).toHaveLength(1)
  })
})

describe('what crosses: K_blob cloned, the bytes transferred', () => {
  it('a transferred buffer is detached on the page, and the page\'s K_blob is untouched', async () => {
    const worker = stubWorker()
    const door = await freshDoor()
    const blobKey = v1.randomBytes(32)
    const keyBefore = blobKey.slice()
    const plaintext = plain(64, 3)
    const frame = await door.sealFrameOff(blobKey, 0, 1, plaintext)
    expect(plaintext.byteLength).toBe(0) // moved, not copied
    expect(worker.stats.posted[0].op === 'seal-frame' && worker.stats.posted[0].plaintext).toEqual(plain(64, 3))
    expect(blobKey).toEqual(keyBefore)
    const frames = [frame]
    await door.openFrameRunOff(blobKey, 1, 0, frames)
    expect(frames[0].byteLength).toBe(0)
  })

  it('a sub-view is copied, so its parent stays whole and usable (control: the whole buffer above is detached)', async () => {
    stubWorker()
    const door = await freshDoor()
    const blobKey = v1.randomBytes(32)
    const parent = plain(100, 5)
    const view = parent.subarray(10, 60)
    const frame = await door.sealFrameOff(blobKey, 0, 1, view)
    expect(parent).toEqual(plain(100, 5))
    expect(view.byteLength).toBe(50)
    expect(v1.openFrame(blobKey, 0, 1, frame)).toEqual(plain(100, 5).subarray(10, 60))
    // the same for a frame that is a view into a larger buffer
    const framed = new Uint8Array(frame.byteLength + 8)
    framed.set(frame, 8)
    expect(await door.openFrameRunOff(blobKey, 1, 0, [framed.subarray(8)])).toEqual(view)
    expect(framed.byteLength).toBe(frame.byteLength + 8)
  })
})

describe('an abort', () => {
  it('rejects AbortError at once while a run is in the Worker, drops that run\'s answer and sends no later run', async () => {
    const worker = stubWorker('real', { hold: true })
    const door = await freshDoor()
    const b = blob(40)
    const ctl = new AbortController()
    const opened = door.openFramesOff(b.blobKey, b.frames(), ctl.signal)
    await tick()
    worker.release() // the first run answered
    await tick()
    expect(worker.stats.posted).toHaveLength(2) // the second run is in the Worker
    ctl.abort()
    await expect(opened).rejects.toMatchObject({ name: 'AbortError' })
    worker.release() // its answer arrives late, and is nobody's
    await tick()
    expect(worker.stats.posted).toHaveLength(2) // the third run was never sent
  })

  it('control: the same blob, unaborted, resolves to openFrames\'s bytes', async () => {
    const worker = stubWorker('real', { hold: true })
    const door = await freshDoor()
    const b = blob(40)
    const opened = door.openFramesOff(b.blobKey, b.frames(), new AbortController().signal)
    for (let i = 0; i < 3; i++) {
      await tick()
      worker.release()
    }
    expect(await opened).toEqual(b.plaintext)
    expect(worker.stats.posted).toHaveLength(3)
  })

  it('an already aborted signal sends nothing', async () => {
    const worker = stubWorker()
    const door = await freshDoor()
    const b = blob(2)
    const ctl = new AbortController()
    ctl.abort()
    await expect(door.openFramesOff(b.blobKey, b.frames(), ctl.signal)).rejects.toMatchObject({ name: 'AbortError' })
    await tick()
    expect(worker.stats.posted).toHaveLength(0)
  })
})

describe('a Worker that cannot run latches off and the jobs finish on the page thread, with one warning', () => {
  it('a Worker that does not load: every outstanding job finishes on the page, and every later one runs there', async () => {
    const worker = stubWorker('no-load')
    const door = await freshDoor()
    const b = blob(20)
    const blobKey = v1.randomBytes(32)
    const outstanding = [
      door.openFramesOff(b.blobKey, b.frames()),
      door.sealFrameOff(blobKey, 0, 2, plain(5, 0)),
      door.sealFrameOff(blobKey, 1, 2, plain(5, 1)),
    ] as const
    const [opened, f0, f1] = await Promise.all(outstanding)
    expect(opened).toEqual(b.plaintext)
    expect(v1.openFrame(blobKey, 0, 2, f0)).toEqual(plain(5, 0))
    expect(v1.openFrame(blobKey, 1, 2, f1)).toEqual(plain(5, 1))
    expect(warn).toHaveBeenCalledTimes(1)
    expect(worker.stats.terminated).toBe(1)

    expect(await door.openFramesOff(b.blobKey, b.frames())).toEqual(b.plaintext)
    expect(worker.stats.constructed).toBe(1) // latched: no second Worker
    expect(warn).toHaveBeenCalledTimes(1)
  })

  it('a Worker that dies with a job in it: a seal is read again from its source, an open rejects frame_worker_lost', async () => {
    stubWorker('dies-after-post')
    const door = await freshDoor()
    const blobKey = v1.randomBytes(32)
    const reread = vi.fn(async () => plain(5, 9))
    const b = blob(2)
    const [sealed, opened] = await Promise.allSettled([
      door.sealFrameOff(blobKey, 0, 1, plain(5, 9), reread),
      door.openFramesOff(b.blobKey, b.frames()),
    ])
    expect(sealed.status === 'fulfilled' && v1.openFrame(blobKey, 0, 1, sealed.value)).toEqual(plain(5, 9))
    expect(reread).toHaveBeenCalledTimes(1)
    expect(opened).toMatchObject({ status: 'rejected', reason: { message: 'frame_worker_lost' } })
    expect(warn).toHaveBeenCalledTimes(1)
  })

  it('a postMessage that throws: the job finishes on the page, its bytes intact', async () => {
    stubWorker('throw-post')
    const door = await freshDoor()
    const b = blob(3)
    expect(await door.openFramesOff(b.blobKey, b.frames())).toEqual(b.plaintext)
    expect(warn).toHaveBeenCalledTimes(1)
  })

  it('a Worker whose constructor throws: on the page, one warning', async () => {
    stubWorker('throw-construct')
    const door = await freshDoor()
    const b = blob(3)
    expect(await door.openFramesOff(b.blobKey, b.frames())).toEqual(b.plaintext)
    expect(await door.openFramesOff(b.blobKey, b.frames())).toEqual(b.plaintext)
    expect(warn).toHaveBeenCalledTimes(1)
  })

  it('no Worker global at all (vitest, node): on the page, silently', async () => {
    const door = await freshDoor()
    const b = blob(3)
    expect(await door.openFramesOff(b.blobKey, b.frames())).toEqual(b.plaintext)
    expect(warn).not.toHaveBeenCalled()
  })
})

describe('the upload pump seals through the Worker', () => {
  const SCOPE = '11111111-1111-1111-1111-111111111111'
  const OWNER = { ownerId: '33333333-3333-3333-3333-333333333333', role: 'ORIGINAL' as const }

  /** A store that keeps each PUT body; `deny(index, attempt)` answers 403 instead. */
  function store(deny: (index: number, attempt: number) => boolean = () => false) {
    const objects = new Map<number, Uint8Array>()
    const attempts = new Map<number, number>()
    vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit) => {
      const index = Number(url.split('/').pop())
      const attempt = (attempts.get(index) ?? 0) + 1
      attempts.set(index, attempt)
      if (deny(index, attempt)) return new Response('', { status: 403 })
      objects.set(index, new Uint8Array(init.body as Uint8Array))
      return new Response('', { status: 200 })
    }))
    return objects
  }
  const putsFrom = (from: number, count: number) =>
    Array.from({ length: count - from }, (_, k) => ({ index: from + k, url: `mem://up/${from + k}` }))

  async function upload(data: Uint8Array<ArrayBuffer>, objects: Map<number, Uint8Array>, signal?: AbortSignal) {
    const { planBlob, reserveAndPump, CHUNK_SIZE } = await import('./transport')
    const plan = planBlob(SCOPE, OWNER, v1.randomBytes(32), new Blob([data]))
    const signWindow = vi.fn(async (_uploadId: string, from: number) => putsFrom(from, plan.chunkCount))
    const run = reserveAndPump([plan], async () => [{ uploadId: 'up', puts: putsFrom(0, plan.chunkCount) }], signWindow, undefined, signal)
    const reopen = () => v1.concat(...Array.from({ length: plan.chunkCount }, (_, i) => v1.openFrame(plan.blobKey, i, plan.chunkCount, objects.get(i)!)))
    return { plan, run, signWindow, reopen, CHUNK_SIZE }
  }

  it('every frame is sealed in the Worker, and what the store holds opens back to the source', async () => {
    const worker = stubWorker()
    vi.resetModules()
    const objects = store()
    const data = plain(2 * 262144 + 11, 4) // three frames, a short last one
    const { plan, run, reopen } = await upload(data, objects)
    await run
    expect(plan.chunkCount).toBe(3)
    expect(worker.stats.posted.map((r) => r.op === 'seal-frame' && r.index).sort()).toEqual([0, 1, 2])
    expect(reopen()).toEqual(data)
    expect(warn).not.toHaveBeenCalled()
  })

  it('still re-signs and retries a PUT the store answers 403', async () => {
    stubWorker()
    vi.resetModules()
    const objects = store((index, attempt) => index === 1 && attempt === 1)
    const data = plain(2 * 262144 + 11, 6)
    const { run, signWindow, reopen } = await upload(data, objects)
    await run
    expect(signWindow).toHaveBeenCalledWith('up', 1)
    expect(reopen()).toEqual(data)
  })

  it('cancels on abort: AbortError, and no frame is sealed after it', async () => {
    const worker = stubWorker('real', { hold: true })
    vi.resetModules()
    const objects = store()
    const ctl = new AbortController()
    const { run } = await upload(plain(9 * 262144, 1), objects, ctl.signal)
    const outcome = run.then(() => 'resolved', (e: unknown) => e) // observed at once: it rejects while answers drain
    await tick()
    const posted = worker.stats.posted.length
    expect(posted).toBeGreaterThan(0)
    ctl.abort()
    for (let i = 0; i < posted; i++) {
      worker.release()
      await tick()
    }
    expect(await outcome).toMatchObject({ name: 'AbortError' })
    expect(worker.stats.posted).toHaveLength(posted)
  })
})
