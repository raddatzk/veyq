// @vitest-environment node
//
// The digest worker's front door (inbox #554) against a fake Worker, `frameWorker.test.ts`'s pattern: the fake records
// what is posted (through `structuredClone(msg, { transfer })`, as a real post clones it) and answers with the loop the
// real worker runs (`digest.ts` `checksumsHere`) over the `Blob` it was handed; that the real worker answers so is
// `digest.worker.test.ts`. Which Worker exists is set per case with `vi.stubGlobal`, and the module is re-imported per
// case: the Worker, its latch and its warning are module-level. `contentDigest` (`sharedBlob.ts`) is the door every
// production caller uses, so the cases go through it.
//
// Every negative has a positive control on the same predicate.
//
// Bites: drop the `cancel` post and "an abort" goes red; finish a latched job by rejecting it instead of on the page and
// "an error event mid-digest" goes red; retry a `source` answer on the page and "a source answer" goes red (the page
// reads the file); send a copy's payload to the Worker and its control goes red (it posts).
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import * as v1 from '@veyq/crypto/v1'
import { b64url } from '@veyq/crypto/base64'
import { DIGEST_SLICE, checksumsHere, type DigestRequest, type DigestResponse } from './digest'
import { framedPayload } from '../export/copySource'
import { crc32 } from '../export/crc32'

vi.mock('../../api/client', () => ({ blobApi: vi.fn(), tokenApi: vi.fn() }))

type Mode = 'real' | 'dies-after-post' | 'throw-construct' | 'throw-post' | 'refuses-read'
type Listener = (e: unknown) => void

/** The fake Worker. `hold` keeps answers back until `release()`, so a case can act while a digest is in the Worker. */
function stubWorker(mode: Mode = 'real', opts: { hold?: boolean } = {}) {
  const stats = { constructed: 0, posted: [] as DigestRequest[], transfers: [] as Transferable[][], terminated: 0 }
  const held: (() => void)[] = []
  const instances: FakeWorker[] = []
  class FakeWorker {
    private handlers: Record<string, Listener[]> = {}
    constructor() {
      if (mode === 'throw-construct') throw new Error('blocked by CSP')
      stats.constructed += 1
      instances.push(this)
    }
    addEventListener(type: string, cb: Listener): void {
      ;(this.handlers[type] ??= []).push(cb)
    }
    emit(type: string, event: unknown): void {
      this.handlers[type]?.forEach((cb) => cb(event))
    }
    postMessage(msg: DigestRequest, transfer: Transferable[] = []): void {
      if (mode === 'throw-post') throw new DOMException('could not be cloned', 'DataCloneError')
      const copy = structuredClone(msg, { transfer })
      stats.posted.push(copy)
      stats.transfers.push(transfer)
      if (copy.op !== 'digest') return
      if (mode === 'dies-after-post') return // answers nothing; the case fires `error` itself
      const reply = async () => this.emit('message', { data: await answer(copy) })
      if (opts.hold) held.push(() => void reply())
      else setTimeout(() => void reply(), 1)
    }
    terminate(): void {
      stats.terminated += 1
    }
  }
  /** What the real worker answers (`digest.worker.ts`): the same loop, over the Blob it was handed. */
  async function answer(req: Extract<DigestRequest, { op: 'digest' }>): Promise<DigestResponse> {
    if (mode === 'refuses-read') return { id: req.id, ok: false, kind: 'source', name: 'NotReadableError', message: 'changed on disk' }
    return { id: req.id, ok: true, ...(await checksumsHere(req.blob, req.crc32 === true)) }
  }
  vi.stubGlobal('Worker', FakeWorker as unknown as typeof Worker)
  return {
    stats,
    /** Answer the oldest held digest. */
    release: () => held.shift()?.(),
    /** Fire an event on the Worker the front door made. */
    emit: (type: string) => instances[0]?.emit(type, {}),
  }
}

async function freshDoor() {
  vi.resetModules()
  return import('./sharedBlob')
}

