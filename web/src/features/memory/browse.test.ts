import { describe, it, expect } from 'vitest'
import { childrenOf, breadcrumbOf, nodeById } from './browse'
import { TENANT_SCOPE } from './location'
import { NodeKind } from '../../api/generated/content'
import type { TreeNode } from './tree'

const PERSONAL_SCOPE = '~me'

function node(over: Partial<TreeNode> & Pick<TreeNode, 'id' | 'kind'>): TreeNode {
  return { parentId: null, orderIndex: 0, epoch: 0, name: over.id, wrappedContentKey: null, wrappedMeta: null, metaVersion: 0, ...over }
}

// A small tree: root has folder "Trips" + memory "Solo"; "Trips" holds memory "Italy" + folder "2025".
const tree: TreeNode[] = [
  node({ id: 'trips', kind: NodeKind.Folder, name: 'Trips', orderIndex: 1 }),
  node({ id: 'solo', kind: NodeKind.Memory, name: 'Solo', orderIndex: 0 }),
  node({ id: 'italy', kind: NodeKind.Memory, name: 'Italy', parentId: 'trips' }),
  node({ id: '2025', kind: NodeKind.Folder, name: '2025', parentId: 'trips' }),
  node({ id: 'day1', kind: NodeKind.Chapter, name: 'Day 1', parentId: 'italy', orderIndex: 0 }),
  node({ id: 'day2', kind: NodeKind.Chapter, name: 'Day 2', parentId: 'italy', orderIndex: 1 }),
]

describe('childrenOf', () => {
  it('splits a level into folders + memories, ordered', () => {
    const root = childrenOf(tree, null)
    expect(root.folders.map((n) => n.name)).toEqual(['Trips'])
    expect(root.memories.map((n) => n.name)).toEqual(['Solo'])

    const inTrips = childrenOf(tree, 'trips')
    expect(inTrips.folders.map((n) => n.name)).toEqual(['2025'])
    expect(inTrips.memories.map((n) => n.name)).toEqual(['Italy'])
  })

  it('lists a Memory’s chapters, ordered (cr-0060)', () => {
    const inItaly = childrenOf(tree, 'italy')
    expect(inItaly.chapters.map((n) => n.name)).toEqual(['Day 1', 'Day 2'])
    expect(inItaly.folders).toEqual([]) // no folders inside a Memory
  })

  it('returns empty for a leaf', () => {
    expect(childrenOf(tree, 'solo')).toEqual({ folders: [], memories: [], chapters: [] })
  })
})

describe('breadcrumbOf', () => {
  it('walks root → ancestors → node, each crumb carrying its #fragment in the active scope', () => {
    expect(breadcrumbOf(tree, '2025', 'Personal', PERSONAL_SCOPE)).toEqual([
      { id: null, name: 'Personal', hash: '#~me' },
      { id: 'trips', name: 'Trips', hash: '#~me/trips' },
      { id: '2025', name: '2025', hash: '#~me/trips/2025' },
    ])
  })

  it('builds token-less fragments in the tenant scope', () => {
    expect(breadcrumbOf(tree, '2025', 'Family', TENANT_SCOPE)).toEqual([
      { id: null, name: 'Family', hash: '' },
      { id: 'trips', name: 'Trips', hash: '#trips' },
      { id: '2025', name: '2025', hash: '#trips/2025' },
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
