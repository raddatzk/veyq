import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'

vi.mock('../../api/client', () => ({ blobApi: vi.fn(), tokenApi: vi.fn() }))

import {
  CHUNK_SIZE,
  PUMP_IN_FLIGHT,
  planBlob,
  reserveAndPump,
  authedReserve,
  authedWindow,
  windowPuts,
  openBlob,
  makeChunkProvider,
} from './transport'
import { blobApi } from '../../api/client'
import type { BlobApi, BlobUploadRequestItem, PresignedUrl } from '../../api/generated/blob'
import { PLAINTEXT_CHUNK_SIZE } from '../../sw/range'
import { fakeApi } from '../../test/apiMock'
import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'

const SCOPE_ID = '11111111-1111-1111-1111-111111111111'
const OWNER = { ownerId: '33333333-3333-3333-3333-333333333333', role: 'ORIGINAL' as const }

beforeEach(() => vi.clearAllMocks())
afterEach(() => vi.unstubAllGlobals())

describe('the frame size', () => {
  it('is the one the Service Worker range layer assembles by (sw/range.ts keeps a literal so its bundle stays free of this module)', () => {
    expect(CHUNK_SIZE).toBe(PLAINTEXT_CHUNK_SIZE)
  })
})

/**
 * Core's authed blob control plane as an in-process store: `startBlobUploads` signs the first `window` chunks,
 * `presignBlobUploadWindows` each next range, `presignBlobDownloads` the whole blob; `fetch` keeps every PUT body
 * and serves it back on the matching GET. `events` logs window requests and PUTs in order.
 */
function fakeBlobStore(window = Number.POSITIVE_INFINITY) {
  const objects = new Map<string, Uint8Array>()
  const sessions = new Map<string, { chunkCount: number; blobId: string }>()
  const uploadOfBlob = new Map<string, string>()
  const declared: BlobUploadRequestItem[] = []
  const events: string[] = []
  const inFlight = { now: 0, max: 0 }
  let n = 0
  const putsOf = (uploadId: string, from: number, chunkCount: number): PresignedUrl[] =>
    Array.from({ length: Math.max(0, Math.min(from + window, chunkCount) - from) }, (_, k) => ({ index: from + k, url: `mem://${uploadId}/${from + k}` }))
  fakeApi(blobApi, {
    startBlobUploads: vi.fn<BlobApi['startBlobUploads']>(async ({ blobUploadsBatchRequest }) => ({
      results: blobUploadsBatchRequest.items.map((item) => {
        const uploadId = `up-${n++}`
        declared.push(item)
        sessions.set(uploadId, { chunkCount: item.chunkCount, blobId: item.blobId })
        uploadOfBlob.set(item.blobId, uploadId)
        return { uploadId, presignedPuts: putsOf(uploadId, 0, item.chunkCount), error: null }
      }),
    })),
    presignBlobUploadWindows: vi.fn<BlobApi['presignBlobUploadWindows']>(async ({ blobUploadWindowsRequest }) => ({
      results: blobUploadWindowsRequest.items.map(({ uploadId, fromChunk }) => {
        events.push(`window:${fromChunk}`)
        return { presignedPuts: putsOf(uploadId, fromChunk!, sessions.get(uploadId)!.chunkCount), error: null }
      }),
    })),
    presignBlobDownloads: vi.fn<BlobApi['presignBlobDownloads']>(async ({ blobDownloadsBatchRequest }) => {
      const uploadId = uploadOfBlob.get(blobDownloadsBatchRequest.items[0].blobId)!
      const { chunkCount } = sessions.get(uploadId)!
      // served last-first: the read side orders by the declared index, not by arrival
      const gets = Array.from({ length: chunkCount }, (_, i) => ({ index: i, url: `mem://${uploadId}/${i}` })).reverse()
      return { results: [{ presignedGets: gets, error: null }] }
    }),
  })
  vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
    if (init?.method === 'PUT') {
      events.push(`put:${url.split('/').pop()}`)
      if (new Headers(init.headers).get('Content-Type') !== 'application/octet-stream') return { ok: false, status: 403 } as Response
      inFlight.max = Math.max(inFlight.max, ++inFlight.now)
      await new Promise((r) => setTimeout(r, 2))
      inFlight.now--
      objects.set(url, new Uint8Array(init.body as Uint8Array))
      return { ok: true, status: 200 } as Response
    }
    const body = objects.get(url)
    if (!body) return { ok: false, status: 404 } as Response
    return { ok: true, status: 200, arrayBuffer: async () => body.buffer } as unknown as Response
  }))
  return { declared, events, inFlight, objects }
}

