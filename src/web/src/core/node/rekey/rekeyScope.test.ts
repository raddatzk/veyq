// A scope's re-key (ADR 0109) through the REAL crypto: no seal is mocked, only the transport — a small in-memory server
// that serves the rows and applies a commit as the backend's `RekeyStore.commit` does (every staged value swapped in,
// the node's own epoch +1, its `scopeEpoch` the commit's, its due mark cleared). The world is built at epoch 0 with the
// real seals, then the scope rotated to 1 as ADR 0115 does it: a fresh key E_1 replaced E_0 (no chain — a member's
// device holds only E_1), the rotation's commit re-sealed every scope-root entry and recovery copy under E_1 (each entry
// still bound to the scope epoch its node's key was minted under) and marked every node due; then `rekeyScope` runs.
// Each uc.spaces.rekey-after-rotation criterion is a negative with its positive control on the same key.
//
// ADR 0111 decision 11 on the same server: a member's run leaves the nodes only an owner re-keys (`ownerOnly`, which the
// server derives as the backend's `OwnerOnlyRekey` does), and a member's write pulls its own path forward through
// `pullRekeyPath` — the same per-node commit, on the written node and its lagging ancestors only — then retries at once.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import * as v1 from '@veyq/crypto/v1'
import { b64std, b64url, fromB64std } from '@veyq/crypto/base64'
import { SHARE_BOOKMARK_KIND } from '../kind'
import { keptLink } from '../../bookmark/bookmarkLink'
import { bookmarkMetaObject, bookmarkOf } from '../../bookmark/bookmarkMeta'
import { shareBookmarkKind } from '../../bookmark/kind'
import { registry } from '../../applications/registry'
import type { ScopeKey } from '../../../features/spaces/epochs'
import { blobKeyContext, unwrapBlobKey } from '../../blob/keys'
import { SHARE_KEY } from '../../sharing/shareTree'
import { mintIdentityKey, openDropKey, openDropPublicKey, openIdentityKey } from '../../sharing/dropKey'
import { identityNameContext } from '../../sharing/guestIdentity'
import { sealNodeMeta, unsealNodeMeta } from '../nodeMeta'
import { fakeApi } from '../../../test/apiMock'
import type {
  CommitNodeRekeyRequest, RekeyBlobWrap, RekeyChildEntry, RekeyIdentitySeal, RekeyShareRoleEnum, RekeyShareWrap,
} from '../../../api/generated/content'
import { contentKeyChain, openContentKey, openName, openTree, sealName, slugTokenFor, wrapContentKey, type NodeAddress } from '../tree'

const SCOPE = '7c3f0e2a-9b1d-4a6e-8b2c-1f2d3e4a5b6c'

// --- the in-memory server ---------------------------------------------------------------------------------------
type Row = {
  id: string; parentId: string | null; kind: string; trashed: boolean; epoch: number; scopeEpoch: number; due: boolean
  placementRevision: number; metaRevision: number
  encryptedName: string; slugToken: string; wrappedContentKey: string; wrappedMeta: string | null; sealedDropPrivateKey: string | null
  shareIdentityId: string | null; wrappedIdentityKey: string | null
}
type Share = { shareId: string; nodeId: string; role: RekeyShareRoleEnum; sealedShareKey: string; wrappedRecoveryValueKey: string; sealedShareKeyEpoch: number; wrap: string | null; sealedDropPublicKey: string | null }
type Identity = { identityId: string; shareId: string; wrappedIdentityKey: string | null; sealedDisplayName: string }
/** A blob of the node: an attachment slot's (bound to the node), or — with [ownerId] — one its application stores through
 *  core's blob service (ADR 0113), bound to the id the server lists it with. */
type Blob = { blobId: string; nodeId: string; role: string; wrappedBlobKey: string; ownerId?: string }
type Stage = { children: RekeyChildEntry[]; blobs: RekeyBlobWrap[]; shares: RekeyShareWrap[]; identities: RekeyIdentitySeal[] }
const server = {
  current: 1,
  nodes: new Map<string, Row>(),
  shares: [] as Share[],
  identities: [] as Identity[],
  blobs: [] as Blob[],
  stages: new Map<string, Stage>(),
  crashOnCommitOf: null as string | null,
  epochs: null as ScopeKey | null,
  /** The caller's role in the scope, as its row says: an owner re-keys every node, a member all but the owner-only ones. */
  role: 'OWNER' as 'OWNER' | 'MEMBER',
  /** The nodes whose kind the server's applications declare a drop box (Depot's `DEPOT_DROPBOX`); this core-only world
   *  keeps every node a `FOLDER`, so the server's answer stands in for the kind. */
  dropBoxes: new Set<string>(),
  /** Every commit, in order: who re-keyed what. */
  commits: [] as string[],
  /** Each commit's request, by node: what it stated. */
  commitBodies: new Map<string, CommitNodeRekeyRequest>(),
  /** Each staged list's `fromEpoch`, by node. */
  stagedFrom: new Map<string, number | null>(),
  /** The links a commit closed as key-lost, in order. */
  closedLost: [] as string[],
  /** Every share entry staged, in order. */
  stagedShares: [] as RekeyShareWrap[],
}

/** The backend's `OwnerOnlyRekey`: a drop box (by its kind, which an application declares — a drop key alone is not
 *  one), a guest's identity folder, or below one. The client never derives it; it reads `ownerOnly`. */
function ownerOnly(n: Row): boolean {
  if (server.dropBoxes.has(n.id)) return true
  for (let cur: Row | undefined = n; cur; cur = cur.parentId ? server.nodes.get(cur.parentId) : undefined) if (cur.shareIdentityId) return true
  return false
}

vi.mock('../../../app/sessionRefresh', () => ({ withRefresh: (f: () => unknown) => f() }))
vi.mock('../../../features/spaces/scopes', () => ({ openScopeById: vi.fn() }))
vi.mock('../../../api/client', () => ({ rekeyApi: vi.fn(), applicationBlobsApi: vi.fn() }))
vi.mock('../../../app/activeSpace', () => ({ activeSpace: { scopeId: null }, activeEpochKeys: () => null, refreshActiveScope: vi.fn() }))

