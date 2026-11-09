import { describe, it, expect } from 'vitest'
import { searchDocument, type SearchDocument, type SearchFacets } from './model'
import { byRank, chipKey, matchAll, Rank, satisfies, suggestChips, textRank, type ChipSources, type SearchChip } from './query'

let n = 0
const doc = (name: string, facets: SearchFacets = {}, o: Partial<SearchDocument> = {}): SearchDocument =>
  searchDocument({ scopeId: 's1', kind: 'MEMORY', id: `d${++n}`, nodeId: `d${n}`, revision: '0:0', name, path: [], facets, ...o })

const ANNA = 'user-anna'
const sources = (over: Partial<ChipSources> = {}): ChipSources => ({
  documents: [doc('Rome', { places: ['Roma'] }), doc('Lake', { places: ['Zürich'] })],
  spaces: [{ scopeId: 'g1', label: 'Family group' }],
  people: [{ userId: ANNA, name: 'Anna Schmidt' }, { userId: 'user-ben', name: 'Ben Müller' }],
  kinds: [{ label: 'Memory', kinds: ['MEMORY'] }, { label: 'Photo', kinds: ['memories.photo'] }],
  ...over,
})
const offered = (text: string, chips: SearchChip[] = [], s = sources()) => suggestChips(text, chips, s).map((x) => `${chipKey(x.chip)} | ${x.text}`)

describe('chip suggestions from the typed text', () => {
  it('four digits are a year', () => {
    expect(offered('rome 2024')).toEqual(['year:2024 | rome'])
    expect(offered('202')).toEqual([])
  })

  it('a prefix of a word of a member\'s name is that person, case and accents aside', () => {
    expect(offered('anna')).toEqual([`person:${ANNA} | `])
    expect(offered('schm')).toEqual([`person:${ANNA} | `])
    expect(offered('MULL')).toEqual(['person:user-ben | '])
  })

  it('reads a tail of several words, and keeps the text before it', () => {
    expect(offered('beach anna sch')).toEqual([`person:${ANNA} | beach`])
  })

  it('a prefix of a place a document carries is that place', () => {
    expect(offered('zur')).toEqual(['place:zurich | '])
    expect(suggestChips('zur', [], sources())[0].chip).toEqual({ type: 'place', place: 'zurich', label: 'Zürich' })
  })

  it('a prefix of a space\'s name is that space, and of a kind\'s word that kind', () => {
    expect(offered('fami')).toEqual(['space:g1 | '])
    expect(offered('pho')).toEqual(['kind:memories.photo | '])
  })

  it('offers nothing for one letter, and no chip already chosen', () => {
    expect(offered('a')).toEqual([])
    expect(offered('anna', [{ type: 'person', userId: ANNA, label: 'Anna Schmidt' }])).toEqual([])
    expect(offered('2024', [{ type: 'year', year: 2024 }])).toEqual([])
  })

  it('does not offer a match inside a word', () => {
    expect(offered('chmidt')).toEqual([])
  })
})

