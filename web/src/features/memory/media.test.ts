import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'

vi.mock('../../api/client', () => ({
  mediaApi: vi.fn(),
  loginApi: vi.fn(),
  tokenApi: vi.fn(),
  sessionApi: vi.fn(),
}))

// exifr does the real image parse in the browser; here we drive it so the seal/round-trip is deterministic.
// Default: no EXIF (parse → undefined), so the non-EXIF paths behave exactly as a source without metadata.
vi.mock('exifr', () => ({ default: { parse: vi.fn(async () => undefined) } }))

import {
  addMedia,
  uploadCoverBlob,
  deleteCoverBlob,
  listMedia,
  listMediaPage,
  newestCursor,
  openBlob,
  openOriginal,
  makeChunkProvider,
  gridRef,
  lightboxRef,
  downloadRef,
  scaleToMaxEdge,
  unsealMeta,
  setMediaMeta,
  backfillMediaType,
  isVideoMediaType,
  effectiveCapturedAt,
  readCaptureTime,
  CHUNK_SIZE,
  COVER_SPEC,
  TIER_SPECS,
  type MediaDeriver,
  type MediaItem,
  type MediaMeta,
  type Dimensions,
} from './media'
import exifr from 'exifr'
import { mediaApi } from '../../api/client'
import { ResponseError } from '../../api/generated/media'
import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'

const SCOPE_ID = '11111111-1111-1111-1111-111111111111'
const NODE_ID = '22222222-2222-2222-2222-222222222222'

/**
 * A tiny in-process object store that handles **several blobs per add-media** (original + derivatives). Each
 * upload session gets its own `mem://<uploadId>/<chunk>` keyspace; the fake replays PUT bodies on the matching
 * GET, so a real v1 seal/open + K_blob wrap round-trips per tier. `listNodeMedia` reconstructs the role-tagged
 * MediaSummary from what `completeMedia` saw (blobId == uploadId in this fake). Operator-blind: the "store"
 * only ever holds ciphertext.
 */
