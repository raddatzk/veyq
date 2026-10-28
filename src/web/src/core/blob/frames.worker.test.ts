// @vitest-environment node
//
// The frame worker's dispatcher, loaded for real (inbox #536, #220): `self` is stubbed (its `message` listener captured,
// its `postMessage` recorded with the transfer list), `fetch` is stubbed as the store, and every op in FRAME_OPS is sent.
// The front door's own tests run against a fake Worker and the transport's tests run on the page thread (vitest has no
// Worker), so none of them would see a missing branch here or a byte this placement changed; this is the test that does.
// Each answer is compared with the page thread's result of the same `keys.ts` call: the placement changes nothing (the
// bytes are pinned to the v1 vectors by `v1.conformance.test.ts`, and the worker runs exactly those functions).
//
// Bites: drop the `open-frames` branch and its case goes red with the refusal; drop the key's `fill(0)` and the
// zeroing case goes red; answer the run against its own length instead of `total` and the run case goes red; drop the
// `cancel` branch and the cancel case goes red (the PUT is never aborted); answer `seal-put` with its frame and its
// case goes red (the bytes stay in the worker).
import { describe, it, expect, vi, beforeAll, afterAll, beforeEach } from 'vitest'
import * as v1 from '@veyq/crypto/v1'
import { FRAME_OPS, type FrameMessage, type FrameOp, type FrameRequest } from './frameOps'
import { PUT_CONTENT_TYPE } from './framePut'
import { openFrameRun, openFrames } from './keys'

type Posted = { message: FrameMessage; transfer: Transferable[] }
let onMessage: ((event: { data: unknown }) => void) | undefined
const posted: Posted[] = []
let seq = 0

/** What the stubbed store saw: each PUT's URL, Content-Type, body and signal; `answer` decides its response. */
type Put = { url: string; contentType: string | null; body: Uint8Array; blob: boolean; signal: AbortSignal | undefined }
const puts: Put[] = []
let answer: (put: Put) => Promise<Response> = async () => new Response('', { status: 200 })

const reply = (id: number) => posted.find((p) => 'id' in p.message && p.message.id === id)

/** Send one request and wait for its answer (a `seal-put` answers after its PUT, a few ticks later). */
async function send(request: Omit<FrameRequest, 'id'> | Record<string, unknown>): Promise<Posted> {
  const id = (seq += 1)
  onMessage?.({ data: { id, ...request } })
  for (let i = 0; i < 50 && !reply(id); i++) await new Promise((r) => setTimeout(r, 1))
  const found = reply(id)
  if (!found) throw new Error(`no reply to request ${id}`)
  return found
}

const blobKey = v1.randomBytes(32)
const plain = (n: number, seed: number) => new Uint8Array(n).map((_, i) => (i * 31 + seed) % 251)
// Five frames of a blob, the last one short; opened as a run from index 2.
const parts = [plain(40, 1), plain(40, 2), plain(40, 3), plain(40, 4), plain(7, 5)]
const frames = parts.map((p, i) => v1.sealFrame(blobKey, i, parts.length, p, v1.randomBytes(12)))

/** The store's copy of a sealed frame: the page thread's seal of the same input with the worker's own nonce. */
function expectSealedOf(bytes: Uint8Array, index: number, total: number, plaintext: Uint8Array) {
  expect(bytes).toEqual(v1.sealFrame(blobKey, index, total, plaintext, bytes.slice(0, 12)))
  expect(v1.openFrame(blobKey, index, total, bytes)).toEqual(plaintext)
}

