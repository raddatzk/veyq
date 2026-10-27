// @vitest-environment node
//
// The frame worker's dispatcher, loaded for real (inbox #536): `self` is stubbed (its `message` listener captured, its
// `postMessage` recorded with the transfer list) and every op in FRAME_OPS is sent. The front door's own tests run
// against a fake Worker and the transport's tests run on the page thread (vitest has no Worker), so none of them would
// see a missing branch here or a byte this placement changed; this is the test that does. Each answer is compared with
// the page thread's result of the same `keys.ts` call: the placement changes nothing (the bytes are pinned to the v1
// vectors by `v1.conformance.test.ts`, and the worker runs exactly those functions).
//
// Bites: drop the `open-frames` branch and its case goes red with the refusal; drop the key's `fill(0)` and the
// zeroing case goes red; answer the run against its own length instead of `total` and the run case goes red.
import { describe, it, expect, vi, beforeAll, afterAll } from 'vitest'
import * as v1 from '@veyq/crypto/v1'
import { FRAME_OPS, type FrameMessage, type FrameOp, type FrameRequest } from './frameOps'
import { openFrameRun, openFrames } from './keys'

type Posted = { message: FrameMessage; transfer: Transferable[] }
let onMessage: ((event: { data: unknown }) => void) | undefined
const posted: Posted[] = []
let seq = 0

function send(request: Omit<FrameRequest, 'id'> | Record<string, unknown>): Posted {
  const id = (seq += 1)
  onMessage?.({ data: { id, ...request } })
  const reply = posted.find((p) => 'id' in p.message && p.message.id === id)
  if (!reply) throw new Error(`no reply to request ${id}`)
  return reply
}

const blobKey = v1.randomBytes(32)
const plain = (n: number, seed: number) => new Uint8Array(n).map((_, i) => (i * 31 + seed) % 251)
// Five frames of a blob, the last one short; opened as a run from index 2.
const parts = [plain(40, 1), plain(40, 2), plain(40, 3), plain(40, 4), plain(7, 5)]
const frames = parts.map((p, i) => v1.sealFrame(blobKey, i, parts.length, p, v1.randomBytes(12)))

// Keyed by FrameOp, so an op added to the list without a case here does not compile.
const CASES: Record<FrameOp, { request: () => Record<string, unknown>; check: (bytes: Uint8Array) => void }> = {
  'seal-frame': {
    request: () => ({ op: 'seal-frame', blobKey: blobKey.slice(), index: 3, total: 5, plaintext: parts[3].slice() }),
    // The nonce is the worker's own draw; with it, the page thread's seal of the same input is the same bytes.
    check: (bytes) => {
      expect(bytes).toEqual(v1.sealFrame(blobKey, 3, 5, parts[3], bytes.slice(0, 12)))
      expect(v1.openFrame(blobKey, 3, 5, bytes)).toEqual(parts[3])
    },
  },
  'open-frames': {
    request: () => ({ op: 'open-frames', blobKey: blobKey.slice(), total: 5, from: 2, frames: frames.slice(2).map((f) => f.slice()) }),
    check: (bytes) => {
      expect(bytes).toEqual(openFrameRun(blobKey, 5, 2, frames.slice(2)))
      expect(bytes).toEqual(v1.concat(parts[2], parts[3], parts[4]))
    },
  },
}

beforeAll(async () => {
  vi.stubGlobal('self', {
    addEventListener: (type: string, cb: (event: { data: unknown }) => void) => {
      if (type === 'message') onMessage = cb
    },
    postMessage: (message: FrameMessage, transfer: Transferable[] = []) => posted.push({ message, transfer }),
  })
  await import('./frames.worker')
})
afterAll(() => vi.unstubAllGlobals())

describe('frames.worker answers every op the front door sends', () => {
  it('says it is ready once its module ran, before any request', () => {
    expect(posted).toEqual([{ message: { ready: true }, transfer: [] }])
  })

  it.each(FRAME_OPS)('%s → ok, the page thread\'s bytes, the answer\'s buffer transferred', (op) => {
    const { message, transfer } = send(CASES[op].request())
    expect(message).toMatchObject({ id: seq, ok: true })
    const { bytes } = message as { bytes: Uint8Array }
    CASES[op].check(bytes)
    expect(transfer).toEqual([bytes.buffer]) // moved to the page, not copied
    expect(bytes.byteLength).toBe(bytes.buffer.byteLength) // and the buffer holds nothing but the answer
  })

  it('opens a whole blob as one run exactly as openFrames does', () => {
    const { message } = send({ op: 'open-frames', blobKey: blobKey.slice(), total: 5, from: 0, frames: frames.map((f) => f.slice()) })
    expect(message).toEqual({ id: seq, ok: true, bytes: openFrames(blobKey, frames) })
  })

  it('zeroes the K_blob it was handed once it answered, whatever the answer', () => {
    const sealKey = blobKey.slice()
    send({ op: 'seal-frame', blobKey: sealKey, index: 0, total: 1, plaintext: new Uint8Array(0) })
    const refusedKey = v1.randomBytes(32)
    send({ op: 'open-frames', blobKey: refusedKey, total: 5, from: 0, frames: frames.map((f) => f.slice()) })
    for (const key of [sealKey, refusedKey]) expect([...key].every((b) => b === 0)).toBe(true)
  })

  // The negatives, each beside the case that opens (above): a frame the AEAD refuses is `source`, not silence.
  it('answers a frame the AEAD refuses as a source failure, with nothing transferred', () => {
    const wrongKey = send({ op: 'open-frames', blobKey: v1.randomBytes(32), total: 5, from: 2, frames: frames.slice(2).map((f) => f.slice()) })
    expect(wrongKey).toMatchObject({ message: { id: seq, ok: false, kind: 'source' }, transfer: [] })
    // the run opened against its own length, not the whole blob's: the AAD binds `total`
    const ownLength = send({ op: 'open-frames', blobKey: blobKey.slice(), total: 3, from: 2, frames: frames.slice(2).map((f) => f.slice()) })
    expect(ownLength.message).toMatchObject({ ok: false, kind: 'source' })
  })

  it('refuses an op it has no branch for, by its sentinel', () => {
    expect(send({ op: 'no-such-op', blobKey: blobKey.slice() }).message).toEqual({
      id: seq,
      ok: false,
      kind: 'source',
      error: 'Error: frame_worker_unknown_op',
    })
  })
})
