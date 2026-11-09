import { describe, it, expect } from 'vitest'
import { searchDocument, type SearchDocument, type SearchFacets } from './model'
import { searchResults } from './results'
import {
  chipKey, createMatcher, MATCH_HISTORY, matchAll, matchedText, nameParts, Rank, reasonKeyword, satisfies, suggestChips, textRank, type ChipSources,
  type SearchChip, type SearchQuery,
} from './query'

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

// ADR 0125 decision 4: a node something lies below is an In: chip, matched by its id, scoped to its space
describe('the In: chip', () => {
  // the tenant's space: Reisen/ → Rom 2024 (a Memory) → a photo; Arbeit/ → Reisen (a second "Reisen"); a group: Reisen/ → Paris
  const reisen = doc('Reisen', {}, { id: 'reisen', nodeId: 'reisen' })
  const rom = doc('Rom 2024', {}, { id: 'rom', nodeId: 'rom', path: ['Reisen'], pathIds: ['reisen'] })
  const photo = doc('strand.jpg', {}, { id: 'p1', nodeId: 'rom', kind: 'memories.photo', path: ['Reisen', 'Rom 2024'], pathIds: ['reisen', 'rom'] })
  const arbeit = doc('Arbeit', {}, { id: 'arbeit', nodeId: 'arbeit' })
  const reisen2 = doc('Reisen', {}, { id: 'reisen2', nodeId: 'reisen2', path: ['Arbeit'], pathIds: ['arbeit'] })
  const memo = doc('Notiz', {}, { id: 'memo', nodeId: 'memo', path: ['Arbeit', 'Reisen'], pathIds: ['arbeit', 'reisen2'] })
  const groupReisen = doc('Reisen', {}, { scopeId: 'g1', id: 'g-reisen', nodeId: 'g-reisen' })
  const paris = doc('Paris', {}, { scopeId: 'g1', id: 'paris', nodeId: 'paris', path: ['Reisen'], pathIds: ['g-reisen'] })
  const empty = doc('Reiseziele', {}, { id: 'empty', nodeId: 'empty' })
  const all = [reisen, rom, photo, arbeit, reisen2, memo, groupReisen, paris, empty]
  const inChip = (d: SearchDocument): SearchChip => ({ type: 'in', scopeId: d.scopeId, nodeId: d.id, label: d.name, path: d.path })

  it('offers a node something lies below by a word of its name, with the names above it; not an empty one', () => {
    const s = suggestChips('reis', [], sources({ documents: all })).filter((x) => x.chip.type === 'in')
    expect(s.map((x) => chipKey(x.chip))).toEqual(['in:s1:reisen', 'in:s1:reisen2', 'in:g1:g-reisen'])
    expect(s[1].chip).toEqual({ type: 'in', scopeId: 's1', nodeId: 'reisen2', label: 'Reisen', path: ['Arbeit'] })
    expect(offered('reisez', [], sources({ documents: all }))).toEqual([]) // Reiseziele holds nothing
  })

  it('narrows to the node\'s subtree, a photo by its Memory included, the node itself not; control: without it all are there', () => {
    const names = (chips: SearchChip[]) => matchAll(all, { chips, text: '' }).map((m) => m.doc.name)
    expect(names([inChip(reisen)])).toEqual(['Rom 2024', 'strand.jpg'])
    expect(matchAll(all, { chips: [], text: 'strand' }).map((m) => m.doc.id)).toEqual(['p1'])
  })

  it('tells two nodes of one name apart by id, in one space and across spaces', () => {
    expect(matchAll(all, { chips: [inChip(reisen2)], text: '' }).map((m) => m.doc.id)).toEqual(['memo'])
    expect(matchAll(all, { chips: [inChip(groupReisen)], text: '' }).map((m) => m.doc.id)).toEqual(['paris'])
    // the space is part of it: an id another space holds below a node of the same id is not this one's
    const elsewhere = doc('Fremd', {}, { scopeId: 'g2', id: 'x', nodeId: 'x', pathIds: ['reisen'] })
    expect(satisfies(elsewhere, inChip(reisen))).toBe(false)
  })

  it('a document of no space (a person, a settings section) is in no node', () => {
    expect(satisfies(doc('Anna', {}, { scopeId: '', kind: 'person' }), inChip(reisen))).toBe(false)
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

  // was: `byRank` sorted the matches; the order is now the one list's (`results.ts`), which in one place is rank, then name
  it('orders by rank, then by the name, in one place', () => {
    const docs = [doc('Aroma'), doc('Trip to Rome'), doc('Rom'), doc('Roma B'), doc('Roma A'), doc('Summer', { places: ['Rom'] })]
    const order = searchResults(docs, { chips: [], text: 'rom' }, { activeScopeId: 's1', openNodeId: null }, new Intl.Collator('en'))
    expect(order.results.map((d) => d.name)).toEqual(['Rom', 'Roma A', 'Roma B', 'Trip to Rome', 'Aroma', 'Summer'])
  })

  it('ranks a settings section\'s word with a place: after every match of a name, inside a word included', () => {
    const mail = doc('Mail', {}, { keywords: ['E-Mail', 'SMTP'] })
    expect(textRank(mail, 'smtp')).toBe(Rank.place)
    expect(textRank(mail, 'mtp')).toBeNull() // from a word's start only
    expect(textRank(doc('Gesmtpx'), 'smtp')).toBe(Rank.inside) // control: a name inside a word ranks before it
    expect(textRank(doc('Mail', {}, { keywords: ['Mail'] }), 'mail')).toBe(Rank.exact) // the name counts first
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

// uc.platform.search step 5 (owner 2026-11-09): a result never stands there for a reason the member cannot see
describe('a reason the member can see', () => {
  const password = () => doc('Passwort ändern', {}, { keywords: ['Kennwort', 'Passwort', 'Anmeldung'] })

  it('one character matches word starts only - a name\'s, another name\'s, a place\'s; two match inside a word', () => {
    expect(textRank(doc('Reiselink'), 'k')).toBeNull()
    expect(textRank(doc('Kiel'), 'k')).toBe(Rank.prefix)
    expect(textRank(doc('Bad Kissingen'), 'k')).toBe(Rank.wordStart)
    expect(textRank(doc('Kevin', {}, { aliases: ['~xkev'] }), '~')).toBe(Rank.prefix) // an alias's start
    expect(textRank(doc('Home', { places: ['Parkweg'] }), 'k')).toBeNull()
    expect(textRank(doc('Home', { places: ['Kassel'] }), 'k')).toBe(Rank.place)
    // two characters: inside a word counts
    expect(textRank(doc('Paket'), 'ke')).toBe(Rank.inside)
    expect(textRank(doc('Home', { places: ['Parkweg'] }), 'rk')).toBe(Rank.place)
    expect(textRank(doc('Paket'), 'k')).toBeNull() // control: the same name, one character
  })

  it('counts a character outside the basic plane as one', () => {
    expect(textRank(doc('Party 🎉🎉'), '🎉')).toBe(Rank.wordStart)
    expect(textRank(doc('x🎉'), '🎉')).toBeNull() // one character, inside a word
  })

  it('a settings word counts from the third character on: "ke" no, "ken" yes', () => {
    expect(textRank(password(), 'ke')).toBeNull()
    expect(textRank(password(), 'k')).toBeNull()
    expect(textRank(password(), 'ken')).toBe(Rank.place)
    expect(textRank(doc('Mail', {}, { keywords: ['SMTP'] }), 'sm')).toBeNull()
    expect(textRank(doc('Mail', {}, { keywords: ['SMTP'] }), 'smt')).toBe(Rank.place)
  })

  // owner 2026-11-09: a word shorter than three characters ("S3") is found when typed whole
  it('a settings word shorter than three characters matches when typed whole: "s3" yes, "s" no; control: "ke" still no', () => {
    const storage = () => doc('Speicher', {}, { keywords: ['Speicherplatz', 'Kontingent', 'S3'] })
    expect(textRank(storage(), 's3')).toBe(Rank.place)
    expect(reasonKeyword(storage(), 's3')).toBe('S3')
    expect(textRank(storage(), 's')).toBe(Rank.prefix) // its name's start, not the short word
    expect(reasonKeyword(storage(), 's')).toBeNull()
    expect(textRank(doc('Lager', {}, { keywords: ['S3'] }), 's')).toBeNull() // a part of a short word is no match
    expect(textRank(password(), 'ke')).toBeNull() // control: a longer word still counts from three characters only
  })

  it('names the word that is the reason, as the section names it; none where the name matched', () => {
    expect(reasonKeyword(password(), 'ken')).toBe('Kennwort')
    expect(reasonKeyword(password(), 'anmel')).toBe('Anmeldung')
    expect(reasonKeyword(password(), 'passw')).toBeNull() // the label matched: it is the reason
    expect(reasonKeyword(password(), 'ke')).toBeNull() // nothing matched
    expect(reasonKeyword(doc('Kennwort'), 'ken')).toBeNull() // no words at all
    expect(reasonKeyword(password(), '')).toBeNull()
  })

  it('draws the reason with its matched part bold', () => {
    expect(nameParts(reasonKeyword(password(), 'ken')!, 'ken')).toEqual([{ text: 'Ken', strong: true }, { text: 'nwort', strong: false }])
  })
})

// ADR 0125 decision 4: the month chip - a month's name with a year, in the UI's language or in English
describe('the month chip', () => {
  const names = (locale: string) => Array.from({ length: 12 }, (_, i) => new Intl.DateTimeFormat(locale, { month: 'long' }).format(new Date(2000, i, 15)))
  const de = names('de')
  const en = names('en')
  const months = de.map((d, i) => [d, en[i]])
  const withMonths = sources({ months })

  it('"mai 2024" and "May 2024" offer May 2024, first - the year alone with "mai" kept as text after it', () => {
    expect(offered('mai 2024', [], withMonths)).toEqual(['month:2024-05 | ', 'year:2024 | mai'])
    expect(offered('May 2024', [], withMonths)).toEqual(['month:2024-05 | ', 'year:2024 | May'])
    expect(suggestChips('Mai 2024', [], withMonths)[0].chip).toEqual({ type: 'month', year: 2024, month: 5 })
  })

  it('reads a month from its first three letters, folded, and keeps the words before it', () => {
    expect(offered('rom mar 2024', [], withMonths)).toEqual(['month:2024-03 | rom', 'year:2024 | rom mar'])
    expect(offered('MÄRZ 2024', [], withMonths)[0]).toBe('month:2024-03 | ')
    expect(offered('sept 2023', [], withMonths)[0]).toBe('month:2023-09 | ')
    // two letters are no month: "ma" could be March or May
    expect(offered('ma 2024', [], withMonths)).toEqual(['year:2024 | ma'])
  })

  it('a month\'s name alone offers nothing: the year makes it a filter', () => {
    expect(offered('Mai', [], withMonths)).toEqual([])
    expect(offered('may', [], withMonths)).toEqual([])
    // control: the same name with a year is the month
    expect(offered('may 2024', [], withMonths)[0]).toBe('month:2024-05 | ')
  })

  it('without month names (a gallery\'s filter) no month is offered', () => {
    expect(offered('mai 2024')).toEqual(['year:2024 | mai'])
  })

  it('holds a Memory whose months overlap it, at both edges', () => {
    const spring = doc('Spring', { months: { from: '2024-04', to: '2024-05' } })
    const may = { type: 'month', year: 2024, month: 5 } as const
    expect(satisfies(spring, may)).toBe(true)
    expect(satisfies(spring, { type: 'month', year: 2024, month: 4 })).toBe(true)
    expect(satisfies(spring, { type: 'month', year: 2024, month: 6 })).toBe(false)
    expect(satisfies(spring, { type: 'month', year: 2024, month: 3 })).toBe(false)
    expect(satisfies(spring, { type: 'month', year: 2023, month: 5 })).toBe(false)
    const newYear = doc('New year', { months: { from: '2023-12', to: '2024-01' } })
    expect(satisfies(newYear, { type: 'month', year: 2023, month: 12 })).toBe(true)
    expect(satisfies(newYear, { type: 'month', year: 2024, month: 1 })).toBe(true)
    expect(satisfies(newYear, { type: 'month', year: 2024, month: 2 })).toBe(false)
  })

  it('holds a photo captured in it, local time, to the last minute of its last day', () => {
    const may = { type: 'month', year: 2024, month: 5 } as const
    const june = { type: 'month', year: 2024, month: 6 } as const
    const lastOfMay = doc('IMG_1.jpg', { capturedAt: new Date(2024, 4, 31, 23, 59).getTime() })
    const firstOfJune = doc('IMG_2.jpg', { capturedAt: new Date(2024, 5, 1, 0, 1).getTime() })
    expect(satisfies(lastOfMay, may)).toBe(true)
    expect(satisfies(lastOfMay, june)).toBe(false)
    expect(satisfies(firstOfJune, june)).toBe(true)
    expect(satisfies(firstOfJune, may)).toBe(false)
  })

  it('holds no Memory without a date', () => {
    expect(satisfies(doc('Undated'), { type: 'month', year: 2024, month: 5 })).toBe(false)
    expect(satisfies(doc('Only years', { years: { from: 2024, to: 2024 } }), { type: 'month', year: 2024, month: 5 })).toBe(false)
  })
})

// uc.platform.search step 6: the matched part of a name, found in the folded name and drawn over the name's own characters
describe('the matched part of a name', () => {
  const strong = (name: string, text: string) => nameParts(name, matchedText(text)).map((p) => (p.strong ? `[${p.text}]` : p.text)).join('')

  it('a plain prefix', () => {
    expect(strong('Kevins Geburtstag', 'kev')).toBe('[Kev]ins Geburtstag')
    expect(nameParts('Kevins Geburtstag', 'kev')).toEqual([{ text: 'Kev', strong: true }, { text: 'ins Geburtstag', strong: false }])
  })

  it('"Straße" for "strasse": the folded match is mapped back over the ß', () => {
    expect(strong('Straße', 'strasse')).toBe('[Straße]')
    expect(strong('Straße 12', 'strasse')).toBe('[Straße] 12')
    expect(strong('Große Straße', 'strasse')).toBe('Große [Straße]')
    expect(strong('Straßenfest', 'stras')).toBe('[Straß]enfest')
  })

  it('"Café" for "cafe", composed and decomposed', () => {
    expect(strong('Café Müller', 'cafe')).toBe('[Café] Müller')
    expect(strong('Café Müller', 'cafe mu')).toBe('[Café Mü]ller')
    expect(strong('Unser Café', 'CAFÉ')).toBe('Unser [Café]')
  })

  it('a word start before an earlier match inside a word, and each word of several', () => {
    expect(strong('Aroma Rom', 'rom')).toBe('Aroma [Rom]')
    expect(strong('Summer in Rome', 'rome summer')).toBe('[Summer] in [Rome]')
  })

  it('nothing bold where the name did not match, or for no text', () => {
    expect(strong('Kevin Raddatz', '~kradd')).toBe('Kevin Raddatz')
    expect(strong('Rome', '')).toBe('Rome')
  })
})

// the typing margin (ADR 0125 § Consequences) - a text that only grew narrows the last matches
describe('the matcher of a field', () => {
  const names = ['Kevelaer 2012', 'Kevins Geburtstag', 'Ken', 'Aroma', 'IMG_0001.jpg', 'Summer in Kev', 'Keller', 'Café Müller']
  const docs = names.map((name, i) => doc(name, { years: { from: 2010 + i, to: 2010 + i }, places: i % 2 ? ['Kevelaer'] : [] }))
  const year = (y: number): SearchChip => ({ type: 'year', year: y })
  const sorted = (ms: { doc: SearchDocument; rank: number }[]) => ms.map((m) => `${m.doc.name}:${m.rank}`).sort()

  it('answers every step of a typed sequence as a whole scan does, letters taken back and chips moved included', () => {
    const steps: SearchQuery[] = [
      { chips: [], text: 'k' }, { chips: [], text: 'ke' }, { chips: [], text: 'kev' }, { chips: [], text: 'kev ' }, { chips: [], text: 'kev s' },
      { chips: [], text: 'kev' }, { chips: [], text: 'ke' }, { chips: [year(2012)], text: 'ke' }, { chips: [year(2012)], text: 'kev' },
      { chips: [], text: 'kev' }, { chips: [], text: '' }, { chips: [], text: 'c' }, { chips: [], text: 'caf' }, { chips: [year(2017)], text: '' },
      { chips: [year(2017)], text: 'c' },
    ]
    const match = createMatcher()
    for (const q of steps) expect(sorted(match(docs, q)), JSON.stringify(q)).toEqual(sorted(matchAll(docs, q)))
  })

  // was: from "ke" to "kev"; a text of two characters is no superset any more (uc.platform.search step 5), so from "kev"
  // uc.platform.search step 5: "K" matches word starts only and "Ke" inside words too, "Ke" no settings word and "Ken" one
  it('answers "K" to "Ke" to "Ken" and back as fresh queries do - a shorter text below every rule is no superset', () => {
    const list = [
      ...docs, doc('Paket'), doc('Reiselink'), doc('Passwort ändern', {}, { keywords: ['Kennwort'] }), doc('Home', { places: ['Parkweg'] }),
    ]
    const steps = ['k', 'ke', 'ken', 'kenn', 'ken', 'ke', 'k', 'ke', 'ken', 'r', 'rk', 'rkw', 'rk']
    const match = createMatcher()
    for (const text of steps) {
      const q = { chips: [], text }
      expect(sorted(match(list, q)), text).toEqual(sorted(matchAll(list, q))) // matchAll: no memory, a whole scan
    }
    // the cases the boundary decides, each present: a fresh scan finds what the shorter text did not
    expect(sorted(matchAll(list, { chips: [], text: 'ke' }))).toContain('Paket:3')
    expect(sorted(matchAll(list, { chips: [], text: 'k' })).some((s) => s.startsWith('Paket'))).toBe(false)
    expect(sorted(matchAll(list, { chips: [], text: 'ken' }))).toContain('Passwort ändern:4')
    expect(sorted(matchAll(list, { chips: [], text: 'ke' })).some((s) => s.startsWith('Passwort'))).toBe(false)
    expect(sorted(matchAll(list, { chips: [], text: 'rk' }))).toContain('Home:4')
  })

  it('narrows the last matches while the text grows: a document it dropped is not asked again', () => {
    const match = createMatcher()
    match(docs, { chips: [], text: 'kev' })
    // a document renamed in place under the same list is only seen by a whole scan: the narrowing never looks at it
    const img = docs.find((d) => d.name === 'IMG_0001.jpg')! as { foldedName: string }
    const before = img.foldedName
    img.foldedName = 'kevimg'
    try {
      expect(match(docs, { chips: [], text: 'kevi' }).map((m) => m.doc.name)).not.toContain('IMG_0001.jpg')
      expect(matchAll(docs, { chips: [], text: 'kevi' }).map((m) => m.doc.name)).toContain('IMG_0001.jpg') // control
    } finally {
      img.foldedName = before
    }
  })

  it('scans again for another document list, another chip, or after a query that asked nothing', () => {
    const match = createMatcher()
    expect(match(docs, { chips: [], text: '' })).toEqual([])
    expect(match(docs, { chips: [], text: 'k' }).length).toBe(matchAll(docs, { chips: [], text: 'k' }).length)
    const more = [...docs, doc('Kevin')]
    expect(match(more, { chips: [], text: 'kev' }).map((m) => m.doc.name)).toContain('Kevin')
    expect(match(more, { chips: [year(2011)], text: 'kev' }).map((m) => m.doc.name)).toEqual(['Kevins Geburtstag'])
    expect(match(more, { chips: [], text: 'kev' }).map((m) => m.doc.name)).toContain('Kevelaer 2012')
    // a query that grew, but without the chip a remembered one had: never narrowed from that one's matches
    const other = createMatcher()
    other(docs, { chips: [year(2011)], text: 'ke' })
    expect(sorted(other(docs, { chips: [], text: 'kev' }))).toEqual(sorted(matchAll(docs, { chips: [], text: 'kev' })))
  })

  it('the same query again is the last answer as it was ("show more")', () => {
    const match = createMatcher()
    const first = match(docs, { chips: [], text: 'ke' })
    expect(match(docs, { chips: [], text: 'ke ' })).toBe(first)
  })

  // The owner's item (2026-11-09): the queries asked while the field is open are remembered, for one index generation.
  /** The documents, each counting how often a match reads its name: a scan of a document is one read. */
  function counted() {
    let reads = 0
    const list = docs.map((d) => {
      const copy = { ...d }
      Object.defineProperty(copy, 'foldedName', { get: () => (reads++, d.foldedName) })
      return copy as SearchDocument
    })
    return { list, reads: () => reads }
  }

  // was: "ke" then "kev" narrowed from "ke"; a two-character text is no superset any more (uc.platform.search step 5),
  // so "kev" scans, and "kevi" narrows from it - the Backspace is the same
  it('Backspace from "kev" to "ke" reads no document: the answer for "ke" is remembered', () => {
    const { list, reads } = counted()
    const match = createMatcher()
    const ke = match(list, { chips: [], text: 'ke' })
    expect(reads()).toBe(list.length) // the first query scans every document
    const kev = match(list, { chips: [], text: 'kev' })
    expect(reads()).toBe(2 * list.length) // "ke" is no superset of "kev" (a settings word counts from three): scanned
    const kevi = match(list, { chips: [], text: 'kevi' })
    expect(reads()).toBe(2 * list.length + kev.length) // narrowed: only what "kev" matched
    const before = reads()
    expect(match(list, { chips: [], text: 'ke' })).toBe(ke) // Backspace
    expect(reads()).toBe(before)
    // typing on narrows from the closest remembered shorter query, "kevi"
    match(list, { chips: [], text: 'kevin' })
    expect(reads()).toBe(before + kevi.length)
    // control: a matcher without that memory reads every document again for "ke"
    const fresh = createMatcher()
    const at = reads()
    fresh(list, { chips: [], text: 'ke' })
    expect(reads()).toBe(at + list.length)
  })

  it('forgets everything for the index\'s next generation (another document list), and when cleared', () => {
    const { list, reads } = counted()
    const match = createMatcher()
    match(list, { chips: [], text: 'ke' })
    // control: the same generation answers from memory
    let at = reads()
    match(list, { chips: [], text: 'ke' })
    expect(reads()).toBe(at)
    // the next generation: the same documents in a new list (a space rebuilt) - scanned again, and only the new list
    const next = [...list]
    at = reads()
    match(next, { chips: [], text: 'ke' })
    expect(reads()).toBe(at + next.length)
    match(next, { chips: [], text: 'kev' })
    at = reads()
    match(next, { chips: [], text: 'ke' })
    expect(reads()).toBe(at)
    // cleared (the list closed, sign-out): scanned again
    match.clear()
    match(next, { chips: [], text: 'ke' })
    expect(reads()).toBe(at + next.length)
  })

  it('remembers at most MATCH_HISTORY queries, the oldest asked going first', () => {
    const { list, reads } = counted()
    const match = createMatcher()
    match(list, { chips: [], text: 'ke' })
    for (let i = 0; i < MATCH_HISTORY; i++) match(list, { chips: [year(2010 + (i % 8))], text: `x${i}` })
    const at = reads()
    match(list, { chips: [], text: 'ke' })
    expect(reads()).toBe(at + list.length)
  })
})
