// Live e2e (VEYQ_E2E=1) for a SINGLE-FILE share (inbox #378, ADR 0080 decisions 1 and 6) — against a REAL backend, MariaDB
// and the bundled S3-compatible store, with REAL crypto and the functions the share page runs (no mock):
//   - a member shares ONE file READ: the guest's resolve serves that file alone (no folder above, no sibling), its key
//     opens through the fragment key, its blob downloads through the link byte for byte, the sibling's blob is `404`;
//   - a WRITE_ONLY link on a file is refused (`422 illegal_scope`) — it would mean nothing on a node with no identity folder;
//   - a READ link takes no write; a READ_WRITE link: a named guest replaces the file's content (compare-and-set against
//     the blob it read, the new blob bringing its own meta), the member reads the new bytes; a stale replace is refused;
//     the guest creates nothing under the file and places nothing beside it (`illegal_scope`);
//   - the member trashes the file: the guest lists nothing, its blob is `404`, an upload into it is refused.
// Run against a FRESH instance: `python3 scripts/run_web_e2e.py src/web/src/features/depot/fileSingleShare.e2e.test.ts`.
//
// The raw-wire check as in `depotShare.e2e.test.ts`: no file name or MIME type crossed the wire in the clear; the
// plaintexts it sealed go to `recordCanaries` (ADR 0053).
//
// UNVERIFIED when written: run — the worktree that wrote it may not run the orchestrator (the main checkout does); it is
// compiled (`npm run typecheck`) and lint-clean only.
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
import { mintShare, NO_NOTE } from '../../core/sharing/manageShares'
import { resolvePublicShare } from '../../core/sharing/resolvePublicShare'
import { createGuestIdentity } from '../../core/sharing/guestIdentity'
import { createShareNodeAsGuest, GuestTreeError, guestTreeContext, openGuestTreeParts } from '../../core/sharing/guestTree'
import { shareChunkUrls } from '../../core/sharing/guestBlob'
import { decodeHttpError, httpStatus } from '../../shared/httpError'
import { fileNameSlug } from '../../shared/naming'
import { filesApi, publicShareFilesApi } from './api'
import { FILE_KIND } from './kinds'
import { fileUploadJob, StaleFileError } from './uploadFile'
import { guestFileUploadJob } from './shareUpload'
import { fetchFileContent, fileFacts } from './download'

const FOLDER_NAME = 'Nachlass Hohenfels'
const TARGET_FILE = 'Kaufvertrag Hohenfels 1962.pdf'
const SIBLING_FILE = 'Grundbuchauszug Hohenfels.pdf'
const GUEST_NAME = 'Notarin Wiltrud'
const MIME = 'application/x-veyq-single-file-share-e2e'

