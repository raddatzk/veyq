// Live e2e (VEYQ_E2E=1) for Files on a shared folder (cr-files-application F2) — against a REAL backend, MariaDB and the
// bundled S3-compatible store, with REAL crypto and the functions the share page runs (no mock):
//   - a READ link: the guest lists the folder's files (`listShareFiles`) and downloads the member's file through the
//     link (`resolveShareBlob`), decrypted under the key from the chain, byte for byte; a write on it is refused;
//   - a READ_WRITE link: a named guest creates a FILE with core's `createShareNode` and uploads into it (the share
//     section's own job, `guestFileUploadJob`), its meta sealed into the blob it uploads (`wrappedBlobMeta`, inbox #397);
//     the member lists it, reads its name, its blob's meta and its bytes, and the node's own payload is never written;
//     the guest replaces the member's file (compare-and-set), the new blob bringing its own meta; a stale replace is
//     refused;
//   - a file the member trashed is gone for the guest: not listed, its blob `404`, an upload into it `404`.
// Run against a FRESH instance: `python3 scripts/run_web_e2e.py web/src/features/files/filesShare.e2e.test.ts`.
//
// The raw-wire check as in `files.e2e.test.ts`: this suite wraps `fetch` and asserts that no file name or MIME type
// crossed it in the clear, and hands the plaintexts it sealed to `recordCanaries` (ADR 0053).
//
// UNVERIFIED when written: run — the worktree that wrote it may not run the orchestrator (the main checkout does).
import { describe, it, expect, beforeAll, afterAll } from 'vitest'
import { b64url } from '@veyq/crypto/base64'
import { birthInstance } from '../../test/e2eBootstrap'
import { recordCanaries } from '../../test/canaries'
import { ensurePersonalScope, openScope } from '../spaces/scopes'
import { ScopeKind } from '../../api/generated/scopes'
import { ShareRole } from '../../api/generated/sharing'
import { createFolder, deleteNode, listTree, nodeContentKey } from '../../core/node/tree'
import { CHUNK_SIZE } from '../../core/blob/transport'
import { registry } from '../../core/applications/registry'
import { mintShare } from '../../core/sharing/manageShares'
import { resolvePublicShare } from '../../core/sharing/resolvePublicShare'
import { createGuestIdentity } from '../../core/sharing/guestIdentity'
import { guestTreeContext, openGuestTreeParts } from '../../core/sharing/guestTree'
import { guestReserve, guestWindow, shareChunkUrls } from '../../core/sharing/guestBlob'
import { reserveAndPump, planBlob } from '../../core/blob/transport'
import { decodeHttpError, httpStatus } from '../../shared/httpError'
import { fileNameSlug } from '../../shared/naming'
import { filesApi, publicShareFilesApi } from './api'
import { FILE_CONTENT_ROLE, FILE_KIND } from './kinds'
import { fileUploadJob, StaleFileError } from './uploadFile'
import { guestFileUploadJob } from './shareUpload'
import { fetchFileContent, fileFacts } from './download'

const FOLDER_NAME = 'Vereinsablage Kranichfeld'
const MEMBER_FILE = 'Satzung Kranichfeld 1987.pdf'
const GUEST_FILE = 'Protokoll Jahresversammlung Ü.txt'
const GUEST_NAME = 'Schriftführerin Adelgunde'
const MIME = 'application/x-veyq-files-share-e2e'

const wire: string[] = []
let realFetch: typeof fetch
beforeAll(() => {
  // A node-environment suite gets no application registry (`src/test/setup.ts`): FILE with its own folding, as
  // Files' `register.ts` registers it, so the guest's slug is the one the page computes.
  if (!registry.kind(FILE_KIND).supported) registry.registerKind({ kind: FILE_KIND, icon: 'pi pi-file', shareable: false, slug: fileNameSlug })
  realFetch = globalThis.fetch
  globalThis.fetch = async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url
    wire.push(url)
    if (typeof init?.body === 'string') wire.push(init.body)
    const response = await realFetch(input, init)
    if (/^(application\/(json|.*\+json)|text\/)/i.test(response.headers.get('content-type') ?? '')) wire.push(await response.clone().text())
    return response
  }
})
afterAll(() => {
  globalThis.fetch = realFetch
})

