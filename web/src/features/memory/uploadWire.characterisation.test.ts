// The upload pipeline on the wire, pinned (cr-files-application B0): every request the three Memories upload entry
// points send — `addMedia`, `uploadCoverBlob` and the guest's `addSharePhoto` — recorded at `fetch` with all randomness
// pinned, and compared to a committed snapshot. It is the safety net for moving the neutral blob transport out of
// `media.ts` (B1): nothing about the requests, their order, the framing or the bytes may change in that move.
//
// What it records, per request in send order: the method, the path (no host), every header (`content-type` by value,
// the rest as `<present>`), a JSON body canonicalised (sorted keys; secrets as `<present>`), and for a binary `PUT`
// body its length and SHA-256. Per scenario also the most `PUT`s ever in flight at once and the entry point's result.
// `test/wireRecorder.ts` cannot do this: it is a canary sensor and records no binary bodies.
//
// **Pinned.** `crypto.getRandomValues` (so every `K_blob`, nonce and seal) is a seeded generator, `crypto.randomUUID` a
// counter, `Date.now` a constant — each reset per scenario, so a scenario's bytes do not depend on what ran before.
// The deriver is a stub returning fixed bytes, and `exifr` finds nothing (as in `media.test.ts`): the derived pixels
// and the EXIF tier are the deriver's and the parser's business, not the transport's, and are NOT pinned here. (Since
// inbox #395 F10 the EXIF tier is sealed even when the parser finds nothing — it carries the source's file name — so its
// seal draws a nonce before the blobs are planned: that update changed `wrappedExif` from null and, through the pinned
// stream, every later `K_blob` wrap and PUT digest; no length, path, header, order or count moved.)
// (Re-blessed again for inbox #397, the blob meta. Every declare item gained `wrappedBlobMeta` (380 base64: one 256-byte
// bucket sealed — ORIGINAL `{type,name,modifiedAt}`, THUMBNAIL/PREVIEW/COVER `{type:'image/jpeg'}`); `wrappedExif` went
// back to null (the name left the EXIF tier, and exifr finds nothing here); `wrappedMeta` shrank 176 → 144 base64 (the
// listed tier dropped `mediaType`). Through the pinned stream: the EXIF nonce is no longer drawn and each plan draws a
// meta nonce after its wrap, so the ORIGINAL's and PREVIEW's `K_blob` wraps and every PUT digest changed, while the
// THUMBNAIL's and the cover's wraps came out byte-identical (the stream realigns behind the ORIGINAL; the cover had no
// EXIF to lose). No length of anything else, no path, header, order or count moved.)
// Every scenario runs twice and must record the same thing both times — the guard against a recording that depends on
// scheduling rather than on the code.
//
// **Updating it deliberately.** A change that is MEANT to alter the wire (a new header, a new field, another frame
// size) updates the snapshots with `npx vitest run -u src/features/memory/uploadWire.characterisation.test.ts` and
// the diff of `__snapshots__/uploadWire.*.json` is reviewed as part of that change. A move that is meant to change
// nothing (B1) must leave them untouched.
//
// Only `exifr` is mocked, by its package name — no module of ours, so a move cannot disarm a mock here: the real
// `media.ts`, `share.ts`, generated clients, `api/client.ts` and `withRefresh` all run.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'

vi.mock('exifr', () => ({ default: { parse: vi.fn(async () => undefined) } }))

import { addMedia, CHUNK_SIZE, type MediaDeriver, type AddMediaSource } from './media'
import { FULL_FRAME, uploadedCover } from '../../core/node/cover/coverModel'
import { uploadCoverImage, writeNodeCover } from '../../core/node/cover/coverWrite'
import { unsealNodeMeta } from '../../core/node/nodeMeta'
import { addSharePhoto } from './share'
import { session } from '../../app/session'

const SCOPE_ID = '33333333-3333-4333-8333-333333333333'
const NODE_ID = '6e5a0000-0000-4000-8000-0000000000b1'
const SHARE_ID = 'Sh4reIdSh4reIdSh4reIdSh4reIdSh4reIdSh4re-_0'
const ACCESS_TOKEN = 'access-token-1'
const CONTRIBUTOR_TOKEN = 'contributor-token-1'
const PW_TOKEN = 'pw-token-1'
const NOW = 1_760_000_000_000
/** How many PUTs the fake server signs per window — small, so a multi-chunk blob needs several windows. */
const SERVER_WINDOW = 3

// --- pinned randomness ------------------------------------------------------------------------------------------

