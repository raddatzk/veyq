import { describe, it, expect, vi, beforeEach, type Mock } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

vi.mock('../../api/client', () => ({
  contentApi: vi.fn(),
  loginApi: vi.fn(),
  tokenApi: vi.fn(),
  sessionApi: vi.fn(),
}))

import { listTree, listTrash, restoreNode, renameNode, createFolder, nodeContentKey, openName, slugTokenFor, type TreeNode } from './tree'
import { epochsOf } from '../../features/spaces/epochsTestUtil'
import { contentApi } from '../../api/client'
import { fakeApi } from '../../test/apiMock'
import type { ContentApi, NodeSummary } from '../../api/generated/content'
import * as v1 from '@veyq/crypto/v1'
import { b64std } from '@veyq/crypto/base64'

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
const nameCtx = (nodeId: string, epoch = 0) => ({ purpose: 'node-name', scopeId: SCOPE_ID, nodeId, epoch }) as const
const PARENT = '5c09e000-0000-4000-8000-000000000f09'
const kmemCtx = (nodeId: string, epoch = 0) => ({ purpose: 'content-key', scopeId: SCOPE_ID, nodeId, epoch }) as const
const wrap = (kek: Uint8Array, nodeId: string, k: Uint8Array, epoch = 0) => b64std(v1.seal(kmemCtx(nodeId, epoch), kek, k))
const opened = (key: Uint8Array, nodeId: string, blob: string, epoch = 0) =>
  new TextDecoder().decode(v1.open(nameCtx(nodeId, epoch), key, fromB64(blob)))

describe('nodeContentKey — resolve a chapter key down the chain', () => {
  const FOLD = '5c09e000-0000-4000-8000-000000000f01'
  const MEM = '5c09e000-0000-4000-8000-000000000e01'
  const CHAP = '5c09e000-0000-4000-8000-000000000c01'
  const node = (id: string, parentId: string | null, kind: string, wrappedContentKey: string): TreeNode =>
    ({ id, parentId, kind: kind as TreeNode['kind'], orderIndex: 0, epoch: 0, name: id, wrappedContentKey, wrappedMeta: null, metaVersion: 0 })

  it('walks K_SC → folder key → Memory K_mem → chapter K_mem: a folder is a link of the chain (ADR 0080)', () => {
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
})

describe('createFolder', () => {
  it('at the scope root: key, name and slug all under the write key (ADR 0080)', async () => {
    const create = echoCreate()
    mockContent({ createNode: create })
    const id = await createFolder(SCOPE_ID, epochsOf(scopeKey), 'Trips')
    const req = create.mock.calls[0][0].createNodeRequest
    expect(req.kind).toBe('FOLDER')
    expect(req.parentId).toBeUndefined()
    expect(v1.open(kmemCtx(id), scopeKey, fromB64(req.wrappedContentKey))).toHaveLength(32)
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
    expect(v1.open(kmemCtx(id), parentKey, fromB64(req.wrappedContentKey))).toHaveLength(32)
    expect(() => v1.open(kmemCtx(id), scopeKey, fromB64(req.wrappedContentKey))).toThrow(v1.OpenRefused)
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
    id, parentId, kind: 'FOLDER', epoch, slugToken: 'x', orderIndex: 0, metaVersion: 0,
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
  return { id, parentId: null, kind: 'FOLDER', orderIndex: 0, epoch: 0, name: 'Reisen', wrappedContentKey: wrap(e0, id, key), wrappedMeta: null, metaVersion: 0 }
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
        { id: OLD, parentId: null, kind: 'MEMORY', epoch: 0, encryptedName: sealUnder(e0, OLD, 0, 'Rom 2024'), deletedAt, purgeAfter },
        { id: NEW, parentId: F1, kind: 'FOLDER', epoch: 1, encryptedName: sealUnder(folderKey, NEW, 1, 'Sommer'), deletedAt, purgeAfter },
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

    await restoreNode(SCOPE_ID, epochs, [], { id: 'n1', parentId: null, epoch: 0, name: 'Rom 2026' })

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
    await restoreNode(SCOPE_ID, epochs, [liveParent(F1, scopeKey, folderKey)], { id: N1, parentId: F1, epoch: 0, name: 'Rom 2026' }, 'Rom 2026 (2)')

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
    await expect(restoreNode(SCOPE_ID, epochsOf(scopeKey), [], { id: 'n1', parentId: F1, epoch: 0, name: 'x' })).rejects.toBeInstanceOf(v1.OpenRefused)
    expect(restore).not.toHaveBeenCalled()
  })
})

describe('renameNode', () => {
  it('re-seals the name and the slug under the parent\'s key resolved from the tree', async () => {
    const update = vi.fn<ContentApi['updateNode']>()
    mockContent({ updateNode: update })
    const folderKey = v1.randomBytes(32)
    const N1 = '5c09e000-0000-4000-8000-0000000000b2'

    await renameNode(SCOPE_ID, epochsOf(scopeKey), [liveParent(F1, scopeKey, folderKey)], { id: N1, parentId: F1, epoch: 0 }, 'Neapel')

    const req = update.mock.calls[0][0].updateNodeRequest
    expect(opened(folderKey, N1, req.encryptedName!)).toBe('Neapel')
    expect(req.slugToken).toBe(b64std(v1.slugToken(folderKey, F1, 'neapel')))
  })
})

// The `directory_entry` vector (ADR 0080 Decision 2): the node-name seal and the slug token are unchanged — only the
// key moved to the parent's — so the vector chains the existing `content-key` vector's node (the parent) to a child
// whose entry is under that node's key, and this suite opens it through the tree's own functions.
describe('directory_entry conformance vector', () => {
  const vectors = JSON.parse(readFileSync(resolve(__dirname, '../../../../contracts/crypto/vectors/v1.json'), 'utf8'))
  const d = vectors.directory_entry
  const unhex = (h: string) => Uint8Array.from(h.match(/../g)!.map((b) => parseInt(b, 16)))
  const parentContentKey = unhex(d.parentContentKey)
  const child = { scopeId: d.child.context.scopeId, nodeId: d.child.context.nodeId, epoch: d.child.context.epoch }
  const blob = b64std(unhex(d.child.blob))

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

  it('the child\'s slug token is keyed by the parent\'s content key, and differs from the K_SC-keyed one', () => {
    const hex = (b64: string) => Array.from(fromB64(b64), (x) => x.toString(16).padStart(2, '0')).join('')
    expect(hex(slugTokenFor(parentContentKey, d.parentNodeId, d.slug))).toBe(d.token)
    expect(hex(slugTokenFor(unhex(d.scopeKey), d.parentNodeId, d.slug))).toBe(d.token_under_scope_key)
    expect(d.token).not.toBe(d.token_under_scope_key)
  })
})

function sealUnder(key: Uint8Array, nodeId: string, epoch: number, name: string): string {
  return b64std(v1.seal(nameCtx(nodeId, epoch), key, v1.utf8(name)))
}
