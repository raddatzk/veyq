// A copy on the wire (uc.platform.copy-node, cr-copy-node phase 1): a folder holding a file copied to the scope root through
// core's `prepareCopy` and Depot's real hooks (`exportFile` / `importFile`, `fileUploadJob`), every request recorded at
// `fetch` - the JSON the real generated clients serialize and the frames the real pump PUTs - against a fake control plane
// and store that serve the ORIGINAL as a member's client reads it (its listing, its presigned chunks, sealed under its own
// K_blob). No module of ours is mocked. What it pins:
//   - the copy's writes carry new ids, new keys and new ciphertext only: no write request names the original folder, the
//     original file or its blob, nor carries its K_blob wrap - only the reads do (the listing, the download presign, the
//     chunk GETs);
//   - the copied folder is born under the scope's write key, the copied file under the new folder's key, and its blob under
//     a FRESH K_blob wrapped under the NEW file's key - the original's key opens none of it - while its frames decrypt to the
//     original's bytes and its sealed meta carries the original's type, name and time;
//   - the original is read frame by frame, each chunk fetched once (decrypted by chunk, never whole).
import { describe, it, expect, vi, beforeAll, afterEach } from 'vitest'
import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'
import { registry } from '../../core/applications/registry'
import { prepareCopy } from '../../core/export/copySubtree'
import { CHUNK_SIZE } from '../../core/blob/transport'
import { FOLDER_KIND } from '../../core/node/kind'
import type { TreeNode } from '../../core/node/tree'
import type { TreeSource } from '../../core/access'
import { clearUploads } from '../../core/blob/queue'
import { epochsOf } from '../spaces/epochsTestUtil'
import { fileNameSlug } from '../../shared/naming'
import { session } from '../../app/session'
import { memberAccessOf, treeNode } from '../../test/models'
import { FILE_CONTENT_ROLE, FILE_KIND } from './kinds'
import { exportFile, importFile } from './exportImport'

const SCOPE = '44444444-4444-4444-8444-444444444444'
const FOLDER = '5c09e000-0000-4000-8000-0000000000a1'
const FILE = '5c09e000-0000-4000-8000-0000000000a2'
const BLOB = '5c09e000-0000-4000-8000-0000000000b1'
const NAME = 'Bericht 2026.pdf'
const MODIFIED = 1_700_000_000_000
const scopeKey = v1.randomBytes(32)
const folderKey = v1.randomBytes(32)
const fileKey = v1.randomBytes(32)
const blobKey = v1.randomBytes(32)
const SIZE = 2 * CHUNK_SIZE + 5 // three frames
const plain = new Uint8Array(SIZE).map((_, i) => (i * 13 + 5) % 251)
const total = Math.ceil(SIZE / CHUNK_SIZE)
const frames = Array.from({ length: total }, (_, i) => v1.sealFrame(blobKey, i, total, plain.slice(i * CHUNK_SIZE, (i + 1) * CHUNK_SIZE), v1.randomBytes(12)))
const blobKeyCtx = (ownerId: string, blobId: string) => ({ purpose: 'blob-key', scopeId: SCOPE, ownerId, blobId, role: FILE_CONTENT_ROLE }) as const
const originalWrap = b64std(v1.seal(blobKeyCtx(FILE, BLOB), fileKey, blobKey))
const originalBlob = {
  blobId: BLOB, chunkCount: total, byteSize: SIZE + total * v1.FRAME_OVERHEAD, wrappedBlobKey: originalWrap,
  wrappedBlobMeta: b64std(v1.sealBlobMeta(blobKey, { type: 'application/pdf', name: NAME, modifiedAt: MODIFIED })),
}

beforeAll(() => {
  if (!registry.kind(FILE_KIND).supported) {
    registry.registerKind({
      kind: FILE_KIND, icon: 'file', shareable: false, slug: fileNameSlug, downloadsAsFile: true,
      exportContent: exportFile, importContent: importFile, importStreams: true,
    })
  }
  session.accessToken = 'access-token'
})
afterEach(() => {
  vi.unstubAllGlobals()
  clearUploads()
})

