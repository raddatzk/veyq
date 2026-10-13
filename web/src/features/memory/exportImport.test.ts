import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('./media', async (orig) => ({
  ...(await orig<typeof import('./media')>()),
  listMedia: vi.fn(), unsealMeta: vi.fn(), originalBlobMeta: vi.fn(), originalMediaType: vi.fn(), loadMediaExif: vi.fn(),
  chunkProvider: vi.fn(), plaintextSize: vi.fn(), addMedia: vi.fn(), setMediaMeta: vi.fn(), backfillPlaceName: vi.fn(),
}))
vi.mock('../../core/people/directory', () => ({ loadTenantPeople: vi.fn() }))
vi.mock('../../core/blob/transport', () => ({ openBlob: vi.fn() }))
vi.mock('../../core/node/tree', async (orig) => ({ ...(await orig<typeof import('../../core/node/tree')>()), createContentNode: vi.fn(), listTree: vi.fn() }))
vi.mock('../../core/node/cover/coverWrite', () => ({ uploadCoverImage: vi.fn(), writeNodeCover: vi.fn() }))

import * as v1 from '@veyq/crypto/v1'
import { addMedia, backfillPlaceName, chunkProvider, listMedia, loadMediaExif, originalBlobMeta, originalMediaType, plaintextSize, setMediaMeta, unsealMeta, type MediaItem } from './media'
import { loadTenantPeople, type Person } from '../../core/people/directory'
import { createContentNode, listTree, type TreeNode } from '../../core/node/tree'
import { writeNodeCover, uploadCoverImage } from '../../core/node/cover/coverWrite'
import { sealNodeMeta, unsealNodeMeta } from '../../core/node/nodeMeta'
import type { ExportNodeContext, ImportNodeContext } from '../../core/export/hooks'
import type { ManifestNode } from '../../core/export/manifest'
import type { EpochKeys } from '../spaces/epochs'
import { exportMemoryContent, importMemoryContent, MEMORY_CONTENT, CHAPTER_CONTENT } from './exportImport'
import { CHAPTER_KIND, MEMORY_KIND } from './kinds'

const SCOPE = '11111111-1111-4111-8111-111111111111'
const NODE = '22222222-2222-4222-8222-222222222222'
const U1 = '44444444-4444-4444-8444-444444444444'
const U2 = '55555555-5555-4555-8555-555555555555'
const GONE = '66666666-6666-4666-8666-666666666666'
const NEW = '33333333-3333-4333-8333-333333333333'
const KEY = v1.randomBytes(32)
const item = (mediaId: string, ownerKey = `user:${U1}`): MediaItem => ({ mediaId, ownerKey, original: { byteSize: 1, chunkCount: 1 }, derivatives: [] }) as unknown as MediaItem
const person = (displayName: string) => ({ displayName }) as unknown as Person

function exportCtx(options: { includeLocation: boolean }, meta: Record<string, unknown> = {}, kind = MEMORY_KIND) {
  const payloads: { name: string; modifiedAt?: number; mediaType: string }[] = []
  const node = { id: NODE, kind, name: 'Lisbon', wrappedMeta: sealNodeMeta(KEY, { scopeId: SCOPE, nodeId: NODE }, { v: 1, ...meta }) } as unknown as TreeNode
  const ctx = {
    scopeId: SCOPE, node, nodeKey: KEY, options, cache: new Map(), epochs: {},
    addPayload: (name: string, s: { size: number; mediaType: string; modifiedAt?: number }) => {
      payloads.push({ name, modifiedAt: s.modifiedAt, mediaType: s.mediaType })
      return { path: `data/n1/${name}`, size: s.size, mediaType: s.mediaType }
    },
  } as unknown as ExportNodeContext
  return { ctx, payloads }
}

beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(loadTenantPeople).mockResolvedValue(new Map([[U1, person('Anna Meyer')], [U2, person('Ben')]]))
  vi.mocked(listMedia).mockResolvedValue([item('a'), item('b')])
  vi.mocked(plaintextSize).mockReturnValue(100)
  vi.mocked(originalMediaType).mockReturnValue('image/jpeg')
  vi.mocked(chunkProvider).mockReturnValue({ chunkCount: 0, getPlaintextChunk: async () => new Uint8Array(0) } as unknown as ReturnType<typeof chunkProvider>)
  vi.mocked(loadMediaExif).mockResolvedValue({ make: 'Fuji', altitude: 12, imgDirection: 90 } as unknown as Awaited<ReturnType<typeof loadMediaExif>>)
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
    const { ctx, payloads } = exportCtx({ includeLocation: true }, meta)
    const c = (await exportMemoryContent(ctx))!
    expect(c).toMatchObject({ type: MEMORY_CONTENT, schema: 1, date: meta.date, participants: ['Anna Meyer', 'Ben'], cover: { mediaIndex: 1 } })
    expect(c.location).toMatchObject({ name: 'Lisbon', derived: { lat: 38.7, lng: -9.1 } })
    const [a, b] = c.media as { name: string; capturedAt: string; capturedAtCorrected?: true; location?: unknown; exif?: Record<string, unknown>; contributor?: string }[]
    expect(a).toMatchObject({ name: 'a.jpg', capturedAt: new Date(1_700_000_000_000).toISOString(), location: { lat: 38.7, lon: -9.1, place: 'Lisbon' }, contributor: 'Anna Meyer' })
    expect(a.capturedAtCorrected).toBeUndefined()
    expect(a.exif).toMatchObject({ make: 'Fuji', altitude: 12 })
    expect(b).toMatchObject({ capturedAt: new Date(1_700_000_100_000).toISOString(), capturedAtCorrected: true })
    // the upload falls back to the modified time for a missing capture time: the capture time rides there
    expect(payloads.map((p) => p.modifiedAt)).toEqual([1_700_000_000_000, 1_700_000_100_000])
  })

  it('opted out of location: no coordinates, no place anywhere in the manifest copies, no locating EXIF', async () => {
    const { ctx } = exportCtx({ includeLocation: false }, meta)
    const c = (await exportMemoryContent(ctx))!
    expect(c.location).toBeUndefined()
    const [a] = c.media as { location?: unknown; exif?: Record<string, unknown> }[]
    expect(a.location).toBeUndefined()
    expect(a.exif).toEqual({ make: 'Fuji' })
  })

  it('a chapter exports as memories.chapter', async () => {
    const { ctx } = exportCtx({ includeLocation: true }, {}, CHAPTER_KIND)
    expect((await exportMemoryContent(ctx))!.type).toBe(CHAPTER_CONTENT)
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
      scopeId: SCOPE, epochs: {} as EpochKeys, parent: null, name: 'Lisbon (2)', node: n, cache: new Map(),
      payload: vi.fn(async (ref: { path: string; modifiedAt?: number }, name: string) => file(name, ref.modifiedAt ?? 0)),
    }) as unknown as ImportNodeContext

  beforeEach(() => {
    const nodeKey = v1.randomBytes(32)
    vi.mocked(createContentNode).mockResolvedValue({ nodeId: NEW, contentKey: nodeKey })
    let n = 0
    vi.mocked(addMedia).mockImplementation(async () => ({ blobId: 'b', mediaId: `m${++n}` }))
    vi.mocked(listMedia).mockResolvedValue(['m1', 'm2', 'm3'].map((id) => ({ ...item(id), wrappedMeta: 'w', metaVersion: 1 })))
    vi.mocked(setMediaMeta).mockResolvedValue({ tier: null, wrappedMeta: 'w2', metaVersion: 2, wrote: true })
    vi.mocked(listTree).mockResolvedValue([{ id: NEW, wrappedMeta: 'tw', metaVersion: 3 } as unknown as TreeNode])
  })

  it('creates the Memory with its date and place sealed at birth, adds the originals through the upload and sets the cover', async () => {
    const c = ctx(node())
    const made = await importMemoryContent(MEMORY_KIND)(c)
    expect(made.nodeId).toBe(NEW)
    const [, , parent, kind, name, sealer] = vi.mocked(createContentNode).mock.calls[0]
    expect([parent, kind, name]).toEqual([null, MEMORY_KIND, 'Lisbon (2)'])
    const sealed = sealer!(made.nodeKey, { scopeId: SCOPE, nodeId: NEW, epoch: 1, placementVersion: 0 })
    const raw = unsealNodeMeta(made.nodeKey, { scopeId: SCOPE, nodeId: NEW }, sealed)
    expect(raw.date).toEqual({ mode: 'single', from: '2025-09-14' })
    expect(raw.location).toMatchObject({ name: 'Lisbon' })
    expect(vi.mocked(addMedia)).toHaveBeenCalledTimes(3)
    const src = vi.mocked(addMedia).mock.calls[0][3]
    expect(src).toMatchObject({ mediaType: 'image/jpeg', lastModified: 1_700_000_000_000, name: 'a.jpg' })
    expect(vi.mocked(writeNodeCover)).toHaveBeenCalledWith(expect.objectContaining({ nodeId: NEW, cover: { source: { kind: 'media', mediaId: 'm2' }, crop: { x: 0, y: 0, width: 1, height: 1 } } }))
  })

  it('restores a correction as an override and a place name, only for the media that need it', async () => {
    await importMemoryContent(MEMORY_KIND)(ctx(node()))
    expect(vi.mocked(setMediaMeta)).toHaveBeenCalledTimes(1)
    expect(vi.mocked(setMediaMeta).mock.calls[0][1]).toMatchObject({ mediaId: 'm2' })
    expect(vi.mocked(setMediaMeta).mock.calls[0][3]).toBe(Date.parse('2023-11-14T22:15:00.000Z'))
    expect(vi.mocked(backfillPlaceName)).toHaveBeenCalledTimes(1)
    expect(vi.mocked(backfillPlaceName).mock.calls[0][1]).toMatchObject({ mediaId: 'm1' })
    expect(vi.mocked(backfillPlaceName).mock.calls[0][3]).toBe('Lisbon')
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

  it('refuses content that is not a Memory\'s, and a newer schema', async () => {
    await expect(importMemoryContent(MEMORY_KIND)(ctx({ id: 'n', kind: MEMORY_KIND, name: 'x', content: { type: 'files.file' } } as ManifestNode))).rejects.toThrow('import_invalid_content')
    await expect(importMemoryContent(MEMORY_KIND)(ctx(node({ schema: 9 })))).rejects.toThrow('import_newer_content')
    expect(createContentNode).not.toHaveBeenCalled()
  })
})
