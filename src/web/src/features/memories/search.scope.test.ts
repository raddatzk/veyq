// @vitest-environment happy-dom
// Memories' part in the search of every space (ADR 0125 decision 8a, `scopeMediaDocuments`): the space's listing read by its
// tag, each row opened under its node's key with the real crypto (rows sealed by the real upload path), one document per
// medium - its original's name, its capture time, its place -; a `304` opens nothing; a medium at its revision is kept
// unopened; a blocked medium is no document; the pages are followed by the last id.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'

vi.mock('../../api/client', () => ({ blobApi: vi.fn(), contentApi: vi.fn(), loginApi: vi.fn(), tokenApi: vi.fn(), sessionApi: vi.fn() }))
vi.mock('./api', () => ({ mediaApi: vi.fn() }))
vi.mock('../../shared/image/exifTags', () => ({ readExifTags: vi.fn() }))

import * as v1 from '@veyq/crypto/v1'
import { addMedia, type MediaDeriver } from './media'
import { readExifTags } from '../../shared/image/exifTags'
import { blobApi } from '../../api/client'
import { mediaApi } from './api'
import { fakeApi } from '../../test/apiMock'
import { galleryFakeStore } from './components/galleryFakeStore'
import { PHOTO_DOCUMENT, SCOPE_MEDIA_PAGE, scopeMediaDocuments } from './search'
import { runThrough } from '../../core/node/tree'
import type { ScopeDocumentsContext } from '../../core/applications/registry'
import type { ScopeDocument } from '../../core/search/model'
import type { ScopeMediaPage, ScopeMedium } from '../../api/generated/media'

const SCOPE_ID = '11111111-1111-1111-1111-111111111111'
const ROM = '22222222-2222-2222-2222-222222222222'
const GARDEN = '33333333-3333-3333-3333-333333333333'
const TAKEN = Date.UTC(2024, 4, 3, 12)

const deriver = (): MediaDeriver => ({
  deriveImage: vi.fn(async (_b: Blob, { maxEdge }: { maxEdge: number; quality: number }) => ({ bytes: new Uint8Array([0xff, 0xd8, maxEdge & 0xff]), source: null })),
  deriveVideoPoster: vi.fn(async () => new Uint8Array([0xff, 0xd8])),
})

/** The space's media as the server lists them: real sealed rows, uploaded to `ROM` and `GARDEN` under their own keys. */
async function space(): Promise<{ rows: ScopeMedium[]; keys: Map<string, Uint8Array> }> {
  const keys = new Map([[ROM, v1.randomBytes(32)], [GARDEN, v1.randomBytes(32)]])
  const rows: ScopeMedium[] = []
  for (const [nodeId, name] of [[ROM, 'strand.jpg'], [GARDEN, 'IMG_0002.jpg']] as const) {
    const store = galleryFakeStore()
    fakeApi(mediaApi, store.api)
    fakeApi(blobApi, store.blob)
    vi.stubGlobal('fetch', store.fetchStub)
    await addMedia(SCOPE_ID, nodeId, keys.get(nodeId)!, { blob: new Blob([new Uint8Array(8).fill(1) as BlobPart]), mediaType: 'image/jpeg', name }, deriver())
    for (const m of (await store.api.listNodeMedia({ scopeId: SCOPE_ID, nodeId })).media) {
      rows.push({
        nodeId, mediaId: m.mediaId, blobId: m.blobId, chunkCount: m.chunkCount, byteSize: m.byteSize, wrappedBlobKey: m.wrappedBlobKey,
        wrappedBlobMeta: m.wrappedBlobMeta, wrappedMeta: m.wrappedMeta, metaRevision: 0, ownerKey: 'user:u1', blockedByOperator: false,
      })
    }
  }
  return { rows, keys }
}

/** The listing's fake: `pages` in order; the first answers `304` when asked with `tag`. */
function listing(pages: ScopeMedium[][], tag = '"t1"') {
  const page = (media: ScopeMedium[], more: boolean): ScopeMediaPage => ({ media, more, nodes: [] })
  const raw = vi.fn(async (r: { ifNoneMatch?: string }) => {
    if (r.ifNoneMatch === tag) throw { response: { status: 304 } }
    return { raw: new Response(null, { headers: { ETag: tag } }), value: async () => page(pages[0], pages.length > 1) }
  })
  const later = vi.fn(async (r: { after?: string }) => {
    const at = pages.findIndex((p) => p.at(-1)?.mediaId === r.after) + 1
    return page(pages[at], at < pages.length - 1)
  })
  fakeApi(mediaApi, { listScopeMediaRaw: raw, listScopeMedia: later })
  return { raw, later }
}

function context(keys: Map<string, Uint8Array>, o: Partial<ScopeDocumentsContext> = {}) {
  const asked = vi.fn(async () => (nodeId: string) => keys.get(nodeId) ?? null)
  const ctx: ScopeDocumentsContext = {
    scopeId: SCOPE_ID, tag: null, previous: new Map(), keys: asked, signal: new AbortController().signal,
    slices: async (steps) => runThrough(steps), ...o,
  }
  return { ctx, asked }
}

beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(readExifTags).mockResolvedValue({ DateTimeOriginal: TAKEN })
})
afterEach(() => vi.unstubAllGlobals())

