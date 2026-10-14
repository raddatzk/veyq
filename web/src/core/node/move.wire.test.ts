// A member's move on the wire (inbox #358): through the move dialog's door (`nodeMover`, the member's) and the real
// placement write (`moveNode`), recorded at `fetch` — the JSON the real generated client serializes. What is pinned is
// what makes a moved subtree keep working (ADR 0080 decisions 1, 3, 4 and 11):
//   - the node's OWN content key travels unchanged, re-wrapped under the destination's key at the NEXT placement
//     version, and the request states the version it was built on (the server's compare-and-set);
//   - the epoch: a move into or out of the scope root seals at the CURRENT epoch (the entry is under `K_SC` there), a
//     move between two folders at the node's stored one;
//   - a drop-box guest's folder moved out: its key opened through the drop key and the guest's `K_up`, re-wrapped under
//     the destination's key — after the server ends its identity, the folder and the file inside it open as before.
// Read back the way the next listing reads it (`nodeContentKey` over the rows as the server then stores them).
import { describe, it, expect, vi, afterEach } from 'vitest'

vi.mock('./treeStore', async () => {
  const { reactive } = await import('vue')
  return { treeStore: reactive({ nodes: [] as unknown[], loaded: true, error: false }), loadTree: vi.fn(async () => {}) }
})

import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'
import { nodeContentKey, wrapContentKey, type TreeNode } from './tree'
import { treeStore, loadTree } from './treeStore'
import { nodeMover } from './move'
import { dropKeysForShare, mintIdentityKey, openDropPublicKey } from '../sharing/dropKey'
import { epochsOf } from '../../features/spaces/epochsTestUtil'
import type { EpochKeys } from '../../features/spaces/epochs'
import { memberAccessOf, treeNode } from '../../test/models'

const SCOPE = '11111111-1111-1111-1111-111111111111'
const A = '5c09e000-0000-4000-8000-0000000000a0'
const B = '5c09e000-0000-4000-8000-0000000000b0'
const M = '5c09e000-0000-4000-8000-0000000000c0'
const C = '5c09e000-0000-4000-8000-0000000000c1'

afterEach(() => vi.unstubAllGlobals())

function recordFetch() {
  const bodies: { url: string; method: string; json: Record<string, unknown> }[] = []
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    bodies.push({ url: String(input), method: init?.method ?? 'GET', json: JSON.parse(String(init?.body)) as Record<string, unknown> })
    return new Response(null, { status: 204 })
  }))
  return bodies
}

/** A row sealed under `entryKey` at (epoch, version) — as the server would serve it. */
function row(id: string, parentId: string | null, kind: string, entryKey: Uint8Array, key: Uint8Array, name: string, epoch: number, placementVersion: number, extra: Partial<TreeNode> = {}): TreeNode {
  const address = { scopeId: SCOPE, nodeId: id, epoch, placementVersion }
  return treeNode({
    id, parentId, kind, name, epoch, placementVersion,
    wrappedContentKey: wrapContentKey(entryKey, address, key), ...extra,
  })
}
/** What the server stores after the placement `json` of `node` (ADR 0080 decision 3; a move out of a drop box ends the
 *  folder's identity, decision 11). */
const applied = (node: TreeNode, json: Record<string, unknown>): TreeNode => ({
  ...node,
  parentId: (json.parentId as string | undefined) ?? null,
  wrappedContentKey: json.wrappedContentKey as string,
  epoch: json.epoch as number,
  placementVersion: node.placementVersion + 1,
  shareIdentityId: null,
  wrappedIdentityKey: null,
})
const same = (a: Uint8Array | null, b: Uint8Array) => expect(Array.from(a ?? [])).toEqual(Array.from(b))

async function moveAsMember(epochs: EpochKeys, nodes: TreeNode[], nodeId: string, to: string | null) {
  treeStore.nodes = nodes
  const bodies = recordFetch()
  await nodeMover(() => memberAccessOf({ scopeId: SCOPE, epochs }))!.move(nodeId, to)
  expect(bodies).toHaveLength(1)
  expect(bodies[0].method).toBe('PATCH')
  expect(bodies[0].url).toContain(`/api/scopes/${SCOPE}/nodes/${nodeId}`)
  expect(loadTree, 'the tree is re-read after the move').toHaveBeenCalled()
  return bodies[0].json
}

