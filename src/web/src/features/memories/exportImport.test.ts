import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('./media', async (orig) => ({
  ...(await orig<typeof import('./media')>()),
  listMedia: vi.fn(), listMediaPages: vi.fn(), unsealMeta: vi.fn(), originalBlobMeta: vi.fn(), originalMediaType: vi.fn(), loadMediaExif: vi.fn(),
  chunkProvider: vi.fn(), plaintextSize: vi.fn(), addMedia: vi.fn(), setMediaMeta: vi.fn(), backfillPlaceName: vi.fn(), openBlob: vi.fn(),
  addCopiedMedia: vi.fn(), tierChunkProviders: vi.fn(), rawMediaMeta: vi.fn(), readByChunk: vi.fn(), prepareMedia: vi.fn(),
}))
vi.mock('./share', async (orig) => ({ ...(await orig<typeof import('./share')>()), readShareMedia: vi.fn(), openSharedMemory: vi.fn(), shareChunkProvider: vi.fn() }))
vi.mock('../../core/people/directory', () => ({ loadTenantPeople: vi.fn() }))
vi.mock('../../core/node/tree', async (orig) => ({ ...(await orig<typeof import('../../core/node/tree')>()), createContentNode: vi.fn(), fetchTreeRows: vi.fn() }))
vi.mock('../../core/node/cover/coverWrite', async (orig) => ({ ...(await orig<object>()), uploadCoverBlob: vi.fn(), uploadCoverImage: vi.fn(), writeNodeCover: vi.fn() }))
vi.mock('./banner/bannerWrite', async (orig) => ({ ...(await orig<object>()), uploadBanner: vi.fn(), writeMemoryBanner: vi.fn() }))
vi.mock('../../core/blob/transport', async (orig) => ({ ...(await orig<object>()), makeChunkProvider: vi.fn() }))

import { contentDigest, rememberDigest } from '../../core/blob/sharedBlob'
import * as v1 from '@veyq/crypto/v1'
import { b64url } from '@veyq/crypto/base64'
import {
  addCopiedMedia, addMedia, backfillPlaceName, chunkProvider, listMedia, listMediaPages, loadMediaExif, openBlob, originalBlobMeta, originalMediaType, plaintextSize,
  prepareMedia, rawMediaMeta, setMediaMeta, tierChunkProviders, unsealMeta, type MediaItem, type PreparedMedia,
} from './media'
import { readShareMedia, openSharedMemory, shareChunkProvider } from './share'
import { guestAccessOf, memberAccessOf } from '../../test/models'
import type { Access } from '../../core/access'
import type { PublicShareHead } from '../../core/sharing/resolvePublicShare'
import { loadTenantPeople, type Person } from '../../core/people/directory'
import { createContentNode, fetchTreeRows, type TreeNode } from '../../core/node/tree'
import { writeNodeCover, uploadCoverBlob, uploadCoverImage } from '../../core/node/cover/coverWrite'
import { sealNodeMeta, unsealNodeMeta } from '../../core/node/nodeMeta'
import type { ExportNodeContext, ImportNodeContext } from '../../core/export/hooks'
import type { ManifestNode } from '../../core/export/manifest'
import type { ScopeKey } from '../spaces/epochs'
import { TRACK_ACTIVITIES } from './track/trackLabel'
import { exportMemoryContent, importMemoryContent, validateMemoryContent, MEMORIES_LIMITS, MEMORY_CONTENT, CHAPTER_CONTENT } from './exportImport'
import { readFileSync } from 'node:fs'
import { CHAPTER_KIND, MEMORY_KIND } from './kinds'
import { uploadBanner, writeMemoryBanner } from './banner/bannerWrite'
import { makeChunkProvider } from '../../core/blob/transport'

const SCOPE = '11111111-1111-4111-8111-111111111111'
const NODE = '22222222-2222-4222-8222-222222222222'
const U1 = '44444444-4444-4444-8444-444444444444'
const U2 = '55555555-5555-4555-8555-555555555555'
const GONE = '66666666-6666-4666-8666-666666666666'
const NEW = '33333333-3333-4333-8333-333333333333'
const KEY = v1.randomBytes(32)
const item = (mediaId: string, ownerKey = `user:${U1}`): MediaItem => ({ mediaId, ownerKey, original: { byteSize: 1, chunkCount: 1 }, derivatives: [] }) as unknown as MediaItem
const person = (displayName: string) => ({ displayName }) as unknown as Person

const member = memberAccessOf({ scopeId: SCOPE })
function exportCtx(meta: Record<string, unknown> = {}, kind = MEMORY_KIND, access: Access = member) {
  const payloads: { name: string; modifiedAt?: number; mediaType: string }[] = []
  const node = { id: NODE, kind, name: 'Lisbon', wrappedMeta: sealNodeMeta(KEY, { scopeId: SCOPE, nodeId: NODE }, { v: 1, ...meta }) } as unknown as TreeNode
  const ctx = {
    access, tree: { rootId: NODE }, scopeId: SCOPE, node, nodeKey: KEY, cache: new Map(),
    addPayload: (name: string, s: { size: number; mediaType: string; modifiedAt?: number }) => {
      payloads.push({ name, modifiedAt: s.modifiedAt, mediaType: s.mediaType })
      return { path: `Lisbon/${name}`, size: s.size, mediaType: s.mediaType }
    },
  } as unknown as ExportNodeContext
  return { ctx, payloads }
}

beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(loadTenantPeople).mockResolvedValue(new Map([[U1, person('Anna Meyer')], [U2, person('Ben')]]))
  vi.mocked(listMediaPages).mockResolvedValue({ notModified: false, items: [item('a'), item('b')], mediaRevision: 1, deleteRevision: 0, mediaIds: null, cursor: null })
  vi.mocked(plaintextSize).mockReturnValue(100)
  vi.mocked(originalMediaType).mockReturnValue('image/jpeg')
  vi.mocked(chunkProvider).mockReturnValue({ chunkCount: 0, getPlaintextChunk: async () => new Uint8Array(0) } as unknown as ReturnType<typeof chunkProvider>)
  vi.mocked(loadMediaExif).mockResolvedValue({ make: 'Fuji', altitude: 12, imgDirection: 90 } as unknown as Awaited<ReturnType<typeof loadMediaExif>>)
  // the copy's doors answer by default, so a test of the archive path goes red on its own assertion, not on a missing stub
  vi.mocked(tierChunkProviders).mockReturnValue([])
  vi.mocked(rawMediaMeta).mockReturnValue(null)
  vi.mocked(addCopiedMedia).mockResolvedValue({ blobId: 'b', mediaId: 'copied' })
  vi.mocked(uploadCoverBlob).mockResolvedValue({ uploadId: 'u', plan: { blobId: 'cb', chunkCount: 1, byteSize: 10, wrappedBlobKey: 'k', ownerId: NEW } })
  vi.mocked(uploadCoverImage).mockResolvedValue({ uploadId: 'u', plan: { blobId: 'cb', chunkCount: 1, byteSize: 10, wrappedBlobKey: 'k', ownerId: NEW } })
  vi.mocked(originalBlobMeta).mockImplementation((_s, _k, it) => ({ v: 1, type: 'image/jpeg', name: `${it.mediaId}.jpg`, modifiedAt: 5 }))
  vi.mocked(unsealMeta).mockImplementation((_s, _k, it) =>
    it.mediaId === 'a'
      ? { v: 1, capturedAt: 1_700_000_000_000, gpsLat: 38.7, gpsLng: -9.1, placeName: 'Lisbon' }
      : { v: 1, capturedAt: 1_000, capturedAtOverride: 1_700_000_100_000 })
})

describe('exportMemoryContent', () => {
  const meta = {
    date: { mode: 'range', from: '2025-09-14', to: '2025-09-17' },
    location: { name: 'Lisbon', nameSource: 'manual', derivedMode: 'fixed', derived: { lat: 38.7, lng: -9.1 } },
    participants: { userIds: [U1, U2, GONE] },
    cover: { source: { kind: 'media', mediaId: 'b' }, crop: { x: 0, y: 0, width: 1, height: 1 } },
  }

  it('exports the media with capture time, correction flag, place and EXIF, and the node\'s date, place, cover and names', async () => {
    const { ctx, payloads } = exportCtx(meta)
    const c = (await exportMemoryContent(ctx))!
    expect(c).toMatchObject({ type: MEMORY_CONTENT, schema: 1, date: meta.date, participants: ['Anna Meyer', 'Ben'] })
    expect(c.cover, 'an older client\'s photo reference is no cover: nothing to export').toBeUndefined()
    expect(c.location).toMatchObject({ name: 'Lisbon', derived: { lat: 38.7, lng: -9.1 } })
    const [a, b] = c.media as { name: string; capturedAt: string; capturedAtCorrected?: true; location?: unknown; exif?: Record<string, unknown>; contributor?: string }[]
    expect(a).toMatchObject({ name: 'a.jpg', capturedAt: new Date(1_700_000_000_000).toISOString(), location: { lat: 38.7, lon: -9.1, place: 'Lisbon' }, contributor: 'Anna Meyer' })
    expect(a.capturedAtCorrected).toBeUndefined()
    expect(a.exif).toEqual({ make: 'Fuji', altitude: 12, imgDirection: 90 }) // always whole: there is no location option
    expect(b).toMatchObject({ capturedAt: new Date(1_700_000_100_000).toISOString(), capturedAtCorrected: true })
    // the upload falls back to the modified time for a missing capture time: the capture time rides there
    expect(payloads.map((p) => p.modifiedAt)).toEqual([1_700_000_000_000, 1_700_000_100_000])
  })

  // ADR 0130 decision 9: a track's own name and activity travel with it; a medium without them carries no `track`.
  // Lever: drop the `medium.track` assignment in the walk.
  it('exports a GPX track\'s own name and activity as its `track`, and nothing for a medium without them', async () => {
    // media of their own: the listed tier is opened once per medium and seal (`openedListing.ts`), across the tests
    vi.mocked(listMediaPages).mockResolvedValue({ notModified: false, items: [item('t1'), item('t2')], mediaRevision: 1, deleteRevision: 0, mediaIds: null, cursor: null })
    vi.mocked(unsealMeta).mockImplementation((_s, _k, it) =>
      it.mediaId === 't1' ? { v: 1, capturedAt: 1_000, trackName: 'Gipfeltour', trackActivity: 'skiing' } : { v: 1, capturedAt: 2_000, trackActivity: 'paragliding' })
    const c = (await exportMemoryContent(exportCtx(meta).ctx))!
    const [a, b] = c.media as { track?: unknown }[]
    expect(a.track).toEqual({ name: 'Gipfeltour', activity: 'skiing' })
    expect(b.track, 'a newer client\'s activity is not one this format names').toBeUndefined()
    expect(validateMemoryContent(c)).toBeNull()
  })

  it('an uploaded cover travels as a payload of its own, read through the session\'s source', async () => {
    vi.mocked(openBlob).mockResolvedValue(new Uint8Array([1, 2, 3]))
    const ref = { blobId: 'c', chunkCount: 1, byteSize: 3, wrappedBlobKey: 'k' }
    const { ctx, payloads } = exportCtx({ cover: { source: { kind: 'upload', ref }, crop: { x: 0, y: 0, width: 1, height: 1 } } })
    const c = (await exportMemoryContent(ctx))!
    expect(payloads.at(-1)).toMatchObject({ name: 'cover.jpg', mediaType: 'image/jpeg' })
    expect(c.cover).toMatchObject({ file: { path: 'Lisbon/cover.jpg', size: 3 } })
  })

  it('a READ guest downloads through the link: no member read, no names (never the member directory), no EXIF copy', async () => {
    const guest = guestAccessOf('READ', { head: { scopeId: SCOPE } as unknown as PublicShareHead })
    vi.mocked(readShareMedia).mockResolvedValue({ listing: { media: [] }, etag: null } as unknown as Awaited<ReturnType<typeof readShareMedia>>)
    vi.mocked(openSharedMemory).mockReturnValue({ items: [{ item: item('a'), nodeId: NODE }, { item: item('x'), nodeId: 'other' }] } as unknown as ReturnType<typeof openSharedMemory>)
    vi.mocked(shareChunkProvider).mockReturnValue({ chunkCount: 0, getPlaintextChunk: async () => new Uint8Array(0) } as unknown as ReturnType<typeof shareChunkProvider>)
    const { ctx, payloads } = exportCtx(meta, MEMORY_KIND, guest)
    const c = (await exportMemoryContent(ctx))!
    expect(listMediaPages).not.toHaveBeenCalled()
    expect(loadTenantPeople).not.toHaveBeenCalled()
    expect(loadMediaExif).not.toHaveBeenCalled()
    expect(payloads.map((p) => p.name)).toEqual(['a.jpg']) // this node's media only
    expect(c.participants).toBeUndefined()
    const [a] = c.media as { exif?: unknown; contributor?: string; location?: unknown }[]
    expect(a).toMatchObject({ location: { place: 'Lisbon' } })
    expect(a.exif).toBeUndefined()
    expect(a.contributor).toBeUndefined()
  })

  it('a download carries nothing a copy adds: no tiers, no listed tier, no blob meta - only the originals', async () => {
    const { ctx, payloads } = exportCtx(meta)
    ;(ctx as { purpose: string }).purpose = 'download'
    const c = (await exportMemoryContent(ctx))!
    expect((c.media as { copy?: unknown }[]).map((m) => m.copy)).toEqual([undefined, undefined])
    expect(payloads.map((p) => p.name)).toEqual(['a.jpg', 'b.jpg'])
    expect(tierChunkProviders).not.toHaveBeenCalled()
    expect(rawMediaMeta).not.toHaveBeenCalled()
  })

  it('a copy\'s walk adds each medium\'s tiers as payloads read by frame, its blob meta and its listed tier verbatim', async () => {
    const provider = { chunkCount: 1, getPlaintextChunk: async () => new Uint8Array(0) }
    vi.mocked(tierChunkProviders).mockReturnValue([
      { kind: 'THUMBNAIL', ref: { byteSize: 1, chunkCount: 1 }, meta: { v: 1, type: 'image/jpeg' }, provider },
    ] as unknown as ReturnType<typeof tierChunkProviders>)
    vi.mocked(rawMediaMeta).mockReturnValue({ v: 1, capturedAtOverride: 7, laterField: 'kept' })
    const { ctx, payloads } = exportCtx(meta)
    ;(ctx as { purpose: string }).purpose = 'copy'
    const c = (await exportMemoryContent(ctx))!
    expect(payloads.map((p) => p.name)).toEqual(['a.jpg', 'a.jpg.thumbnail', 'b.jpg', 'b.jpg.thumbnail'])
    expect((c.media as { copy?: unknown }[])[0].copy).toEqual({
      original: { type: 'image/jpeg', name: 'a.jpg', modifiedAt: 5 },
      meta: { v: 1, capturedAtOverride: 7, laterField: 'kept' },
      tiers: [{ kind: 'THUMBNAIL', file: { path: 'Lisbon/a.jpg.thumbnail', size: 100, mediaType: 'image/jpeg' }, meta: { type: 'image/jpeg' } }],
    })
  })

  it('a copy\'s walk stops on an EXIF read that fails, where a download names no EXIF', async () => {
    vi.mocked(tierChunkProviders).mockReturnValue([])
    vi.mocked(loadMediaExif).mockRejectedValue(new Error('network'))
    const { ctx } = exportCtx(meta)
    expect(((await exportMemoryContent(ctx))!.media as { exif?: unknown }[])[0].exif).toBeUndefined()
    ;(ctx as { purpose: string }).purpose = 'copy'
    await expect(exportMemoryContent(ctx)).rejects.toThrow('network')
  })

  it('a chapter exports as memories.chapter', async () => {
    const { ctx } = exportCtx({}, CHAPTER_KIND)
    expect((await exportMemoryContent(ctx))!.type).toBe(CHAPTER_CONTENT)
  })
})

