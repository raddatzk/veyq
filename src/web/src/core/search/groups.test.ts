import { describe, it, expect } from 'vitest'
import { PERSON_DOCUMENT, SETTINGS_DOCUMENT, searchDocument, type SearchDocument } from './model'
import { descendantsOf, firstRanked, searchGroups, type SearchContext } from './groups'
import { byRank, matchAll } from './query'
import { treeNode } from '../../test/models'

const node = (id: string, name: string, scopeId = 'active') =>
  searchDocument({ scopeId, kind: 'MEMORY', id, nodeId: id, revision: '0:0', name, path: [], facets: {} })
const photo = (id: string, name: string, nodeId: string, scopeId = 'active'): SearchDocument =>
  searchDocument({ scopeId, kind: 'memories.photo', id, nodeId, revision: '0', name, path: [], facets: {}, open: () => {} })

const person = (id: string, name: string): SearchDocument =>
  searchDocument({ scopeId: '', kind: PERSON_DOCUMENT, id, nodeId: id, revision: '', name, path: [], facets: {}, open: () => {} })
const section = (id: string, name: string): SearchDocument =>
  searchDocument({ scopeId: '', kind: SETTINGS_DOCUMENT, id, nodeId: id, revision: '', name, path: [], facets: {}, open: () => {} })

// the active space: Trips/ → Rome (open) → Day 1 (a chapter) ; Trips/ → Paris
const tree = [
  treeNode({ id: 'trips', parentId: null, name: 'Trips' }),
  treeNode({ id: 'rome', parentId: 'trips', name: 'Rome' }),
  treeNode({ id: 'day1', parentId: 'rome', name: 'Rome day 1' }),
  treeNode({ id: 'paris', parentId: 'trips', name: 'Paris from Rome' }),
]
const docs = [
  node('trips', 'Trips'), node('rome', 'Rome'), node('day1', 'Rome day 1'), node('paris', 'Paris from Rome'),
  node('o1', 'Rome again', 'other-space'),
  photo('m1', 'Rome beach.jpg', 'rome'),
  photo('m2', 'Rome old.jpg', 'paris'), // contributed for a page no longer open
]
const ctx = (o: Partial<SearchContext> = {}): SearchContext => ({ activeScopeId: 'active', openNodeId: 'rome', activeTree: tree, ...o })
const collator = new Intl.Collator('en')
const shape = (c: SearchContext) =>
  searchGroups(docs, { chips: [], text: 'rome' }, c, collator).map((g) => [g.id, g.results.map((d) => d.name)])

describe('the results grouped by where the member stands', () => {
  it('puts the open node\'s contributions and what lies below it first, the rest of the space next, other spaces last', () => {
    expect(shape(ctx())).toEqual([
      ['open', ['Rome beach.jpg', 'Rome day 1']],
      ['space', ['Rome', 'Paris from Rome']],
      ['other', ['Rome again']],
    ])
  })

  it('counts a contribution only for the node open in the active space', () => {
    expect(shape(ctx({ openNodeId: null }))).toEqual([
      ['space', ['Rome', 'Rome day 1', 'Paris from Rome']],
      ['other', ['Rome again']],
    ])
    expect(shape(ctx({ activeScopeId: 'other-space', openNodeId: 'rome' }))[0]).toEqual(['space', ['Rome again']])
  })

  it('leaves out a group with nothing', () => {
    const groups = searchGroups(docs, { chips: [], text: 'trips' }, ctx(), collator)
    expect(groups.map((g) => g.id)).toEqual(['space'])
  })

  it('ranks within a group, never across: an exact match in another space stays below the space\'s word-start match', () => {
    const extra = [...docs, node('o2', 'Rome', 'other-space')]
    const groups = searchGroups(extra, { chips: [], text: 'rome' }, ctx({ openNodeId: null }), collator)
    expect(groups.map((g) => g.id)).toEqual(['space', 'other'])
    expect(groups[1].results.map((d) => d.name)).toEqual(['Rome', 'Rome again'])
  })

  it('puts the members, then the settings sections, after every space\'s group (ADR 0125 decision 9)', () => {
    const extra = [...docs, person('u1', 'Romy'), section('maps', 'Rome maps')]
    expect(searchGroups(extra, { chips: [], text: 'rom' }, ctx(), collator).map((g) => g.id)).toEqual(['open', 'space', 'other', 'people', 'settings'])
    expect(searchGroups(extra, { chips: [], text: 'romy' }, ctx(), collator).map((g) => g.id)).toEqual(['people']) // no empty group
  })

  it('shows neither group while a chip is set, even for a document the chip would let through', () => {
    const extra = [person('u1', 'Romy'), section('maps', 'Rome maps'), node('rome', 'Rome')]
    const chip = { type: 'kind', kinds: [PERSON_DOCUMENT, SETTINGS_DOCUMENT, 'MEMORY'], label: 'x' } as const
    expect(searchGroups(extra, { chips: [chip], text: 'rom' }, ctx({ openNodeId: null }), collator).map((g) => g.id)).toEqual(['space'])
    // control: without the chip, both are there
    expect(searchGroups(extra, { chips: [], text: 'rom' }, ctx({ openNodeId: null }), collator).map((g) => g.id)).toEqual(['space', 'people', 'settings'])
  })

  it('knows what lies below a node, and survives a cycle', () => {
    expect([...descendantsOf(tree, 'trips')].sort()).toEqual(['day1', 'paris', 'rome'])
    const cyclic = [treeNode({ id: 'a', parentId: 'b' }), treeNode({ id: 'b', parentId: 'a' })]
    expect([...descendantsOf(cyclic, 'a')].sort()).toEqual(['a', 'b'])
  })
})

// cr-search-polish: a group is ranked only as far as it is shown, its count stays exact
describe('a group ranked as far as it is shown', () => {
  // names that tie on rank and differ by collation, case, digits and accents - the order a full sort gives is the answer
  const many = Array.from({ length: 300 }, (_, i) =>
    node(`n${i}`, `${['Rom', 'rom', 'Röm', 'Roma', 'Trip to Rome', 'Aroma'][i % 6]} ${(i * 7919) % 300}`))

  it('selects the same first N as a full sort, for every N', () => {
    const matches = matchAll(many, { chips: [], text: 'rom' })
    const order = byRank(collator)
    const full = [...matches].sort(order).map((m) => m.doc.id)
    for (const n of [0, 1, 8, 16, 299, 300, 301]) expect(firstRanked(matches, n, order).map((m) => m.doc.id), `n=${n}`).toEqual(full.slice(0, n))
  })

  it('leaves the matches it was handed as they were', () => {
    const matches = matchAll(many, { chips: [], text: 'rom' })
    const before = matches.map((m) => m.doc.id)
    firstRanked(matches, 8, byRank(collator))
    expect(matches.map((m) => m.doc.id)).toEqual(before)
  })

  it('hands each group its first `limit` results and its exact total', () => {
    const groups = searchGroups(many, { chips: [], text: 'rom' }, ctx({ openNodeId: null, activeTree: [] }), collator, { limits: { space: 8 } })
    expect(groups.map((g) => [g.id, g.results.length, g.total])).toEqual([['space', 8, 300]])
    const whole = searchGroups(many, { chips: [], text: 'rom' }, ctx({ openNodeId: null, activeTree: [] }), collator)
    expect(groups[0].results).toEqual(whole[0].results.slice(0, 8))
    expect(whole[0].results).toHaveLength(300)
  })
})
