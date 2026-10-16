import { describe, it, expect } from 'vitest'
import { childrenOf, breadcrumbOf, nodeById } from './browse'
import { TENANT_SCOPE } from './location'
import { FOLDER_KIND, type NodeKind } from './kind'
import { MEMORY_KIND, CHAPTER_KIND } from '../../features/memory/kinds'
import type { TreeNode } from './tree'
import { treeNode } from '../../test/models'

const PERSONAL_SCOPE = '~me'

function node(over: Partial<TreeNode> & Pick<TreeNode, 'id' | 'kind'>): TreeNode {
  return treeNode({ parentId: null, orderIndex: 0, epoch: 0, name: over.id, wrappedContentKey: 'eA==', wrappedMeta: null, metaRevision: 0, placementRevision: 0, ...over })
}

// A small tree: root has folder "Trips" + memory "Solo"; "Trips" holds memory "Italy" + folder "2025".
const tree: TreeNode[] = [
  node({ id: 'trips', kind: FOLDER_KIND, name: 'Trips', orderIndex: 1 }),
  node({ id: 'solo', kind: MEMORY_KIND, name: 'Solo', orderIndex: 0 }),
  node({ id: 'italy', kind: MEMORY_KIND, name: 'Italy', parentId: 'trips' }),
  node({ id: '2025', kind: FOLDER_KIND, name: '2025', parentId: 'trips' }),
  node({ id: 'day1', kind: CHAPTER_KIND, name: 'Day 1', parentId: 'italy', orderIndex: 0 }),
  node({ id: 'day2', kind: CHAPTER_KIND, name: 'Day 2', parentId: 'italy', orderIndex: 1 }),
]

describe('childrenOf', () => {
  it('splits a level into folders + per-kind buckets, ordered', () => {
    const root = childrenOf(tree, null)
    expect(root.folders.map((n) => n.name)).toEqual(['Trips'])
    expect(root.byKind.get(MEMORY_KIND)?.map((n) => n.name)).toEqual(['Solo'])

    const inTrips = childrenOf(tree, 'trips')
    expect(inTrips.folders.map((n) => n.name)).toEqual(['2025'])
    expect(inTrips.byKind.get(MEMORY_KIND)?.map((n) => n.name)).toEqual(['Italy'])
  })

  it('lists a Memory’s chapters, ordered (cr-0060)', () => {
    const inItaly = childrenOf(tree, 'italy')
    expect(inItaly.byKind.get(CHAPTER_KIND)?.map((n) => n.name)).toEqual(['Day 1', 'Day 2'])
    expect(inItaly.folders).toEqual([]) // no folders inside a Memory
  })

  it('orders every bucket by orderIndex, then name — whatever the tree order', () => {
    const shuffled: TreeNode[] = [
      node({ id: 'b', kind: MEMORY_KIND, name: 'B', orderIndex: 1 }),
      node({ id: 'z', kind: MEMORY_KIND, name: 'Z', orderIndex: 0 }),
      node({ id: 'a', kind: MEMORY_KIND, name: 'A', orderIndex: 1 }),
      node({ id: 'f2', kind: FOLDER_KIND, name: 'F2', orderIndex: 2 }),
      node({ id: 'f1', kind: FOLDER_KIND, name: 'F1', orderIndex: 2 }),
      node({ id: 'f0', kind: FOLDER_KIND, name: 'F0', orderIndex: 0 }),
    ]
    const root = childrenOf(shuffled, null)
    expect(root.byKind.get(MEMORY_KIND)?.map((n) => n.name)).toEqual(['Z', 'A', 'B'])
    expect(root.folders.map((n) => n.name)).toEqual(['F0', 'F1', 'F2'])
  })

  it('keeps a kind core has never heard of: it gets its own bucket and does not leak into the others', () => {
    const odd = [...tree, node({ id: 'x', kind: 'FUTURE' as NodeKind, name: 'X' })]
    const root = childrenOf(odd, null)
    expect(root.byKind.get('FUTURE' as NodeKind)?.map((n) => n.name)).toEqual(['X'])
    expect(root.byKind.get(MEMORY_KIND)?.map((n) => n.name)).toEqual(['Solo'])
  })

  it('returns empty for a leaf: no folders, no kind keys at all', () => {
    const leaf = childrenOf(tree, 'solo')
    expect(leaf.folders).toEqual([])
    expect(leaf.byKind.size).toBe(0)
  })
})

describe('breadcrumbOf', () => {
  it('walks root → ancestors → node, each crumb carrying its #fragment in the active scope (a folder\'s ends in the slash that marks it)', () => {
    expect(breadcrumbOf(tree, '2025', 'Personal', PERSONAL_SCOPE)).toEqual([
      { id: null, name: 'Personal', hash: '#~me' },
      { id: 'trips', name: 'Trips', hash: '#~me/trips/' },
      { id: '2025', name: '2025', hash: '#~me/trips/2025/' },
    ])
  })

  it('builds token-less fragments in the tenant scope', () => {
    expect(breadcrumbOf(tree, '2025', 'Family', TENANT_SCOPE)).toEqual([
      { id: null, name: 'Family', hash: '' },
      { id: 'trips', name: 'Trips', hash: '#trips/' },
      { id: '2025', name: '2025', hash: '#trips/2025/' },
    ])
  })

  it('is just the root at the top level', () => {
    expect(breadcrumbOf(tree, null, 'Personal', PERSONAL_SCOPE)).toEqual([
      { id: null, name: 'Personal', hash: '#~me' },
    ])
  })
})

describe('nodeById', () => {
  it('finds a node or returns null', () => {
    expect(nodeById(tree, 'italy')?.name).toBe('Italy')
    expect(nodeById(tree, null)).toBeNull()
    expect(nodeById(tree, 'nope')).toBeNull()
  })
})