describe('a place that is a name only (inbox #458)', () => {
  it('exports without a coordinate, passes the download\'s contract and is sealed again on import', async () => {
    const nameOnly = { name: 'At grandma’s', nameSource: 'manual', derivedMode: 'auto' }
    const c = (await exportMemoryContent(exportCtx({ location: nameOnly }).ctx))!
    expect(c.location).toEqual({ ...nameOnly, derived: undefined, nameAnchor: undefined })
    const wire = JSON.parse(JSON.stringify(c)) as ManifestNode['content'] & object // what the archive's manifest holds
    expect(validateMemoryContent(wire)).toBeNull()

    const nodeKey = v1.randomBytes(32)
    vi.mocked(createContentNode).mockResolvedValue({ nodeId: NEW, contentKey: nodeKey, epoch: 0, scopeEpoch: 0 })
    vi.mocked(fetchTreeRows).mockResolvedValue([{ id: NEW, wrappedMeta: 'tw', metaRevision: 3, epoch: 0, scopeEpoch: 0 } as unknown as Awaited<ReturnType<typeof fetchTreeRows>>[number]])
    const n = { id: 'n4', kind: MEMORY_KIND, name: 'Granny', content: { ...wire, media: [], cover: undefined } } as unknown as ManifestNode
    const made = await importMemoryContent(MEMORY_KIND)({
      scopeId: SCOPE, epochs: {} as ScopeKey, parent: null, name: 'Granny', node: n, cache: new Map(), payload: vi.fn(),
    } as unknown as ImportNodeContext)
    const sealer = vi.mocked(createContentNode).mock.calls[0][5]!
    const raw = unsealNodeMeta(made.nodeKey, { scopeId: SCOPE, nodeId: NEW }, sealer(made.nodeKey, { scopeId: SCOPE, nodeId: NEW, scopeEpoch: 1, placementRevision: 0 }))
    expect(raw.location).toEqual({ name: 'At grandma’s', nameSource: 'manual', derivedMode: 'auto' })
  })
})

