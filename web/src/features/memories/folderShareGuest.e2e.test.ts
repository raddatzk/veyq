// cr-node-key-hierarchy 5b.4a live e2e (VEYQ_E2E=1) — a READ_WRITE guest's tree operations on a FOLDER link (ADR 0080
// decisions 2, 3, 6 and 10), against a REAL backend + MariaDB + the bundled store, with REAL crypto. Core's functions
// only (`core/sharing/guestTree.ts`, `core/sharing/guestIdentity.ts`): the public folder page is 5b.4b, and so is a
// guest's upload into a Memory below the folder (5a.5), which this suite does not touch.
//
// Two actors on one instance, serialized (the `folderShare.e2e` shape):
//   A          = curator: server owner + tenant owner (from `birthInstance`); a folder holding two subfolders, a Memory
//                (with a photo and a chapter) in the first one.
//   anonymous  = a guest with NO session: every call goes through `publicSharesApi()`, which carries no bearer.
//
// What only this layer can prove: that the server stores what a guest seals under the keys of the chain — a folder
// created under the root and one nested in it, named under their PARENT's key at the parent's epoch — so the member's
// own `listTree` (which opens top-down from `K_SC`) reads them; that a guest's move re-wraps the Memory's key under the
// new parent so the member still opens its chain and media; and that the server refuses what core's and Memories'
// rules refuse, by code. The unit layer (`core/sharing/guestTree.wire.test.ts`) proves the requests at `fetch`.
//
//   python3 scripts/run_web_e2e.py web/src/features/memories/folderShareGuest.e2e.test.ts
import { describe, it, expect } from 'vitest'
import { b64url } from '@veyq/crypto/base64'
import { publicSharesApi } from '../../api/client'
import { HttpStatus, isCodedError } from '../../shared/httpError'
import { birthInstance } from '../../test/e2eBootstrap'
import { recordCanaries } from '../../test/canaries'
import { ensurePersonalScope, listMyScopes, openPersonalScope } from '../spaces/scopes'
import { epochsOf } from '../spaces/epochsTestUtil'
import { createFolder, listTree, nodeContentKey, sealName, slugTokenFor, wrapContentKey } from '../../core/node/tree'
import { FOLDER_KIND } from '../../core/node/kind'
import { createChapter, createMemory } from './memoryNodes'
import { MEMORY_KIND } from './kinds'
import { addMedia, listMedia, openOriginal } from './media'
import { mintShare } from '../../core/sharing/manageShares'
import { resolvePublicShare } from '../../core/sharing/resolvePublicShare'
import { openShareRoot } from '../../core/sharing/shareTree'
import { createGuestIdentity } from '../../core/sharing/guestIdentity'
import { createShareNodeAsGuest, guestTreeContext, placeShareNodeAsGuest, GuestTreeError } from '../../core/sharing/guestTree'
import { guestTreeSource } from '../../core/sharing/guestAccess'
import { ScopeKind } from '../../api/generated/scopes'
import { ShareRole } from '../../api/generated/sharing'

// Distinctive plaintext for the wire-assertion sweep (ADR 0053) — distinct from the declared tenant name.
const TENANT_NAME = 'E2E Folder Share Guest' // declared plaintext (veyq_tenant.name) — NOT a canary
const FOLDER_NAME = 'Nachlass Wisniewski'
const LEFT_NAME = 'Kartons Dachboden'
const RIGHT_NAME = 'Kartons Keller'
const MEMORY_NAME = 'Hochzeit Tante Halina'
const CHAPTER_NAME = 'Polterabend Scheune'
const GUEST_NAME = 'Cousine Agnieszka'
const CREATED_NAME = 'Fundstuecke Ostpreussen'
const NESTED_NAME = 'Feldpostbriefe Opa'
const GUEST_MEMORY_NAME = 'Sommerfest Gartenlaube Zoppot'

/** Deterministic bytes, a different fill per photo. */
function bytes(fill: number, size = 200 * 1024): Uint8Array {
  const out = new Uint8Array(size)
  for (let i = 0; i < out.length; i++) out[i] = (i * 29 + fill) & 0xff
  return out
}
const photo = (fill: number) => ({ blob: new Blob([bytes(fill) as BlobPart]), mediaType: '' }) // original-only

