// The order of the unified level list (inbox #407): folders first, then every other node mixed by name.
import { describe, it, expect } from 'vitest'
import { compareByName, orderLevel } from './levelList'
import { treeNode } from '../../test/models'
import type { TreeNode } from './tree'

const n = (id: string, name: string, kind = 'FILE', createdAt = 0): TreeNode => treeNode({ id, name, kind, createdAt })
const names = (list: readonly TreeNode[]) => list.map((x) => x.name)

describe('orderLevel', () => {
  it('lists the folders first, then every other node mixed by name - not grouped by kind', () => {
    const folders = [n('f2', 'Zebra', 'FOLDER'), n('f1', 'Alpha', 'FOLDER')]
    const others = [n('a', 'Report', 'FILE'), n('b', 'Beach', 'MEMORY'), n('c', 'Inbox', 'FILES_DROPBOX'), n('d', 'Aaa', 'FILE')]
    expect(names(orderLevel(folders, others, 'en'))).toEqual(['Alpha', 'Zebra', 'Aaa', 'Beach', 'Inbox', 'Report'])
  })

  it('puts a folder named "Zzz" before a file named "Aaa": the kind decides first, the name second', () => {
    expect(names(orderLevel([n('f', 'Zzz', 'FOLDER')], [n('a', 'Aaa')], 'en'))).toEqual(['Zzz', 'Aaa'])
  })

  it('reads digits as numbers (natural order): "Foto 2" before "Foto 10"', () => {
    const list = orderLevel([], [n('1', 'Foto 10'), n('2', 'Foto 2'), n('3', 'Foto 1')], 'en')
    expect(names(list)).toEqual(['Foto 1', 'Foto 2', 'Foto 10'])
  })

  it('ignores case and accents between equal letters, and sorts by the locale (German a-umlaut beside a)', () => {
    // base sensitivity: "a" and "Ä" are the same letter, so they tie and the creation order (here equal: the id) decides
    expect(names(orderLevel([], [n('1', 'b'), n('2', 'Ä'), n('3', 'a'), n('4', 'C')], 'de'))).toEqual(['Ä', 'a', 'b', 'C'])
    expect(names(orderLevel([], [n('1', 'zebra'), n('2', 'Äpfel')], 'de'))).toEqual(['Äpfel', 'zebra'])
    // case-insensitive: lower case does not sort after every upper case
    expect(names(orderLevel([], [n('1', 'banana'), n('2', 'Apple'), n('3', 'cherry')], 'en'))).toEqual(['Apple', 'banana', 'cherry'])
  })

  it('breaks a tie of equal names by the creation order (the older first, then the id), whatever order the tree listed them in', () => {
    const older = n('z-old', 'Anna', 'FILE', 1000)
    const newer = n('a-new', 'Anna', 'FILE', 2000)
    const sameMs1 = n('b', 'Bob', 'FILE', 5)
    const sameMs2 = n('a', 'Bob', 'FILE', 5)
    expect(orderLevel([], [newer, older], 'en').map((x) => x.id)).toEqual(['z-old', 'a-new'])
    expect(orderLevel([], [older, newer], 'en').map((x) => x.id)).toEqual(['z-old', 'a-new'])
    expect(orderLevel([], [sameMs1, sameMs2], 'en').map((x) => x.id)).toEqual(['a', 'b'])
    // names equal under the comparison (case, accents) are a tie too
    expect(orderLevel([], [n('2', 'anna', 'FILE', 2), n('1', 'ANNA', 'FILE', 1)], 'en').map((x) => x.id)).toEqual(['1', '2'])
  })

  it('does not mutate its inputs', () => {
    const folders = [n('f2', 'B', 'FOLDER'), n('f1', 'A', 'FOLDER')]
    const others = [n('b', 'B'), n('a', 'A')]
    orderLevel(folders, others, 'en')
    expect(names(folders)).toEqual(['B', 'A'])
    expect(names(others)).toEqual(['B', 'A'])
  })

  it('compareByName is a usable comparator on its own', () => {
    expect([n('2', 'b'), n('1', 'a')].sort(compareByName('en')).map((x) => x.id)).toEqual(['1', '2'])
  })
})