function fakeStore() {
  const objects = new Map<string, Uint8Array>()
  const sessions = new Map<string, { wrappedBlobKey: string; chunkCount: number }>()
  let upCounter = 0
  const captured = { startItems: [] as any[], completeItem: undefined as any, updatedMeta: undefined as any, coverUpload: undefined as any, coverDeletedNode: undefined as any }

  const blobOf = (uploadId: string) => {
    const s = sessions.get(uploadId)!
    return { blobId: uploadId, chunkCount: s.chunkCount, byteSize: 0, wrappedBlobKey: s.wrappedBlobKey }
  }

  const api = {
    startBlobUploads: vi.fn(async ({ blobUploadsBatchRequest }: any) => {
      const results = blobUploadsBatchRequest.items.map((item: any) => {
        const uploadId = `up-${upCounter++}`
        sessions.set(uploadId, { wrappedBlobKey: item.wrappedBlobKey, chunkCount: item.chunkCount })
        captured.startItems.push(item)
        const presignedPuts = Array.from({ length: item.chunkCount }, (_, i) => ({ index: i, url: `mem://${uploadId}/${i}` }))
        return { uploadId, presignedPuts, error: null }
      })
      return { results }
    }),
    completeMedia: vi.fn(async ({ mediaBatchRequest }: any) => {
      const item = mediaBatchRequest.items[0]
      captured.completeItem = item
      return { results: [{ blobId: `blob-${item.originalUploadId}`, mediaId: 'media-1', error: null }] }
    }),
    listNodeMedia: vi.fn(async () => {
      const item = captured.completeItem
      // cr-0107: the response is now the MediaListPage envelope (delta rows + both revisions + optional id-set).
      return {
        media: [
          {
            mediaId: 'media-1',
            ...blobOf(item.originalUploadId),
            derivatives: (item.derivatives ?? []).map((d: any) => ({ kind: d.kind, ...blobOf(d.uploadId) })),
            wrappedMeta: captured.updatedMeta ?? item.wrappedMeta ?? null, // listed for grid layout + date sorting (cr-0068)
            updatedAt: new Date('2026-07-09T12:00:00.000Z'), // the keyset cursor's sort key (cr-0107)
            // wrappedExif is deliberately NOT echoed by listNodeMedia — it is lazy via getMediaExif (cr-0064)
          },
        ],
        mediaRevision: 1,
        deleteRevision: 0,
        mediaIds: null,
      }
    }),
    updateMediaMeta: vi.fn(async ({ mediaMetaUpdate }: any) => {
      captured.updatedMeta = mediaMetaUpdate.wrappedMeta // opaque ciphertext (or null to clear)
      return { wrappedMeta: mediaMetaUpdate.wrappedMeta ?? null }
    }),
    presignBlobDownloads: vi.fn(async ({ blobDownloadsBatchRequest }: any) => {
      const uploadId = blobDownloadsBatchRequest.items[0].blobId // == uploadId in this fake
      const s = sessions.get(uploadId)!
      return {
        results: [{ presignedGets: Array.from({ length: s.chunkCount }, (_, i) => ({ index: i, url: `mem://${uploadId}/${i}` })), error: null }],
      }
    }),
    // cr-0073: promote one upload session into a standalone cover blob (no media row). The fake returns the
    // committed ref keyed by the uploadId so a caller can round-trip it through presignBlobDownloads.
    commitCoverBlob: vi.fn(async ({ nodeId, coverBlobCommitRequest }: any) => {
      captured.coverUpload = { nodeId, uploadId: coverBlobCommitRequest.uploadId }
      return blobOf(coverBlobCommitRequest.uploadId)
    }),
    deleteCoverBlob: vi.fn(async ({ nodeId }: any) => {
      captured.coverDeletedNode = nodeId
    }),
  }

  const fetchStub = vi.fn(async (url: string, init?: RequestInit) => {
    if (init?.method === 'PUT') {
      objects.set(url, new Uint8Array(init.body as Uint8Array))
      return { ok: true, status: 200 } as Response
    }
    const body = objects.get(url)
    if (!body) return { ok: false, status: 404 } as Response
    return { ok: true, status: 200, arrayBuffer: async () => body.buffer } as unknown as Response
  })

  return { api, fetchStub, objects, captured }
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
    vi.mocked(mediaApi).mockReturnValue(store.api as unknown as ReturnType<typeof mediaApi>)
    vi.stubGlobal('fetch', store.fetchStub)
    const deriver = stubDeriver()

    const memoryKey = v1.randomBytes(32)
    const data = new Uint8Array(10).fill(7)
    const { blobId, mediaId } = await addMedia(SCOPE_ID, NODE_ID, memoryKey, { bytes: data, mediaType: 'image/png' }, deriver)

    expect(mediaId).toBe('media-1')
    expect(blobId).toBe('blob-up-0') // original is upload index 0

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
    vi.mocked(mediaApi).mockReturnValue(store.api as unknown as ReturnType<typeof mediaApi>)
    vi.stubGlobal('fetch', store.fetchStub)
    const deriver = stubDeriver()
    deriver.sourceDims = { w: 4032, h: 3024 } // the real deriver always knows what it just decoded
    const probe = vi.fn(async () => ({ w: 1, h: 1 }))
    deriver.probeDimensions = probe

    const memoryKey = v1.randomBytes(32)
    const data = new Uint8Array(64).fill(5)
    await addMedia(SCOPE_ID, NODE_ID, memoryKey, { bytes: data, mediaType: 'image/jpeg' }, deriver)

    expect(deriver.imageSources.filter((n) => n === data.length)).toHaveLength(1) // ONE decode of the original
    expect(probe).not.toHaveBeenCalled() // nothing re-measured what that decode already knew
    // …and the geometry it knew is what got sealed, at full source resolution — not the preview's size
    const [item] = await listMedia(SCOPE_ID, NODE_ID)
    expect(unsealMeta(memoryKey, item)).toMatchObject({ w: 4032, h: 3024 })
    // both tiers still arrive, at their pinned specs
    expect(store.captured.completeItem.derivatives.map((d: { kind: string }) => d.kind)).toEqual(['THUMBNAIL', 'PREVIEW'])
    expect(deriver.deriveImage).toHaveBeenNthCalledWith(1, expect.anything(), TIER_SPECS.PREVIEW)
    expect(deriver.deriveImage).toHaveBeenNthCalledWith(2, expect.anything(), TIER_SPECS.THUMBNAIL)
  })

  it('a video derives a single poster and uses it for both the thumbnail and preview tier', async () => {
    const store = fakeStore()
    vi.mocked(mediaApi).mockReturnValue(store.api as unknown as ReturnType<typeof mediaApi>)
    vi.stubGlobal('fetch', store.fetchStub)
    const deriver = stubDeriver()

    const memoryKey = v1.randomBytes(32)
    await addMedia(SCOPE_ID, NODE_ID, memoryKey, { bytes: new Uint8Array(20).fill(3), mediaType: 'video/mp4' }, deriver)

    expect(deriver.posters).toBe(1) // decoded once
    expect(deriver.images).toBe(0)
    expect(store.captured.completeItem.derivatives.map((d: any) => d.kind)).toEqual(['THUMBNAIL', 'PREVIEW'])

    // both derivative tiers open back to the SAME poster bytes
    const [item] = await listMedia(SCOPE_ID, NODE_ID)
    const thumb = await openBlob(SCOPE_ID, memoryKey, gridRef(item))
    const preview = await openBlob(SCOPE_ID, memoryKey, lightboxRef(item))
    expect(Array.from(thumb)).toEqual([0xff, 0xd8, 0x07, 0x09])
    expect(Array.from(preview)).toEqual(Array.from(thumb))
  })

  it('a non-image/non-video attaches the original only (no derivatives)', async () => {
    const store = fakeStore()
    vi.mocked(mediaApi).mockReturnValue(store.api as unknown as ReturnType<typeof mediaApi>)
    vi.stubGlobal('fetch', store.fetchStub)
    const deriver = stubDeriver()

    await addMedia(SCOPE_ID, NODE_ID, v1.randomBytes(32), { bytes: new Uint8Array([1, 2, 3]), mediaType: 'application/pdf' }, deriver)

    expect(deriver.images + deriver.posters).toBe(0)
    expect(store.captured.startItems).toHaveLength(1)
    expect(store.captured.completeItem.derivatives).toEqual([])
  })

  it('throws store_unavailable when the store cannot presign (no transport)', async () => {
    vi.mocked(mediaApi).mockReturnValue({
      startBlobUploads: vi.fn(async () => ({ results: [{ uploadId: 'up-1', presignedPuts: null, error: null }] })),
    } as unknown as ReturnType<typeof mediaApi>)

    await expect(
      addMedia(SCOPE_ID, NODE_ID, v1.randomBytes(32), { bytes: new Uint8Array([1]), mediaType: 'application/pdf' }, stubDeriver()),
    ).rejects.toThrow('store_unavailable')
  })
})

