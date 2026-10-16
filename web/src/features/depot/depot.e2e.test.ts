// Live e2e (VEYQ_E2E=1) for Depot's member half (cr-files-application F1b) — against a REAL backend, MariaDB and the
// bundled S3-compatible store, with REAL crypto and the real functions the section runs (no mock): a member's file is
// created, uploaded over real presigned PUTs in several frames, listed, downloaded and decrypted byte for byte (whole,
// and streamed into a save target as the section's download does — inbox #371, written but not yet run),
// replaced through the compare-and-set, refused when the replace is stale, trashed and restored through core, and
// refused to a caller without a grant; a second member of a shared scope replaces it. Run against a FRESH instance:
// `python3 scripts/run_web_e2e.py` (what CI runs) gives this file its own database.
//
//   docker compose -f infra/docker-compose.yml --profile s3 up -d
//   VEYQ_E2E=1 npx vitest run files.e2e
//
// A file's type, uploaded name and modified time are its BLOB's sealed meta (`wrappedBlobMeta` under `K_blob`, declared
// with the upload — inbox #397): the suite reads them back from the listed blob, sees the replace bring its own, and
// asserts the node's own payload is never written (`metaRevision` stays 0).
//
// The raw-wire check is two-fold, as in the neighbouring suites: every plaintext this flow sealed (the file's name, its
// MIME type, its meta) is handed to `recordCanaries`, which the orchestrator sweeps for in the database, the bucket and
// the HTTP recording (ADR 0053); and this suite itself wraps `fetch` and asserts that neither the name nor the MIME
// type appears in any request URL or body, or any textual response body, it exchanged.
//
// UNVERIFIED when written (no run from the worktree that wrote it): that a tenant member holding a delivered grant has
// WRITE on the tenant scope's files (the backend's gate for `completeFile` is WRITE).
import { describe, it, expect, beforeAll, afterAll, vi } from 'vitest'
import { become, birthInstance, mkActor, onboardMember } from '../../test/e2eBootstrap'
import { recordCanaries } from '../../test/canaries'
import { ensurePersonalScope, openScope } from '../spaces/scopes'
import { ScopeKind } from '../../api/generated/scopes'
import { createFolder, deleteNode, listTrash, listTree, nodeContentKey, restoreNode } from '../../core/node/tree'
import { CHUNK_SIZE, planBlob, reserveAndPump, authedReserve, authedWindow } from '../../core/blob/transport'
import { runGrantDeliveryWorker } from '../tasks/grantDeliveryWorker'
import { httpStatus } from '../../shared/httpError'
import { filesApi } from './api'
import { FILE_CONTENT_ROLE, FILE_KIND } from './kinds'
import { fileUploadJob, completeFileUpload, StaleFileError } from './uploadFile'
import { downloadFile, fetchFileContent, fileFacts } from './download'

const NAME = 'Quartalsbericht Ü-e2e.pdf'
const MIME = 'application/x-veyq-files-e2e'

// --- the suite's own raw-wire record -----------------------------------------------------------------------------
const wire: string[] = []
let realFetch: typeof fetch
beforeAll(() => {
  realFetch = globalThis.fetch
  globalThis.fetch = async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url
    wire.push(url)
    if (typeof init?.body === 'string') wire.push(init.body)
    const response = await realFetch(input, init)
    if (/^(application\/(json|.*\+json)|text\/)/i.test(response.headers.get('content-type') ?? '')) {
      wire.push(await response.clone().text())
    }
    return response
  }
})
afterAll(() => {
  globalThis.fetch = realFetch
})

const bytesOf = (n: number, salt: number) => new Uint8Array(n).map((_, i) => (i * 13 + salt) % 251)
const fileOf = (bytes: Uint8Array, name = NAME) => new File([bytes as BlobPart], name, { type: MIME, lastModified: 1_700_000_000_000 })
const ctx = () => ({ onProgress: () => {}, signal: new AbortController().signal })
const contentOf = async (b: Blob) => Array.from(new Uint8Array(await b.arrayBuffer()))

