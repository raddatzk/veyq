import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'

vi.mock('../../api/client', () => ({
  blobApi: vi.fn(),
  loginApi: vi.fn(),
  tokenApi: vi.fn(),
  sessionApi: vi.fn(),
}))
vi.mock('./api', () => ({ mediaApi: vi.fn() }))

// exifr does the real image parse in the browser; here we drive it so the seal/round-trip is deterministic.
// Default: no EXIF (parse → undefined), so the non-EXIF paths behave exactly as a source without metadata.
vi.mock('exifr', () => ({ default: { parse: vi.fn(async () => undefined) } }))

import {
  addMedia,
  listMedia,
  listMediaPage,
  listMediaPages,
  listTrash,
  MEDIA_PAGE_LIMIT,
  openBlob,
  openOriginal,
  gridRef,
  lightboxRef,
  downloadRef,
  scaleToMaxEdge,
  unsealMeta,
  setMediaMeta,
  isVideoMediaType,
  originalBlobMeta,
  originalMediaType,
  effectiveCapturedAt,
  readCaptureTime,
  backfillPlaceName,
  PLACE_NAME_MAX_BYTES,
  clampPlaceName,
  EXIF_TIER_MAX_B64,
  EXIF_TEXT_MAX_BYTES,
  CHUNK_SIZE,
  PUMP_IN_FLIGHT,
  TIER_SPECS,
  type MediaDeriver,
  type MediaItem,
  type MediaMeta,
  type Dimensions,
} from './media'
import exifr from 'exifr'
import { blobApi } from '../../api/client'
import { mediaApi } from './api'
import {
  ResponseError,
  type MediaApi,
  type MediaBatchResponse,
  type MediaListPage,
  type MediaMetaResponse,
  type MediaRequestItem,
} from '../../api/generated/media'
import {
  type BlobApi,
  type BlobDownloadsBatchResponse,
  type BlobUploadRequestItem,
  type BlobUploadResultItem,
  type BlobUploadWindowResultItem,
  type BlobUploadWindowsResponse,
  type BlobUploadsBatchResponse,
  type PresignedUrl,
} from '../../api/generated/blob'
import { fakeApi, stubApi } from '../../test/apiMock'
import { mediaItem } from '../../test/models'
import { MetaConflictError, MetaUnreadableError } from '../../core/node/sealedMetaWrite'
import { planBlob, readBlobMeta, unwrapBlobKey } from '../../core/blob/transport'
import { rememberDigest } from '../../core/blob/sharedBlob'
import * as v1 from '@veyq/crypto/v1'
import { b64std, b64url, fromB64std } from '@veyq/crypto/base64'

const SCOPE_ID = '11111111-1111-1111-1111-111111111111'
const NODE_ID = '22222222-2222-2222-2222-222222222222'
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
// A media's two metadata tiers are sealed bound to (scope, media) — ADR 0072 — so a reader names the media it opens.
const metaCtx = (mediaId: string) => ({ purpose: 'media-meta', scopeId: SCOPE_ID, mediaId }) as const
const exifCtx = (mediaId: string) => ({ purpose: 'media-exif', scopeId: SCOPE_ID, mediaId }) as const
/** A listed tier sealed as the upload would — the base a meta write re-reads (ADR 0077). */
const sealTier = (mediaId: string, key: Uint8Array, tier: object) =>
  b64std(v1.sealField(metaCtx(mediaId), key, new TextEncoder().encode(JSON.stringify(tier))))
/** A media a write targets: its listed tier (or none) at a revision. */
const target = (mediaId: string, key: Uint8Array, tier: object | null, metaRevision = 0) =>
  ({ mediaId, wrappedMeta: tier ? sealTier(mediaId, key, tier) : undefined, metaRevision })
/** A thrown generated-client failure carrying `409 stale_revision` with the server's current tier. */
const staleTier = (wrappedMeta: string | null, metaRevision: number) => ({
  response: new Response(JSON.stringify({ code: 'stale_revision', wrappedMeta, metaRevision }), { status: 409 }),
})
const openJson = (ctx: v1.FieldContext, key: Uint8Array, sealed: string) =>
  JSON.parse(new TextDecoder().decode(v1.openField(ctx, key, fromB64std(sealed))))

/**
 * A tiny in-process object store that handles **several blobs per add-media** (original + derivatives). Each
 * upload session gets its own `mem://<uploadId>/<chunk>` keyspace; the fake replays PUT bodies on the matching
 * GET, so a real v1 seal/open + K_blob wrap round-trips per tier. `listNodeMedia` reconstructs the role-tagged
 * MediaSummary from what `completeMedia` saw (blobId == uploadId in this fake). Operator-blind: the "store"
 * only ever holds ciphertext.
 */
/**
 * `window` models the server's `veyq.blob.presign-window` (ADR 0013 §2): `startBlobUploads` signs only that many
 * chunks and `presignBlobUploadWindows` each next range — unbounded by default, so a test that is not about the
 * pump sees every URL at start. `events` logs every window request and every PUT, in order, as `window:<from>` /
 * `put:<index>`; `forbidFirstPut` makes the first PUT of those chunk indices answer 403, as an expired URL does;
 * `putDelayMs` holds each PUT open so concurrency is observable (`inFlight.max`).
 */
function fakeStore(opts: { window?: number; forbidFirstPut?: number[]; putDelayMs?: number } = {}) {
  const window = opts.window ?? Number.POSITIVE_INFINITY
  const objects = new Map<string, Uint8Array>()
  const sessions = new Map<string, { wrappedBlobKey: string; wrappedBlobMeta?: string | null; chunkCount: number; blobId: string }>()
  // The client mints each blob id and binds its K_blob wrap to it (ADR 0072); the store keys by upload session.
  const uploadOfBlob = new Map<string, string>()
  const events: string[] = []
  const inFlight = { now: 0, max: 0 }
  const forbidden = new Set(opts.forbidFirstPut ?? [])
  let upCounter = 0
  const putsOf = (uploadId: string, from: number, chunkCount: number): PresignedUrl[] =>
    Array.from({ length: Math.max(0, Math.min(from + window, chunkCount) - from) }, (_, k) => ({ index: from + k, url: `mem://${uploadId}/${from + k}` }))
  let completeItem: MediaRequestItem | undefined
  const captured = {
    startItems: [] as BlobUploadRequestItem[],
    /** The media `completeMedia` last attached — reading it before any completion is a broken test, so it throws. */
    get completeItem(): MediaRequestItem {
      if (!completeItem) throw new Error('fakeStore: no media completed yet')
      return completeItem
    },
    updatedMeta: undefined as string | null | undefined,
  }

  const blobOf = (uploadId: string) => {
    const s = sessions.get(uploadId)!
    // the blob's sealed meta is served back unchanged beside its wrap, as the backend does (inbox #397)
    return { blobId: s.blobId, chunkCount: s.chunkCount, byteSize: 0, wrappedBlobKey: s.wrappedBlobKey, wrappedBlobMeta: s.wrappedBlobMeta ?? null }
  }

  // Core's generic blob transfer (the `blob` tag) — installed as `blobApi`; the media operations below as `mediaApi`.
  const blob = {
    startBlobUploads: vi.fn<BlobApi['startBlobUploads']>(async ({ blobUploadsBatchRequest }): Promise<BlobUploadsBatchResponse> => {
      const results = blobUploadsBatchRequest.items.map((item): BlobUploadResultItem => {
        const uploadId = `up-${upCounter++}`
        sessions.set(uploadId, { wrappedBlobKey: item.wrappedBlobKey, wrappedBlobMeta: item.wrappedBlobMeta, chunkCount: item.chunkCount, blobId: item.blobId })
        uploadOfBlob.set(item.blobId, uploadId)
        captured.startItems.push(item)
        return { uploadId, presignedPuts: putsOf(uploadId, 0, item.chunkCount), error: null }
      })
      return { results }
    }),
    presignBlobUploadWindows: vi.fn<BlobApi['presignBlobUploadWindows']>(async ({ blobUploadWindowsRequest }): Promise<BlobUploadWindowsResponse> => ({
      results: blobUploadWindowsRequest.items.map(({ uploadId, fromChunk }): BlobUploadWindowResultItem => {
        events.push(`window:${fromChunk}`)
        return { presignedPuts: putsOf(uploadId, fromChunk!, sessions.get(uploadId)!.chunkCount), error: null }
      }),
    })),
    presignBlobDownloads: vi.fn<BlobApi['presignBlobDownloads']>(async ({ blobDownloadsBatchRequest }): Promise<BlobDownloadsBatchResponse> => {
      const uploadId = uploadOfBlob.get(blobDownloadsBatchRequest.items[0].blobId)!
      const s = sessions.get(uploadId)!
      return {
        results: [{ presignedGets: Array.from({ length: s.chunkCount }, (_, i) => ({ index: i, url: `mem://${uploadId}/${i}` })), error: null }],
      }
    }),
  }

  const api = {
    completeMedia: vi.fn<MediaApi['completeMedia']>(async ({ mediaBatchRequest }): Promise<MediaBatchResponse> => {
      const item = mediaBatchRequest.items[0]
      completeItem = item
      // a shared original (ADR 0013 § 5) is the row the client minted; an upload's, the session's blob
      const blobId = item.originalShared ? item.originalShared.blobId : sessions.get(item.originalUploadId!)!.blobId
      return { results: [{ blobId, mediaId: item.mediaId, error: null }] }
    }),
    listNodeMedia: vi.fn<MediaApi['listNodeMedia']>(async (): Promise<MediaListPage> => {
      const item = captured.completeItem
      // cr-0107: the response is now the MediaListPage envelope (delta rows + both revisions + optional id-set).
      return {
        media: [
          {
            blockedByOperator: false,
            mediaId: item.mediaId, // the client-minted id the upload was completed for
            ...blobOf(item.originalUploadId!),
            derivatives: (item.derivatives ?? []).map((d) => ({ kind: d.kind, ...blobOf(d.uploadId) })),
            wrappedMeta: captured.updatedMeta ?? item.wrappedMeta ?? null, // listed for grid layout + date sorting (cr-0068)
            updatedAt: new Date('2026-07-09T12:00:00.000Z'), // the keyset cursor's sort key (cr-0107)
            // wrappedExif is deliberately NOT echoed by listNodeMedia — it is lazy via getMediaExif (cr-0064)
          },
        ],
        mediaRevision: 1,
        deleteRevision: 0,
        mediaIds: null,
        more: false,
      }
    }),
    updateMediaMeta: vi.fn<MediaApi['updateMediaMeta']>(async ({ mediaMetaUpdate }): Promise<MediaMetaResponse> => {
      captured.updatedMeta = mediaMetaUpdate.wrappedMeta // opaque ciphertext (or null to clear)
      return { wrappedMeta: mediaMetaUpdate.wrappedMeta ?? null }
    }),
  }

  const fetchStub = vi.fn(async (url: string, init?: RequestInit) => {
    if (init?.method === 'PUT') {
      // A presigned PUT signs `content-type: application/octet-stream`, so the store refuses any other type —
      // and a missing header too (ADR 0013 §6). Modelled here so every upload test in this file proves it.
      if (new Headers(init.headers).get('Content-Type') !== 'application/octet-stream') {
        return new Response(null, { status: 403 })
      }
      const index = Number(url.split('/').pop())
      events.push(`put:${index}`)
      if (forbidden.delete(index)) return new Response(null, { status: 403 })
      inFlight.max = Math.max(inFlight.max, ++inFlight.now)
      if (opts.putDelayMs !== undefined) await new Promise((r) => setTimeout(r, opts.putDelayMs))
      inFlight.now--
      objects.set(url, new Uint8Array(init.body as Uint8Array))
      return new Response('', { status: 200 }) // the store's answer to a PUT: 200, an empty body
    }
    const body = objects.get(url)
    if (!body) return { ok: false, status: 404 } as Response
    return { ok: true, status: 200, arrayBuffer: async () => body.buffer } as unknown as Response
  })

  return { api, blob, fetchStub, objects, captured, events, inFlight }
}

