// cr-node-key-hierarchy 5b.4b live e2e (VEYQ_E2E=1) — a READ_WRITE guest of a FOLDER link uploads into a Memory below
// the folder and into its chapter (5a.5, ADR 0080 Decision 6), against a REAL backend + MariaDB + the bundled store,
// with REAL crypto. The web half of what `SubtreeUploadSliceTest` proves on the server: the upload names its TARGET
// (`addSharePhoto`'s `nodeId`) and seals under the target's key from the chain.
//
// Two actors on one instance, serialized (the `chapterShare.e2e` / `folderShareGuest.e2e` shape):
//   A          = curator: server owner + tenant owner (from `birthInstance`); a folder holding a Memory (with a chapter
//                and a photo of A's).
//   anonymous  = guests with NO session — every call goes through `publicSharesApi()` or `publicShareMediaApi()`.
//
// What only this layer can prove: that the server files the guest's uploads on the named nodes and the member opens
// them; that the guest's NAME — sealed under the FOLDER's key, the link's root — is read by the member on the Memory
// and on the chapter through the chain (`contentKeyChain` + `openBucketName`), and not under the node's key alone; that
// the guest deletes only its own; that a READ link refuses an upload; and that what a guest is served names no other
// guest's identity (asserted on the raw wire, src/web/CLAUDE.md).
//
//   python3 scripts/run_web_e2e.py src/web/src/features/memories/folderShareUpload.e2e.test.ts
import { describe, it, expect } from 'vitest'
import { b64url } from '@veyq/crypto/base64'
import { publicSharesApi } from '../../api/client'
import { publicShareMediaApi } from './api'
import { HttpStatus, isCodedError, isHttpStatus } from '../../shared/httpError'
import { birthInstance } from '../../test/e2eBootstrap'
import { recordCanaries } from '../../test/canaries'
import { ensurePersonalScope, listMyScopes, openPersonalScope } from '../spaces/scopes'
import { epochsOf } from '../spaces/epochsTestUtil'
import { contentKeyChain, createFolder, listTree, nodeContentKey } from '../../core/node/tree'
import { createChapter, createMemory } from './memoryNodes'
import { addMedia, listMedia, openOriginal } from './media'
import { listBuckets, openBucketName } from './mediaBuckets'
import { mintShare } from '../../core/sharing/manageShares'
import { resolvePublicShare, shareRootEpoch } from '../../core/sharing/resolvePublicShare'
import { openShareRoot, walkSubtree } from '../../core/sharing/shareTree'
import { createGuestIdentity } from '../../core/sharing/guestIdentity'
import { addSharePhoto, deleteSharePhoto } from './share'
import { ScopeKind } from '../../api/generated/scopes'
import { ShareRole } from '../../api/generated/sharing'

// Distinctive plaintext for the wire-assertion sweep (ADR 0053) — distinct from the declared tenant name.
const TENANT_NAME = 'E2E Folder Share Upload' // declared plaintext (veyq_tenant.name) — NOT a canary
const FOLDER_NAME = 'Familienarchiv Kowalczyk'
const MEMORY_NAME = 'Goldene Hochzeit Masuren'
const CHAPTER_NAME = 'Festessen Gasthof Mazury'
const GUEST_NAME = 'Neffe Bartholomaeus'
const OTHER_GUEST_NAME = 'Nichte Wiesława'

/** Deterministic bytes, a different fill per photo, so a mixed-up read-back cannot pass. */
function bytes(fill: number, size = 200 * 1024): Uint8Array {
  const out = new Uint8Array(size)
  for (let i = 0; i < out.length; i++) out[i] = (i * 23 + fill) & 0xff
  return out
}
const photo = (fill: number) => ({ blob: new Blob([bytes(fill) as BlobPart]), mediaType: '' }) // original-only

/** The refusal a call ended in, or null when it succeeded. */
const failure = (p: Promise<unknown>): Promise<unknown> => p.then(() => null, (e: unknown) => e)

