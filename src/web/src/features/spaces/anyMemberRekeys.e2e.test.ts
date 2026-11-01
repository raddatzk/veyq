// ADR 0111 decision 11 live (VEYQ_E2E=1): after a rotation a plain MEMBER's write into an existing Memory pulls its
// path's re-key forward on its own device and lands, a guest's write into an existing folder waits until a member's
// device re-keyed it, and every member's worker continues the re-key — against a REAL backend + MariaDB + the bundled
// store, with REAL crypto. Three actors on one instance, serialized (`become()` swaps the session and the vault):
//   A = tenant owner, owner of group G — the remover; its device commits the rotation and, as if it stopped right
//       after, re-keys nothing.
//   B = a plain member of G.
//   C = a member of G whom A removes.
//   anonymous = a READ_WRITE guest of a folder link in G, named before the rotation.
//
// What only this layer proves: that the server lets a plain member read, stage and commit a re-key (the owner-only gate
// is gone), that it serves the member's scope row `rekeying`, and that the client's pull, re-read and re-seal meet the
// real server's epoch gate — and, for a medium's meta edit, that the re-key re-sealed the listed tier and raised its
// revision, so the edit's rebuild under the new key meets `409 stale_revision` and re-bases on it (inbox #563). The unit layer (`core/node/rekey/rekeyScope.test.ts`, `features/memories/uploadQueue.test.ts`)
// proves the same steps against an in-memory server.
//
//   python3 scripts/run_web_e2e.py src/web/src/features/spaces/anyMemberRekeys.e2e.test.ts
import { beforeAll, describe, it, expect, vi } from 'vitest'
import { b64url } from '@veyq/crypto/base64'
import { birthInstance, become, mkActor, onboardMember } from '../../test/e2eBootstrap'
import { recordCanaries } from '../../test/canaries'
import { addGroupMember, listScopeMembers, resolveGroupMember } from './members'
import { createGroup, listMyScopes, openScopeById, rotateGroupForRemoval } from './scopes'
import { createFolder, listTree, nodeContentKey } from '../../core/node/tree'
import { FOLDER_KIND } from '../../core/node/kind'
import { assertNodeKeyAtFloor, nodeKeyForWrite } from '../../core/node/rekey/floor'
import { pullRekeyPath } from '../../core/node/rekey/rekeyScope'
import { mintShare } from '../../core/sharing/manageShares'
import { resolvePublicShare, shareRootEpoch } from '../../core/sharing/resolvePublicShare'
import { openShareRoot } from '../../core/sharing/shareTree'
import { createGuestIdentity } from '../../core/sharing/guestIdentity'
import { createShareNodeAsGuest, guestTreeContext } from '../../core/sharing/guestTree'
import { registry } from '../../core/applications/registry'
import { runScopeRotationWorker } from '../tasks/scopeRotationWorker'
import { createMemory } from '../memories/memoryNodes'
import { listMedia, setMediaMeta, unsealMeta } from '../memories/media'
import { enqueueUploads, useUploadQueue } from '../memories/uploadQueue'
import { registerMemoriesLifecycle } from '../../test/memoriesLifecycle'
import { MEMORY_KIND } from '../memories/kinds'
import { rekeyMemoryContent } from '../memories/rekey'
import { ShareRole } from '../../api/generated/sharing'
import { decodeHttpError } from '../../shared/httpError'

// The re-key fails closed on a kind nobody registered: register Memories' real `rekeyContent`, as the rotation suite does.
beforeAll(() => {
  registerMemoriesLifecycle()
  if (!registry.kind(MEMORY_KIND).supported) registry.registerKind({ kind: MEMORY_KIND, icon: 'images', rekeyContent: rekeyMemoryContent })
})

const TENANT_NAME = 'E2E Any Member Rekeys' // declared plaintext — NOT a canary
const GROUP_NAME = 'Wandergruppe Allgaeu'
const FOLDER_NAME = 'Gipfelbuch Nebelhorn'
const MEMORY_NAME = 'Huettenabend Prinz Luitpold'
const MEMORY2_NAME = 'Gipfelkreuz Hochvogel'
const GUEST_NAME = 'Bergfreundin Walburga'
const GUEST_FOLDER = 'Funde vom Grat'

/** Deterministic bytes for one original-only photo. */
function bytes(fill: number, size = 120 * 1024): Uint8Array {
  const out = new Uint8Array(size)
  for (let i = 0; i < out.length; i++) out[i] = (i * 31 + fill) & 0xff
  return out
}

