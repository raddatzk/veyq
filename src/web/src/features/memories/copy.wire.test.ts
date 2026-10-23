// A Memory copy on the wire (uc.platform.copy-node, ADR 0082 decision 11): a folder holding a Memory with a chapter, copied to
// the scope root through core's `prepareCopy` and Memories' real hooks (`exportMemoryContent` / `importMemoryContent`,
// `addCopiedMedia`, `uploadCoverBlob`, `writeNodeCover`), every request recorded at `fetch` - the JSON the real generated
// clients serialize and the frames the real pump PUTs - against a fake control plane and store that serve the ORIGINALS as a
// member's client reads them. Only the tenant's people directory is stubbed (a read the copy carries no name from), and the
// two kinds are registered with their real hooks (a node suite gets no registry). What it pins:
//   - the copy's writes carry new ids, keys and ciphertext only: no write names an original node, medium or blob, nor carries
//     an original wrap - only the reads do; and the reads are each original blob presigned once and each of its frames
//     fetched once (read by chunk, never whole);
//   - every node, medium and blob is born under a fresh key bound to the NEW ids, the original's keys open none of it;
//   - a medium's existing THUMBNAIL/PREVIEW tiers are uploaded as they are (their frames decrypt to the original tiers'
//     bytes) - nothing is decoded or derived: `createImageBitmap` and object URLs are never touched;
//   - the facts are kept: the listed tier sealed anew carries every field verbatim (capture time and its correction,
//     geometry, coordinates, place name, a field a newer client wrote), the EXIF bundle, each blob's declared meta, the
//     Memory's date and place; the uploaded cover is copied as its blob (its bytes, no re-encode) with its crop, and the
//     chapter's photo cover follows its medium to the copy's new id.
import { describe, it, expect, vi, beforeAll, afterEach } from 'vitest'

vi.mock('../../core/people/directory', () => ({ loadTenantPeople: vi.fn(async () => new Map()) }))

import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'
import { prepareCopy } from '../../core/export/copySubtree'
import { CHUNK_SIZE } from '../../core/blob/transport'
import { FOLDER_KIND } from '../../core/node/kind'
import type { TreeNode } from '../../core/node/tree'
import type { TreeSource } from '../../core/access'
import { sealNodeMeta, unsealNodeMeta } from '../../core/node/nodeMeta'
import { epochsOf } from '../spaces/epochsTestUtil'
import { session } from '../../app/session'
import { memberAccessOf, treeNode } from '../../test/models'
import { registry } from '../../core/applications/registry'
import { CHAPTER_KIND, MEMORY_KIND } from './kinds'
import { MEMORIES_CONTENT_SCHEMA, exportMemoryContent, importMemoryContent } from './exportImport'
import { MEDIA_PAGE_LIMIT } from './media'

const SCOPE = '44444444-4444-4444-8444-444444444444'
const FOLDER = '5c09e000-0000-4000-8000-0000000000f1'
const MEMORY = '5c09e000-0000-4000-8000-0000000000f2'
const CHAPTER = '5c09e000-0000-4000-8000-0000000000f3'
const M1 = '5c09e000-0000-4000-8000-0000000000e1'
const M2 = '5c09e000-0000-4000-8000-0000000000e2'
const scopeKey = v1.randomBytes(32)
const folderKey = v1.randomBytes(32)
const memoryKey = v1.randomBytes(32)
const chapterKey = v1.randomBytes(32)

/** A stored blob: its plaintext, its K_blob, its frames, and the ref the server lists. */
type Stored = { blobId: string; plain: Uint8Array; key: Uint8Array; frames: Uint8Array[]; wrap: string; ref: Record<string, unknown> }
const blobs = new Map<string, Stored>()
const blobKeyCtx = (ownerId: string, blobId: string, role: string) => ({ purpose: 'blob-key', scopeId: SCOPE, ownerId, blobId, role }) as const
function stored(n: number, size: number, ownerId: string, role: string, ownerKey: Uint8Array, meta?: v1.BlobMeta): Stored {
  const blobId = `5c09e000-0000-4000-8000-0000000000${(0xa0 + n).toString(16)}`
  const plain = new Uint8Array(size).map((_, i) => (i * 7 + n) % 251)
  const key = v1.randomBytes(32)
  const total = Math.max(1, Math.ceil(size / CHUNK_SIZE))
  const frames = Array.from({ length: total }, (_, i) => v1.sealFrame(key, i, total, plain.slice(i * CHUNK_SIZE, (i + 1) * CHUNK_SIZE), v1.randomBytes(12)))
  const wrap = b64std(v1.seal(blobKeyCtx(ownerId, blobId, role), ownerKey, key))
  const ref: Record<string, unknown> = { blobId, chunkCount: total, byteSize: size + total * v1.FRAME_OVERHEAD, wrappedBlobKey: wrap }
  if (meta) ref.wrappedBlobMeta = b64std(v1.sealBlobMeta(key, meta))
  const s = { blobId, plain, key, frames, wrap, ref }
  blobs.set(blobId, s)
  return s
}