/** The in-memory server behind `rekeyApi` — it applies a commit as `RekeyStore.commit` does — and the member's scope
 *  as `openScopeById` opens it; installed per test. */
function installServer(): void {
  vi.mocked(openScopeById).mockImplementation(async () => {
    const ek = server.epochs!
    // only the scope key and the caller's role are read by the driver (a copy: the run zeroes it); the rest of an opened
    // scope is not this test's
    return { role: server.role, epochs: { epoch: ek.epoch, key: ek.key.slice() } } as unknown as Awaited<ReturnType<typeof openScopeById>>
  })
  fakeApi(rekeyApi, {
    listRekeyNodes: async () => ({ currentEpoch: server.current, nodes: [...server.nodes.values()].map((n) => ({ ...n, ownerOnly: ownerOnly(n) })) }),
    getNodeRekeyParts: async ({ nodeId }) => {
      const shares = server.shares.filter((s) => s.nodeId === nodeId)
      return {
        blobs: server.blobs.filter((b) => b.nodeId === nodeId && !b.ownerId).map(({ blobId, role, wrappedBlobKey }) => ({ blobId, role, wrappedBlobKey })),
        shares: shares.map((s) => ({ shareId: s.shareId, role: s.role, sealedShareKey: s.sealedShareKey, wrappedRecoveryValueKey: s.wrappedRecoveryValueKey, sealedShareKeyEpoch: s.sealedShareKeyEpoch, hasWrap: s.wrap !== null, hasDropPublicKey: s.sealedDropPublicKey !== null })),
        identities: server.identities.filter((i) => shares.some((s) => s.shareId === i.shareId)).map((i) => ({
          identityId: i.identityId, shareId: i.shareId, wrappedIdentityKey: i.wrappedIdentityKey, sealedDisplayName: i.wrappedIdentityKey ? null : i.sealedDisplayName,
        })),
      }
    },
    stageNodeRekey: async ({ nodeId, rekeyId, nodeRekeyStage: s }) => {
      server.stagedFrom.set(nodeId, s.fromEpoch)
      server.stagedShares.push(...(s.shares ?? []))
      const st = server.stages.get(rekeyId) ?? { children: [], blobs: [], shares: [], identities: [] }
      st.children.push(...(s.children ?? [])); st.blobs.push(...(s.blobs ?? [])); st.shares.push(...(s.shares ?? [])); st.identities.push(...(s.identities ?? []))
      server.stages.set(rekeyId, st)
    },
    commitNodeRekey: async ({ nodeId, rekeyId, commitNodeRekeyRequest: c }) => {
      if (server.crashOnCommitOf === nodeId) throw new Error('the device stopped')
      const node = server.nodes.get(nodeId)!
      if (server.role !== 'OWNER' && ownerOnly(node)) throw refusal(403, 'not_scope_owner')
      // the backend's gates (ADR 0114 decisions 3-4): the node's own epoch as stored, the scope's current epoch, the
      // parent not due and stated at its own epoch
      if (c.nodeEpoch !== node.epoch || c.scopeEpoch !== server.current) throw refusal(409, 'stale_epoch')
      const parent = node.parentId ? server.nodes.get(node.parentId)! : null
      if (parent?.due) throw refusal(409, 'node_rekey_pending')
      if (parent && c.parentEpoch !== parent.epoch) throw refusal(409, 'stale_epoch')
      server.commits.push(nodeId)
      server.commitBodies.set(nodeId, c)
      const st = server.stages.get(rekeyId) ?? { children: [], blobs: [], shares: [], identities: [] }
      for (const ch of st.children) {
        const child = server.nodes.get(ch.nodeId)!
        Object.assign(child, { encryptedName: ch.encryptedName, slugToken: ch.slugToken, wrappedContentKey: ch.wrappedContentKey, placementRevision: child.placementRevision + 1 })
      }
      for (const b of st.blobs) server.blobs.find((x) => x.blobId === b.blobId)!.wrappedBlobKey = b.wrappedBlobKey
      for (const sw of st.shares) {
        const share = server.shares.find((x) => x.shareId === sw.shareId)!
        if (sw.lostCopyDigest) {
          // the backend closes a key-lost link through its close body when the digest is its copy's (`RekeyStore.closeLost`)
          if (sw.lostCopyDigest !== b64std(v1.sha256Digest(v1.utf8(share.sealedShareKey)))) throw refusal(409, 'rekey_parts_stale')
          server.closedLost.push(share.shareId)
          Object.assign(share, { wrap: null, sealedDropPublicKey: null })
        }
        if (sw.wrap) share.wrap = sw.wrap
        if (sw.sealedDropPublicKey) share.sealedDropPublicKey = sw.sealedDropPublicKey
      }
      for (const i of st.identities) {
        const id = server.identities.find((x) => x.identityId === i.identityId)!
        if (i.wrappedIdentityKey) id.wrappedIdentityKey = i.wrappedIdentityKey
        if (i.sealedDisplayName) id.sealedDisplayName = i.sealedDisplayName
      }
      Object.assign(node, {
        encryptedName: c.encryptedName, slugToken: c.slugToken, wrappedContentKey: c.wrappedContentKey,
        placementRevision: node.placementRevision + 1, epoch: node.epoch + 1, scopeEpoch: c.scopeEpoch, due: false,
        ...(c.wrappedMeta ? { wrappedMeta: c.wrappedMeta, metaRevision: node.metaRevision + 1 } : {}),
        ...(c.sealedDropPrivateKey ? { sealedDropPrivateKey: c.sealedDropPrivateKey } : {}),
      })
      server.stages.delete(rekeyId)
    },
  })
  // The service's paged read (`listApplicationBlobRekey`): ONE row per page whatever the limit asks, so a client that
  // stops after its first page misses a wrap — which the commit then lacks.
  fakeApi(applicationBlobsApi, {
    listApplicationBlobRekey: async ({ nodeId, after }) => {
      const own = server.blobs.filter((b) => b.nodeId === nodeId && b.ownerId).sort((a, b) => a.blobId.localeCompare(b.blobId))
      const rest = after ? own.filter((b) => b.blobId > after) : own
      const page = rest.slice(0, 1)
      return {
        blobs: page.map((b) => ({ blobId: b.blobId, role: b.role, ownerId: b.ownerId!, wrappedBlobKey: b.wrappedBlobKey })),
        more: rest.length > 1, cursor: page[0]?.blobId ?? null,
      }
    },
  })
}

