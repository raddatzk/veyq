import { describe, it, expect } from 'vitest'
import * as v1 from '@veyq/crypto/v1'
import { keyResolver } from './keyResolver'
import { wrapContentKey, type TreeNode } from './tree'
import { treeNode } from '../../test/models'

const SCOPE = '00000000-0000-4000-8000-000000000101'
const id = (n: number) => `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`
const addr = (nodeId: string) => ({ scopeId: SCOPE, nodeId, scopeEpoch: 0, placementRevision: 0 })

describe('keyResolver', () => {
  const scopeKey = v1.randomBytes(32)
  const rootKey = v1.randomBytes(32)
  const childKey = v1.randomBytes(32)
  const root: TreeNode = treeNode({ id: id(1), parentId: null, wrappedContentKey: wrapContentKey(scopeKey, addr(id(1)), rootKey) })
  const child: TreeNode = treeNode({ id: id(2), parentId: id(1), wrappedContentKey: wrapContentKey(rootKey, addr(id(2)), childKey) })
  const orphan: TreeNode = treeNode({ id: id(3), parentId: id(9), wrappedContentKey: wrapContentKey(rootKey, addr(id(3)), childKey) })

  it('opens each node down its chain, once', () => {
    const r = keyResolver(SCOPE, { epoch: 0, key: scopeKey }, [root, child, orphan])
    expect(r.keyOf(child)).toEqual(childKey)
    expect(r.keyOf(root)).toEqual(rootKey)
    expect(r.keyOf(child)).toBe(r.keyOf(child))
    expect(r.keyOf(orphan)).toBeNull()
  })

  it('zeroes every key it opened and none of the caller\'s', () => {
    const scope = scopeKey.slice()
    const r = keyResolver(SCOPE, { epoch: 0, key: scope }, [root, child])
    const opened = [r.keyOf(root)!, r.keyOf(child)!]
    r.zero()
    for (const k of opened) expect(k.every((b) => b === 0)).toBe(true)
    expect(scope).toEqual(scopeKey)
    expect(r.keyOf(child)).toBeNull()
  })
})