describe('a member\'s move between folders', () => {
  const scopeKey = v1.randomBytes(32)
  const keys = { a: v1.randomBytes(32), b: v1.randomBytes(32), m: v1.randomBytes(32), c: v1.randomBytes(32) }
  // A and B at the root, M (version 3) in A with its child C. M was sealed at epoch 0; the scope is at epoch 0.
  const tree = () => [
    row(A, null, 'FOLDER', scopeKey, keys.a, 'Reisen', 0, 0),
    row(B, null, 'FOLDER', scopeKey, keys.b, 'Archiv', 0, 0),
    row(M, A, 'FOLDER', keys.a, keys.m, 'Italien', 0, 3),
    row(C, M, 'FOLDER', keys.m, keys.c, 'Rom', 0, 0),
  ]

  it('re-wraps the node\'s own key under the destination at version + 1, states version 3, keeps the stored epoch', async () => {
    const nodes = tree()
    const json = await moveAsMember(epochsOf(scopeKey), nodes, M, B)
    expect(Object.keys(json).sort()).toEqual(['encryptedName', 'epoch', 'parentId', 'placementVersion', 'slugToken', 'wrappedContentKey'])
    expect(json).toMatchObject({ parentId: B, placementVersion: 3, epoch: 0 })
    const at4 = { purpose: 'content-key', scopeId: SCOPE, nodeId: M, epoch: 0, placementVersion: 4 } as const
    same(v1.open(at4, keys.b, fromB64std(json.wrappedContentKey as string)), keys.m)
    expect(() => v1.open(at4, keys.a, fromB64std(json.wrappedContentKey as string)), 'never the old parent\'s key').toThrow(v1.OpenRefused)
    expect(new TextDecoder().decode(v1.open({ ...at4, purpose: 'node-name' }, keys.b, fromB64std(json.encryptedName as string)))).toBe('Italien')
    expect(json.slugToken).toBe(b64std(v1.slugToken(keys.b, B, 'italien')))
    // The subtree as the server then stores it: the node at B under its new wrap, its child untouched — both open.
    const after = nodes.map((x) => (x.id === M ? applied(x, json) : x))
    same(nodeContentKey(SCOPE, epochsOf(scopeKey), after, M), keys.m)
    same(nodeContentKey(SCOPE, epochsOf(scopeKey), after, C), keys.c)
  })
})

