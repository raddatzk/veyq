// A file upload on the wire (cr-files-application F1b): every request one Files job sends, recorded at `fetch` — the
// JSON the real generated clients serialize and the frames the real pump PUTs — against a fake control plane and store.
// No module of ours is mocked: core's `createContentNode`, `planBlob`, `reserveAndPump`, `authedReserve`/`authedWindow`,
// Files' `completeFile` client and the meta write all run. What it pins:
//   - a NEW file: create node → start → PUTs (several windows) → `completeFile` → meta write, in that order; the node's
//     key wrap and name open under the PARENT's key (ADR 0080 Decision 1/2) and its slug token is the FILE folding's;
//     the `blob-key` wrap is bound to (scope, owner = the node, blob, `FILE_CONTENT`) and opens under the NODE's key —
//     never the parent's; the frames decrypt to the file; `completeFile` carries the upload id and no `expectedBlobId`;
//     the meta write is at version 0 and opens under the node key;
//   - a REPLACE: no create, `expectedBlobId` = the blob the member saw, the meta write at the node's version;
//   - a `409 stale_file`: the job fails with the conflict (the current blob named), writes no meta, and a rerun sends
//     nothing; a create's `slug_taken`/`illegal_structure` likewise; a failed meta write does not fail the upload; the
//     queue's retry reuses the node it created.
// The keys are recovered the way a member's client would: the node key by opening the create's wrap under the parent's.
import { describe, it, expect, vi, beforeAll, afterEach } from 'vitest'
import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'
import { fileUploadJob, StaleFileError, type FileUploadTarget } from './uploadFile'
import { FILE_KIND } from './kinds'
import { CHUNK_SIZE } from '../../core/blob/transport'
import { enqueue, clearUploads, useUploadQueue } from '../../core/blob/queue'
import { registry } from '../../core/applications/registry'
import { epochsOf } from '../spaces/epochsTestUtil'
import { fileNameSlug, slugify } from '../../shared/naming'
import { session } from '../../app/session'

const SCOPE = '33333333-3333-4333-8333-333333333333'
const FOLDER = '5c09e000-0000-4000-8000-0000000000f0'
const EXISTING = '5c09e000-0000-4000-8000-0000000000e1'
const scopeKey = v1.randomBytes(32)
const folderKey = v1.randomBytes(32)
const existingKey = v1.randomBytes(32)
const SERVER_WINDOW = 2
const NAME = 'Bericht 2026.PDF' // `fileNameSlug` keeps the space and the dot, `slugify` would not
const MODIFIED = 1_700_000_000_000

// This node-environment suite gets no application registry (`src/test/setup.ts`), so it registers FILE with its own
// folding on the app's registry, as Files' `register.ts` does.
beforeAll(() => {
  if (!registry.kind(FILE_KIND).supported) registry.registerKind({ kind: FILE_KIND, icon: 'pi pi-file', shareable: false, slug: fileNameSlug })
  session.accessToken = 'access-token'
})
afterEach(() => {
  vi.unstubAllGlobals()
  clearUploads()
})

const bytesOf = (n: number, salt: number) => new Uint8Array(n).map((_, i) => (i * 31 + salt) % 251)
const fileOf = (n: number, salt = 1) => new File([bytesOf(n, salt)], NAME, { type: 'application/pdf', lastModified: MODIFIED })

type Req = { method: string; path: string; json?: Record<string, unknown>; bytes?: Uint8Array }
type Answers = {
  create?: () => Response
  complete?: (body: Record<string, unknown>) => Response | undefined
  patch?: () => Response
  put?: (n: number) => Response | undefined
}
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })

/** The control plane and the store at once (the characterisation suite's shape): each start signs the first window,
 *  each window request the next, and `completeFile` answers the blob the started upload declared. */