/**
 * A stub deriver: returns a tiny "JPEG" tagged by the requested maxEdge so each tier's bytes are distinct.
 *
 * `imageSources` records the BYTE SIZE of every blob handed to `deriveImage`, which is what lets a test count
 * decodes **of the original** apart from the cheap pass over the already-downscaled preview. `sourceDims` is
 * null by default — modelling a deriver that reports no size, so the geometry falls back to a probe; set it to
 * model the real browser deriver, which always knows the size of what it just decoded.
 */
function stubDeriver(): MediaDeriver & { images: number; posters: number; imageSources: number[]; sourceDims: Dimensions | null } {
  const d = {
    images: 0,
    posters: 0,
    imageSources: [] as number[],
    sourceDims: null as Dimensions | null,
    deriveImage: vi.fn(async (blob: Blob, { maxEdge }: { maxEdge: number; quality: number }) => {
      d.images++
      d.imageSources.push(blob.size)
      return { bytes: new Uint8Array([0xff, 0xd8, maxEdge & 0xff, (maxEdge >> 8) & 0xff]), source: d.sourceDims }
    }),
    deriveVideoPoster: vi.fn(async () => {
      d.posters++
      return new Uint8Array([0xff, 0xd8, 0x07, 0x09])
    }),
  }
  return d
}

beforeEach(() => vi.clearAllMocks())
afterEach(() => vi.unstubAllGlobals())

describe('scaleToMaxEdge (longest-edge target math)', () => {
  it('scales the longest edge to the cap, preserving aspect ratio', () => {
    expect(scaleToMaxEdge(4000, 2000, 400)).toEqual({ width: 400, height: 200 }) // landscape
    expect(scaleToMaxEdge(2000, 4000, 400)).toEqual({ width: 200, height: 400 }) // portrait
    expect(scaleToMaxEdge(1000, 1000, 400)).toEqual({ width: 400, height: 400 }) // square
  })

  it('never upscales a source already within the cap', () => {
    expect(scaleToMaxEdge(300, 150, 400)).toEqual({ width: 300, height: 150 })
    expect(scaleToMaxEdge(400, 400, 400)).toEqual({ width: 400, height: 400 })
  })

  it('rounds to whole pixels and keeps each side ≥ 1px', () => {
    expect(scaleToMaxEdge(2048, 3, 400)).toEqual({ width: 400, height: 1 })
    expect(scaleToMaxEdge(0, 0, 400)).toEqual({ width: 0, height: 0 })
  })
})

describe('addMedia — derivative generation + role/batch assembly', () => {
  it('an image uploads original + thumbnail + preview in one batch, role-tagged', async () => {
    const store = fakeStore()
    fakeApi(mediaApi, store.api)
    fakeApi(blobApi, store.blob)
    vi.stubGlobal('fetch', store.fetchStub)
    const deriver = stubDeriver()

    const memoryKey = v1.randomBytes(32)
    const data = new Uint8Array(10).fill(7)
    const { blobId, mediaId } = await addMedia(SCOPE_ID, NODE_ID, memoryKey, { blob: new Blob([data as BlobPart]), mediaType: 'image/png' }, deriver)

    // the client mints the media id and every blob id, and binds each K_blob wrap to its slot (ADR 0072)
    expect(mediaId).toMatch(UUID)
    expect(store.captured.completeItem.mediaId).toBe(mediaId)
    expect(blobId).toBe(store.captured.startItems[0].blobId) // original is upload index 0
    for (const i of store.captured.startItems) expect(i.blobId).toMatch(UUID)
    expect(store.captured.startItems.map((i) => [i.ownerId, i.role])).toEqual([
      [mediaId, 'ORIGINAL'], [mediaId, 'THUMBNAIL'], [mediaId, 'PREVIEW'],
    ])

    // three blobs declared in one start batch (original + 2 derivatives)
    expect(store.captured.startItems).toHaveLength(3)
    // Both tiers are still produced at their pinned specs — the property this always asserted. What changed is
    // that the ORIGINAL is decoded once: PREVIEW comes from it, THUMBNAIL comes from PREVIEW. The order is
    // therefore reversed and load-bearing, not incidental.
    expect(deriver.images).toBe(2)
    expect(deriver.posters).toBe(0)
    expect(deriver.deriveImage).toHaveBeenNthCalledWith(1, expect.anything(), TIER_SPECS.PREVIEW)
    expect(deriver.deriveImage).toHaveBeenNthCalledWith(2, expect.anything(), TIER_SPECS.THUMBNAIL)
    expect(deriver.imageSources.filter((n) => n === data.length)).toHaveLength(1) // the source, exactly once

    // complete attached one role-tagged media: original + [THUMBNAIL, PREVIEW]
    expect(store.captured.completeItem.nodeId).toBe(NODE_ID)
    expect(store.captured.completeItem.originalUploadId).toBe('up-0')
    expect(store.captured.completeItem.derivatives).toEqual([
      { kind: 'THUMBNAIL', uploadId: 'up-1' },
      { kind: 'PREVIEW', uploadId: 'up-2' },
    ])

    // each tier has its OWN K_blob wrap (per-blob keys, never shared)
    const wraps = store.captured.startItems.map((i) => i.wrappedBlobKey)
    expect(new Set(wraps).size).toBe(3)
  })

  // The original used to be decoded four times per picked photo: once per tier, once for the sealed geometry,
  // and once for the in-flight tile's aspect ratio. Inside `addMedia` it is now decoded ONCE. Lever: restore the
  // second `deriveImage` over the source blob in `deriveTiers` and `imageSources` carries two entries at the
  // source's size. That lever is a call this CR deletes and no later phase reintroduces.
  //
  // The counting probe matters as much as the count: `stubDeriver` deliberately ships WITHOUT `probeDimensions`,
  // so the geometry decode runs in no other test — a bite that did not install one here would be counting a
  // path that never ran.
  it('decodes the original once for both tiers and the sealed geometry', async () => {
    const store = fakeStore()
    fakeApi(mediaApi, store.api)
    fakeApi(blobApi, store.blob)
    vi.stubGlobal('fetch', store.fetchStub)
    const deriver = stubDeriver()
    deriver.sourceDims = { w: 4032, h: 3024 } // the real deriver always knows what it just decoded
    const probe = vi.fn(async () => ({ w: 1, h: 1 }))
    deriver.probeDimensions = probe

    const memoryKey = v1.randomBytes(32)
    const data = new Uint8Array(64).fill(5)
    await addMedia(SCOPE_ID, NODE_ID, memoryKey, { blob: new Blob([data as BlobPart]), mediaType: 'image/jpeg' }, deriver)

    expect(deriver.imageSources.filter((n) => n === data.length)).toHaveLength(1) // ONE decode of the original
    expect(probe).not.toHaveBeenCalled() // nothing re-measured what that decode already knew
    // …and the geometry it knew is what got sealed, at full source resolution — not the preview's size
    const [item] = await listMedia(SCOPE_ID, NODE_ID)
    expect(unsealMeta(SCOPE_ID, memoryKey, item)).toMatchObject({ w: 4032, h: 3024 })
    // both tiers still arrive, at their pinned specs
    expect(store.captured.completeItem.derivatives!.map((d) => d.kind)).toEqual(['THUMBNAIL', 'PREVIEW'])
    expect(deriver.deriveImage).toHaveBeenNthCalledWith(1, expect.anything(), TIER_SPECS.PREVIEW)
    expect(deriver.deriveImage).toHaveBeenNthCalledWith(2, expect.anything(), TIER_SPECS.THUMBNAIL)
  })

  it('a video derives a single poster and uses it for both the thumbnail and preview tier', async () => {
    const store = fakeStore()
    fakeApi(mediaApi, store.api)
    fakeApi(blobApi, store.blob)
    vi.stubGlobal('fetch', store.fetchStub)
    const deriver = stubDeriver()

    const memoryKey = v1.randomBytes(32)
    await addMedia(SCOPE_ID, NODE_ID, memoryKey, { blob: new Blob([new Uint8Array(20).fill(3) as BlobPart]), mediaType: 'video/mp4' }, deriver)

    expect(deriver.posters).toBe(1) // decoded once
    expect(deriver.images).toBe(0)
    expect(store.captured.completeItem.derivatives!.map((d) => d.kind)).toEqual(['THUMBNAIL', 'PREVIEW'])

    // both derivative tiers open back to the SAME poster bytes
    const [item] = await listMedia(SCOPE_ID, NODE_ID)
    const thumb = await openBlob(SCOPE_ID, memoryKey, gridRef(item))
    const preview = await openBlob(SCOPE_ID, memoryKey, lightboxRef(item))
    expect(Array.from(thumb)).toEqual([0xff, 0xd8, 0x07, 0x09])
    expect(Array.from(preview)).toEqual(Array.from(thumb))
  })

  it('a non-image/non-video attaches the original only (no derivatives)', async () => {
    const store = fakeStore()
    fakeApi(mediaApi, store.api)
    fakeApi(blobApi, store.blob)
    vi.stubGlobal('fetch', store.fetchStub)
    const deriver = stubDeriver()

    await addMedia(SCOPE_ID, NODE_ID, v1.randomBytes(32), { blob: new Blob([new Uint8Array([1, 2, 3]) as BlobPart]), mediaType: 'application/pdf' }, deriver)

    expect(deriver.images + deriver.posters).toBe(0)
    expect(store.captured.startItems).toHaveLength(1)
    expect(store.captured.completeItem.derivatives).toEqual([])
  })

  it('throws store_unavailable when the store cannot presign (no transport)', async () => {
    stubApi(blobApi, { startBlobUploads: { results: [{ uploadId: 'up-1', presignedPuts: null, error: null }] } })

    await expect(
      addMedia(SCOPE_ID, NODE_ID, v1.randomBytes(32), { blob: new Blob([new Uint8Array([1]) as BlobPart]), mediaType: 'application/pdf' }, stubDeriver()),
    ).rejects.toThrow('store_unavailable')
  })
})

describe('addMedia — a file the node already stores (ADR 0013 § 5: the video banner)', () => {
  it('an original equal to a stored blob is a shared row: its K_blob wrapped again for the medium, only the tiers uploaded', async () => {
    const store = fakeStore()
    fakeApi(mediaApi, store.api)
    fakeApi(blobApi, store.blob)
    vi.stubGlobal('fetch', store.fetchStub)
    const memoryKey = v1.randomBytes(32)
    const data = new Uint8Array(10).fill(7)
    // the node's video banner: the same file, stored under the node's key in the BANNER slot
    const banner = planBlob(SCOPE_ID, { ownerId: NODE_ID, role: 'BANNER' }, memoryKey, new Blob([data as BlobPart]))
    const asked: [string, number][] = []
    const storedOriginal = (sha256: string, byteSize: number) => {
      asked.push([sha256, byteSize])
      return sha256 === b64url(v1.sha256Digest(data)) && byteSize === banner.byteSize ? { key: memoryKey, ref: banner } : null
    }
    const { blobId, mediaId } = await addMedia(SCOPE_ID, NODE_ID, memoryKey, { blob: new Blob([data as BlobPart]), mediaType: 'video/mp4' }, stubDeriver(), { storedOriginal })

    expect(asked).toEqual([[b64url(v1.sha256Digest(data)), banner.byteSize]])
    expect(store.captured.startItems.map((i) => i.role), 'no ORIGINAL uploaded').toEqual(['THUMBNAIL', 'PREVIEW'])
    const item = store.captured.completeItem
    expect(item.originalUploadId ?? null).toBeNull()
    expect(item.originalShared).toMatchObject({ sourceBlobId: banner.blobId, blobId })
    // the shared row opens bound to the medium's ORIGINAL with the banner's K_blob, its blob-meta the file's facts
    const row = { blobId, chunkCount: banner.chunkCount, byteSize: banner.byteSize, wrappedBlobKey: item.originalShared!.wrappedBlobKey, ownerId: mediaId, role: 'ORIGINAL' }
    expect(unwrapBlobKey(SCOPE_ID, memoryKey, row)).toEqual(banner.blobKey)
    expect(readBlobMeta(SCOPE_ID, memoryKey, { ...row, wrappedBlobMeta: item.originalShared!.wrappedBlobMeta })).toMatchObject({ type: 'video/mp4', sha256: b64url(v1.sha256Digest(data)) })
  })

  it('another file is uploaded as before', async () => {
    const store = fakeStore()
    fakeApi(mediaApi, store.api)
    fakeApi(blobApi, store.blob)
    vi.stubGlobal('fetch', store.fetchStub)
    await addMedia(SCOPE_ID, NODE_ID, v1.randomBytes(32), { blob: new Blob([new Uint8Array(10).fill(8) as BlobPart]), mediaType: 'image/png' }, stubDeriver(), { storedOriginal: () => null })
    expect(store.captured.startItems.map((i) => i.role)).toEqual(['ORIGINAL', 'THUMBNAIL', 'PREVIEW'])
    expect(store.captured.completeItem.originalShared ?? null).toBeNull()
  })
})

