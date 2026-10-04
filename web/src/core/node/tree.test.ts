import { describe, it, expect, vi, beforeEach, type Mock } from 'vitest'

vi.mock('../../api/client', () => ({
  contentApi: vi.fn(),
  loginApi: vi.fn(),
  tokenApi: vi.fn(),
  sessionApi: vi.fn(),
}))

import { listTree, listTrash, restoreNode, createFolder, nodeContentKey, type TreeNode } from './tree'
import { epochsOf } from '../../features/spaces/epochsTestUtil'
import { contentApi } from '../../api/client'
import { fakeApi } from '../../test/apiMock'
import type { ContentApi } from '../../api/generated/content'
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
const kmemCtx = (nodeId: string, epoch = 0) => ({ purpose: 'content-key', scopeId: SCOPE_ID, nodeId, epoch }) as const

describe('nodeContentKey — resolve a chapter key down the chain', () => {
  it('walks K_SC → Memory K_mem → chapter K_mem (folders are transparent)', () => {
    const memKey = v1.randomBytes(32)
    const chapKey = v1.randomBytes(32)
    const FOLD = '5c09e000-0000-4000-8000-000000000f01'
    const MEM = '5c09e000-0000-4000-8000-000000000e01'
    const CHAP = '5c09e000-0000-4000-8000-000000000c01'
    const wrap = (kek: Uint8Array, nodeId: string, k: Uint8Array) => b64std(v1.seal(kmemCtx(nodeId), kek, k))
    const tree: TreeNode[] = [
      { id: FOLD, parentId: null, kind: 'FOLDER' as TreeNode['kind'], orderIndex: 0, epoch: 0, name: 'Trips', wrappedContentKey: null, wrappedMeta: null, metaVersion: 0 },
      { id: MEM, parentId: FOLD, kind: 'MEMORY' as TreeNode['kind'], orderIndex: 0, epoch: 0, name: 'Italy', wrappedContentKey: wrap(scopeKey, MEM, memKey), wrappedMeta: null, metaVersion: 0 },
      { id: CHAP, parentId: MEM, kind: 'CHAPTER' as TreeNode['kind'], orderIndex: 0, epoch: 0, name: 'Day 1', wrappedContentKey: wrap(memKey, CHAP, chapKey), wrappedMeta: null, metaVersion: 0 },
    ]
    expect(Array.from(nodeContentKey(SCOPE_ID, epochsOf(scopeKey), tree, CHAP)!)).toEqual(Array.from(chapKey))
    expect(Array.from(nodeContentKey(SCOPE_ID, epochsOf(scopeKey), tree, MEM)!)).toEqual(Array.from(memKey))
    expect(nodeContentKey(SCOPE_ID, epochsOf(scopeKey), tree, FOLD)).toBeNull() // a folder has no content key
  })
})

describe('createFolder', () => {
  it('creates a keyless folder (no wrappedContentKey)', async () => {
    const create = echoCreate()
    mockContent({ createNode: create })
    const id = await createFolder(SCOPE_ID, epochsOf(scopeKey), 'Trips')
    const req = create.mock.calls[0][0].createNodeRequest
    expect(req.kind).toBe('FOLDER')
    expect(req.wrappedContentKey).toBeUndefined()
    expect(req.nodeId).toMatch(UUID)
    expect(id).toBe(req.nodeId)
    expect(new TextDecoder().decode(v1.open(nameCtx(id), scopeKey, fromB64(req.encryptedName)))).toBe('Trips')
  })
})

describe('listTree', () => {
  it('decrypts node names under K_SC', async () => {
    const F1 = '5c09e000-0000-4000-8000-0000000000f1'
    const encryptedName = sealUnder(scopeKey, F1, 0, 'Trips')
    mockContent({
      listNodes: vi.fn<ContentApi['listNodes']>().mockResolvedValue([
        { id: F1, parentId: null, kind: 'FOLDER', epoch: 0, encryptedName, slugToken: 'x', orderIndex: 0, metaVersion: 0 },
      ]),
    })
    const tree = await listTree(SCOPE_ID, epochsOf(scopeKey))
    expect(tree).toHaveLength(1)
    expect(tree[0].name).toBe('Trips')
    expect(tree[0].parentId).toBeNull()
  })

  it('refuses a name the server moved onto another node\'s row', async () => {
    const encryptedName = sealUnder(scopeKey, '5c09e000-0000-4000-8000-0000000000f1', 0, 'Trips')
    mockContent({
      listNodes: vi.fn<ContentApi['listNodes']>().mockResolvedValue([
        { id: '5c09e000-0000-4000-8000-0000000000f2', parentId: null, kind: 'FOLDER', epoch: 0, encryptedName, slugToken: 'x', orderIndex: 0, metaVersion: 0 },
      ]),
    })
    await expect(listTree(SCOPE_ID, epochsOf(scopeKey))).rejects.toBeInstanceOf(v1.OpenRefused)
  })
})