const bytesOf = (n: number, salt: number) => new Uint8Array(n).map((_, i) => (i * 17 + salt) % 251)
const fileOf = (bytes: Uint8Array, name: string) => new File([bytes as BlobPart], name, { type: MIME, lastModified: 1_700_000_000_000 })
const ctx = () => ({ onProgress: () => {}, signal: new AbortController().signal })
const contentOf = async (b: Blob) => Array.from(new Uint8Array(await b.arrayBuffer()))
const failure = (p: Promise<unknown>): Promise<unknown> => p.then(() => null, (e: unknown) => e)

describe.skipIf(!process.env.VEYQ_E2E)('files share e2e (live, real crypto, a folder link with files)', () => {
  it('a READ guest downloads, a READ_WRITE guest uploads and replaces, the member reads it, a trashed file is 404', async () => {
    // --- the member: a folder holding a two-frame file -------------------------------------------------------------
    await birthInstance('E2E Files Share')
    await ensurePersonalScope()
    const personal = (await openScope(ScopeKind.Personal))!
    const { scopeId, epochs } = personal
    const folderId = await createFolder(scopeId, epochs, FOLDER_NAME)
    const folderKey = nodeContentKey(scopeId, epochs, await listTree(scopeId, epochs), folderId)!
    const memberBytes = bytesOf(CHUNK_SIZE + 321, 1)
    const memberFile = (await fileUploadJob({ scopeId, epochs, parent: { id: folderId, key: folderKey } }, fileOf(memberBytes, MEMBER_FILE)).run(ctx())).nodeId

    // --- a READ link: the guest lists and downloads through the link ----------------------------------------------
    const read = await mintShare(scopeId, folderId, folderKey, epochs, FOLDER_NAME, ShareRole.Read)
    const readHead = await resolvePublicShare(read.shareId, b64url(read.kShare))
    const readParts = openGuestTreeParts(readHead)
    expect(readParts.names.get(memberFile)).toBe(MEMBER_FILE)
    const readListing = (await publicShareFilesApi().listShareFiles({ shareId: read.shareId })).items
    expect(readListing.map((i) => i.nodeId)).toEqual([memberFile])
    const memberBlob = readListing[0].fileBlob!
    const guestRead = await fetchFileContent(readHead.scopeId, readParts.keys.get(memberFile)!, memberFile, memberBlob, (b) => shareChunkUrls(read.shareId, b))
    expect(await contentOf(guestRead)).toEqual(Array.from(memberBytes))

    // --- a READ_WRITE link: a named guest creates a file and uploads into it ---------------------------------------
    const rw = await mintShare(scopeId, folderId, folderKey, epochs, FOLDER_NAME, ShareRole.ReadWrite)
    const rwFragment = b64url(rw.kShare)
    let rwHead = await resolvePublicShare(rw.shareId, rwFragment)
    const guest = await createGuestIdentity(rwHead, openGuestTreeParts(rwHead).keys.get(folderId)!, { name: GUEST_NAME, fragmentKey: rwFragment })
    const guestCtx = () => guestTreeContext(rwHead, { contributorToken: guest.contributorToken, fragmentKey: rwFragment })
    const guestBytes = bytesOf(2 * CHUNK_SIZE + 99, 2)
    const created = await guestFileUploadJob({ ctx: guestCtx(), parentId: folderId }, fileOf(guestBytes, GUEST_FILE)).run(ctx())
    const guestFile = created.nodeId

    // a READ link refuses the same write: its token names no identity there, and its role takes none
    const readWrite = await failure(publicShareFilesApi().completeShareFile({
      shareId: read.shareId, nodeId: guestFile, shareFileCompleteRequest: { contributorToken: guest.contributorToken, uploadId: crypto.randomUUID() },
    }))
    expect(httpStatus(readWrite)).toBe(403)

    // the member sees the guest's file: its name, its blob's meta (declared with the upload), its bytes
    const tree = await listTree(scopeId, epochs)
    const guestNode = tree.find((n) => n.id === guestFile)!
    expect(guestNode).toMatchObject({ kind: FILE_KIND, parentId: folderId, name: GUEST_FILE, metaVersion: 0 })
    const guestKey = nodeContentKey(scopeId, epochs, tree, guestFile)!
    const memberListing = (await filesApi().listFiles({ scopeId, parentId: folderId })).items
    const guestBlob = memberListing.find((i) => i.nodeId === guestFile)!.fileBlob!
    expect(fileFacts(scopeId, guestKey, guestFile, guestBlob)).toEqual({
      size: guestBytes.length, meta: { v: 1, type: MIME, name: GUEST_FILE, modifiedAt: 1_700_000_000_000 },
    })
    expect(await contentOf(await fetchFileContent(scopeId, guestKey, guestFile, guestBlob))).toEqual(Array.from(guestBytes))

    // --- the guest replaces the member's file with the blob it read; a replace still expecting it is stale ---------
    rwHead = await resolvePublicShare(rw.shareId, rwFragment)
    const rwKeys = openGuestTreeParts(rwHead).keys
    const replacement = bytesOf(777, 3)
    await guestFileUploadJob({
      ctx: guestCtx(), parentId: folderId,
      replace: { nodeId: memberFile, nodeKey: rwKeys.get(memberFile)!, expectedBlobId: memberBlob.blobId },
    }, fileOf(replacement, MEMBER_FILE)).run(ctx())
    const replacedBlob = (await filesApi().listFiles({ scopeId, parentId: folderId })).items.find((i) => i.nodeId === memberFile)!.fileBlob!
    expect(replacedBlob.blobId).not.toBe(memberBlob.blobId)
    const memberKey = nodeContentKey(scopeId, epochs, await listTree(scopeId, epochs), memberFile)!
    expect(await contentOf(await fetchFileContent(scopeId, memberKey, memberFile, replacedBlob))).toEqual(Array.from(replacement))
    expect(fileFacts(scopeId, memberKey, memberFile, replacedBlob).size).toBe(replacement.length)
    // a guest of the link reads the same meta through the chain's key, from the link's listing
    const guestListed = (await publicShareFilesApi().listShareFiles({ shareId: rw.shareId })).items.find((i) => i.nodeId === memberFile)!.fileBlob!
    expect(fileFacts(scopeId, rwKeys.get(memberFile)!, memberFile, guestListed).meta).toMatchObject({ type: MIME, name: MEMBER_FILE })
    const stale = await failure(guestFileUploadJob({
      ctx: guestCtx(), parentId: folderId,
      replace: { nodeId: memberFile, nodeKey: rwKeys.get(memberFile)!, expectedBlobId: memberBlob.blobId },
    }, fileOf(bytesOf(5, 4), MEMBER_FILE)).run(ctx()))
    expect(stale).toBeInstanceOf(StaleFileError)
    expect((stale as StaleFileError).currentBlobId).toBe(replacedBlob.blobId)

    // --- the member trashes the guest's file: for the guest it is gone ---------------------------------------------
    await deleteNode(scopeId, guestFile)
    expect((await publicShareFilesApi().listShareFiles({ shareId: rw.shareId })).items.map((i) => i.nodeId)).toEqual([memberFile])
    expect(httpStatus(await failure(shareChunkUrls(rw.shareId, guestBlob.blobId)))).toBe(404)
    const to = { shareId: rw.shareId, contributorToken: guest.contributorToken, nodeId: guestFile }
    const intoTrash = await failure(reserveAndPump(
      [planBlob(scopeId, { ownerId: guestFile, role: FILE_CONTENT_ROLE }, guestKey, new Blob([bytesOf(10, 5) as BlobPart]))],
      (d) => guestReserve(to, d), guestWindow(to),
    ))
    expect(await decodeHttpError(intoTrash)).toEqual({ status: 404, code: 'node_not_found' })

    // --- the wire: no name, no MIME type in the clear; the canaries the orchestrator sweeps for ----------------------
    for (const secret of [FOLDER_NAME, MEMBER_FILE, GUEST_FILE, GUEST_NAME, MIME]) {
      expect(wire.filter((w) => w.includes(secret)), `"${secret}" on the wire`).toEqual([])
    }
    recordCanaries(FOLDER_NAME, MEMBER_FILE, GUEST_FILE, GUEST_NAME, MIME)
  }, 240_000)
})