// Keyed by FrameOp, so an op added to the list without a case here does not compile.
const CASES: Record<FrameOp, () => Promise<void>> = {
  'open-frames': async () => {
    const { message, transfer } = await send({ op: 'open-frames', blobKey: blobKey.slice(), total: 5, from: 2, frames: frames.slice(2).map((f) => f.slice()) })
    const { bytes } = message as { bytes: Uint8Array }
    expect(bytes).toEqual(openFrameRun(blobKey, 5, 2, frames.slice(2)))
    expect(bytes).toEqual(v1.concat(parts[2], parts[3], parts[4]))
    expect(transfer).toEqual([bytes.buffer]) // moved to the page, not copied
    expect(bytes.byteLength).toBe(bytes.buffer.byteLength) // and the buffer holds nothing but the answer
  },
  'seal-put': async () => {
    // The chunk is a `Blob` handle the worker reads itself: the page sends no plaintext and gets no frame back. The
    // nonce is the worker's own draw; with it, the page thread's seal of the same input is the same bytes.
    const { message, transfer } = await send({
      op: 'seal-put', blobKey: blobKey.slice(), index: 1, total: 5, chunk: new Blob([parts[1]]), url: 'https://store.test/b/1?X-Amz-Signature=s',
    })
    expect(message).toEqual({ id: seq, ok: true, status: 200 })
    expect(transfer).toEqual([])
    expect(puts).toHaveLength(1)
    // A Blob body: a byte-array body cost the sending thread about seven times as much (framePut.ts).
    expect(puts[0]).toMatchObject({ url: 'https://store.test/b/1?X-Amz-Signature=s', contentType: PUT_CONTENT_TYPE, blob: true })
    expectSealedOf(puts[0].body, 1, 5, parts[1])
  },
  cancel: async () => {
    // A PUT held by the store until its signal aborts; the cancel names it by its id.
    let release: (r: Response) => void = () => {}
    answer = (put) => new Promise<Response>((resolve, reject) => {
      release = resolve
      put.signal?.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')))
    })
    const id = (seq += 1)
    onMessage?.({ data: { id, op: 'seal-put', blobKey: blobKey.slice(), index: 0, total: 5, chunk: new Blob([parts[0]]), url: 'https://store.test/b/0' } })
    for (let i = 0; i < 50 && puts.length === 0; i++) await new Promise((r) => setTimeout(r, 1))
    expect(puts).toHaveLength(1)
    expect(puts[0].signal?.aborted).toBe(false)
    onMessage?.({ data: { id: (seq += 1), op: 'cancel', target: id } })
    expect(puts[0].signal?.aborted).toBe(true)
    expect(reply(seq)).toBeUndefined() // a cancel is no job: it has no answer
    release(new Response('', { status: 200 }))
  },
}

beforeAll(async () => {
  vi.stubGlobal('self', {
    addEventListener: (type: string, cb: (event: { data: unknown }) => void) => {
      if (type === 'message') onMessage = cb
    },
    postMessage: (message: FrameMessage, transfer: Transferable[] = []) => posted.push({ message, transfer }),
  })
  vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit) => {
    const blob = init.body instanceof Blob
    const put = {
      url,
      contentType: new Headers(init.headers).get('Content-Type'),
      body: blob ? new Uint8Array(await (init.body as Blob).arrayBuffer()) : new Uint8Array(init.body as Uint8Array),
      blob,
      signal: init.signal ?? undefined,
    }
    expect(init.method).toBe('PUT')
    puts.push(put)
    return answer(put)
  }))
  await import('./frames.worker')
})
beforeEach(() => {
  puts.length = 0
  answer = async () => new Response('', { status: 200 })
})
afterAll(() => vi.unstubAllGlobals())

