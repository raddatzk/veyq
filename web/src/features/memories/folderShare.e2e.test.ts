// cr-node-key-hierarchy 5b.3 live e2e (VEYQ_E2E=1) — a member shares a FOLDER (ADR 0080 decisions 6, 9 and 12), against
// a REAL backend + MariaDB + the bundled store, with REAL crypto. The member side only: the guest's folder page and its
// READ_WRITE operations are 5b.4.
//
// Two actors on one instance, serialized (the `chapterShare.e2e` shape):
//   A          = curator: server owner + tenant owner (from `birthInstance`); a folder holding a Memory (with a photo)
//                and a subfolder, and a Memory beside the folder that the link must not reach.
//   anonymous  = a guest with NO session — the resolve goes through `publicSharesApi()` (core's link), which carries
//                no bearer.
//
// What only this layer can prove: that the server accepts a FOLDER as the root of a READ link minted by core's own
// `mintShare` with the folder's key from the chain (the key the member's folder dialog is handed), roots what it serves
// at that folder, and that a guest holding only the link opens the descendants' names through the platform's own walk
// (`openShareRoot` + `walkSubtree`) — and that the server refuses a WRITE_ONLY link on a folder. The absences are
// asserted on the raw wire (`…Raw(…).raw.json()`), because the generated client drops a field the spec does not name
// (web/CLAUDE.md). The unit layer (`core/sharing/folderShare.wire.test.ts`) proves the request against a recorded fetch.
//
//   python3 scripts/run_web_e2e.py web/src/features/memories/folderShare.e2e.test.ts
import { describe, it, expect } from 'vitest'
import * as v1 from '@veyq/crypto/v1'
import { b64std, b64url } from '@veyq/crypto/base64'
import { publicSharesApi, sharesApi } from '../../api/client'
import { publicShareMediaApi } from './api'
import { HttpStatus, isCodedError } from '../../shared/httpError'
import { birthInstance } from '../../test/e2eBootstrap'
import { recordCanaries } from '../../test/canaries'
import { ensurePersonalScope, listMyScopes, openPersonalScope } from '../spaces/scopes'
import { epochsOf } from '../spaces/epochsTestUtil'
import { createFolder, listTree, nodeContentKey } from '../../core/node/tree'
import { createMemory } from './memoryNodes'
import { addMedia } from './media'
import { mintShare, newShareId } from '../../core/sharing/manageShares'
import { resolvePublicShare, SHARE_MEMORY_NAME } from '../../core/sharing/resolvePublicShare'
import { openShareRoot, walkSubtree } from '../../core/sharing/shareTree'
import { ScopeKind } from '../../api/generated/scopes'
import { ShareRole } from '../../api/generated/sharing'

// Distinctive plaintext for the wire-assertion sweep (ADR 0053) — distinct from the declared tenant name.
const TENANT_NAME = 'E2E Folder Share' // declared plaintext (veyq_tenant.name) — NOT a canary
const FOLDER_NAME = 'Familienarchiv Kowalczyk'
const MEMORY_NAME = 'Sommerfest Gartenlaube'
const SUB_NAME = 'Briefe Urgrossmutter'
const OUTSIDE_NAME = 'Steuerordner Privat'
const GATED_NOTE = 'Bitte zuerst lesen: nur fuer die Familie Kowalczyk'

/** Deterministic bytes, a different fill per photo. */
function bytes(fill: number, size = 200 * 1024): Uint8Array {
  const out = new Uint8Array(size)
  for (let i = 0; i < out.length; i++) out[i] = (i * 23 + fill) & 0xff
  return out
}
const photo = (fill: number) => ({ blob: new Blob([bytes(fill) as BlobPart]), mediaType: '' }) // original-only

type RawResolve = { rootKind?: string; nodeId?: string; wrap?: string | null; nodes: Record<string, unknown>[] }