// The Memory's photo: a three-frame original and its two tiers. The chapter's: one frame, a thumbnail only.
// With its digest (`blob-meta` `sha256`, ADR 0013 § 5): a copy's original is the same bytes, so the digest is carried as it is.
const ORIGINAL_META = { type: 'image/jpeg', name: 'IMG_0412.jpg', modifiedAt: 1_694_700_000_000, sha256: 'ungWv48Bz-pBQUDeXa4iI7ADYaOWF3qctBD_YfIAFa0' }
const m1Original = stored(1, 2 * CHUNK_SIZE + 5, M1, 'ORIGINAL', memoryKey, ORIGINAL_META)
const m1Thumb = stored(2, 900, M1, 'THUMBNAIL', memoryKey, { type: 'image/jpeg' })
const m1Preview = stored(3, 4000, M1, 'PREVIEW', memoryKey, { type: 'image/jpeg' })
const m2Original = stored(4, 700, M2, 'ORIGINAL', chapterKey, { type: 'image/png', name: 'alfama.png', modifiedAt: 5 })
const m2Thumb = stored(5, 300, M2, 'THUMBNAIL', chapterKey, { type: 'image/jpeg' })
// The Memory's uploaded cover: a COVER blob owned by the node, under the node's key, declaring no meta (as `uploadedCover` lists it).
const cover = stored(6, 1200, MEMORY, 'COVER', memoryKey)
const CROP = { x: 0.1, y: 0.2, width: 0.5, height: 0.4 }

// The facts: M1's listed tier (a corrected capture time, a place name, a field a newer client wrote) and its EXIF bundle.
const M1_TIER = {
  v: 1, w: 4000, h: 3000, capturedAt: 1_694_700_000_000, capturedAtSource: 'exif', capturedAtOverride: 1_694_703_600_000,
  gpsLat: 38.7139, gpsLng: -9.1394, placeName: 'Lisboa', placeLevel: 'city', laterField: 'kept',
}
const M1_EXIF = { v: 1, make: 'Fujifilm', model: 'X-T5', fNumber: 4 }
const seal = (purpose: string, mediaId: string, key: Uint8Array, value: unknown) =>
  b64std(v1.sealField({ purpose, scopeId: SCOPE, mediaId } as v1.FieldContext, key, new TextEncoder().encode(JSON.stringify(value))))
const open = (purpose: string, mediaId: string, key: Uint8Array, sealed: string) =>
  JSON.parse(new TextDecoder().decode(v1.openField({ purpose, scopeId: SCOPE, mediaId } as v1.FieldContext, key, fromB64std(sealed)))) as unknown

const summary = (mediaId: string, original: Stored, tiers: [string, Stored][], wrappedMeta?: string) => ({
  mediaId, ...original.ref, ownerKey: 'user:me', updatedAt: '2026-10-14T10:00:00Z', metaRevision: 3, wrappedMeta,
  derivatives: tiers.map(([kind, t]) => ({ kind, ...t.ref })),
})
const LISTINGS: Record<string, unknown> = {
  [MEMORY]: { media: [summary(M1, m1Original, [['THUMBNAIL', m1Thumb], ['PREVIEW', m1Preview]], seal('media-meta', M1, memoryKey, M1_TIER))], mediaRevision: 1, deleteRevision: 0 },
  [CHAPTER]: { media: [summary(M2, m2Original, [['THUMBNAIL', m2Thumb]])], mediaRevision: 1, deleteRevision: 0 },
}
const EXIF: Record<string, string | null> = { [M1]: seal('media-exif', M1, memoryKey, M1_EXIF), [M2]: null }