describe('addMedia — progress, abort, best-effort derivation (cr-0058)', () => {
  it('reports progress 0 → 1 (monotonic) over the ciphertext chunks', async () => {
    const store = fakeStore()
    fakeApi(mediaApi, store.api)
    fakeApi(blobApi, store.blob)
    vi.stubGlobal('fetch', store.fetchStub)
    const seen: number[] = []

    await addMedia(SCOPE_ID, NODE_ID, v1.randomBytes(32), { blob: new Blob([new Uint8Array(20).fill(1) as BlobPart]), mediaType: 'image/png' }, stubDeriver(), {
      onProgress: (f) => seen.push(f),
    })

    expect(seen[0]).toBe(0)
    expect(seen[seen.length - 1]).toBe(1)
    expect(seen).toEqual([...seen].sort((a, b) => a - b)) // non-decreasing
  })

  it('a pre-aborted signal throws AbortError and never attaches the media', async () => {
    const store = fakeStore()
    fakeApi(mediaApi, store.api)
    fakeApi(blobApi, store.blob)
    vi.stubGlobal('fetch', store.fetchStub)
    const ac = new AbortController()
    ac.abort()

    await expect(
      addMedia(SCOPE_ID, NODE_ID, v1.randomBytes(32), { blob: new Blob([new Uint8Array([1]) as BlobPart]), mediaType: 'image/png' }, stubDeriver(), {
        signal: ac.signal,
      }),
    ).rejects.toMatchObject({ name: 'AbortError' })
    expect(store.api.completeMedia).not.toHaveBeenCalled()
  })

  it('uploads original-only (no derivatives) when derivation throws — e.g. an undecodable HEIC', async () => {
    const store = fakeStore()
    fakeApi(mediaApi, store.api)
    fakeApi(blobApi, store.blob)
    vi.stubGlobal('fetch', store.fetchStub)
    const deriver = stubDeriver()
    deriver.deriveImage = vi.fn(async () => {
      throw new Error('decode_failed') // browser can't decode this codec (HEIC off Safari)
    })

    const { mediaId } = await addMedia(SCOPE_ID, NODE_ID, v1.randomBytes(32), { blob: new Blob([new Uint8Array([1, 2, 3]) as BlobPart]), mediaType: 'image/heic' }, deriver)

    expect(mediaId).toBe(store.captured.completeItem.mediaId)
    expect(store.captured.startItems).toHaveLength(1) // original only — no derivative blobs
    expect(store.captured.completeItem.derivatives).toEqual([])
  })
})

describe('addMedia — the digest beside the derivation (inbox #554)', () => {
  const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms))
  /** An image whose reads are gated: each slice's `arrayBuffer` says it started (`onRead`) and then waits for `gate`. */
  function gatedImage(bytes: Uint8Array, gate: Promise<void>, onRead: () => void) {
    const blob = new Blob([bytes as BlobPart], { type: 'image/png' })
    const slice = blob.slice.bind(blob)
    vi.spyOn(blob, 'slice').mockImplementation((start, end, type) => {
      const part = slice(start, end, type)
      return Object.assign(part, {
        arrayBuffer: async () => {
          onRead()
          await gate
          return Blob.prototype.arrayBuffer.call(part)
        },
      })
    })
    return blob
  }
  const setUp = () => {
    const store = fakeStore()
    fakeApi(mediaApi, store.api)
    fakeApi(blobApi, store.blob)
    vi.stubGlobal('fetch', store.fetchStub)
    return store
  }

  // The digest's first read waits until the derivation has started, and `deriveImage` resolves only once the digest has
  // started reading: an upload that awaits either before starting the other never settles. Bite: await the digest
  // before `deriveTiers` again, and this goes red with 'stuck'.
  it('starts the digest and the derivation together: each waits on the other, and the upload settles', async () => {
    setUp()
    let derivationStarted!: () => void
    const deriving = new Promise<void>((r) => (derivationStarted = r))
    let digestReading!: () => void
    const reading = new Promise<void>((r) => (digestReading = r))
    const deriver = stubDeriver()
    const derive = deriver.deriveImage
    deriver.deriveImage = vi.fn(async (blob: Blob, spec: { maxEdge: number; quality: number }) => {
      derivationStarted()
      await reading
      return derive(blob, spec)
    })
    const bytes = new Uint8Array(CHUNK_SIZE + 9).fill(3)
    const source = gatedImage(bytes, deriving, digestReading)
    const upload = addMedia(SCOPE_ID, NODE_ID, v1.randomBytes(32), { blob: source, mediaType: 'image/png' }, deriver)
    expect(await Promise.race([upload.then(() => 'settled'), sleep(1000).then(() => 'stuck')])).toBe('settled')
    expect(deriver.deriveImage).toHaveBeenCalledTimes(2) // both tiers, so the derivation's result was kept
  })

  // The derivation here never finishes, so the upload can end only by the digest's own rejection: one that waited for
  // both (or swallowed the digest's abort) hangs. Bite: `.catch()` the digest's rejection, and this goes red with 'stuck'.
  it('an abort while the digest reads rejects AbortError without waiting for the derivation, and reserves nothing', async () => {
    const store = setUp()
    let release!: () => void
    const gate = new Promise<void>((r) => (release = r))
    let reads = 0
    const ctl = new AbortController()
    const deriver = stubDeriver()
    deriver.deriveImage = vi.fn(() => new Promise<never>(() => {}))
    const source = gatedImage(new Uint8Array(3 * CHUNK_SIZE).fill(4), gate, () => (reads += 1))
    const upload = addMedia(SCOPE_ID, NODE_ID, v1.randomBytes(32), { blob: source, mediaType: 'image/png' }, deriver, { signal: ctl.signal })
    const outcome = upload.then(() => 'resolved', (e: unknown) => e)
    await sleep(5)
    expect(reads).toBe(1) // the digest is in its first read
    expect(deriver.deriveImage).toHaveBeenCalledTimes(1) // and the derivation runs beside it
    ctl.abort()
    release()
    expect(await Promise.race([outcome, sleep(1000).then(() => 'stuck')])).toMatchObject({ name: 'AbortError' })
    expect(store.blob.startBlobUploads).not.toHaveBeenCalled()
    expect(store.api.completeMedia).not.toHaveBeenCalled()
  })

  it('control: the same upload, unaborted, reserves once', async () => {
    const store = setUp()
    let release!: () => void
    const gate = new Promise<void>((r) => (release = r))
    let reads = 0
    const source = gatedImage(new Uint8Array(3 * CHUNK_SIZE).fill(4), gate, () => (reads += 1))
    const upload = addMedia(SCOPE_ID, NODE_ID, v1.randomBytes(32), { blob: source, mediaType: 'image/png' }, stubDeriver(), { signal: new AbortController().signal })
    await sleep(5)
    expect(reads).toBe(1)
    release()
    await upload
    expect(store.blob.startBlobUploads).toHaveBeenCalledTimes(1)
    expect(store.api.completeMedia).toHaveBeenCalledTimes(1)
  })
})

