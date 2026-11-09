// The one list of results (ADR 0125 decisions 5 and 9): the match first, then where the result
// lies, then the name; a chip is a predicate over every document, people and settings sections included; the list is ranked
// only as far as it is drawn, its count exact.
import { describe, it, expect } from 'vitest'
import { PERSON_DOCUMENT, SETTINGS_DOCUMENT, searchDocument, type SearchDocument } from './model'
import { firstRanked, inOrder, Place, placeOf, searchResults, type Ranked, type SearchContext } from './results'
import { matchAll, type SearchChip } from './query'
import { placeOf as nodePlaceOf } from './documents'
import { treeNode } from '../../test/models'

const node = (id: string, name: string, pathIds: string[] = [], scopeId = 'active', facets = {}) =>
  searchDocument({ scopeId, kind: 'MEMORY', id, nodeId: id, revision: '0:0', name, path: [], pathIds, facets })
const photo = (id: string, name: string, nodeId: string, pathIds: string[], scopeId = 'active'): SearchDocument =>
  searchDocument({ scopeId, kind: 'memories.photo', id, nodeId, revision: '0', name, path: [], pathIds, facets: {}, open: () => {} })

const person = (id: string, name: string): SearchDocument =>
  searchDocument({ scopeId: '', kind: PERSON_DOCUMENT, id, nodeId: id, revision: '', name, path: [], facets: {}, open: () => {} })
const section = (id: string, name: string): SearchDocument =>
  searchDocument({ scopeId: '', kind: SETTINGS_DOCUMENT, id, nodeId: id, revision: '', name, path: [], facets: {}, open: () => {} })

// the active space: Trips/ → Rome (open) → Day 1 (a chapter) ; Trips/ → Paris
const docs = [
  node('trips', 'Trips'), node('rome', 'Rome', ['trips']), node('day1', 'Rome day 1', ['trips', 'rome']),
  node('paris', 'Paris from Rome', ['trips']),
  node('o1', 'Rome again', [], 'other-space'),
  photo('m1', 'Rome beach.jpg', 'rome', ['trips', 'rome']),
  photo('m2', 'Rome old.jpg', 'paris', ['trips', 'paris']), // contributed for a page no longer open
]
const ctx = (o: Partial<SearchContext> = {}): SearchContext => ({ activeScopeId: 'active', openNodeId: 'rome', ...o })
const collator = new Intl.Collator('en')
const names = (list: readonly SearchDocument[], text: string, c = ctx(), chips: SearchChip[] = []) =>
  searchResults(list, { chips, text }, c, collator).results.map((d) => d.name)

describe('one list, ordered by the match, then where the result lies, then the name', () => {
  // was: "the open node's group first, the space's next, other spaces last" - the same order now, within one rank
  it('at an equal match, puts what lies below the open node first, the rest of the space next, other spaces last', () => {
    // every one a word-start or prefix match of "rome", so where it lies decides
    expect(names(docs, 'rome')).toEqual(['Rome', 'Rome beach.jpg', 'Rome day 1', 'Rome again', 'Paris from Rome'])
    expect(docs.map((d) => placeOf(d, ctx()))).toEqual([Place.space, Place.space, Place.open, Place.space, Place.other, Place.open, null])
  })

  // was: the same test, over groups
  it('counts a contribution only for the node open in the active space', () => {
    expect(names(docs, 'rome', ctx({ openNodeId: null }))).toEqual(['Rome', 'Rome day 1', 'Rome again', 'Paris from Rome'])
    expect(names(docs, 'rome', ctx({ activeScopeId: 'other-space', openNodeId: 'rome' }))).toEqual(['Rome', 'Rome again', 'Rome day 1', 'Paris from Rome'])
  })

  // was: "ranks within a group, never across" - the owner reversed it (2026-11-09): the match decides first
  it('puts an exact match in another space before an inside-word match here', () => {
    const list = [node('here', 'Aroma'), node('there', 'Rom', [], 'other-space')]
    expect(names(list, 'rom', ctx({ openNodeId: null }))).toEqual(['Rom', 'Aroma'])
  })

  it('at an equal match, the active space first; at an equal place, the name', () => {
    const list = [node('a', 'Rome B', [], 'other-space'), node('b', 'Rome C'), node('c', 'Rome A', [], 'other-space'), node('d', 'Rome D')]
    expect(names(list, 'rome', ctx({ openNodeId: null }))).toEqual(['Rome C', 'Rome D', 'Rome A', 'Rome B'])
  })

  // was: "puts the members, then the settings sections, after every space's group"
  it('puts a person and a settings section after content at an equal match, and before it at a better one', () => {
    const list = [...docs, person('u1', 'Rome Romy'), section('maps', 'Rome maps')]
    const ordered = names(list, 'rome', ctx({ openNodeId: null }))
    // the prefix matches: content (Rome, Rome again, Rome day 1), then the two of no space by name; then the word-start match
    expect(ordered).toEqual(['Rome', 'Rome day 1', 'Rome again', 'Rome maps', 'Rome Romy', 'Paris from Rome'])
    expect(names([node('n', 'Aromy'), person('u1', 'Romy')], 'romy', ctx({ openNodeId: null }))).toEqual(['Romy', 'Aromy'])
  })
})