describe('addMedia — progress, abort, best-effort derivation (cr-0058)', () => {
  it('reports progress 0 → 1 (monotonic) over the ciphertext chunks', async () => {
    const store = fakeStore()
    vi.mocked(mediaApi).mockReturnValue(store.api as unknown as ReturnType<typeof mediaApi>)
    vi.stubGlobal('fetch', store.fetchStub)
    const seen: number[] = []

    await addMedia(SCOPE_ID, NODE_ID, v1.randomBytes(32), { bytes: new Uint8Array(20).fill(1), mediaType: 'image/png' }, stubDeriver(), {
      onProgress: (f) => seen.push(f),
    })

    expect(seen[0]).toBe(0)
    expect(seen[seen.length - 1]).toBe(1)
    expect(seen).toEqual([...seen].sort((a, b) => a - b)) // non-decreasing
  })

  it('a pre-aborted signal throws AbortError and never attaches the media', async () => {
    const store = fakeStore()
    vi.mocked(mediaApi).mockReturnValue(store.api as unknown as ReturnType<typeof mediaApi>)
    vi.stubGlobal('fetch', store.fetchStub)
    const ac = new AbortController()
    ac.abort()

    await expect(
      addMedia(SCOPE_ID, NODE_ID, v1.randomBytes(32), { bytes: new Uint8Array([1]), mediaType: 'image/png' }, stubDeriver(), {
        signal: ac.signal,
      }),
    ).rejects.toMatchObject({ name: 'AbortError' })
    expect(store.api.completeMedia).not.toHaveBeenCalled()
  })

  it('uploads original-only (no derivatives) when derivation throws — e.g. an undecodable HEIC', async () => {
    const store = fakeStore()
    vi.mocked(mediaApi).mockReturnValue(store.api as unknown as ReturnType<typeof mediaApi>)
    vi.stubGlobal('fetch', store.fetchStub)
    const deriver = stubDeriver()
    deriver.deriveImage = vi.fn(async () => {
      throw new Error('decode_failed') // browser can't decode this codec (HEIC off Safari)
    })

    const { mediaId } = await addMedia(SCOPE_ID, NODE_ID, v1.randomBytes(32), { bytes: new Uint8Array([1, 2, 3]), mediaType: 'image/heic' }, deriver)

    expect(mediaId).toBe('media-1')
    expect(store.captured.startItems).toHaveLength(1) // original only — no derivative blobs
    expect(store.captured.completeItem.derivatives).toEqual([])
  })
})