// The member's tree: Reisen/ ─ Lissabon (a Memory: date, place, uploaded cover) ─ Alfama (a chapter whose cover is an older
// client's photo reference: read as no cover, so the copy carries none).
const DATE = { mode: 'range', from: '2025-09-14', to: '2025-09-17' }
const NODES: TreeNode[] = [
  treeNode({ id: FOLDER, kind: FOLDER_KIND, name: 'Reisen', parentId: null }),
  treeNode({
    id: MEMORY, kind: MEMORY_KIND, name: 'Lissabon', parentId: FOLDER,
    wrappedMeta: sealNodeMeta(memoryKey, { scopeId: SCOPE, nodeId: MEMORY }, {
      v: 1, date: DATE, location: { name: 'Lissabon', nameSource: 'manual' },
      cover: { source: { kind: 'upload', ref: { blobId: cover.blobId, chunkCount: 1, byteSize: cover.ref.byteSize, wrappedBlobKey: cover.wrap, ownerId: MEMORY, role: 'COVER' } }, crop: CROP },
    }),
  }),
  treeNode({
    id: CHAPTER, kind: CHAPTER_KIND, name: 'Alfama', parentId: MEMORY,
    wrappedMeta: sealNodeMeta(chapterKey, { scopeId: SCOPE, nodeId: CHAPTER }, { v: 1, cover: { source: { kind: 'media', mediaId: M2 }, crop: CROP } }),
  }),
]
const keys = new Map([[FOLDER, folderKey], [MEMORY, memoryKey], [CHAPTER, chapterKey]])
const tree: TreeSource<TreeNode> = {
  rootId: null,
  node: (id) => NODES.find((n) => n.id === id) ?? null,
  children: (id) => NODES.filter((n) => n.parentId === id),
  keyOf: (id) => keys.get(id) ?? null,
  nameOf: (id) => NODES.find((n) => n.id === id)?.name ?? '',
  inIdentityFolder: () => false,
  linkTo: () => '/',
}

type Req = { method: string; host: string; path: string; json?: Record<string, unknown>; bytes?: Uint8Array }
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })

/** The control plane and the store: the originals' listings, EXIF, presigns and chunks; the copy's creates, uploads, completions, covers. */
function fakeServer() {
  const requests: Req[] = []
  const uploads = new Map<string, { blobId: string; ownerId: string; role: string; chunkCount: number; byteSize: number; wrappedBlobKey: string; wrappedBlobMeta?: string }>()
  const created: Record<string, unknown>[] = []
  let seq = 0
  const window = (uploadId: string, from: number) =>
    Array.from({ length: Math.max(0, Math.min(2, uploads.get(uploadId)!.chunkCount - from)) }, (_, k) => ({ index: from + k, url: `https://store.test/up/${uploadId}/${from + k}` }))
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = new URL(String(input), 'http://api.test')
    const method = init?.method ?? 'GET'
    if (url.host === 'store.test') {
      requests.push({ method, host: url.host, path: url.pathname, bytes: init?.body ? new Uint8Array(init.body as Uint8Array) : undefined })
      if (method === 'GET') {
        const [, , blobId, index] = url.pathname.split('/')
        return new Response(blobs.get(blobId)!.frames[Number(index)] as BodyInit, { status: 200 })
      }
      return new Response(null, { status: 200 })
    }
    const body = init?.body === undefined ? undefined : JSON.parse(String(init.body)) as Record<string, unknown>
    requests.push({ method, host: url.host, path: url.pathname + url.search, json: body })
    const path = url.pathname
    const listing = /^\/api\/scopes\/[^/]+\/nodes\/([^/]+)\/media$/.exec(path)
    if (method === 'GET' && listing) return json(LISTINGS[listing[1]])
    const exif = /^\/api\/scopes\/[^/]+\/media\/([^/]+)\/exif$/.exec(path)
    if (method === 'GET' && exif) return json({ wrappedExif: EXIF[exif[1]] })
    if (method === 'POST' && path === `/api/scopes/${SCOPE}/blob-downloads-batch`) {
      const blobId = (body!.items as { blobId: string }[])[0].blobId
      return json({ results: [{ presignedGets: blobs.get(blobId)!.frames.map((_, index) => ({ index, url: `https://store.test/orig/${blobId}/${index}` })), error: null }] })
    }
    if (method === 'POST' && path === `/api/scopes/${SCOPE}/nodes`) {
      created.push(body!)
      return json({ nodeId: body!.nodeId }, 201)
    }
    if (method === 'GET' && path === `/api/scopes/${SCOPE}/nodes`) {
      return json(created.map((c) => ({
        id: c.nodeId, parentId: c.parentId, kind: c.kind, encryptedName: c.encryptedName, slugToken: c.slugToken, orderIndex: 0, epoch: 0,
        wrappedContentKey: c.wrappedContentKey, wrappedMeta: c.wrappedMeta, metaRevision: 0, placementRevision: 0, createdAt: '2026-10-14T10:00:00Z',
      })))
    }
    if (method === 'POST' && path.endsWith('/blob-uploads-batch')) {
      const items = body!.items as { blobId: string; ownerId: string; role: string; chunkCount: number; byteSize: number; wrappedBlobKey: string }[]
      return json({ results: items.map((it) => {
        const uploadId = `00000000-0000-4000-8000-0000000000${(++seq).toString().padStart(2, '0')}`
        uploads.set(uploadId, it)
        return { uploadId, presignedPuts: window(uploadId, 0), error: null }
      }) })
    }
    if (method === 'POST' && path.endsWith('/blob-upload-windows')) {
      const items = body!.items as { uploadId: string; fromChunk: number }[]
      return json({ results: items.map((i) => ({ presignedPuts: window(i.uploadId, i.fromChunk), error: null })) })
    }
    if (method === 'POST' && path === `/api/scopes/${SCOPE}/media-batch`) {
      const items = body!.items as { mediaId: string; originalUploadId: string }[]
      return json({ results: items.map((m) => ({ mediaId: m.mediaId, blobId: uploads.get(m.originalUploadId)!.blobId, error: null })) })
    }
    if (method === 'PUT' && path.endsWith('/cover')) return new Response(null, { status: 204 })
    if (method === 'PATCH' && /\/nodes\/[^/]+$/.test(path)) return new Response(null, { status: 204 })
    throw new Error(`unexpected request ${method} ${url}`)
  }))
  return { requests, uploads }
}

