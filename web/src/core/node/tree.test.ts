import { describe, it, expect, vi, beforeEach, type Mock } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

vi.mock('../../api/client', () => ({
  contentApi: vi.fn(),
  loginApi: vi.fn(),
  tokenApi: vi.fn(),
  sessionApi: vi.fn(),
}))

import {
  listTree, listTrash, restoreNode, renameNode, moveNode, createFolder, nodeContentKey, contentKeyChain, openName, slugTokenFor,
  PlacementConflictError, PLACEMENT_RETRIES, sameKeyOr, treeFingerprint, type TreeNode,
} from './tree'
import { httpError } from '../../test/httpError'
import { FOLDER_KIND } from './kind'
import { epochsOf } from '../../features/spaces/epochsTestUtil'
import { contentApi } from '../../api/client'
import { fakeApi } from '../../test/apiMock'
import type { ContentApi, NodeSummary } from '../../api/generated/content'
import * as v1 from '@veyq/crypto/v1'
import { b64std } from '@veyq/crypto/base64'
import { treeNode } from '../../test/models'

const SCOPE_ID = '11111111-1111-1111-1111-111111111111'
const scopeKey = v1.randomBytes(32)

type NodeMethod = 'createNode' | 'listNodes' | 'updateNode' | 'deleteNode' | 'listTrashedNodes' | 'restoreNode'

function mockContent(over: { [K in NodeMethod]?: Mock<ContentApi[K]> }) {
  fakeApi(contentApi, {
    createNode: echoCreate(),
    listNodes: vi.fn<ContentApi['listNodes']>().mockResolvedValue([]),
    updateNode: vi.fn<ContentApi['updateNode']>(),
    deleteNode: vi.fn<ContentApi['deleteNode']>(),
    listTrashedNodes: vi.fn<ContentApi['listTrashedNodes']>().mockResolvedValue([]),
    restoreNode: vi.fn<ContentApi['restoreNode']>(),
    ...over,
  })
}

beforeEach(() => vi.clearAllMocks())

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
/** A createNode mock that echoes the client-minted node id back, as the server does (ADR 0072). */
function echoCreate() {
  return vi.fn<ContentApi['createNode']>(async (p) => ({ nodeId: p.createNodeRequest.nodeId }))
}
const nameCtx = (nodeId: string, epoch = 0) => ({ purpose: 'node-name', scopeId: SCOPE_ID, nodeId, epoch, placementRevision: 0 }) as const
const PARENT = '5c09e000-0000-4000-8000-000000000f09'
const kNodeCtx = (nodeId: string, epoch = 0) => ({ purpose: 'content-key', scopeId: SCOPE_ID, nodeId, epoch, placementRevision: 0 }) as const
const wrap = (kek: Uint8Array, nodeId: string, k: Uint8Array, epoch = 0) => b64std(v1.seal(kNodeCtx(nodeId, epoch), kek, k))
const opened = (key: Uint8Array, nodeId: string, blob: string, epoch = 0) =>
  new TextDecoder().decode(v1.openField(nameCtx(nodeId, epoch), key, fromB64(blob)))

