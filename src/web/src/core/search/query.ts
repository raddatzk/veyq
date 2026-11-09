// The query (ADR 0125 decision 4, uc.platform.search steps 4-5): one text, and chips - each a typed predicate over a
// document's fields, never text. Pure functions over documents; nothing here reads the network, a key or any storage.
//
// **Chips from the typed text.** Every tail of the text that starts at a word - "rom anna sch", "anna sch", "sch" - is read
// against what the index knows: four digits are a year; a prefix of a word of a member's name is that person; of a place a
// document carries, that place; of a space's name, that space; of a kind's word, that kind. Choosing one turns that tail
// into the chip and the rest stays text.
//
// **Matching and ranking.** A document matches when it satisfies every chip and the folded remaining text matches its name
// (exact, prefix, the start of a word, anywhere - in that rank; another name it carries, a person's `~username`, counts as
// its name) or, ranked after every name match, one of its places. Equal ranks are ordered by the name's collation.
import { fold } from '../../shared/text/fold'
import { yearsOf, type SearchDocument } from './model'

export type SearchChip =
  | { type: 'space'; scopeId: string; label: string }
  | { type: 'year'; year: number }
  /** `place` folded; `label` as a document names it. */
  | { type: 'place'; place: string; label: string }
  | { type: 'person'; userId: string; label: string }
  /** Every kind whose word is `label`. */
  | { type: 'kind'; kinds: readonly string[]; label: string }

export interface SearchQuery {
  chips: readonly SearchChip[]
  text: string
}

/** A chip's identity: two chips of one key are the same predicate. */
export function chipKey(chip: SearchChip): string {
  switch (chip.type) {
    case 'space':
      return `space:${chip.scopeId}`
    case 'year':
      return `year:${chip.year}`
    case 'place':
      return `place:${chip.place}`
    case 'person':
      return `person:${chip.userId}`
    case 'kind':
      return `kind:${[...chip.kinds].sort().join(',')}`
  }
}

/** What a chip may be suggested from. */
export interface ChipSources {
  documents: readonly SearchDocument[]
  spaces: readonly { scopeId: string; label: string }[]
  people: readonly { userId: string; name: string }[]
  /** Each kind word with the kinds it names. */
  kinds: readonly { label: string; kinds: readonly string[] }[]
}

/** A chip offered for the typed text: `text` is what stays once it is chosen. */
export interface ChipSuggestion {
  chip: SearchChip
  text: string
}

/** The shortest tail of the text a chip is suggested for, folded (a year's four digits aside). */
export const MIN_TAIL = 2
/** How many chips of one type are suggested at most. */
export const PER_TYPE = 3
const YEAR = /^\d{4}$/

// `wordPrefix` and `tailsOf` are exported for a chip type an application adds over the same grammar (Memories' capture day
// in a gallery's filter, `features/memories/galleryFilter.ts`), so the reading of the typed text is one, never a copy.

/** Whether `name` (folded) has a word that starts with `prefix` (folded), the whole name included. */
export function wordPrefix(name: string, prefix: string): boolean {
  if (name.startsWith(prefix)) return true
  for (let i = name.indexOf(prefix); i > 0; i = name.indexOf(prefix, i + 1)) if (isBoundary(name, i)) return true
  return false
}

const isBoundary = (s: string, i: number) => i === 0 || !/[\p{L}\p{N}]/u.test(s[i - 1])

/** The tails of `text` that start at a word, longest first, each with what stays before it. */
export function tailsOf(text: string): { tail: string; rest: string }[] {
  const out: { tail: string; rest: string }[] = []
  const re = /\S+/g
  for (let m = re.exec(text); m; m = re.exec(text)) out.push({ tail: text.slice(m.index).trim(), rest: text.slice(0, m.index).trimEnd() })
  return out
}

