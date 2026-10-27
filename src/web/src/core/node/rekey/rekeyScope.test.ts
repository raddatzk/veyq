// A scope's re-key (ADR 0109) through the REAL crypto: no seal is mocked, only the transport — a small in-memory server
// that serves the rows and applies a commit as the backend's `RekeyStore.commit` does (every staged value swapped in,
// the node's epoch moved). The world is built at epoch 0 with the real seals, then the scope rotates to 1 (E_1 chained to
// E_0, as a member's device holds them) and `rekeyScope` runs. Each ADR 0109 bite test is a negative with its positive
// control on the same key.
//
// ADR 0111 decision 11 on the same server: a member's run leaves the nodes only an owner re-keys (`ownerOnly`, which the
// server derives as the backend's `OwnerOnlyRekey` does), and a member's write pulls its own path forward through
// `pullRekeyPath` — the same per-node commit, on the written node and its lagging ancestors only — then retries at once.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'
import type { EpochKeys } from '../../../features/spaces/epochs'
import { blobKeyContext, unwrapBlobKey } from '../../blob/keys'
import { SHARE_KEY } from '../../sharing/shareTree'
import { mintIdentityKey, openDropKey, openDropPublicKey, openIdentityKey } from '../../sharing/dropKey'
import { identityNameContext } from '../../sharing/guestIdentity'
import { sealNodeMeta, unsealNodeMeta } from '../nodeMeta'
import { fakeApi } from '../../../test/apiMock'
import type { RekeyBlobWrap, RekeyChildEntry, RekeyIdentitySeal, RekeyShareRoleEnum, RekeyShareWrap } from '../../../api/generated/content'
import { contentKeyChain, openTree, sealName, slugTokenFor, wrapContentKey, type NodeAddress } from '../tree'

const SCOPE = '7c3f0e2a-9b1d-4a6e-8b2c-1f2d3e4a5b6c'

// --- the in-memory server ---------------------------------------------------------------------------------------
type Row = {
  id: string; parentId: string | null; kind: string; trashed: boolean; epoch: number; placementRevision: number; metaRevision: number
  encryptedName: string; slugToken: string; wrappedContentKey: string; wrappedMeta: string | null; sealedDropPrivateKey: string | null
  shareIdentityId: string | null; wrappedIdentityKey: string | null
}
type Share = { shareId: string; nodeId: string; role: RekeyShareRoleEnum; sealedShareKey: string; sealedShareKeyEpoch: number; wrap: string | null; sealedDropPublicKey: string | null }
type Identity = { identityId: string; shareId: string; wrappedIdentityKey: string | null; sealedDisplayName: string }
type Blob = { blobId: string; nodeId: string; role: string; wrappedBlobKey: string }
type Stage = { children: RekeyChildEntry[]; blobs: RekeyBlobWrap[]; shares: RekeyShareWrap[]; identities: RekeyIdentitySeal[] }
const server = {
  current: 1,
  nodes: new Map<string, Row>(),
  shares: [] as Share[],
  identities: [] as Identity[],
  blobs: [] as Blob[],
  stages: new Map<string, Stage>(),
  crashOnCommitOf: null as string | null,
  epochs: null as EpochKeys | null,
  /** The caller's role in the scope, as its row says: an owner re-keys every node, a member all but the owner-only ones. */
  role: 'OWNER' as 'OWNER' | 'MEMBER',
  /** Every commit, in order: who re-keyed what. */
  commits: [] as string[],
}

/** The backend's `OwnerOnlyRekey`: a drop box root, a guest's identity folder, or below one. */
function ownerOnly(n: Row): boolean {
  if (n.sealedDropPrivateKey) return true
  for (let cur: Row | undefined = n; cur; cur = cur.parentId ? server.nodes.get(cur.parentId) : undefined) if (cur.shareIdentityId) return true
  return false
}