describe('addMedia — E2EE-sealed layout + EXIF tiers (cr-0059/cr-0064)', () => {
  const CAPTURED_AT = Date.UTC(2021, 4, 1, 10, 0, 0) // 2021-05-01T10:00:00Z

  afterEach(() => {
    vi.mocked(exifr.parse).mockReset()
    vi.mocked(exifr.parse).mockResolvedValue(undefined)
  })

  it('seals the layout tier {v,w,h} (geometry only) under K_node and round-trips it through listMedia', async () => {
    const store = fakeStore()
    fakeApi(mediaApi, store.api)
    fakeApi(blobApi, store.blob)
    vi.stubGlobal('fetch', store.fetchStub)
    const deriver = stubDeriver()
    deriver.probeDimensions = vi.fn(async () => ({ w: 1920, h: 1080 }))
    vi.mocked(exifr.parse).mockResolvedValue({ DateTimeOriginal: new Date(CAPTURED_AT), Make: 'Canon' })

    const memoryKey = v1.randomBytes(32)
    await addMedia(SCOPE_ID, NODE_ID, memoryKey, { blob: new Blob([new Uint8Array(10).fill(7) as BlobPart]), mediaType: 'image/png' }, deriver)

    // the wire carries only opaque ciphertext — never the plaintext dimensions
    const sealed = store.captured.completeItem.wrappedMeta
    expect(typeof sealed).toBe('string')
    expect(sealed).not.toContain('1920')

    const [item] = await listMedia(SCOPE_ID, NODE_ID)
    const meta = unsealMeta(SCOPE_ID, memoryKey, item)
    expect(meta).toMatchObject({ v: 1, w: 1920, h: 1080 }) // geometry rides the listed metadata tier (cr-0068)
    // a wrong K_node can't open it (bound to the Memory key)
    expect(unsealMeta(SCOPE_ID, v1.randomBytes(32), item)).toBeNull()
  })

  it('seals date + GPS in the listed tier, a date/gps-free EXIF tier lazy alongside', async () => {
    const store = fakeStore()
    fakeApi(mediaApi, store.api)
    fakeApi(blobApi, store.blob)
    vi.stubGlobal('fetch', store.fetchStub)
    const deriver = stubDeriver()
    deriver.probeDimensions = vi.fn(async () => ({ w: 800, h: 600 }))
    vi.mocked(exifr.parse).mockResolvedValue({
      DateTimeOriginal: new Date(CAPTURED_AT),
      Make: 'Canon', Model: 'EOS R', LensModel: 'RF 50mm', FNumber: 1.8, ISO: 200, latitude: 48.137, longitude: 11.575,
    })

    const memoryKey = v1.randomBytes(32)
    await addMedia(SCOPE_ID, NODE_ID, memoryKey, { blob: new Blob([new Uint8Array(10).fill(7) as BlobPart]), mediaType: 'image/jpeg' }, deriver)

    // the capture time + GPS are sealed in the always-listed metadata tier (opaque — no plaintext on the wire)
    const sealedMeta = store.captured.completeItem.wrappedMeta
    expect(typeof sealedMeta).toBe('string')
    expect(sealedMeta).not.toContain('2021')
    expect(sealedMeta).not.toContain('48.137')
    // the EXIF tier is sealed opaque and **date- AND gps-free** — camera only (cr-0068)
    const sealedExif = store.captured.completeItem.wrappedExif
    expect(sealedExif).not.toContain('Canon')
    const exif = openJson(exifCtx(store.captured.completeItem.mediaId), memoryKey, sealedExif!)
    expect(exif).toMatchObject({ v: 1, make: 'Canon', model: 'EOS R', lens: 'RF 50mm', fNumber: 1.8, iso: 200 })
    expect(exif.capturedAt).toBeUndefined()
    expect(exif.gpsLat).toBeUndefined() // GPS moved to the listed tier (cr-0068)
    expect(exif.gpsLng).toBeUndefined()

    // listMedia carries the metadata tier (for date sorting + the place medoid) but NOT the EXIF tier
    const [item] = await listMedia(SCOPE_ID, NODE_ID)
    expect(item.wrappedMeta).toBeDefined()
    expect((item as Record<string, unknown>).wrappedExif).toBeUndefined()
    const meta = unsealMeta(SCOPE_ID, memoryKey, item)
    expect(effectiveCapturedAt(meta)).toBe(CAPTURED_AT)
    expect(meta).toMatchObject({ gpsLat: 48.137, gpsLng: 11.575 }) // GPS available at list-level for the medoid
  })

  // B1.1 / B1.3 / B1.5 / B1.6 in one pass over one upload: everything the panel shows must reach the LAZY
  // tier, the filename must reach it too, the LISTED tier must not grow any of it (it is capped at 512 base64
  // and shared with the capture-time PUT), and the filename must not appear in anything sent.
  //
  // The raw shapes below are not invented: they are what the un-mocked library actually returns for the
  // generated fixture, pinned by `readExif.fixture.test.ts`. Keep the two in step — that test is the only
  // thing standing between this mock and a green lie.
  it('seals the panel fields and the filename in the LAZY tier, and leaks neither into the listed one nor the wire', async () => {
    const store = fakeStore()
    fakeApi(mediaApi, store.api)
    fakeApi(blobApi, store.blob)
    vi.stubGlobal('fetch', store.fetchStub)
    const deriver = stubDeriver()
    deriver.sourceDims = { w: 4032, h: 3024 }
    vi.mocked(exifr.parse).mockResolvedValue({
      Make: 'Canon', Model: 'EOS R', LensModel: 'RF 50mm F1.8 STM',
      FocalLength: 50, FocalLengthIn35mmFormat: 75, FNumber: 1.8, ExposureTime: 0.002, ISO: 200,
      ExposureCompensation: -0.7, Flash: 9, Orientation: 6,
      DateTimeOriginal: new Date(CAPTURED_AT),
      Software: 'veyq fixture 1.0', Artist: 'A. Photographer', Copyright: '(c) 2021 A. Photographer',
      Rating: 4,
      title: { lang: 'x-default', value: 'Marienplatz' },
      description: { lang: 'x-default', value: 'Marienplatz at golden hour' },
      GPSAltitude: 519.4, GPSAltitudeRef: { 0: 0 },
      GPSImgDirection: 215.3, GPSImgDirectionRef: 'T', GPSHPositioningError: 4.8,
      latitude: 48.137, longitude: 11.575,
    })

    const memoryKey = v1.randomBytes(32)
    const FILENAME = 'IMG_0042_holiday.HEIC'
    await addMedia(SCOPE_ID, NODE_ID, memoryKey, { blob: new Blob([new Uint8Array(12).fill(4) as BlobPart]), mediaType: 'image/jpeg', name: FILENAME }, deriver)

    // B1.1 + B1.3 — the lazy tier carries every new field; the filename is the original's blob meta (inbox #397)
    const exif = openJson(exifCtx(store.captured.completeItem.mediaId), memoryKey, store.captured.completeItem.wrappedExif!)
    expect(exif.name).toBeUndefined()
    expect(exif).toMatchObject({
      focalLength35: 75, exposureCompensation: -0.7, flash: 9, orientation: 6,
      software: 'veyq fixture 1.0', artist: 'A. Photographer', rating: 4,
      title: 'Marienplatz', description: 'Marienplatz at golden hour',
      altitude: 519.4, imgDirection: 215.3, imgDirectionRef: 'T', gpsAccuracy: 4.8,
    })
    expect(exif.copyright).toContain('A. Photographer')

    // B1.5 — the LISTED tier grew nothing. Asserted as an allow-list rather than field by field, so a future
    // panel-only field added to the wrong tier reddens here without anyone remembering to add a case.
    // Read the SEALED payload, not `unsealMeta`'s normalised view: that view materialises every key of the
    // type (`capturedAtOverride` among them) whether or not it was sealed, so keying the assertion off it
    // would measure the normaliser instead of what actually rides in the capped tier.
    const [item] = await listMedia(SCOPE_ID, NODE_ID)
    const sealedMeta = openJson(metaCtx(item.mediaId), memoryKey, item.wrappedMeta!)
    expect(Object.keys(sealedMeta).sort()).toEqual(
      ['capturedAt', 'capturedAtSource', 'gpsLat', 'gpsLng', 'h', 'v', 'w'].sort(),
    )
    expect(originalBlobMeta(SCOPE_ID, memoryKey, item)).toMatchObject({ type: 'image/jpeg', name: FILENAME })

    // B1.6 — nothing the client SENT carries the filename. uc.memory.gallery's "no pixel, no filename" is a
    // claim about the server's view, and this is what keeps it true now that the name exists at all.
    expect(JSON.stringify(store.captured)).not.toContain('IMG_0042')
    expect(JSON.stringify(store.captured)).not.toContain('holiday')
  })

  // B2.3 / B2.4 / B2.7 — the three ways sealing a place name can go wrong, each with its own lever.
  it('seals a place name read-modify-write, keeps a name-only tier, and stays inside the 512 cap', async () => {
    const store = fakeStore()
    fakeApi(mediaApi, store.api)
    fakeApi(blobApi, store.blob)
    vi.stubGlobal('fetch', store.fetchStub)
    const deriver = stubDeriver()
    deriver.sourceDims = { w: 4032, h: 3024 }
    vi.mocked(exifr.parse).mockResolvedValue({
      DateTimeOriginal: new Date(CAPTURED_AT), latitude: 48.137, longitude: 11.575, Make: 'Canon',
    })
    const memoryKey = v1.randomBytes(32)
    await addMedia(SCOPE_ID, NODE_ID, memoryKey, { blob: new Blob([new Uint8Array(12).fill(4) as BlobPart]), mediaType: 'image/jpeg', lastModified: 1 }, deriver)
    const [item] = await listMedia(SCOPE_ID, NODE_ID)
    const meta = unsealMeta(SCOPE_ID, memoryKey, item)!
    const withOverride = { ...meta, capturedAtOverride: Date.UTC(2019, 7, 15) }

    // B2.3 — re-sealing the raw tier is what keeps the date and the GPS. Lever: seal `{ v: 1, placeName }` instead.
    const saved = await backfillPlaceName(SCOPE_ID, target(item.mediaId, memoryKey, withOverride), memoryKey, 0, 'München', 'city')
    expect(saved.tier).toMatchObject({
      placeName: 'München', placeLevel: 'city',
      capturedAtOverride: Date.UTC(2019, 7, 15), gpsLat: 48.137, gpsLng: 11.575, w: 4032,
    })

    // B2.4 — a tier whose ONLY content is a name must not seal to null, or the name is resolved again on every
    // open. Lever: make `metaHasContent` ignore `placeName`.
    const nameOnly = await backfillPlaceName(SCOPE_ID, target(item.mediaId, memoryKey, null), memoryKey, 0, 'Formentera', 'island')
    expect(nameOnly.wrappedMeta).not.toBeNull()
    expect(nameOnly.tier).toMatchObject({ placeName: 'Formentera' })

    // B2.7 — the cap. The listed tier is 512 base64 and `persistMeta` is SHARED with the capture-time PUT, so
    // an overflowing name breaks that write too. Worst case: every field populated plus a maximal name.
    // Lever: remove the clamp in `backfillPlaceName`.
    const maximal = await backfillPlaceName(
      SCOPE_ID, target(item.mediaId, memoryKey, { ...withOverride, capturedAtSource: 'exif' }), memoryKey, 0,
      'Sant Francesc de Formentera i les Illes Balears de la Mediterrània Occidental'.repeat(4),
      'country',
    )
    expect(maximal.wrappedMeta!.length).toBeLessThanOrEqual(512)
    expect(new TextEncoder().encode(maximal.tier!.placeName!).length).toBeLessThanOrEqual(PLACE_NAME_MAX_BYTES)
  })

  // The EXIF tier is capped at `wrappedExif.maxLength` (16384 base64) and the server rejects a longer one, so
  // free text an editor wrote must be shortened on the client — the upload never fails for its metadata.
  // Lever: seal `meta.exif` as-is in `sealMetadata`.
  it('fits a tier with a 20 000-character description under the contract cap and keeps every number', async () => {
    const store = fakeStore()
    fakeApi(mediaApi, store.api)
    fakeApi(blobApi, store.blob)
    vi.stubGlobal('fetch', store.fetchStub)
    vi.mocked(exifr.parse).mockResolvedValue({
      Make: 'Canon', FNumber: 1.8, ISO: 200, FocalLength: 50, Rating: 4,
      description: { lang: 'x-default', value: 'ä'.repeat(20_000) },
    })
    const memoryKey = v1.randomBytes(32)
    await addMedia(SCOPE_ID, NODE_ID, memoryKey, { blob: new Blob([new Uint8Array(12).fill(4) as BlobPart]), mediaType: 'image/jpeg' }, stubDeriver())

    const sealed = store.captured.completeItem.wrappedExif as string
    expect(sealed.length).toBeLessThanOrEqual(EXIF_TIER_MAX_B64)
    const exif = openJson(exifCtx(store.captured.completeItem.mediaId), memoryKey, sealed)
    expect(exif).toMatchObject({ make: 'Canon', fNumber: 1.8, iso: 200, focalLength: 50, rating: 4 })
    // shortened, not dropped, and never split inside a two-byte character
    expect(exif.description).toBe('ä'.repeat(EXIF_TEXT_MAX_BYTES / 2))
  })

  // Clamping alone cannot bound the tier: JSON escapes a control character to six bytes, so two clamped fields
  // of them overflow it. The tier then sheds free text, longest first, and keeps its numbers.
  // Lever: remove the drop loop in `sealExifTier`.
  it('drops free text, never numbers, when clamped fields still overflow the cap', async () => {
    const store = fakeStore()
    fakeApi(mediaApi, store.api)
    fakeApi(blobApi, store.blob)
    vi.stubGlobal('fetch', store.fetchStub)
    const escaped = '\u0001'.repeat(5_000)
    vi.mocked(exifr.parse).mockResolvedValue({
      Make: 'Canon', FNumber: 1.8, ISO: 200,
      Artist: escaped, Copyright: escaped, title: { lang: 'x-default', value: 'x' + escaped },
    })
    const memoryKey = v1.randomBytes(32)
    await addMedia(SCOPE_ID, NODE_ID, memoryKey, { blob: new Blob([new Uint8Array(12).fill(4) as BlobPart]), mediaType: 'image/jpeg' }, stubDeriver())

    const sealed = store.captured.completeItem.wrappedExif as string
    expect(sealed.length).toBeLessThanOrEqual(EXIF_TIER_MAX_B64)
    const exif = openJson(exifCtx(store.captured.completeItem.mediaId), memoryKey, sealed)
    expect(exif).toMatchObject({ make: 'Canon', fNumber: 1.8, iso: 200 })
    // the two longest escaped fields went; the one a character shorter then fits and stays
    expect(exif.artist).toBeUndefined()
    expect(exif.copyright).toBeUndefined()
    expect(exif.title).toMatch(/^x\u0001+$/)
  })

  // inbox #397 — every upload seals the original's name, type and modified time into the ORIGINAL's blob meta, under its
  // own K_blob — a video, an image without EXIF and a PDF alike — and into neither metadata tier. Lever: drop the meta
  // from the ORIGINAL's `planBlob` in `uploadMediaVia` (every case reddens).
  it('seals the file name, type and modified time into the original\'s blob meta — never into a tier, never in the clear', async () => {
    const memoryKey = v1.randomBytes(32)
    const cases: { mediaType: string; name: string; exifr: 'none' | 'throws' }[] = [
      { mediaType: 'video/mp4', name: 'VID_2026.mp4', exifr: 'none' },
      { mediaType: 'image/png', name: ' Screenshot 2026-10-09.png ', exifr: 'none' },
      { mediaType: 'image/jpeg', name: 'broken.jpg', exifr: 'throws' },
      { mediaType: 'application/pdf', name: 'Tickets.pdf', exifr: 'none' },
    ]
    for (const c of cases) {
      const store = fakeStore()
      fakeApi(mediaApi, store.api)
      fakeApi(blobApi, store.blob)
      vi.stubGlobal('fetch', store.fetchStub)
      if (c.exifr === 'throws') vi.mocked(exifr.parse).mockRejectedValueOnce(new Error('corrupt'))
      await addMedia(SCOPE_ID, NODE_ID, memoryKey, { blob: new Blob([new Uint8Array(12).fill(4) as BlobPart]), mediaType: c.mediaType, name: c.name, lastModified: 1_700_000_000_000 }, stubDeriver())

      const { mediaId, wrappedExif, wrappedMeta } = store.captured.completeItem
      const [item] = await listMedia(SCOPE_ID, NODE_ID)
      // its SHA-256 too (base64url): what a later upload of the same file into the cover or banner dialog matches (ADR 0013 § 5)
      const sha256 = b64url(v1.sha256Digest(new Uint8Array(12).fill(4)))
      expect(originalBlobMeta(SCOPE_ID, memoryKey, item), c.mediaType).toEqual({ v: 1, type: c.mediaType, name: c.name.trim(), modifiedAt: 1_700_000_000_000, sha256 })
      expect(originalMediaType(SCOPE_ID, memoryKey, item), c.mediaType).toBe(c.mediaType)
      // the derivative tiers declare their own type (the deriver's JPEG) and nothing about the file
      for (const d of item.derivatives) {
        expect(readBlobMeta(SCOPE_ID, memoryKey, d.ref), `${c.mediaType} ${d.kind}`).toEqual({ v: 1, type: 'image/jpeg' })
      }
      // the EXIF tier no longer carries a name (none parsed here, so none sealed), the listed tier neither name nor type
      expect(wrappedExif ?? null, c.mediaType).toBeNull()
      if (wrappedMeta) {
        const keys = Object.keys(openJson(metaCtx(mediaId), memoryKey, wrappedMeta))
        expect(keys, c.mediaType).not.toContain('name')
        expect(keys, c.mediaType).not.toContain('mediaType')
      }
      // and nothing sent carries either in the clear — the declares included
      expect(JSON.stringify(store.captured), c.mediaType).not.toContain(c.name.trim())
      expect(JSON.stringify(store.captured), c.mediaType).not.toContain(c.mediaType)
    }
  })

  // An empty `File.type` (Safari's Photos import) with an extension nothing maps: the type is sniffed from the bytes at
  // upload, sealed in the blob meta, and drives the derivation. Lever: return `given ?? UNKNOWN_TYPE` without sniffing.
  it('sniffs an untyped original\'s type from its first bytes and declares it in the blob meta', async () => {
    const store = fakeStore()
    fakeApi(mediaApi, store.api)
    fakeApi(blobApi, store.blob)
    vi.stubGlobal('fetch', store.fetchStub)
    const deriver = stubDeriver()
    const memoryKey = v1.randomBytes(32)
    const jpeg = new Uint8Array(40).fill(9)
    jpeg.set([0xff, 0xd8, 0xff, 0xe0])
    await addMedia(SCOPE_ID, NODE_ID, memoryKey, { blob: new Blob([jpeg as BlobPart]), mediaType: 'application/octet-stream', name: 'IMG' }, deriver)

    const [item] = await listMedia(SCOPE_ID, NODE_ID)
    expect(originalBlobMeta(SCOPE_ID, memoryKey, item)).toMatchObject({ type: 'image/jpeg', name: 'IMG' })
    expect(deriver.images).toBeGreaterThan(0) // derived as the image it is
  })

  // A foreign ref: another blob's meta served in the original's slot does not open under the original's K_blob — the
  // reader gets null (the lightbox then falls back), never another blob's name. Lever: drop the catch in core's
  // `openBlobMetaOf` (the swap then throws OpenRefused instead of answering null).
  it('a foreign blob meta in the original\'s slot opens to null, never to that blob\'s facts', async () => {
    const store = fakeStore()
    fakeApi(mediaApi, store.api)
    fakeApi(blobApi, store.blob)
    vi.stubGlobal('fetch', store.fetchStub)
    const memoryKey = v1.randomBytes(32)
    await addMedia(SCOPE_ID, NODE_ID, memoryKey, { blob: new Blob([new Uint8Array(12).fill(4) as BlobPart]), mediaType: 'video/mp4', name: 'a.mp4' }, stubDeriver())
    const [item] = await listMedia(SCOPE_ID, NODE_ID)
    const swapped: MediaItem = { ...item, original: { ...item.original, wrappedBlobMeta: item.derivatives[0].ref.wrappedBlobMeta } }
    expect(originalBlobMeta(SCOPE_ID, memoryKey, swapped)).toBeNull()
    expect(originalBlobMeta(SCOPE_ID, v1.randomBytes(32), item)).toBeNull() // another key: the wrap does not open
    expect(originalBlobMeta(SCOPE_ID, memoryKey, { ...item, original: { ...item.original, wrappedBlobMeta: undefined } })).toBeNull()
  })

  it('falls back to the file creation date when the source carries no EXIF date', async () => {
    const store = fakeStore()
    fakeApi(mediaApi, store.api)
    fakeApi(blobApi, store.blob)
    vi.stubGlobal('fetch', store.fetchStub)
    const deriver = stubDeriver()
    deriver.probeDimensions = vi.fn(async () => ({ w: 640, h: 480 }))
    // exifr.parse → undefined (default mock): no readable EXIF; the file's lastModified stands in
    const FILE_MTIME = Date.UTC(2020, 0, 2, 3, 4, 5)

    const memoryKey = v1.randomBytes(32)
    await addMedia(SCOPE_ID, NODE_ID, memoryKey, { blob: new Blob([new Uint8Array([1, 2, 3]) as BlobPart]), mediaType: 'image/png', lastModified: FILE_MTIME }, deriver)

    expect(store.captured.completeItem.wrappedExif ?? null).toBeNull() // no EXIF bundle
    const [item] = await listMedia(SCOPE_ID, NODE_ID)
    const meta = unsealMeta(SCOPE_ID, memoryKey, item)
    expect(meta).toMatchObject({ v: 1, w: 640, h: 480 })
    expect(effectiveCapturedAt(meta)).toBe(FILE_MTIME) // file-date fallback
    expect(meta?.capturedAtSource).toBe('file') // marked untrustworthy for the Memory `auto` range
  })

  it('an import\'s restored facts - a corrected capture time and a place name - are sealed into the listed tier at birth (inbox #700)', async () => {
    const store = fakeStore()
    fakeApi(mediaApi, store.api)
    fakeApi(blobApi, store.blob)
    vi.stubGlobal('fetch', store.fetchStub)
    const deriver = stubDeriver()
    deriver.probeDimensions = vi.fn(async () => ({ w: 640, h: 480 }))
    const FILE_MTIME = Date.UTC(2020, 0, 2, 3, 4, 5)
    const CORRECTED = Date.UTC(2019, 6, 1, 12)
    const LONG_PLACE = 'Ä'.repeat(PLACE_NAME_MAX_BYTES) // two bytes each: clamped to half

    const memoryKey = v1.randomBytes(32)
    await addMedia(SCOPE_ID, NODE_ID, memoryKey, { blob: new Blob([new Uint8Array([1, 2, 3]) as BlobPart]), mediaType: 'image/png', lastModified: FILE_MTIME }, deriver, {
      restored: { capturedAtOverride: CORRECTED, placeName: LONG_PLACE },
    })

    // the one completion carries them: no meta write follows
    expect(store.captured.updatedMeta).toBeUndefined()
    const [item] = await listMedia(SCOPE_ID, NODE_ID)
    const meta = unsealMeta(SCOPE_ID, memoryKey, item)
    expect(meta).toMatchObject({ w: 640, h: 480, capturedAt: FILE_MTIME, capturedAtOverride: CORRECTED, placeName: clampPlaceName(LONG_PLACE) })
    expect(new TextEncoder().encode(meta!.placeName!).length).toBeLessThanOrEqual(PLACE_NAME_MAX_BYTES)
    expect(effectiveCapturedAt(meta)).toBe(CORRECTED)

    // a plain upload restores nothing
    await addMedia(SCOPE_ID, NODE_ID, memoryKey, { blob: new Blob([new Uint8Array([4, 5, 6]) as BlobPart]), mediaType: 'image/png', lastModified: FILE_MTIME }, deriver)
    const plain = openJson(metaCtx(store.captured.completeItem.mediaId), memoryKey, store.captured.completeItem.wrappedMeta!)
    expect([plain.capturedAtOverride, plain.placeName]).toEqual([undefined, undefined])
  })

  // The capture-time key has TWO consumers: the metadata tier sealed at upload, and the in-flight upload tile
  // that must know its grid place BEFORE a byte is uploaded (useOptimisticTiles). They agree because they
  // consume one rule — `captureTimeOf` — and this is the bite that says so rather than assuming it. Lever:
  // drop `lastModified` from the tile-side call and the file-date branch below parts company with the seal.
  it('the picked file’s capture time is the one the tier seals — EXIF date, else the file date', async () => {
    const store = fakeStore()
    fakeApi(mediaApi, store.api)
    fakeApi(blobApi, store.blob)
    vi.stubGlobal('fetch', store.fetchStub)
    const deriver = stubDeriver()
    const FILE_MTIME = Date.UTC(2019, 5, 6, 7, 8, 9)
    const bytes = new Uint8Array([1, 2, 3])
    const asPickedFile = () => new File([bytes as BlobPart], 'p.jpg', { type: 'image/jpeg', lastModified: FILE_MTIME })

    // (a) the file carries a real EXIF date → both sides take it, and the file date is ignored by both
    vi.mocked(exifr.parse).mockResolvedValue({ DateTimeOriginal: new Date(CAPTURED_AT) })
    const keyA = v1.randomBytes(32)
    await addMedia(SCOPE_ID, NODE_ID, keyA, { blob: new Blob([bytes as BlobPart]), mediaType: 'image/jpeg', lastModified: FILE_MTIME }, deriver)
    const sealedA = unsealMeta(SCOPE_ID, keyA, (await listMedia(SCOPE_ID, NODE_ID))[0])
    const tileA = await readCaptureTime(asPickedFile(), 'image/jpeg', FILE_MTIME)
    expect(tileA.capturedAt).toBe(sealedA?.capturedAt)
    expect(tileA.capturedAt).toBe(CAPTURED_AT)
    expect(tileA.capturedAtSource).toBe(sealedA?.capturedAtSource)

    // (b) no readable EXIF → both fall back to the file's own date. This is the half the lever removes.
    vi.mocked(exifr.parse).mockResolvedValue(undefined)
    const keyB = v1.randomBytes(32)
    await addMedia(SCOPE_ID, NODE_ID, keyB, { blob: new Blob([bytes as BlobPart]), mediaType: 'image/jpeg', lastModified: FILE_MTIME }, deriver)
    const listed = await listMedia(SCOPE_ID, NODE_ID)
    const sealedB = unsealMeta(SCOPE_ID, keyB, listed[listed.length - 1])
    const tileB = await readCaptureTime(asPickedFile(), 'image/jpeg', FILE_MTIME)
    expect(tileB.capturedAt).toBe(sealedB?.capturedAt)
    expect(tileB.capturedAt).toBe(FILE_MTIME)
    expect(tileB.capturedAtSource).toBe('file')
  })

  it('seals no capture date in the tier when there is neither an EXIF date nor a file date', async () => {
    const store = fakeStore()
    fakeApi(mediaApi, store.api)
    fakeApi(blobApi, store.blob)
    vi.stubGlobal('fetch', store.fetchStub)
    const deriver = stubDeriver()
    deriver.probeDimensions = vi.fn(async () => ({ w: 640, h: 480 }))

    const memoryKey = v1.randomBytes(32)
    await addMedia(SCOPE_ID, NODE_ID, memoryKey, { blob: new Blob([new Uint8Array([1, 2, 3]) as BlobPart]), mediaType: 'image/png' }, deriver)

    const [item] = await listMedia(SCOPE_ID, NODE_ID)
    const meta = unsealMeta(SCOPE_ID, memoryKey, item)
    expect(effectiveCapturedAt(meta)).toBeUndefined() // no date → sorts last
    expect(meta).toMatchObject({ w: 640, h: 480 }) // geometry still rides the tier
  })

  it('seals no listed tier for an undecodable, undated source — its type rides the blob meta alone', async () => {
    const store = fakeStore()
    fakeApi(mediaApi, store.api)
    fakeApi(blobApi, store.blob)
    vi.stubGlobal('fetch', store.fetchStub)
    const deriver = stubDeriver()
    deriver.probeDimensions = vi.fn(async () => null) // e.g. a non-image / undecodable codec

    const memoryKey = v1.randomBytes(32)
    // No geometry, no date, no GPS: nothing for the listed tier. The type (the video signal, #104/#105) is the
    // original's blob meta since inbox #397, so it no longer keeps an otherwise empty tier alive.
    await addMedia(SCOPE_ID, NODE_ID, memoryKey, { blob: new Blob([new Uint8Array([1, 2, 3]) as BlobPart]), mediaType: 'application/pdf' }, deriver)

    expect(store.captured.completeItem.wrappedExif ?? null).toBeNull()
    expect(store.captured.completeItem.wrappedMeta ?? null).toBeNull()
    const [item] = await listMedia(SCOPE_ID, NODE_ID)
    expect(unsealMeta(SCOPE_ID, memoryKey, item)).toBeNull()
    expect(originalMediaType(SCOPE_ID, memoryKey, item)).toBe('application/pdf')
  })

  it('a deriver with no probeDimensions still declares the type (best-effort — probe optional)', async () => {
    const store = fakeStore()
    fakeApi(mediaApi, store.api)
    fakeApi(blobApi, store.blob)
    vi.stubGlobal('fetch', store.fetchStub)

    const memoryKey = v1.randomBytes(32)
    await addMedia(SCOPE_ID, NODE_ID, memoryKey, { blob: new Blob([new Uint8Array([1]) as BlobPart]), mediaType: 'image/png' }, stubDeriver())
    const [item] = await listMedia(SCOPE_ID, NODE_ID)
    expect(originalMediaType(SCOPE_ID, memoryKey, item)).toBe('image/png')
  })

  it('seals NO metadata tier when there is truly nothing — an empty MIME + no derivable metadata', async () => {
    const store = fakeStore()
    fakeApi(mediaApi, store.api)
    fakeApi(blobApi, store.blob)
    vi.stubGlobal('fetch', store.fetchStub)

    const memoryKey = v1.randomBytes(32)
    await addMedia(SCOPE_ID, NODE_ID, memoryKey, { blob: new Blob([new Uint8Array([1]) as BlobPart]), mediaType: '' }, stubDeriver())
    expect(store.captured.completeItem.wrappedMeta ?? null).toBeNull()
    const [item] = await listMedia(SCOPE_ID, NODE_ID)
    expect(unsealMeta(SCOPE_ID, memoryKey, item)).toBeNull()
  })
})