// inbox #421: a selection of several is N of the single node's writes - no batch request - and ONE re-read at the end.
describe('a member moves a selection of several', () => {
  const X = '5c09e000-0000-4000-8000-0000000000c2'
  const scopeKey = v1.randomBytes(32)
  const keys = { a: v1.randomBytes(32), b: v1.randomBytes(32), m: v1.randomBytes(32), c: v1.randomBytes(32), x: v1.randomBytes(32) }
  const tree = () => [
    row(A, null, 'FOLDER', scopeKey, keys.a, 'Reisen', 0, 0),
    row(B, null, 'FOLDER', scopeKey, keys.b, 'Archiv', 0, 0),
    row(M, A, 'FOLDER', keys.a, keys.m, 'Italien', 0, 3),
    row(C, M, 'FOLDER', keys.m, keys.c, 'Rom', 0, 0),
    row(X, A, 'FOLDER', keys.a, keys.x, 'Spanien', 0, 1),
  ]

  it('sends one PATCH per node, each exactly the single node\'s write, and re-reads the tree once', async () => {
    const epochs = epochsOf(scopeKey)
    // the single node's write of each, as the reference
    const single = new Map<string, Record<string, unknown>>()
    for (const id of [M, X]) {
      vi.mocked(loadTree).mockClear()
      single.set(id, await moveAsMember(epochs, tree(), id, B))
    }
    vi.mocked(loadTree).mockClear()
    treeStore.nodes = tree()
    const bodies = recordFetch()
    const out = await nodeMover(() => memberAccessOf({ scopeId: SCOPE, epochs }))!.moveMany([{ nodeId: M, to: B }, { nodeId: X, to: B }])
    expect(out).toEqual([{ nodeId: M, error: null }, { nodeId: X, error: null }])
    expect(bodies.map((b) => [b.method, b.url.replace(/.*\/nodes\//, '')])).toEqual([['PATCH', M], ['PATCH', X]])
    expect(loadTree).toHaveBeenCalledTimes(1)
    for (const [i, id] of [M, X].entries()) {
      const json = bodies[i].json
      // the same fields and the same placement as the single write; a fresh seal, so the ciphertexts differ and both open
      expect(Object.keys(json).sort()).toEqual(Object.keys(single.get(id)!).sort())
      expect({ parentId: json.parentId, epoch: json.epoch, placementVersion: json.placementVersion, slugToken: json.slugToken })
        .toEqual({ parentId: single.get(id)!.parentId, epoch: single.get(id)!.epoch, placementVersion: single.get(id)!.placementVersion, slugToken: single.get(id)!.slugToken })
    }
    const after = tree().map((x) => (x.id === M ? applied(x, bodies[0].json) : x.id === X ? applied(x, bodies[1].json) : x))
    same(nodeContentKey(SCOPE, epochs, after, M), keys.m)
    same(nodeContentKey(SCOPE, epochs, after, C), keys.c)
    same(nodeContentKey(SCOPE, epochs, after, X), keys.x)
  })
})

describe('a member\'s move and the scope\'s epochs (ADR 0080 decision 4)', () => {
  // A scope rotated once: E_0 and E_1, the write key E_1. A and B were created at epoch 0 (their entries under E_0).
  const e0 = v1.randomBytes(32)
  const e1 = v1.randomBytes(32)
  const rotated: EpochKeys = { current: 1, keys: new Map([[0, e0], [1, e1]]) }
  const keys = { a: v1.randomBytes(32), b: v1.randomBytes(32), m: v1.randomBytes(32), c: v1.randomBytes(32) }
  const tree = () => [
    row(A, null, 'FOLDER', e0, keys.a, 'Reisen', 0, 0),
    row(B, null, 'FOLDER', e0, keys.b, 'Archiv', 0, 0),
    row(M, A, 'FOLDER', keys.a, keys.m, 'Italien', 0, 3),
    row(C, M, 'FOLDER', keys.m, keys.c, 'Rom', 0, 0),
  ]

  it('into the scope root: sealed at the CURRENT epoch, under E_1 — never the node\'s old E_0', async () => {
    const nodes = tree()
    const json = await moveAsMember(rotated, nodes, M, null)
    expect(json.parentId, 'the root is no parent on the wire').toBeUndefined()
    expect(json).toMatchObject({ epoch: 1, placementVersion: 3 })
    const wrap = fromB64std(json.wrappedContentKey as string)
    same(v1.open({ purpose: 'content-key', scopeId: SCOPE, nodeId: M, epoch: 1, placementVersion: 4 }, e1, wrap), keys.m)
    expect(() => v1.open({ purpose: 'content-key', scopeId: SCOPE, nodeId: M, epoch: 0, placementVersion: 4 }, e0, wrap)).toThrow(v1.OpenRefused)
    const after = nodes.map((x) => (x.id === M ? applied(x, json) : x))
    same(nodeContentKey(SCOPE, rotated, after, M), keys.m)
    same(nodeContentKey(SCOPE, rotated, after, C), keys.c)
  })

  it('out of the scope root into a folder: the current epoch too (stored as sent), under the folder\'s key', async () => {
    const nodes = tree()
    const json = await moveAsMember(rotated, nodes, A, B)
    expect(json).toMatchObject({ parentId: B, epoch: 1, placementVersion: 0 })
    same(v1.open({ purpose: 'content-key', scopeId: SCOPE, nodeId: A, epoch: 1, placementVersion: 1 }, keys.b, fromB64std(json.wrappedContentKey as string)), keys.a)
    const after = nodes.map((x) => (x.id === A ? applied(x, json) : x))
    for (const [id, key] of [[A, keys.a], [M, keys.m], [C, keys.c]] as const) same(nodeContentKey(SCOPE, rotated, after, id), key)
  })

  it('between two folders: the node\'s stored epoch (0), whatever the current one', async () => {
    const json = await moveAsMember(rotated, tree(), C, B)
    expect(json).toMatchObject({ parentId: B, epoch: 0, placementVersion: 0 })
  })
})

describe('a member moves a drop-box guest\'s folder out (ADR 0080 decision 11)', () => {
  const BOX = '22222222-2222-4222-8222-222222222222'
  const FOLDER = '33333333-3333-4333-8333-333333333333'
  const FILE = '44444444-4444-4444-8444-444444444444'
  const DEST = '66666666-6666-4666-8666-666666666666'
  const IDENTITY = '55555555-5555-4555-8555-555555555555'

  it('opens the folder\'s key through the drop key and K_up, re-wraps it under the destination; after, it and its file open', async () => {
    const scopeKey = v1.randomBytes(32)
    const epochs = epochsOf(scopeKey)
    const boxKey = v1.randomBytes(32)
    const kShare = v1.randomBytes(32)
    const drop = dropKeysForShare(SCOPE, BOX, boxKey, kShare, null)
    const { identityKey, wrappedIdentityKey } = mintIdentityKey(SCOPE, BOX, IDENTITY, openDropPublicKey(kShare, drop.sealedDropPublicKey))
    const keys = { folder: v1.randomBytes(32), file: v1.randomBytes(32), dest: v1.randomBytes(32) }
    const nodes = [
      row(BOX, null, 'FILES_DROPBOX', scopeKey, boxKey, 'Einsendungen', 0, 0, { sealedDropPrivateKey: drop.sealedDropPrivateKey }),
      // the guest sealed its folder's entry under its own K_up
      row(FOLDER, BOX, 'FOLDER', identityKey, keys.folder, 'Adelgunde', 0, 0, { shareIdentityId: IDENTITY, wrappedIdentityKey }),
      row(FILE, FOLDER, 'FILE', keys.folder, keys.file, 'Bericht.pdf', 0, 0),
      row(DEST, null, 'FOLDER', scopeKey, keys.dest, 'Vereinsablage', 0, 0),
    ]
    const json = await moveAsMember(epochs, nodes, FOLDER, DEST)
    expect(json).toMatchObject({ parentId: DEST, placementVersion: 0, epoch: 0 })
    const at1 = { purpose: 'content-key', scopeId: SCOPE, nodeId: FOLDER, epoch: 0, placementVersion: 1 } as const
    same(v1.open(at1, keys.dest, fromB64std(json.wrappedContentKey as string)), keys.folder)
    expect(() => v1.open(at1, identityKey, fromB64std(json.wrappedContentKey as string)), 'no longer under the guest\'s K_up').toThrow(v1.OpenRefused)
    expect(new TextDecoder().decode(v1.open({ ...at1, purpose: 'node-name' }, keys.dest, fromB64std(json.encryptedName as string)))).toBe('Adelgunde')
    // The server ends the identity with the move: the folder is an ordinary one under DEST, its file below it.
    const after = nodes.map((x) => (x.id === FOLDER ? applied(x, json) : x))
    expect(after.find((x) => x.id === FOLDER)!.shareIdentityId).toBeNull()
    same(nodeContentKey(SCOPE, epochs, after, FOLDER), keys.folder)
    same(nodeContentKey(SCOPE, epochs, after, FILE), keys.file)
    // control: the same rows WITHOUT the server ending the identity do not open (the entry is not under K_up any more)
    const stale = after.map((x) => (x.id === FOLDER ? { ...x, shareIdentityId: IDENTITY, wrappedIdentityKey } : x))
    expect(() => nodeContentKey(SCOPE, epochs, stale, FOLDER)).toThrow()
  })
})