/** A thrown generated-client error carrying `{ code }`, the shape `isCodedError` reads. */
const refusal = (status: number, code: string) => Object.assign(new Error(code), {
  response: new Response(JSON.stringify({ code }), { status, headers: { 'Content-Type': 'application/json' } }),
})

const { rekeyScope, rekeyPath, pullRekeyPath, RekeyPathNotMineError } = await import('./rekeyScope')
const { retryWhileRekeying, writeAtNodeEpoch } = await import('../../../shared/rekeyRetry')
const { openScopeById } = await import('../../../features/spaces/scopes')
const { rekeyApi, applicationBlobsApi } = await import('../../../api/client')

// --- the world, sealed at epoch 0, then rotated to 1 -----------------------------------------------------------
const E0 = v1.randomBytes(32)
const E1 = v1.randomBytes(32) // a fresh key, not derived from E_0 (ADR 0115 decision 1)
let K: Record<'root' | 'child' | 'box', Uint8Array>
const ids = { root: crypto.randomUUID(), child: crypto.randomUUID(), box: crypto.randomUUID(), cover: crypto.randomUUID(), link: 'A'.repeat(43), drop: 'B'.repeat(43), guest: crypto.randomUUID(), reader: crypto.randomUUID() }
let K_SHARE: Uint8Array
let K_DROP_SHARE: Uint8Array
let K_UP: Uint8Array
let OLD_DROP_PRIVATE: string

const addr = (nodeId: string, scopeEpoch: number, placementRevision = 0): NodeAddress => ({ scopeId: SCOPE, nodeId, scopeEpoch, placementRevision })
/** A row whose entry is sealed under [entryKey], bound to [at] — its key's scope epoch and its placement revision (a
 *  scope-root entry the rotation re-sealed under E_1 is at revision 1, still bound to scope epoch 0). Due, as every
 *  node is from the rotation's commit on. */
function node(
  id: string, parentId: string | null, entryKey: Uint8Array, key: Uint8Array, name: string, extra: Partial<Row> = {},
  at: { scopeEpoch: number; placementRevision: number } = { scopeEpoch: 0, placementRevision: parentId === null ? 1 : 0 },
): Row {
  return {
    id, parentId, kind: 'FOLDER', trashed: false, epoch: 0, scopeEpoch: at.scopeEpoch, due: true,
    placementRevision: at.placementRevision, metaRevision: 0,
    encryptedName: sealName(entryKey, addr(id, at.scopeEpoch, at.placementRevision), name),
    slugToken: slugTokenFor(entryKey, parentId ?? SCOPE, 'FOLDER', name),
    wrappedContentKey: wrapContentKey(entryKey, addr(id, at.scopeEpoch, at.placementRevision), key), wrappedMeta: null, sealedDropPrivateKey: null,
    shareIdentityId: null, wrappedIdentityKey: null,
    // what a member's tree listing carries besides, so `openTree` reads these rows as it reads `listNodes`'
    ...({ orderIndex: 0, createdAt: new Date(0), blockedByOperator: false } as object), ...extra,
  }
}
// a link's recovery copy as a mint at epoch 1 wrote it: K_share under a value key of its own, whose wrap is under E_1
// (ADR 0115; a rotation re-wraps it and leaves the copy)
const recoveryContext = (nodeId: string, shareId: string) =>
  ({ purpose: 'share-key-recovery', scopeId: SCOPE, nodeId, shareId, epoch: 1 }) as const
const recoveryWrapOf = (scopeKey: Uint8Array, nodeId: string, shareId: string, valueKey: Uint8Array) =>
  b64std(v1.wrapValueKey(scopeKey, { scopeId: SCOPE, epoch: 1, value: recoveryContext(nodeId, shareId) }, valueKey))
const recovery = (nodeId: string, shareId: string, kShare: Uint8Array) => {
  const valueKey = v1.generateValueKey()
  return {
    sealedShareKey: b64std(v1.seal(recoveryContext(nodeId, shareId), valueKey, kShare)),
    wrappedRecoveryValueKey: recoveryWrapOf(E1, nodeId, shareId, valueKey),
  }
}
/** What a member's device holds after the rotation: E_1 alone. */
const memberEpochs = (): ScopeKey => ({ epoch: 1, key: E1 })
const rows = () => [...server.nodes.values()]
const keyOf = (id: string) => contentKeyChain(SCOPE, memberEpochs(), openTree(SCOPE, memberEpochs(), rows() as unknown as Parameters<typeof openTree>[2]), id)[0]

