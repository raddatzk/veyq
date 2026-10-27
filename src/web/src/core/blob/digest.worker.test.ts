// @vitest-environment node
//
// The digest worker's dispatcher, loaded for real (inbox #554): `self` is stubbed (its `message` listener captured, its
// `postMessage` recorded) and every op in DIGEST_OPS is sent. The front door's own tests run against a fake Worker and
// every other suite hashes on the page thread (vitest has no Worker), so none of them would see a missing branch here
// or a digest this placement changed; this is the test that does. Each answer is compared with the page thread's loop
// (`digestHere`) and with `b64url(v1.sha256Digest(whole))`, the digest computed in one piece by a different path.
//
// Bites: answer a constant digest (e.g. hash a fresh empty buffer per slice) and the same-size control goes red; drop
// the `cancel` branch and "a cancel mid-digest" goes red (the digest reads on and answers ok); drop the `digest` branch
// and every size answers the refusal.
import { afterAll, beforeAll, describe, expect, it, vi } from 'vitest'
import * as v1 from '@veyq/crypto/v1'
import { b64url } from '@veyq/crypto/base64'
import { DIGEST_OPS, DIGEST_SLICE, digestHere, type DigestOp, type DigestResponse } from './digest'

let onMessage: ((event: { data: unknown }) => void) | undefined
const posted: DigestResponse[] = []
let seq = 0

const tick = () => new Promise((r) => setTimeout(r, 0))

/** Send one request and wait until the worker answered it (its handler is async: it reads the blob slice by slice). */
async function send(request: Record<string, unknown>, id = (seq += 1)): Promise<DigestResponse> {
  onMessage?.({ data: { id, ...request } })
  for (let i = 0; i < 500; i++) {
    const reply = posted.find((p) => p.id === id)
    if (reply) return reply
    await tick()
  }
  throw new Error(`no reply to request ${id}`)
}

const random = (n: number) => {
  const out = new Uint8Array(n)
  for (let at = 0; at < n; at += 65536) out.set(v1.randomBytes(Math.min(65536, n - at)), at)
  return out
}
const whole = (bytes: Uint8Array) => b64url(v1.sha256Digest(bytes))

/** A source whose reads wait until released, so a case can act while the worker is between two slices. */
function heldSource(bytes: Uint8Array) {
  const reads: number[] = []
  const waiting: (() => void)[] = []
  const source = {
    size: bytes.length,
    type: '',
    slice(start: number, end: number) {
      return {
        arrayBuffer: () =>
          new Promise<ArrayBuffer>((resolve) => {
            reads.push(start)
            waiting.push(() => resolve(bytes.slice(start, end).buffer))
          }),
      }
    },
  }
  return { source, reads, release: () => waiting.shift()?.() }
}

beforeAll(async () => {
  vi.stubGlobal('self', {
    addEventListener: (type: string, cb: (event: { data: unknown }) => void) => {
      if (type === 'message') onMessage = cb
    },
    postMessage: (message: DigestResponse) => posted.push(message),
  })
  await import('./digest.worker')
})
afterAll(() => vi.unstubAllGlobals())

// Keyed by DigestOp, so an op added to the list without a case here does not compile.
const COVERED: Record<DigestOp, string> = {
  digest: 'the sizes below and the same-size control',
  cancel: 'a cancel mid-digest',
}

describe('digest.worker answers every op the front door sends', () => {
  it('has a case for every op', () => {
    expect(Object.keys(COVERED).sort()).toEqual([...DIGEST_OPS].sort())
  })

  const sizes: [string, number][] = [
    ['0 bytes', 0],
    ['1 byte', 1],
    ['exactly one slice (256 KiB)', DIGEST_SLICE],
    ['one slice and one byte', DIGEST_SLICE + 1],
    ['about 1 MiB of random bytes', 4 * DIGEST_SLICE + 12345],
  ]
  it.each(sizes)('%s → the page thread\'s loop\'s digest, which is the SHA-256 of the whole', async (_, size) => {
    const bytes = random(size)
    const reply = await send({ op: 'digest', blob: new Blob([bytes]) })
    expect(reply).toEqual({ id: seq, ok: true, digest: whole(bytes) })
    expect(await digestHere(new Blob([bytes]))).toBe(whole(bytes))
    expect(whole(bytes)).toMatch(/^[A-Za-z0-9_-]{43}$/)
  })

  it('control for a constant digest: two different inputs of the same size answer different digests', async () => {
    const a = random(DIGEST_SLICE + 1)
    const b = a.slice()
    b[DIGEST_SLICE] ^= 1 // differs in the second slice only, so a digest of the first slice alone matches too
    const [ra, rb] = [await send({ op: 'digest', blob: new Blob([a]) }), await send({ op: 'digest', blob: new Blob([b]) })]
    expect(ra).toMatchObject({ ok: true, digest: whole(a) })
    expect(rb).toMatchObject({ ok: true, digest: whole(b) })
    expect((ra as { digest: string }).digest).not.toBe((rb as { digest: string }).digest)
  })

  it('a cancel mid-digest stops it at its next slice and answers AbortError (control: released, it reads every slice)', async () => {
    const bytes = random(4 * DIGEST_SLICE)
    const held = heldSource(bytes)
    const id = (seq += 1)
    onMessage?.({ data: { id, op: 'digest', blob: held.source } })
    await tick()
    held.release() // slice 0 read
    await tick()
    expect(held.reads).toEqual([0, DIGEST_SLICE]) // slice 1 is being read
    onMessage?.({ data: { id, op: 'cancel' } })
    held.release()
    for (let i = 0; i < 20 && !posted.some((p) => p.id === id); i++) await tick()
    expect(posted.find((p) => p.id === id)).toMatchObject({ ok: false, kind: 'source', name: 'AbortError' })
    expect(held.reads).toEqual([0, DIGEST_SLICE]) // nothing read after the cancel

    // control: the same source, never cancelled, reads all four slices and answers the digest
    const control = heldSource(bytes)
    const done = send({ op: 'digest', blob: control.source })
    for (let i = 0; i < 4; i++) {
      await tick()
      control.release()
    }
    expect(await done).toMatchObject({ ok: true, digest: whole(bytes) })
    expect(control.reads).toEqual([0, 1, 2, 3].map((i) => i * DIGEST_SLICE))
  })

  it('a cancel for no running digest answers nothing and harms nothing', async () => {
    const before = posted.length
    onMessage?.({ data: { id: 999_999, op: 'cancel' } })
    await tick()
    expect(posted).toHaveLength(before)
  })

  it('a read the source refuses answers its error\'s name, as a source failure', async () => {
    const refusing = {
      size: 10,
      type: '',
      slice: () => ({ arrayBuffer: () => Promise.reject(new DOMException('changed on disk', 'NotReadableError')) }),
    }
    expect(await send({ op: 'digest', blob: refusing })).toEqual({
      id: seq, ok: false, kind: 'source', name: 'NotReadableError', message: 'changed on disk',
    })
  })

  it('refuses an op it has no branch for, by its sentinel', async () => {
    expect(await send({ op: 'no-such-op' })).toEqual({
      id: seq, ok: false, kind: 'source', name: 'Error', message: 'digest_worker_unknown_op',
    })
  })
})