describe('setMediaMeta — sealed capturedAt override in the listed-metadata tier (cr-0066/0068)', () => {
  const ORIG = Date.UTC(2021, 4, 1, 10, 0, 0)
  const CORRECTED = Date.UTC(2019, 7, 15, 8, 30, 0)
  const MEDIA_ID = '5c09e000-0000-4000-8000-000000000a01'

  function decodeMeta(memoryKey: Uint8Array, sealed: string): MediaMeta {
    return openJson(metaCtx(MEDIA_ID), memoryKey, sealed)
  }

  it('re-seals the tier with the override, keeping the original capturedAt + geometry/GPS (operator-blind)', async () => {
    const store = fakeStore()
    fakeApi(mediaApi, store.api)
    fakeApi(blobApi, store.blob)
    const memoryKey = v1.randomBytes(32)
    // a full listed tier: geometry + date + GPS. Only the date is user-editable; the rest rides along unchanged.
    const captured: MediaMeta = { v: 1, w: 800, h: 600, capturedAt: ORIG, gpsLat: 48.1, gpsLng: 11.5 }

    const updated = await setMediaMeta(SCOPE_ID, target(MEDIA_ID, memoryKey, captured, 3), memoryKey, 0, CORRECTED)

    expect(store.api.updateMediaMeta.mock.calls[0][0].mediaMetaUpdate.metaRevision).toBe(3) // the revision it read
    expect(updated.metaRevision).toBe(4)
    // the wire carries only opaque ciphertext — never the plaintext date
    const sealed = store.captured.updatedMeta
    expect(typeof sealed).toBe('string')
    expect(sealed).not.toContain('2019')
    // the sealed tier keeps the original + geometry + GPS, and adds the override
    expect(decodeMeta(memoryKey, sealed!)).toEqual({ v: 1, w: 800, h: 600, capturedAt: ORIG, gpsLat: 48.1, gpsLng: 11.5, capturedAtOverride: CORRECTED })
    // effective time now prefers the correction
    expect(effectiveCapturedAt(updated.tier)).toBe(CORRECTED)
    // a wrong K_node can't open it
    expect(() => decodeMeta(v1.randomBytes(32), sealed!)).toThrow()
  })

  it('clears the override (null) back to the original', async () => {
    const store = fakeStore()
    fakeApi(mediaApi, store.api)
    fakeApi(blobApi, store.blob)
    const memoryKey = v1.randomBytes(32)
    const captured: MediaMeta = { v: 1, capturedAt: ORIG, capturedAtOverride: CORRECTED }

    const updated = await setMediaMeta(SCOPE_ID, target(MEDIA_ID, memoryKey, captured), memoryKey, 0, null)

    expect(decodeMeta(memoryKey, store.captured.updatedMeta!)).toEqual({ v: 1, capturedAt: ORIG })
    expect(effectiveCapturedAt(updated.tier)).toBe(ORIG) // falls back to the derived original
  })

  it('clearing an override with no other content drops the whole tier to null', async () => {
    const store = fakeStore()
    fakeApi(mediaApi, store.api)
    fakeApi(blobApi, store.blob)
    const memoryKey = v1.randomBytes(32)
    const captured: MediaMeta = { v: 1, capturedAtOverride: CORRECTED } // only ever had a manual date, no geometry/GPS

    const updated = await setMediaMeta(SCOPE_ID, target(MEDIA_ID, memoryKey, captured), memoryKey, 0, null)

    expect(store.captured.updatedMeta).toBeNull() // nothing left to seal → the tier is cleared
    expect(updated.tier).toBeNull()
  })

  it('sets a date on a media with no metadata tier at all (fresh tier)', async () => {
    const store = fakeStore()
    fakeApi(mediaApi, store.api)
    fakeApi(blobApi, store.blob)
    const memoryKey = v1.randomBytes(32)

    const updated = await setMediaMeta(SCOPE_ID, target(MEDIA_ID, memoryKey, null), memoryKey, 0, CORRECTED)

    expect(decodeMeta(memoryKey, store.captured.updatedMeta!)).toEqual({ v: 1, capturedAtOverride: CORRECTED })
    expect(effectiveCapturedAt(updated.tier)).toBe(CORRECTED)
  })

  /**
   * W8 (live defect, ADR 0077 point 4): a date correction re-seals the RAW tier, so a resolved place name — and any
   * field a newer client wrote — survives. Before, the writers spread `unsealMeta`'s projection, which carried
   * neither, and every correction erased the name. `unsealMeta` now reads the name back too.
   */
  it('W8: a correction keeps the resolved place name and any unknown field; unsealMeta reads the name', async () => {
    const store = fakeStore()
    fakeApi(mediaApi, store.api)
    fakeApi(blobApi, store.blob)
    const memoryKey = v1.randomBytes(32)
    const tier = { v: 1, capturedAt: ORIG, gpsLat: 38.7, gpsLng: -9.1, placeName: 'Lisboa', placeLevel: 'city', future: 'kept' }
    const media = target(MEDIA_ID, memoryKey, tier)
    expect(unsealMeta(SCOPE_ID, memoryKey, mediaItem(media))?.placeName).toBe('Lisboa')

    await setMediaMeta(SCOPE_ID, media, memoryKey, 0, CORRECTED)

    expect(decodeMeta(memoryKey, store.captured.updatedMeta!)).toEqual({ ...tier, capturedAtOverride: CORRECTED })
  })

  /**
   * W4-shaped, for the editor (ADR 0077): the correction meets a 409 whose tier holds a peer's place name — the
   * retry re-applies only the override on it. A peer's OWN correction meanwhile is a same-field conflict instead:
   * no retry, the lightbox re-opens on it.
   */
  it('re-applies the override on the conflict tier; a peer\'s own correction surfaces as a conflict', async () => {
    const memoryKey = v1.randomBytes(32)
    const peers = sealTier(MEDIA_ID, memoryKey, { v: 1, capturedAt: ORIG, placeName: 'Lisboa' })
    const updateMediaMeta = vi.fn<MediaApi['updateMediaMeta']>().mockRejectedValueOnce(staleTier(peers, 2)).mockResolvedValue({})
    fakeApi(mediaApi, { updateMediaMeta })

    const updated = await setMediaMeta(SCOPE_ID, target(MEDIA_ID, memoryKey, { v: 1, capturedAt: ORIG }, 1), memoryKey, 0, CORRECTED)
    expect(updateMediaMeta.mock.calls[1][0].mediaMetaUpdate.metaRevision).toBe(2)
    expect(decodeMeta(memoryKey, updateMediaMeta.mock.calls[1][0].mediaMetaUpdate.wrappedMeta!)).toEqual({
      v: 1, capturedAt: ORIG, placeName: 'Lisboa', capturedAtOverride: CORRECTED,
    })
    expect(updated).toMatchObject({ metaRevision: 3, wrote: true })

    const corrected = sealTier(MEDIA_ID, memoryKey, { v: 1, capturedAt: ORIG, capturedAtOverride: 1 })
    const conflicting = vi.fn<MediaApi['updateMediaMeta']>().mockRejectedValueOnce(staleTier(corrected, 2)).mockResolvedValue({})
    fakeApi(mediaApi, { updateMediaMeta: conflicting })
    const err = await setMediaMeta(SCOPE_ID, target(MEDIA_ID, memoryKey, { v: 1, capturedAt: ORIG }, 1), memoryKey, 0, CORRECTED).catch((e) => e)
    expect(err).toBeInstanceOf(MetaConflictError)
    expect(conflicting).toHaveBeenCalledTimes(1)
  })
})