/** How `p` stands after `ms`: `pending`, `landed`, or the refusal it ended in (its HTTP status and code). */
async function standing(p: Promise<unknown>, ms: number): Promise<string> {
  return Promise.race([
    p.then(() => 'landed', async (e: unknown) => {
      const { status, code } = await decodeHttpError(e)
      return `refused ${status} ${code ?? (e as Error)?.message}`
    }),
    new Promise<string>((r) => setTimeout(() => r('pending'), ms)),
  ])
}

describe.skipIf(!process.env.VEYQ_E2E)('any member re-keys (ADR 0111 decision 11, live, real crypto)', () => {
  it('a member\'s photo into an existing Memory lands right after a rotation; a guest waits until a member\'s device re-keyed its folder', async () => {
    // --- A: the instance, two members, a group holding a folder (with a READ_WRITE link) and a Memory ----------------
    const { owner: A, tenant, salt } = await birthInstance(TENANT_NAME)
    const B = await onboardMember(tenant.tenantId, mkActor('b'), salt)
    const C = await onboardMember(tenant.tenantId, mkActor('c'), salt)
    await become(A)
    const gG = await createGroup(GROUP_NAME)
    // A group add carries the grant (ADR 0111 decision 4): B and C hold the group's key from the add on.
    for (const who of [B, C]) {
      const candidate = await resolveGroupMember(gG, who.username)
      await addGroupMember(gG, who.username, candidate.userId, candidate)
    }
    const openedA = (await openScopeById(gG))!
    const folderId = await createFolder(gG, openedA.epochs, FOLDER_NAME)
    const memory = await createMemory(gG, openedA.epochs, MEMORY_NAME)
    const memory2 = await createMemory(gG, openedA.epochs, MEMORY2_NAME)
    const folderKey = nodeContentKey(gG, openedA.epochs, await listTree(gG, openedA.epochs), folderId)!
    const minted = await mintShare(gG, folderId, { key: folderKey, epoch: 0 }, openedA.epochs, FOLDER_NAME, ShareRole.ReadWrite)
    const fragmentKey = b64url(minted.kShare)
    recordCanaries(GROUP_NAME, FOLDER_NAME, MEMORY_NAME, MEMORY2_NAME, GUEST_NAME, GUEST_FOLDER)
    const { batches } = useUploadQueue()

    // --- A: a photo in the second Memory, its time corrected: a listed tier sealed under that Memory's key from before ----
    const key2A = nodeContentKey(gG, openedA.epochs, await listTree(gG, openedA.epochs), memory2.nodeId)!
    enqueueUploads({ scopeId: gG, nodeId: memory2.nodeId, memoryKey: key2A, nodeEpoch: 0 },
      [{ file: new File([bytes(5) as BlobPart], 'kreuz.jpg', { lastModified: 1 }), mediaType: '' }], { label: '0' })
    const photoA = batches.value.at(-1)!.files[0]
    await vi.waitFor(() => expect(['done', 'failed']).toContain(photoA.status), { timeout: 60_000, interval: 250 })
    expect(photoA.status, `A's photo landed (${photoA.error ?? ''})`).toBe('done')
    const [listedA] = await listMedia(gG, memory2.nodeId)
    await setMediaMeta(gG, listedA, key2A, 0, Date.UTC(2024, 6, 1))

    // --- anonymous: named on the link before the rotation ------------------------------------------------------------
    const head = await resolvePublicShare(minted.shareId, fragmentKey)
    const identity = await createGuestIdentity(head, openShareRoot(head).key, { name: GUEST_NAME, fragmentKey, rootEpoch: shareRootEpoch(head.share) })
    const contextNow = async () => guestTreeContext(await resolvePublicShare(minted.shareId, fragmentKey), { contributorToken: identity.contributorToken, fragmentKey })

    // --- A: removes C; the rotation commits epoch 1, and A's device re-keys nothing (it stopped right after) -------------
    await rotateGroupForRemoval(gG, await listScopeMembers(gG), C.userId!)
    expect((await listMyScopes()).find((s) => s.id === gG)?.currentEpoch).toBe(1)

    // --- B, a plain member: its row reads re-keying (served to every member now) -----------------------------------
    await become(B)
    const rowB = (await listMyScopes()).find((s) => s.id === gG)!
    expect(rowB.role, 'B is a plain member').toBe('MEMBER')
    expect(rowB.rekeying, 'a member\'s row carries the unfinished re-key').toBe(true)

    // --- B adds a photo to the Memory right away: the device floor refuses the old key, the pull re-keys the Memory's
    //     path on B's device, and the photo is sealed again under the Memory's key now ----------------------------------
    const openedB = (await openScopeById(gG))!
    const oldKey = nodeContentKey(gG, openedB.epochs, await listTree(gG, openedB.epochs), memory.nodeId)!
    enqueueUploads({
      scopeId: gG, nodeId: memory.nodeId, memoryKey: oldKey, nodeEpoch: 0,
      atFloor: (epoch) => assertNodeKeyAtFloor({ id: memory.nodeId, epoch }, openedB.epochs),
      pull: pullRekeyPath(gG, [memory.nodeId]),
      reread: async () => {
        const at = nodeKeyForWrite(gG, openedB.epochs, await listTree(gG, openedB.epochs), memory.nodeId)!
        return { key: at.key, epoch: at.epoch }
      },
    }, [{ file: new File([bytes(3) as BlobPart], 'gipfel.jpg', { lastModified: 1 }), mediaType: '' }], { label: '1' })
    const file = batches.value.at(-1)!.files[0]
    await vi.waitFor(() => expect(['done', 'failed']).toContain(file.status), { timeout: 60_000, interval: 250 })
    expect(file.status, `the photo landed (${file.error ?? ''})`).toBe('done')
    const afterPull = await listTree(gG, openedB.epochs)
    expect(afterPull.find((n) => n.id === memory.nodeId)?.epoch, 'the Memory was re-keyed by the member').toBe(1)
    expect(afterPull.find((n) => n.id === folderId)?.epoch, 'only the written path: the folder still lags').toBe(0)
    expect(await listMedia(gG, memory.nodeId)).toHaveLength(1)
    expect(afterPull.find((n) => n.id === memory2.nodeId)?.epoch, 'the second Memory still lags').toBe(0)

    // --- B corrects the time of A's photo in the second Memory, as listed before its re-key: the floor refuses the old
    //     key, the pull re-keys that Memory, and the edit is rebuilt under its new key — re-based on the tier the re-key
    //     re-sealed, whose revision it raised (`409 stale_revision`) ----------------------------------------------------
    const key2B = nodeContentKey(gG, openedB.epochs, afterPull, memory2.nodeId)!
    const [listedB] = await listMedia(gG, memory2.nodeId)
    const edited = await setMediaMeta(gG, listedB, key2B, 0, Date.UTC(2024, 6, 2), {
      atFloor: (epoch) => assertNodeKeyAtFloor({ id: memory2.nodeId, epoch }, openedB.epochs),
      pull: pullRekeyPath(gG, [memory2.nodeId]),
      reread: async () => {
        const at = nodeKeyForWrite(gG, openedB.epochs, await listTree(gG, openedB.epochs), memory2.nodeId)!
        return { key: at.key, epoch: at.epoch }
      },
    })
    expect(edited.wrote, 'the edit landed').toBe(true)
    expect(edited.metaRevision, 'A\'s write, the re-key\'s re-seal, then B\'s').toBe((listedB.metaRevision ?? 0) + 2)
    const afterEdit = await listTree(gG, openedB.epochs)
    expect(afterEdit.find((n) => n.id === memory2.nodeId)?.epoch, 'the edit\'s pull re-keyed the second Memory').toBe(1)
    expect(afterEdit.find((n) => n.id === folderId)?.epoch, 'and nothing else: the folder still lags').toBe(0)

    // --- anonymous: a write into the folder waits while it lags ... -------------------------------------------------
    const guestWrite = createShareNodeAsGuest(await contextNow(), { parentId: folderId, kind: FOLDER_KIND, name: GUEST_FOLDER })
    expect(await standing(guestWrite, 1_000), 'the guest waits: it holds no key above its root to re-key with').toBe('pending')

    // --- ... until B's worker continues the re-key in the background (no job to claim: the row says re-keying) ---------
    await runScopeRotationWorker()
    const created = await guestWrite
    const afterResume = await listTree(gG, openedB.epochs)
    expect(afterResume.every((n) => n.epoch === 1), 'every node re-keyed by the member\'s worker').toBe(true)
    expect(afterResume.find((n) => n.id === created.nodeId)?.name, 'the guest\'s folder landed under the new key').toBe(GUEST_FOLDER)
    expect((await listMyScopes()).find((s) => s.id === gG)?.rekeying).toBe(false)

    // --- A reads what B and the guest added, under the new keys ------------------------------------------------------
    await become(A)
    const openedA2 = (await openScopeById(gG))!
    const treeA = await listTree(gG, openedA2.epochs)
    expect(treeA.map((n) => n.name).sort()).toEqual([GUEST_FOLDER, FOLDER_NAME, MEMORY_NAME, MEMORY2_NAME].sort())
    expect(await listMedia(gG, memory.nodeId)).toHaveLength(1)
    const key2Now = nodeContentKey(gG, openedA2.epochs, treeA, memory2.nodeId)!
    const [photoNow] = await listMedia(gG, memory2.nodeId)
    expect(unsealMeta(gG, key2Now, photoNow)?.capturedAtOverride, 'B\'s corrected time, under the new key').toBe(Date.UTC(2024, 6, 2))
  }, 180_000)
})