describe.skipIf(!process.env.VEYQ_E2E)('files e2e (live, operator-blind, direct-to-store)', () => {
  it('a member uploads, lists, downloads, replaces, trashes and restores a file; strangers are refused; a peer replaces', async () => {
    const { owner: A, tenant, salt } = await birthInstance('E2E Depot')
    await ensurePersonalScope()
    const personal = (await openScope(ScopeKind.Personal))!
    const scopeId = personal.scopeId
    const epochs = personal.epochs

    // a folder, and a multi-frame file uploaded into it with the section's own job
    const folderId = await createFolder(scopeId, epochs, 'E2E Dokumente')
    let tree = await listTree(scopeId, epochs)
    const folderKey = nodeContentKey(scopeId, epochs, tree, folderId)!
    const first = bytesOf(2 * CHUNK_SIZE + 1234, 1)
    const uploaded = await fileUploadJob({ scopeId, epochs, parent: { id: folderId, key: folderKey } }, fileOf(first)).run(ctx())
    const nodeId = uploaded.nodeId

    // the tree lists it as a FILE named as picked, its own payload untouched; listFiles pairs it with its blob, whose
    // sealed meta says what the file is and whose sealed size says how big
    tree = await listTree(scopeId, epochs)
    const fileNode = tree.find((n) => n.id === nodeId)!
    expect(fileNode).toMatchObject({ kind: FILE_KIND, parentId: folderId, name: NAME, metaRevision: 0 })
    expect(fileNode.wrappedMeta ?? null, 'no node-meta write').toBeNull()
    const nodeKey = nodeContentKey(scopeId, epochs, tree, nodeId)!
    const listed = (await filesApi().listFiles({ scopeId, parentId: folderId })).items
    expect(listed.map((i) => i.nodeId)).toEqual([nodeId])
    const blob1 = listed[0].fileBlob!
    expect(blob1.chunkCount).toBe(3)
    const facts1 = fileFacts(scopeId, nodeKey, nodeId, blob1)
    expect(facts1).toEqual({ size: first.length, meta: { v: 1, type: MIME, name: NAME, modifiedAt: 1_700_000_000_000 } })
    expect(fileFacts(scopeId, nodeKey, folderId, blob1).meta, 'a ref moved to another node opens no meta').toBeNull()

    // download: the real presigned GETs, decrypted under the node key — the original bytes
    expect(await contentOf(await fetchFileContent(scopeId, nodeKey, nodeId, blob1))).toEqual(Array.from(first))

    // the user's download streams (inbox #371): the same bytes, frame by frame, into the save target — here a picked
    // file that collects its writes — and the file is closed, not aborted
    const saved = { parts: [] as Uint8Array[], ended: '' }
    vi.stubGlobal('showSaveFilePicker', async () => ({
      createWritable: async () => ({
        write: async (b: Uint8Array) => void saved.parts.push(b.slice()),
        close: async () => void (saved.ended = 'close'),
        abort: async () => void (saved.ended = 'abort'),
      }),
    }))
    try {
      await downloadFile(scopeId, nodeKey, nodeId, blob1, NAME)
    } finally {
      vi.unstubAllGlobals()
    }
    expect(Buffer.concat(saved.parts)).toEqual(Buffer.from(first))
    expect(saved.ended).toBe('close')

    // replace with new bytes, expecting the blob read: the download returns the new bytes, the blob id moved
    const second = bytesOf(CHUNK_SIZE + 7, 2)
    const replaced = await fileUploadJob({
      scopeId, epochs, parent: { id: folderId, key: folderKey },
      replace: { nodeId, nodeKey, expectedBlobId: blob1.blobId },
    }, new File([second as BlobPart], 'ersetzt-e2e.bin', { type: `${MIME}-2`, lastModified: 1_700_000_100_000 })).run(ctx())
    expect(replaced).toEqual({ nodeId })
    const blob2 = (await filesApi().listFiles({ scopeId, parentId: folderId })).items[0].fileBlob!
    expect(blob2.blobId).not.toBe(blob1.blobId)
    // the replace brought its own meta with its bytes; the node kept its name and its (empty) payload
    expect(fileFacts(scopeId, nodeKey, nodeId, blob2)).toEqual({
      size: second.length, meta: { v: 1, type: `${MIME}-2`, name: 'ersetzt-e2e.bin', modifiedAt: 1_700_000_100_000 },
    })
    expect((await listTree(scopeId, epochs)).find((n) => n.id === nodeId)).toMatchObject({ name: NAME, metaRevision: 0 })
    expect(await contentOf(await fetchFileContent(scopeId, nodeKey, nodeId, blob2))).toEqual(Array.from(second))

    // a replace that still expects the OLD blob: 409 stale_file naming the current one, and nothing changes
    const stalePlan = planBlob(scopeId, { ownerId: nodeId, role: FILE_CONTENT_ROLE }, nodeKey, new Blob([bytesOf(10, 3) as BlobPart]))
    const [staleUpload] = await reserveAndPump([stalePlan], (d) => authedReserve(scopeId, d), authedWindow(scopeId))
    const stale = await completeFileUpload(scopeId, nodeId, staleUpload, blob1.blobId).catch((e: unknown) => e)
    expect(stale).toBeInstanceOf(StaleFileError)
    expect((stale as StaleFileError).currentBlobId).toBe(blob2.blobId)
    expect((await filesApi().listFiles({ scopeId, parentId: folderId })).items[0].fileBlob!.blobId).toBe(blob2.blobId)

    // trash through core: the file leaves the listing, the trash lists it with its kind and name; restore brings it back
    await deleteNode(scopeId, nodeId)
    expect((await filesApi().listFiles({ scopeId, parentId: folderId })).items).toEqual([])
    tree = await listTree(scopeId, epochs)
    const trashed = (await listTrash(scopeId, epochs, tree)).find((t) => t.id === nodeId)!
    expect(trashed).toMatchObject({ kind: FILE_KIND, name: NAME, parentId: folderId })
    await restoreNode(scopeId, epochs, tree, trashed)
    expect((await filesApi().listFiles({ scopeId, parentId: folderId })).items[0].fileBlob!.blobId).toBe(blob2.blobId)

    // a member without a grant on this scope cannot read its blob refs
    const M = await onboardMember(tenant.tenantId, mkActor('m'), salt)
    await become(M)
    const refused = await filesApi().listFiles({ scopeId, parentId: folderId }).catch((e: unknown) => e)
    expect([403, 404]).toContain(httpStatus(refused))

    // a shared (tenant) scope: A uploads a file, M receives a grant and replaces it with the blob M read
    await become(A)
    const shared = (await openScope(ScopeKind.Tenant))!
    const sharedUpload = await fileUploadJob({ scopeId: shared.scopeId, epochs: shared.epochs, parent: null }, fileOf(bytesOf(100, 4), 'shared-e2e.txt')).run(ctx())
    await runGrantDeliveryWorker({ scopeId: tenant.tenantId, kind: ScopeKind.Tenant, key: shared.key, epoch: shared.epochs.current })
    await become(M)
    const sharedM = (await openScope(ScopeKind.Tenant))!
    const treeM = await listTree(sharedM.scopeId, sharedM.epochs)
    const sharedNode = treeM.find((n) => n.id === sharedUpload.nodeId)!
    const sharedKeyM = nodeContentKey(sharedM.scopeId, sharedM.epochs, treeM, sharedNode.id)!
    const sharedBlob = (await filesApi().listFiles({ scopeId: sharedM.scopeId })).items.find((i) => i.nodeId === sharedNode.id)!.fileBlob!
    const third = bytesOf(500, 5)
    await fileUploadJob({
      scopeId: sharedM.scopeId, epochs: sharedM.epochs, parent: null,
      replace: { nodeId: sharedNode.id, nodeKey: sharedKeyM, expectedBlobId: sharedBlob.blobId },
    }, fileOf(third, 'shared-e2e.txt')).run(ctx())
    const sharedBlob2 = (await filesApi().listFiles({ scopeId: sharedM.scopeId })).items.find((i) => i.nodeId === sharedNode.id)!.fileBlob!
    expect(sharedBlob2.blobId).not.toBe(sharedBlob.blobId)
    expect(await contentOf(await fetchFileContent(sharedM.scopeId, sharedKeyM, sharedNode.id, sharedBlob2))).toEqual(Array.from(third))

    // the wire: neither the name nor the MIME type ever crossed it in the clear
    for (const secret of [NAME, MIME, 'shared-e2e.txt', 'ersetzt-e2e.bin']) {
      expect(wire.filter((w) => w.includes(secret)), `"${secret}" on the wire`).toEqual([])
    }
    // the canaries the orchestrator sweeps for: what the server's own responses unseal to
    recordCanaries(
      (await listTree(sharedM.scopeId, sharedM.epochs)).map((n) => n.name),
      fileFacts(sharedM.scopeId, sharedKeyM, sharedNode.id, sharedBlob2).meta,
    )
    await become(A)
    recordCanaries((await listTree(scopeId, epochs)).map((n) => n.name), facts1.meta, fileFacts(scopeId, nodeKey, nodeId, blob2).meta)
  }, 240_000)
})