describe('importMemoryContent', () => {
  const file = (name: string, lastModified: number) => new File(['x'], name, { type: 'image/jpeg', lastModified })
  const node = (over: Record<string, unknown> = {}): ManifestNode => ({
    id: 'n3', kind: MEMORY_KIND, name: 'Lisbon',
    content: {
      type: MEMORY_CONTENT, schema: 1,
      date: { mode: 'single', from: '2025-09-14' },
      location: { name: 'Lisbon', nameSource: 'manual', derivedMode: 'fixed' },
      media: [
        { file: { path: 'data/n3/a.jpg', size: 1, mediaType: 'image/jpeg', modifiedAt: 1_700_000_000_000 }, name: 'a.jpg', capturedAt: '2023-11-14T22:13:20.000Z', location: { place: 'Lisbon' } },
        { file: { path: 'data/n3/b.jpg', size: 1, mediaType: 'image/jpeg', modifiedAt: 1_700_000_100_000 }, name: 'b.jpg', capturedAt: '2023-11-14T22:15:00.000Z', capturedAtCorrected: true },
        { file: { path: 'data/n3/c.jpg', size: 1, mediaType: 'image/jpeg' }, name: 'c.jpg' },
      ],
      cover: { mediaIndex: 1, crop: { x: 0, y: 0, width: 1, height: 1 } },
      ...over,
    },
  }) as unknown as ManifestNode
  const ctx = (n: ManifestNode) =>
    ({
      scopeId: SCOPE, epochs: {} as ScopeKey, parent: null, name: 'Lisbon (2)', node: n, cache: new Map(),
      payload: vi.fn(async (ref: { path: string; modifiedAt?: number }, name: string) => file(name, ref.modifiedAt ?? 0)),
    }) as unknown as ImportNodeContext

  beforeEach(() => {
    const nodeKey = v1.randomBytes(32)
    vi.mocked(createContentNode).mockResolvedValue({ nodeId: NEW, contentKey: nodeKey, epoch: 0, scopeEpoch: 0 })
    let n = 0
    vi.mocked(addMedia).mockImplementation(async () => ({ blobId: 'b', mediaId: `m${++n}` }))
    vi.mocked(prepareMedia).mockImplementation(async (source) => ({ source, sha256: 'd', tiers: [], meta: { dims: null } }) as unknown as PreparedMedia)
    vi.mocked(fetchTreeRows).mockResolvedValue([{ id: NEW, wrappedMeta: 'tw', metaRevision: 3, epoch: 0, scopeEpoch: 0 } as unknown as Awaited<ReturnType<typeof fetchTreeRows>>[number]])
  })

  it('creates the Memory with its date and place sealed at birth, adds the originals; an older archive\'s cover by mediaIndex is not restored', async () => {
    const c = ctx(node())
    const made = await importMemoryContent(MEMORY_KIND)(c)
    expect(made.nodeId).toBe(NEW)
    const [, , parent, kind, name, sealer] = vi.mocked(createContentNode).mock.calls[0]
    expect([parent, kind, name]).toEqual([null, MEMORY_KIND, 'Lisbon (2)'])
    const sealed = sealer!(made.nodeKey, { scopeId: SCOPE, nodeId: NEW, scopeEpoch: 1, placementRevision: 0 })
    const raw = unsealNodeMeta(made.nodeKey, { scopeId: SCOPE, nodeId: NEW }, sealed)
    expect(raw.date).toEqual({ mode: 'single', from: '2025-09-14' })
    expect(raw.location).toMatchObject({ name: 'Lisbon' })
    expect(vi.mocked(addMedia)).toHaveBeenCalledTimes(3)
    const src = vi.mocked(addMedia).mock.calls[0][3]
    expect(src).toMatchObject({ mediaType: 'image/jpeg', lastModified: 1_700_000_000_000, name: 'a.jpg' })
    expect(vi.mocked(writeNodeCover), 'a cover is set only by an upload: a photo reference is left out').not.toHaveBeenCalled()
  })

  it('imports an archive written while background music existed, and seals no playlist into the meta (ADR 0135 decision 1)', async () => {
    const n = node({ music: [{ service: 'spotify', id: '37i9dQZF1DXcBWIGoYBM5M' }, { service: 'apple-music', id: 'pl.u-abc123', storefront: 'de' }] })
    expect(validateMemoryContent(n.content as ManifestNode['content'] & object), 'the older archive passes the check').toBeNull()
    const made = await importMemoryContent(MEMORY_KIND)(ctx(n))
    expect(made.nodeId).toBe(NEW)
    const sealer = vi.mocked(createContentNode).mock.calls[0][5]
    const raw = unsealNodeMeta(made.nodeKey, { scopeId: SCOPE, nodeId: NEW }, sealer!(made.nodeKey, { scopeId: SCOPE, nodeId: NEW, scopeEpoch: 1, placementRevision: 0 }))
    expect(raw.music, 'the playlists are not restored').toBeUndefined()
    expect(raw.date, 'control: the meta beside it is sealed').toEqual({ mode: 'single', from: '2025-09-14' })
    expect(vi.mocked(addMedia), 'control: the media are imported').toHaveBeenCalledTimes(3)
  })

  it('hands each original to the upload as the very object the payload returned, so the digest core remembered for it is read (inbox #555)', async () => {
    const c = ctx(node())
    await importMemoryContent(MEMORY_KIND)(c)
    const returned = await Promise.all(vi.mocked(c.payload).mock.results.map((r) => r.value as Promise<File>))
    const uploaded = vi.mocked(addMedia).mock.calls.map((call) => call[3].blob)
    expect(uploaded).toHaveLength(3)
    uploaded.forEach((blob, i) => expect(blob, `medium ${i}`).toBe(returned[i]))
  })

  describe('the next original is prepared while this one is on the wire (inbox #784)', () => {
    /** Each `addMedia` reports its first progress, then stays on the wire until the test lets it land (`land`, in order). */
    function onTheWire() {
      const land: (() => void)[] = []
      vi.mocked(addMedia).mockImplementation(async (_s, _n, _k, source, _d, opts) => {
        opts!.onProgress!(0)
        await new Promise<void>((r) => land.push(r))
        return { blobId: 'b', mediaId: source.name! }
      })
      return { land }
    }
    const flush = async () => { for (let i = 0; i < 10; i++) await Promise.resolve() }

    it('asks for and prepares the next original once this one reports its first progress, never before, and hands each its own stage', async () => {
      const { land } = onTheWire()
      let first!: () => void
      vi.mocked(addMedia).mockImplementationOnce(async (_s, _n, _k, source, _d, opts) => {
        await new Promise<void>((r) => (first = r))
        // its own stage is still running: the next one has not started (one decode at a time)
        expect(vi.mocked(prepareMedia).mock.calls.map(([s]) => s.name)).toEqual(['a.jpg'])
        opts!.onProgress!(0)
        await new Promise<void>((r) => land.push(r))
        return { blobId: 'b', mediaId: source.name! }
      })
      const c = ctx(node())
      const run = importMemoryContent(MEMORY_KIND)(c)
      await flush()
      expect(vi.mocked(c.payload).mock.calls.map(([ref]) => ref.path), 'before the first progress').toEqual(['data/n3/a.jpg'])
      first()
      await flush()
      // a.jpg is on the wire and has not landed: b.jpg is asked for and through its stage
      expect(vi.mocked(c.payload).mock.calls.map(([ref]) => ref.path)).toEqual(['data/n3/a.jpg', 'data/n3/b.jpg'])
      expect(vi.mocked(prepareMedia).mock.calls.map(([s]) => s.name)).toEqual(['a.jpg', 'b.jpg'])
      expect(addMedia).toHaveBeenCalledTimes(1)
      land.shift()!()
      await flush()
      expect(vi.mocked(prepareMedia).mock.calls.map(([s]) => s.name), 'c.jpg ahead of b.jpg on the wire').toEqual(['a.jpg', 'b.jpg', 'c.jpg'])
      land.shift()!()
      await flush()
      land.shift()!()
      await run
      expect(prepareMedia).toHaveBeenCalledTimes(3)
      const calls = vi.mocked(addMedia).mock.calls
      const stages = await Promise.all(vi.mocked(prepareMedia).mock.results.map((r) => r.value as Promise<PreparedMedia>))
      calls.forEach((call, i) => {
        expect(call[3], `medium ${i}: its source is its stage's`).toBe(stages[i].source)
        expect(call[5]!.prepared, `medium ${i}: its own stage`).toBe(stages[i])
      })
    })

    it('a refusal the next payload meets is the import\'s only after this medium landed, and the medium after it is never asked for', async () => {
      const { land } = onTheWire()
      const c = ctx(node())
      const refused = new Error('checksum')
      vi.mocked(c.payload).mockImplementation(async (ref, name) => {
        if (ref.path === 'data/n3/b.jpg') throw refused
        return file(name, 0)
      })
      let settled = false
      const run = importMemoryContent(MEMORY_KIND)(c).finally(() => { settled = true })
      run.catch(() => undefined)
      await flush()
      expect(vi.mocked(c.payload).mock.calls.map(([ref]) => ref.path)).toEqual(['data/n3/a.jpg', 'data/n3/b.jpg'])
      expect(settled, 'a.jpg is still on the wire: the refusal waits for it').toBe(false)
      land.shift()!()
      await expect(run).rejects.toBe(refused)
      expect(addMedia).toHaveBeenCalledTimes(1)
      expect(vi.mocked(addMedia).mock.results[0].type).toBe('return')
      expect(vi.mocked(c.payload).mock.calls.map(([ref]) => ref.path), 'nothing after the refused one').toEqual(['data/n3/a.jpg', 'data/n3/b.jpg'])
    })

    it('a medium that fails ends the import with its own error; the next one, already prepared, is dropped unawaited', async () => {
      const c = ctx(node())
      vi.mocked(addMedia).mockImplementationOnce(async (_s, _n, _k, _src, _d, opts) => {
        opts!.onProgress!(0)
        await flush()
        throw new Error('upload_failed')
      })
      vi.mocked(c.payload).mockImplementation(async (ref, name) => {
        if (ref.path === 'data/n3/b.jpg') throw new Error('checksum')
        return file(name, 0)
      })
      await expect(importMemoryContent(MEMORY_KIND)(c)).rejects.toThrow('upload_failed')
      expect(addMedia).toHaveBeenCalledTimes(1)
    })
  })

  it('restores a correction as an override and a place name at birth, in the upload of the medium that carries them (inbox #700)', async () => {
    await importMemoryContent(MEMORY_KIND)(ctx(node()))
    const restored = vi.mocked(addMedia).mock.calls.map((c) => c[5]?.restored)
    // a.jpg's time was not corrected: the bytes' own is read again, only its place comes back
    expect(restored).toEqual([{ placeName: 'Lisbon' }, { capturedAtOverride: Date.parse('2023-11-14T22:15:00.000Z') }, {}])
    expect([setMediaMeta, backfillPlaceName, listMedia].map((f) => vi.mocked(f).mock.calls.length), 'no second pass').toEqual([0, 0, 0])
  })

  it('restores a GPX track\'s own name and activity at birth, beside the rest of its facts', async () => {
    const track = { file: { path: 'data/n3/t.gpx', size: 1, mediaType: 'application/gpx+xml' }, name: 't.gpx', track: { name: 'Gipfeltour', activity: 'cycling' } }
    const plain = { file: { path: 'data/n3/u.gpx', size: 1, mediaType: 'application/gpx+xml' }, name: 'u.gpx', track: { name: '  ' } }
    await importMemoryContent(MEMORY_KIND)(ctx(node({ media: [track, plain] })))
    expect(vi.mocked(addMedia).mock.calls.map((c) => c[5]?.restored)).toEqual([{ trackName: 'Gipfeltour', trackActivity: 'cycling' }, {}])
  })

  it('creates a chapter under its Memory with the chapter kind', async () => {
    const n = node({ type: CHAPTER_CONTENT, media: [], cover: undefined, date: undefined, location: undefined })
    n.kind = CHAPTER_KIND
    const c = ctx(n)
    ;(c as { parent: unknown }).parent = { id: 'mem', key: new Uint8Array(32) }
    await importMemoryContent(CHAPTER_KIND)(c)
    expect(vi.mocked(createContentNode).mock.calls[0][3]).toBe(CHAPTER_KIND)
    expect(vi.mocked(createContentNode).mock.calls[0][5]).toBeUndefined() // nothing to seal at birth
    expect(vi.mocked(addMedia)).not.toHaveBeenCalled()
    expect(vi.mocked(writeNodeCover)).not.toHaveBeenCalled()
  })

  it('restores an uploaded cover through the cover upload', async () => {
    vi.mocked(uploadCoverImage).mockResolvedValue({ uploadId: 'u', plan: { blobId: 'cb', chunkCount: 1, byteSize: 10, wrappedBlobKey: 'k', ownerId: NEW } })
    const n = node({ media: [], cover: { file: { path: 'data/n3/cover', size: 1, mediaType: 'image/jpeg' }, crop: { x: 0.1, y: 0.1, width: 0.5, height: 0.5 } } })
    await importMemoryContent(MEMORY_KIND)(ctx(n))
    expect(vi.mocked(uploadCoverImage)).toHaveBeenCalledTimes(1)
    expect(vi.mocked(writeNodeCover)).toHaveBeenCalledWith(expect.objectContaining({ uploadId: 'u', cover: expect.objectContaining({ source: expect.objectContaining({ kind: 'upload' }) }) }))
  })

  it('an import of a download ignores a `copy` it finds: the original goes through the ordinary upload, a cover through the re-encode', async () => {
    const tier = { kind: 'THUMBNAIL', file: { path: 'data/n3/t', size: 1, mediaType: 'image/jpeg' } }
    const forged = node({
      media: [{ file: { path: 'data/n3/a.jpg', size: 1, mediaType: 'image/jpeg' }, name: 'a.jpg', copy: { tiers: [tier], meta: { v: 1, gpsLat: 1 } } }],
      cover: { file: { path: 'data/n3/cover', size: 1, mediaType: 'image/jpeg' }, crop: { x: 0, y: 0, width: 1, height: 1 } },
    })
    vi.mocked(uploadCoverImage).mockResolvedValue({ uploadId: 'u', plan: { blobId: 'cb', chunkCount: 1, byteSize: 10, wrappedBlobKey: 'k', ownerId: NEW } })
    const c = ctx(forged)
    ;(c as { purpose: string }).purpose = 'import'
    await importMemoryContent(MEMORY_KIND)(c)
    expect(addMedia).toHaveBeenCalledTimes(1)
    expect(addCopiedMedia).not.toHaveBeenCalled()
    expect(uploadCoverBlob).not.toHaveBeenCalled()
    expect(vi.mocked(c.payload).mock.calls.map(([ref]) => ref.path)).toEqual(['data/n3/a.jpg', 'data/n3/cover'])
  })

  it('a copy uploads each medium with its tiers and facts, decodes nothing, seals every fact at birth and copies the cover blob', async () => {
    let n = 0
    vi.mocked(addCopiedMedia).mockImplementation(async () => ({ blobId: 'b', mediaId: `c${++n}` }))
    vi.mocked(uploadCoverBlob).mockResolvedValue({ uploadId: 'u', plan: { blobId: 'cb', chunkCount: 1, byteSize: 10, wrappedBlobKey: 'k', ownerId: NEW } })
    const tier = { kind: 'PREVIEW', file: { path: 'Lisbon/a.jpg.preview', size: 2, mediaType: 'image/jpeg' }, meta: { type: 'image/jpeg' } }
    const copied = node({
      media: [
        { file: { path: 'Lisbon/a.jpg', size: 1, mediaType: 'image/jpeg' }, name: 'a.jpg', capturedAtCorrected: true, capturedAt: '2023-11-14T22:15:00.000Z', location: { place: 'Lisbon' },
          exif: { v: 1, make: 'Fuji' }, copy: { original: { type: 'image/jpeg', name: 'a.jpg' }, meta: { v: 1, placeName: 'Lisbon' }, tiers: [tier] } },
      ],
      cover: { file: { path: 'Lisbon/cover.jpg', size: 3, mediaType: 'image/jpeg' }, crop: { x: 0, y: 0, width: 1, height: 1 } },
    })
    const c = ctx(copied)
    ;(c as { purpose: string }).purpose = 'copy'
    await importMemoryContent(MEMORY_KIND)(c)
    expect(addMedia).not.toHaveBeenCalled()
    const [, nodeId, , source] = vi.mocked(addCopiedMedia).mock.calls[0]
    expect(nodeId).toBe(NEW)
    expect(source).toMatchObject({ original: { meta: { type: 'image/jpeg', name: 'a.jpg' } }, meta: { v: 1, placeName: 'Lisbon' }, exif: { v: 1, make: 'Fuji' } })
    expect(source.tiers.map((t) => [t.kind, t.meta])).toEqual([['PREVIEW', { type: 'image/jpeg' }]])
    expect([setMediaMeta, backfillPlaceName, listMedia].map((f) => vi.mocked(f).mock.calls.length)).toEqual([0, 0, 0])
    expect(uploadCoverImage).not.toHaveBeenCalled()
    expect(vi.mocked(uploadCoverBlob).mock.calls[0][4]).toEqual({ type: 'image/jpeg' })
    expect(vi.mocked(writeNodeCover)).toHaveBeenCalledWith(expect.objectContaining({ uploadId: 'u' }))
  })

  it('a copy\'s medium without its tiers and facts is refused, never decoded', async () => {
    const c = ctx(node({ cover: undefined }))
    ;(c as { purpose: string }).purpose = 'copy'
    await expect(importMemoryContent(MEMORY_KIND)(c)).rejects.toThrow('copy_without_facts')
    expect(addMedia).not.toHaveBeenCalled()
  })

  it('refuses content that is not a Memory\'s, and a newer schema', async () => {
    await expect(importMemoryContent(MEMORY_KIND)(ctx({ id: 'n', kind: MEMORY_KIND, name: 'x', content: { type: 'depot.file' } } as ManifestNode))).rejects.toThrow('import_invalid_content')
    await expect(importMemoryContent(MEMORY_KIND)(ctx(node({ schema: 9 })))).rejects.toThrow('import_newer_content')
    expect(createContentNode).not.toHaveBeenCalled()
  })
})