// The member's tree: Projekte/ ─ Bericht 2026.pdf, every key open.
const NODES: TreeNode[] = [
  treeNode({ id: FOLDER, kind: FOLDER_KIND, name: 'Projekte', parentId: null }),
  treeNode({ id: FILE, kind: FILE_KIND, name: NAME, parentId: FOLDER }),
]
const keys = new Map([[FOLDER, folderKey], [FILE, fileKey]])
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

/** The control plane and the store: the original's listing, presign and chunks; the copy's creates, uploads and completion. */
function fakeServer() {
  const requests: Req[] = []
  const uploads = new Map<string, { blobId: string; chunkCount: number; byteSize: number; wrappedBlobKey: string }>()
  let seq = 0
  const window = (uploadId: string, from: number) =>
    Array.from({ length: Math.max(0, Math.min(2, uploads.get(uploadId)!.chunkCount - from)) }, (_, k) => ({ index: from + k, url: `https://store.test/up/${uploadId}/${from + k}` }))
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = new URL(String(input), 'http://api.test')
    const method = init?.method ?? 'GET'
    if (url.host === 'store.test') {
      requests.push({ method, host: url.host, path: url.pathname, bytes: init?.body ? new Uint8Array(init.body as Uint8Array) : undefined })
      if (method === 'GET') return new Response(frames[Number(url.pathname.split('/').pop())] as BodyInit, { status: 200 })
      return new Response(null, { status: 200 })
    }
    const body = init?.body === undefined ? undefined : JSON.parse(String(init.body)) as Record<string, unknown>
    requests.push({ method, host: url.host, path: url.pathname + url.search, json: body })
    const path = url.pathname
    if (method === 'GET' && path === `/api/scopes/${SCOPE}/files`) {
      return json({ items: url.searchParams.get('parentId') === FOLDER ? [{ nodeId: FILE, fileBlob: originalBlob }] : [] })
    }
    if (method === 'POST' && path === `/api/scopes/${SCOPE}/blob-downloads-batch`) {
      return json({ results: [{ presignedGets: frames.map((_, index) => ({ index, url: `https://store.test/orig/${index}` })), error: null }] })
    }
    if (method === 'POST' && path === `/api/scopes/${SCOPE}/nodes`) return json({ nodeId: body!.nodeId }, 201)
    if (method === 'POST' && path.endsWith('/blob-uploads-batch')) {
      const items = body!.items as { blobId: string; chunkCount: number; byteSize: number; wrappedBlobKey: string }[]
      return json({ results: items.map((it) => {
        const uploadId = `00000000-0000-4000-8000-00000000000${++seq}`
        uploads.set(uploadId, it)
        return { uploadId, presignedPuts: window(uploadId, 0), error: null }
      }) })
    }
    if (method === 'POST' && path.endsWith('/blob-upload-windows')) {
      const items = body!.items as { uploadId: string; fromChunk: number }[]
      return json({ results: items.map((i) => ({ presignedPuts: window(i.uploadId, i.fromChunk), error: null })) })
    }
    if (method === 'POST' && /\/files\/[^/]+\/content$/.test(path)) {
      const u = uploads.get(body!.uploadId as string)!
      return json({ blobId: u.blobId, chunkCount: u.chunkCount, byteSize: u.byteSize, wrappedBlobKey: u.wrappedBlobKey })
    }
    throw new Error(`unexpected request ${method} ${url}`)
  }))
  return requests
}

/** A read of the original: a GET (the listing, a chunk) or the download presign. */
const isRead = (r: Req) => r.method === 'GET' || r.path.endsWith('/blob-downloads-batch')