describe('addMedia — E2EE-sealed layout + EXIF tiers (cr-0059/cr-0064)', () => {
  const CAPTURED_AT = Date.UTC(2021, 4, 1, 10, 0, 0) // 2021-05-01T10:00:00Z

  afterEach(() => {
    vi.mocked(exifr.parse).mockReset()
    vi.mocked(exifr.parse).mockResolvedValue(undefined as never)
  })

  it('seals the layout tier {v,w,h} (geometry only) under K_mem and round-trips it through listMedia', async () => {
    const store = fakeStore()
    vi.mocked(mediaApi).mockReturnValue(store.api as unknown as ReturnType<typeof mediaApi>)
    vi.stubGlobal('fetch', store.fetchStub)
    const deriver = stubDeriver()
    deriver.probeDimensions = vi.fn(async () => ({ w: 1920, h: 1080 }))
    vi.mocked(exifr.parse).mockResolvedValue({ DateTimeOriginal: new Date(CAPTURED_AT), Make: 'Canon' } as never)

    const memoryKey = v1.randomBytes(32)
    await addMedia(SCOPE_ID, NODE_ID, memoryKey, { bytes: new Uint8Array(10).fill(7), mediaType: 'image/png' }, deriver)

    // the wire carries only opaque ciphertext — never the plaintext dimensions
    const sealed = store.captured.completeItem.wrappedMeta
    expect(typeof sealed).toBe('string')
    expect(sealed).not.toContain('1920')

    const [item] = await listMedia(SCOPE_ID, NODE_ID)
    const meta = unsealMeta(memoryKey, item)
    expect(meta).toMatchObject({ v: 1, w: 1920, h: 1080 }) // geometry rides the listed metadata tier (cr-0068)
    // a wrong K_mem can't open it (bound to the Memory key)
    expect(unsealMeta(v1.randomBytes(32), item)).toBeNull()
  })

  it('seals date + GPS in the listed tier, a date/gps-free EXIF tier lazy alongside', async () => {
    const store = fakeStore()
    vi.mocked(mediaApi).mockReturnValue(store.api as unknown as ReturnType<typeof mediaApi>)
    vi.stubGlobal('fetch', store.fetchStub)
    const deriver = stubDeriver()
    deriver.probeDimensions = vi.fn(async () => ({ w: 800, h: 600 }))
    vi.mocked(exifr.parse).mockResolvedValue({
      DateTimeOriginal: new Date(CAPTURED_AT),
      Make: 'Canon', Model: 'EOS R', LensModel: 'RF 50mm', FNumber: 1.8, ISO: 200, latitude: 48.137, longitude: 11.575,
    } as never)

    const memoryKey = v1.randomBytes(32)
    await addMedia(SCOPE_ID, NODE_ID, memoryKey, { bytes: new Uint8Array(10).fill(7), mediaType: 'image/jpeg' }, deriver)

    // the capture time + GPS are sealed in the always-listed metadata tier (opaque — no plaintext on the wire)
    const sealedMeta = store.captured.completeItem.wrappedMeta
    expect(typeof sealedMeta).toBe('string')
    expect(sealedMeta).not.toContain('2021')
    expect(sealedMeta).not.toContain('48.137')
    // the EXIF tier is sealed opaque and **date- AND gps-free** — camera only (cr-0068)
    const sealedExif = store.captured.completeItem.wrappedExif
    expect(sealedExif).not.toContain('Canon')
    const exif = JSON.parse(new TextDecoder().decode(v1.symUnwrap(memoryKey, fromB64std(sealedExif), 'media-exif')))
    expect(exif).toMatchObject({ v: 1, make: 'Canon', model: 'EOS R', lens: 'RF 50mm', fNumber: 1.8, iso: 200 })
    expect(exif.capturedAt).toBeUndefined()
    expect(exif.gpsLat).toBeUndefined() // GPS moved to the listed tier (cr-0068)
    expect(exif.gpsLng).toBeUndefined()

    // listMedia carries the metadata tier (for date sorting + the place medoid) but NOT the EXIF tier
    const [item] = await listMedia(SCOPE_ID, NODE_ID)
    expect(item.wrappedMeta).toBeDefined()
    expect((item as Record<string, unknown>).wrappedExif).toBeUndefined()
    const meta = unsealMeta(memoryKey, item)
    expect(effectiveCapturedAt(meta)).toBe(CAPTURED_AT)
    expect(meta).toMatchObject({ gpsLat: 48.137, gpsLng: 11.575 }) // GPS available at list-level for the medoid
  })

  it('falls back to the file creation date when the source carries no EXIF date', async () => {
    const store = fakeStore()
    vi.mocked(mediaApi).mockReturnValue(store.api as unknown as ReturnType<typeof mediaApi>)
    vi.stubGlobal('fetch', store.fetchStub)
    const deriver = stubDeriver()
    deriver.probeDimensions = vi.fn(async () => ({ w: 640, h: 480 }))
    // exifr.parse → undefined (default mock): no readable EXIF; the file's lastModified stands in
    const FILE_MTIME = Date.UTC(2020, 0, 2, 3, 4, 5)

    const memoryKey = v1.randomBytes(32)
    await addMedia(SCOPE_ID, NODE_ID, memoryKey, { bytes: new Uint8Array([1, 2, 3]), mediaType: 'image/png', lastModified: FILE_MTIME }, deriver)

    expect(store.captured.completeItem.wrappedExif ?? null).toBeNull() // no EXIF bundle
    const [item] = await listMedia(SCOPE_ID, NODE_ID)
    const meta = unsealMeta(memoryKey, item)
    expect(meta).toMatchObject({ v: 1, w: 640, h: 480 })
    expect(effectiveCapturedAt(meta)).toBe(FILE_MTIME) // file-date fallback
    expect(meta?.capturedAtSource).toBe('file') // marked untrustworthy for the Memory `auto` range
  })

  // The capture-time key has TWO consumers: the metadata tier sealed at upload, and the in-flight upload tile
  // that must know its grid place BEFORE a byte is uploaded (useOptimisticTiles). They agree because they
  // consume one rule — `captureTimeOf` — and this is the bite that says so rather than assuming it. Lever:
  // drop `lastModified` from the tile-side call and the file-date branch below parts company with the seal.
  it('the picked file’s capture time is the one the tier seals — EXIF date, else the file date', async () => {
    const store = fakeStore()
    vi.mocked(mediaApi).mockReturnValue(store.api as unknown as ReturnType<typeof mediaApi>)
    vi.stubGlobal('fetch', store.fetchStub)
    const deriver = stubDeriver()
    const FILE_MTIME = Date.UTC(2019, 5, 6, 7, 8, 9)
    const bytes = new Uint8Array([1, 2, 3])
    const asPickedFile = () => new File([bytes as BlobPart], 'p.jpg', { type: 'image/jpeg', lastModified: FILE_MTIME })

    // (a) the file carries a real EXIF date → both sides take it, and the file date is ignored by both
    vi.mocked(exifr.parse).mockResolvedValue({ DateTimeOriginal: new Date(CAPTURED_AT) } as never)
    const keyA = v1.randomBytes(32)
    await addMedia(SCOPE_ID, NODE_ID, keyA, { bytes, mediaType: 'image/jpeg', lastModified: FILE_MTIME }, deriver)
    const sealedA = unsealMeta(keyA, (await listMedia(SCOPE_ID, NODE_ID))[0])
    const tileA = await readCaptureTime(asPickedFile(), 'image/jpeg', FILE_MTIME)
    expect(tileA.capturedAt).toBe(sealedA?.capturedAt)
    expect(tileA.capturedAt).toBe(CAPTURED_AT)
    expect(tileA.capturedAtSource).toBe(sealedA?.capturedAtSource)

    // (b) no readable EXIF → both fall back to the file's own date. This is the half the lever removes.
    vi.mocked(exifr.parse).mockResolvedValue(undefined as never)
    const keyB = v1.randomBytes(32)
    await addMedia(SCOPE_ID, NODE_ID, keyB, { bytes, mediaType: 'image/jpeg', lastModified: FILE_MTIME }, deriver)
    const listed = await listMedia(SCOPE_ID, NODE_ID)
    const sealedB = unsealMeta(keyB, listed[listed.length - 1])
    const tileB = await readCaptureTime(asPickedFile(), 'image/jpeg', FILE_MTIME)
    expect(tileB.capturedAt).toBe(sealedB?.capturedAt)
    expect(tileB.capturedAt).toBe(FILE_MTIME)
    expect(tileB.capturedAtSource).toBe('file')
  })

  it('seals no capture date in the tier when there is neither an EXIF date nor a file date', async () => {
    const store = fakeStore()
    vi.mocked(mediaApi).mockReturnValue(store.api as unknown as ReturnType<typeof mediaApi>)
    vi.stubGlobal('fetch', store.fetchStub)
    const deriver = stubDeriver()
    deriver.probeDimensions = vi.fn(async () => ({ w: 640, h: 480 }))

    const memoryKey = v1.randomBytes(32)
    await addMedia(SCOPE_ID, NODE_ID, memoryKey, { bytes: new Uint8Array([1, 2, 3]), mediaType: 'image/png' }, deriver)

    const [item] = await listMedia(SCOPE_ID, NODE_ID)
    const meta = unsealMeta(memoryKey, item)
    expect(effectiveCapturedAt(meta)).toBeUndefined() // no date → sorts last
    expect(meta).toMatchObject({ w: 640, h: 480 }) // geometry still rides the tier
  })

  it('seals only the mediaType when the source is undecodable (probe returns null) — no geometry/date/gps', async () => {
    const store = fakeStore()
    vi.mocked(mediaApi).mockReturnValue(store.api as unknown as ReturnType<typeof mediaApi>)
    vi.stubGlobal('fetch', store.fetchStub)
    const deriver = stubDeriver()
    deriver.probeDimensions = vi.fn(async () => null) // e.g. a non-image / undecodable codec

    const memoryKey = v1.randomBytes(32)
    // The tier still seals — the persisted mediaType signal (#104/#105) rides it even with no derivable metadata.
    await addMedia(SCOPE_ID, NODE_ID, memoryKey, { bytes: new Uint8Array([1, 2, 3]), mediaType: 'application/pdf' }, deriver)

    expect(store.captured.completeItem.wrappedExif ?? null).toBeNull()
    const [item] = await listMedia(SCOPE_ID, NODE_ID)
    const meta = unsealMeta(memoryKey, item)
    expect(meta).toMatchObject({ mediaType: 'application/pdf' })
    expect(meta?.w).toBeUndefined()
    expect(meta?.capturedAt).toBeUndefined()
    expect(meta?.gpsLat).toBeUndefined()
  })

  it('a deriver with no probeDimensions still seals the mediaType (best-effort — probe optional)', async () => {
    const store = fakeStore()
    vi.mocked(mediaApi).mockReturnValue(store.api as unknown as ReturnType<typeof mediaApi>)
    vi.stubGlobal('fetch', store.fetchStub)

    const memoryKey = v1.randomBytes(32)
    await addMedia(SCOPE_ID, NODE_ID, memoryKey, { bytes: new Uint8Array([1]), mediaType: 'image/png' }, stubDeriver())
    const [item] = await listMedia(SCOPE_ID, NODE_ID)
    expect(unsealMeta(memoryKey, item)).toMatchObject({ mediaType: 'image/png' })
  })

  it('seals NO metadata tier when there is truly nothing — an empty MIME + no derivable metadata', async () => {
    const store = fakeStore()
    vi.mocked(mediaApi).mockReturnValue(store.api as unknown as ReturnType<typeof mediaApi>)
    vi.stubGlobal('fetch', store.fetchStub)

    const memoryKey = v1.randomBytes(32)
    await addMedia(SCOPE_ID, NODE_ID, memoryKey, { bytes: new Uint8Array([1]), mediaType: '' }, stubDeriver())
    expect(store.captured.completeItem.wrappedMeta ?? null).toBeNull()
    const [item] = await listMedia(SCOPE_ID, NODE_ID)
    expect(unsealMeta(memoryKey, item)).toBeNull()
  })
})