describe('validateMemoryContent: a download\'s Memory checked against its contract before anything is written', () => {
  const medium = { file: { path: 'Trip/a.jpg', size: 1, mediaType: 'image/jpeg' }, name: 'a.jpg' }
  const sound = { type: MEMORY_CONTENT, schema: 1, media: [medium], date: { mode: 'range', from: '2025-09-14', to: '2025-09-17' }, participants: ['Anna'], cover: { file: { path: 'Trip/cover.jpg', size: 1, mediaType: 'image/jpeg' }, crop: {} } }

  it('control: sound content passes, unknown fields are ignored', () => {
    expect(validateMemoryContent({ ...sound, someday: 'a field a later build adds' })).toBeNull()
    expect(validateMemoryContent({ type: CHAPTER_CONTENT, media: [] })).toBeNull()
    // an older archive's cover by `mediaIndex` has no file: left out on import, never a refusal
    expect(validateMemoryContent({ ...sound, cover: { mediaIndex: 0, crop: {} } })).toBeNull()
  })

  it.each([
    ['another kind\'s content', { ...sound, type: 'depot.file' }, 'invalid_manifest'],
    ['media that is no list', { ...sound, media: {} }, 'invalid_manifest'],
    ['a medium without its file', { ...sound, media: [{ name: 'a.jpg' }] }, 'invalid_manifest'],
    ['a capture time that is no time', { ...sound, media: [{ ...medium, capturedAt: 'yesterday' }] }, 'invalid_manifest'],
    ['a latitude out of range', { ...sound, media: [{ ...medium, location: { lat: 91 } }] }, 'invalid_manifest'],
    ['a date of another shape', { ...sound, date: { mode: 'someday' } }, 'invalid_manifest'],
    ['a cover whose file is no payload', { ...sound, cover: { file: 'cover.jpg', crop: {} } }, 'invalid_manifest'],
    ['a medium name over its length', { ...sound, media: [{ ...medium, name: 'x'.repeat(MEMORIES_LIMITS.name + 1) }] }, 'too_large'],
    ['a contributor over its length', { ...sound, media: [{ ...medium, contributor: 'x'.repeat(MEMORIES_LIMITS.contributor + 1) }] }, 'too_large'],
    ['a place over its length', { ...sound, location: { name: 'x'.repeat(MEMORIES_LIMITS.place + 1) } }, 'too_large'],
    ['a participant over its length', { ...sound, participants: ['x'.repeat(MEMORIES_LIMITS.participant + 1)] }, 'too_large'],
    ['more participants than the cap', { ...sound, participants: Array.from({ length: MEMORIES_LIMITS.participants + 1 }, () => 'A') }, 'too_large'],
    ['a track label that is no object', { ...sound, media: [{ ...medium, track: 'Ridge' }] }, 'invalid_manifest'],
    ['a track activity the format does not name', { ...sound, media: [{ ...medium, track: { activity: 'paragliding' } }] }, 'invalid_manifest'],
    ['a track name over its length', { ...sound, media: [{ ...medium, track: { name: 'x'.repeat(MEMORIES_LIMITS.trackName + 1) } }] }, 'too_large'],
    ['a banner with no file', { ...sound, banner: { medium: 'image' } }, 'invalid_manifest'],
    ['a banner of another medium', { ...sound, banner: { medium: 'audio', file: { path: 'b', size: 1 } } }, 'invalid_manifest'],
    ['a banner focal point off the medium', { ...sound, banner: { medium: 'image', file: { path: 'b', size: 1 }, focal: { x: 2, y: 0 } } }, 'invalid_manifest'],
  ])('refuses %s', (_what, content, code) => {
    expect(validateMemoryContent(content as unknown as ManifestNode['content'] & object)).toMatchObject({ code })
  })

  it('each limit is the schema\'s maxLength / maxItems of that field', () => {
    // Memories' own content schema (ADR 0082): core's envelope leaves `content` opaque.
    const schema = JSON.parse(readFileSync(new URL('../../../../../contracts/export/memories/content.schema.json', import.meta.url), 'utf8'))
    const d = schema.$defs
    expect({
      name: d.medium.properties.name.maxLength,
      contributor: d.medium.properties.contributor.maxLength,
      place: d.place.properties.name.maxLength,
      participant: d.memory.properties.participants.items.maxLength,
      participants: d.memory.properties.participants.maxItems,
      trackName: d.medium.properties.track.properties.name.maxLength,
    }).toEqual(MEMORIES_LIMITS)
    expect(d.medium.properties.track.properties.activity.enum, 'the activities are the client\'s').toEqual([...TRACK_ACTIVITIES])
    // Background music left the format (ADR 0135 decision 1), and neither node object closes its properties, so an
    // archive written before still validates against the schema.
    expect(d.music).toBeUndefined()
    expect(d.memory.properties.music).toBeUndefined()
    expect(d.chapter.properties.music).toBeUndefined()
    expect(d.memory.additionalProperties).toBeUndefined()
    expect(d.chapter.additionalProperties).toBeUndefined()
    // the banner has no description, and neither a cover nor a banner names a medium by index
    expect(Object.keys(d.banner.properties)).not.toContain('alt')
    expect(Object.keys(d.banner.properties)).not.toContain('mediaIndex')
    expect(Object.keys(d.cover.properties)).not.toContain('mediaIndex')
    expect(d.chapter.properties.banner.$ref).toBe('#/$defs/banner')
    expect(d.medium.properties.location.properties.place.maxLength).toBe(MEMORIES_LIMITS.place)
    expect(d.chapter.properties.participants.maxItems).toBe(MEMORIES_LIMITS.participants)
    expect(d.chapter.properties.participants.items.maxLength).toBe(MEMORIES_LIMITS.participant)
    // the file's content types are the two this module writes, and only those
    expect(schema.oneOf.map((r: { $ref: string }) => d[r.$ref.replace('#/$defs/', '')].properties.type.const)).toEqual([MEMORY_CONTENT, CHAPTER_CONTENT])
  })
})