/** The chips offered for `text` (see the header), none already among `chips`; at most a few per type. */
export function suggestChips(text: string, chips: readonly SearchChip[], sources: ChipSources): ChipSuggestion[] {
  const taken = new Set(chips.map(chipKey))
  const seen = new Set<string>()
  const byType: Record<SearchChip['type'], ChipSuggestion[]> = { year: [], person: [], place: [], space: [], kind: [] }
  const offer = (s: ChipSuggestion) => {
    const key = chipKey(s.chip)
    if (taken.has(key) || seen.has(key) || byType[s.chip.type].length >= PER_TYPE) return
    seen.add(key)
    byType[s.chip.type].push(s)
  }
  const tails = tailsOf(text)
  const last = tails.at(-1)
  if (last && YEAR.test(last.tail)) offer({ chip: { type: 'year', year: Number(last.tail) }, text: last.rest })
  const places = placesOfCached(sources.documents)
  for (const { tail, rest } of tails) {
    const t = fold(tail)
    if (t.length < MIN_TAIL) continue
    for (const p of sources.people) if (wordPrefix(foldOnce(p.name), t)) offer({ chip: { type: 'person', userId: p.userId, label: p.name }, text: rest })
    for (const [place, label] of places) if (wordPrefix(place, t)) offer({ chip: { type: 'place', place, label }, text: rest })
    for (const s of sources.spaces) if (wordPrefix(foldOnce(s.label), t)) offer({ chip: { type: 'space', scopeId: s.scopeId, label: s.label }, text: rest })
    for (const k of sources.kinds) if (wordPrefix(foldOnce(k.label), t)) offer({ chip: { type: 'kind', kinds: k.kinds, label: k.label }, text: rest })
  }
  return [...byType.year, ...byType.person, ...byType.place, ...byType.space, ...byType.kind]
}

/** A name of a suggestion's source (a member, a space, a kind's word) folded once, not on every keystroke
 *  (cr-search-polish): the names are few and stable, and a query's text is never kept here. */
const folded = new Map<string, string>()
function foldOnce(name: string): string {
  let f = folded.get(name)
  if (f === undefined) {
    if (folded.size >= 4096) folded.clear()
    folded.set(name, (f = fold(name)))
  }
  return f
}

/** {@link placesOf} once per document list: a keystroke asks again over the same list (cr-search-at-volume). */
const placesByList = new WeakMap<readonly SearchDocument[], Map<string, string>>()
function placesOfCached(docs: readonly SearchDocument[]): Map<string, string> {
  let places = placesByList.get(docs)
  if (!places) placesByList.set(docs, (places = placesOf(docs)))
  return places
}

/** Every place the documents name, folded → as first named. */
function placesOf(docs: readonly SearchDocument[]): Map<string, string> {
  const places = new Map<string, string>()
  for (const d of docs) d.foldedPlaces.forEach((p, i) => {
    if (p && !places.has(p)) places.set(p, d.facets.places![i])
  })
  return places
}

/** Whether `doc` satisfies `chip`. */
export function satisfies(doc: SearchDocument, chip: SearchChip): boolean {
  switch (chip.type) {
    case 'space':
      return doc.scopeId === chip.scopeId
    case 'year': {
      const years = yearsOf(doc)
      return !!years && years.from <= chip.year && chip.year <= years.to
    }
    case 'place':
      return doc.foldedPlaces.includes(chip.place)
    case 'person':
      return !!doc.facets.people?.includes(chip.userId)
    case 'kind':
      return chip.kinds.includes(doc.kind)
  }
}

/** The ranks of a text match, best first. */
export const Rank = { exact: 0, prefix: 1, wordStart: 2, inside: 3, place: 4 } as const

/** The words of the folded text `t`, split once per query: {@link matchAll} asks every document with them, and a split per
 *  document was a regular expression and an array for each of 17,000 documents on every keystroke (cr-search-photos-of-every-space). */
const wordsOf = (t: string): string[] => t.split(/\s+/).filter(Boolean)

/** How `doc` matches the folded text `t` (its `words`, when the caller split them already), or null when it does not; an
 *  empty text matches every document at the best rank. */