describe('nodeContentKey — resolve a chapter key down the chain', () => {
  const FOLD = '5c09e000-0000-4000-8000-000000000f01'
  const MEM = '5c09e000-0000-4000-8000-000000000e01'
  const CHAP = '5c09e000-0000-4000-8000-000000000c01'
  const node = (id: string, parentId: string | null, kind: string, wrappedContentKey: string): TreeNode =>
    treeNode({ id, parentId, kind: kind as TreeNode['kind'], orderIndex: 0, epoch: 0, name: id, wrappedContentKey, wrappedMeta: null, metaRevision: 0, placementRevision: 0 })

  it('walks K_SC → folder key → Memory K_node → chapter K_node: a folder is a link of the chain (ADR 0080)', () => {
    const folderKey = v1.randomBytes(32)
    const memKey = v1.randomBytes(32)
    const chapKey = v1.randomBytes(32)
    const tree = [
      node(FOLD, null, 'FOLDER', wrap(scopeKey, FOLD, folderKey)),
      node(MEM, FOLD, 'MEMORY', wrap(folderKey, MEM, memKey)),
      node(CHAP, MEM, 'CHAPTER', wrap(memKey, CHAP, chapKey)),
    ]
    expect(Array.from(nodeContentKey(SCOPE_ID, epochsOf(scopeKey), tree, FOLD)!)).toEqual(Array.from(folderKey))
    expect(Array.from(nodeContentKey(SCOPE_ID, epochsOf(scopeKey), tree, MEM)!)).toEqual(Array.from(memKey))
    expect(Array.from(nodeContentKey(SCOPE_ID, epochsOf(scopeKey), tree, CHAP)!)).toEqual(Array.from(chapKey))
  })

  it('a Memory inside a folder wrapped under K_SC (skipping the folder) does not open', () => {
    const memKey = v1.randomBytes(32)
    const tree = [node(FOLD, null, 'FOLDER', wrap(scopeKey, FOLD, v1.randomBytes(32))), node(MEM, FOLD, 'MEMORY', wrap(scopeKey, MEM, memKey))]
    expect(() => nodeContentKey(SCOPE_ID, epochsOf(scopeKey), tree, MEM)).toThrow(v1.OpenRefused)
  })

  it('no chain without the scope-root ancestor, nor through a parent cycle', () => {
    const orphan = [node(MEM, FOLD, 'MEMORY', wrap(scopeKey, MEM, v1.randomBytes(32)))] // its folder is not in the tree
    expect(nodeContentKey(SCOPE_ID, epochsOf(scopeKey), orphan, MEM)).toBeNull()
    const cycle = [node(FOLD, MEM, 'FOLDER', 'eA=='), node(MEM, FOLD, 'MEMORY', 'eA==')]
    expect(nodeContentKey(SCOPE_ID, epochsOf(scopeKey), cycle, MEM)).toBeNull()
  })

  // 5b.4b: a member reads what a guest sealed under an ANCESTOR's key (a folder link's guest name on a Memory below).
  it('contentKeyChain: the node\'s key and every ancestor\'s, nearest first; empty without a chain', () => {
    const folderKey = v1.randomBytes(32)
    const memKey = v1.randomBytes(32)
    const chapKey = v1.randomBytes(32)
    const tree = [
      node(FOLD, null, 'FOLDER', wrap(scopeKey, FOLD, folderKey)),
      node(MEM, FOLD, 'MEMORY', wrap(folderKey, MEM, memKey)),
      node(CHAP, MEM, 'CHAPTER', wrap(memKey, CHAP, chapKey)),
    ]
    expect(contentKeyChain(SCOPE_ID, epochsOf(scopeKey), tree, CHAP).map((k) => Array.from(k)))
      .toEqual([chapKey, memKey, folderKey].map((k) => Array.from(k)))
    expect(contentKeyChain(SCOPE_ID, epochsOf(scopeKey), [node(MEM, FOLD, 'MEMORY', wrap(scopeKey, MEM, memKey))], MEM)).toEqual([])
  })
})

describe('sameKeyOr — the key of the node on screen stays the same object while it is the same key (inbox #503)', () => {
  it('hands back the previous array for the same bytes, the next one for other bytes or none', () => {
    const previous = new Uint8Array(32).fill(5)
    expect(sameKeyOr(previous, new Uint8Array(32).fill(5))).toBe(previous)
    const other = new Uint8Array(32).fill(5)
    other[31] = 6
    expect(sameKeyOr(previous, other)).toBe(other)
    expect(sameKeyOr(previous, new Uint8Array(16).fill(5))).not.toBe(previous)
    expect(sameKeyOr(previous, null)).toBeNull()
    const first = new Uint8Array(32)
    expect(sameKeyOr(undefined, first)).toBe(first)
  })
})

describe('treeFingerprint — a poll that changes only a drop key is not "nothing changed" (inbox #506)', () => {
  const row = (over: Partial<NodeSummary> = {}): NodeSummary => ({
    id: PARENT, kind: 'DEPOT_DROPBOX', epoch: 0, encryptedName: 'bmFtZQ==', slugToken: 's', orderIndex: 0, wrappedContentKey: 'a2V5',
    metaRevision: 0, placementRevision: 0, createdAt: new Date(0), blockedByOperator: false, ...over,
  })
  it("differs when only a node's drop key, or an identity folder's wrapped K_up, does — and not for the same rows", () => {
    expect(treeFingerprint([row()]), 'the control').toBe(treeFingerprint([row()]))
    expect(treeFingerprint([row({ sealedDropPrivateKey: 'ZHJvcA==' })]), "the first WRITE_ONLY link's drop key").not.toBe(treeFingerprint([row()]))
    expect(treeFingerprint([row({ shareIdentityId: 'id-1', wrappedIdentityKey: 'b3RoZXI=' })]))
      .not.toBe(treeFingerprint([row({ shareIdentityId: 'id-1', wrappedIdentityKey: 'a3Vw' })]))
  })
})

