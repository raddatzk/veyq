// Where a node lies (ADR 0125 decision 4): a document carries the ids and names of the nodes above it, which an *In:* chip
// and the open node's place in the order read. The web builds them from its tree (`documents.ts`); the core reads them.
import { describe, it, expect } from 'vitest'
import { placeOf } from './documents'
import { treeNode } from '../../test/models'

describe('where a node lies', () => {
  it('is the ids above it, outermost first, and survives a cycle', () => {
    const tree = [treeNode({ id: 'trips', parentId: null, name: 'Trips' }), treeNode({ id: 'rome', parentId: 'trips', name: 'Rome' }),
      treeNode({ id: 'day1', parentId: 'rome', name: 'Day 1' })]
    const byId = new Map(tree.map((n) => [n.id, n]))
    expect(placeOf(tree[2], byId)).toEqual({ names: ['Trips', 'Rome'], ids: ['trips', 'rome'] })
    const cyclic = [treeNode({ id: 'a', parentId: 'b', name: 'A' }), treeNode({ id: 'b', parentId: 'a', name: 'B' })]
    expect(placeOf(cyclic[0], new Map(cyclic.map((n) => [n.id, n]))).ids).toEqual(['b'])
  })
})