describe.skipIf(!process.env.VEYQ_E2E)('folder share upload e2e (live, real crypto, a folder guest uploads below the folder)', () => {
  it('a guest uploads into a Memory and its chapter below the folder; the member reads both and the guest\'s name', async () => {
    // --- A: cold-birth the instance; a folder holding a Memory (a photo of A's) with a chapter ----------------------
    await birthInstance(TENANT_NAME)
    await ensurePersonalScope()
    const personal = (await listMyScopes()).find((s) => s.kind === ScopeKind.Personal)
    expect(personal, 'the personal scope was born').toBeDefined()
    const scopeId = personal!.id
    const epochs = epochsOf((await openPersonalScope())!)
    const folderId = await createFolder(scopeId, epochs, FOLDER_NAME)
    const folderKey = nodeContentKey(scopeId, epochs, await listTree(scopeId, epochs), folderId)!
    const memory = await createMemory(scopeId, epochs, MEMORY_NAME, { id: folderId, key: folderKey, epoch: 0 })
    const chapter = await createChapter(scopeId, epochs, memory.memoryKey, CHAPTER_NAME, memory.nodeId, 0)
    const membersPhoto = await addMedia(scopeId, memory.nodeId, memory.memoryKey, photo(1))

    const minted = await mintShare(scopeId, folderId, { key: folderKey, epoch: 0 }, epochs, FOLDER_NAME, ShareRole.ReadWrite)
    const shareId = minted.shareId
    const fragmentKey = b64url(minted.kShare)

    // --- anonymous: open the subtree, name yourself under the folder's key, upload into the Memory and the chapter --
    const head = await resolvePublicShare(shareId, fragmentKey)
    const rootKey = openShareRoot(head).key
    const { keys } = walkSubtree(head.scopeId, head.share.nodes, rootKey)
    const guest = await createGuestIdentity(head, rootKey, { name: GUEST_NAME, fragmentKey, rootEpoch: shareRootEpoch(head.share) })
    const epochOf = (nodeId: string) => head.share.nodes.find((n) => n.id === nodeId)!.epoch
    const upload = (nodeId: string) => ({ ...guest, shareId, scopeId: head.scopeId, sealKey: keys.get(nodeId)!, nodeEpoch: epochOf(nodeId), nodeId })
    const intoMemory = await addSharePhoto(upload(memory.nodeId), photo(2))
    const intoChapter = await addSharePhoto(upload(chapter.nodeId), photo(3))

    // --- A: both are filed on the named nodes and open under those nodes' keys -------------------------------------
    const inMemory = (await listMedia(scopeId, memory.nodeId)).find((i) => i.mediaId === intoMemory.mediaId)
    expect(inMemory, "the guest's photo is in the Memory's listing").toBeDefined()
    expect(Array.from(await openOriginal(scopeId, memory.memoryKey, inMemory!)), 'the member opens it').toEqual(Array.from(bytes(2)))
    const inChapter = (await listMedia(scopeId, chapter.nodeId)).find((i) => i.mediaId === intoChapter.mediaId)
    expect(inChapter, "the guest's photo is in the chapter's listing").toBeDefined()
    expect(Array.from(await openOriginal(scopeId, chapter.chapterKey, inChapter!)), 'the member opens it').toEqual(Array.from(bytes(3)))

    // --- A: the guest's name, sealed under the FOLDER's key, read on both nodes through the chain --------------------
    const tree = await listTree(scopeId, epochs)
    const recovered: (string | null)[] = []
    for (const nodeId of [memory.nodeId, chapter.nodeId]) {
      const [own, ...above] = contentKeyChain(scopeId, epochs, tree, nodeId)
      const primary = (await listBuckets(scopeId, nodeId)).find((b) => b.isPrimary && b.ownerKey === `share:${guest.identityId}`)
      expect(primary?.sealedName, "the guest's bucket carries its sealed name").toBeTruthy()
      expect(openBucketName(scopeId, nodeId, own, primary!), "under the node's own key alone it does not open").toBeNull()
      const name = openBucketName(scopeId, nodeId, own, primary!, above)
      expect(name, "the member reads the guest's name through the chain").toBe(GUEST_NAME)
      recovered.push(name)
    }

    // --- anonymous: deletes only its own — the member's photo is a non-enumerable 404 -------------------------------
    const refused = await failure(deleteSharePhoto(shareId, guest.contributorToken, membersPhoto.mediaId))
    expect(isHttpStatus(refused, HttpStatus.notFound), "deleting the member's photo is a 404").toBe(true)
    await deleteSharePhoto(shareId, guest.contributorToken, intoMemory.mediaId)
    const after = (await listMedia(scopeId, memory.nodeId)).map((i) => i.mediaId)
    expect(after, "the guest's own photo is gone").not.toContain(intoMemory.mediaId)
    expect(after, "the member's photo stays (the live control)").toContain(membersPhoto.mediaId)

    // --- a READ link of the same folder refuses an upload into the Memory --------------------------------------------
    const readOnly = await mintShare(scopeId, folderId, { key: folderKey, epoch: 0 }, epochs, FOLDER_NAME, ShareRole.Read)
    const onRead = await failure(addSharePhoto({ ...upload(memory.nodeId), shareId: readOnly.shareId }, photo(4)))
    expect(await isCodedError(onRead, HttpStatus.forbidden, 'share_not_contributable'), 'a READ link: 403 share_not_contributable').toBe(true)

    // --- the raw wire: a second guest uploads too; what the first is served names neither identity -----------------
    const other = await createGuestIdentity(head, rootKey, { name: OTHER_GUEST_NAME, fragmentKey, rootEpoch: shareRootEpoch(head.share) })
    await addSharePhoto({ ...other, shareId, scopeId: head.scopeId, sealKey: keys.get(memory.nodeId)!, nodeEpoch: epochOf(memory.nodeId), nodeId: memory.nodeId }, photo(5))
    const resolveWire = JSON.stringify(await (await publicSharesApi().resolveShareRaw({ shareId })).raw.json())
    const mediaWire = JSON.stringify(await (await publicShareMediaApi().listShareMediaRaw({ shareId })).raw.json())
    for (const id of [other.identityId, guest.identityId]) {
      expect(resolveWire.includes(id), 'the resolve names no guest identity').toBe(false)
      expect(mediaWire.includes(id), "the media read names no READ_WRITE guest's identity").toBe(false)
    }

    // Every value recovered by UNSEALING what the server returned (ADR 0053).
    recordCanaries(...recovered)
  }, 240_000)
})
