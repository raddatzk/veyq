// Live e2e (VEYQ_E2E=1) for moving a node (inbox #358, ADR 0080 decisions 3, 4 and 11) — against a REAL backend, MariaDB
// and the bundled S3-compatible store, with REAL crypto and the door core's move dialog uses for a member (`nodeMover`
// → `moveNode`, no mock):
//   - a member creates folders A and B, a folder N in A holding a file with real content; moves N (and so its subtree)
//     into B, then to the scope root, then back into A — after each move the tree lists N where it went, and N's file
//     downloads and decrypts byte for byte through the chain as it now is (the node's own key never changed, only its
//     wrap); a stale second move built on the old placement version is refused `409 stale_placement`;
//   - a drop box: a guest names itself, gets its folder and uploads a file; the member moves the guest's folder out into
//     a normal folder — the folder lists there as an ordinary one (no identity), its file still decrypts, and the guest
//     can no longer add to it with its old token (`403`).
// Run against a FRESH instance: `python3 scripts/run_web_e2e.py web/src/core/node/move.e2e.test.ts`.
//
// The raw-wire check as in the neighbouring suites: no folder, file or guest name crosses `fetch` in the clear, and the
// plaintexts are handed to `recordCanaries` (ADR 0053).
//
// UNVERIFIED when written: run — the worktree that wrote it does not run the orchestrator (the main checkout does).
import { describe, it, expect, beforeAll, afterAll } from 'vitest'
import { b64url } from '@veyq/crypto/base64'
import { birthInstance } from '../../test/e2eBootstrap'
import { contentApi } from '../../api/client'
import { recordCanaries } from '../../test/canaries'
import { ensurePersonalScope, openScope } from '../../features/spaces/scopes'
import { ScopeKind } from '../../api/generated/scopes'
import { createFolder, listTree, moveNode, nodeContentKey, type TreeNode } from './tree'
import { registry } from '../applications/registry'
import { resolvePublicShare } from '../sharing/resolvePublicShare'
import { decodeHttpError, httpStatus } from '../../shared/httpError'
import { fileNameSlug } from '../../shared/naming'
import { CHUNK_SIZE } from '../blob/transport'
import { filesApi } from '../../features/files/api'
import { FILE_KIND } from '../../features/files/kinds'
import { fileUploadJob } from '../../features/files/uploadFile'
import { fetchFileContent } from '../../features/files/download'
import { createDropBox, dropGuestContext, ensureDropFolder, mintDropBoxLink, nameDropGuest } from '../../features/files/dropBox'
import { guestFileUploadJob } from '../../features/files/shareUpload'

const A_NAME = 'Umzug Kranichfeld'
const B_NAME = 'Archiv Sommerwiese'
const N_NAME = 'Steuerunterlagen 2025'
const FILE_NAME = 'Grundsteuerbescheid Kranichfeld.pdf'
const BOX_NAME = 'Einsendungen Vereinsfest'
const GUEST_NAME = 'Hildegard Moosbacher'
const GUEST_FILE = 'Hildegards Kassenbuch.ods'
const AFTER_MOVE = 'nach dem Herausziehen.txt'
const MIME = 'application/x-veyq-move-e2e'