function fromB64(s: string): Uint8Array {
  return Uint8Array.from(atob(s), (c) => c.charCodeAt(0))
}

// --- the space node trash (uc.memory.delete-node, ADR 0061) ------------------

describe('listTrash', () => {
  it('opens each trashed root\'s name under ITS OWN epoch key and carries both lifecycle dates', async () => {
    const e0 = v1.randomBytes(32)
    const e1 = v1.randomBytes(32)
    const epochs = { current: 1, keys: new Map([[0, e0], [1, e1]]) }
    const deletedAt = new Date('2026-08-17T10:15:30Z')
    const purgeAfter = new Date('2026-09-16T10:15:30Z')
    const OLD = '5c09e000-0000-4000-8000-0000000000a1'
    const NEW = '5c09e000-0000-4000-8000-0000000000a2'
    mockContent({
      listTrashedNodes: vi.fn<ContentApi['listTrashedNodes']>().mockResolvedValue([
        { id: OLD, parentId: null, kind: 'MEMORY', epoch: 0, encryptedName: sealUnder(e0, OLD, 0, 'Rom 2024'), deletedAt, purgeAfter },
        { id: NEW, parentId: 'f1', kind: 'FOLDER', epoch: 1, encryptedName: sealUnder(e1, NEW, 1, 'Reisen'), deletedAt, purgeAfter },
      ]),
    })

    const rows = await listTrash(SCOPE_ID, epochs)
    // a trash spanning a rotation decrypts fully — the pre-rotation root opens under E_0, not the write key
    expect(rows.map((r) => r.name)).toEqual(['Rom 2024', 'Reisen'])
    expect(rows[0].parentId).toBeNull()
    expect(rows[1].parentId).toBe('f1')
    expect(rows[0].purgeAfter).toEqual(purgeAfter)
  })
})

describe('restoreNode', () => {
  it('derives the slug token under the node\'s OWN epoch key and sends no name when the name is unchanged', async () => {
    const e0 = v1.randomBytes(32)
    const e1 = v1.randomBytes(32)
    const epochs = { current: 1, keys: new Map([[0, e0], [1, e1]]) }
    const restore = vi.fn<ContentApi['restoreNode']>()
    mockContent({ restoreNode: restore })

    await restoreNode(SCOPE_ID, epochs, { id: 'n1', parentId: null, epoch: 0, name: 'Rom 2026' })

    const req = restore.mock.calls[0][0].restoreNodeRequest
    // E_0, because the node's epoch is fixed at birth and a restore does not re-tag it (as a rename does not)
    expect(req.slugToken).toBe(b64std(v1.slugToken(e0, SCOPE_ID, 'rom-2026')))
    expect(req.slugToken).not.toBe(b64std(v1.slugToken(e1, SCOPE_ID, 'rom-2026')))
    // an ordinary restore leaves the stored name alone
    expect(req.encryptedName).toBeUndefined()
  })

  it('re-seals the name in the SAME request as the token derived from it, on the taken-name retry', async () => {
    const restore = vi.fn<ContentApi['restoreNode']>()
    mockContent({ restoreNode: restore })
    const epochs = epochsOf(scopeKey)

    const N1 = '5c09e000-0000-4000-8000-0000000000b1'
    await restoreNode(SCOPE_ID, epochs, { id: N1, parentId: 'f1', epoch: 0, name: 'Rom 2026' }, 'Rom 2026 (2)')

    const req = restore.mock.calls[0][0].restoreNodeRequest
    if (req.encryptedName == null) throw new Error('the taken-name retry sent no name')
    // both halves describe the NEW name, and they travel together — a node is never live carrying a slug its
    // own name does not produce
    expect(req.slugToken).toBe(b64std(v1.slugToken(scopeKey, 'f1', 'rom-2026-2')))
    expect(new TextDecoder().decode(v1.open(nameCtx(N1), scopeKey, fromB64(req.encryptedName)))).toBe('Rom 2026 (2)')
  })
})

function sealUnder(key: Uint8Array, nodeId: string, epoch: number, name: string): string {
  return b64std(v1.seal(nameCtx(nodeId, epoch), key, v1.utf8(name)))
}