describe('createFolder', () => {
  it('at the scope root: key, name and slug all under the write key (ADR 0080)', async () => {
    const create = echoCreate()
    mockContent({ createNode: create })
    const id = await createFolder(SCOPE_ID, epochsOf(scopeKey), 'Trips')
    const req = create.mock.calls[0][0].createNodeRequest
    expect(req.kind).toBe('FOLDER')
    expect(req.parentId).toBeUndefined()
    expect(v1.open(kNodeCtx(id), scopeKey, fromB64(req.wrappedContentKey))).toHaveLength(32)
    expect(req.nodeId).toMatch(UUID)
    expect(id).toBe(req.nodeId)
    expect(opened(scopeKey, id, req.encryptedName)).toBe('Trips')
    expect(req.slugToken).toBe(b64std(v1.slugToken(scopeKey, SCOPE_ID, 'trips')))
  })

  // Bite test (a), the write half: a nested node's directory entry is its parent's (ADR 0080 Decision 2).
  it('below a parent, wraps its key AND seals its name under the PARENT\'s key, never K_SC', async () => {
    const create = echoCreate()
    mockContent({ createNode: create })
    const parentKey = v1.randomBytes(32)
    const id = await createFolder(SCOPE_ID, epochsOf(scopeKey), 'Day trips', { id: PARENT, key: parentKey })
    const req = create.mock.calls[0][0].createNodeRequest
    expect(req.parentId).toBe(PARENT)
    expect(v1.open(kNodeCtx(id), parentKey, fromB64(req.wrappedContentKey))).toHaveLength(32)
    expect(() => v1.open(kNodeCtx(id), scopeKey, fromB64(req.wrappedContentKey))).toThrow(v1.OpenRefused)
    expect(opened(parentKey, id, req.encryptedName)).toBe('Day trips')
    expect(() => opened(scopeKey, id, req.encryptedName)).toThrow(v1.OpenRefused)
  })

  // Bite test (d): the slug key is the parent's content key — stable per parent, so the same name under the same
  // parent collides (the server's UNIQUE(parentId, slugToken) refuses it), and it is not the K_SC-keyed token.
  it('the slug token is keyed by the parent key: equal for the same name under the same parent, unlike K_SC\'s', async () => {
    const create = echoCreate()
    mockContent({ createNode: create })
    const parentKey = v1.randomBytes(32)
    await createFolder(SCOPE_ID, epochsOf(scopeKey), 'Day trips', { id: PARENT, key: parentKey })
    await createFolder(SCOPE_ID, epochsOf(scopeKey), 'Day Trips', { id: PARENT, key: parentKey }) // folds to the same slug
    const [a, b] = create.mock.calls.map((c) => c[0].createNodeRequest.slugToken)
    expect(a).toBe(b)
    expect(a).toBe(b64std(v1.slugToken(parentKey, PARENT, 'day-trips')))
    expect(a).not.toBe(b64std(v1.slugToken(scopeKey, PARENT, 'day-trips')))
  })
})

/** A listed row whose name is sealed under `entryKey` and whose key wraps `ownKey` under the same key. */
function row(id: string, parentId: string | null, entryKey: Uint8Array, ownKey: Uint8Array, name: string, epoch = 0): NodeSummary {
  return {
    blockedByOperator: false,
    id, parentId, kind: 'FOLDER', epoch, slugToken: 'x', orderIndex: 0, metaRevision: 0, placementRevision: 0, createdAt: new Date(0),
    encryptedName: sealUnder(entryKey, id, epoch, name), wrappedContentKey: wrap(entryKey, id, ownKey, epoch),
  }
}