function fakeServer(answers: Answers = {}) {
  const requests: Req[] = []
  const uploads = new Map<string, { blobId: string; chunkCount: number; byteSize: number; wrappedBlobKey: string }>()
  let seq = 0
  let puts = 0
  const window = (uploadId: string, from: number) =>
    Array.from({ length: Math.max(0, Math.min(SERVER_WINDOW, uploads.get(uploadId)!.chunkCount - from)) }, (_, k) => ({
      index: from + k, url: `https://store.test/b/${uploadId}/${from + k}`,
    }))
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = new URL(String(input), 'http://api.test')
    const method = init?.method ?? 'GET'
    if (url.host === 'store.test') {
      requests.push({ method, path: url.pathname, bytes: new Uint8Array(init!.body as Uint8Array) })
      return answers.put?.(++puts) ?? new Response(null, { status: 200 })
    }
    const body = init?.body === undefined ? undefined : JSON.parse(String(init.body)) as Record<string, unknown>
    requests.push({ method, path: url.pathname + url.search, json: body })
    const path = url.pathname
    if (method === 'POST' && path === `/api/scopes/${SCOPE}/nodes`) {
      return answers.create?.() ?? json({ nodeId: body!.nodeId }, 201)
    }
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
      const answered = answers.complete?.(body!)
      if (answered) return answered
      const u = uploads.get(body!.uploadId as string)!
      return json({ blobId: u.blobId, chunkCount: u.chunkCount, byteSize: u.byteSize, wrappedBlobKey: u.wrappedBlobKey })
    }
    if (method === 'PATCH' && path.startsWith(`/api/scopes/${SCOPE}/nodes/`)) return answers.patch?.() ?? new Response(null, { status: 204 })
    throw new Error(`unexpected request ${method} ${url}`)
  }))
  return requests
}

const ctx = () => ({ onProgress: vi.fn(), signal: new AbortController().signal })
const shape = (rs: Req[]) => rs.map((r) => `${r.method} ${r.path.replace(/\/b\/[^/]+\/\d+$/, '/b/…')}`)
const blobKeyCtx = (ownerId: string, blobId: string) =>
  ({ purpose: 'blob-key', scopeId: SCOPE, ownerId, blobId, role: 'FILE_CONTENT' }) as const
const newTarget = (): FileUploadTarget => ({ scopeId: SCOPE, epochs: epochsOf(scopeKey), parent: { id: FOLDER, key: folderKey } })
const replaceTarget = (expectedBlobId: string | null, metaVersion = 3): FileUploadTarget => ({
  ...newTarget(), replace: { nodeId: EXISTING, nodeKey: existingKey, meta: { wrappedMeta: null, metaVersion }, expectedBlobId },
})