export function textRank(doc: SearchDocument, t: string, words: readonly string[] = wordsOf(t)): number | null {
  if (!t) return Rank.exact
  let best = nameRank(doc.foldedName, t, words)
  for (const alias of doc.foldedAliases) {
    const rank = nameRank(alias, t, words)
    if (rank !== null && (best === null || rank < best)) best = rank
  }
  if (best !== null) return best
  if (doc.foldedPlaces.some((p) => p.includes(t))) return Rank.place
  return null
}

/** How the folded name `name` matches the folded text `t` (split into `words`), or null. */
function nameRank(name: string, t: string, words: readonly string[]): number | null {
  // Most documents do not hold the text at all (a photo's IMG_… for "K"): one search answers them, not four.
  if (!name.includes(t)) return words.length > 1 && words.every((w) => name.includes(w)) ? Rank.inside : null
  if (name === t) return Rank.exact
  if (name.startsWith(t)) return Rank.prefix
  if (wordPrefix(name, t)) return Rank.wordStart
  return Rank.inside
}

/** A document that matched, with its rank. */
export interface Match {
  doc: SearchDocument
  rank: number
}

/** The text of `query` as it is matched: folded, trimmed, one space between words. */
const foldedText = (query: SearchQuery): string => fold(query.text.trim()).replace(/\s+/g, ' ')

/** The documents matching `query`, unordered. A query with neither a chip nor text matches nothing. */
export function matchAll(docs: readonly SearchDocument[], query: SearchQuery): Match[] {
  return matchIn(docs, query.chips, foldedText(query))
}

function matchIn(docs: Iterable<SearchDocument>, chips: readonly SearchChip[], t: string): Match[] {
  if (!t && chips.length === 0) return []
  const out: Match[] = []
  const words = wordsOf(t)
  for (const doc of docs) {
    if (chips.length > 0 && !chips.every((c) => satisfies(doc, c))) continue
    const rank = textRank(doc, t, words)
    if (rank !== null) out.push({ doc, rank })
  }
  return out
}

/** {@link matchAll} that remembers its last answer (`createMatcher`). */
export type Matcher = (docs: readonly SearchDocument[], query: SearchQuery) => Match[]

/**
 * A {@link matchAll} for a field the member types into (cr-search-polish): it keeps its last answer, and where the query
 * only GREW - the same documents, the same chips, a text that starts with the last one - it narrows the last matches
 * instead of scanning every document. That is sound because a text that grows only drops matches: every word of the longer
 * text holds a word of the shorter one, and a name, an alias or a place that holds the longer text holds the shorter. The
 * same query again (a "show more") is answered by the last matches as they are. Anything else - a letter taken back, a chip
 * added or removed, the documents moved - scans every document again. A query that asks nothing is never remembered: its
 * empty answer is not the matches of every text.
 */
export function createMatcher(): Matcher {
  let last: { docs: readonly SearchDocument[]; chips: string; t: string; matches: Match[] } | null = null
  return (docs, query) => {
    const t = foldedText(query)
    const chips = query.chips.map(chipKey).sort().join('\u0000')
    if (!t && query.chips.length === 0) {
      last = null
      return []
    }
    let matches: Match[]
    if (last && last.docs === docs && last.chips === chips && t.startsWith(last.t)) {
      matches = t === last.t ? last.matches : matchIn(docsOf(last.matches), query.chips, t)
    } else matches = matchIn(docs, query.chips, t)
    last = { docs, chips, t, matches }
    return matches
  }
}

function* docsOf(matches: readonly Match[]): Generator<SearchDocument> {
  for (const m of matches) yield m.doc
}

/** Order matches within a group: rank, then the name's collation, then the id (a stable answer for equal names). */
export function byRank(collator: Intl.Collator): (a: Match, b: Match) => number {
  return (a, b) => a.rank - b.rank || collator.compare(a.doc.name, b.doc.name) || (a.doc.id < b.doc.id ? -1 : a.doc.id > b.doc.id ? 1 : 0)
}