describe('frames.worker answers every op the front door sends', () => {
  it('says it is ready once its module ran, before any request', () => {
    expect(posted[0]).toEqual({ message: { ready: true }, transfer: [] })
  })

  it.each(FRAME_OPS)('%s', async (op) => {
    await CASES[op]()
  })

  it('opens a whole blob as one run exactly as openFrames does', async () => {
    const { message } = await send({ op: 'open-frames', blobKey: blobKey.slice(), total: 5, from: 0, frames: frames.map((f) => f.slice()) })
    expect(message).toEqual({ id: seq, ok: true, bytes: openFrames(blobKey, frames) })
  })

  it('zeroes the K_blob it was handed once it answered, whatever the answer', async () => {
    const openKey = blobKey.slice()
    await send({ op: 'open-frames', blobKey: openKey, total: 5, from: 0, frames: frames.map((f) => f.slice()) })
    const refusedKey = v1.randomBytes(32)
    await send({ op: 'open-frames', blobKey: refusedKey, total: 5, from: 0, frames: frames.map((f) => f.slice()) })
    const putKey = blobKey.slice()
    await send({ op: 'seal-put', blobKey: putKey, index: 4, total: 5, chunk: parts[4].slice(), url: 'https://store.test/b/4' })
    answer = async () => {
      throw new TypeError('Failed to fetch')
    }
    const failedKey = blobKey.slice()
    await send({ op: 'seal-put', blobKey: failedKey, index: 4, total: 5, chunk: parts[4].slice(), url: 'https://store.test/b/4' })
    for (const key of [openKey, refusedKey, putKey, failedKey]) expect([...key].every((b) => b === 0)).toBe(true)
  })

  it('seals a chunk handed over as bytes (a source that is no Blob) exactly as one it reads from a Blob', async () => {
    const { message } = await send({ op: 'seal-put', blobKey: blobKey.slice(), index: 4, total: 5, chunk: parts[4].slice(), url: 'https://store.test/b/4' })
    expect(message).toEqual({ id: seq, ok: true, status: 200 })
    expectSealedOf(puts[0].body, 4, 5, parts[4])
  })

  it('reads the store\'s empty answer to its end, so Chromium does not report the stored chunk as aborted', async () => {
    const answers: Response[] = []
    answer = async () => {
      const r = new Response('', { status: 200 })
      answers.push(r)
      return r
    }
    await send({ op: 'seal-put', blobKey: blobKey.slice(), index: 0, total: 5, chunk: new Blob([parts[0]]), url: 'https://store.test/b/0' })
    expect(answers.map((r) => r.bodyUsed)).toEqual([true])
  })

  // The negatives, each beside the case that succeeds (above).
  it('answers a store refusal with its status, for the page to re-sign (403) or fail on', async () => {
    answer = async () => new Response('', { status: 403 })
    expect((await send({ op: 'seal-put', blobKey: blobKey.slice(), index: 0, total: 5, chunk: new Blob([parts[0]]), url: 'u' })).message)
      .toEqual({ id: seq, ok: true, status: 403 })
  })

  it('answers a PUT that failed in the network as a network failure, not as a frame the AEAD refused', async () => {
    answer = async () => {
      throw new TypeError('Failed to fetch')
    }
    expect((await send({ op: 'seal-put', blobKey: blobKey.slice(), index: 0, total: 5, chunk: new Blob([parts[0]]), url: 'u' })).message)
      .toEqual({ id: seq, ok: false, kind: 'network', error: 'Failed to fetch' })
  })

  it('answers a frame the AEAD refuses as a source failure, with nothing transferred', async () => {
    const wrongKey = await send({ op: 'open-frames', blobKey: v1.randomBytes(32), total: 5, from: 2, frames: frames.slice(2).map((f) => f.slice()) })
    expect(wrongKey).toMatchObject({ message: { id: seq, ok: false, kind: 'source' }, transfer: [] })
    // the run opened against its own length, not the whole blob's: the AAD binds `total`
    const ownLength = await send({ op: 'open-frames', blobKey: blobKey.slice(), total: 3, from: 2, frames: frames.slice(2).map((f) => f.slice()) })
    expect(ownLength.message).toMatchObject({ ok: false, kind: 'source' })
  })

  it('refuses an op it has no branch for, by its sentinel', async () => {
    expect((await send({ op: 'no-such-op', blobKey: blobKey.slice() })).message).toEqual({
      id: seq,
      ok: false,
      kind: 'source',
      error: 'Error: frame_worker_unknown_op',
    })
  })
})