const tick = () => new Promise((r) => setTimeout(r, 5))
const random = (n: number) => {
  const out = new Uint8Array(n)
  for (let at = 0; at < n; at += 65536) out.set(v1.randomBytes(Math.min(65536, n - at)), at)
  return out
}
const whole = (bytes: Uint8Array) => b64url(v1.sha256Digest(bytes))
/** A Blob of `bytes` whose page-side reads are counted (the Worker reads its own clone, never this object). */
function spiedBlob(bytes: Uint8Array) {
  const blob = new Blob([bytes as BlobPart])
  return { blob, slice: vi.spyOn(blob, 'slice') }
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

describe('what crosses: the Blob, and nothing is transferred', () => {
  it('a Blob is posted with an empty transfer list, read in the Worker, and its answer resolves', async () => {
    const worker = stubWorker()
    const door = await freshDoor()
    const bytes = random(2 * DIGEST_SLICE + 5)
    const page = spiedBlob(bytes)
    expect(await door.contentDigest(page.blob)).toBe(whole(bytes))
    expect(worker.stats.posted).toHaveLength(1)
    expect(worker.stats.posted[0]).toMatchObject({ op: 'digest' })
    expect(worker.stats.transfers).toEqual([[]])
    expect(page.slice).not.toHaveBeenCalled() // the page read nothing: the Worker did
    expect(page.blob.size).toBe(bytes.length) // and the page still holds the Blob
    expect(warn).not.toHaveBeenCalled() // the control for the latch cases' one warning
  })

  it('control: a source that is not a Blob (a copy\'s payload) posts nothing and resolves to the same digest on the page', async () => {
    const worker = stubWorker()
    const door = await freshDoor()
    const bytes = random(2 * DIGEST_SLICE + 5)
    const frames = {
      frameSize: DIGEST_SLICE,
      chunkCount: 3,
      getPlaintextChunk: async (i: number) => bytes.slice(i * DIGEST_SLICE, (i + 1) * DIGEST_SLICE),
    }
    const payload = framedPayload(frames, { size: bytes.length, name: 'a.bin', type: '', lastModified: 0, path: 'a.bin' })
    expect(await door.contentDigest(payload)).toBe(whole(bytes))
    expect(worker.stats.constructed).toBe(0)
    expect(worker.stats.posted).toHaveLength(0)
  })
})

describe('an import\'s payload check goes through the same Worker (inbox #557)', () => {
  it('a ZIP entry\'s check posts crc32: true, reads nothing on the page and answers the raw SHA-256 and the CRC-32', async () => {
    const worker = stubWorker()
    const door = await freshDoor()
    const bytes = random(2 * DIGEST_SLICE + 5)
    const page = spiedBlob(bytes)
    const checked = await door.payloadChecksums(page.blob, true)
    expect(worker.stats.posted).toEqual([expect.objectContaining({ op: 'digest', crc32: true })])
    expect(page.slice, 'the Worker read it').not.toHaveBeenCalled()
    expect(checked).toEqual({ sha256: v1.sha256Digest(bytes), crc32: crc32(bytes) })
  })

  it('control: a directory\'s file asks no CRC-32 and gets none', async () => {
    const worker = stubWorker()
    const door = await freshDoor()
    const bytes = random(DIGEST_SLICE + 1)
    expect(await door.payloadChecksums(new Blob([bytes as BlobPart]), false)).toEqual({ sha256: v1.sha256Digest(bytes) })
    expect(worker.stats.posted[0]).not.toHaveProperty('crc32')
  })

  it('with no Worker the same pass runs on the page, to the same answer', async () => {
    const door = await freshDoor() // vitest: no Worker global
    const bytes = random(DIGEST_SLICE + 3)
    expect(await door.payloadChecksums(new Blob([bytes as BlobPart]), true)).toEqual({ sha256: v1.sha256Digest(bytes), crc32: crc32(bytes) })
  })
})

describe('an abort', () => {
  it('rejects AbortError at once while the digest is in the Worker, sends cancel, and drops the late answer', async () => {
    const worker = stubWorker('real', { hold: true })
    const door = await freshDoor()
    const ctl = new AbortController()
    const digest = door.contentDigest(new Blob([random(DIGEST_SLICE)]), ctl.signal)
    const outcome = digest.then(() => 'resolved', (e: unknown) => e)
    await tick()
    ctl.abort()
    expect(await outcome).toMatchObject({ name: 'AbortError' }) // nothing answered yet: the rejection did not wait
    const [sent, cancel] = worker.stats.posted
    expect(cancel).toEqual({ id: sent.id, op: 'cancel' })
    worker.release() // its answer arrives late, and is nobody's
    await tick()
    expect(worker.stats.posted).toHaveLength(2)
    expect(warn).not.toHaveBeenCalled()
  })

  it('control: the same digest, unaborted, resolves and sends no cancel', async () => {
    const worker = stubWorker('real', { hold: true })
    const door = await freshDoor()
    const bytes = random(DIGEST_SLICE)
    const digest = door.contentDigest(new Blob([bytes as BlobPart]), new AbortController().signal)
    await tick()
    worker.release()
    expect(await digest).toBe(whole(bytes))
    expect(worker.stats.posted.map((r) => r.op)).toEqual(['digest'])
  })

  it('an already aborted signal sends nothing', async () => {
    const worker = stubWorker()
    const door = await freshDoor()
    const ctl = new AbortController()
    ctl.abort()
    await expect(door.contentDigest(new Blob([random(8)]), ctl.signal)).rejects.toMatchObject({ name: 'AbortError' })
    expect(worker.stats.posted).toHaveLength(0)
  })
})

describe('a Worker that cannot run latches off and the digests finish on the page thread, with one warning', () => {
  it('an error event mid-digest: every outstanding digest finishes on the page from byte 0, to the right value', async () => {
    const worker = stubWorker('dies-after-post')
    const door = await freshDoor()
    const a = random(3 * DIGEST_SLICE + 1)
    const b = random(DIGEST_SLICE)
    const pa = spiedBlob(a)
    const pb = spiedBlob(b)
    const outstanding = Promise.all([door.contentDigest(pa.blob), door.contentDigest(pb.blob)])
    await tick()
    expect(worker.stats.posted).toHaveLength(2) // both in the Worker, neither answered
    worker.emit('error')
    expect(await outstanding).toEqual([whole(a), whole(b)])
    // the page read each from its first byte: the Worker's progress was its own and is gone
    expect(pa.slice.mock.calls.map((c) => c[0])).toEqual([0, 1, 2, 3].map((i) => i * DIGEST_SLICE))
    expect(pb.slice.mock.calls.map((c) => c[0])).toEqual([0])
    expect(warn).toHaveBeenCalledTimes(1)
    expect(worker.stats.terminated).toBe(1)

    // latched: a later digest runs on the page, no second Worker, no second warning
    expect(await door.contentDigest(new Blob([b as BlobPart]))).toBe(whole(b))
    expect(worker.stats.constructed).toBe(1)
    expect(warn).toHaveBeenCalledTimes(1)
  })

  it('a messageerror latches the same way', async () => {
    const worker = stubWorker('dies-after-post')
    const door = await freshDoor()
    const bytes = random(10)
    const digest = door.contentDigest(new Blob([bytes as BlobPart]))
    await tick()
    worker.emit('messageerror')
    expect(await digest).toBe(whole(bytes))
    expect(warn).toHaveBeenCalledTimes(1)
  })

  it('a postMessage that throws: the digest finishes on the page', async () => {
    stubWorker('throw-post')
    const door = await freshDoor()
    const bytes = random(DIGEST_SLICE + 3)
    expect(await door.contentDigest(new Blob([bytes as BlobPart]))).toBe(whole(bytes))
    expect(warn).toHaveBeenCalledTimes(1)
  })

  it('a Worker whose constructor throws: on the page, one warning', async () => {
    stubWorker('throw-construct')
    const door = await freshDoor()
    const bytes = random(7)
    expect(await door.contentDigest(new Blob([bytes as BlobPart]))).toBe(whole(bytes))
    expect(await door.contentDigest(new Blob([bytes as BlobPart]))).toBe(whole(bytes))
    expect(warn).toHaveBeenCalledTimes(1)
  })

  it('no Worker global at all (vitest, node): on the page, silently', async () => {
    const door = await freshDoor()
    const bytes = random(7)
    expect(await door.contentDigest(new Blob([bytes as BlobPart]))).toBe(whole(bytes))
    expect(warn).not.toHaveBeenCalled()
  })
})

describe('a read the browser refuses', () => {
  it('a source answer rejects with a DOMException of that name, and the page does not retry', async () => {
    const worker = stubWorker('refuses-read')
    const door = await freshDoor()
    const page = spiedBlob(random(DIGEST_SLICE))
    const outcome = await door.contentDigest(page.blob).then(() => 'resolved', (e: unknown) => e)
    expect(outcome).toBeInstanceOf(DOMException)
    expect(outcome).toMatchObject({ name: 'NotReadableError', message: 'changed on disk' })
    expect(page.slice).not.toHaveBeenCalled() // no page-thread retry
    expect(warn).not.toHaveBeenCalled() // the input's failure, not the Worker's: nothing latched
    expect(worker.stats.constructed).toBe(1)
  })

  it('control: the same Blob on a healthy Worker resolves', async () => {
    stubWorker()
    const door = await freshDoor()
    const bytes = random(DIGEST_SLICE)
    const page = spiedBlob(bytes)
    expect(await door.contentDigest(page.blob)).toBe(whole(bytes))
  })
})