describe('the media type — the persisted video signal (cr-inline-video-playback, #104/#105; blob meta since inbox #397)', () => {
  const MEDIA_ID = '5c09e000-0000-4000-8000-000000000a01'
  function decodeMeta(memoryKey: Uint8Array, sealed: string): MediaMeta {
    return openJson(metaCtx(MEDIA_ID), memoryKey, sealed)
  }

  it('isVideoMediaType matches only video/* MIME types', () => {
    expect(isVideoMediaType('video/mp4')).toBe(true)
    expect(isVideoMediaType('video/webm')).toBe(true)
    expect(isVideoMediaType('video/x-matroska')).toBe(true)
    expect(isVideoMediaType('image/jpeg')).toBe(false)
    expect(isVideoMediaType('application/pdf')).toBe(false)
    expect(isVideoMediaType(undefined)).toBe(false)
    expect(isVideoMediaType('')).toBe(false)
  })

  // The sealed mediaType is written by the WRITE_ONLY guest's client, and the playback shim serves it back as the
  // response's Content-Type on the app origin. A browser opening a listed type as a page resolves it to its last
  // valid entry, so `video/mp4, text/html` would pass a prefix check and render as a document (ADR 0047 § The
  // shim serves inert responses). Only ONE well-formed `video/<subtype>` essence counts as a video.
  it('isVideoMediaType refuses anything but a single well-formed video essence', () => {
    expect(isVideoMediaType('video/mp4, text/html')).toBe(false)
    expect(isVideoMediaType('video/mp4,text/html')).toBe(false)
    expect(isVideoMediaType('video/mp4; codecs="avc1"')).toBe(false)
    expect(isVideoMediaType('video/mp4;charset=utf-8')).toBe(false)
    expect(isVideoMediaType('video/mp4 ')).toBe(false)
    expect(isVideoMediaType(' video/mp4')).toBe(false)
    expect(isVideoMediaType('video/')).toBe(false)
    expect(isVideoMediaType('video/mp4/html')).toBe(false)
    expect(isVideoMediaType('video/mp4\ntext/html')).toBe(false)
  })

  it('every chunk PUT carries exactly the pinned Content-Type (ADR 0013 §6)', async () => {
    const store = fakeStore()
    fakeApi(mediaApi, store.api)
    fakeApi(blobApi, store.blob)
    vi.stubGlobal('fetch', store.fetchStub)

    const memoryKey = v1.randomBytes(32)
    await addMedia(SCOPE_ID, NODE_ID, memoryKey, { blob: new Blob([new Uint8Array([1, 2, 3]) as BlobPart]), mediaType: 'text/html' }, stubDeriver())

    const puts = store.fetchStub.mock.calls.filter(([, init]) => init?.method === 'PUT')
    expect(puts.length).toBeGreaterThan(0)
    for (const [, init] of puts) {
      expect(new Headers(init!.headers).get('Content-Type')).toBe('application/octet-stream') // never the file's type
    }
  })

  it('seals the video type into the original\'s blob meta at upload — not into the listed tier', async () => {
    const store = fakeStore()
    fakeApi(mediaApi, store.api)
    fakeApi(blobApi, store.blob)
    vi.stubGlobal('fetch', store.fetchStub)

    const memoryKey = v1.randomBytes(32)
    await addMedia(SCOPE_ID, NODE_ID, memoryKey, { blob: new Blob([new Uint8Array([1, 2, 3]) as BlobPart]), mediaType: 'video/mp4' }, stubDeriver())

    // the wire is opaque ciphertext — the MIME never crosses in the clear
    expect(JSON.stringify(store.captured)).not.toContain('video/mp4')
    const [item] = await listMedia(SCOPE_ID, NODE_ID)
    expect(originalMediaType(SCOPE_ID, memoryKey, item)).toBe('video/mp4')
    expect(isVideoMediaType(originalMediaType(SCOPE_ID, memoryKey, item))).toBe(true)
    // the listed tier carries no type any more (inbox #397)
    if (item.wrappedMeta) expect(Object.keys(openJson(metaCtx(item.mediaId), memoryKey, item.wrappedMeta))).not.toContain('mediaType')
  })

  /** W7 (#273, live defect): a backfill over a tier that does not open makes no PUT. Before, the (since removed,
   *  inbox #397) type backfill PUT a tier holding only the sniffed type, and the capture time and GPS sealed in the
   *  unopenable one were gone; the place-name backfill shares that write path. */
  it('W7: a backfill over a tier that does not open makes no PUT', async () => {
    const store = fakeStore()
    fakeApi(mediaApi, store.api)
    fakeApi(blobApi, store.blob)
    const memoryKey = v1.randomBytes(32)
    const elsewhere = target(MEDIA_ID, v1.randomBytes(32), { v: 1, capturedAt: 1, gpsLat: 1, gpsLng: 2 })

    await expect(backfillPlaceName(SCOPE_ID, elsewhere, memoryKey, 0, 'Lisboa')).rejects.toBeInstanceOf(MetaUnreadableError)
    expect(store.api.updateMediaMeta).not.toHaveBeenCalled()
    // control: the same backfill over a tier that opens does PUT
    await backfillPlaceName(SCOPE_ID, target(MEDIA_ID, memoryKey, { v: 1, capturedAt: 1 }), memoryKey, 0, 'Lisboa')
    expect(store.api.updateMediaMeta).toHaveBeenCalledTimes(1)
  })

  /**
   * W4 (ADR 0077): the place-name backfill starts from a tier without a correction, meets a 409 whose tier has a
   * peer's `capturedAtOverride` — the retry keeps the override. And a backfill the fresh tier no longer needs (a
   * peer resolved the name meanwhile) writes nothing.
   */
  it('W4: the backfill re-bases on the conflict tier and keeps a peer\'s correction; a filled field is not re-sent', async () => {
    const memoryKey = v1.randomBytes(32)
    const corrected = sealTier(MEDIA_ID, memoryKey, { v: 1, capturedAt: 10, capturedAtOverride: 20, gpsLat: 1, gpsLng: 2 })
    const updateMediaMeta = vi.fn<MediaApi['updateMediaMeta']>().mockRejectedValueOnce(staleTier(corrected, 4)).mockResolvedValue({})
    fakeApi(mediaApi, { updateMediaMeta })

    await backfillPlaceName(SCOPE_ID, target(MEDIA_ID, memoryKey, { v: 1, capturedAt: 10, gpsLat: 1, gpsLng: 2 }, 3), memoryKey, 0, 'Lisboa', 'city')
    expect(decodeMeta(memoryKey, updateMediaMeta.mock.calls[1][0].mediaMetaUpdate.wrappedMeta!)).toMatchObject({
      capturedAtOverride: 20, placeName: 'Lisboa',
    })

    const named = sealTier(MEDIA_ID, memoryKey, { v: 1, capturedAt: 10, placeName: 'Lisbon' })
    const late = vi.fn<MediaApi['updateMediaMeta']>().mockRejectedValueOnce(staleTier(named, 5)).mockResolvedValue({})
    fakeApi(mediaApi, { updateMediaMeta: late })
    const result = await backfillPlaceName(SCOPE_ID, target(MEDIA_ID, memoryKey, { v: 1, capturedAt: 10 }, 4), memoryKey, 0, 'Lisboa')
    expect(late).toHaveBeenCalledTimes(1)
    expect(result).toMatchObject({ wrote: false, metaRevision: 5, wrappedMeta: named })
  })
})