const wire: string[] = []
let realFetch: typeof fetch
beforeAll(() => {
  // A node-environment suite gets no application registry (`src/test/setup.ts`): FILE with its own folding.
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

const bytesOf = (n: number, salt: number) => new Uint8Array(n).map((_, i) => (i * 7 + salt) % 251)
const fileOf = (bytes: Uint8Array, name: string) => new File([bytes as BlobPart], name, { type: MIME, lastModified: 1_700_000_000_000 })
const ctx = () => ({ onProgress: () => {}, signal: new AbortController().signal })
const contentOf = async (b: Blob) => Array.from(new Uint8Array(await b.arrayBuffer()))
const failure = (p: Promise<unknown>): Promise<unknown> => p.then(() => null, (e: unknown) => e)
const byId = (tree: TreeNode[], id: string) => tree.find((n) => n.id === id)!

describe.skipIf(!process.env.VEYQ_E2E)('move e2e (live, real crypto: a subtree keeps opening; a guest folder moved out ends its identity)', () => {
  it('a member moves a folder with a file between folders and the root; a drop box guest\'s folder out of its box', async () => {
    await birthInstance('E2E Move')
    await ensurePersonalScope()
    const { scopeId, epochs } = (await openScope(ScopeKind.Personal))!

    // --- A/N/file and B ---------------------------------------------------------------------------------------------
    const aId = await createFolder(scopeId, epochs, A_NAME)
    const bId = await createFolder(scopeId, epochs, B_NAME)
    let tree = await listTree(scopeId, epochs)
    const nId = await createFolder(scopeId, epochs, N_NAME, { id: aId, key: nodeContentKey(scopeId, epochs, tree, aId)! })
    tree = await listTree(scopeId, epochs)
    const nKey = nodeContentKey(scopeId, epochs, tree, nId)!
    const bytes = bytesOf(CHUNK_SIZE + 4321, 3)
    const fileId = (await fileUploadJob({ scopeId, epochs, parent: { id: nId, key: nKey } }, fileOf(bytes, FILE_NAME)).run(ctx())).nodeId

    /** After a move: N listed under `parent` with its name, its key unchanged, and its file decrypting byte for byte. */
    const readsUnder = async (parent: string | null) => {
      const t = await listTree(scopeId, epochs)
      expect(byId(t, nId)).toMatchObject({ parentId: parent, name: N_NAME })
      expect(Array.from(nodeContentKey(scopeId, epochs, t, nId)!), 'the node\'s own key never changes').toEqual(Array.from(nKey))
      const blob = (await filesApi().listFiles({ scopeId, parentId: nId })).items.find((i) => i.nodeId === fileId)!.fileBlob!
      const fileKey = nodeContentKey(scopeId, epochs, t, fileId)!
      expect(await contentOf(await fetchFileContent(scopeId, fileKey, fileId, blob))).toEqual(Array.from(bytes))
      expect(byId(t, fileId).name).toBe(FILE_NAME)
      return t
    }

    const before = await readsUnder(aId)
    await moveNode(scopeId, epochs, before, nId, bId)
    const inB = await readsUnder(bId)
    expect(byId(inB, nId).placementVersion).toBe(byId(before, nId).placementVersion + 1)
    // a second move built on the placement the first one superseded is refused by the server's compare-and-set
    // (sent once, unretried: the version stated is the stale one)
    const stale = await failure(contentApi().updateNode({
      scopeId, nodeId: nId,
      updateNodeRequest: { parentId: aId, wrappedContentKey: byId(inB, nId).wrappedContentKey, encryptedName: 'c3RhbGU=', slugToken: 'c3RhbGU=', epoch: epochs.current, placementVersion: byId(before, nId).placementVersion },
    }))
    expect(await decodeHttpError(stale)).toEqual({ status: 409, code: 'stale_placement' })
    await moveNode(scopeId, epochs, inB, nId, null) // to the scope root: sealed under K_SC at the current epoch
    const atRoot = await readsUnder(null)
    await moveNode(scopeId, epochs, atRoot, nId, aId)
    await readsUnder(aId)

    // --- a drop box guest's folder, moved out by the member ---------------------------------------------------------
    tree = await listTree(scopeId, epochs)
    const boxId = await createDropBox(scopeId, epochs, { id: bId, key: nodeContentKey(scopeId, epochs, tree, bId)! }, BOX_NAME)
    const boxKey = nodeContentKey(scopeId, epochs, await listTree(scopeId, epochs), boxId)!
    const link = await mintDropBoxLink(scopeId, boxId, boxKey, epochs, BOX_NAME)
    const head = await resolvePublicShare(link.shareId, b64url(link.kShare))
    const hilde = await nameDropGuest(head, { name: GUEST_NAME })
    const folder = await ensureDropFolder(head, hilde, GUEST_NAME)
    const guestBytes = bytesOf(2345, 9)
    const guestFile = (await guestFileUploadJob({ ctx: dropGuestContext(head, hilde, folder), parentId: folder.nodeId }, fileOf(guestBytes, GUEST_FILE)).run(ctx())).nodeId

    const withBox = await listTree(scopeId, epochs)
    expect(byId(withBox, folder.nodeId)).toMatchObject({ parentId: boxId, name: GUEST_NAME })
    expect(byId(withBox, folder.nodeId).shareIdentityId).toBeTruthy()
    await moveNode(scopeId, epochs, withBox, folder.nodeId, aId)

    const out = await listTree(scopeId, epochs)
    expect(byId(out, folder.nodeId)).toMatchObject({ parentId: aId, name: GUEST_NAME, shareIdentityId: null })
    expect(out.filter((n) => n.parentId === boxId), 'the drop box holds no folder of it any more').toEqual([])
    const blob = (await filesApi().listFiles({ scopeId, parentId: folder.nodeId })).items.find((i) => i.nodeId === guestFile)!.fileBlob!
    expect(await contentOf(await fetchFileContent(scopeId, nodeContentKey(scopeId, epochs, out, guestFile)!, guestFile, blob))).toEqual(Array.from(guestBytes))

    // the guest adds nothing to its old folder with its old token
    const intoOld = await failure(guestFileUploadJob({ ctx: dropGuestContext(head, hilde, folder), parentId: folder.nodeId }, fileOf(bytesOf(7, 1), AFTER_MOVE)).run(ctx()))
    expect(httpStatus(intoOld)).toBe(403)
    expect((await listTree(scopeId, epochs)).filter((n) => n.parentId === folder.nodeId).map((n) => n.name)).toEqual([GUEST_FILE])

    // --- the wire: no name, no MIME type in the clear ----------------------------------------------------------------
    const secrets = [A_NAME, B_NAME, N_NAME, FILE_NAME, BOX_NAME, GUEST_NAME, GUEST_FILE, AFTER_MOVE, MIME]
    for (const secret of secrets) expect(wire.filter((w) => w.includes(secret)), `"${secret}" on the wire`).toEqual([])
    recordCanaries(...secrets)
  }, 300_000)
})