/** A seeded xorshift32 byte stream: the same seed gives the same bytes in the same call order. */
function pinRandomness(seed: number) {
  let x = seed >>> 0 || 1
  const nextByte = () => {
    x ^= x << 13; x >>>= 0
    x ^= x >>> 17
    x ^= x << 5; x >>>= 0
    return x & 0xff
  }
  vi.spyOn(globalThis.crypto, 'getRandomValues').mockImplementation(<T extends ArrayBufferView | null>(array: T): T => {
    if (array) {
      const bytes = new Uint8Array(array.buffer, array.byteOffset, array.byteLength)
      for (let i = 0; i < bytes.length; i++) bytes[i] = nextByte()
    }
    return array
  })
  let uuids = 0
  vi.spyOn(globalThis.crypto, 'randomUUID').mockImplementation(() => {
    uuids += 1
    return `00000000-0000-4000-8000-${uuids.toString(16).padStart(12, '0')}` as `${string}-${string}-${string}-${string}-${string}`
  })
  vi.spyOn(Date, 'now').mockReturnValue(NOW)
}

// --- the recording fake server ----------------------------------------------------------------------------------

type Recorded = {
  method: string
  path: string
  headers: Record<string, string>
  body?: unknown
  bytes?: { length: number; sha256: string }
}

type Recording = { requests: Recorded[]; maxInFlightPuts: number; putCount: number; result?: unknown }

const SECRET_VALUES = new Set([ACCESS_TOKEN, CONTRIBUTOR_TOKEN, PW_TOKEN, `Bearer ${ACCESS_TOKEN}`])

/** Sort keys recursively, and replace a secret's value by `<present>`. */
function canonical(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(canonical)
  if (value && typeof value === 'object') {
    return Object.fromEntries(Object.keys(value).sort().map((k) => [k, canonical((value as Record<string, unknown>)[k])]))
  }
  return typeof value === 'string' && SECRET_VALUES.has(value) ? '<present>' : value
}

function headersOf(init?: RequestInit): Record<string, string> {
  const out: Record<string, string> = {}
  new Headers(init?.headers).forEach((v, k) => { out[k] = k === 'content-type' ? v : '<present>' })
  return Object.fromEntries(Object.keys(out).sort().map((k) => [k, out[k]]))
}

async function sha256Hex(bytes: Uint8Array): Promise<string> {
  const digest = new Uint8Array(await crypto.subtle.digest('SHA-256', bytes as BufferSource))
  return Array.from(digest, (b) => b.toString(16).padStart(2, '0')).join('')
}

/**
 * Stub `fetch` as the control plane and the object store at once. Each start hands out the first [SERVER_WINDOW]
 * PUTs of every upload, each window request the next [SERVER_WINDOW] from `fromChunk`; a `PUT` is answered after a
 * timer, so the pump's workers really overlap and the in-flight bound is observable. JSON answers are immediate.
 */
function recordingServer() {
  const requests: (Recorded & { raw?: Uint8Array })[] = []
  const uploads = new Map<string, { chunkCount: number; byteSize: number; wrappedBlobKey: string; blobId: string }>()
  let uploadSeq = 0
  let inFlight = 0
  let maxInFlight = 0
  const json = (body: unknown) => new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } })
  const puts = (uploadId: string, from: number) => {
    const { chunkCount } = uploads.get(uploadId)!
    return Array.from({ length: Math.max(0, Math.min(SERVER_WINDOW, chunkCount - from)) }, (_, k) => ({
      index: from + k, url: `https://store.test/bucket/${uploadId}/${from + k}?X-Sig=s`,
    }))
  }

  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = new URL(String(input), 'http://api.test')
    const method = init?.method ?? 'GET'
    const rec: Recorded & { raw?: Uint8Array } = { method, path: url.pathname + url.search, headers: headersOf(init) }
    requests.push(rec)

    if (url.host === 'store.test') {
      if (method !== 'PUT') throw new Error(`unexpected store request ${method} ${url}`)
      rec.raw = new Uint8Array(init!.body as Uint8Array) // a copy: the pump may reuse nothing, but never trust that
      inFlight += 1
      maxInFlight = Math.max(maxInFlight, inFlight)
      await new Promise((r) => setTimeout(r, 1))
      inFlight -= 1
      return new Response(null, { status: 200 })
    }

    const body = init?.body === undefined ? undefined : JSON.parse(String(init.body)) as Record<string, unknown>
    rec.body = body === undefined ? undefined : canonical(body)
    const path = url.pathname
    if (method === 'POST' && (path.endsWith('/blob-uploads-batch') || path.endsWith('/blob-uploads'))) {
      const items = body!.items as { blobId: string; chunkCount: number; byteSize: number; wrappedBlobKey: string }[]
      return json({
        results: items.map((it) => {
          const uploadId = `upload-${++uploadSeq}`
          uploads.set(uploadId, it)
          return { uploadId, presignedPuts: puts(uploadId, 0), error: null }
        }),
      })
    }
    if (method === 'POST' && path.endsWith('/blob-upload-windows')) {
      const items = body!.items as { uploadId: string; fromChunk: number }[]
      return json({ results: items.map(({ uploadId, fromChunk }) => ({ presignedPuts: puts(uploadId, fromChunk), error: null })) })
    }
    if (method === 'POST' && (path.endsWith('/media-batch') || path.endsWith('/media'))) {
      const items = body!.items as { mediaId: string; originalUploadId: string }[]
      return json({ results: items.map((m) => ({ mediaId: m.mediaId, blobId: uploads.get(m.originalUploadId)!.blobId, error: null })) })
    }
    if (method === 'PUT' && path.endsWith('/cover')) {
      if (!uploads.has((body as { uploadId: string }).uploadId)) throw new Error('setNodeCover names an upload nobody opened')
      return new Response(null, { status: 204 }) // inbox #411: the blob step and the meta naming it, ONE request
    }
    throw new Error(`unexpected request ${method} ${url}`)
  }))

  return {
    async recording(result: unknown): Promise<Recording> {
      const out: Recorded[] = []
      for (const { raw, ...r } of requests) {
        out.push(raw ? { ...r, bytes: { length: raw.length, sha256: await sha256Hex(raw) } } : r)
      }
      return { requests: out, putCount: out.filter((r) => r.method === 'PUT').length, maxInFlightPuts: maxInFlight, result }
    },
  }
}

