// A guest's file upload on the wire (cr-files-application F2): every request one guest job sends, recorded at `fetch`,
// against a fake control plane and store. No module of ours is mocked: core's `createShareNodeAsGuest`, `planBlob`,
// `reserveAndPump`, `guestReserve`/`guestWindow` and Depot's `completeShareFile` client all run. What it pins:
//   - a NEW file: guest create → guest start → PUTs → `completeShareFile`, every call to the link's paths with the
//     guest's token and the password token, the upload's target the NEW node; the node's key wrap opens under the
//     FOLDER's key, the `blob-key` wrap under the NODE's key (owner = the node, role `FILE_CONTENT`), the meta on the
//     declare under the blob's own K_blob describing the picked file (inbox #397), and the completion carrying ids only
//     — no body names the file or its type in plaintext;
//   - a REPLACE: no create, `expectedBlobId` = the blob the guest saw, the new file's meta on the new blob;
//   - a `409 stale_file`: `StaleFileError` with the current blob, and a rerun sends nothing.
import { describe, it, expect, vi, beforeAll, afterEach } from 'vitest'
import * as v1 from '@veyq/crypto/v1'
import { fromB64std } from '@veyq/crypto/base64'
import { guestFileUploadJob } from './shareUpload'
import { StaleFileError } from './uploadFile'
import { FILE_KIND } from './kinds'
import { FOLDER_KIND } from '../../core/node/kind'
import { registry } from '../../core/applications/registry'
import type { GuestTreeContext } from '../../core/sharing/guestTree'
import type { ShareNode } from '../../api/generated/sharing'
import { fileNameSlug } from '../../shared/naming'

const SCOPE = '33333333-3333-4333-8333-333333333334'
const SHARE = 'Sh4reIdSh4reIdSh4reIdSh4reIdSh4reIdSh4re-_4'
const ROOT = '5c09e000-0000-4000-8000-0000000000a0'
const EXISTING = '5c09e000-0000-4000-8000-0000000000a1'
const rootKey = v1.randomBytes(32)
const existingKey = v1.randomBytes(32)
const TOKEN = 'tok-guest'
const PW = 'cGFzc3dvcmQtdG9rZW4='
const NAME = 'Rezept.pdf'
const MODIFIED = 1_700_000_000_000

beforeAll(() => {
  if (!registry.kind(FILE_KIND).supported) registry.registerKind({ kind: FILE_KIND, icon: 'pi pi-file', shareable: false, slug: fileNameSlug })
})
afterEach(() => vi.unstubAllGlobals())

const fileOf = (n: number) => new File([new Uint8Array(n).map((_, i) => i % 251)], NAME, { type: 'application/pdf', lastModified: MODIFIED })

function context(): GuestTreeContext {
  const root: ShareNode = { id: ROOT, parentId: null, kind: FOLDER_KIND, epoch: 2 }
  const existing: ShareNode = { id: EXISTING, parentId: ROOT, kind: FILE_KIND, epoch: 2, placementVersion: 0 }
  return {
    head: {} as GuestTreeContext['head'],
    rootId: ROOT,
    keys: new Map([[ROOT, rootKey], [EXISTING, existingKey]]),
    names: new Map([[EXISTING, 'Alt.txt']]),
    nodes: new Map([[ROOT, root], [EXISTING, existing]]),
    shareId: SHARE, scopeId: SCOPE, pwToken: PW, contributorToken: TOKEN,
    reresolve: async () => { throw new Error('not in this test') },
  }
}

type Req = { method: string; path: string; headers: Record<string, string>; json?: Record<string, unknown> }
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })

function fakeServer(complete?: (body: Record<string, unknown>) => Response | undefined) {
  const requests: Req[] = []
  const uploads = new Map<string, { blobId: string; chunkCount: number; byteSize: number; wrappedBlobKey: string }>()
  let seq = 0
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = new URL(String(input), 'http://api.test')
    const method = init?.method ?? 'GET'
    if (url.host === 'store.test') return new Response(null, { status: 200 })
    const headers = Object.fromEntries(new Headers(init?.headers).entries())
    const body = init?.body === undefined ? undefined : JSON.parse(String(init.body)) as Record<string, unknown>
    requests.push({ method, path: url.pathname, headers, json: body })
    const path = url.pathname
    if (method === 'POST' && path === `/api/shares/${SHARE}/nodes`) return json({ nodeId: body!.nodeId }, 201)
    if (method === 'POST' && path === `/api/shares/${SHARE}/blob-uploads`) {
      const items = body!.items as { blobId: string; chunkCount: number; byteSize: number; wrappedBlobKey: string }[]
      return json({ results: items.map((it) => {
        const uploadId = `00000000-0000-4000-8000-00000000010${++seq}`
        uploads.set(uploadId, it)
        return { uploadId, presignedPuts: Array.from({ length: it.chunkCount }, (_, i) => ({ index: i, url: `https://store.test/${uploadId}/${i}` })), error: null }
      }) })
    }
    if (method === 'POST' && /^\/api\/shares\/[^/]+\/files\/[^/]+\/content$/.test(path)) {
      const answered = complete?.(body!)
      if (answered) return answered
      const u = uploads.get(body!.uploadId as string)!
      return json({ blobId: u.blobId, chunkCount: u.chunkCount, byteSize: u.byteSize, wrappedBlobKey: u.wrappedBlobKey })
    }
    throw new Error(`unexpected request ${method} ${url}`)
  }))
  return requests
}

const ctx = () => ({ onProgress: vi.fn(), signal: new AbortController().signal })
const control = (rs: Req[]) => rs.map((r) => `${r.method} ${r.path}`)
/** The declared blob of a start, its meta opened under the K_blob its wrap unwraps to under `nodeKey`. */
const declaredMeta = (start: Record<string, unknown>, nodeId: string, nodeKey: Uint8Array) => {
  const [item] = start.items as { blobId: string; wrappedBlobKey: string; wrappedBlobMeta: string }[]
  const blobKey = v1.open(
    { purpose: 'blob-key', scopeId: SCOPE, ownerId: nodeId, blobId: item.blobId, role: 'FILE_CONTENT' }, nodeKey, fromB64std(item.wrappedBlobKey),
  )
  return v1.openBlobMeta(blobKey, fromB64std(item.wrappedBlobMeta))
}
const expectNoPlaintextName = (rs: Req[], name: string) => {
  for (const r of rs) {
    expect(JSON.stringify(r.json ?? {}), `${r.method} ${r.path}`).not.toContain(name)
    expect(JSON.stringify(r.json ?? {}), `${r.method} ${r.path}`).not.toContain('application/pdf')
  }
}