describe("Memories' photos of every space", () => {
  it('opens every medium under its node\'s key: its name, its capture time, its node and its tag', async () => {
    const { rows, keys } = await space()
    const { raw } = listing([rows])
    const { ctx, asked } = context(keys)
    const answer = await scopeMediaDocuments(ctx)
    expect(raw).toHaveBeenCalledWith(expect.objectContaining({ scopeId: SCOPE_ID, limit: SCOPE_MEDIA_PAGE, ifNoneMatch: undefined }), expect.anything())
    expect(answer?.tag).toBe('"t1"')
    expect(answer?.documents.map((d) => [d.name, d.nodeId, d.kind, d.facets.capturedAt])).toEqual([
      ['strand.jpg', ROM, PHOTO_DOCUMENT, TAKEN], ['IMG_0002.jpg', GARDEN, PHOTO_DOCUMENT, TAKEN],
    ])
    expect(asked).toHaveBeenCalledTimes(1)
  })

  it('answers a 304 with null, asking for no key', async () => {
    const { rows, keys } = await space()
    listing([rows])
    const { ctx, asked } = context(keys, { tag: '"t1"' })
    expect(await scopeMediaDocuments(ctx)).toBeNull()
    expect(asked).not.toHaveBeenCalled()
  })

  it('keeps a medium at its revision without opening it, and opens only what moved', async () => {
    const { rows, keys } = await space()
    listing([rows], '"t2"')
    const kept: ScopeDocument = { kind: PHOTO_DOCUMENT, id: rows[0].mediaId, nodeId: ROM, revision: `0:${rows[0].blobId}`, name: 'as held', facets: {} }
    const only = new Map([[GARDEN, keys.get(GARDEN)!]]) // the Memory of the kept medium has no key to hand: it must not be asked
    const { ctx } = context(only, { tag: '"t1"', previous: new Map([[kept.id, kept]]) })
    const answer = await scopeMediaDocuments(ctx)
    expect(answer?.documents.map((d) => d.name)).toEqual(['as held', 'IMG_0002.jpg'])
  })

  it('asks for no key when every medium is at its revision', async () => {
    const { rows, keys } = await space()
    listing([rows], '"t2"')
    const previous = new Map(rows.map((r): [string, ScopeDocument] => [r.mediaId, { kind: PHOTO_DOCUMENT, id: r.mediaId, nodeId: r.nodeId, revision: `0:${r.blobId}`, name: 'held', facets: {} }]))
    const { ctx, asked } = context(keys, { tag: '"t1"', previous })
    expect((await scopeMediaDocuments(ctx))?.documents).toHaveLength(2)
    expect(asked).not.toHaveBeenCalled()
  })

  it('makes no document of a blocked medium', async () => {
    const { rows, keys } = await space()
    listing([[{ ...rows[0], blockedByOperator: true }, rows[1]]])
    const { ctx } = context(keys)
    expect((await scopeMediaDocuments(ctx))?.documents.map((d) => d.name)).toEqual(['IMG_0002.jpg'])
  })

  it('follows the pages by the last id', async () => {
    const { rows, keys } = await space()
    const { later } = listing([[rows[0]], [rows[1]]])
    const { ctx } = context(keys)
    expect((await scopeMediaDocuments(ctx))?.documents.map((d) => d.name)).toEqual(['strand.jpg', 'IMG_0002.jpg'])
    expect(later).toHaveBeenCalledWith(expect.objectContaining({ after: rows[0].mediaId }), expect.anything())
  })

  it('leaves out a medium whose node has no key on this device', async () => {
    const { rows, keys } = await space()
    listing([rows])
    const { ctx } = context(new Map([[ROM, keys.get(ROM)!]]))
    expect((await scopeMediaDocuments(ctx))?.documents.map((d) => d.name)).toEqual(['strand.jpg'])
  })

  // ADR 0130 decision 7: a GPX track is a medium of its Memory and no photo - the search makes no photo document of it.
  it('makes no document of a GPX track', async () => {
    const { rows, keys } = await space()
    const store = galleryFakeStore()
    fakeApi(mediaApi, store.api)
    fakeApi(blobApi, store.blob)
    vi.stubGlobal('fetch', store.fetchStub)
    const gpx = '<gpx><trk><trkseg><trkpt lat="1" lon="1"/><trkpt lat="2" lon="2"/></trkseg></trk></gpx>'
    await addMedia(SCOPE_ID, ROM, keys.get(ROM)!, { blob: new Blob([gpx]), mediaType: 'application/gpx+xml', name: 'tour.gpx' }, deriver())
    const track = (await store.api.listNodeMedia({ scopeId: SCOPE_ID, nodeId: ROM })).media[0]
    listing([[...rows, {
      nodeId: ROM, mediaId: track.mediaId, blobId: track.blobId, chunkCount: track.chunkCount, byteSize: track.byteSize, wrappedBlobKey: track.wrappedBlobKey,
      wrappedBlobMeta: track.wrappedBlobMeta, wrappedMeta: track.wrappedMeta, metaRevision: 0, ownerKey: 'user:u1', blockedByOperator: false,
    }]])
    const { ctx } = context(keys)
    expect((await scopeMediaDocuments(ctx))?.documents.map((d) => d.name)).toEqual(['strand.jpg', 'IMG_0002.jpg'])
  })
})
