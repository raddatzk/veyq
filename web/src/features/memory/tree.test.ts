import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('../../api/client', () => ({
  contentApi: vi.fn(),
  loginApi: vi.fn(),
  tokenApi: vi.fn(),
  sessionApi: vi.fn(),
}))

import { listTree, listTrash, restoreNode, createFolder, createMemory, createChapter, openMemory, openContentKey, nodeContentKey, type TreeNode } from './tree'
import { epochsOf } from '../spaces/epochsTestUtil'
import { contentApi } from '../../api/client'
import * as v1 from '../../shared/crypto/v1'
import { b64std } from '../../shared/base64'

const SCOPE_ID = '11111111-1111-1111-1111-111111111111'
const scopeKey = v1.randomBytes(32)

function mockContent(over: Partial<Record<'createNode' | 'listNodes' | 'updateNode' | 'deleteNode' | 'listTrashedNodes' | 'restoreNode', unknown>>) {
  vi.mocked(contentApi).mockReturnValue({
    createNode: vi.fn().mockResolvedValue({ nodeId: 'n1' }),
    listNodes: vi.fn().mockResolvedValue([]),
    updateNode: vi.fn(),
    deleteNode: vi.fn(),
    listTrashedNodes: vi.fn().mockResolvedValue([]),
    restoreNode: vi.fn(),
    ...over,
  } as unknown as ReturnType<typeof contentApi>)
}

beforeEach(() => vi.clearAllMocks())

describe('createMemory', () => {
  it('seals the name, blinds the slug, and wraps a K_mem that opens back (operator-blind)', async () => {
    const create = vi.fn().mockResolvedValue({ nodeId: 'mem-1' })
    mockContent({ createNode: create })

    const { nodeId, memoryKey } = await createMemory(SCOPE_ID, epochsOf(scopeKey), 'Italien 2026')
    expect(nodeId).toBe('mem-1')
    expect(memoryKey.length).toBe(32)

    const req = create.mock.calls[0][0].createNodeRequest
    expect(req.kind).toBe('MEMORY')
    // name round-trips under K_SC; the server only ever saw ciphertext
    expect(new TextDecoder().decode(v1.symUnwrap(scopeKey, fromB64(req.encryptedName), 'node-name'))).toBe('Italien 2026')
    // wrapped K_mem opens to exactly the returned key
    expect(Array.from(openMemory(scopeKey, { wrappedContentKey: req.wrappedContentKey })!)).toEqual(Array.from(memoryKey))
    // slug token = blinded MAC under K_SC over the root parent slot (the scope id) + the slug
    expect(req.slugToken).toBe(b64std(v1.slugToken(scopeKey, SCOPE_ID, 'italien-2026')))
  })
})

describe('createChapter — recursive K_mem (cr-0060)', () => {
  it('wraps the chapter K_mem under the PARENT Memory key, not K_SC', async () => {
    const create = vi.fn().mockResolvedValue({ nodeId: 'chap-1' })
    mockContent({ createNode: create })
    const parentKey = v1.randomBytes(32) // the parent Memory's K_mem

    const { nodeId, chapterKey } = await createChapter(SCOPE_ID, epochsOf(scopeKey), parentKey, 'Tag 1', 'mem-1')
    expect(nodeId).toBe('chap-1')

    const req = create.mock.calls[0][0].createNodeRequest
    expect(req.kind).toBe('CHAPTER')
    expect(req.parentId).toBe('mem-1')
    // the name is still sealed under K_SC (whole-tree navigation), the slug too
    expect(new TextDecoder().decode(v1.symUnwrap(scopeKey, fromB64(req.encryptedName), 'node-name'))).toBe('Tag 1')
    expect(req.slugToken).toBe(b64std(v1.slugToken(scopeKey, 'mem-1', 'tag-1')))
    // the K_mem opens under the PARENT key…
    expect(Array.from(openContentKey(parentKey, { wrappedContentKey: req.wrappedContentKey })!)).toEqual(Array.from(chapterKey))
    // …and NOT under K_SC (a wrong KEK can't open it — crypto-enforced least privilege)
    expect(() => openContentKey(scopeKey, { wrappedContentKey: req.wrappedContentKey })).toThrow()
  })
})