describe("a guest's new file on the wire", () => {
  it('creates the FILE under the folder, uploads into THAT node with the meta on the declare — the link\'s token and password on every call', async () => {
    const requests = fakeServer()
    const result = await guestFileUploadJob({ ctx: context(), parentId: ROOT }, fileOf(40)).run(ctx())
    const node = result.nodeId

    expect(control(requests)).toEqual([
      `POST /api/shares/${SHARE}/nodes`,
      `POST /api/shares/${SHARE}/blob-uploads`,
      `POST /api/shares/${SHARE}/files/${node}/content`,
    ])
    expect(result).toEqual({ nodeId: node })
    for (const r of requests) expect(r.headers['x-share-password'], `${r.path} carries the password token`).toBe(PW)
    expect(requests[0].headers['x-contributor-token']).toBe(TOKEN)
    expect(requests[1].json!.contributorToken).toBe(TOKEN)
    expect(requests[2].json!.contributorToken).toBe(TOKEN)

    // The node: kind FILE under the folder, its key wrapped under the FOLDER's key at the folder's epoch.
    const created = requests[0].json!
    expect(created).toMatchObject({ parentId: ROOT, kind: FILE_KIND, epoch: 2 })
    const nodeKey = v1.open(
      { purpose: 'content-key', scopeId: SCOPE, nodeId: node, epoch: 2, placementVersion: 0 }, rootKey, fromB64std(created.wrappedContentKey as string),
    )

    // The upload: target = the new node, role FILE_CONTENT, owner = the node, `K_blob` under the NODE's key.
    const start = requests[1].json!
    expect(start.nodeId).toBe(node)
    const [item] = start.items as { blobId: string; ownerId: string; role: string; wrappedBlobKey: string }[]
    expect(item).toMatchObject({ ownerId: node, role: 'FILE_CONTENT' })
    const blobCtx = { purpose: 'blob-key', scopeId: SCOPE, ownerId: node, blobId: item.blobId, role: 'FILE_CONTENT' } as const
    expect(() => v1.open(blobCtx, nodeKey, fromB64std(item.wrappedBlobKey))).not.toThrow()
    expect(() => v1.open(blobCtx, rootKey, fromB64std(item.wrappedBlobKey)), 'never under the folder key').toThrow()

    // The meta: the blob's own, declared with it, describing the picked file; the completion: ids only.
    expect(declaredMeta(start, node, nodeKey)).toEqual({ v: 1, type: 'application/pdf', name: NAME, modifiedAt: MODIFIED })
    const done = requests[2].json!
    expect(Object.keys(done).sort()).toEqual(['contributorToken', 'uploadId'])
    expectNoPlaintextName(requests, NAME)
  })

  it('declares its final refusals to the queue, and nothing else', () => {
    const job = guestFileUploadJob({ ctx: context(), parentId: ROOT }, new File(['x'], 'a.txt'))
    for (const code of ['slug_taken', 'illegal_structure', 'stale_file']) expect(job.isFinal?.(code), code).toBe(true)
    expect(job.isFinal?.('chunk_put_failed:500')).toBe(false)
  })

  it('a name a readable sibling folds to is refused before anything is sent (slug_taken)', async () => {
    const requests = fakeServer()
    const job = guestFileUploadJob({ ctx: context(), parentId: ROOT }, new File(['x'], 'alt.TXT'))
    await expect(job.run(ctx())).rejects.toThrow('slug_taken')
    expect(requests).toEqual([])
  })
})

describe("a guest's replace on the wire", () => {
  it('sends no create, expects the blob it saw, and declares the NEW file\'s meta on the new blob', async () => {
    const requests = fakeServer()
    const target = { ctx: context(), parentId: ROOT, replace: { nodeId: EXISTING, nodeKey: existingKey, expectedBlobId: 'b0b0b0b0-0000-4000-8000-000000000001' } }
    await guestFileUploadJob(target, fileOf(7)).run(ctx())

    expect(control(requests)).toEqual([`POST /api/shares/${SHARE}/blob-uploads`, `POST /api/shares/${SHARE}/files/${EXISTING}/content`])
    expect(declaredMeta(requests[0].json!, EXISTING, existingKey)).toEqual({ v: 1, type: 'application/pdf', name: NAME, modifiedAt: MODIFIED })
    const done = requests[1].json!
    expect(done.expectedBlobId).toBe('b0b0b0b0-0000-4000-8000-000000000001')
    expect('wrappedMeta' in done).toBe(false)
  })

  it('a 409 stale_file is StaleFileError with the current blob, and a rerun sends nothing', async () => {
    const requests = fakeServer(() => json({ code: 'stale_file', currentBlobId: 'c0c0c0c0-0000-4000-8000-000000000002' }, 409))
    const target = { ctx: context(), parentId: ROOT, replace: { nodeId: EXISTING, nodeKey: existingKey, expectedBlobId: null } }
    const job = guestFileUploadJob(target, fileOf(7))
    const err = await job.run(ctx()).catch((e: unknown) => e)
    expect(err).toBeInstanceOf(StaleFileError)
    expect((err as StaleFileError).currentBlobId).toBe('c0c0c0c0-0000-4000-8000-000000000002')
    const sent = requests.length
    await expect(job.run(ctx())).rejects.toBeInstanceOf(StaleFileError)
    expect(requests).toHaveLength(sent)
  })
})

