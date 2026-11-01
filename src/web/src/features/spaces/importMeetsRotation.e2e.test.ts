// A rotation landing WHILE an import or a copy runs, live (VEYQ_E2E=1, inbox #701): a member imports a download into the
// group it has entered - the active scope, so the run's re-reads go through `activeSpace` and `treeStore` as in the app -
// and then copies what it imported, while the group's owner rotates the group twice under each run, against a REAL
// backend + MariaDB + the bundled store, with REAL crypto. Three actors on one instance, serialized (`become()` swaps the
// session and the vault, never the active scope):
//   A = tenant owner and owner of group G; rotates G mid-run (once only committing, once re-keying every node too).
//   B = a plain member of G; enters G, imports a Memory with a chapter into G's folder, then copies that Memory beside it.
//   C = a plain member of G who does nothing; reads the result.
//
// The rotations are timed on the wire: a medium's completion (`POST …/media-batch`, the one write that states the
// Memory's epoch) is held back while A rotates, before the run's second and its fourth completion - so each lands between
// two of the run's writes, after the medium's blobs were uploaded under the key the run held:
//   1. A commits the next epoch and re-keys nothing. B's device still holds the old one; the completion states the
//      Memory's epoch from before and is refused per item `node_rekey_pending`: B's device pulls the Memory's path
//      forward, re-opens G at the new epoch and uploads that medium again under the Memory's new key.
//   2. A commits the next epoch and its device re-keys every node. B's completion states the epoch B's pull left, which
//      the Memory no longer has: per item `stale_epoch`. B's pull re-keys nothing, re-opens G, and the medium is uploaded
//      again under the key A's re-key gave the Memory.
// The chapter below the Memory is created after both, at the newest epoch, under the key the run's writer last landed
// under. The copy then meets the same two rotations reading its media from the Memory the import made, by frame, while
// A's second rotation re-keys that source too.
//
// What only this layer proves: that the real server's per-item refusals reach the run's writer as the refusals it acts on
// (the `stale_epoch` one did not: a bare `Error`, the run rolled back - found by this suite), that the pull and the
// re-read through the active scope open what a real re-key wrote, and that every node and every medium - its original's
// bytes and its sealed facts, the corrected capture time and the place name restored at birth (inbox #700) included -
// ends at the newest epoch, readable by every member. The unit layer (`core/export/importSubtree.midRun.test.ts`) proves
// the same steps against an in-memory epoch gate, and the other writes a run makes (a cover, a banner, a Pot, a file).
//
//   VEYQ_INFRA_DIR=<main checkout>/infra/docker VEYQ_JAR=<jar> python3 scripts/run_web_e2e.py \
//     src/web/src/features/spaces/importMeetsRotation.e2e.test.ts
import { afterAll, beforeAll, describe, it, expect } from 'vitest'
import { birthInstance, become, mkActor, onboardMember, type Actor } from '../../test/e2eBootstrap'
import { recordCanaries } from '../../test/canaries'
import { registry } from '../../core/applications/registry'
import { registerMemoriesLifecycle } from '../../test/memoriesLifecycle'
import { CHAPTER_KIND, MEMORY_KIND } from '../memories/kinds'
import { rekeyMemoryContent } from '../memories/rekey'
import {
  CHAPTER_CONTENT, MEMORIES_CONTENT_SCHEMA, MEMORY_CONTENT, exportMemoryContent, importMemoryContent, validateMemoryContent,
} from '../memories/exportImport'
import { listMedia, openOriginal, unsealMeta } from '../memories/media'
import { addGroupMember, rekeySpace, resolveGroupMember } from './members'
import { createGroup, listMyScopes, openScopeById } from './scopes'
import { activeEpochKeys, activeSpace, clearActiveSpace, enterScope } from '../../app/activeSpace'
import { stopGrantDeliveryPolling } from '../tasks/grantDeliveryPolling'
import { stopScopeRotationPolling } from '../tasks/scopeRotationPolling'
import { loadTree, treeStore } from '../../core/node/treeStore'
import { createFolder, listTree, nodeContentKey } from '../../core/node/tree'
import { FOLDER_KIND } from '../../core/node/kind'
import { rekeyScope } from '../../core/node/rekey/rekeyScope'
import { memberAccess, memberTreeSource } from '../../core/access'
import { replaySubtrees, type PayloadResolver } from '../../core/export/importSubtree'
import { prepareCopy } from '../../core/export/copySubtree'
import { MANIFEST_FORMAT, MANIFEST_VERSION, type Manifest, type ManifestNode } from '../../core/export/manifest'