// uc.memory.banner: the banner travels with its Memory as its own files and its framing (ADR 0082). Bite 11: drop
// `content.banner` from the export and this round trip loses the banner, its frame and its focal point.
describe('the banner travels with its Memory', () => {
  const ref = (blobId: string) => ({ blobId, chunkCount: 1, byteSize: 100, wrappedBlobKey: 'k' })
  const stored = {
    medium: 'video', blob: ref('v'), poster: ref('p'), type: 'video/quicktime', origin: { mediaId: 'b' },
    frame: 'fit', focal: { x: 0.2, y: 0.7 }, alt: 'The bay', // an older client's origin and description travel nowhere
  }
  const DIG = 'ungWv48Bz-pBQUDeXa4iI7ADYaOWF3qctBD_YfIAFa0'
  const importCtx = (content: Record<string, unknown>) =>
    ({
      scopeId: SCOPE, epochs: {} as ScopeKey, parent: null, name: 'Lisbon', cache: new Map(),
      node: { id: 'n', kind: MEMORY_KIND, name: 'Lisbon', content },
      payload: vi.fn(async (_ref: unknown, name: string) => new File(['x'], name)),
    }) as unknown as ImportNodeContext

  beforeEach(() => {
    vi.mocked(makeChunkProvider).mockReturnValue({ chunkCount: 1, byteSize: 100, getPlaintextChunk: async () => new Uint8Array([1]) } as unknown as ReturnType<typeof makeChunkProvider>)
    vi.mocked(openBlob).mockResolvedValue(new Uint8Array([0xff, 0xd8]))
    vi.mocked(createContentNode).mockResolvedValue({ nodeId: NEW, contentKey: v1.randomBytes(32), epoch: 0, scopeEpoch: 0 })
    let n = 0
    vi.mocked(addMedia).mockImplementation(async () => ({ blobId: 'b', mediaId: `m${++n}` }))
    vi.mocked(listMedia).mockResolvedValue([])
    vi.mocked(fetchTreeRows).mockResolvedValue([{ id: NEW, wrappedMeta: 'tw', metaRevision: 3, epoch: 0, scopeEpoch: 0 } as unknown as Awaited<ReturnType<typeof fetchTreeRows>>[number]])
    vi.mocked(uploadBanner).mockResolvedValue({ medium: 'video', uploadId: 'bu', posterUploadId: 'pu', blob: ref('nv'), poster: ref('np'), type: 'video/quicktime', sha256: DIG })
  })

  it('a video banner exports as its files and framing, and comes back into the new node\'s slots with them', async () => {
    const { ctx, payloads } = exportCtx({ banner: stored })
    const content = (await exportMemoryContent(ctx))!
    expect(payloads.slice(-2).map((p) => [p.name, p.mediaType])).toEqual([['banner.mov', 'video/quicktime'], ['banner-poster.jpg', 'image/jpeg']])
    expect(content.banner).toMatchObject({ medium: 'video', frame: 'fit', focal: { x: 0.2, y: 0.7 } })
    expect(content.banner).not.toHaveProperty('alt')
    expect(content.banner).not.toHaveProperty('mediaIndex')
    expect(validateMemoryContent(content)).toBeNull()
    // read through the member's chunk source over the BANNER ref: the banner's own attachment, nothing of the gallery
    expect(vi.mocked(makeChunkProvider).mock.calls[0][2]).toMatchObject({ blobId: 'v', role: 'BANNER', ownerId: NODE })

    const wire = JSON.parse(JSON.stringify(content)) as Record<string, unknown> // as an archive carries it
    await importMemoryContent(MEMORY_KIND)(importCtx(wire))
    // a video's file is the banner's original: its digest is read again for the banner's `sha256` and its blob-meta
    expect(vi.mocked(uploadBanner).mock.calls[0][3]).toMatchObject({
      medium: 'video', type: 'video/quicktime', sha256: await contentDigest(new File(['x'], 'banner.mov')), video: { upload: expect.any(File) },
    })
    expect(vi.mocked(writeMemoryBanner)).toHaveBeenCalledWith(expect.objectContaining({
      nodeId: NEW, upload: { uploadId: 'bu', posterUploadId: 'pu' }, surfaceConflict: false,
      banner: {
        medium: 'video', blob: ref('nv'), poster: ref('np'), type: 'video/quicktime', sha256: DIG,
        frame: 'fit', focal: { x: 0.2, y: 0.7 },
      },
    }))
  })

  it('a video banner\'s file reaches the digest door as the object the payload returned: its remembered digest is the banner\'s (inbox #555)', async () => {
    const { ctx } = exportCtx({ banner: stored })
    const c = importCtx(JSON.parse(JSON.stringify((await exportMemoryContent(ctx))!)) as Record<string, unknown>)
    // a value no hash of the file gives: the banner's `sha256` can only be what core remembered for that very object
    const marker = new Uint8Array(32).fill(0x5a)
    const returned: File[] = []
    vi.mocked(c.payload).mockImplementation(async (_ref, name) => {
      const f = new File(['x'], name)
      rememberDigest(f, marker)
      returned.push(f)
      return f
    })
    await importMemoryContent(MEMORY_KIND)(c)
    const prepared = vi.mocked(uploadBanner).mock.calls[0][3]
    if (prepared.medium !== 'video') throw new Error('expected a video banner')
    expect((prepared.video as { upload: File }).upload).toBe(returned.find((f) => f.name === 'banner.mov'))
    expect(prepared.sha256).toBe(b64url(marker))
  })

  it('a banner that does not come back is a loss, never a failed import', async () => {
    vi.mocked(uploadBanner).mockRejectedValue(new Error('quota_exceeded'))
    const content = { type: MEMORY_CONTENT, schema: 1, media: [], banner: { medium: 'image', file: { path: 'n/banner.jpg', size: 2, mediaType: 'image/jpeg' } } }
    await expect(importMemoryContent(MEMORY_KIND)(importCtx(content))).resolves.toMatchObject({ nodeId: NEW })
    expect(writeMemoryBanner).not.toHaveBeenCalled()
  })
})