/** A read of the originals: a GET (a listing, the EXIF, the tree, a chunk) or a download presign. */
const isRead = (r: Req) => r.method === 'GET' || r.path.endsWith('/blob-downloads-batch')

beforeAll(() => {
  // A node-environment suite gets no registry (`test/setup.ts`): Memories' two kinds with their real hooks, as the files suite
  // registers Depot's. That the real registration declares `importStreams` is `CopyNodesDialog.test.ts`'s (the real registry).
  for (const kind of [MEMORY_KIND, CHAPTER_KIND]) {
    if (!registry.kind(kind).supported) {
      registry.registerKind({
        kind, icon: 'images', shareable: false, contentSchema: MEMORIES_CONTENT_SCHEMA,
        exportContent: exportMemoryContent, importContent: importMemoryContent(kind), importStreams: true,
      })
    }
  }
  session.accessToken = 'access-token'
})
afterEach(() => {
  vi.unstubAllGlobals()
})

describe('a folder holding a Memory with a chapter, copied to the scope root, on the wire', () => {
  it('writes new ids, keys and ciphertext only; uploads the tiers and facts it read by chunk, decoding nothing; keeps the uploaded cover', async () => {
    const { requests, uploads } = fakeServer()
    const decode = vi.fn(() => { throw new Error('a copy decodes nothing') })
    vi.stubGlobal('createImageBitmap', decode)
    const objectUrl = vi.spyOn(URL, 'createObjectURL')
    const epochs = epochsOf(scopeKey)

    const prepared = await prepareCopy({ access: memberAccessOf({ scopeId: SCOPE, epochs }), tree, rootIds: [FOLDER], appVersion: 'test' })
    const all = [m1Original, m1Thumb, m1Preview, m2Original, m2Thumb, cover]
    expect([prepared.nodes, prepared.bytes, prepared.leftOut]).toEqual([3, all.reduce((s, b) => s + b.plain.length, 0), []])
    expect(prepared.kinds.sort()).toEqual([CHAPTER_KIND, FOLDER_KIND, MEMORY_KIND].sort())
    expect(requests.filter((r) => !isRead(r)), 'the plan writes nothing').toEqual([])

    const result = await prepared.run({ scopeId: SCOPE, epochs, tree: NODES, parent: null })
    expect(decode).not.toHaveBeenCalled()
    expect(objectUrl).not.toHaveBeenCalled()

    // --- nothing written names an original ----------------------------------------------------------------------------
    const writes = requests.filter((r) => !isRead(r))
    const originals = [FOLDER, MEMORY, CHAPTER, M1, M2, ...all.flatMap((b) => [b.blobId, b.wrap])]
    for (const w of writes) {
      const text = `${w.path} ${JSON.stringify(w.json ?? {})}`
      for (const original of originals) expect(text, `${w.method} ${w.path}`).not.toContain(original)
    }
    // …and the reads: the two listings, the two EXIF tiers, the tree once (the one cover write reads its node - the
    // chapter's photo reference is no cover), and every original blob presigned ONCE with each of its frames fetched ONCE -
    // by chunk, never whole
    expect(requests.filter(isRead).map((r) => `${r.method} ${r.path}`).sort()).toEqual([
      `GET /api/scopes/${SCOPE}/nodes/${MEMORY}/media?limit=${MEDIA_PAGE_LIMIT}`, `GET /api/scopes/${SCOPE}/nodes/${CHAPTER}/media?limit=${MEDIA_PAGE_LIMIT}`,
      `GET /api/scopes/${SCOPE}/media/${M1}/exif`, `GET /api/scopes/${SCOPE}/media/${M2}/exif`,
      `GET /api/scopes/${SCOPE}/nodes`,
      ...all.map(() => `POST /api/scopes/${SCOPE}/blob-downloads-batch`),
      ...all.flatMap((b) => b.frames.map((_, i) => `GET /orig/${b.blobId}/${i}`)),
    ].sort())
    expect(requests.filter((r) => r.path.endsWith('/blob-downloads-batch')).map((r) => (r.json!.items as { blobId: string }[])[0].blobId).sort())
      .toEqual(all.map((b) => b.blobId).sort())

    // --- the nodes: each under a fresh key, the Memory's date and place sealed at birth -------------------------------
    const creates = writes.filter((r) => r.path === `/api/scopes/${SCOPE}/nodes`).map((r) => r.json!)
    expect(creates.map((c) => c.kind)).toEqual([FOLDER_KIND, MEMORY_KIND, CHAPTER_KIND])
    const [folder, memory, chapter] = creates
    expect([folder.parentId, memory.parentId, chapter.parentId]).toEqual([undefined, result.nodeIds[0], memory.nodeId])
    const at = (nodeId: unknown) => ({ scopeId: SCOPE, nodeId: nodeId as string, epoch: 0, placementRevision: 0 })
    const keyOf = (c: Record<string, unknown>, parent: Uint8Array) => v1.open({ purpose: 'content-key', ...at(c.nodeId) }, parent, fromB64std(c.wrappedContentKey as string))
    const newFolderKey = keyOf(folder, scopeKey)
    const newMemoryKey = keyOf(memory, newFolderKey)
    const newChapterKey = keyOf(chapter, newMemoryKey)
    for (const [fresh, old] of [[newFolderKey, folderKey], [newMemoryKey, memoryKey], [newChapterKey, chapterKey]]) expect(Array.from(fresh)).not.toEqual(Array.from(old))
    expect(new TextDecoder().decode(v1.openField({ purpose: 'node-name', ...at(folder.nodeId) }, scopeKey, fromB64std(folder.encryptedName as string)))).toBe('Reisen (2)')
    const bornMeta = unsealNodeMeta(newMemoryKey, { scopeId: SCOPE, nodeId: memory.nodeId as string }, memory.wrappedMeta as string)
    expect(bornMeta.date).toEqual(DATE)
    expect((bornMeta.location as { name: string }).name).toBe('Lissabon')

    // --- the media: fresh ids, every blob under a fresh K_blob bound to the new medium, the bytes the originals' --------
    const completes = writes.filter((r) => r.path === `/api/scopes/${SCOPE}/media-batch`).map((r) => (r.json!.items as Record<string, unknown>[])[0])
    expect(completes.map((c) => c.nodeId)).toEqual([memory.nodeId, chapter.nodeId])
    const puts = writes.filter((r) => r.host === 'store.test' && r.method === 'PUT')
    /** The plaintext and declared meta of an upload the copy made, opened under `ownerKey` only. */
    const oldKeyOf = (b: Stored) => (b === m2Original || b === m2Thumb ? chapterKey : memoryKey)
    const uploaded = (uploadId: string, ownerKey: Uint8Array, old: Stored) => {
      const u = uploads.get(uploadId)!
      const wrap = fromB64std(u.wrappedBlobKey)
      const key = v1.open(blobKeyCtx(u.ownerId, u.blobId, u.role), ownerKey, wrap)
      expect(() => v1.open(blobKeyCtx(u.ownerId, u.blobId, u.role), oldKeyOf(old), wrap)).toThrow(v1.OpenRefused)
      expect(Array.from(key)).not.toEqual(Array.from(old.key))
      const frames = puts.filter((p) => p.path.startsWith(`/up/${uploadId}/`)).map((p) => [Number(p.path.split('/').pop()), p.bytes!] as const).sort(([a], [b]) => a - b)
      expect(frames).toHaveLength(u.chunkCount)
      const plain = v1.concat(...frames.map(([i, bytes]) => v1.openFrame(key, i, u.chunkCount, bytes)))
      return { u, plain, meta: u.wrappedBlobMeta ? v1.openBlobMeta(key, fromB64std(u.wrappedBlobMeta)) : null }
    }
    const [c1, c2] = completes as { mediaId: string; originalUploadId: string; derivatives: { kind: string; uploadId: string }[]; wrappedMeta: string | null; wrappedExif: string | null }[]
    expect(c1.derivatives.map((d) => d.kind)).toEqual(['THUMBNAIL', 'PREVIEW'])
    expect(c2.derivatives.map((d) => d.kind)).toEqual(['THUMBNAIL'])
    for (const [c, ownerKey, original, tiers] of [[c1, newMemoryKey, m1Original, [m1Thumb, m1Preview]], [c2, newChapterKey, m2Original, [m2Thumb]]] as const) {
      const o = uploaded(c.originalUploadId, ownerKey, original)
      expect(o.u).toMatchObject({ ownerId: c.mediaId, role: 'ORIGINAL' })
      expect(Array.from(o.plain)).toEqual(Array.from(original.plain))
      for (const [i, d] of c.derivatives.entries()) {
        const t = uploaded(d.uploadId, ownerKey, tiers[i])
        expect(t.u).toMatchObject({ ownerId: c.mediaId, role: d.kind })
        expect(Array.from(t.plain), `${d.kind} as it was`).toEqual(Array.from(tiers[i].plain))
        expect(t.meta).toEqual({ v: 1, type: 'image/jpeg' })
      }
    }
    expect(uploaded(c1.originalUploadId, newMemoryKey, m1Original).meta).toEqual({ v: 1, ...ORIGINAL_META })
    expect(uploaded(c2.originalUploadId, newChapterKey, m2Original).meta).toEqual({ v: 1, type: 'image/png', name: 'alfama.png', modifiedAt: 5 })

    // --- the facts, sealed anew bound to the new medium: the listed tier verbatim, the EXIF bundle ----------------------
    expect([typeof c1.wrappedMeta, typeof c1.wrappedExif], 'both tiers sealed').toEqual(['string', 'string'])
    expect(open('media-meta', c1.mediaId, newMemoryKey, c1.wrappedMeta!)).toEqual(M1_TIER)
    expect(() => open('media-meta', M1, newMemoryKey, c1.wrappedMeta!)).toThrow(v1.OpenRefused)
    expect(open('media-exif', c1.mediaId, newMemoryKey, c1.wrappedExif!)).toEqual(M1_EXIF)
    expect([c2.wrappedMeta, c2.wrappedExif]).toEqual([null, null])
    expect(writes.some((r) => r.path.includes('/meta')), 'no second pass: every fact was sealed at birth').toBe(false)

    // --- the covers: the Memory's uploaded image copied as its blob (no re-encode); the chapter's photo reference is none ---
    const coverPut = writes.find((r) => r.method === 'PUT' && r.path === `/api/scopes/${SCOPE}/nodes/${memory.nodeId}/cover`)!.json!
    const c = uploaded(coverPut.uploadId as string, newMemoryKey, cover)
    expect(c.u).toMatchObject({ ownerId: memory.nodeId, role: 'COVER' })
    expect(Array.from(c.plain), 'the cover bytes as they were').toEqual(Array.from(cover.plain))
    const memoryCover = unsealNodeMeta(newMemoryKey, { scopeId: SCOPE, nodeId: memory.nodeId as string }, coverPut.wrappedMeta as string).cover as { source: { kind: string; ref: { blobId: string } }; crop: unknown }
    expect([memoryCover.source.kind, memoryCover.source.ref.blobId, memoryCover.crop]).toEqual(['upload', c.u.blobId, CROP])
    const chapterMetas = writes.filter((r) => r.path.startsWith(`/api/scopes/${SCOPE}/nodes/${chapter.nodeId}`) && typeof r.json?.wrappedMeta === 'string')
    for (const r of chapterMetas) {
      expect(unsealNodeMeta(newChapterKey, { scopeId: SCOPE, nodeId: chapter.nodeId as string }, r.json!.wrappedMeta as string).cover).toBeUndefined()
    }
    expect(writes.some((r) => r.path === `/api/scopes/${SCOPE}/nodes/${chapter.nodeId}/cover`), 'no cover for the chapter').toBe(false)
  })
})