const wire: string[] = []
let realFetch: typeof fetch
beforeAll(() => {
  // A node-environment suite gets no application registry (`src/test/setup.ts`): FILE with its own folding, as Depot's
  // `register.ts` registers it.
  if (!registry.kind(FILE_KIND).supported) registry.registerKind({ kind: FILE_KIND, icon: 'file', shareable: true, slug: fileNameSlug })
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

const bytesOf = (n: number, salt: number) => new Uint8Array(n).map((_, i) => (i * 13 + salt) % 251)
const fileOf = (bytes: Uint8Array, name: string) => new File([bytes as BlobPart], name, { type: MIME, lastModified: 1_700_000_000_000 })
const ctx = () => ({ onProgress: () => {}, signal: new AbortController().signal })
// Sealed-looking filler (48 random bytes): the wire check refuses a declared-opaque field shorter than a nonce and a tag.
const filler = (): string => Buffer.from(crypto.getRandomValues(new Uint8Array(48))).toString('base64')
const contentOf = async (b: Blob) => Array.from(new Uint8Array(await b.arrayBuffer()))
const failure = (p: Promise<unknown>): Promise<unknown> => p.then(() => null, (e: unknown) => e)

describe.skipIf(!process.env.VEYQ_E2E)('single-file share e2e (live, real crypto, a link rooted at one file)', () => {
  it('a READ guest sees and downloads that file alone, a READ_WRITE guest replaces it, WRITE_ONLY is refused, a trashed file is gone', async () => {
    // --- the member: a folder holding two files; the target is a two-frame file ----------------------------------------
    await birthInstance('E2E Single File Share')
    await ensurePersonalScope()
    const personal = (await openScope(ScopeKind.Personal))!
    const { scopeId, epochs } = personal
    const folderId = await createFolder(scopeId, epochs, FOLDER_NAME)
    const folderKey = nodeContentKey(scopeId, epochs, await listTree(scopeId, epochs), folderId)!
    const targetBytes = bytesOf(CHUNK_SIZE + 211, 1)
    const siblingBytes = bytesOf(900, 2)
    const parent = { id: folderId, key: folderKey }
    const target = (await fileUploadJob({ scopeId, epochs, parent }, fileOf(targetBytes, TARGET_FILE)).run(ctx())).nodeId
    const sibling = (await fileUploadJob({ scopeId, epochs, parent }, fileOf(siblingBytes, SIBLING_FILE)).run(ctx())).nodeId
    const tree = await listTree(scopeId, epochs)
    const targetKey = nodeContentKey(scopeId, epochs, tree, target)!
    const listing = (await filesApi().listFiles({ scopeId, parentId: folderId })).items
    const targetBlob = listing.find((i) => i.nodeId === target)!.fileBlob!
    const siblingBlob = listing.find((i) => i.nodeId === sibling)!.fileBlob!

    // --- a WRITE_ONLY link on a file is refused ------------------------------------------------------------------------
    const dropOnFile = await failure(mintShare(scopeId, target, targetKey, epochs, TARGET_FILE, ShareRole.WriteOnly, null, NO_NOTE, '', '', {
      dropFields: async () => ({ sealedDropPublicKey: filler(), sealedDropPrivateKey: filler() }),
    }))
    expect(await decodeHttpError(dropOnFile)).toEqual({ status: 422, code: 'illegal_scope' })

    // --- a READ link on the file: the guest sees that file alone and downloads it ------------------------------------
    const read = await mintShare(scopeId, target, targetKey, epochs, TARGET_FILE, ShareRole.Read)
    const readHead = await resolvePublicShare(read.shareId, b64url(read.kShare))
    expect(readHead.rootKind).toBe(FILE_KIND)
    expect(readHead.name).toBe(TARGET_FILE)
    const readParts = openGuestTreeParts(readHead)
    expect([...readParts.nodes.keys()]).toEqual([target])
    expect(Array.from(readParts.keys.get(target)!)).toEqual(Array.from(targetKey))
    const readListing = (await publicShareFilesApi().listShareFiles({ shareId: read.shareId })).items
    expect(readListing.map((i) => i.nodeId)).toEqual([target])
    expect(fileFacts(readHead.scopeId, readParts.keys.get(target)!, target, readListing[0].fileBlob!).meta)
      .toEqual({ v: 1, type: MIME, name: TARGET_FILE, modifiedAt: 1_700_000_000_000 })
    const downloaded = await fetchFileContent(readHead.scopeId, readParts.keys.get(target)!, target, readListing[0].fileBlob!, (b) => shareChunkUrls(read.shareId, b))
    expect(await contentOf(downloaded)).toEqual(Array.from(targetBytes))
    // …and nothing beside it: the sibling's blob is not this link's
    expect(httpStatus(await failure(shareChunkUrls(read.shareId, siblingBlob.blobId)))).toBe(404)
    // a READ link takes no write
    const readWrite = await failure(publicShareFilesApi().completeShareFile({
      shareId: read.shareId, nodeId: target, shareFileCompleteRequest: { contributorToken: 'x'.repeat(43), uploadId: crypto.randomUUID() },
    }))
    expect(httpStatus(readWrite)).toBe(403)

    // --- a READ_WRITE link: a named guest replaces the content -----------------------------------------------------------
    const rw = await mintShare(scopeId, target, targetKey, epochs, TARGET_FILE, ShareRole.ReadWrite)
    const rwFragment = b64url(rw.kShare)
    const rwHead = await resolvePublicShare(rw.shareId, rwFragment)
    const rwKeys = openGuestTreeParts(rwHead).keys
    const guest = await createGuestIdentity(rwHead, rwKeys.get(target)!, { name: GUEST_NAME, fragmentKey: rwFragment })
    const guestCtx = () => guestTreeContext(rwHead, { contributorToken: guest.contributorToken, fragmentKey: rwFragment })
    const replacement = bytesOf(640, 3)
    await guestFileUploadJob({
      ctx: guestCtx(), parentId: target,
      replace: { nodeId: target, nodeKey: rwKeys.get(target)!, expectedBlobId: targetBlob.blobId },
    }, fileOf(replacement, TARGET_FILE)).run(ctx())
    const replacedBlob = (await filesApi().listFiles({ scopeId, parentId: folderId })).items.find((i) => i.nodeId === target)!.fileBlob!
    expect(replacedBlob.blobId).not.toBe(targetBlob.blobId)
    expect(await contentOf(await fetchFileContent(scopeId, targetKey, target, replacedBlob))).toEqual(Array.from(replacement))
    expect(fileFacts(scopeId, targetKey, target, replacedBlob).size).toBe(replacement.length)
    // a replace still expecting the old blob is stale; the file keeps the guest's content
    const stale = await failure(guestFileUploadJob({
      ctx: guestCtx(), parentId: target,
      replace: { nodeId: target, nodeKey: rwKeys.get(target)!, expectedBlobId: targetBlob.blobId },
    }, fileOf(bytesOf(5, 4), TARGET_FILE)).run(ctx()))
    expect(stale).toBeInstanceOf(StaleFileError)
    expect((stale as StaleFileError).currentBlobId).toBe(replacedBlob.blobId)

    // the guest creates nothing under the file (a file has no children: no rule answers for it, `illegal_scope`); what lies
    // above or beside it, it holds no key of — the backend suite refuses those placements by id
    const underFile = await failure(createShareNodeAsGuest(guestCtx(), { parentId: target, kind: FILE_KIND, name: 'x.txt' }))
    expect(underFile).toBeInstanceOf(GuestTreeError)
    expect((underFile as GuestTreeError).code).toBe('illegal_scope')
    const siblingTarget = await failure(guestFileUploadJob({
      ctx: guestCtx(), parentId: target,
      replace: { nodeId: sibling, nodeKey: targetKey, expectedBlobId: siblingBlob.blobId },
    }, fileOf(bytesOf(5, 6), SIBLING_FILE)).run(ctx()))
    expect(httpStatus(siblingTarget), 'the sibling is no node of this link').toBe(404)

    // --- the member trashes the file: for the guest it is gone ------------------------------------------------------------
    await deleteNode(scopeId, target)
    expect((await publicShareFilesApi().listShareFiles({ shareId: rw.shareId })).items).toEqual([])
    expect(httpStatus(await failure(shareChunkUrls(rw.shareId, replacedBlob.blobId)))).toBe(404)
    const intoTrash = await failure(guestFileUploadJob({
      ctx: guestCtx(), parentId: target,
      replace: { nodeId: target, nodeKey: rwKeys.get(target)!, expectedBlobId: replacedBlob.blobId },
    }, fileOf(bytesOf(5, 7), TARGET_FILE)).run(ctx()))
    expect([403, 404]).toContain(httpStatus(intoTrash))
    // a file in the trash is shared by nobody
    const trashed = await failure(mintShare(scopeId, target, targetKey, epochs, TARGET_FILE, ShareRole.Read))
    expect(await decodeHttpError(trashed)).toMatchObject({ status: 404, code: 'node_not_found' })

    // --- the wire: no name, no MIME type in the clear; the canaries the orchestrator sweeps for -----------------------
    for (const secret of [FOLDER_NAME, TARGET_FILE, SIBLING_FILE, GUEST_NAME, MIME]) {
      expect(wire.filter((w) => w.includes(secret)), `"${secret}" on the wire`).toEqual([])
    }
    recordCanaries(FOLDER_NAME, TARGET_FILE, SIBLING_FILE, GUEST_NAME, MIME)
  }, 240_000)
})