beforeEach(() => {
  installServer()
  K = { root: v1.randomBytes(32), child: v1.randomBytes(32), box: v1.randomBytes(32) }
  server.nodes.clear(); server.stages.clear(); server.crashOnCommitOf = null; server.current = 1
  server.role = 'OWNER'; server.commits = []; server.commitBodies.clear(); server.stagedFrom.clear(); server.closedLost = []; server.stagedShares = []
  server.epochs = memberEpochs()
  const kBlob = v1.randomBytes(32)
  const coverRef = { blobId: ids.cover, ownerId: ids.root, role: 'COVER' as const }
  const coverWrap = b64std(v1.seal(blobKeyContext(SCOPE, coverRef), K.root, kBlob))
  const meta = sealNodeMeta(K.root, { scopeId: SCOPE, nodeId: ids.root }, {
    v: 1, cover: { source: { kind: 'upload', ref: { blobId: ids.cover, chunkCount: 1, byteSize: 10, wrappedBlobKey: coverWrap, ownerId: ids.root, role: 'COVER' } }, crop: { x: 0, y: 0, width: 1, height: 1 } },
    // a plain field of an application's, one core does not know: carried as it is
    plainSample: [{ kind: 'sample', id: 'p1' }],
  })
  const drop = v1.generateDropKey()
  OLD_DROP_PRIVATE = b64std(v1.seal({ purpose: 'drop-private-key', scopeId: SCOPE, nodeId: ids.box }, K.box, v1.serializeDropKey(drop)))
  // the scope-root entries as the rotation's commit left them: under E_1, bound to scope epoch 0 at revision 1
  server.nodes.set(ids.root, node(ids.root, null, E1, K.root, 'root', { wrappedMeta: meta }))
  server.nodes.set(ids.child, node(ids.child, ids.root, K.root, K.child, 'child', { trashed: true }))
  server.nodes.set(ids.box, node(ids.box, null, E1, K.box, 'box', { sealedDropPrivateKey: OLD_DROP_PRIVATE }))
  server.dropBoxes = new Set([ids.box])
  server.blobs = [{ blobId: ids.cover, nodeId: ids.root, role: 'COVER', wrappedBlobKey: coverWrap }]
  K_SHARE = v1.randomBytes(32)
  K_DROP_SHARE = v1.randomBytes(32)
  const dropPublicSealed = b64std(v1.seal({ purpose: 'drop-public-key' }, K_DROP_SHARE, v1.serializeHybridPublic(v1.dropKeyPublic(drop))))
  server.shares = [
    { shareId: ids.link, nodeId: ids.root, role: 'READ_WRITE', ...recovery(ids.root, ids.link, K_SHARE), sealedShareKeyEpoch: 1, wrap: b64std(v1.seal(SHARE_KEY, K_SHARE, K.root)), sealedDropPublicKey: null },
    { shareId: ids.drop, nodeId: ids.box, role: 'WRITE_ONLY', ...recovery(ids.box, ids.drop, K_DROP_SHARE), sealedShareKeyEpoch: 1, wrap: null, sealedDropPublicKey: dropPublicSealed },
  ]
  const minted = mintIdentityKey(SCOPE, ids.box, ids.guest, v1.dropKeyPublic(drop))
  K_UP = minted.identityKey
  server.identities = [
    { identityId: ids.guest, shareId: ids.drop, wrappedIdentityKey: minted.wrappedIdentityKey, sealedDisplayName: 'Z3Vlc3Q=' },
    { identityId: ids.reader, shareId: ids.link, wrappedIdentityKey: null, sealedDisplayName: b64std(v1.sealField(identityNameContext(SCOPE, ids.link, ids.reader), K.root, v1.utf8('Ada'))) },
  ]
})