describe('planBlob', () => {
  it('shapes a blob from its size alone and wraps a fresh K_blob bound to (scope, owner, blob, role)', () => {
    const key = v1.randomBytes(32)
    const plan = planBlob(SCOPE_ID, OWNER, key, new Blob([new Uint8Array(2 * CHUNK_SIZE + 1)]))
    expect(plan.chunkCount).toBe(3)
    expect(plan.byteSize).toBe(2 * CHUNK_SIZE + 1 + 3 * v1.FRAME_OVERHEAD)
    const ctx = { purpose: 'blob-key', scopeId: SCOPE_ID, ownerId: OWNER.ownerId, blobId: plan.blobId, role: 'ORIGINAL' } as const
    expect(v1.open(ctx, key, fromB64std(plan.wrappedBlobKey))).toEqual(plan.blobKey)
    expect(() => v1.open({ ...ctx, role: 'COVER' }, key, fromB64std(plan.wrappedBlobKey))).toThrow(v1.OpenRefused)
    // an empty source is one empty frame
    expect(planBlob(SCOPE_ID, OWNER, key, new Blob([])).chunkCount).toBe(1)
  })
})

describe('reserveAndPump over the authed control plane', () => {
  it('declares each plan, pumps it window by window within PUMP_IN_FLIGHT, and reads back the same bytes', async () => {
    const store = fakeBlobStore(5)
    const key = v1.randomBytes(32)
    const data = new Uint8Array(11 * CHUNK_SIZE + 5).map((_, i) => i % 251)
    const small = new Uint8Array([7, 8, 9])
    const plans = [planBlob(SCOPE_ID, OWNER, key, new Blob([data])), planBlob(SCOPE_ID, { ...OWNER, role: 'THUMBNAIL' }, key, new Blob([small]))]
    const progress: number[] = []

    const uploadIds = await reserveAndPump(plans, (d) => authedReserve(SCOPE_ID, d), authedWindow(SCOPE_ID), (f) => progress.push(f))

    expect(uploadIds).toEqual(['up-0', 'up-1'])
    expect(store.declared.map((d) => [d.blobId, d.ownerId, d.role, d.chunkCount, d.byteSize, d.wrappedBlobKey])).toEqual(
      plans.map((p) => [p.blobId, p.ownerId, p.role, p.chunkCount, p.byteSize, p.wrappedBlobKey]),
    )
    expect(store.events.filter((e) => e.startsWith('window:'))).toEqual(['window:5', 'window:10'])
    expect(store.inFlight.max).toBeLessThanOrEqual(PUMP_IN_FLIGHT)
    expect(store.inFlight.max).toBeGreaterThan(1)
    expect(progress[0]).toBe(0)
    expect(progress.at(-1)).toBe(1)
    expect(progress).toEqual([...progress].sort((a, b) => a - b))
    // operator-blind: what the store holds is ciphertext frames, which open back to the source
    expect(store.objects.get('mem://up-0/0')!.length).toBe(CHUNK_SIZE + v1.FRAME_OVERHEAD)
    expect(await openBlob(SCOPE_ID, key, plans[0])).toEqual(data)
    expect(await openBlob(SCOPE_ID, key, plans[1])).toEqual(small)
  })

  it('a pre-aborted signal throws AbortError before anything is reserved', async () => {
    const reserve = vi.fn(async () => [])
    const ctl = new AbortController()
    ctl.abort()
    const plan = planBlob(SCOPE_ID, OWNER, v1.randomBytes(32), new Blob([new Uint8Array(1)]))
    await expect(reserveAndPump([plan], reserve, vi.fn(), undefined, ctl.signal)).rejects.toMatchObject({ name: 'AbortError' })
    expect(reserve).not.toHaveBeenCalled()
  })

  it('windowPuts hands back the PUTs, or the per-item error', () => {
    expect(windowPuts({ presignedPuts: [{ index: 3, url: 'u' }] })).toEqual([{ index: 3, url: 'u' }])
    expect(() => windowPuts({ error: 'range_invalid' })).toThrow('range_invalid')
    expect(() => windowPuts({ presignedPuts: null })).toThrow('store_unavailable')
    expect(() => windowPuts(undefined)).toThrow('upload_failed')
  })
})