describe('each chip is a predicate over a document', () => {
  it('a space chip holds a document of that space', () => {
    expect(satisfies(doc('x', {}, { scopeId: 'g1' }), { type: 'space', scopeId: 'g1', label: 'G' })).toBe(true)
    expect(satisfies(doc('x', {}, { scopeId: 's1' }), { type: 'space', scopeId: 'g1', label: 'G' })).toBe(false)
  })

  it('a year chip holds a Memory whose range overlaps the year', () => {
    const across = doc('New year', { years: { from: 2023, to: 2024 } })
    expect(satisfies(across, { type: 'year', year: 2023 })).toBe(true)
    expect(satisfies(across, { type: 'year', year: 2024 })).toBe(true)
    expect(satisfies(across, { type: 'year', year: 2022 })).toBe(false)
    expect(satisfies(across, { type: 'year', year: 2025 })).toBe(false)
    expect(satisfies(doc('Undated'), { type: 'year', year: 2024 })).toBe(false)
  })

  it('a year chip holds a photo captured in that year', () => {
    const photo = doc('IMG_1.jpg', { capturedAt: new Date(2021, 6, 1).getTime() })
    expect(satisfies(photo, { type: 'year', year: 2021 })).toBe(true)
    expect(satisfies(photo, { type: 'year', year: 2022 })).toBe(false)
  })

  it('a place chip holds a document naming that place, case and accents aside', () => {
    expect(satisfies(doc('x', { places: ['ZÜRICH'] }), { type: 'place', place: 'zurich', label: 'Zürich' })).toBe(true)
    expect(satisfies(doc('x', { places: ['Zürichsee'] }), { type: 'place', place: 'zurich', label: 'Zürich' })).toBe(false)
  })

  it('a person chip holds a document listing that user id', () => {
    expect(satisfies(doc('x', { people: [ANNA] }), { type: 'person', userId: ANNA, label: 'Anna' })).toBe(true)
    expect(satisfies(doc('x', { people: ['other'] }), { type: 'person', userId: ANNA, label: 'Anna' })).toBe(false)
  })

  it('a kind chip holds a document of one of its kinds', () => {
    expect(satisfies(doc('x', {}, { kind: 'memories.photo' }), { type: 'kind', kinds: ['memories.photo'], label: 'Photo' })).toBe(true)
    expect(satisfies(doc('x'), { type: 'kind', kinds: ['memories.photo'], label: 'Photo' })).toBe(false)
  })

  it('a document must satisfy every chip', () => {
    const d = doc('Rome', { years: { from: 2024, to: 2024 }, people: [ANNA] })
    expect(matchAll([d], { chips: [{ type: 'year', year: 2024 }, { type: 'person', userId: ANNA, label: 'A' }], text: '' })).toHaveLength(1)
    expect(matchAll([d], { chips: [{ type: 'year', year: 2024 }, { type: 'person', userId: 'x', label: 'X' }], text: '' })).toHaveLength(0)
  })
})

describe('the text', () => {
  it('ranks an exact name, then a prefix, then a word start, then inside a word, then a place', () => {
    expect(textRank(doc('Rom'), 'rom')).toBe(Rank.exact)
    expect(textRank(doc('Roma 2024'), 'rom')).toBe(Rank.prefix)
    expect(textRank(doc('Trip to Rome'), 'rom')).toBe(Rank.wordStart)
    expect(textRank(doc('Aroma'), 'rom')).toBe(Rank.inside)
    expect(textRank(doc('Summer', { places: ['Rom'] }), 'rom')).toBe(Rank.place)
    expect(textRank(doc('Summer'), 'rom')).toBeNull()
  })

  it('matches case and accents aside, ß as ss', () => {
    expect(textRank(doc('Café Müller'), 'cafe mul')).toBe(Rank.prefix)
    expect(textRank(doc('Hauptstraße'), 'strasse')).toBe(Rank.inside)
  })

  it('matches several words anywhere in the name', () => {
    expect(textRank(doc('Summer in Rome'), 'rome summer')).toBe(Rank.inside)
  })

  it('orders by rank, then by the name', () => {
    const docs = [doc('Aroma'), doc('Trip to Rome'), doc('Rom'), doc('Roma B'), doc('Roma A'), doc('Summer', { places: ['Rom'] })]
    const order = matchAll(docs, { chips: [], text: 'rom' }).sort(byRank(new Intl.Collator('en'))).map((m) => m.doc.name)
    expect(order).toEqual(['Rom', 'Roma A', 'Roma B', 'Trip to Rome', 'Aroma', 'Summer'])
  })

  it('matches another name a document carries as its name (a person\'s ~username), at its best rank', () => {
    const kevin = doc('Kevin Raddatz', {}, { aliases: ['~kraddatz'] })
    expect(textRank(kevin, '~kraddatz')).toBe(Rank.exact)
    expect(textRank(kevin, 'kradd')).toBe(Rank.wordStart)
    expect(textRank(kevin, 'kev')).toBe(Rank.prefix) // the name still counts first
    expect(textRank(doc('Kevin Raddatz'), '~kraddatz')).toBeNull() // control: without it, the handle finds nothing
  })

  it('a query with neither text nor a chip finds nothing', () => {
    expect(matchAll([doc('Rome')], { chips: [], text: '   ' })).toEqual([])
  })
})