describe('rekeyScope (ADR 0109, real crypto)', () => {
  // uc.spaces.rekey-after-rotation:every-node-rekeyed
  it('bite 1: after the re-key, what is added inside a node that existed before opens with no key from before', async () => {
    expect((await rekeyScope(SCOPE)).done).toBe(true)
    expect(rows().every((n) => !n.due && n.epoch === 1 && n.scopeEpoch === 1)).toBe(true)
    const newRoot = keyOf(ids.root)
    // a medium added after the re-key: its K_blob wrapped under the node's key as the tree now yields it
    const kBlob = v1.randomBytes(32)
    const ref = { blobId: crypto.randomUUID(), ownerId: ids.root, role: 'ORIGINAL', wrappedBlobKey: '' }
    ref.wrappedBlobKey = b64std(v1.seal(blobKeyContext(SCOPE, ref), newRoot, kBlob))
    for (const old of [K.root, E0, E1]) expect(() => unwrapBlobKey(SCOPE, old, ref), 'no key from before opens it').toThrow(v1.OpenRefused)
    expect(unwrapBlobKey(SCOPE, newRoot, ref)).toEqual(kBlob) // control
    // and the node's own key is replaced: its entry under E_1 now wraps the new key, not the one from before
    expect(newRoot).not.toEqual(K.root)
  })

  // ADR 0113 decisions 3 and 7: a node's blobs in core's blob service are read page by page and each re-wrapped bound
  // to the id the server lists it with — the owner for an owner's content (a medium), the node for an item of its own.
  it('re-wraps every blob of core\'s blob service page by page, each bound to the id it is listed with', async () => {
    const owner = crypto.randomUUID()
    const kept = [v1.randomBytes(32), v1.randomBytes(32)]
    const refs = [
      { blobId: '00000000-0000-4000-8000-000000000001', ownerId: owner, role: 'ORIGINAL' }, // an owner's content
      { blobId: '00000000-0000-4000-8000-000000000002', ownerId: ids.root, role: 'EXPENSES_RECEIPT' }, // an item of its own
    ]
    refs.forEach((r, i) => server.blobs.push({ ...r, nodeId: ids.root, wrappedBlobKey: b64std(v1.seal(blobKeyContext(SCOPE, r), K.root, kept[i])) }))
    await rekeyScope(SCOPE)
    const newRoot = keyOf(ids.root)
    refs.forEach((r, i) => {
      const wrap = server.blobs.find((b) => b.blobId === r.blobId)!.wrappedBlobKey
      expect(unwrapBlobKey(SCOPE, newRoot, { ...r, wrappedBlobKey: wrap }), r.role).toEqual(kept[i])
      expect(() => unwrapBlobKey(SCOPE, K.root, { ...r, wrappedBlobKey: wrap })).toThrow(v1.OpenRefused)
    })
    // bound as listed: the medium's wrap does not open as the node's
    const medium = server.blobs.find((b) => b.blobId === refs[0].blobId)!.wrappedBlobKey
    expect(() => unwrapBlobKey(SCOPE, newRoot, { ...refs[0], ownerId: ids.root, wrappedBlobKey: medium })).toThrow(v1.OpenRefused)
  })

  it('stops the re-key of a node whose service blob does not open under the id it is listed with (fail closed)', async () => {
    const ref = { blobId: crypto.randomUUID(), ownerId: crypto.randomUUID(), role: 'ORIGINAL' }
    const wrap = b64std(v1.seal(blobKeyContext(SCOPE, ref), K.root, v1.randomBytes(32)))
    server.blobs.push({ ...ref, ownerId: crypto.randomUUID(), nodeId: ids.root, wrappedBlobKey: wrap }) // listed with another owner
    await expect(rekeyScope(SCOPE)).rejects.toThrow(v1.OpenRefused)
    expect(server.nodes.get(ids.root)!).toMatchObject({ epoch: 0, due: true })
  })

  it('re-wraps every K_blob it covers without changing it, and re-seals the meta and a READ_WRITE guest\'s name', async () => {
    const before = unwrapBlobKey(SCOPE, K.root, { blobId: ids.cover, ownerId: ids.root, role: 'COVER', wrappedBlobKey: server.blobs[0].wrappedBlobKey })
    await rekeyScope(SCOPE)
    const newRoot = keyOf(ids.root)
    const slot = server.blobs[0]
    expect(unwrapBlobKey(SCOPE, newRoot, { blobId: ids.cover, ownerId: ids.root, role: 'COVER', wrappedBlobKey: slot.wrappedBlobKey })).toEqual(before)
    const meta = unsealNodeMeta(newRoot, { scopeId: SCOPE, nodeId: ids.root }, server.nodes.get(ids.root)!.wrappedMeta)
    const inMeta = (meta.cover as { source: { ref: { wrappedBlobKey: string } } }).source.ref.wrappedBlobKey
    expect(unwrapBlobKey(SCOPE, newRoot, { blobId: ids.cover, ownerId: ids.root, role: 'COVER', wrappedBlobKey: inMeta })).toEqual(before)
    expect(() => unwrapBlobKey(SCOPE, K.root, { blobId: ids.cover, ownerId: ids.root, role: 'COVER', wrappedBlobKey: inMeta })).toThrow(v1.OpenRefused)
    expect(meta.plainSample, 'a plain field of the meta rides along unchanged').toEqual([{ kind: 'sample', id: 'p1' }])
    const reader = server.identities.find((i) => i.identityId === ids.reader)!
    expect(new TextDecoder().decode(v1.openField(identityNameContext(SCOPE, ids.link, ids.reader), newRoot, fromB64std(reader.sealedDisplayName)))).toBe('Ada')
  })

  // ADR 0120 decision 3: a bookmark's link and password live in its meta under its own key, which the rotation replaces
  // like every node's; the link reads the same under the new key and nothing of it under the key from before.
  it('re-seals a bookmark\'s link and password under its new key, and none of it opens under the key from before', async () => {
    // a node-environment file is given no registry by the setup (`src/test/setup.ts`): core's bookmark is registered here
    // as the composition root registers it
    if (!registry.kind(SHARE_BOOKMARK_KIND).supported) registry.registerCoreKind(SHARE_BOOKMARK_KIND, shareBookmarkKind)
    const kBookmark = v1.randomBytes(32)
    const bookmarkId = crypto.randomUUID()
    const link = keptLink(`https://photos.other.example/s/${'C'.repeat(43)}#k=${b64url(v1.randomBytes(32))}`)!
    const at = { scopeId: SCOPE, nodeId: bookmarkId }
    server.nodes.set(bookmarkId, node(bookmarkId, ids.root, K.root, kBookmark, 'kept link', {
      kind: SHARE_BOOKMARK_KIND, wrappedMeta: sealNodeMeta(kBookmark, at, bookmarkMetaObject(link, 'pw')),
    }))
    await rekeyScope(SCOPE)
    expect(server.commits).toContain(bookmarkId)
    const fresh = keyOf(bookmarkId)
    expect(fresh).not.toEqual(kBookmark)
    const sealed = server.nodes.get(bookmarkId)!.wrappedMeta
    expect(bookmarkOf(unsealNodeMeta(fresh, at, sealed))).toEqual({ link, password: 'pw' })
    expect(bookmarkOf(unsealNodeMeta(kBookmark, at, sealed)), 'the key from before opens nothing of it').toBeNull()
  })

  // uc.spaces.rekey-after-rotation:resumable
  it('bite 4: interrupted after the parent\'s commit, the tree opens as it stands and a second run finishes it', async () => {
    server.crashOnCommitOf = ids.child
    await expect(rekeyScope(SCOPE)).rejects.toThrow('the device stopped')
    expect(server.nodes.get(ids.root)!).toMatchObject({ epoch: 1, due: false })
    expect(server.nodes.get(ids.child)!).toMatchObject({ epoch: 0, due: true })
    // the whole tree opens under E_1: the child's entry is under its parent's NEW key already
    const opened = openTree(SCOPE, memberEpochs(), rows() as unknown as Parameters<typeof openTree>[2])
    expect(opened.map((n) => n.name).sort()).toEqual(['box', 'child', 'root'])
    expect(keyOf(ids.child)).toEqual(K.child) // its own key still from before: it is what the second run replaces
    server.crashOnCommitOf = null
    expect((await rekeyScope(SCOPE)).done).toBe(true) // control: resumed from the rows, nothing kept between runs
    expect(rows().every((n) => !n.due && n.epoch === 1)).toBe(true)
    expect(keyOf(ids.child)).not.toEqual(K.child)
  })

  // uc.spaces.rekey-after-rotation:links-follow-and-stay-marked
  it('bite 5: a link from before keeps showing what is added, stays re-issue-required, and a re-issue closes it', async () => {
    const before = server.shares.find((s) => s.shareId === ids.link)!
    const copy = before.sealedShareKey
    const wrap = before.wrappedRecoveryValueKey
    await rekeyScope(SCOPE)
    const link = server.shares.find((s) => s.shareId === ids.link)!
    // the node's re-key leaves the recovery copy and its value key's wrap as they were (the wrap under E_1, at epoch 1):
    // the K_share in it is the one from before, which is why the link stays re-issue-required (the server's rule, by the
    // epoch the link was minted at, ADR 0115 decision 2)
    expect(link).toMatchObject({ sealedShareKey: copy, wrappedRecoveryValueKey: wrap, sealedShareKeyEpoch: 1 })
    // the old K_share (the one an excluded party can rebuild from the copy) opens the NEW root key
    expect(v1.open(SHARE_KEY, K_SHARE, fromB64std(link.wrap!))).toEqual(keyOf(ids.root))
    // a re-issue's fresh K_share: the old one opens nothing of it
    const fresh = v1.randomBytes(32)
    const reissued = b64std(v1.seal(SHARE_KEY, fresh, keyOf(ids.root)))
    expect(() => v1.open(SHARE_KEY, K_SHARE, fromB64std(reissued))).toThrow(v1.OpenRefused)
    expect(v1.open(SHARE_KEY, fresh, fromB64std(reissued))).toEqual(keyOf(ids.root)) // control
  })

  // uc.spaces.rekey-after-rotation:drop-key-replaced
  it('bite 6: an upload to a drop box after the re-key does not open with the drop key from before', async () => {
    await rekeyScope(SCOPE)
    const box = server.nodes.get(ids.box)!
    expect(box.sealedDropPrivateKey).not.toBe(OLD_DROP_PRIVATE)
    const oldDrop = openDropKey(SCOPE, ids.box, K.box, OLD_DROP_PRIVATE)
    const newDrop = openDropKey(SCOPE, ids.box, keyOf(ids.box), box.sealedDropPrivateKey!)
    // a new guest after the re-key seals its K_up to the drop public key the link now serves
    const link = server.shares.find((s) => s.shareId === ids.drop)!
    const served = openDropPublicKey(K_DROP_SHARE, link.sealedDropPublicKey!)
    const after = mintIdentityKey(SCOPE, ids.box, crypto.randomUUID(), served)
    const id = crypto.randomUUID()
    const wrapped = mintIdentityKey(SCOPE, ids.box, id, served).wrappedIdentityKey
    expect(() => openIdentityKey(SCOPE, ids.box, oldDrop, id, wrapped), 'the old drop key opens no new upload').toThrow(v1.OpenRefused)
    expect(openIdentityKey(SCOPE, ids.box, newDrop, id, wrapped)).toHaveLength(32) // control
    expect(after.identityKey).toHaveLength(32)
    // the guest from before keeps its K_up, now reached through the new drop key (ADR 0109 point 5)
    const guest = server.identities.find((i) => i.identityId === ids.guest)!
    expect(openIdentityKey(SCOPE, ids.box, newDrop, ids.guest, guest.wrappedIdentityKey!)).toEqual(K_UP)
  })
})

