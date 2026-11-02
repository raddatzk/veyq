// Live e2e (VEYQ_E2E=1) for the drop box (cr-files-application F3, ADR 0080 decision 11) — against a REAL backend,
// MariaDB and the bundled S3-compatible store, with REAL crypto and the functions the pages run (no mock):
//   - a member creates a drop box in a folder and mints a WRITE_ONLY link (the drop key set on the node with it);
//   - two guests name themselves (each its own K_up) and each gets ONE folder; a second upload of one identity — a new
//     `ensureShareDropFolder` call, as after a reload — lands in the same folder; neither guest sees anything (the
//     resolve serves no node, `listShareFiles` nothing);
//   - the member opens both folders through the drop box's key (core's tree: drop key → K_up → K_F), reads names,
//     bytes and each file's blob meta (type, modified time — inbox #397); a member's create inside a guest's folder is refused;
//   - revoking the link (switch off, then delete) keeps the folders and the files, which the member still opens.
// Run against a FRESH instance: `python3 scripts/run_web_e2e.py src/web/src/features/depot/depotDropBox.e2e.test.ts`.
//
// The raw-wire check as in `depotShare.e2e.test.ts`: no file name, guest name or MIME type crosses `fetch` in the clear,
// and the plaintexts are handed to `recordCanaries` (ADR 0053).
//
// UNVERIFIED when written: run — the worktree that wrote it does not run the orchestrator (the main checkout does).
import { describe, it, expect, beforeAll, afterAll } from 'vitest'
import { b64url } from '@veyq/crypto/base64'
import { birthInstance } from '../../test/e2eBootstrap'
import { recordCanaries } from '../../test/canaries'
import { ensurePersonalScope, openScope } from '../spaces/scopes'
import { ScopeKind } from '../../api/generated/scopes'
import { createContentNode, createFolder, listTree, nodeContentKey } from '../../core/node/tree'
import { CHUNK_SIZE } from '../../core/blob/transport'
import { registry } from '../../core/applications/registry'
import { deleteShare, revokeShare } from '../../core/sharing/manageShares'
import { ShareRole } from '../../api/generated/sharing'
import { resolvePublicShare } from '../../core/sharing/resolvePublicShare'
import { decodeHttpError, httpStatus } from '../../shared/httpError'
import { fileNameSlug } from '../../shared/naming'
import { filesApi, publicShareFilesApi } from './api'
import { DROPBOX_KIND, FILE_KIND } from './kinds'
import { createDropBox, dropGuestContext, ensureDropFolder, mintDropBoxLink, nameDropGuest } from './dropBox'
import { guestFileUploadJob } from './shareUpload'
import { fetchFileContent, fileFacts } from './download'
import { nothingOfItsOwnToRekey } from '../../core/node/rekey/hooks'
import { testKind } from '../../test/testApplication'

const FOLDER_NAME = 'Vereinsablage Kranichfeld'
const BOX_NAME = 'Einsendungen Sommerfest'
const ANNA = 'Annegret Kranichfelder'
const BEN = 'Bertram Sommerwiese'
const ANNA_FILE_1 = 'Annegrets Tombola-Liste.pdf'
const ANNA_FILE_2 = 'Annegrets Kuchenplan.ods'
const BEN_FILE = 'Bertrams Fotoauswahl.zip'
const MIME = 'application/x-veyq-files-dropbox-e2e'