describe.skipIf(!process.env.VEYQ_E2E)('folder share e2e (live, real crypto, the link is rooted at the folder)', () => {
  it('a READ link on a folder serves the folder\'s subtree; the guest opens its descendants\' names; WRITE_ONLY is refused', async () => {
    // --- A: cold-birth the instance; a folder with a Memory (a photo) and a subfolder; a Memory beside it --------
    await birthInstance(TENANT_NAME)
    await ensurePersonalScope()
    const personal = (await listMyScopes()).find((s) => s.kind === ScopeKind.Personal)
    expect(personal, 'the personal scope was born').toBeDefined()
    const scopeId = personal!.id
    const scopeKey = (await openPersonalScope())!
    const epochs = epochsOf(scopeKey)
    const folderId = await createFolder(scopeId, epochs, FOLDER_NAME)
    // The folder's key exactly as the member's shell finds it: the tree as listed, the chain walked (MemoryHomeView).
    const folderKey = nodeContentKey(scopeId, epochs, await listTree(scopeId, epochs), folderId)
    expect(folderKey, "the folder's key opens through the chain").not.toBeNull()
    const memory = await createMemory(scopeId, epochs, MEMORY_NAME, { id: folderId, key: folderKey! })
    const inMemory = await addMedia(scopeId, memory.nodeId, memory.memoryKey, photo(1))
    const subId = await createFolder(scopeId, epochs, SUB_NAME, { id: folderId, key: folderKey! })
    const outside = await createMemory(scopeId, epochs, OUTSIDE_NAME)

    // --- A: a READ link of the folder, through core's mint (what FolderShareDialog calls) --------------------------
    const minted = await mintShare(scopeId, folderId, folderKey!, epochs, FOLDER_NAME, ShareRole.Read)
    const shareId = minted.shareId

    // --- anonymous: the link opens the folder under its name; the walk opens the descendants' names --------------
    const head = await resolvePublicShare(shareId, b64url(minted.kShare))
    expect(head.rootKind, 'the server names the folder as the root kind').toBe('FOLDER')
    expect(head.role).toBe(ShareRole.Read)
    expect(head.name, "the guest reads the folder's name under K_share").toBe(FOLDER_NAME)
    const root = openShareRoot(head)
    expect(Array.from(root.key), "the link's wrap opens to the folder's own key").toEqual(Array.from(folderKey!))
    const walked = walkSubtree(head.scopeId, head.share.nodes, root.key)
    expect(walked.names.get(memory.nodeId), "the Memory's name opens under the folder's key").toBe(MEMORY_NAME)
    expect(walked.names.get(subId), "the subfolder's name opens under the folder's key").toBe(SUB_NAME)
    expect(Array.from(walked.keys.get(memory.nodeId) ?? []), "and the Memory's own key with it").toEqual(Array.from(memory.memoryKey))
    expect(walked.names.has(folderId), 'the root has no entry: its title is the share name').toBe(false)

    // --- the raw wire: rooted at the folder, nothing of its parent or of the Memory beside it --------------------
    const wire = (await (await publicSharesApi().resolveShareRaw({ shareId })).raw.json()) as RawResolve
    expect(wire.rootKind).toBe('FOLDER')
    expect(wire.nodes.map((n) => n.id).sort(), 'the resolved subtree is the folder and what is in it').toEqual([folderId, memory.nodeId, subId].sort())
    const rootRow = wire.nodes.find((n) => n.id === folderId)!
    expect(rootRow.parentId ?? null, "the root's parent is not served").toBeNull()
    expect(rootRow.wrappedContentKey ?? null, "nor its wrap under K_SC (its entry)").toBeNull()
    expect(rootRow.encryptedName ?? null, "nor its name under K_SC (its entry)").toBeNull()
    expect(JSON.stringify(wire).includes(outside.nodeId), 'the Memory beside the folder appears nowhere').toBe(false)
    // The role matrix (docs/apps/memories.md): a READ folder link reads the Memories below it with their media.
    const mediaWire = (await (await publicShareMediaApi().listShareMediaRaw({ shareId })).raw.json()) as { media: { mediaId: string }[] }
    expect(mediaWire.media.map((m) => m.mediaId), "the Memory's photo below the folder is served").toContain(inMemory.mediaId)

    // --- A: a WRITE_ONLY link on a folder is refused by the server (no application writes under a folder) --------
    // `mintShare` refuses a WRITE_ONLY role client-side before posting, so the request is built by hand here: well
    // formed for its role (no wrap, a sealed drop public key, a recovery copy at the current epoch).
    const kShare = v1.randomBytes(32)
    const writeOnlyId = newShareId()
    const refused = await sharesApi()
      .createShare({
        createShareRequest: {
          shareId: writeOnlyId, nodeId: folderId, role: ShareRole.WriteOnly,
          sealedDropPublicKey: b64std(v1.randomBytes(48)),
          sealedName: b64std(v1.sealField(SHARE_MEMORY_NAME, kShare, v1.utf8(FOLDER_NAME))),
          sealedShareKey: b64std(v1.randomBytes(72)), sealedShareKeyEpoch: epochs.current,
          writeVerifier: b64std(v1.sha256Digest(v1.randomBytes(32))),
        },
      })
      .then(() => null, (e: unknown) => e)
    expect(refused, 'the WRITE_ONLY mint failed').not.toBeNull()
    expect(await isCodedError(refused, HttpStatus.unprocessable, 'illegal_scope'), 'a WRITE_ONLY folder link is 422 illegal_scope').toBe(true)

    // --- uc.sharing.share-note: a note that asks to be accepted carries its switch INSIDE the seal ---------------
    expect(head.noteAcceptance, 'a link without a note asks nothing').toBeUndefined()
    const asked = await mintShare(scopeId, folderId, folderKey!, epochs, FOLDER_NAME, ShareRole.Read, null, { texts: { en: GATED_NOTE }, mustAccept: true })
    const gated = await resolvePublicShare(asked.shareId, b64url(asked.kShare))
    expect(gated.noteByLocale, 'the guest opens the note with the fragment key').toEqual({ en: GATED_NOTE })
    expect(gated.noteAcceptance?.digest, 'and learns from inside the seal that it asks to be accepted').toMatch(/^[A-Za-z0-9_-]{43}$/)
    const gatedWire = await (await publicSharesApi().resolveShareRaw({ shareId: asked.shareId })).raw.json()
    expect(JSON.stringify(gatedWire), 'the switch is no field of its own on the wire').not.toMatch(/mustAccept/)

    // Every value recovered by UNSEALING what the server returned (ADR 0053).
    recordCanaries(head.name, walked.names.get(memory.nodeId), walked.names.get(subId), gated.noteByLocale)
  }, 240_000)
})