describe('setMediaMeta — sealed capturedAt override in the listed-metadata tier (cr-0066/0068)', () => {
  const ORIG = Date.UTC(2021, 4, 1, 10, 0, 0)
  const CORRECTED = Date.UTC(2019, 7, 15, 8, 30, 0)
  const MEDIA_ID = 'media-1'

  function decodeMeta(memoryKey: Uint8Array, sealed: string): MediaMeta {
    return JSON.parse(new TextDecoder().decode(v1.symUnwrap(memoryKey, fromB64std(sealed), 'media-meta')))
  }

  it('re-seals the tier with the override, keeping the original capturedAt + geometry/GPS (operator-blind)', async () => {
    const store = fakeStore()
    vi.mocked(mediaApi).mockReturnValue(store.api as unknown as ReturnType<typeof mediaApi>)
    const memoryKey = v1.randomBytes(32)
    // a full listed tier: geometry + date + GPS. Only the date is user-editable; the rest rides along unchanged.
    const captured: MediaMeta = { v: 1, w: 800, h: 600, capturedAt: ORIG, gpsLat: 48.1, gpsLng: 11.5 }

    const updated = await setMediaMeta(SCOPE_ID, MEDIA_ID, memoryKey, captured, CORRECTED)

    // the wire carries only opaque ciphertext — never the plaintext date
    const sealed = store.captured.updatedMeta
    expect(typeof sealed).toBe('string')
    expect(sealed).not.toContain('2019')
    // the sealed tier keeps the original + geometry + GPS, and adds the override
    expect(decodeMeta(memoryKey, sealed)).toEqual({ v: 1, w: 800, h: 600, capturedAt: ORIG, gpsLat: 48.1, gpsLng: 11.5, capturedAtOverride: CORRECTED })
    // effective time now prefers the correction
    expect(effectiveCapturedAt(updated.tier)).toBe(CORRECTED)
    // a wrong K_mem can't open it
    expect(() => decodeMeta(v1.randomBytes(32), sealed)).toThrow()
  })

  it('clears the override (null) back to the original', async () => {
    const store = fakeStore()
    vi.mocked(mediaApi).mockReturnValue(store.api as unknown as ReturnType<typeof mediaApi>)
    const memoryKey = v1.randomBytes(32)
    const captured: MediaMeta = { v: 1, capturedAt: ORIG, capturedAtOverride: CORRECTED }

    const updated = await setMediaMeta(SCOPE_ID, MEDIA_ID, memoryKey, captured, null)

    expect(decodeMeta(memoryKey, store.captured.updatedMeta)).toEqual({ v: 1, capturedAt: ORIG })
    expect(effectiveCapturedAt(updated.tier)).toBe(ORIG) // falls back to the derived original
  })

  it('clearing an override with no other content drops the whole tier to null', async () => {
    const store = fakeStore()
    vi.mocked(mediaApi).mockReturnValue(store.api as unknown as ReturnType<typeof mediaApi>)
    const memoryKey = v1.randomBytes(32)
    const captured: MediaMeta = { v: 1, capturedAtOverride: CORRECTED } // only ever had a manual date, no geometry/GPS

    const updated = await setMediaMeta(SCOPE_ID, MEDIA_ID, memoryKey, captured, null)

    expect(store.captured.updatedMeta).toBeNull() // nothing left to seal → the tier is cleared
    expect(updated.tier).toBeNull()
  })

  it('sets a date on a media with no metadata tier at all (fresh tier)', async () => {
    const store = fakeStore()
    vi.mocked(mediaApi).mockReturnValue(store.api as unknown as ReturnType<typeof mediaApi>)
    const memoryKey = v1.randomBytes(32)

    const updated = await setMediaMeta(SCOPE_ID, MEDIA_ID, memoryKey, null, CORRECTED)

    expect(decodeMeta(memoryKey, store.captured.updatedMeta)).toEqual({ v: 1, capturedAtOverride: CORRECTED })
    expect(effectiveCapturedAt(updated.tier)).toBe(CORRECTED)
  })
})