// Moved unchanged from `features/memory/media.test.ts` with the code it tests (cr-files-application B1).
describe('makeChunkProvider — the page-side plaintext chunk seam (Slice 2, ADR 0047)', () => {
  // The SW never sees a key; it asks the page for one chunk's plaintext by index. Build a real chunked-AEAD blob
  // (K_blob wrapped under K_node, each frame bound to `index‖total`) so the seam runs the true seal/open path.
  // The wrap is sealed with a HARDCODED blob-key context (== transport.ts blobKeyContext); if the purpose or a bound
  // field ever changes, this test breaks loudly.
  const BLOB = { blobId: '5c09e000-0000-4000-8000-000000000b01', ownerId: '5c09e000-0000-4000-8000-000000000b02', role: 'PREVIEW' as const }

  function buildBlob(memoryKey: Uint8Array, plaintextChunks: Uint8Array[]) {
    const blobKey = v1.randomBytes(32)
    const total = plaintextChunks.length
    const frames = plaintextChunks.map((pt, i) => v1.sealFrame(blobKey, i, total, pt, v1.randomBytes(12)))
    const wrappedBlobKey = b64std(v1.seal({ purpose: 'blob-key', scopeId: SCOPE_ID, ...BLOB }, memoryKey, blobKey))
    const byteSize = frames.reduce((n, f) => n + f.length, 0)
    return { blobKey, frames, ref: { ...BLOB, chunkCount: total, byteSize, wrappedBlobKey } }
  }

  /** A fetch stub that serves `frames[i]` at URL `slot/<n>/<i>`, where `slot` advances on each re-presign so a
   *  stale-URL 403 can be simulated for the first slot only. `status403For` names the slots that answer 403. */
  function storeFor(frames: Uint8Array[], status403For: Set<number> = new Set()) {
    const fetchStub = vi.fn(async (url: string) => {
      const m = /^slot\/(\d+)\/(\d+)$/.exec(url)!
      const [slot, idx] = [Number(m[1]), Number(m[2])]
      if (status403For.has(slot)) return { ok: false, status: 403 } as Response
      return { ok: true, status: 200, arrayBuffer: async () => frames[idx].buffer } as unknown as Response
    })
    let slot = 0
    const presignAll = vi.fn(async () => frames.map((_, i) => `slot/${slot}/${i}`))
    // capture the slot AFTER each presign so a re-presign moves to a fresh (non-403) slot
    const presignAdvancing = vi.fn(async () => {
      const urls = frames.map((_, i) => `slot/${slot}/${i}`)
      slot++
      return urls
    })
    return { fetchStub, presignAll, presignAdvancing }
  }

  it('round-trips each chunk to its plaintext and exposes chunkCount + byteSize', async () => {
    const memoryKey = v1.randomBytes(32)
    const chunks = [new Uint8Array([1, 2, 3]), new Uint8Array([4, 5]), new Uint8Array([6, 7, 8, 9])]
    const { frames, ref } = buildBlob(memoryKey, chunks)
    const { fetchStub, presignAll } = storeFor(frames)
    vi.stubGlobal('fetch', fetchStub)

    const p = makeChunkProvider(SCOPE_ID, memoryKey, ref, presignAll)
    expect(p.chunkCount).toBe(3)
    expect(p.byteSize).toBe(ref.byteSize)
    for (let i = 0; i < chunks.length; i++) {
      expect(Array.from(await p.getPlaintextChunk(i))).toEqual(Array.from(chunks[i]))
    }
  })

  it('refuses a ref whose wrap was sealed for another slot — before any presign (ADR 0072)', async () => {
    const memoryKey = v1.randomBytes(32)
    const { ref } = buildBlob(memoryKey, [new Uint8Array([1])])
    const presignAll = vi.fn(async () => [])
    expect(() => makeChunkProvider(SCOPE_ID, memoryKey, { ...ref, role: 'ORIGINAL' }, presignAll)).toThrow(v1.OpenRefused)
    expect(() => makeChunkProvider(SCOPE_ID, memoryKey, { ...ref, ownerId: crypto.randomUUID() }, presignAll)).toThrow(v1.OpenRefused)
    expect(presignAll).not.toHaveBeenCalled()
  })

  it('presigns the WHOLE blob only once and reuses the cache across chunk reads', async () => {
    const memoryKey = v1.randomBytes(32)
    const { frames, ref } = buildBlob(memoryKey, [new Uint8Array([1]), new Uint8Array([2]), new Uint8Array([3])])
    const { fetchStub, presignAll } = storeFor(frames)
    vi.stubGlobal('fetch', fetchStub)

    const p = makeChunkProvider(SCOPE_ID, memoryKey, ref, presignAll)
    await p.getPlaintextChunk(0)
    await p.getPlaintextChunk(2)
    await p.getPlaintextChunk(1)
    expect(presignAll).toHaveBeenCalledTimes(1) // one whole-blob presign backs every chunk (no per-chunk presign)
  })

  it('binds the WHOLE blob chunkCount as the AAD total — a range-subset total goes red (the trap)', async () => {
    const memoryKey = v1.randomBytes(32)
    // 5-chunk blob; imagine the SW asks for a range covering chunks [2..4]. The provider MUST open chunk 2 with
    // total = 5 (the whole blob). A naive impl that passed the subset count (3) as `total` fails every open.
    const chunks = Array.from({ length: 5 }, (_, i) => new Uint8Array([i, i, i]))
    const { blobKey, frames, ref } = buildBlob(memoryKey, chunks)
    const { fetchStub, presignAll } = storeFor(frames)
    vi.stubGlobal('fetch', fetchStub)

    const p = makeChunkProvider(SCOPE_ID, memoryKey, ref, presignAll)
    // Green: the provider opens chunk 2 with the whole chunkCount (5).
    expect(Array.from(await p.getPlaintextChunk(2))).toEqual(Array.from(chunks[2]))
    // Red bite: the same frame opened with the range-subset total (3) — what the trap forbids — must reject.
    expect(() => v1.openFrame(blobKey, 2, 3, frames[2])).toThrow()
  })

  it('re-presigns the whole blob and retries once on a stale-URL 403', async () => {
    const memoryKey = v1.randomBytes(32)
    const { frames, ref } = buildBlob(memoryKey, [new Uint8Array([1, 1]), new Uint8Array([2, 2])])
    const { fetchStub, presignAdvancing } = storeFor(frames, new Set([0])) // slot 0 → 403, slot 1 → ok
    vi.stubGlobal('fetch', fetchStub)

    const p = makeChunkProvider(SCOPE_ID, memoryKey, ref, presignAdvancing)
    expect(Array.from(await p.getPlaintextChunk(1))).toEqual(Array.from([2, 2]))
    expect(presignAdvancing).toHaveBeenCalledTimes(2) // initial presign (403) → re-presign → retry succeeds
  })

  it('rejects an out-of-range chunk index without touching the store', async () => {
    const memoryKey = v1.randomBytes(32)
    const { frames, ref } = buildBlob(memoryKey, [new Uint8Array([1])])
    const { fetchStub, presignAll } = storeFor(frames)
    vi.stubGlobal('fetch', fetchStub)

    const p = makeChunkProvider(SCOPE_ID, memoryKey, ref, presignAll)
    await expect(p.getPlaintextChunk(1)).rejects.toThrow(/chunk_out_of_range/)
    await expect(p.getPlaintextChunk(-1)).rejects.toThrow(/chunk_out_of_range/)
    expect(fetchStub).not.toHaveBeenCalled()
  })
})