// --- fixed inputs -----------------------------------------------------------------------------------------------

/** `n` deterministic bytes, distinct per `salt`. */
const bytesOf = (n: number, salt: number) => new Uint8Array(n).map((_, i) => (i * 31 + salt) % 251)

/** A stub deriver: fixed preview / thumbnail / cover bytes and a fixed geometry — the pixels are not under test. */
function fixedDeriver(sizes: { preview: number; thumbnail: number; cover: number }): MediaDeriver {
  return {
    deriveImage: vi.fn(async (_source: Blob, opts: { maxEdge: number }) => {
      const n = opts.maxEdge >= 2048 ? (sizes.cover || sizes.preview) : opts.maxEdge >= 1000 ? sizes.preview : sizes.thumbnail
      return { bytes: bytesOf(n, opts.maxEdge % 251), source: { w: 4032, h: 3024 } }
    }),
    deriveVideoPoster: vi.fn(async () => bytesOf(64, 7)),
  } as unknown as MediaDeriver
}

const memoryKey = () => bytesOf(32, 99)
const imageSource = (byteCount: number, salt: number): AddMediaSource => ({
  blob: new Blob([bytesOf(byteCount, salt)], { type: 'image/jpeg' }),
  mediaType: 'image/jpeg',
  lastModified: 1_700_000_000_000,
  name: 'IMG_0001.JPG',
})

/** Run one scenario under fresh pins and a fresh server, and return what went over the wire. */
async function record(seed: number, run: () => Promise<unknown>): Promise<Recording> {
  vi.restoreAllMocks()
  pinRandomness(seed)
  const server = recordingServer()
  const result = await run()
  return server.recording(result)
}

/** Record twice (the recording must not depend on scheduling), then compare to the committed file. */
async function expectWire(name: string, seed: number, run: () => Promise<unknown>): Promise<Recording> {
  const first = await record(seed, run)
  const second = await record(seed, run)
  expect(second).toEqual(first)
  await expect(JSON.stringify(first, null, 2) + '\n').toMatchFileSnapshot(`./__snapshots__/uploadWire.${name}.json`)
  return first
}

beforeEach(() => {
  session.apiBase = ''
  session.accessToken = ACCESS_TOKEN
})

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
  session.accessToken = null
})