describe('a folder with a file copied to the scope root, on the wire', () => {
  it('writes new ids, new keys and new ciphertext only; the reads alone name the original', async () => {
    const requests = fakeServer()
    const epochs = epochsOf(scopeKey)
    const prepared = await prepareCopy({ access: memberAccessOf({ scopeId: SCOPE, epochs }), tree, rootIds: [FOLDER], appVersion: 'test' })
    expect([prepared.nodes, prepared.bytes, prepared.leftOut]).toEqual([2, SIZE, []])
    expect(requests.filter((r) => !isRead(r)), 'the plan writes nothing').toEqual([])

    const result = await prepared.run({ scopeId: SCOPE, epochs, tree: NODES, parent: null })

    // --- nothing written names the original ---------------------------------------------------------------------------
    const writes = requests.filter((r) => !isRead(r))
    expect(writes.length).toBeGreaterThan(0)
    for (const w of writes) {
      const text = `${w.path} ${JSON.stringify(w.json ?? {})}`
      for (const original of [FOLDER, FILE, BLOB, originalWrap]) expect(text, `${w.method} ${w.path}`).not.toContain(original)
    }
    // …and the reads are of the original only: the listing of its folder, the presign of its blob, its three chunks, once each
    expect(requests.filter(isRead).map((r) => `${r.method} ${r.path}`).sort()).toEqual([
      `GET /api/scopes/${SCOPE}/files?parentId=${FOLDER}`,
      `POST /api/scopes/${SCOPE}/blob-downloads-batch`,
      ...[0, 1, 2].map((i) => `GET /orig/${i}`),
    ].sort())
    expect(requests.find((r) => r.path.endsWith('/blob-downloads-batch'))!.json).toEqual({ items: [{ blobId: BLOB }] })

    // --- the copied folder: at the root, under the scope's write key --------------------------------------------------
    const creates = writes.filter((r) => r.path === `/api/scopes/${SCOPE}/nodes`).map((r) => r.json!)
    expect(creates.map((c) => [c.kind, c.parentId])).toEqual([[FOLDER_KIND, undefined], [FILE_KIND, result.nodeIds[0]]])
    const [folder, file] = creates
    const at = (nodeId: string) => ({ scopeId: SCOPE, nodeId, epoch: 0, placementRevision: 0 })
    const newFolderKey = v1.open({ purpose: 'content-key', ...at(folder.nodeId as string) }, scopeKey, fromB64std(folder.wrappedContentKey as string))
    expect(new TextDecoder().decode(v1.openField({ purpose: 'node-name', ...at(folder.nodeId as string) }, scopeKey, fromB64std(folder.encryptedName as string)))).toBe('Projekte (2)')
    expect(Array.from(newFolderKey)).not.toEqual(Array.from(folderKey))

    // --- the copied file: under the new folder's key, a fresh node key ------------------------------------------------
    const newFileKey = v1.open({ purpose: 'content-key', ...at(file.nodeId as string) }, newFolderKey, fromB64std(file.wrappedContentKey as string))
    expect(new TextDecoder().decode(v1.openField({ purpose: 'node-name', ...at(file.nodeId as string) }, newFolderKey, fromB64std(file.encryptedName as string)))).toBe(NAME)
    expect(Array.from(newFileKey)).not.toEqual(Array.from(fileKey))

    // --- its blob: a fresh K_blob under the NEW file's key, the original's key opens nothing of it --------------------
    const [declare] = writes.find((r) => r.path.endsWith('/blob-uploads-batch'))!.json!.items as { blobId: string; ownerId: string; role: string; chunkCount: number; wrappedBlobKey: string; wrappedBlobMeta: string }[]
    expect(declare).toMatchObject({ ownerId: file.nodeId, role: FILE_CONTENT_ROLE, chunkCount: total })
    const wrap = fromB64std(declare.wrappedBlobKey)
    const newBlobKey = v1.open(blobKeyCtx(file.nodeId as string, declare.blobId), newFileKey, wrap)
    expect(() => v1.open(blobKeyCtx(file.nodeId as string, declare.blobId), fileKey, wrap)).toThrow(v1.OpenRefused)
    expect(Array.from(newBlobKey)).not.toEqual(Array.from(blobKey))
    const puts = writes.filter((r) => r.host === 'store.test' && r.method === 'PUT')
    expect(puts).toHaveLength(total)
    const opened = puts.map((r) => [Number(r.path.split('/').pop()), r.bytes!] as const).sort(([a], [b]) => a - b)
      .map(([i, bytes]) => v1.openFrame(newBlobKey, i, total, bytes))
    expect(Array.from(v1.concat(...opened))).toEqual(Array.from(plain))
    for (const [i, put] of puts.entries()) expect(() => v1.openFrame(blobKey, i, total, put.bytes!)).toThrow()
    expect(v1.openBlobMeta(newBlobKey, fromB64std(declare.wrappedBlobMeta))).toEqual({ v: 1, type: 'application/pdf', name: NAME, modifiedAt: MODIFIED })
  })
})