describe('a new file on the wire', () => {
  it('creates the node under the parent\'s key, pumps a blob sealed under the NODE key, completes without expectedBlobId, then writes the meta', async () => {
    const requests = fakeServer()
    const size = 2 * CHUNK_SIZE + 5 // three frames, two windows
    const file = fileOf(size)

    const result = await fileUploadJob(newTarget(), file).run(ctx())

    // The control plane in order; the pump's PUTs and its look-ahead window interleave by scheduling, so they are
    // pinned by count and by place (all between the start and the completion), not by their mutual order.
    const control = shape(requests).filter((s) => !s.startsWith('PUT'))
    expect(control).toEqual([
      `POST /api/scopes/${SCOPE}/nodes`,
      `POST /api/scopes/${SCOPE}/blob-uploads-batch`,
      `POST /api/scopes/${SCOPE}/blob-upload-windows`,
      `POST /api/scopes/${SCOPE}/files/${result.nodeId}/content`,
      `PATCH /api/scopes/${SCOPE}/nodes/${result.nodeId}`,
    ])
    const putAt = shape(requests).flatMap((s, i) => (s.startsWith('PUT') ? [i] : []))
    expect(putAt).toHaveLength(3)
    expect(Math.min(...putAt)).toBeGreaterThan(1)
    expect(Math.max(...putAt)).toBeLessThan(shape(requests).indexOf(control[3]))
    expect(result.metaWritten).toBe(true)

    // the node: kind FILE under the folder, its key wrap and name under the PARENT's key at version 0, the FILE folding
    const create = requests[0].json!
    expect(create).toMatchObject({ nodeId: result.nodeId, parentId: FOLDER, kind: FILE_KIND, epoch: 0 })
    const address = { scopeId: SCOPE, nodeId: result.nodeId, epoch: 0, placementVersion: 0 }
    const nodeKey = v1.open({ purpose: 'content-key', ...address }, folderKey, fromB64std(create.wrappedContentKey as string))
    expect(() => v1.open({ purpose: 'content-key', ...address }, scopeKey, fromB64std(create.wrappedContentKey as string))).toThrow(v1.OpenRefused)
    expect(new TextDecoder().decode(v1.open({ purpose: 'node-name', ...address }, folderKey, fromB64std(create.encryptedName as string)))).toBe(NAME)
    expect(create.slugToken).toBe(b64std(v1.slugToken(folderKey, FOLDER, fileNameSlug(NAME))))
    expect(create.slugToken).not.toBe(b64std(v1.slugToken(folderKey, FOLDER, slugify(NAME))))

    // the blob: owned by the node in the role FILE_CONTENT, its K_blob wrap opening under the node's key only
    const [start] = requests[1].json!.items as { blobId: string; ownerId: string; role: string; chunkCount: number; byteSize: number; wrappedBlobKey: string }[]
    expect(start).toMatchObject({ ownerId: result.nodeId, role: 'FILE_CONTENT', chunkCount: 3, byteSize: size + 3 * v1.FRAME_OVERHEAD })
    const wrap = fromB64std(start.wrappedBlobKey)
    const blobKey = v1.open(blobKeyCtx(result.nodeId, start.blobId), nodeKey, wrap)
    expect(() => v1.open(blobKeyCtx(result.nodeId, start.blobId), folderKey, wrap)).toThrow(v1.OpenRefused)
    expect(() => v1.open(blobKeyCtx(FOLDER, start.blobId), nodeKey, wrap)).toThrow(v1.OpenRefused)
    expect(() => v1.open({ ...blobKeyCtx(result.nodeId, start.blobId), role: 'ORIGINAL' }, nodeKey, wrap)).toThrow(v1.OpenRefused)
    const frames = requests.filter((r) => r.method === 'PUT').map((r, i, all) => v1.openFrame(blobKey, i, all.length, r.bytes!))
    expect(Array.from(v1.concat(...frames))).toEqual(Array.from(bytesOf(size, 1)))

    // the completion: the started upload, no expectedBlobId at all (an empty slot)
    expect(requests.find((r) => r.path.endsWith('/content'))!.json).toEqual({ uploadId: '00000000-0000-4000-8000-000000000001' })

    // the meta: at version 0, under the node's key with the node-meta seal
    const patch = requests.find((r) => r.method === 'PATCH')!.json!
    expect(Object.keys(patch).sort()).toEqual(['metaVersion', 'wrappedMeta'])
    expect(patch.metaVersion).toBe(0)
    const meta = JSON.parse(new TextDecoder().decode(
      v1.open({ purpose: 'node-meta', scopeId: SCOPE, nodeId: result.nodeId }, nodeKey, fromB64std(patch.wrappedMeta as string)),
    ))
    expect(meta).toEqual({ v: 1, mime: 'application/pdf', size, modifiedAt: MODIFIED })
  })

  it('at the scope root the node wraps under the write key E_current', async () => {
    const requests = fakeServer()
    const { nodeId } = await fileUploadJob({ scopeId: SCOPE, epochs: epochsOf(scopeKey), parent: null }, fileOf(10)).run(ctx())
    const create = requests[0].json!
    expect(create.parentId).toBeUndefined()
    const address = { scopeId: SCOPE, nodeId, epoch: 0, placementVersion: 0 }
    expect(() => v1.open({ purpose: 'content-key', ...address }, scopeKey, fromB64std(create.wrappedContentKey as string))).not.toThrow()
    expect(create.slugToken).toBe(b64std(v1.slugToken(scopeKey, SCOPE, fileNameSlug(NAME))))
  })
})