// was: "shows neither group while a chip is set" - replaced by the predicate rule (ADR 0125 decision 4)
describe('a chip is a predicate over every document', () => {
  const list = [
    person('u1', 'Romy'), section('maps', 'Rome maps'),
    node('rome', 'Rome', [], 'active', { years: { from: 2020, to: 2020 } }),
  ]
  it('Kind: Person lists only people', () => {
    const chip: SearchChip = { type: 'kind', kinds: [PERSON_DOCUMENT], label: 'Person' }
    expect(names(list, 'rom', ctx({ openNodeId: null }), [chip])).toEqual(['Romy'])
    expect(names(list, '', ctx({ openNodeId: null }), [chip])).toEqual(['Romy'])
  })

  it('Year: 2020 lists no person and no setting; control: without it both are there', () => {
    expect(names(list, 'rom', ctx({ openNodeId: null }), [{ type: 'year', year: 2020 }])).toEqual(['Rome'])
    expect(names(list, 'rom', ctx({ openNodeId: null }))).toEqual(['Rome', 'Rome maps', 'Romy'])
  })

  it('Person: X asks what X takes part in, so X\'s own profile is not listed', () => {
    const chip: SearchChip = { type: 'person', userId: 'u1', label: 'Romy' }
    const withHer = node('feast', 'Feast', [], 'active', { people: ['u1'] })
    expect(names([...list, withHer], '', ctx({ openNodeId: null }), [chip])).toEqual(['Feast'])
  })
})

describe('where a node lies', () => {
  // was: "knows what lies below a node, and survives a cycle" (`descendantsOf`, gone): a document now carries the ids above it
  it('is the ids above it, outermost first, and survives a cycle', () => {
    const tree = [treeNode({ id: 'trips', parentId: null, name: 'Trips' }), treeNode({ id: 'rome', parentId: 'trips', name: 'Rome' }),
      treeNode({ id: 'day1', parentId: 'rome', name: 'Day 1' })]
    const byId = new Map(tree.map((n) => [n.id, n]))
    expect(nodePlaceOf(tree[2], byId)).toEqual({ names: ['Trips', 'Rome'], ids: ['trips', 'rome'] })
    const cyclic = [treeNode({ id: 'a', parentId: 'b', name: 'A' }), treeNode({ id: 'b', parentId: 'a', name: 'B' })]
    expect(nodePlaceOf(cyclic[0], new Map(cyclic.map((n) => [n.id, n]))).ids).toEqual(['b'])
  })
})

// the list is ranked only as far as it is shown, its count stays exact
describe('a list ranked as far as it is shown', () => {
  // names that tie on rank and differ by collation, case, digits and accents - the order a full sort gives is the answer;
  // half in another space, so the place takes part too
  const many = Array.from({ length: 300 }, (_, i) =>
    node(`n${i}`, `${['Rom', 'rom', 'Röm', 'Roma', 'Trip to Rome', 'Aroma'][i % 6]} ${(i * 7919) % 300}`, [], i % 2 ? 'active' : 'other-space'))
  const ranked = (): Ranked[] => matchAll(many, { chips: [], text: 'rom' }).map((m) => ({ ...m, place: placeOf(m.doc, ctx({ openNodeId: null }))! }))

  it('selects the same first N as a full sort, for every N', () => {
    const matches = ranked()
    const order = inOrder(collator)
    const full = [...matches].sort(order).map((m) => m.doc.id)
    for (const n of [0, 1, 10, 20, 299, 300, 301]) expect(firstRanked(matches, n, order).map((m) => m.doc.id), `n=${n}`).toEqual(full.slice(0, n))
  })

  it('leaves the matches it was handed as they were', () => {
    const matches = ranked()
    const before = matches.map((m) => m.doc.id)
    firstRanked(matches, 10, inOrder(collator))
    expect(matches.map((m) => m.doc.id)).toEqual(before)
  })

  it('hands back its first `limit` results and the exact total', () => {
    const page = searchResults(many, { chips: [], text: 'rom' }, ctx({ openNodeId: null }), collator, { limit: 10 })
    expect([page.results.length, page.total]).toEqual([10, 300])
    const whole = searchResults(many, { chips: [], text: 'rom' }, ctx({ openNodeId: null }), collator)
    expect(page.results).toEqual(whole.results.slice(0, 10))
    expect(whole.results).toHaveLength(300)
  })
})