describe('listTree', () => {
  const F1 = '5c09e000-0000-4000-8000-0000000000f1'
  const F2 = '5c09e000-0000-4000-8000-0000000000f2'
  const F3 = '5c09e000-0000-4000-8000-0000000000f3'

  it('decrypts a scope-root name under K_SC', async () => {
    mockContent({ listNodes: vi.fn<ContentApi['listNodes']>().mockResolvedValue([row(F1, null, scopeKey, v1.randomBytes(32), 'Trips')]) })
    const tree = await listTree(SCOPE_ID, epochsOf(scopeKey))
    expect(tree).toHaveLength(1)
    expect(tree[0].name).toBe('Trips')
    expect(tree[0].parentId).toBeNull()
  })

  // Bite test (a), the read half: a nested name opens under its parent's content key, derived top-down in one pass —
  // listed child-first, so the order of the rows is not what makes it work.
  it('opens every nested name under its parent\'s content key, top-down, whatever the row order', async () => {
    const k1 = v1.randomBytes(32)
    const k2 = v1.randomBytes(32)
    mockContent({
      listNodes: vi.fn<ContentApi['listNodes']>().mockResolvedValue([
        row(F3, F2, k2, v1.randomBytes(32), 'Day 1'),
        row(F2, F1, k1, k2, 'Rome'),
        row(F1, null, scopeKey, k1, 'Trips'),
      ]),
    })
    const tree = await listTree(SCOPE_ID, epochsOf(scopeKey))
    expect(Object.fromEntries(tree.map((n) => [n.id, n.name]))).toEqual({ [F1]: 'Trips', [F2]: 'Rome', [F3]: 'Day 1' })
  })

  it('refuses a nested name sealed under K_SC — the pre-ADR-0080 entry — instead of showing it', async () => {
    const k1 = v1.randomBytes(32)
    const child = { ...row(F2, F1, k1, v1.randomBytes(32), 'Rome'), encryptedName: sealUnder(scopeKey, F2, 0, 'Rome') }
    mockContent({ listNodes: vi.fn<ContentApi['listNodes']>().mockResolvedValue([row(F1, null, scopeKey, k1, 'Trips'), child]) })
    await expect(listTree(SCOPE_ID, epochsOf(scopeKey))).rejects.toBeInstanceOf(v1.OpenRefused)
  })

  it('refuses a name the server moved onto another node\'s row', async () => {
    const moved = { ...row(F2, null, scopeKey, v1.randomBytes(32), 'x'), encryptedName: sealUnder(scopeKey, F1, 0, 'Trips') }
    mockContent({ listNodes: vi.fn<ContentApi['listNodes']>().mockResolvedValue([moved]) })
    await expect(listTree(SCOPE_ID, epochsOf(scopeKey))).rejects.toBeInstanceOf(v1.OpenRefused)
  })

  it('a row whose parent chain does not reach the scope root degrades like an unreadable name', async () => {
    const orphan = row(F2, F3, v1.randomBytes(32), v1.randomBytes(32), 'Rome') // its parent is not listed
    mockContent({ listNodes: vi.fn<ContentApi['listNodes']>().mockResolvedValue([row(F1, null, scopeKey, v1.randomBytes(32), 'Trips'), orphan]) })
    await expect(listTree(SCOPE_ID, epochsOf(scopeKey))).rejects.toBeInstanceOf(v1.OpenRefused)
  })
})

function fromB64(s: string): Uint8Array {
  return Uint8Array.from(atob(s), (c) => c.charCodeAt(0))
}

// --- the space node trash (uc.memory.delete-node, ADR 0061) ------------------

/** A live folder `id` at the scope root (epoch 0) under `e0`, carrying `key` — a trashed root's live parent. */
function liveParent(id: string, e0: Uint8Array, key: Uint8Array): TreeNode {
  return treeNode({ id, parentId: null, kind: 'FOLDER', orderIndex: 0, epoch: 0, name: 'Reisen', wrappedContentKey: wrap(e0, id, key), wrappedMeta: null, metaRevision: 0, placementRevision: 0 })
}
const F1 = '5c09e000-0000-4000-8000-0000000000f1'

describe('listTrash', () => {
  it('opens a root entry under ITS OWN epoch key and a nested one under its live parent\'s key; carries both dates', async () => {
    const e0 = v1.randomBytes(32)
    const e1 = v1.randomBytes(32)
    const epochs = { current: 1, keys: new Map([[0, e0], [1, e1]]) }
    const folderKey = v1.randomBytes(32)
    const deletedAt = new Date('2026-08-17T10:15:30Z')
    const purgeAfter = new Date('2026-09-16T10:15:30Z')
    const OLD = '5c09e000-0000-4000-8000-0000000000a1'
    const NEW = '5c09e000-0000-4000-8000-0000000000a2'
    mockContent({
      listTrashedNodes: vi.fn<ContentApi['listTrashedNodes']>().mockResolvedValue([
        { blockedByOperator: false, id: OLD, parentId: null, kind: 'MEMORY', epoch: 0, placementRevision: 0, encryptedName: sealUnder(e0, OLD, 0, 'Rom 2024'), deletedAt, purgeAfter, attached: false },
        { blockedByOperator: false, id: NEW, parentId: F1, kind: 'FOLDER', epoch: 1, placementRevision: 0, encryptedName: sealUnder(folderKey, NEW, 1, 'Sommer'), deletedAt, purgeAfter, attached: false },
      ]),
    })

    const rows = await listTrash(SCOPE_ID, epochs, [liveParent(F1, e0, folderKey)])
    // a trash spanning a rotation decrypts fully — the pre-rotation root opens under E_0, not the write key
    expect(rows.map((r) => r.name)).toEqual(['Rom 2024', 'Sommer'])
    expect(rows[0].parentId).toBeNull()
    expect(rows[1].parentId).toBe(F1)
    expect(rows[0].purgeAfter).toEqual(purgeAfter)
  })
})