// Registered as Memories' `register.ts` does for what an export walk, an import and a re-key ask of a kind; a
// node-environment suite gets no registry from `setup.ts`.
beforeAll(() => {
  registerMemoriesLifecycle()
  for (const kind of [MEMORY_KIND, CHAPTER_KIND]) {
    if (registry.kind(kind).supported) continue
    registry.registerKind({
      kind, icon: 'images', contentSchema: MEMORIES_CONTENT_SCHEMA, validateContent: validateMemoryContent,
      exportContent: exportMemoryContent, importContent: importMemoryContent(kind), importStreams: true, rekeyContent: rekeyMemoryContent,
      placement: kind === MEMORY_KIND ? { root: true, parents: [FOLDER_KIND] } : { root: false, parents: [MEMORY_KIND] },
    })
  }
})
// The scope this suite entered holds the epoch keys and two background polls: dropped whatever the outcome.
afterAll(() => clearActiveSpace())

const GROUP_NAME = 'Segelclub Chiemsee'
const FOLDER_NAME = 'Regatten am Chiemsee'
const MEMORY_NAME = 'Fraueninsel Herbstregatta'
const COPY_NAME = `${MEMORY_NAME} (2)`
const CHAPTER_NAME = 'Zieleinlauf Gstadt'
const PLACE = 'Prien am Chiemsee'
const CORRECTED_AT = '2024-09-21T14:30:00.000Z'
const MEDIUM_TIME = Date.UTC(2024, 8, 21, 9) // each payload's modified time, the capture time an uncorrected medium keeps

/** Deterministic bytes for one original-only photo (no decoder in this process, so no derived tiers). */
function bytes(fill: number, size = 96 * 1024): Uint8Array {
  const out = new Uint8Array(size)
  for (let i = 0; i < out.length; i++) out[i] = (i * 37 + fill) & 0xff
  return out
}
const PHOTOS = { 'p1.jpg': bytes(1), 'p2.jpg': bytes(2), 'p3.jpg': bytes(3), 'p4.jpg': bytes(4) } as const
type PhotoName = keyof typeof PHOTOS
const medium = (name: PhotoName, extra: Record<string, unknown> = {}) => ({
  file: { path: `${MEMORY_NAME}/${name}`, size: PHOTOS[name].length, mediaType: 'image/jpeg', modifiedAt: MEDIUM_TIME }, name, ...extra,
})

/**
 * The run's medium completions (`completeMedia`, `POST …/media-batch`): `before[n]` runs before the n-th of them is sent
 * (1-based), and each one's per-item answer is recorded (its `error`, null when the medium landed).
 */
function onCompletions(before: Record<number, () => Promise<void>>): { codes: (string | null)[]; restore: () => void } {
  const codes: (string | null)[] = []
  const inner = globalThis.fetch
  let sent = 0
  globalThis.fetch = async (input, init) => {
    const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url
    const completion = url.endsWith('/media-batch')
    if (completion) await before[++sent]?.()
    const res = await inner(input, init)
    if (completion && res.ok) codes.push(((await res.clone().json()) as { results: { error?: string | null }[] }).results[0]?.error ?? null)
    return res
  }
  return { codes, restore: () => void (globalThis.fetch = inner) }
}