describe('the upload pump — read, seal and send one frame at a time (ADR 0013 §2 + Consequences)', () => {
  /** A typed source of `chunks` frames (the last one short), with spies on the two ways to read it. */
  function spiedSource(chunks: number, mediaType = 'application/pdf') {
    const blob = new Blob([new Uint8Array((chunks - 1) * CHUNK_SIZE + 7).fill(5)], { type: mediaType })
    return { blob, slice: vi.spyOn(blob, 'slice'), arrayBuffer: vi.spyOn(blob, 'arrayBuffer') }
  }
  const upload = (store: ReturnType<typeof fakeStore>, blob: Blob, mediaType = 'application/pdf') => {
    fakeApi(mediaApi, store.api)
    fakeApi(blobApi, store.blob)
    vi.stubGlobal('fetch', store.fetchStub)
    return addMedia(SCOPE_ID, NODE_ID, v1.randomBytes(32), { blob, mediaType }, stubDeriver())
  }

  it('reads the original as one slice per frame and never as a whole - its digest first, then the pump', async () => {
    const source = spiedSource(3)
    await upload(fakeStore(), source.blob)
    expect(source.arrayBuffer).not.toHaveBeenCalled()
    const size = source.blob.size
    // the digest (`blob-meta` `sha256`, ADR 0013 § 5) reads every frame once, bounded by the size; the pump seals each
    const digest = [0, 1, 2].map((i) => [i * CHUNK_SIZE, Math.min(size, (i + 1) * CHUNK_SIZE)])
    const pump = [0, 1, 2].map((i) => [i * CHUNK_SIZE, (i + 1) * CHUNK_SIZE])
    expect(source.slice.mock.calls).toEqual([...digest, ...pump])
  })

  it('an original whose digest core remembered (an imported payload, inbox #555) is read by the pump alone', async () => {
    const source = spiedSource(3)
    // a value no hash of these bytes gives: the sealed `sha256` can only have come from what core remembered
    const remembered = new Uint8Array(32).fill(0xa5)
    rememberDigest(source.blob, remembered)
    const store = fakeStore()
    fakeApi(mediaApi, store.api)
    fakeApi(blobApi, store.blob)
    vi.stubGlobal('fetch', store.fetchStub)
    const memoryKey = v1.randomBytes(32)
    await addMedia(SCOPE_ID, NODE_ID, memoryKey, { blob: source.blob, mediaType: 'application/pdf' }, stubDeriver())
    const pump = [0, 1, 2].map((i) => [i * CHUNK_SIZE, (i + 1) * CHUNK_SIZE])
    expect(source.slice.mock.calls, 'no digest pass: only the pump reads the original').toEqual(pump)
    const [item] = await listMedia(SCOPE_ID, NODE_ID)
    expect(originalBlobMeta(SCOPE_ID, memoryKey, item)?.sha256).toBe(b64url(remembered))
  })

  it('never has more than PUMP_IN_FLIGHT PUTs in flight for a many-chunk blob', async () => {
    const store = fakeStore({ putDelayMs: 5 })
    await upload(store, spiedSource(12).blob)
    expect(store.events.filter((e) => e.startsWith('put:'))).toHaveLength(12)
    expect(store.inFlight.max).toBeLessThanOrEqual(PUMP_IN_FLIGHT)
    expect(store.inFlight.max).toBeGreaterThan(1) // control: the bound is a bound, not a sequential pump
  })

  it('asks for the next window before the signed range runs out', async () => {
    // Slow PUTs make the order a property of the pump, not of the event loop: a pump that asked only once it held
    // no URL would reach `window:10` after `put:6` (it needs seven PUTs done to pick chunk 10). Asked AHEAD, the
    // window is on its way while a whole in-flight's worth of signed chunks is still unsent.
    const store = fakeStore({ window: 10, putDelayMs: 2 })
    await upload(store, spiedSource(25).blob)
    const at = (e: string) => store.events.indexOf(e)
    expect(store.events.filter((e) => e.startsWith('window:'))).toEqual(['window:10', 'window:20'])
    expect(at('window:10')).toBeLessThan(at(`put:${10 - PUMP_IN_FLIGHT}`))
    expect(at('window:20')).toBeLessThan(at(`put:${20 - PUMP_IN_FLIGHT}`))
  })

  it('re-signs from a chunk whose PUT answers 403, and retries that chunk once', async () => {
    const store = fakeStore({ window: 10, forbidFirstPut: [3] })
    const key = v1.randomBytes(32)
    fakeApi(mediaApi, store.api)
    fakeApi(blobApi, store.blob)
    vi.stubGlobal('fetch', store.fetchStub)
    const data = new Uint8Array(4 * CHUNK_SIZE + 9).map((_, i) => i % 251)
    await addMedia(SCOPE_ID, NODE_ID, key, { blob: new Blob([data]), mediaType: 'application/pdf' }, stubDeriver())
    expect(store.events.filter((e) => e === 'window:3')).toHaveLength(1)
    expect(store.events.filter((e) => e === 'put:3')).toHaveLength(2)
    // and what landed is the file: the retried frame is the same sealed frame, at its own index
    const [item] = await listMedia(SCOPE_ID, NODE_ID)
    expect(await openOriginal(SCOPE_ID, key, item)).toEqual(data)
  })

})