describe('nodeContentKey — resolve a chapter key down the chain', () => {
  it('walks K_SC → Memory K_mem → chapter K_mem (folders are transparent)', () => {
    const memKey = v1.randomBytes(32)
    const chapKey = v1.randomBytes(32)
    const wrap = (kek: Uint8Array, k: Uint8Array) => b64std(v1.symWrap(kek, k, 'content-key', v1.randomBytes(12)))
    const tree: TreeNode[] = [
      { id: 'fold', parentId: null, kind: 'FOLDER' as TreeNode['kind'], orderIndex: 0, epoch: 0, name: 'Trips', wrappedContentKey: null, wrappedMeta: null },
      { id: 'mem', parentId: 'fold', kind: 'MEMORY' as TreeNode['kind'], orderIndex: 0, epoch: 0, name: 'Italy', wrappedContentKey: wrap(scopeKey, memKey), wrappedMeta: null },
      { id: 'chap', parentId: 'mem', kind: 'CHAPTER' as TreeNode['kind'], orderIndex: 0, epoch: 0, name: 'Day 1', wrappedContentKey: wrap(memKey, chapKey), wrappedMeta: null },
    ]
    expect(Array.from(nodeContentKey(epochsOf(scopeKey), tree, 'chap')!)).toEqual(Array.from(chapKey))
    expect(Array.from(nodeContentKey(epochsOf(scopeKey), tree, 'mem')!)).toEqual(Array.from(memKey))
    expect(nodeContentKey(epochsOf(scopeKey), tree, 'fold')).toBeNull() // a folder has no content key
  })
})

describe('createFolder', () => {
  it('creates a keyless folder (no wrappedContentKey)', async () => {
    const create = vi.fn().mockResolvedValue({ nodeId: 'f1' })
    mockContent({ createNode: create })
    await createFolder(SCOPE_ID, epochsOf(scopeKey), 'Trips')
    const req = create.mock.calls[0][0].createNodeRequest
    expect(req.kind).toBe('FOLDER')
    expect(req.wrappedContentKey).toBeUndefined()
  })
})

describe('listTree', () => {
  it('decrypts node names under K_SC', async () => {
    const encryptedName = b64std(v1.symWrap(scopeKey, v1.utf8('Trips'), 'node-name', v1.randomBytes(12)))
    mockContent({
      listNodes: vi.fn().mockResolvedValue([
        { id: 'f1', parentId: null, kind: 'FOLDER', encryptedName, slugToken: 'x', orderIndex: 0 },
      ]),
    })
    const tree = await listTree(SCOPE_ID, epochsOf(scopeKey))
    expect(tree).toHaveLength(1)
    expect(tree[0].name).toBe('Trips')
    expect(tree[0].parentId).toBeNull()
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
    mockContent({
      listTrashedNodes: vi.fn().mockResolvedValue([
        { id: 'old', parentId: null, kind: 'MEMORY', epoch: 0, encryptedName: sealUnder(e0, 'Rom 2024'), deletedAt, purgeAfter },
        { id: 'new', parentId: 'f1', kind: 'FOLDER', epoch: 1, encryptedName: sealUnder(e1, 'Reisen'), deletedAt, purgeAfter },
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
    const restore = vi.fn()
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
    const restore = vi.fn()
    mockContent({ restoreNode: restore })
    const epochs = epochsOf(scopeKey)

    await restoreNode(SCOPE_ID, epochs, { id: 'n1', parentId: 'f1', epoch: 0, name: 'Rom 2026' }, 'Rom 2026 (2)')

    const req = restore.mock.calls[0][0].restoreNodeRequest
    // both halves describe the NEW name, and they travel together — a node is never live carrying a slug its
    // own name does not produce
    expect(req.slugToken).toBe(b64std(v1.slugToken(scopeKey, 'f1', 'rom-2026-2')))
    expect(new TextDecoder().decode(v1.symUnwrap(scopeKey, fromB64(req.encryptedName), 'node-name'))).toBe('Rom 2026 (2)')
  })
})

function sealUnder(key: Uint8Array, name: string): string {
  return b64std(v1.symWrap(key, v1.utf8(name), 'node-name', v1.randomBytes(12)))
}