describe.skipIf(!process.env.VEYQ_E2E)('an import and a copy meet a rotation mid-run (inbox #701, ADR 0111 decision 11, live, real crypto)', () => {
  it('a member\'s import and copy into its active group land at the newest epoch while the owner rotates twice under each', async () => {
    // --- A: the instance, two members, group G holding a folder ---------------------------------------------------
    const { owner: A, tenant, salt } = await birthInstance('E2E Import Meets Rotation')
    const B = await onboardMember(tenant.tenantId, mkActor('b'), salt)
    const C = await onboardMember(tenant.tenantId, mkActor('c'), salt)
    recordCanaries(GROUP_NAME, FOLDER_NAME, MEMORY_NAME, CHAPTER_NAME, PLACE)
    await become(A)
    const gG = await createGroup(GROUP_NAME)
    for (const who of [B, C]) {
      const candidate = await resolveGroupMember(gG, who.username)
      await addGroupMember(gG, who.username, candidate.userId, candidate)
    }
    const folderId = await createFolder(gG, (await openScopeById(gG))!.epochs, FOLDER_NAME)

    // --- B enters G, as the shell does: the active scope at epoch 0, its tree loaded ------------------------------
    await become(B)
    await enterScope(`@${gG}`)
    // The background polls would run the rotation worker under whichever actor `become()` left signed in: this suite
    // times every re-key itself.
    stopScopeRotationPolling()
    stopGrantDeliveryPolling()
    expect([activeSpace.status, activeSpace.scopeId, activeEpochKeys()?.current]).toEqual(['ready', gG, 0])

    /** A rotates G (and with `rekeyNodes` re-keys every node on its device), then B is signed in again; what it
     *  committed and where the run's Memory `name` stood after it. */
    const rotations: { epoch: number; memoryEpoch: number }[] = []
    const rotateAsA = (name: string, rekeyNodes: boolean) => async () => {
      await become(A)
      const epoch = await rekeySpace(gG)
      if (rekeyNodes) expect((await rekeyScope(gG)).done, 'A\'s device re-keyed every node').toBe(true)
      const memory = (await listTree(gG, (await openScopeById(gG))!.epochs)).find((n) => n.name === name)!
      rotations.push({ epoch, memoryEpoch: memory.epoch })
      await become(B)
    }
    /** The folder as B's device holds it now: the parent a run is started under. */
    const folderNow = async () => {
      await loadTree()
      const at = treeStore.nodes.find((n) => n.id === folderId)!
      return { id: folderId, key: nodeContentKey(gG, activeEpochKeys()!, treeStore.nodes, folderId)!, epoch: at.epoch }
    }

    /** Every member holds `epoch`, opens every node at it and every medium of each Memory in `memories` and of its chapter:
     *  the original's bytes, its listed tier, p2's restored facts. */
    async function readsEverything(members: Actor[], epoch: number, memories: string[]): Promise<void> {
      for (const who of members) {
        await become(who)
        const held = (await openScopeById(gG))!
        expect(held.epochs.current, `${who.username} holds epoch ${epoch}`).toBe(epoch)
        const tree = await listTree(gG, held.epochs)
        const expected = [[FOLDER_NAME, epoch], ...memories.flatMap((m) => [[m, epoch], [CHAPTER_NAME, epoch]])]
        expect(tree.map((n) => [n.name, n.epoch]).sort(), `${who.username} opens every node at epoch ${epoch}`).toEqual(expected.sort())
        for (const memoryName of memories) {
          const memory = tree.find((n) => n.name === memoryName)!
          const chapterNode = tree.find((n) => n.parentId === memory.id)!
          for (const [node, photos] of [[memory, ['p1.jpg', 'p2.jpg', 'p3.jpg']], [chapterNode, ['p4.jpg']]] as const) {
            const key = nodeContentKey(gG, held.epochs, tree, node.id)!
            const items = await listMedia(gG, node.id)
            expect(items, `${memoryName}/${node.name}: one medium per photo, none twice`).toHaveLength(photos.length)
            const opened = await Promise.all(items.map(async (item) => ({ bytes: await openOriginal(gG, key, item), meta: unsealMeta(gG, key, item) })))
            for (const name of photos) {
              const hit = opened.find((o) => o.bytes.length === PHOTOS[name].length && o.bytes.every((b, i) => b === PHOTOS[name][i]))
              expect(hit, `${who.username} opens ${memoryName}'s ${name}`).toBeDefined()
              expect(hit!.meta?.capturedAt, `${name}'s listed tier opens`).toBe(MEDIUM_TIME)
              if (name === 'p2.jpg') expect([hit!.meta?.capturedAtOverride, hit!.meta?.placeName]).toEqual([Date.parse(CORRECTED_AT), PLACE])
            }
          }
        }
      }
      expect((await listMyScopes()).find((s) => s.id === gG)?.rekeying, 'no node lags').toBe(false)
    }

    // --- the download B imports: a Memory of three photos (the second's time corrected and placed), a chapter of one --
    const chapter: ManifestNode = {
      id: 'c', name: CHAPTER_NAME, kind: CHAPTER_KIND, content: { type: CHAPTER_CONTENT, schema: 1, media: [medium('p4.jpg')] },
    }
    const root: ManifestNode = {
      id: 'm', name: MEMORY_NAME, kind: MEMORY_KIND, children: [chapter],
      content: {
        type: MEMORY_CONTENT, schema: 1,
        media: [medium('p1.jpg'), medium('p2.jpg', { capturedAt: CORRECTED_AT, capturedAtCorrected: true, location: { place: PLACE } }), medium('p3.jpg')],
      },
    }
    const manifest = { format: MANIFEST_FORMAT, version: MANIFEST_VERSION, exportedAt: '2026-10-29T00:00:00Z', appVersion: '1', root } as Manifest
    const resolves: Record<string, number> = {}
    const payload: PayloadResolver = async (ref, fileName) => {
      const name = ref.path.split('/').pop() as PhotoName
      resolves[name] = (resolves[name] ?? 0) + 1
      return new File([PHOTOS[name] as BlobPart], fileName, { type: ref.mediaType, lastModified: MEDIUM_TIME })
    }

    // --- B's import runs; A rotates before its 2nd and its 4th completion ---------------------------------------------
    const imported = onCompletions({ 2: rotateAsA(MEMORY_NAME, false), 4: rotateAsA(MEMORY_NAME, true) })
    const made = await replaySubtrees({
      scopeId: gG, epochs: activeEpochKeys()!, tree: treeStore.nodes, parent: await folderNow(), items: [{ manifest, payload }],
    }).finally(imported.restore)
    expect(made.nodes).toBe(2)
    // the rotations were met mid-run: the first left the Memory behind, the second's re-key moved it past B's key
    expect(rotations).toEqual([{ epoch: 1, memoryEpoch: 0 }, { epoch: 2, memoryEpoch: 2 }])
    expect(imported.codes, 'p1, p2 refused, p2 again, p3 refused, p3 again, p4').toEqual([null, 'node_rekey_pending', null, 'stale_epoch', null, null])
    expect(resolves, 'each refused medium was uploaded again, nothing else').toEqual({ 'p1.jpg': 1, 'p2.jpg': 2, 'p3.jpg': 2, 'p4.jpg': 1 })
    expect(activeEpochKeys()?.current, 'B\'s device re-opened G at each epoch it met').toBe(2)
    await readsEverything([A, B, C], 2, [MEMORY_NAME])

    // --- B copies the Memory it imported beside it; A rotates under the copy as under the import ----------------------
    await become(B)
    await loadTree()
    const prepared = await prepareCopy({
      access: memberAccess({
        scopeId: gG, epochs: activeEpochKeys(), readonly: false, applicationsOff: new Set(),
        publicShares: false, publicSharesContribute: false, publicSharesDropBox: false,
      }),
      tree: memberTreeSource({ nodes: () => treeStore.nodes, scopeId: () => gG, epochs: () => activeEpochKeys(), scope: () => `@${gG}` }),
      rootIds: [made.nodeIds[0]!],
      appVersion: '1',
    })
    expect([prepared.nodes, prepared.leftOut]).toEqual([2, []])
    rotations.length = 0
    const copied = onCompletions({ 2: rotateAsA(COPY_NAME, false), 4: rotateAsA(COPY_NAME, true) })
    const copy = await prepared.run({ scopeId: gG, epochs: activeEpochKeys()!, tree: treeStore.nodes, parent: await folderNow() })
      .finally(copied.restore)
    expect(copy.nodes).toBe(2)
    expect(rotations).toEqual([{ epoch: 3, memoryEpoch: 2 }, { epoch: 4, memoryEpoch: 4 }])
    expect(copied.codes, 'the copy\'s media: the same two refusals, each met once').toEqual([null, 'node_rekey_pending', null, 'stale_epoch', null, null])
    expect(activeEpochKeys()?.current).toBe(4)
    await readsEverything([A, B, C], 4, [MEMORY_NAME, COPY_NAME])

  }, 480_000)
})