describe('the Memories upload pipeline on the wire (characterisation, cr-files-application B0)', () => {
  it('addMedia — a one-chunk image with its two derivative tiers', async () => {
    const wire = await expectWire('addMedia.small', 1, () =>
      addMedia(SCOPE_ID, NODE_ID, memoryKey(), imageSource(1000, 1), fixedDeriver({ preview: 700, thumbnail: 300, cover: 0 })))
    expect(wire.putCount).toBe(3) // original + thumbnail + preview, one frame each
  })

  it('addMedia — a multi-chunk original (short last chunk) through several windows', async () => {
    const wire = await expectWire('addMedia.multiChunk', 2, () =>
      addMedia(SCOPE_ID, NODE_ID, memoryKey(), imageSource(6 * CHUNK_SIZE + 1234, 2), fixedDeriver({ preview: 900, thumbnail: 200, cover: 0 })))
    expect(wire.putCount).toBe(7 + 2)
    expect(wire.maxInFlightPuts).toBeGreaterThan(1) // control: the bound is observable, not a sequential pump
  })

  // A cover is core's since inbox #411: the same upload and the same one-request write (`setNodeCover`) for a Memory's, a
  // folder's and a drop box's, and since the cover dialog moved to core (inbox #411, follow-up) it is ONE save path. This scenario
  // is that path as the dialog's `upload` intent runs it with a Memory's key (core's `uploadCoverImage` + `writeNodeCover`). The blob's declare and its PUTs are byte for byte what `uploadCoverBlob` sent; only the last request
  // moved: the commit `POST .../cover-blob` became `PUT .../cover`, carrying the node's sealed meta that names the cover.
  it('a derived multi-chunk cover - the blob, then ONE setNodeCover with the meta that names it', async () => {
    const wire = await expectWire('uploadCoverBlob', 3, () =>
      uploadCoverImage(
        SCOPE_ID, NODE_ID, memoryKey(), imageSource(5000, 3), fixedDeriver({ preview: 0, thumbnail: 0, cover: 2 * CHUNK_SIZE + 5 }),
      ).then((up) => writeNodeCover({
        scopeId: SCOPE_ID, nodeId: NODE_ID, nodeKey: memoryKey(), current: { wrappedMeta: null, metaRevision: 0 },
        cover: uploadedCover(up.plan, { ...FULL_FRAME }), uploadId: up.uploadId, surfaceConflict: false,
      })).then((r) => ({ wrappedMeta: r.wrappedMeta, metaRevision: r.metaRevision })),
    )
    expect(wire.requests.filter((r) => r.bytes).length, 'the three frames of the blob, then the write (also a PUT)').toBe(3)
    expect(wire.putCount).toBe(4)

    // The last request is the write, and it is sealed: the cover named in the meta is the blob the first request declared,
    // the meta opens only under THIS node's key, bound to scope and node (purpose `node-meta`, ADR 0072), and nothing of the
    // source (its file name, the key) crosses in the clear.
    const declared = (wire.requests[0].body as { items: { blobId: string; ownerId: string; role: string }[] }).items[0]
    expect(declared).toMatchObject({ ownerId: NODE_ID, role: 'COVER' })
    const write = wire.requests[wire.requests.length - 1]
    expect(write).toMatchObject({ method: 'PUT', path: `/api/scopes/${SCOPE_ID}/nodes/${NODE_ID}/cover` })
    const body = write.body as { uploadId: string; expectedBlobId: string | null; wrappedMeta: string; metaRevision: number }
    expect(body).toMatchObject({ uploadId: 'upload-1', expectedBlobId: null, metaRevision: 0 })
    const cover = unsealNodeMeta(memoryKey(), { scopeId: SCOPE_ID, nodeId: NODE_ID }, body.wrappedMeta).cover as { source: { kind: string; ref: { blobId: string; ownerId: string; role: string } } }
    expect(cover.source).toMatchObject({ kind: 'upload', ref: { blobId: declared.blobId, ownerId: NODE_ID, role: 'COVER' } })
    expect(unsealNodeMeta(memoryKey(), { scopeId: SCOPE_ID, nodeId: '6e5a0000-0000-4000-8000-0000000000b2' }, body.wrappedMeta).cover, 'another node cannot open it').toBeUndefined()
    expect(JSON.stringify(wire.requests.map((r) => r.body))).not.toContain('IMG_0001')
  })

  it('addSharePhoto — a guest upload below the share root, behind a share password', async () => {
    const wire = await expectWire('addSharePhoto', 4, () => {
      session.accessToken = null // the anonymous /s/ page has no session
      return addSharePhoto(
        { identityId: 'identity-1', contributorToken: CONTRIBUTOR_TOKEN, shareId: SHARE_ID, scopeId: SCOPE_ID, sealKey: memoryKey(), pwToken: PW_TOKEN, nodeId: NODE_ID },
        imageSource(4 * CHUNK_SIZE + 17, 4),
        fixedDeriver({ preview: 800, thumbnail: 250, cover: 0 }),
      )
    })
    expect(wire.putCount).toBe(5 + 2)
    expect(wire.requests.some((r) => 'authorization' in r.headers)).toBe(false) // never a bearer from the guest page
  })
})