describe('mediaType — the persisted video signal (cr-inline-video-playback, #104/#105)', () => {
  const MEDIA_ID = 'media-1'
  function decodeMeta(memoryKey: Uint8Array, sealed: string): MediaMeta {
    return JSON.parse(new TextDecoder().decode(v1.symUnwrap(memoryKey, fromB64std(sealed), 'media-meta')))
  }

  it('isVideoMediaType matches only video/* MIME types', () => {
    expect(isVideoMediaType('video/mp4')).toBe(true)
    expect(isVideoMediaType('video/webm')).toBe(true)
    expect(isVideoMediaType('image/jpeg')).toBe(false)
    expect(isVideoMediaType('application/pdf')).toBe(false)
    expect(isVideoMediaType(undefined)).toBe(false)
    expect(isVideoMediaType('')).toBe(false)
  })

  it('seals the video mediaType at upload and round-trips it through unsealMeta', async () => {
    const store = fakeStore()
    vi.mocked(mediaApi).mockReturnValue(store.api as unknown as ReturnType<typeof mediaApi>)
    vi.stubGlobal('fetch', store.fetchStub)

    const memoryKey = v1.randomBytes(32)
    await addMedia(SCOPE_ID, NODE_ID, memoryKey, { bytes: new Uint8Array([1, 2, 3]), mediaType: 'video/mp4' }, stubDeriver())

    // the wire is opaque ciphertext — the MIME never crosses in the clear
    expect(store.captured.completeItem.wrappedMeta).not.toContain('video/mp4')
    const [item] = await listMedia(SCOPE_ID, NODE_ID)
    const meta = unsealMeta(memoryKey, item)
    expect(meta?.mediaType).toBe('video/mp4')
    expect(isVideoMediaType(meta?.mediaType)).toBe(true)
  })

  it('backfillMediaType merges the sniffed type into the tier, leaving every other field untouched', async () => {
    const store = fakeStore()
    vi.mocked(mediaApi).mockReturnValue(store.api as unknown as ReturnType<typeof mediaApi>)
    const memoryKey = v1.randomBytes(32)
    // a pre-field media that already carries geometry + a capture date, but no sealed type
    const existing: MediaMeta = { v: 1, w: 1920, h: 1080, capturedAt: Date.UTC(2020, 0, 1) }

    const updated = await backfillMediaType(SCOPE_ID, MEDIA_ID, memoryKey, existing, 'video/webm')

    // the re-sealed tier carries the new type AND the untouched originals — operator-blind (opaque on the wire)
    expect(store.captured.updatedMeta).not.toContain('video/webm')
    expect(decodeMeta(memoryKey, store.captured.updatedMeta)).toEqual({ v: 1, w: 1920, h: 1080, capturedAt: Date.UTC(2020, 0, 1), mediaType: 'video/webm' })
    expect(updated.tier?.mediaType).toBe('video/webm')
  })

  it('backfillMediaType seals a fresh tier for a media that had no metadata at all', async () => {
    const store = fakeStore()
    vi.mocked(mediaApi).mockReturnValue(store.api as unknown as ReturnType<typeof mediaApi>)
    const memoryKey = v1.randomBytes(32)

    const updated = await backfillMediaType(SCOPE_ID, MEDIA_ID, memoryKey, null, 'video/quicktime')

    expect(decodeMeta(memoryKey, store.captured.updatedMeta)).toEqual({ v: 1, mediaType: 'video/quicktime' })
    expect(updated.tier?.mediaType).toBe('video/quicktime')
  })
})

describe('uploadCoverBlob / deleteCoverBlob — standalone cover blob (cr-0073)', () => {
  it('downscales an image to one cover-sized JPEG, seals it, commits it, and round-trips via openBlob', async () => {
    const store = fakeStore()
    vi.mocked(mediaApi).mockReturnValue(store.api as unknown as ReturnType<typeof mediaApi>)
    vi.stubGlobal('fetch', store.fetchStub)
    const deriver = stubDeriver()

    const memoryKey = v1.randomBytes(32)
    const ref = await uploadCoverBlob(SCOPE_ID, NODE_ID, memoryKey, { bytes: new Uint8Array(10).fill(7), mediaType: 'image/png' }, deriver)

    // exactly one downscale at the cover spec (no THUMBNAIL/PREVIEW split), one blob started, no media completed
    expect(deriver.images).toBe(1)
    expect(deriver.deriveImage).toHaveBeenCalledWith(expect.anything(), COVER_SPEC)
    expect(store.captured.startItems).toHaveLength(1)
    expect(store.api.completeMedia).not.toHaveBeenCalled() // NO veyq_media row
    expect(store.captured.coverUpload).toEqual({ nodeId: NODE_ID, uploadId: 'up-0' })

    // the committed ref opens back to the downscaled cover bytes (2048-tagged by the stub) under K_mem
    const opened = await openBlob(SCOPE_ID, memoryKey, ref)
    expect(Array.from(opened)).toEqual([0xff, 0xd8, 2048 & 0xff, 2048 >> 8])
  })

  it('seals the pristine bytes for a non-image source (no downscale)', async () => {
    const store = fakeStore()
    vi.mocked(mediaApi).mockReturnValue(store.api as unknown as ReturnType<typeof mediaApi>)
    vi.stubGlobal('fetch', store.fetchStub)
    const deriver = stubDeriver()

    await uploadCoverBlob(SCOPE_ID, NODE_ID, v1.randomBytes(32), { bytes: new Uint8Array([1, 2, 3]), mediaType: 'application/octet-stream' }, deriver)
    expect(deriver.images).toBe(0) // non-image ⇒ no downscale attempt
    expect(store.captured.startItems).toHaveLength(1)
  })

  it('deleteCoverBlob detaches via the endpoint', async () => {
    const store = fakeStore()
    vi.mocked(mediaApi).mockReturnValue(store.api as unknown as ReturnType<typeof mediaApi>)
    await deleteCoverBlob(SCOPE_ID, NODE_ID)
    expect(store.api.deleteCoverBlob).toHaveBeenCalledWith({ scopeId: SCOPE_ID, nodeId: NODE_ID })
    expect(store.captured.coverDeletedNode).toBe(NODE_ID)
  })
})