describe('restoreNode', () => {
  it('derives the slug token under the node\'s OWN epoch key at the root and sends no name when unchanged', async () => {
    const e0 = v1.randomBytes(32)
    const e1 = v1.randomBytes(32)
    const epochs = { current: 1, keys: new Map([[0, e0], [1, e1]]) }
    const restore = vi.fn<ContentApi['restoreNode']>()
    mockContent({ restoreNode: restore })

    await restoreNode(SCOPE_ID, epochs, [], { id: 'n1', parentId: null, kind: 'FOLDER', epoch: 0, placementRevision: 0, name: 'Rom 2026' })

    const req = restore.mock.calls[0][0].restoreNodeRequest
    // E_0, because the node's epoch is fixed at birth and a restore does not re-tag it (as a rename does not)
    expect(req.slugToken).toBe(b64std(v1.slugToken(e0, SCOPE_ID, 'rom-2026')))
    expect(req.slugToken).not.toBe(b64std(v1.slugToken(e1, SCOPE_ID, 'rom-2026')))
    // an ordinary restore leaves the stored name alone
    expect(req.encryptedName).toBeUndefined()
  })

  it('re-seals the name in the SAME request as the token derived from it, both under the live parent\'s key', async () => {
    const restore = vi.fn<ContentApi['restoreNode']>()
    mockContent({ restoreNode: restore })
    const epochs = epochsOf(scopeKey)
    const folderKey = v1.randomBytes(32)

    const N1 = '5c09e000-0000-4000-8000-0000000000b1'
    await restoreNode(SCOPE_ID, epochs, [liveParent(F1, scopeKey, folderKey)], { id: N1, parentId: F1, kind: 'FOLDER', epoch: 0, placementRevision: 0, name: 'Rom 2026' }, 'Rom 2026 (2)')

    const req = restore.mock.calls[0][0].restoreNodeRequest
    if (req.encryptedName == null) throw new Error('the taken-name retry sent no name')
    // both halves describe the NEW name, and they travel together — a node is never live carrying a slug its
    // own name does not produce
    expect(req.slugToken).toBe(b64std(v1.slugToken(folderKey, F1, 'rom-2026-2')))
    expect(opened(folderKey, N1, req.encryptedName)).toBe('Rom 2026 (2)')
  })

  it('refuses to restore under a parent whose key it cannot derive, rather than sealing under K_SC', async () => {
    const restore = vi.fn<ContentApi['restoreNode']>()
    mockContent({ restoreNode: restore })
    await expect(restoreNode(SCOPE_ID, epochsOf(scopeKey), [], { id: 'n1', parentId: F1, kind: 'FOLDER', epoch: 0, placementRevision: 0, name: 'x' })).rejects.toBeInstanceOf(v1.OpenRefused)
    expect(restore).not.toHaveBeenCalled()
  })
})

// --- placement: move and rename are one write (ADR 0080 Decision 3) ------------------------------------------------