vi.mock('../../../app/sessionRefresh', () => ({ withRefresh: (f: () => unknown) => f() }))
vi.mock('../../../features/spaces/scopes', () => ({ openScopeById: vi.fn() }))
vi.mock('../../../api/client', () => ({ rekeyApi: vi.fn() }))
vi.mock('../../../app/activeSpace', () => ({ activeSpace: { scopeId: null }, activeEpochKeys: () => null, refreshActiveScope: vi.fn() }))

/** The in-memory server behind `rekeyApi` — it applies a commit as `RekeyStore.commit` does — and the member's scope
 *  as `openScopeById` opens it; installed per test. */
function installServer(): void {
  vi.mocked(openScopeById).mockImplementation(async () => {
    const ek = server.epochs!
    // only the epochs and the caller's role are read by the driver; the rest of an opened scope is not this test's
    return { role: server.role, epochs: { current: ek.current, keys: new Map([...ek.keys].map(([e, k]) => [e, k.slice()])) } } as unknown as Awaited<ReturnType<typeof openScopeById>>
  })
  fakeApi(rekeyApi, {
    listRekeyNodes: async () => ({ currentEpoch: server.current, nodes: [...server.nodes.values()].map((n) => ({ ...n, ownerOnly: ownerOnly(n) })) }),
    getNodeRekeyParts: async ({ nodeId }) => {
      const shares = server.shares.filter((s) => s.nodeId === nodeId)
      return {
        blobs: server.blobs.filter((b) => b.nodeId === nodeId).map(({ blobId, role, wrappedBlobKey }) => ({ blobId, role, wrappedBlobKey })),
        shares: shares.map((s) => ({ shareId: s.shareId, role: s.role, sealedShareKey: s.sealedShareKey, sealedShareKeyEpoch: s.sealedShareKeyEpoch, hasWrap: s.wrap !== null, hasDropPublicKey: s.sealedDropPublicKey !== null })),
        identities: server.identities.filter((i) => shares.some((s) => s.shareId === i.shareId)).map((i) => ({
          identityId: i.identityId, shareId: i.shareId, wrappedIdentityKey: i.wrappedIdentityKey, sealedDisplayName: i.wrappedIdentityKey ? null : i.sealedDisplayName,
        })),
      }
    },
    stageNodeRekey: async ({ rekeyId, nodeRekeyStage: s }) => {
      const st = server.stages.get(rekeyId) ?? { children: [], blobs: [], shares: [], identities: [] }
      st.children.push(...(s.children ?? [])); st.blobs.push(...(s.blobs ?? [])); st.shares.push(...(s.shares ?? [])); st.identities.push(...(s.identities ?? []))
      server.stages.set(rekeyId, st)
    },
    commitNodeRekey: async ({ nodeId, rekeyId, commitNodeRekeyRequest: c }) => {
      if (server.crashOnCommitOf === nodeId) throw new Error('the device stopped')
      const node = server.nodes.get(nodeId)!
      if (server.role !== 'OWNER' && ownerOnly(node)) throw refusal(403, 'not_scope_owner')
      server.commits.push(nodeId)
      const st = server.stages.get(rekeyId) ?? { children: [], blobs: [], shares: [], identities: [] }
      for (const ch of st.children) {
        const child = server.nodes.get(ch.nodeId)!
        Object.assign(child, { encryptedName: ch.encryptedName, slugToken: ch.slugToken, wrappedContentKey: ch.wrappedContentKey, placementRevision: child.placementRevision + 1 })
      }
      for (const b of st.blobs) server.blobs.find((x) => x.blobId === b.blobId)!.wrappedBlobKey = b.wrappedBlobKey
      for (const sw of st.shares) {
        const share = server.shares.find((x) => x.shareId === sw.shareId)!
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
        placementRevision: node.placementRevision + 1, epoch: c.epoch,
        ...(c.wrappedMeta ? { wrappedMeta: c.wrappedMeta, metaRevision: node.metaRevision + 1 } : {}),
        ...(c.sealedDropPrivateKey ? { sealedDropPrivateKey: c.sealedDropPrivateKey } : {}),
      })
      server.stages.delete(rekeyId)
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
const { rekeyApi } = await import('../../../api/client')

// --- the world, sealed at epoch 0 ------------------------------------------------------------------------------
const E0 = v1.randomBytes(32)
const E1 = v1.randomBytes(32)
let K: Record<'root' | 'child' | 'box', Uint8Array>
const ids = { root: crypto.randomUUID(), child: crypto.randomUUID(), box: crypto.randomUUID(), cover: crypto.randomUUID(), link: 'A'.repeat(43), drop: 'B'.repeat(43), guest: crypto.randomUUID(), reader: crypto.randomUUID() }
let K_SHARE: Uint8Array
let K_DROP_SHARE: Uint8Array
let K_UP: Uint8Array
let OLD_DROP_PRIVATE: string

const addr = (nodeId: string, epoch: number, placementRevision = 0): NodeAddress => ({ scopeId: SCOPE, nodeId, epoch, placementRevision })
function node(id: string, parentId: string | null, entryKey: Uint8Array, key: Uint8Array, name: string, extra: Partial<Row> = {}): Row {
  return {
    id, parentId, kind: 'FOLDER', trashed: false, epoch: 0, placementRevision: 0, metaRevision: 0,
    encryptedName: sealName(entryKey, addr(id, 0), name), slugToken: slugTokenFor(entryKey, parentId ?? SCOPE, 'FOLDER', name),
    wrappedContentKey: wrapContentKey(entryKey, addr(id, 0), key), wrappedMeta: null, sealedDropPrivateKey: null,
    shareIdentityId: null, wrappedIdentityKey: null,
    // what a member's tree listing carries besides, so `openTree` reads these rows as it reads `listNodes`'
    ...({ orderIndex: 0, createdAt: new Date(0), blockedByOperator: false } as object), ...extra,
  }
}
const recovery = (nodeId: string, shareId: string, kShare: Uint8Array) =>
  b64std(v1.seal({ purpose: 'share-key-recovery', scopeId: SCOPE, nodeId, shareId, epoch: 0 }, E0, kShare))
const memberEpochs = (): EpochKeys => ({ current: 1, keys: new Map([[0, E0], [1, E1]]) })
const rows = () => [...server.nodes.values()]
const keyOf = (id: string) => contentKeyChain(SCOPE, memberEpochs(), openTree(SCOPE, memberEpochs(), rows() as unknown as Parameters<typeof openTree>[2]), id)[0]

beforeEach(() => {
  installServer()
  K = { root: v1.randomBytes(32), child: v1.randomBytes(32), box: v1.randomBytes(32) }
  server.nodes.clear(); server.stages.clear(); server.crashOnCommitOf = null; server.current = 1
  server.role = 'OWNER'; server.commits = []
  server.epochs = memberEpochs()
  const kBlob = v1.randomBytes(32)
  const coverRef = { blobId: ids.cover, ownerId: ids.root, role: 'COVER' as const }
  const coverWrap = b64std(v1.seal(blobKeyContext(SCOPE, coverRef), K.root, kBlob))
  const meta = sealNodeMeta(K.root, { scopeId: SCOPE, nodeId: ids.root }, {
    v: 1, cover: { source: { kind: 'upload', ref: { blobId: ids.cover, chunkCount: 1, byteSize: 10, wrappedBlobKey: coverWrap, ownerId: ids.root, role: 'COVER' } }, crop: { x: 0, y: 0, width: 1, height: 1 } },
  })
  const drop = v1.generateDropKey()
  OLD_DROP_PRIVATE = b64std(v1.seal({ purpose: 'drop-private-key', scopeId: SCOPE, nodeId: ids.box }, K.box, v1.serializeDropKey(drop)))
  server.nodes.set(ids.root, node(ids.root, null, E0, K.root, 'root', { wrappedMeta: meta }))
  server.nodes.set(ids.child, node(ids.child, ids.root, K.root, K.child, 'child', { trashed: true }))
  server.nodes.set(ids.box, node(ids.box, null, E0, K.box, 'box', { sealedDropPrivateKey: OLD_DROP_PRIVATE }))
  server.blobs = [{ blobId: ids.cover, nodeId: ids.root, role: 'COVER', wrappedBlobKey: coverWrap }]
  K_SHARE = v1.randomBytes(32)
  K_DROP_SHARE = v1.randomBytes(32)
  const dropPublicSealed = b64std(v1.seal({ purpose: 'drop-public-key' }, K_DROP_SHARE, v1.serializeHybridPublic(v1.dropKeyPublic(drop))))
  server.shares = [
    { shareId: ids.link, nodeId: ids.root, role: 'READ_WRITE', sealedShareKey: recovery(ids.root, ids.link, K_SHARE), sealedShareKeyEpoch: 0, wrap: b64std(v1.seal(SHARE_KEY, K_SHARE, K.root)), sealedDropPublicKey: null },
    { shareId: ids.drop, nodeId: ids.box, role: 'WRITE_ONLY', sealedShareKey: recovery(ids.box, ids.drop, K_DROP_SHARE), sealedShareKeyEpoch: 0, wrap: null, sealedDropPublicKey: dropPublicSealed },
  ]
  const minted = mintIdentityKey(SCOPE, ids.box, ids.guest, v1.dropKeyPublic(drop))
  K_UP = minted.identityKey
  server.identities = [
    { identityId: ids.guest, shareId: ids.drop, wrappedIdentityKey: minted.wrappedIdentityKey, sealedDisplayName: 'Z3Vlc3Q=' },
    { identityId: ids.reader, shareId: ids.link, wrappedIdentityKey: null, sealedDisplayName: b64std(v1.sealField(identityNameContext(SCOPE, ids.link, ids.reader), K.root, v1.utf8('Ada'))) },
  ]
})

describe('rekeyScope (ADR 0109, real crypto)', () => {
  it('bite 1: after the re-key, what is added inside a node that existed before opens with no key from before', async () => {
    expect((await rekeyScope(SCOPE)).done).toBe(true)
    expect(rows().every((n) => n.epoch === 1)).toBe(true)
    const newRoot = keyOf(ids.root)
    // a medium added after the re-key: its K_blob wrapped under the node's key as the tree now yields it
    const kBlob = v1.randomBytes(32)
    const ref = { blobId: crypto.randomUUID(), ownerId: ids.root, role: 'ORIGINAL', wrappedBlobKey: '' }
    ref.wrappedBlobKey = b64std(v1.seal(blobKeyContext(SCOPE, ref), newRoot, kBlob))
    for (const old of [K.root, E0, E1]) expect(() => unwrapBlobKey(SCOPE, old, ref), 'no key from before opens it').toThrow(v1.OpenRefused)
    expect(unwrapBlobKey(SCOPE, newRoot, ref)).toEqual(kBlob) // control
    // and the node's own key no longer opens from the old epoch's K_SC (its entry is under E_1 now)
    expect(newRoot).not.toEqual(K.root)
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
    const reader = server.identities.find((i) => i.identityId === ids.reader)!
    expect(new TextDecoder().decode(v1.openField(identityNameContext(SCOPE, ids.link, ids.reader), newRoot, fromB64std(reader.sealedDisplayName)))).toBe('Ada')
  })

  it('bite 4: interrupted after the parent\'s commit, the tree opens as it stands and a second run finishes it', async () => {
    server.crashOnCommitOf = ids.child
    await expect(rekeyScope(SCOPE)).rejects.toThrow('the device stopped')
    expect(server.nodes.get(ids.root)!.epoch).toBe(1)
    expect(server.nodes.get(ids.child)!.epoch).toBe(0)
    // the whole tree opens from the new epoch: the child's entry is under its parent's NEW key already
    const opened = openTree(SCOPE, memberEpochs(), rows() as unknown as Parameters<typeof openTree>[2])
    expect(opened.map((n) => n.name).sort()).toEqual(['box', 'child', 'root'])
    expect(keyOf(ids.child)).toEqual(K.child) // its own key still from before: it is what the second run replaces
    server.crashOnCommitOf = null
    expect((await rekeyScope(SCOPE)).done).toBe(true) // control: resumed from the rows, nothing kept between runs
    expect(rows().every((n) => n.epoch === 1)).toBe(true)
    expect(keyOf(ids.child)).not.toEqual(K.child)
  })

  it('bite 5: a link from before keeps showing what is added, stays re-issue-required, and a re-issue closes it', async () => {
    await rekeyScope(SCOPE)
    const link = server.shares.find((s) => s.shareId === ids.link)!
    expect(link.sealedShareKeyEpoch, 'the recovery copy stays at its epoch: reissueRequired holds').toBe(0)
    // the old K_share (the one an excluded party can rebuild from the copy) opens the NEW root key
    expect(v1.open(SHARE_KEY, K_SHARE, fromB64std(link.wrap!))).toEqual(keyOf(ids.root))
    // a re-issue's fresh K_share: the old one opens nothing of it
    const fresh = v1.randomBytes(32)
    const reissued = b64std(v1.seal(SHARE_KEY, fresh, keyOf(ids.root)))
    expect(() => v1.open(SHARE_KEY, K_SHARE, fromB64std(reissued))).toThrow(v1.OpenRefused)
    expect(v1.open(SHARE_KEY, fresh, fromB64std(reissued))).toEqual(keyOf(ids.root)) // control
  })

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

describe('any member re-keys (ADR 0111 decision 11)', () => {
  afterEach(() => vi.useRealTimers())

  it('a member\'s run re-keys every node but the drop box, which it leaves for an owner without failing', async () => {
    server.role = 'MEMBER'
    const run = await rekeyScope(SCOPE)
    expect(run).toMatchObject({ done: false, committed: 2 })
    expect([ids.root, ids.child].map((id) => server.nodes.get(id)!.epoch)).toEqual([1, 1])
    expect(server.nodes.get(ids.box)!.epoch, 'the drop box is an owner\'s').toBe(0)
    expect(server.commits).not.toContain(ids.box)
    // control: an owner's run finishes it
    server.role = 'OWNER'
    expect((await rekeyScope(SCOPE)).done).toBe(true)
    expect(server.nodes.get(ids.box)!.epoch).toBe(1)
  })

  it('a path pull re-keys the written node and its lagging ancestors top-down, and nothing else', async () => {
    server.role = 'MEMBER'
    expect((await rekeyPath(SCOPE, [ids.child])).done).toBe(true)
    expect(server.commits).toEqual([ids.root, ids.child])
    expect(server.nodes.get(ids.box)!.epoch).toBe(0)
  })

  it('a path through a node only an owner re-keys is not a member\'s: refused before any commit, and the pull says so', async () => {
    server.role = 'MEMBER'
    await expect(rekeyPath(SCOPE, [ids.box])).rejects.toBeInstanceOf(RekeyPathNotMineError)
    expect(await pullRekeyPath(SCOPE, [ids.box])()).toBe(false)
    expect(server.commits).toEqual([])
    server.role = 'OWNER' // control: an owner's pull brings it forward
    expect(await pullRekeyPath(SCOPE, [ids.box])()).toBe(true)
    expect(server.nodes.get(ids.box)!.epoch).toBe(1)
  })

  /** The server's gate on a write under [nodeId]'s key: `409 node_rekey_pending` while it lags. */
  const writeUnder = (nodeId: string, attempts: number[]) => async (n: number) => {
    attempts.push(n)
    if (server.nodes.get(nodeId)!.epoch < server.current) throw refusal(409, 'node_rekey_pending')
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