describe('read-back — operator-blind round-trip + tier selection', () => {
  it('round-trips the original through chunked AEAD under a K_blob wrapped by K_mem', async () => {
    const store = fakeStore()
    vi.mocked(mediaApi).mockReturnValue(store.api as unknown as ReturnType<typeof mediaApi>)
    vi.stubGlobal('fetch', store.fetchStub)

    const memoryKey = v1.randomBytes(32)
    // 600 KiB ⇒ 3 chunks at CHUNK_SIZE = 256 KiB (deterministic fill)
    const data = new Uint8Array(600 * 1024)
    for (let i = 0; i < data.length; i++) data[i] = (i * 31 + 7) & 0xff

    await addMedia(SCOPE_ID, NODE_ID, memoryKey, { bytes: data, mediaType: 'image/jpeg' }, stubDeriver())

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
    vi.mocked(mediaApi).mockReturnValue(store.api as unknown as ReturnType<typeof mediaApi>)
    vi.stubGlobal('fetch', store.fetchStub)
    const memoryKey = v1.randomBytes(32)

    await addMedia(SCOPE_ID, NODE_ID, memoryKey, { bytes: new Uint8Array(5).fill(9), mediaType: 'image/webp' }, stubDeriver())
    const [item] = await listMedia(SCOPE_ID, NODE_ID)

    expect(downloadRef(item).blobId).toBe('up-0')
    expect(gridRef(item).blobId).toBe('up-1') // THUMBNAIL
    expect(lightboxRef(item).blobId).toBe('up-2') // PREVIEW

    // the thumbnail tier opens to the 400px-tagged stub bytes, the preview to the 2048px-tagged ones
    const grid = await openBlob(SCOPE_ID, memoryKey, gridRef(item))
    const light = await openBlob(SCOPE_ID, memoryKey, lightboxRef(item))
    expect(Array.from(grid)).toEqual([0xff, 0xd8, 512 & 0xff, 512 >> 8])
    expect(Array.from(light)).toEqual([0xff, 0xd8, 2048 & 0xff, 2048 >> 8])
  })

  it('falls back to the original when a media has no derivatives', async () => {
    const item: MediaItem = {
      mediaId: 'm',
      original: { blobId: 'orig', chunkCount: 1, byteSize: 0, wrappedBlobKey: 'd3JhcA==' },
      derivatives: [],
    }
    expect(gridRef(item).blobId).toBe('orig')
    expect(lightboxRef(item).blobId).toBe('orig')
    expect(downloadRef(item).blobId).toBe('orig')
  })

  it('the original wrap opens back to a real 32-byte K_blob under K_mem', async () => {
    const store = fakeStore()
    vi.mocked(mediaApi).mockReturnValue(store.api as unknown as ReturnType<typeof mediaApi>)
    vi.stubGlobal('fetch', store.fetchStub)
    const memoryKey = v1.randomBytes(32)

    await addMedia(SCOPE_ID, NODE_ID, memoryKey, { bytes: new Uint8Array([1]), mediaType: 'application/pdf' }, stubDeriver())
    const wrap = store.captured.startItems[0].wrappedBlobKey
    const blobKey = v1.symUnwrap(memoryKey, fromB64std(wrap), 'blob-key')
    expect(blobKey.length).toBe(32)
  })
})