type RawResolve = { nodes: Record<string, unknown>[] }

/** The refusal a call ended in, or null when it succeeded. */
const failure = (p: Promise<unknown>): Promise<unknown> => p.then(() => null, (e: unknown) => e)

describe.skipIf(!process.env.VEYQ_E2E)('folder share guest e2e (live, real crypto, a READ_WRITE guest creates and moves)', () => {
  it('a guest creates a folder and a nested one, moves a Memory between subfolders; the server refuses by code', async () => {
    // --- A: cold-birth the instance; a folder with two subfolders, a Memory (photo + chapter) in the left one ------
    await birthInstance(TENANT_NAME)
    await ensurePersonalScope()
    const personal = (await listMyScopes()).find((s) => s.kind === ScopeKind.Personal)
    expect(personal, 'the personal scope was born').toBeDefined()
    const scopeId = personal!.id
    const epochs = epochsOf((await openPersonalScope())!)
    const folderId = await createFolder(scopeId, epochs, FOLDER_NAME)
    const folderKey = nodeContentKey(scopeId, epochs, await listTree(scopeId, epochs), folderId)!
    const leftId = await createFolder(scopeId, epochs, LEFT_NAME, { id: folderId, key: folderKey })
    const rightId = await createFolder(scopeId, epochs, RIGHT_NAME, { id: folderId, key: folderKey })
    const leftKey = nodeContentKey(scopeId, epochs, await listTree(scopeId, epochs), leftId)!
    const memory = await createMemory(scopeId, epochs, MEMORY_NAME, { id: leftId, key: leftKey })
    const inMemory = await addMedia(scopeId, memory.nodeId, memory.memoryKey, photo(1))
    const chapter = await createChapter(scopeId, epochs, memory.memoryKey, CHAPTER_NAME, memory.nodeId)

    // --- A: a READ_WRITE link of the folder, through core's mint ---------------------------------------------------
    const minted = await mintShare(scopeId, folderId, folderKey, epochs, FOLDER_NAME, ShareRole.ReadWrite)
    const shareId = minted.shareId
    const fragmentKey = b64url(minted.kShare)

    // --- anonymous: name yourself (the platform's identity, the name under the folder's key) -----------------------
    const head = await resolvePublicShare(shareId, fragmentKey)
    expect(head.rootKind).toBe(FOLDER_KIND)
    expect(head.role).toBe(ShareRole.ReadWrite)
    const identity = await createGuestIdentity(head, openShareRoot(head).key, { name: GUEST_NAME, fragmentKey })
    const contextNow = async () => guestTreeContext(await resolvePublicShare(shareId, fragmentKey), { contributorToken: identity.contributorToken, fragmentKey })

    // --- (a) anonymous: a folder under the root, then one nested in it ---------------------------------------------
    const createdId = (await createShareNodeAsGuest(await contextNow(), { parentId: folderId, kind: FOLDER_KIND, name: CREATED_NAME })).nodeId
    const nestedId = (await createShareNodeAsGuest(await contextNow(), { parentId: createdId, kind: FOLDER_KIND, name: NESTED_NAME })).nodeId

    // A: the member's own tree opens both names top-down from K_SC — the guest sealed under the keys of the chain.
    const afterCreate = await listTree(scopeId, epochs)
    const byId = (tree: typeof afterCreate, id: string) => tree.find((n) => n.id === id)
    const folderRow = byId(afterCreate, folderId)!
    expect(byId(afterCreate, createdId), "the guest's folder, under the root").toMatchObject({ parentId: folderId, name: CREATED_NAME, kind: FOLDER_KIND, placementRevision: 0 })
    expect(byId(afterCreate, nestedId), 'the nested folder, under it').toMatchObject({ parentId: createdId, name: NESTED_NAME, kind: FOLDER_KIND, placementRevision: 0 })
    expect(byId(afterCreate, createdId)!.epoch, "stored at the parent's epoch, as the guest sealed").toBe(folderRow.epoch)
    expect(nodeContentKey(scopeId, epochs, afterCreate, nestedId), "the nested folder's key opens through the chain").not.toBeNull()
    // The raw resolve carries the new rows as ciphertext: no plaintext name of what the guest created.
    const wireAfterCreate = (await (await publicSharesApi().resolveShareRaw({ shareId })).raw.json()) as RawResolve
    expect(wireAfterCreate.nodes.map((n) => n.id)).toEqual(expect.arrayContaining([createdId, nestedId]))
    expect(JSON.stringify(wireAfterCreate).includes(CREATED_NAME) || JSON.stringify(wireAfterCreate).includes(NESTED_NAME), 'no plaintext name on the wire').toBe(false)

    // --- (b) anonymous: move the Memory from the left subfolder to the right one -----------------------------------
    const beforeMove = await contextNow()
    expect(beforeMove.nodes.get(memory.nodeId)?.placementRevision).toBe(0)
    await placeShareNodeAsGuest(beforeMove, { nodeId: memory.nodeId, newParentId: rightId })

    // A: the Memory sits under the right subfolder, its name and key open, its chapter and photo with them.
    const afterMove = await listTree(scopeId, epochs)
    expect(byId(afterMove, memory.nodeId), 'the Memory moved, at the next placement revision').toMatchObject({ parentId: rightId, name: MEMORY_NAME, placementRevision: 1 })
    expect(Array.from(nodeContentKey(scopeId, epochs, afterMove, memory.nodeId) ?? []), "the Memory's own key opens under its new parent").toEqual(Array.from(memory.memoryKey))
    expect(Array.from(nodeContentKey(scopeId, epochs, afterMove, chapter.nodeId) ?? []), 'and its chapter below it').toEqual(Array.from(chapter.chapterKey))
    const media = (await listMedia(scopeId, memory.nodeId)).find((i) => i.mediaId === inMemory.mediaId)
    expect(media, "the Memory's photo is still listed").toBeDefined()
    expect(Array.from(await openOriginal(scopeId, memory.memoryKey, media!)), 'and opens').toEqual(Array.from(bytes(1)))

    // --- the guest's page reads that tree (inbox #401 S2): the guest tree source over the real resolve ---------------
    const hash = `#k=${fragmentKey}`
    const pageTree = guestTreeSource(await resolvePublicShare(shareId, fragmentKey), { hash })
    expect(pageTree.rootId).toBe(folderId)
    expect(pageTree.node(memory.nodeId), 'the Memory under its new parent, named through the chain').toMatchObject({ parentId: rightId, name: MEMORY_NAME, kind: MEMORY_KIND })
    expect(pageTree.children(rightId).map((n) => n.id)).toContain(memory.nodeId)
    expect(Array.from(pageTree.keyOf(memory.nodeId) ?? []), "the Memory's key, as the member's").toEqual(Array.from(memory.memoryKey))
    expect(pageTree.nameOf(createdId)).toBe(CREATED_NAME)
    expect(pageTree.linkTo(memory.nodeId), 'a link keeps the share key').toEqual({ query: { node: memory.nodeId }, hash })
    // --- (c) refusals, by code -------------------------------------------------------------------------------------
    const ctx = await contextNow()
    // A guest creates no Memory, not in a folder either (owner decision 2026-10-14: Memories declares no kind
    // guest-creatable, core's `GuestCreatableKinds`), and the level offers none (`memoryRights`).
    const guestMemory = await failure(createShareNodeAsGuest(ctx, { parentId: createdId, kind: MEMORY_KIND, name: GUEST_MEMORY_NAME }))
    expect(guestMemory instanceof GuestTreeError && guestMemory.code, 'a Memory created in a folder').toBe('illegal_scope')
    expect((await listTree(scopeId, epochs)).some((n) => n.parentId === createdId && n.kind === MEMORY_KIND), 'nothing stored').toBe(false)
    // Inside a Memory, Memories decides, and it declares nothing for a guest: a create there is illegal_scope.
    const intoMemory = await failure(createShareNodeAsGuest(ctx, { parentId: memory.nodeId, kind: FOLDER_KIND, name: 'Abgelehnt' }))
    expect(intoMemory instanceof GuestTreeError && intoMemory.code, 'a create inside a Memory').toBe('illegal_scope')
    // A chapter moved out of its Memory: the Memory's owner lets nothing out.
    const chapterOut = await failure(placeShareNodeAsGuest(ctx, { nodeId: chapter.nodeId, newParentId: leftId }))
    expect(chapterOut instanceof GuestTreeError && chapterOut.code, 'a chapter out of a Memory').toBe('illegal_scope')
    // The share root: the client refuses before sending; the server refuses the request built by hand.
    const rootPlaced = await failure(placeShareNodeAsGuest(ctx, { nodeId: folderId, newParentId: leftId }))
    expect(rootPlaced instanceof GuestTreeError && rootPlaced.code, 'the client never places the root').toBe('illegal_structure')
    const rootKey = ctx.keys.get(folderId)!
    const rootAt = { scopeId, nodeId: folderId, epoch: folderRow.epoch, placementRevision: 1 }
    const rootRenamed = await failure(
      publicSharesApi().updateShareNodeRaw({
        shareId, nodeId: folderId, xContributorToken: identity.contributorToken,
        updateShareNodeRequest: {
          parentId: leftId, encryptedName: sealName(rootKey, rootAt, 'Umbenannt'), slugToken: slugTokenFor(rootKey, leftId, FOLDER_KIND, 'Umbenannt'),
          wrappedContentKey: wrapContentKey(rootKey, rootAt, rootKey), placementRevision: 0,
        },
      }),
    )
    expect(await isCodedError(rootRenamed, HttpStatus.unprocessable, 'illegal_structure'), 'the share root renamed is 422 illegal_structure').toBe(true)
    // A placement built on the version before the move (0; the Memory is at 1 now): 409 stale_placement, raw.
    const memoryKey = ctx.keys.get(memory.nodeId)!
    const leftKeyGuest = ctx.keys.get(leftId)!
    const staleAt = { scopeId, nodeId: memory.nodeId, epoch: ctx.nodes.get(memory.nodeId)!.epoch, placementRevision: 1 }
    const stale = await failure(
      publicSharesApi().updateShareNodeRaw({
        shareId, nodeId: memory.nodeId, xContributorToken: identity.contributorToken,
        updateShareNodeRequest: {
          parentId: leftId, encryptedName: sealName(leftKeyGuest, staleAt, MEMORY_NAME), slugToken: slugTokenFor(leftKeyGuest, leftId, MEMORY_KIND, MEMORY_NAME),
          wrappedContentKey: wrapContentKey(leftKeyGuest, staleAt, memoryKey), placementRevision: 0,
        },
      }),
    )
    expect(await isCodedError(stale, HttpStatus.conflict, 'stale_placement'), 'a stale placement revision is 409 stale_placement').toBe(true)
    expect(byId(await listTree(scopeId, epochs), memory.nodeId)?.parentId, 'the Memory stays where the guest moved it').toBe(rightId)

    // A READ link of the same folder: the guest may read, never write.
    const readOnly = await mintShare(scopeId, folderId, folderKey, epochs, FOLDER_NAME, ShareRole.Read)
    const readHead = await resolvePublicShare(readOnly.shareId, b64url(readOnly.kShare))
    const readCtx = guestTreeContext(readHead, { contributorToken: identity.contributorToken, fragmentKey: b64url(readOnly.kShare) })
    const onRead = await failure(createShareNodeAsGuest(readCtx, { parentId: folderId, kind: FOLDER_KIND, name: 'Nur lesen' }))
    expect(onRead instanceof GuestTreeError && onRead.code, 'a READ link').toBe('share_not_contributable')

    // Every value recovered by UNSEALING what the server returned (ADR 0053).
    recordCanaries(byId(afterCreate, createdId)?.name, byId(afterCreate, nestedId)?.name, byId(afterMove, memory.nodeId)?.name)
  }, 240_000)
})