describe('the frontier and the commit (ADR 0114 decisions 3-4, ADR 0115)', () => {
  it('re-keys what is due, whatever its epoch: a node behind but not due is left, one at the current scope epoch but due is re-keyed', async () => {
    // the drop box behind the scope (its key from scope epoch 0) but not marked due; a link's close marked `closed`, a
    // node whose key is already from the current scope epoch (its own epoch 2 after two earlier replacements)
    server.nodes.get(ids.box)!.due = false
    const kClosed = v1.randomBytes(32)
    const closed = crypto.randomUUID()
    server.nodes.set(closed, node(closed, null, E1, kClosed, 'closed', { epoch: 2 }, { scopeEpoch: 1, placementRevision: 0 }))
    expect((await rekeyScope(SCOPE)).done).toBe(true)
    expect(server.commits).not.toContain(ids.box)
    expect(server.nodes.get(ids.box)!).toMatchObject({ epoch: 0, scopeEpoch: 0, sealedDropPrivateKey: OLD_DROP_PRIVATE })
    expect(server.commits).toContain(closed)
    expect(server.nodes.get(closed)!).toMatchObject({ epoch: 3, scopeEpoch: 1, due: false })
    expect(keyOf(closed)).not.toEqual(kClosed)
    // control: the same drop box, marked due, is re-keyed
    server.nodes.get(ids.box)!.due = true
    expect((await rekeyScope(SCOPE)).done).toBe(true)
    expect(server.commits.at(-1)).toBe(ids.box)
    expect(server.nodes.get(ids.box)!).toMatchObject({ epoch: 1, scopeEpoch: 1, due: false })
  })

  it('a due node below a due parent waits for it: the parent commits first, then the child at the parent\'s new epoch', async () => {
    await rekeyScope(SCOPE)
    expect(server.commits.indexOf(ids.root)).toBeLessThan(server.commits.indexOf(ids.child))
    expect(server.commitBodies.get(ids.child)).toMatchObject({ parentId: ids.root, parentEpoch: 1 })
  })

  it('the commit states the node\'s own epoch as nodeEpoch and the scope\'s current epoch as scopeEpoch', async () => {
    // the child's key was replaced twice under scope epoch 0 (two link closes): its own epoch is 2, its scope epoch 0
    server.nodes.get(ids.child)!.epoch = 2
    await rekeyScope(SCOPE)
    expect(server.commitBodies.get(ids.root)).toMatchObject({ nodeEpoch: 0, scopeEpoch: 1 })
    expect(server.commitBodies.get(ids.root)!.parentEpoch).toBeUndefined() // a scope-root node states no parent
    expect(server.commitBodies.get(ids.child)).toMatchObject({ nodeEpoch: 2, scopeEpoch: 1 })
    expect(server.stagedFrom.get(ids.root), 'its staged lists name the epoch they replace').toBe(0)
    expect(server.nodes.get(ids.child)!).toMatchObject({ epoch: 3, scopeEpoch: 1 })
    // control: the server's gate refuses a commit whose nodeEpoch is not the stored one, so the run's green is the gate's
    server.nodes.get(ids.box)!.due = true
    server.nodes.get(ids.box)!.epoch = 5
    const before = server.commits.length
    expect((await rekeyScope(SCOPE)).done).toBe(true)
    expect(server.commits.length).toBe(before + 1)
    expect(server.commitBodies.get(ids.box)).toMatchObject({ nodeEpoch: 5, scopeEpoch: 1 })
  })

  it('a scope-root entry opens under the scope\'s one key bound to its old scope epoch, and is re-sealed bound to the current one', async () => {
    const before = server.nodes.get(ids.root)!
    // as the rotation left it: under E_1, bound to scope epoch 0 at revision 1 — under nothing else
    expect(openContentKey(SCOPE, E1, { ...before, scopeEpoch: 0 })).toEqual(K.root)
    expect(() => openContentKey(SCOPE, E0, before), 'the replaced key opens nothing').toThrow(v1.OpenRefused)
    await rekeyScope(SCOPE)
    const after = server.nodes.get(ids.root)!
    expect(after).toMatchObject({ scopeEpoch: 1, placementRevision: 2 })
    const newRoot = openContentKey(SCOPE, E1, after)
    expect(newRoot).not.toEqual(K.root)
    expect(openName(E1, addr(ids.root, 1, 2), after.encryptedName)).toBe('root')
    // the binding moved with it: the new entry does not open as one bound to the scope epoch from before
    expect(() => openContentKey(SCOPE, E1, { ...after, scopeEpoch: 0 })).toThrow(v1.OpenRefused)
    expect(() => openName(E1, addr(ids.root, 0, 2), after.encryptedName)).toThrow(v1.OpenRefused)
  })

  // ADR 0115 decision 2: a recovery copy's value-key wrap the rotator could not open is staged as one nobody opens — a
  // K_v of nobody's under a key dropped at once; the copy itself is untouched, but its key is lost to every member, so the
  // root's re-key names it and the commit closes it instead of stalling the re-key for good.
  it('names a link whose recovery copy opens for no member as key-lost, and the re-key finishes', async () => {
    const link = server.shares.find((s) => s.shareId === ids.link)!
    link.wrappedRecoveryValueKey = recoveryWrapOf(v1.randomBytes(32), ids.root, ids.link, v1.generateValueKey())
    expect((await rekeyScope(SCOPE)).done).toBe(true)
    const staged = server.stagedShares
    expect(rows().every((n) => !n.due)).toBe(true)
    expect(staged.find((s) => s.shareId === ids.link)).toEqual({
      shareId: ids.link, lostCopyDigest: b64std(v1.sha256Digest(v1.utf8(link.sealedShareKey))),
    })
    expect(server.closedLost).toEqual([ids.link])
    expect(link.wrap, 'nothing new under the lost link').toBeNull()
    // control: the drop box's link, whose copy opens, gets the new drop public key under its K_share
    const drop = server.shares.find((s) => s.shareId === ids.drop)!
    expect(staged.find((s) => s.shareId === ids.drop)).toMatchObject({ sealedDropPublicKey: drop.sealedDropPublicKey })
    expect(openDropPublicKey(K_DROP_SHARE, drop.sealedDropPublicKey!)).toBeTruthy()
  })

  // A rotator holds the scope key, so it can wrap a K_v of its own choosing at the very slot: that wrap opens, and the
  // copy under it does not — the link is key-lost, and no wrap of the new root key under a K_share of the rotator's
  // choosing is ever staged.
  it('names a link whose copy\'s wrap was substituted as key-lost, never wrapping the new key under another K_share', async () => {
    const link = server.shares.find((s) => s.shareId === ids.link)!
    link.wrappedRecoveryValueKey = recoveryWrapOf(E1, ids.root, ids.link, v1.generateValueKey())
    expect((await rekeyScope(SCOPE)).done).toBe(true)
    expect(server.stagedShares.find((s) => s.shareId === ids.link)).toEqual({
      shareId: ids.link, lostCopyDigest: b64std(v1.sha256Digest(v1.utf8(link.sealedShareKey))),
    })
    expect(server.closedLost).toEqual([ids.link])
    // control: the drop box's link, its wrap genuine, is re-keyed as before
    expect(server.stagedShares.find((s) => s.shareId === ids.drop)).not.toHaveProperty('lostCopyDigest', expect.any(String))
  })

  // A node whose key opens for no member — a root entry the rotator could not re-seal — holds up no other node: the run
  // re-keys everything else first and only then refuses, leaving that node due.
  it('re-keys every other node before it refuses on one whose key opens for no member', async () => {
    const root = server.nodes.get(ids.root)!
    root.wrappedContentKey = wrapContentKey(v1.randomBytes(32), addr(ids.root, 0, 1), K.root) // first in the frontier
    await expect(rekeyScope(SCOPE)).rejects.toThrow(v1.OpenRefused)
    expect(server.commits).toEqual([ids.box])
    expect(server.nodes.get(ids.box)!).toMatchObject({ epoch: 1, due: false })
    expect(server.nodes.get(ids.root)!).toMatchObject({ epoch: 0, due: true })
    expect(server.nodes.get(ids.child)!).toMatchObject({ epoch: 0, due: true }) // below it: its chain does not open either
  })

  it('a scope-root entry still under the replaced key opens under nothing the device holds: its re-key refuses, nothing of it committed', async () => {
    // a rotation whose commit did not re-seal the drop box's entry: it is still under E_0, which no member holds
    server.nodes.set(ids.box, node(ids.box, null, E0, K.box, 'box', { sealedDropPrivateKey: OLD_DROP_PRIVATE }))
    await expect(rekeyScope(SCOPE)).rejects.toThrow(v1.OpenRefused)
    expect(server.commits).not.toContain(ids.box)
    expect(server.nodes.get(ids.box)!).toMatchObject({ epoch: 0, due: true })
    // control: the root's entry, re-sealed under E_1, was re-keyed in the same run
    expect(server.commits).toContain(ids.root)
  })
})

