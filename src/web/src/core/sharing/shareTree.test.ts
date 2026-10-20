// The kind-independent subtree walk (cr-node-key-hierarchy 5b.2): a guest opens each descendant's key and name under its
// parent's key, top-down from the root key the share's own wrap holds. Kinds here are the platform's own `FOLDER` and
// an arbitrary token — the walk never reads one. The Memories suite (`features/memories/share.test.ts`) drives the same
// walk end to end through `openSharedMemory`.
import { describe, it, expect } from 'vitest'
import * as v1 from '@veyq/crypto/v1'
import { b64std } from '@veyq/crypto/base64'
import { openShareRoot, walkSubtree, SHARE_KEY } from './shareTree'
import { ShareUnavailableError } from './resolvePublicShare'
import { FOLDER_KIND } from '../node/kind'
import type { ShareNode } from '../../api/generated/sharing'

const SCOPE_ID = '7c3f0e2a-9b1d-4a6e-8b2c-1f2d3e4a5b6c'
const ROOT = '4a6e8b2c-1f2d-3e4a-5b6c-7c3f0e2a9b1d'
const CHILD = '2a9b1d4a-6e8b-2c1f-2d3e-4a5b6c7c3f0e'
const GRANDCHILD = '5c09e000-0000-4000-8000-00000000ce02'
const SIBLING = '5c09e000-0000-4000-8000-00000000ce03'

/** A node's directory entry under its parent's key, as the tree seals it (ADR 0080 Decisions 2 and 3). */
function entry(parentKey: Uint8Array, nodeId: string, name: string, key: Uint8Array, placementRevision = 0) {
  const ctx = { scopeId: SCOPE_ID, nodeId, epoch: 0, placementRevision }
  return {
    placementRevision,
    wrappedContentKey: b64std(v1.seal({ purpose: 'content-key', ...ctx }, parentKey, key)),
    encryptedName: b64std(v1.sealField({ purpose: 'node-name', ...ctx }, parentKey, v1.utf8(name))),
  }
}

describe('walkSubtree', () => {
  it('opens each descendant under its parent key, the root has no name, and returns the opened nodes', () => {
    const rootKey = v1.randomBytes(32)
    const childKey = v1.randomBytes(32)
    const grandKey = v1.randomBytes(32)
    const nodes: ShareNode[] = [
      { blockedByOperator: false, id: ROOT, parentId: null, kind: FOLDER_KIND, epoch: 0 },
      { blockedByOperator: false, id: CHILD, parentId: ROOT, kind: FOLDER_KIND, epoch: 0, ...entry(rootKey, CHILD, 'Taxes', childKey) },
      { blockedByOperator: false, id: GRANDCHILD, parentId: CHILD, kind: 'EXPENSE', epoch: 0, ...entry(childKey, GRANDCHILD, '2026', grandKey) },
    ]

    const walk = walkSubtree(SCOPE_ID, nodes, rootKey)

    expect(walk.keys.get(ROOT)).toEqual(rootKey)
    expect(walk.keys.get(CHILD)).toEqual(childKey)
    expect(walk.keys.get(GRANDCHILD)).toEqual(grandKey)
    expect(Object.fromEntries(walk.names)).toEqual({ [CHILD]: 'Taxes', [GRANDCHILD]: '2026' })
    expect([...walk.nodes.keys()].sort()).toEqual([ROOT, CHILD, GRANDCHILD].sort())
    expect(walk.nodes.get(GRANDCHILD)?.kind).toBe('EXPENSE')
  })

  it('skips a node sealed under another key and everything below it, and keeps its siblings', () => {
    const rootKey = v1.randomBytes(32)
    const childKey = v1.randomBytes(32)
    const nodes: ShareNode[] = [
      { blockedByOperator: false, id: ROOT, parentId: null, kind: FOLDER_KIND, epoch: 0 },
      // sealed under a key the guest does not hold (e.g. the scope key): neither it nor its child opens
      { blockedByOperator: false, id: CHILD, parentId: ROOT, kind: FOLDER_KIND, epoch: 0, ...entry(v1.randomBytes(32), CHILD, 'Hidden', childKey) },
      { blockedByOperator: false, id: GRANDCHILD, parentId: CHILD, kind: FOLDER_KIND, epoch: 0, ...entry(childKey, GRANDCHILD, 'Below', v1.randomBytes(32)) },
      { blockedByOperator: false, id: SIBLING, parentId: ROOT, kind: FOLDER_KIND, epoch: 0, ...entry(rootKey, SIBLING, 'Open', v1.randomBytes(32)) },
    ]

    const walk = walkSubtree(SCOPE_ID, nodes, rootKey)

    expect([...walk.keys.keys()].sort()).toEqual([ROOT, SIBLING].sort())
    expect(Object.fromEntries(walk.names)).toEqual({ [SIBLING]: 'Open' })
    expect(walk.nodes.has(CHILD)).toBe(false)
  })

  it('opens nothing at a placement revision other than the one served with the entry', () => {
    const rootKey = v1.randomBytes(32)
    const sealed = entry(rootKey, CHILD, 'Renamed', v1.randomBytes(32), 2)
    const nodes: ShareNode[] = [
      { blockedByOperator: false, id: ROOT, parentId: null, kind: FOLDER_KIND, epoch: 0 },
      { blockedByOperator: false, id: CHILD, parentId: ROOT, kind: FOLDER_KIND, epoch: 0, ...sealed, placementRevision: 1 },
    ]
    expect(walkSubtree(SCOPE_ID, nodes, rootKey).names.size).toBe(0)
  })

  it('returns empty maps when no root is served', () => {
    const walk = walkSubtree(SCOPE_ID, [], v1.randomBytes(32))
    expect(walk.keys.size + walk.names.size + walk.nodes.size).toBe(0)
  })
})

describe('openShareRoot', () => {
  it("unwraps the root key from the share's wrap with K_share and carries the opened name", () => {
    const kShare = v1.randomBytes(32)
    const rootKey = v1.randomBytes(32)
    const wrap = b64std(v1.seal(SHARE_KEY, kShare, rootKey))
    expect(openShareRoot({ kShare, name: 'Taxes', share: { wrap } })).toEqual({ key: rootKey, name: 'Taxes' })
  })

  it('refuses a share without a wrap (not_found) and a wrap under another key (bad_fragment_key)', () => {
    const kShare = v1.randomBytes(32)
    expect(() => openShareRoot({ kShare, name: 'x', share: { wrap: undefined } })).toThrow(new ShareUnavailableError('not_found'))
    const foreign = b64std(v1.seal(SHARE_KEY, v1.randomBytes(32), v1.randomBytes(32)))
    expect(() => openShareRoot({ kShare, name: 'x', share: { wrap: foreign } })).toThrow(new ShareUnavailableError('bad_fragment_key'))
  })
})