describe('placeNode — move and rename re-wrap and re-seal at the next placement revision', () => {
  const A = '5c09e000-0000-4000-8000-0000000000a0'
  const B = '5c09e000-0000-4000-8000-0000000000b0'
  const M = '5c09e000-0000-4000-8000-0000000000c0'
  const C = '5c09e000-0000-4000-8000-0000000000d0'
  const e0 = v1.randomBytes(32)
  const e1 = v1.randomBytes(32)
  const keys = { A: v1.randomBytes(32), B: v1.randomBytes(32), M: v1.randomBytes(32), C: v1.randomBytes(32) }
  const at = (nodeId: string, epoch: number, placementRevision: number) => ({ scopeId: SCOPE_ID, nodeId, epoch, placementRevision })
  const wrapAt = (kek: Uint8Array, nodeId: string, k: Uint8Array, epoch = 0, revision = 0) =>
    b64std(v1.seal({ purpose: 'content-key', ...at(nodeId, epoch, revision) }, kek, k))
  const unwrapAt = (kek: Uint8Array, nodeId: string, blob: string, epoch = 0, revision = 0) =>
    v1.open({ purpose: 'content-key', ...at(nodeId, epoch, revision) }, kek, fromB64(blob))
  const treeRow = (id: string, parentId: string | null, kind: string, name: string, under: Uint8Array, own: Uint8Array, epoch = 0, revision = 0): TreeNode => treeNode({
    id, parentId, kind, orderIndex: 0, epoch, name, wrappedContentKey: wrapAt(under, id, own, epoch, revision), wrappedMeta: null,
    metaRevision: 0, placementRevision: revision,
  })
  /** Folders A and B at the root (epoch 0), the Memory M in A, its chapter C in M — every entry at revision 0. */
  const tree = (): TreeNode[] => [
    treeRow(A, null, 'FOLDER', 'Reisen', e0, keys.A),
    treeRow(B, null, 'FOLDER', 'Archiv', e0, keys.B),
    treeRow(M, A, 'MEMORY', 'Italien', keys.A, keys.M),
    treeRow(C, M, 'CHAPTER', 'Tag 1', keys.M, keys.C),
  ]
  const rotated = { current: 1, keys: new Map([[0, e0], [1, e1]]) }
  const sent = (update: Mock<ContentApi['updateNode']>, call = 0) => update.mock.calls[call][0].updateNodeRequest

  it('a rename is a placement to the same parent: re-wrapped key, re-sealed name and slug, all at revision + 1', async () => {
    const update = vi.fn<ContentApi['updateNode']>()
    mockContent({ updateNode: update })

    await renameNode(SCOPE_ID, epochsOf(e0), tree(), M, 'Neapel')

    const req = sent(update)
    expect(update.mock.calls[0][0].nodeId).toBe(M)
    expect(req.parentId).toBe(A)
    expect(req.placementRevision).toBe(0) // the revision it was built on; the server stores 1
    expect(req.epoch).toBe(0)
    // the same key, re-wrapped under the parent's key at revision 1 — and refused at the revision it was built on
    expect(Array.from(unwrapAt(keys.A, M, req.wrappedContentKey!, 0, 1))).toEqual(Array.from(keys.M))
    expect(() => unwrapAt(keys.A, M, req.wrappedContentKey!, 0, 0)).toThrow(v1.OpenRefused)
    expect(openName(keys.A, at(M, 0, 1), req.encryptedName!)).toBe('Neapel')
    expect(() => openName(keys.A, at(M, 0, 0), req.encryptedName!)).toThrow(v1.OpenRefused)
    expect(req.slugToken).toBe(b64std(v1.slugToken(keys.A, A, 'neapel')))
  })

  it('a Memory moved into another folder opens its whole chain from there — the wrap only under the new parent', async () => {
    const update = vi.fn<ContentApi['updateNode']>()
    mockContent({ updateNode: update })

    await moveNode(SCOPE_ID, epochsOf(e0), tree(), M, B)

    const req = sent(update)
    expect(req.parentId).toBe(B)
    expect(() => unwrapAt(keys.A, M, req.wrappedContentKey!, 0, 1)).toThrow(v1.OpenRefused) // not the old parent's
    expect(openName(keys.B, at(M, 0, 1), req.encryptedName!)).toBe('Italien') // the name it kept, under B's key
    expect(req.slugToken).toBe(b64std(v1.slugToken(keys.B, B, 'italien')))
    // the tree as the server stores it after the write: the browse opens the Memory and its chapter through B
    const after = tree().map((n) => (n.id === M
      ? { ...n, parentId: B, wrappedContentKey: req.wrappedContentKey!, placementRevision: 1 }
      : n))
    expect(Array.from(nodeContentKey(SCOPE_ID, epochsOf(e0), after, M)!)).toEqual(Array.from(keys.M))
    expect(Array.from(nodeContentKey(SCOPE_ID, epochsOf(e0), after, C)!)).toEqual(Array.from(keys.C))
  })

  it('the epoch: the current one into the root and out of it, the node\'s own between two folders (ADR 0080 Decision 4)', async () => {
    const update = vi.fn<ContentApi['updateNode']>()
    mockContent({ updateNode: update })

    await moveNode(SCOPE_ID, rotated, tree(), M, null) // into the root: under E_current, at the current epoch
    expect(sent(update, 0).epoch).toBe(1)
    expect(sent(update, 0).parentId).toBeUndefined()
    expect(Array.from(unwrapAt(e1, M, sent(update, 0).wrappedContentKey!, 1, 1))).toEqual(Array.from(keys.M))
    expect(sent(update, 0).slugToken).toBe(b64std(v1.slugToken(e1, SCOPE_ID, 'italien')))

    await moveNode(SCOPE_ID, rotated, tree(), A, B) // out of the root: the epoch the server will store, the current
    expect(sent(update, 1).epoch).toBe(1)
    expect(Array.from(unwrapAt(keys.B, A, sent(update, 1).wrappedContentKey!, 1, 1))).toEqual(Array.from(keys.A))

    await moveNode(SCOPE_ID, rotated, tree(), M, B) // between folders: the server keeps the stored epoch, so seal at it
    expect(sent(update, 2).epoch).toBe(0)
    expect(Array.from(unwrapAt(keys.B, M, sent(update, 2).wrappedContentKey!, 0, 1))).toEqual(Array.from(keys.M))
  })

  it('on 409 stale_placement it re-reads the tree once and rebuilds on the revision it finds there', async () => {
    const update = vi.fn<ContentApi['updateNode']>()
      .mockRejectedValueOnce(httpError(409, 'stale_placement'))
      .mockResolvedValueOnce(undefined)
    // what a peer's rename left: M at revision 1, its name and wrap sealed there
    const listed = (): NodeSummary[] => tree().map((n) => ({
      blockedByOperator: false,
      id: n.id, parentId: n.parentId, kind: n.kind, epoch: n.epoch, slugToken: 'x', orderIndex: 0, metaRevision: 0, createdAt: new Date(n.createdAt),
      placementRevision: n.id === M ? 1 : 0,
      encryptedName: b64std(v1.sealField(
        { purpose: 'node-name', ...at(n.id, 0, n.id === M ? 1 : 0) }, n.parentId === null ? e0 : n.parentId === A ? keys.A : keys.M,
        v1.utf8(n.id === M ? 'Rom' : n.name),
      )),
      wrappedContentKey: n.id === M ? wrapAt(keys.A, M, keys.M, 0, 1) : n.wrappedContentKey,
    }))
    const list = vi.fn<ContentApi['listNodes']>().mockImplementation(async () => listed())
    mockContent({ updateNode: update, listNodes: list })

    await renameNode(SCOPE_ID, epochsOf(e0), tree(), M, 'Neapel')

    expect(update).toHaveBeenCalledTimes(2)
    expect(list).toHaveBeenCalledTimes(1)
    expect(sent(update, 1).placementRevision).toBe(1)
    expect(Array.from(unwrapAt(keys.A, M, sent(update, 1).wrappedContentKey!, 0, 2))).toEqual(Array.from(keys.M))
    expect(openName(keys.A, at(M, 0, 2), sent(update, 1).encryptedName!)).toBe('Neapel')
  })

  it('gives up after its bounded retry with its own error, and never retries another failure', async () => {
    const stale = vi.fn<ContentApi['updateNode']>().mockRejectedValue(httpError(409, 'stale_placement'))
    const list = vi.fn<ContentApi['listNodes']>().mockImplementation(async () => [])
    mockContent({ updateNode: stale, listNodes: list })
    // the re-read finds nothing it could rebuild on: the bounded retry is spent on the read, not a loop
    await expect(renameNode(SCOPE_ID, epochsOf(e0), tree(), M, 'Neapel')).rejects.toBeInstanceOf(v1.OpenRefused)

    const twice = vi.fn<ContentApi['updateNode']>().mockRejectedValue(httpError(409, 'stale_placement'))
    mockContent({ updateNode: twice, listNodes: vi.fn<ContentApi['listNodes']>().mockImplementation(async () => tree().map(summaryOf)) })
    await expect(renameNode(SCOPE_ID, epochsOf(e0), tree(), M, 'Neapel')).rejects.toBeInstanceOf(PlacementConflictError)
    expect(twice).toHaveBeenCalledTimes(1 + PLACEMENT_RETRIES)

    const taken = vi.fn<ContentApi['updateNode']>().mockRejectedValue(httpError(409, 'slug_taken'))
    const noRead = vi.fn<ContentApi['listNodes']>()
    mockContent({ updateNode: taken, listNodes: noRead })
    await expect(renameNode(SCOPE_ID, epochsOf(e0), tree(), M, 'Neapel')).rejects.toThrow('slug_taken')
    expect(taken).toHaveBeenCalledTimes(1)
    expect(noRead).not.toHaveBeenCalled()
  })

  // ADR 0080 bite test 4: the revision is part of the address a wrap or a name is opened at.
  it('a wrap or a name served at another placement revision, or on another node\'s row, does not open', () => {
    const moved = treeRow(M, B, 'MEMORY', 'Italien', keys.B, keys.M, 0, 1) // M after a move, at revision 1
    const name = b64std(v1.sealField({ purpose: 'node-name', ...at(M, 0, 1) }, keys.B, v1.utf8('Italien')))
    const base = [tree()[0], tree()[1], moved]
    expect(Array.from(nodeContentKey(SCOPE_ID, epochsOf(e0), base, M)!)).toEqual(Array.from(keys.M)) // control
    expect(openName(keys.B, at(M, 0, 1), name)).toBe('Italien') // control
    // the same wrap and name, listed at the revision before the move (a server replaying the row it superseded)
    expect(() => nodeContentKey(SCOPE_ID, epochsOf(e0), [base[0], base[1], { ...moved, placementRevision: 0 }], M)).toThrow(v1.OpenRefused)
    expect(() => openName(keys.B, at(M, 0, 0), name)).toThrow(v1.OpenRefused)
    // …or on another node's row under the same parent
    const other = { ...moved, id: C }
    expect(() => nodeContentKey(SCOPE_ID, epochsOf(e0), [base[0], base[1], other], C)).toThrow(v1.OpenRefused)
  })

  it('refuses a destination whose key it cannot derive rather than wrapping under something else', async () => {
    const update = vi.fn<ContentApi['updateNode']>()
    mockContent({ updateNode: update })
    await expect(moveNode(SCOPE_ID, epochsOf(e0), tree(), M, '5c09e000-0000-4000-8000-0000000000ff')).rejects.toBeInstanceOf(v1.OpenRefused)
    expect(update).not.toHaveBeenCalled()
  })

  /** A tree row as the server lists it (names opened back from what `tree()` sealed would need the key; the listing
   *  here re-seals each name at revision 0 under the key its entry lives under). */
  function summaryOf(n: TreeNode): NodeSummary {
    const entryKey = n.parentId === null ? e0 : n.parentId === A ? keys.A : n.parentId === B ? keys.B : keys.M
    return {
      blockedByOperator: false,
      id: n.id, parentId: n.parentId, kind: n.kind, epoch: n.epoch, slugToken: 'x', orderIndex: 0, metaRevision: 0, createdAt: new Date(n.createdAt),
      placementRevision: n.placementRevision, wrappedContentKey: n.wrappedContentKey,
      encryptedName: b64std(v1.sealField({ purpose: 'node-name', ...at(n.id, n.epoch, n.placementRevision) }, entryKey, v1.utf8(n.name))),
    }
  }
})