describe('read-back — operator-blind round-trip + tier selection', () => {
  it('round-trips the original through chunked AEAD under a K_blob wrapped by K_node', async () => {
    const store = fakeStore()
    fakeApi(mediaApi, store.api)
    fakeApi(blobApi, store.blob)
    vi.stubGlobal('fetch', store.fetchStub)

    const memoryKey = v1.randomBytes(32)
    // 600 KiB ⇒ 3 chunks at CHUNK_SIZE = 256 KiB (deterministic fill)
    const data = new Uint8Array(600 * 1024)
    for (let i = 0; i < data.length; i++) data[i] = (i * 31 + 7) & 0xff

    await addMedia(SCOPE_ID, NODE_ID, memoryKey, { blob: new Blob([data as BlobPart]), mediaType: 'image/jpeg' }, stubDeriver())

    // operator-blind: the first stored object is ciphertext (nonce+ct+tag), never the plaintext chunk
    const original = store.objects.get('mem://up-0/0')!
    expect(original).not.toEqual(data.subarray(0, CHUNK_SIZE))
    expect(original.length).toBe(CHUNK_SIZE + 12 + 16)

    const [item] = await listMedia(SCOPE_ID, NODE_ID)
    expect(item.original.chunkCount).toBe(Math.ceil(data.length / CHUNK_SIZE))
    const opened = await openOriginal(SCOPE_ID, memoryKey, item)
    expect(Array.from(opened)).toEqual(Array.from(data))
  })

  it('picks the right tier: grid=thumbnail, lightbox=preview, download=original', async () => {
    const store = fakeStore()
    fakeApi(mediaApi, store.api)
    fakeApi(blobApi, store.blob)
    vi.stubGlobal('fetch', store.fetchStub)
    const memoryKey = v1.randomBytes(32)

    await addMedia(SCOPE_ID, NODE_ID, memoryKey, { blob: new Blob([new Uint8Array(5).fill(9) as BlobPart]), mediaType: 'image/webp' }, stubDeriver())
    const [item] = await listMedia(SCOPE_ID, NODE_ID)

    const started = store.captured.startItems.map((i) => i.blobId)
    expect(downloadRef(item).blobId).toBe(started[0])
    expect(gridRef(item).blobId).toBe(started[1]) // THUMBNAIL
    expect(lightboxRef(item).blobId).toBe(started[2]) // PREVIEW
    // each ref is owned by the listed row's media, in the role it is listed in
    expect([downloadRef(item), gridRef(item), lightboxRef(item)].map((r) => [r.ownerId, r.role])).toEqual([
      [item.mediaId, 'ORIGINAL'], [item.mediaId, 'THUMBNAIL'], [item.mediaId, 'PREVIEW'],
    ])

    // the thumbnail tier opens to the 400px-tagged stub bytes, the preview to the 2048px-tagged ones
    const grid = await openBlob(SCOPE_ID, memoryKey, gridRef(item))
    const light = await openBlob(SCOPE_ID, memoryKey, lightboxRef(item))
    expect(Array.from(grid)).toEqual([0xff, 0xd8, 512 & 0xff, 512 >> 8])
    expect(Array.from(light)).toEqual([0xff, 0xd8, 2048 & 0xff, 2048 >> 8])
    // a server serving the thumbnail in the original's slot gets a refusal, not the thumbnail's key (ADR 0072)
    await expect(openBlob(SCOPE_ID, memoryKey, { ...gridRef(item), role: 'ORIGINAL' })).rejects.toBeInstanceOf(v1.OpenRefused)
  })

  it('falls back to the original when a media has no derivatives', async () => {
    const item: MediaItem = {
      mediaId: 'm',
      original: { blobId: 'orig', chunkCount: 1, byteSize: 0, wrappedBlobKey: 'd3JhcA==', ownerId: 'm', role: 'ORIGINAL' },
      derivatives: [],
    }
    expect(gridRef(item).blobId).toBe('orig')
    expect(lightboxRef(item).blobId).toBe('orig')
    expect(downloadRef(item).blobId).toBe('orig')
  })

  it('the original wrap opens back to a real 32-byte K_blob under K_node', async () => {
    const store = fakeStore()
    fakeApi(mediaApi, store.api)
    fakeApi(blobApi, store.blob)
    vi.stubGlobal('fetch', store.fetchStub)
    const memoryKey = v1.randomBytes(32)

    await addMedia(SCOPE_ID, NODE_ID, memoryKey, { blob: new Blob([new Uint8Array([1]) as BlobPart]), mediaType: 'application/pdf' }, stubDeriver())
    const start = store.captured.startItems[0]
    expect(start.ownerId).toBe(store.captured.completeItem.mediaId)
    const wrap = fromB64std(start.wrappedBlobKey)
    const blobCtx = { purpose: 'blob-key', scopeId: SCOPE_ID, ownerId: start.ownerId, blobId: start.blobId, role: 'ORIGINAL' } as const
    const blobKey = v1.open(blobCtx, memoryKey, wrap)
    expect(blobKey.length).toBe(32)
    // bound to its slot: the same wrap in another role, under another owner, or as another blob refuses
    expect(() => v1.open({ ...blobCtx, role: 'THUMBNAIL' }, memoryKey, wrap)).toThrow(v1.OpenRefused)
    expect(() => v1.open({ ...blobCtx, ownerId: crypto.randomUUID() }, memoryKey, wrap)).toThrow(v1.OpenRefused)
    expect(() => v1.open({ ...blobCtx, blobId: crypto.randomUUID() }, memoryKey, wrap)).toThrow(v1.OpenRefused)
  })
})

describe('live-updates data layer (uc.memory.live-updates, cr-0107)', () => {
  const row = (id: string) => ({
    blockedByOperator: false,
    mediaId: id, blobId: `b-${id}`, chunkCount: 1, byteSize: 0, wrappedBlobKey: 'aw==',
    derivatives: [], wrappedMeta: null, updatedAt: new Date('2026-07-09T12:00:00.000Z'),
  })

  describe('listMediaPages', () => {
    it('reads every page, from the cursor each answered, and keeps the first page\'s revisions and the last page\'s cursor', async () => {
      const pages: Record<string, MediaListPage> = {
        first: { media: [row('m1'), row('m2')], mediaRevision: 5, deleteRevision: 2, mediaIds: null, more: true, cursor: '7,m2' },
        '7,m2': { media: [row('m3')], mediaRevision: 6, deleteRevision: 3, mediaIds: ['m3'], more: true, cursor: '9,m3' },
        '9,m3': { media: [row('m4')], mediaRevision: 6, deleteRevision: 3, mediaIds: null, more: false, cursor: '9,m4' },
      }
      const { listNodeMedia } = fakeApi(mediaApi, { listNodeMedia: async (r) => pages[r.since ?? 'first'] })
      const read = await listMediaPages(SCOPE_ID, NODE_ID)
      if (read.notModified) throw new Error('a first load is never a 304')
      expect(read.items.map((i) => i.mediaId)).toEqual(['m1', 'm2', 'm3', 'm4'])
      expect([read.mediaRevision, read.deleteRevision, read.mediaIds, read.cursor]).toEqual([5, 2, null, '9,m4'])
      expect(vi.mocked(listNodeMedia).mock.calls.map(([r]) => [r.since, r.sinceDelete, r.ifNoneMatch, r.limit])).toEqual([
        [undefined, undefined, undefined, MEDIA_PAGE_LIMIT],
        ['7,m2', 2, undefined, MEDIA_PAGE_LIMIT], // a later page: no If-None-Match, the first page's delete revision
        ['9,m3', 2, undefined, MEDIA_PAGE_LIMIT],
      ])
    })

    it('ends a poll at its 304 without a second read', async () => {
      const { listNodeMedia } = fakeApi(mediaApi, {
        listNodeMedia: async () => {
          throw new ResponseError({ status: 304 } as Response, 'Not Modified')
        },
      })
      expect(await listMediaPages(SCOPE_ID, NODE_ID, { ifNoneMatch: '5', since: '7,m2', sinceDelete: 1 })).toEqual({ notModified: true })
      expect(listNodeMedia).toHaveBeenCalledTimes(1)
    })

    it('lists the trash in every page, newest-trashed first', async () => {
      const trashed = (id: string, at: string) => ({ ...row(id), deletedAt: new Date(at) })
      fakeApi(mediaApi, {
        listNodeMedia: async (r) => {
          expect(r.state).toBe('trashed')
          return r.since
            ? { media: [trashed('t3', '2026-07-09T11:00:00.000Z')], mediaRevision: 1, deleteRevision: 1, mediaIds: null, more: false, cursor: '2,t3' }
            : { media: [trashed('t1', '2026-07-09T10:00:00.000Z'), trashed('t2', '2026-07-09T12:00:00.000Z')], mediaRevision: 1, deleteRevision: 1, mediaIds: null, more: true, cursor: '1,t2' }
        },
      })
      expect((await listTrash(SCOPE_ID, NODE_ID)).map((i) => i.mediaId)).toEqual(['t2', 't3', 't1'])
    })
  })

  describe('listMediaPage', () => {
    it('maps the envelope: delta items + both revisions + the id-set', async () => {
      stubApi(mediaApi, {
        listNodeMedia: {
          media: [
            {
              blockedByOperator: false,
              mediaId: 'm1', blobId: 'b1', chunkCount: 1, byteSize: 0, wrappedBlobKey: 'aw==',
              derivatives: [], wrappedMeta: null, updatedAt: new Date('2026-07-09T12:00:00.000Z'),
            },
          ],
          mediaRevision: 5,
          deleteRevision: 2,
          mediaIds: ['m1', 'm2'],
          more: true,
          cursor: '12,m1',
        },
      })

      const page = await listMediaPage(SCOPE_ID, NODE_ID, { ifNoneMatch: '4', since: '2026-07-09T11:00:00.000Z,m0', sinceDelete: 1 })
      expect(page.notModified).toBe(false)
      if (page.notModified) return
      expect(page.items.map((i) => i.mediaId)).toEqual(['m1'])
      expect(page.items[0].updatedAt?.toISOString()).toBe('2026-07-09T12:00:00.000Z')
      expect(page.mediaRevision).toBe(5)
      expect(page.deleteRevision).toBe(2)
      expect(page.mediaIds).toEqual(['m1', 'm2'])
      expect([page.more, page.cursor]).toEqual([true, '12,m1'])
    })

    it('threads the conditional-GET params (If-None-Match, since, sinceDelete) and the page limit through to the client', async () => {
      const { listNodeMedia } = stubApi(mediaApi, { listNodeMedia: { media: [], mediaRevision: 3, deleteRevision: 0, mediaIds: null, more: false } })
      await listMediaPage(SCOPE_ID, NODE_ID, { ifNoneMatch: '3', since: 't,m', sinceDelete: 0 })
      expect(listNodeMedia).toHaveBeenCalledWith({ scopeId: SCOPE_ID, nodeId: NODE_ID, ifNoneMatch: '3', since: 't,m', sinceDelete: 0, limit: MEDIA_PAGE_LIMIT })
    })

    it('returns { notModified: true } on a 304 (idle fast path), swallowing the ResponseError', async () => {
      fakeApi(mediaApi, {
        listNodeMedia: async () => {
          throw new ResponseError({ status: 304 } as Response, 'Not Modified')
        },
      })
      expect(await listMediaPage(SCOPE_ID, NODE_ID, { ifNoneMatch: '5' })).toEqual({ notModified: true })
    })

    it('rethrows a non-304 error', async () => {
      fakeApi(mediaApi, {
        listNodeMedia: async () => {
          throw new ResponseError({ status: 500 } as Response, 'Server Error')
        },
      })
      await expect(listMediaPage(SCOPE_ID, NODE_ID)).rejects.toBeInstanceOf(ResponseError)
    })
  })
})