const wire: string[] = []
let realFetch: typeof fetch
beforeAll(() => {
  // A node-environment suite gets no application registry (`src/test/setup.ts`): FILE with its own folding.
  if (!registry.kind(FILE_KIND).supported) testKind(registry, { rekeyContent: nothingOfItsOwnToRekey, kind: FILE_KIND, icon: 'file', slug: fileNameSlug })
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
const contentOf = async (b: Blob) => Array.from(new Uint8Array(await b.arrayBuffer()))
const failure = (p: Promise<unknown>): Promise<unknown> => p.then(() => null, (e: unknown) => e)

describe.skipIf(!process.env.VEYQ_E2E)('files drop box e2e (live, real crypto, a WRITE_ONLY link with guest folders)', () => {
  it('two guests get two folders, one identity always the same one, the member opens them, a revoke keeps them', async () => {
    // --- the member: a folder holding a drop box, and its WRITE_ONLY link ------------------------------------------
    await birthInstance('E2E Depot Drop Box')
    await ensurePersonalScope()
    const { scopeId, epochs } = (await openScope(ScopeKind.Personal))!
    const folderId = await createFolder(scopeId, epochs, FOLDER_NAME)
    const withFolder = await listTree(scopeId, epochs)
    const folderKey = nodeContentKey(scopeId, epochs, withFolder, folderId)!
    const { epoch: folderEpoch, scopeEpoch: folderScopeEpoch } = withFolder.find((n) => n.id === folderId)!
    const boxId = await createDropBox(scopeId, epochs, { id: folderId, key: folderKey, epoch: folderEpoch, scopeEpoch: folderScopeEpoch }, BOX_NAME)
    const withBox = await listTree(scopeId, epochs)
    const boxKey = nodeContentKey(scopeId, epochs, withBox, boxId)!
    const link = await mintDropBoxLink(scopeId, boxId, { key: boxKey, epoch: withBox.find((n) => n.id === boxId)!.epoch }, epochs, BOX_NAME)
    const head = await resolvePublicShare(link.shareId, b64url(link.kShare))
    expect(head.share.nodes).toEqual([]) // a write-only link serves no node
    expect(head.share.rootKind).toBe(DROPBOX_KIND)

    // --- two guests, each its own folder; Anna twice into the same one --------------------------------------------
    const anna = await nameDropGuest(head, { name: ANNA })
    const ben = await nameDropGuest(head, { name: BEN })
    const annaFolder = await ensureDropFolder(head, anna, ANNA)
    expect(annaFolder.created).toBe(true)
    const anna1 = bytesOf(CHUNK_SIZE + 77, 1)
    const annaFile1 = (await guestFileUploadJob({ ctx: dropGuestContext(head, anna, annaFolder), parentId: annaFolder.nodeId }, fileOf(anna1, ANNA_FILE_1)).run(ctx())).nodeId

    const annaAgain = await ensureDropFolder(head, anna, ANNA) // a reload: the same identity asks again
    expect(annaAgain).toMatchObject({ nodeId: annaFolder.nodeId, created: false })
    expect(annaAgain.key).toEqual(annaFolder.key)
    const anna2 = bytesOf(999, 2)
    const annaFile2 = (await guestFileUploadJob({ ctx: dropGuestContext(head, anna, annaAgain), parentId: annaAgain.nodeId }, fileOf(anna2, ANNA_FILE_2)).run(ctx())).nodeId

    const benFolder = await ensureDropFolder(head, ben, BEN)
    expect(benFolder.nodeId).not.toBe(annaFolder.nodeId)
    const benBytes = bytesOf(4321, 3)
    const benFile = (await guestFileUploadJob({ ctx: dropGuestContext(head, ben, benFolder), parentId: benFolder.nodeId }, fileOf(benBytes, BEN_FILE)).run(ctx())).nodeId

    // Ben writes nothing into Anna's folder; neither sees anything of the drop box.
    const intoAnna = await failure(guestFileUploadJob({ ctx: dropGuestContext(head, ben, { ...benFolder, nodeId: annaFolder.nodeId }), parentId: annaFolder.nodeId }, fileOf(bytesOf(5, 4), 'x.bin')).run(ctx()))
    expect(intoAnna).not.toBeNull()
    expect((await publicShareFilesApi().listShareFiles({ shareId: link.shareId })).items).toEqual([])

    // --- the member opens both folders through the drop box's key ---------------------------------------------------
    const memberReads = async () => {
      const tree = await listTree(scopeId, epochs)
      const folders = tree.filter((n) => n.parentId === boxId)
      expect(folders.map((f) => f.name).sort()).toEqual([ANNA, BEN].sort())
      expect(tree.filter((n) => n.parentId === annaFolder.nodeId).map((n) => n.name).sort()).toEqual([ANNA_FILE_1, ANNA_FILE_2].sort())
      for (const [file, folder, bytes] of [[annaFile1, annaFolder.nodeId, anna1], [annaFile2, annaFolder.nodeId, anna2], [benFile, benFolder.nodeId, benBytes]] as const) {
        const blob = (await filesApi().listFiles({ scopeId, parentId: folder })).items.find((i) => i.nodeId === file)!.fileBlob!
        const fileKey = nodeContentKey(scopeId, epochs, tree, file)!
        expect(await contentOf(await fetchFileContent(scopeId, fileKey, file, blob))).toEqual(Array.from(bytes))
        // the guest's type and modified time ride its blob (inbox #397), opened down the same chain
        expect(fileFacts(scopeId, fileKey, file, blob)).toMatchObject({ size: bytes.length, meta: { type: MIME, modifiedAt: 1_700_000_000_000 } })
      }
      return tree
    }
    const tree = await memberReads()

    // A member places nothing in a guest's folder.
    const memberCreate = await failure(createContentNode(scopeId, epochs, { id: annaFolder.nodeId, key: nodeContentKey(scopeId, epochs, tree, annaFolder.nodeId)!, epoch: tree.find((n) => n.id === annaFolder.nodeId)!.epoch, scopeEpoch: tree.find((n) => n.id === annaFolder.nodeId)!.scopeEpoch }, FILE_KIND, 'mine.txt'))
    expect(await decodeHttpError(memberCreate)).toEqual({ status: 422, code: 'illegal_structure' })

    // --- revoking the link keeps everything; the guest is refused --------------------------------------------------
    // A drop box's link is WRITE_ONLY: its close re-keys nothing (ADR 0114 decision 2).
    const closed = { scopeId, nodeId: boxId, shareId: link.shareId, role: ShareRole.WriteOnly }
    await revokeShare(closed)
    expect(httpStatus(await failure(ensureDropFolder(head, anna, ANNA)))).toBe(404)
    await memberReads()
    await deleteShare({ ...closed, enabled: false, expiresAt: null })
    await memberReads()

    // --- the wire: no name, no MIME type in the clear ---------------------------------------------------------------
    const secrets = [FOLDER_NAME, BOX_NAME, ANNA, BEN, ANNA_FILE_1, ANNA_FILE_2, BEN_FILE, MIME]
    for (const secret of secrets) expect(wire.filter((w) => w.includes(secret)), `"${secret}" on the wire`).toEqual([])
    recordCanaries(...secrets)
  }, 300_000)
})