// The `directory_entry` vector (ADR 0080 Decision 2, ADR 0093 § 9): the slug token is keyed by the parent's key and the
// name is a node-name field under a field key derived from it, so the vector chains the existing `content-key` vector's
// node (the parent) to a child whose entry is under that node's key, and this suite opens it through the tree's own
// functions.
describe('directory_entry conformance vector', () => {
  const vectors = JSON.parse(readFileSync(resolve(__dirname, '../../../../contracts/crypto/vectors/v1.json'), 'utf8'))
  const d = vectors.directory_entry
  const unhex = (h: string) => Uint8Array.from(h.match(/../g)!.map((b) => parseInt(b, 16)))
  const parentContentKey = unhex(d.parentContentKey)
  const child = {
    scopeId: d.child.context.scopeId, nodeId: d.child.context.nodeId, epoch: d.child.context.epoch,
    placementRevision: d.child.context.placementRevision,
  }
  const blob = b64std(unhex(d.child.envelope))

  it('chains the content-key vector: its plaintext is the parent\'s content key, its kek the scope key', () => {
    const ck = vectors.seal.sym['content-key']
    expect(d.parentContentKey).toBe(ck.plaintext)
    expect(d.scopeKey).toBe(ck.kek)
    expect(d.parentNodeId).toBe(ck.context.nodeId)
  })

  it('the child\'s name opens under the parent\'s content key, and not under K_SC', () => {
    expect(openName(parentContentKey, child, blob)).toBe(new TextDecoder().decode(unhex(d.child.plaintext)))
    expect(() => openName(unhex(d.scopeKey), child, blob)).toThrow(v1.OpenRefused)
  })

  it('the child\'s field key is the vector\'s, derived from the parent\'s content key and the envelope\'s salt', () => {
    const key = v1.fieldKeyOf({ purpose: 'node-name', ...child }, parentContentKey, unhex(d.child.envelope))
    expect(Array.from(key)).toEqual(Array.from(unhex(d.child.field_key)))
  })

  it('the child\'s slug token is keyed by the parent\'s content key, and differs from the K_SC-keyed one', () => {
    const hex = (b64: string) => Array.from(fromB64(b64), (x) => x.toString(16).padStart(2, '0')).join('')
    expect(hex(slugTokenFor(parentContentKey, d.parentNodeId, FOLDER_KIND, d.slug))).toBe(d.token)
    expect(hex(slugTokenFor(unhex(d.scopeKey), d.parentNodeId, FOLDER_KIND, d.slug))).toBe(d.token_under_scope_key)
    expect(d.token).not.toBe(d.token_under_scope_key)
  })
})

function sealUnder(key: Uint8Array, nodeId: string, epoch: number, name: string): string {
  return b64std(v1.sealField(nameCtx(nodeId, epoch), key, v1.utf8(name)))
}