describe('any member re-keys (ADR 0111 decision 11)', () => {
  afterEach(() => vi.useRealTimers())

  it('a member\'s run re-keys every node but the drop box, which it leaves for an owner without failing', async () => {
    server.role = 'MEMBER'
    const run = await rekeyScope(SCOPE)
    expect(run).toMatchObject({ done: false, committed: 2 })
    expect([ids.root, ids.child].map((id) => server.nodes.get(id)!.due)).toEqual([false, false])
    expect(server.nodes.get(ids.box)!, 'the drop box is an owner\'s').toMatchObject({ epoch: 0, due: true })
    expect(server.commits).not.toContain(ids.box)
    // control: an owner's run finishes it
    server.role = 'OWNER'
    expect((await rekeyScope(SCOPE)).done).toBe(true)
    expect(server.nodes.get(ids.box)!).toMatchObject({ epoch: 1, due: false })
  })

  it('a member re-keys a node its uploader link gave a drop key: the drop key replaced, the guest\'s K_up re-sealed to it', async () => {
    // the same drop-keyed node, of no drop box kind (a Memory with an uploader link): members write into it, so the
    // server leaves it any member's, and a member's device holds everything its re-key needs
    server.dropBoxes.clear()
    server.role = 'MEMBER'
    expect((await rekeyScope(SCOPE)).done).toBe(true)
    const memory = server.nodes.get(ids.box)!
    expect(memory).toMatchObject({ epoch: 1, due: false })
    expect(memory.sealedDropPrivateKey).not.toBe(OLD_DROP_PRIVATE)
    // everything the member's device needed it holds: the node's old key opened the old drop key, which opened K_up
    const newDrop = openDropKey(SCOPE, ids.box, keyOf(ids.box), memory.sealedDropPrivateKey!)
    const guest = server.identities.find((i) => i.identityId === ids.guest)!
    expect(openIdentityKey(SCOPE, ids.box, newDrop, ids.guest, guest.wrappedIdentityKey!)).toEqual(K_UP)
    const link = server.shares.find((s) => s.shareId === ids.drop)!
    expect(v1.serializeHybridPublic(openDropPublicKey(K_DROP_SHARE, link.sealedDropPublicKey!)))
      .toEqual(v1.serializeHybridPublic(v1.dropKeyPublic(newDrop)))
  })

  it('a path pull re-keys the written node and its lagging ancestors top-down, and nothing else', async () => {
    server.role = 'MEMBER'
    expect((await rekeyPath(SCOPE, [ids.child])).done).toBe(true)
    expect(server.commits).toEqual([ids.root, ids.child])
    expect(server.nodes.get(ids.box)!.due).toBe(true)
  })

  it('a path through a node only an owner re-keys is not a member\'s: refused before any commit, and the pull says so', async () => {
    server.role = 'MEMBER'
    await expect(rekeyPath(SCOPE, [ids.box])).rejects.toBeInstanceOf(RekeyPathNotMineError)
    expect(await pullRekeyPath(SCOPE, [ids.box])()).toBe(false)
    expect(server.commits).toEqual([])
    server.role = 'OWNER' // control: an owner's pull brings it forward
    expect(await pullRekeyPath(SCOPE, [ids.box])()).toBe(true)
    expect(server.nodes.get(ids.box)!).toMatchObject({ epoch: 1, due: false })
  })

  it('a member\'s refused pull still re-opens the active scope it holds behind, so the write\'s re-read after the wait opens an owner\'s re-key (inbox #670)', async () => {
    server.role = 'MEMBER'
    const active = await import('../../../app/activeSpace')
    active.activeSpace.scopeId = SCOPE
    vi.spyOn(active, 'activeEpochKeys').mockReturnValue({ epoch: 0, key: new Uint8Array(32) })
    try {
      expect(await pullRekeyPath(SCOPE, [ids.box])()).toBe(false)
      expect(active.refreshActiveScope).toHaveBeenCalledOnce()
      expect(server.commits).toEqual([])
      vi.mocked(active.refreshActiveScope).mockClear()
      vi.spyOn(active, 'activeEpochKeys').mockReturnValue({ epoch: 1, key: new Uint8Array(32) }) // control: held at the current epoch
      expect(await pullRekeyPath(SCOPE, [ids.box])()).toBe(false)
      expect(active.refreshActiveScope).not.toHaveBeenCalled()
    } finally {
      active.activeSpace.scopeId = null
      vi.mocked(active.activeEpochKeys).mockRestore?.()
    }
  })

  /** The server's gate on a write under [nodeId]'s key: `409 node_rekey_pending` while it is due. */
  const writeUnder = (nodeId: string, attempts: number[]) => async (n: number) => {
    attempts.push(n)
    if (server.nodes.get(nodeId)!.due) throw refusal(409, 'node_rekey_pending')
    return 'written'
  }

  it('a member\'s write refused node_rekey_pending pulls its path (parent, then target) and lands on the second attempt with no wait', async () => {
    server.role = 'MEMBER'
    vi.useFakeTimers()
    const attempts: number[] = []
    const run = retryWhileRekeying(writeUnder(ids.child, attempts), { pull: pullRekeyPath(SCOPE, [ids.child]) })
    // no timer is advanced: a write that waited would never settle here
    await expect(run).resolves.toBe('written')
    expect(attempts, 'the second attempt re-reads').toEqual([0, 1])
    expect(server.commits).toEqual([ids.root, ids.child])
    expect(vi.getTimerCount(), 'no wait was scheduled').toBe(0)
  })

  it('a guest\'s write (no pull) waits as before and re-keys nothing', async () => {
    const attempts: number[] = []
    const err = await retryWhileRekeying(writeUnder(ids.child, attempts), { delaysMs: [0, 0] }).catch((e: unknown) => e)
    expect((err as Error).message).toBe('node_rekey_pending')
    expect(attempts).toEqual([0, 1, 2])
    expect(server.commits).toEqual([])
  })

  it('a member\'s write into the drop box falls back to the waits: its pull is refused, nothing is committed', async () => {
    server.role = 'MEMBER'
    const attempts: number[] = []
    const err = await retryWhileRekeying(writeUnder(ids.box, attempts), { delaysMs: [0, 0], pull: pullRekeyPath(SCOPE, [ids.box]) }).catch((e: unknown) => e)
    expect((err as Error).message).toBe('node_rekey_pending')
    expect(attempts, 'the first attempt and one per wait').toEqual([0, 1, 2])
    expect(server.commits).toEqual([])
  })

  it('a stale_epoch after the pull is re-read once, as writeAtNodeEpoch does', async () => {
    server.role = 'MEMBER'
    const rereads: boolean[] = []
    const out = await writeAtNodeEpoch(async (reread) => {
      rereads.push(reread)
      if (rereads.length === 1) throw refusal(409, 'node_rekey_pending')
      if (rereads.length === 2) throw refusal(409, 'stale_epoch') // re-keyed again between the pull and the re-read
      return 'written'
    }, { delaysMs: [], pull: pullRekeyPath(SCOPE, [ids.child]) })
    expect(out).toBe('written')
    expect(rereads).toEqual([false, true, true])
    expect(server.commits).toEqual([ids.root, ids.child])
  })
})