describe('live-updates data layer (uc.memory.live-updates, cr-0107)', () => {
  const item = (id: string, updatedAt: string): MediaItem => ({
    mediaId: id,
    original: { blobId: `b-${id}`, chunkCount: 1, byteSize: 0, wrappedBlobKey: 'aw==' },
    derivatives: [],
    updatedAt: new Date(updatedAt),
  })

  describe('newestCursor', () => {
    it('is undefined for an empty grid', () => {
      expect(newestCursor([])).toBeUndefined()
    })

    it('picks the latest updatedAt and formats "updatedAt,mediaId"', () => {
      const cursor = newestCursor([item('a', '2026-07-09T10:00:00.000Z'), item('b', '2026-07-09T12:00:00.000Z')])
      expect(cursor).toBe('2026-07-09T12:00:00.000Z,b')
    })

    it('breaks a same-timestamp tie by the larger mediaId (so all seen rows are ≤ the cursor)', () => {
      const t = '2026-07-09T12:00:00.000Z'
      expect(newestCursor([item('a', t), item('c', t), item('b', t)])).toBe(`${t},c`)
    })

    it('round-trips millisecond precision losslessly (matches the server TIMESTAMP(3))', () => {
      expect(newestCursor([item('a', '2026-07-09T12:34:56.789Z')])).toBe('2026-07-09T12:34:56.789Z,a')
    })
  })

  describe('listMediaPage', () => {
    it('maps the envelope: delta items + both revisions + the id-set', async () => {
      vi.mocked(mediaApi).mockReturnValue({
        listNodeMedia: vi.fn(async () => ({
          media: [
            {
              mediaId: 'm1', blobId: 'b1', chunkCount: 1, byteSize: 0, wrappedBlobKey: 'aw==',
              derivatives: [], wrappedMeta: null, updatedAt: new Date('2026-07-09T12:00:00.000Z'),
            },
          ],
          mediaRevision: 5,
          deleteRevision: 2,
          mediaIds: ['m1', 'm2'],
        })),
      } as unknown as ReturnType<typeof mediaApi>)

      const page = await listMediaPage(SCOPE_ID, NODE_ID, { ifNoneMatch: '4', since: '2026-07-09T11:00:00.000Z,m0', sinceDelete: 1 })
      expect(page.notModified).toBe(false)
      if (page.notModified) return
      expect(page.items.map((i) => i.mediaId)).toEqual(['m1'])
      expect(page.items[0].updatedAt?.toISOString()).toBe('2026-07-09T12:00:00.000Z')
      expect(page.mediaRevision).toBe(5)
      expect(page.deleteRevision).toBe(2)
      expect(page.mediaIds).toEqual(['m1', 'm2'])
    })

    it('threads the conditional-GET params (If-None-Match, since, sinceDelete) through to the client', async () => {
      const listNodeMedia = vi.fn(async () => ({ media: [], mediaRevision: 3, deleteRevision: 0, mediaIds: null }))
      vi.mocked(mediaApi).mockReturnValue({ listNodeMedia } as unknown as ReturnType<typeof mediaApi>)
      await listMediaPage(SCOPE_ID, NODE_ID, { ifNoneMatch: '3', since: 't,m', sinceDelete: 0 })
      expect(listNodeMedia).toHaveBeenCalledWith({ scopeId: SCOPE_ID, nodeId: NODE_ID, ifNoneMatch: '3', since: 't,m', sinceDelete: 0 })
    })

    it('returns { notModified: true } on a 304 (idle fast path), swallowing the ResponseError', async () => {
      vi.mocked(mediaApi).mockReturnValue({
        listNodeMedia: vi.fn(async () => {
          throw new ResponseError({ status: 304 } as Response, 'Not Modified')
        }),
      } as unknown as ReturnType<typeof mediaApi>)
      expect(await listMediaPage(SCOPE_ID, NODE_ID, { ifNoneMatch: '5' })).toEqual({ notModified: true })
    })

    it('rethrows a non-304 error', async () => {
      vi.mocked(mediaApi).mockReturnValue({
        listNodeMedia: vi.fn(async () => {
          throw new ResponseError({ status: 500 } as Response, 'Server Error')
        }),
      } as unknown as ReturnType<typeof mediaApi>)
      await expect(listMediaPage(SCOPE_ID, NODE_ID)).rejects.toBeInstanceOf(ResponseError)
    })
  })
})

describe('makeChunkProvider — the page-side plaintext chunk seam (Slice 2, ADR 0047)', () => {
  // The SW never sees a key; it asks the page for one chunk's plaintext by index. Build a real chunked-AEAD blob
  // (K_blob wrapped under K_mem, each frame bound to `index‖total`) so the seam runs the true seal/open path.
  const KBLOB_CTX = 'blob-key' // == media.ts KBLOB_CTX; if the domain tag ever changes this test breaks loudly.

  function buildBlob(memoryKey: Uint8Array, plaintextChunks: Uint8Array[]) {
    const blobKey = v1.randomBytes(32)
    const total = plaintextChunks.length
    const frames = plaintextChunks.map((pt, i) => v1.sealFrame(blobKey, i, total, pt, v1.randomBytes(12)))
    const wrappedBlobKey = b64std(v1.symWrap(memoryKey, blobKey, KBLOB_CTX, v1.randomBytes(12)))
    const byteSize = frames.reduce((n, f) => n + f.length, 0)
    return { blobKey, frames, ref: { blobId: 'blob-x', chunkCount: total, byteSize, wrappedBlobKey } }
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

    const p = makeChunkProvider(memoryKey, ref, presignAll)
    expect(p.chunkCount).toBe(3)
    expect(p.byteSize).toBe(ref.byteSize)
    for (let i = 0; i < chunks.length; i++) {
      expect(Array.from(await p.getPlaintextChunk(i))).toEqual(Array.from(chunks[i]))
    }
  })

  it('presigns the WHOLE blob only once and reuses the cache across chunk reads', async () => {
    const memoryKey = v1.randomBytes(32)
    const { frames, ref } = buildBlob(memoryKey, [new Uint8Array([1]), new Uint8Array([2]), new Uint8Array([3])])
    const { fetchStub, presignAll } = storeFor(frames)
    vi.stubGlobal('fetch', fetchStub)

    const p = makeChunkProvider(memoryKey, ref, presignAll)
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

    const p = makeChunkProvider(memoryKey, ref, presignAll)
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

    const p = makeChunkProvider(memoryKey, ref, presignAdvancing)
    expect(Array.from(await p.getPlaintextChunk(1))).toEqual(Array.from([2, 2]))
    expect(presignAdvancing).toHaveBeenCalledTimes(2) // initial presign (403) → re-presign → retry succeeds
  })

  it('rejects an out-of-range chunk index without touching the store', async () => {
    const memoryKey = v1.randomBytes(32)
    const { frames, ref } = buildBlob(memoryKey, [new Uint8Array([1])])
    const { fetchStub, presignAll } = storeFor(frames)
    vi.stubGlobal('fetch', fetchStub)

    const p = makeChunkProvider(memoryKey, ref, presignAll)
    await expect(p.getPlaintextChunk(1)).rejects.toThrow(/chunk_out_of_range/)
    await expect(p.getPlaintextChunk(-1)).rejects.toThrow(/chunk_out_of_range/)
    expect(fetchStub).not.toHaveBeenCalled()
  })
})