describe('a replace on the wire', () => {
  it('creates nothing, completes with expectedBlobId = the blob the member saw, writes the meta at the node\'s version', async () => {
    const requests = fakeServer()
    const result = await fileUploadJob(replaceTarget('9b1d4a6e-8b2c-1f2d-3e4a-5b6c7c3f0e2a'), fileOf(100)).run(ctx())

    expect(result.nodeId).toBe(EXISTING)
    expect(shape(requests)).toEqual([
      `POST /api/scopes/${SCOPE}/blob-uploads-batch`,
      'PUT /b/…',
      `POST /api/scopes/${SCOPE}/files/${EXISTING}/content`,
      `PATCH /api/scopes/${SCOPE}/nodes/${EXISTING}`,
    ])
    const [start] = requests[0].json!.items as { blobId: string; ownerId: string; wrappedBlobKey: string }[]
    expect(start.ownerId).toBe(EXISTING)
    expect(() => v1.open(blobKeyCtx(EXISTING, start.blobId), existingKey, fromB64std(start.wrappedBlobKey))).not.toThrow()
    expect(requests[2].json).toEqual({ uploadId: '00000000-0000-4000-8000-000000000001', expectedBlobId: '9b1d4a6e-8b2c-1f2d-3e4a-5b6c7c3f0e2a' })
    expect(requests[3].json!.metaVersion).toBe(3)
  })

  it('a node that holds no blob yet is filled like a new one: no expectedBlobId', async () => {
    const requests = fakeServer()
    await fileUploadJob(replaceTarget(null), fileOf(100)).run(ctx())
    expect(requests[2].json).toEqual({ uploadId: '00000000-0000-4000-8000-000000000001' })
  })

  it('a 409 stale_file fails with the conflict and the current blob, writes no meta, and a rerun sends nothing', async () => {
    const requests = fakeServer({
      complete: () => json({ code: 'stale_file', currentBlobId: '8b2c1f2d-3e4a-5b6c-7c3f-0e2a9b1d4a6e' }, 409),
    })
    const job = fileUploadJob(replaceTarget('9b1d4a6e-8b2c-1f2d-3e4a-5b6c7c3f0e2a'), fileOf(100))

    const failure = await job.run(ctx()).catch((e: unknown) => e)
    expect(failure).toBeInstanceOf(StaleFileError)
    expect((failure as StaleFileError).message).toBe('stale_file')
    expect((failure as StaleFileError).currentBlobId).toBe('8b2c1f2d-3e4a-5b6c-7c3f-0e2a9b1d4a6e')
    expect(requests.some((r) => r.method === 'PATCH')).toBe(false)

    const sent = requests.length
    await expect(job.run(ctx())).rejects.toThrow('stale_file')
    expect(requests.length, 'no second upload for a conflict no retry can change').toBe(sent)
  })

  it('through the core queue, the conflict settles the item failed with stale_file after the one automatic retry', async () => {
    fakeServer({ complete: () => json({ code: 'stale_file', currentBlobId: null }, 409) })
    enqueue([fileUploadJob(replaceTarget('9b1d4a6e-8b2c-1f2d-3e4a-5b6c7c3f0e2a'), fileOf(100))], { label: 'Docs', key: 'files:test' })
    const { batches } = useUploadQueue()
    await vi.waitFor(() => expect(batches.value[0].finishedAt).not.toBeNull())
    expect(batches.value[0].files[0]).toMatchObject({ status: 'failed', error: 'stale_file', attempts: 2 })
  })
})

describe('a file upload\'s other outcomes', () => {
  it('a create refused as slug_taken or illegal_structure ends there: no blob started, and a rerun sends nothing', async () => {
    for (const [answer, code] of [
      [json({ code: 'slug_taken' }, 409), 'slug_taken'],
      [json({ code: 'illegal_structure' }, 422), 'illegal_structure'],
    ] as const) {
      const requests = fakeServer({ create: () => answer.clone() })
      const job = fileUploadJob(newTarget(), fileOf(10))
      await expect(job.run(ctx())).rejects.toThrow(code)
      await expect(job.run(ctx())).rejects.toThrow(code)
      expect(shape(requests)).toEqual([`POST /api/scopes/${SCOPE}/nodes`])
      vi.unstubAllGlobals()
    }
  })

  it('a failed meta write leaves the upload done, saying the meta is missing', async () => {
    fakeServer({ patch: () => new Response(null, { status: 500 }) })
    const result = await fileUploadJob(newTarget(), fileOf(10)).run(ctx())
    expect(result.metaWritten).toBe(false)
  })

  it('the retry after a transient failure reuses the node it created: one create, a second upload into it', async () => {
    const requests = fakeServer({ put: (n) => (n === 1 ? new Response(null, { status: 500 }) : undefined) })
    const job = fileUploadJob(newTarget(), fileOf(10))
    await expect(job.run(ctx())).rejects.toThrow('chunk_put_failed:500')
    const { nodeId } = await job.run(ctx())
    expect(requests.filter((r) => r.path === `/api/scopes/${SCOPE}/nodes`)).toHaveLength(1)
    const starts = requests.filter((r) => r.path.endsWith('/blob-uploads-batch'))
    expect(starts.map((s) => (s.json!.items as { ownerId: string }[])[0].ownerId)).toEqual([nodeId, nodeId])
    expect(requests.find((r) => r.path.endsWith('/content'))!.json).toEqual({ uploadId: '00000000-0000-4000-8000-000000000002' })
  })
})
