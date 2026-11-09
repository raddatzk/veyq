// The query (ADR 0125 decision 4, uc.platform.search steps 4-5): one text, and chips - each a typed predicate over a
// document's fields, never text. Pure functions over documents; nothing here reads the network, a key or any storage.
//
// **Chips from the typed text.** Every tail of the text that starts at a word - "rom anna sch", "anna sch", "sch" - is read
// against what the index knows: four digits are a year; a month's name (its first three letters at least, in the UI's
// language or in English) followed by four digits is that month - a month's name alone is no chip, the year is what makes
// it a filter rather than a word of a name; a prefix of a word of a member's name is that person; of a place a
// document carries, that place; of a space's name, that space; of the name of a node something lies below, *In:* that
// node; of a kind's word, that kind. Choosing one turns that tail into the chip and the rest stays text.
//
// **A chip is a predicate over every document** (cr-search-chips-for-everything): a person and a settings section are
// documents like any other, and a chip they cannot satisfy - a year, a place, *In:* - leaves them out by itself; no rule
// hides a kind of document while a chip is set. *Person: X* asks a document's participants, so X's own profile does not
// satisfy it. *In:* asks the ids of the nodes a document lies below (`pathIds`), never a name.
//
// **Matching and ranking.** A document matches when it satisfies every chip and the folded remaining text matches its name
// (exact, prefix, the start of a word, anywhere - in that rank; another name it carries, a person's `~username`, counts as
// its name) or, ranked after every name match, one of its places or - from a word's start only - a word it is found by
// (a settings section's). The order of the results is `results.ts`'s: the rank, then where the result lies, then the name.
//
// **A result is never listed for a reason the member cannot see** (cr-search-visible-reasons, uc.platform.search step 5):
// one typed character matches word starts only - a match inside a word counts from {@link MIN_INSIDE} characters on, so
// "K" lists no "Reiselink" -, and a settings section's word counts from {@link MIN_KEYWORD} on and is drawn on the row's
// second line where it is the reason ({@link reasonKeyword}). Both rules start at a length, so the matcher narrows only
// from a text long enough that every rule holds (`createMatcher`).
import { fold, foldMap } from '../../shared/text/fold'
import { monthsOf, yearsOf, type SearchDocument } from './model'

export type SearchChip =
  | { type: 'space'; scopeId: string; label: string }
  | { type: 'year'; year: number }
  /** A calendar month: `month` 1-12. */
  | { type: 'month'; year: number; month: number }
  /** `place` folded; `label` as a document names it. */
  | { type: 'place'; place: string; label: string }
  | { type: 'person'; userId: string; label: string }
  /** Every kind whose word is `label`. */
  | { type: 'kind'; kinds: readonly string[]; label: string }
  /** What lies below the node `nodeId` of the space `scopeId`: `label` its name, `path` the names above it (what tells two
   *  nodes of one name apart). */
  | { type: 'in'; scopeId: string; nodeId: string; label: string; path: readonly string[] }

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
    case 'month':
      return `month:${monthOf(chip.year, chip.month)}`
    case 'place':
      return `place:${chip.place}`
    case 'person':
      return `person:${chip.userId}`
    case 'kind':
      return `kind:${[...chip.kinds].sort().join(',')}`
    case 'in':
      return `in:${chip.scopeId}:${chip.nodeId}`
  }
}

/** What a chip may be suggested from. */
export interface ChipSources {
  documents: readonly SearchDocument[]
  spaces: readonly { scopeId: string; label: string }[]
  people: readonly { userId: string; name: string }[]
  /** Each kind word with the kinds it names. */
  kinds: readonly { label: string; kinds: readonly string[] }[]
  /** Each month's names, January first (the UI's language's and English); without them no month is offered. */
  months?: readonly (readonly string[])[]
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
/** The fewest letters of a month's name a month chip is offered for ("mär", "sep"). */
export const MIN_MONTH = 3

/** A month as `YYYY-MM`, the shape `SearchFacets.months` holds. */
export const monthOf = (year: number, month: number): string => `${String(year).padStart(4, '0')}-${String(month).padStart(2, '0')}`

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
  const byType: Record<SearchChip['type'], ChipSuggestion[]> = { month: [], year: [], person: [], place: [], space: [], in: [], kind: [] }
  const offer = (s: ChipSuggestion) => {
    const key = chipKey(s.chip)
    if (taken.has(key) || seen.has(key) || byType[s.chip.type].length >= PER_TYPE) return
    seen.add(key)
    byType[s.chip.type].push(s)
  }
  const tails = tailsOf(text)
  const last = tails.at(-1)
  if (last && YEAR.test(last.tail)) {
    offer({ chip: { type: 'year', year: Number(last.tail) }, text: last.rest })
    // the one word before the year: a month's name, or its start
    const before = tails.at(-2)
    const word = before ? fold(last.rest.slice(before.rest.length).trim()) : ''
    if (before && word.length >= MIN_MONTH && !/\s/.test(word)) {
      sources.months?.forEach((names, i) => {
        if (names.some((n) => foldOnce(n).startsWith(word))) offer({ chip: { type: 'month', year: Number(last.tail), month: i + 1 }, text: before.rest })
      })
    }
  }
  const places = placesOfCached(sources.documents)
  const holders = holdersOfCached(sources.documents)
  for (const { tail, rest } of tails) {
    const t = fold(tail)
    if (t.length < MIN_TAIL) continue
    for (const p of sources.people) if (wordPrefix(foldOnce(p.name), t)) offer({ chip: { type: 'person', userId: p.userId, label: p.name }, text: rest })
    for (const [place, label] of places) if (wordPrefix(place, t)) offer({ chip: { type: 'place', place, label }, text: rest })
    for (const s of sources.spaces) if (wordPrefix(foldOnce(s.label), t)) offer({ chip: { type: 'space', scopeId: s.scopeId, label: s.label }, text: rest })
    for (const n of holders) {
      if (wordPrefix(n.foldedName, t)) offer({ chip: { type: 'in', scopeId: n.scopeId, nodeId: n.id, label: n.name, path: n.path }, text: rest })
    }
    for (const k of sources.kinds) if (wordPrefix(foldOnce(k.label), t)) offer({ chip: { type: 'kind', kinds: k.kinds, label: k.label }, text: rest })
  }
  return [...byType.month, ...byType.year, ...byType.person, ...byType.place, ...byType.space, ...byType.in, ...byType.kind]
}

/** A name of a suggestion's source (a member, a space, a kind's word, a month) folded once, not on every keystroke
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

/** {@link holdersOf} once per document list, as the places. */
const holdersByList = new WeakMap<readonly SearchDocument[], SearchDocument[]>()
function holdersOfCached(docs: readonly SearchDocument[]): SearchDocument[] {
  let holders = holdersByList.get(docs)
  if (!holders) holdersByList.set(docs, (holders = holdersOf(docs)))
  return holders
}

/** The documents of the nodes something lies below - a folder, a Memory, a Chapter with something in it: what an *In:*
 *  chip is offered for. Read from the documents' `pathIds`, so core names no kind, and a node with nothing below it,
 *  where the chip would find nothing, is not offered. */
function holdersOf(docs: readonly SearchDocument[]): SearchDocument[] {
  const below = new Set<string>()
  for (const d of docs) for (const id of d.pathIds) below.add(`${d.scopeId}\u0000${id}`)
  return docs.filter((d) => d.id === d.nodeId && !d.open && below.has(`${d.scopeId}\u0000${d.id}`))
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
    case 'month': {
      const months = monthsOf(doc)
      const month = monthOf(chip.year, chip.month)
      return !!months && months.from <= month && month <= months.to
    }
    case 'place':
      return doc.foldedPlaces.includes(chip.place)
    case 'person':
      return !!doc.facets.people?.includes(chip.userId)
    case 'kind':
      return chip.kinds.includes(doc.kind)
    case 'in':
      // by id, never by name: two nodes of one name are two places; the space too, as an id is the space's
      return doc.scopeId === chip.scopeId && doc.pathIds.includes(chip.nodeId)
  }
}

/** The ranks of a text match, best first; `place` is a place's match or a settings section's word's (ADR 0125 decision 5). */
export const Rank = { exact: 0, prefix: 1, wordStart: 2, inside: 3, place: 4 } as const

/** The fewest characters of the folded text a match inside a word counts from: one character matches word starts only. */
export const MIN_INSIDE = 2
/** The fewest characters of the folded text a settings section's word counts from ("Ke" finds no "Kennwort"). */
export const MIN_KEYWORD = 3
/** The length from which every rule of {@link textRank} holds, and so the shortest text the matcher narrows from. */
const EVERY_RULE = Math.max(MIN_INSIDE, MIN_KEYWORD)
/** The characters of the folded text `t` - code points, so a character outside the basic plane is one: every UTF-16 unit
 *  but a trailing surrogate. No allocation - {@link textRank} asks it for every document. */
function lengthOf(t: string): number {
  let n = 0
  for (let i = 0; i < t.length; i++) if ((t.charCodeAt(i) & 0xfc00) !== 0xdc00) n++
  return n
}

/** The words of the folded text `t`, split once per query: {@link matchAll} asks every document with them, and a split per
 *  document was a regular expression and an array for each of 17,000 documents on every keystroke (cr-search-photos-of-every-space). */
const wordsOf = (t: string): string[] => t.split(/\s+/).filter(Boolean)

/** How `doc` matches the folded text `t` (its `words`, when the caller split them already), or null when it does not; an
 *  empty text matches every document at the best rank. **Every rule here must be monotone in the text from
 *  {@link EVERY_RULE} characters on** - a document a longer text matches, the shorter text it grew from matches too, once
 *  that one is that long - since {@link createMatcher} narrows remembered matches while the text grows. Below it two rules
 *  are not (a match inside a word from {@link MIN_INSIDE}, a settings word from {@link MIN_KEYWORD}): "Ke" matches what
 *  "K" did not, and the matcher never narrows from such a text. */
export function textRank(doc: SearchDocument, t: string, words: readonly string[] = wordsOf(t)): number | null {
  if (!t) return Rank.exact
  const inside = lengthOf(t) >= MIN_INSIDE
  const best = bestNameRank(doc, t, words, inside)
  if (best !== null) return best
  // a word the member does not see matches from a word's start only and from MIN_KEYWORD on, and ranks with a place
  if (keywordAt(doc, t, words) >= 0) return Rank.place
  if (doc.foldedPlaces.some((p) => (inside ? p.includes(t) : wordPrefix(p, t)))) return Rank.place
  return null
}

/** The best rank of `doc`'s name or another name it carries, or null. */
function bestNameRank(doc: SearchDocument, t: string, words: readonly string[], inside: boolean): number | null {
  let best = nameRank(doc.foldedName, t, words, inside)
  for (const alias of doc.foldedAliases) {
    const rank = nameRank(alias, t, words, inside)
    if (rank !== null && (best === null || rank < best)) best = rank
  }
  return best
}

/** Which of `doc`'s settings words the folded text `t` matches - from a word's start, and only from {@link MIN_KEYWORD}
 *  characters on -, as its index, or -1. */
function keywordAt(doc: SearchDocument, t: string, words: readonly string[]): number {
  if (!doc.foldedKeywords.length || lengthOf(t) < MIN_KEYWORD) return -1
  return doc.foldedKeywords.findIndex((keyword) => {
    const rank = nameRank(keyword, t, words, false)
    return rank !== null && rank < Rank.inside
  })
}

/**
 * The settings word that is the reason `doc` matches the folded text `t`, as the section names it ("Kennwort"), or null:
 * a word is the reason only where neither the name nor another name it carries matched. The row draws it on its second
 * line - a result never stands there for a reason the member cannot see (uc.platform.search step 5).
 */
export function reasonKeyword(doc: SearchDocument, t: string): string | null {
  if (!t || !doc.keywords?.length) return null
  const words = wordsOf(t)
  if (bestNameRank(doc, t, words, lengthOf(t) >= MIN_INSIDE) !== null) return null
  const at = keywordAt(doc, t, words)
  return at >= 0 ? doc.keywords[at] : null
}

/** How the folded name `name` matches the folded text `t` (split into `words`), or null; a match inside a word only where
 *  `inside` allows it (the text is {@link MIN_INSIDE} characters at least). */
function nameRank(name: string, t: string, words: readonly string[], inside: boolean): number | null {
  // Most documents do not hold the text at all (a photo's IMG_… for "K"): one search answers them, not four.
  if (!name.includes(t)) return words.length > 1 && words.every((w) => name.includes(w)) ? Rank.inside : null
  if (name === t) return Rank.exact
  if (name.startsWith(t)) return Rank.prefix
  if (wordPrefix(name, t)) return Rank.wordStart
  return inside ? Rank.inside : null
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

/** {@link matchAll} with a memory of the queries asked while a field is open (`createMatcher`). */
export interface Matcher {
  (docs: readonly SearchDocument[], query: SearchQuery): Match[]
  /** Forget every remembered answer: the list closed, the index moved, the member signed out. */
  clear(): void
}

/** How many queries a matcher remembers at most (the oldest asked goes first). */
export const MATCH_HISTORY = 64

/**
 * A {@link matchAll} for a field the member types into (cr-search-polish). It remembers the matches of every query asked
 * while the field is open (the owner, 2026-11-09), by its chips and its folded text, for one list of documents:
 * - **The same query again** - a "show more", or Backspace from "Kev" back to "Ke" - is the remembered answer as it was.
 * - **A query that grew** from a remembered one (the same chips, a text that starts with that one's) narrows the matches of
 *   the longest such query instead of scanning every document. That is sound because a text that grows only drops matches
 *   ({@link textRank} is monotone in the text from {@link EVERY_RULE} characters on): every word of the longer text holds a
 *   word of the shorter one, and a name, an alias, a keyword or a place that holds the longer text holds the shorter. So
 *   only a text that long, or an empty one (it matches every document the chips leave), is narrowed from: "K" matches word
 *   starts only and "Ke" inside words too, "Ke" no settings word and "Ken" one - each of those scans every document
 *   (cr-search-visible-reasons).
 * - **Anything else** - a new chip, a text no remembered one starts - scans every document.
 * Another list of documents - the index's next generation: a space rebuilt, a listing re-read, the open Memory's
 * contribution or the roster moved - forgets everything, and so does {@link Matcher.clear}, which the field calls when its
 * list closes and when it goes. A query that asks nothing is never remembered: its empty answer is not the matches of every
 * text. In memory only; nothing is written anywhere.
 */
export function createMatcher(): Matcher {
  let known: readonly SearchDocument[] | null = null
  const history = new Map<string, { chips: string; t: string; matches: Match[] }>()
  const match = ((docs: readonly SearchDocument[], query: SearchQuery): Match[] => {
    const t = foldedText(query)
    if (!t && query.chips.length === 0) return []
    if (docs !== known) {
      history.clear()
      known = docs
    }
    const chips = query.chips.map(chipKey).sort().join('\u0000')
    const key = `${chips}\u0001${t}`
    const asked = history.get(key)
    if (asked) {
      history.delete(key) // asked again: the newest
      history.set(key, asked)
      return asked.matches
    }
    let from: { t: string; matches: Match[] } | undefined
    for (const h of history.values()) {
      if (h.chips === chips && t.startsWith(h.t) && narrowsFrom(h.t) && (!from || h.t.length > from.t.length)) from = h
    }
    const matches = from ? matchIn(docsOf(from.matches), query.chips, t) : matchIn(docs, query.chips, t)
    history.set(key, { chips, t, matches })
    if (history.size > MATCH_HISTORY) history.delete(history.keys().next().value!)
    return matches
  }) as Matcher
  match.clear = () => {
    history.clear()
    known = null
  }
  return match
}

/** Whether the matches of the folded text `t` hold every match of any text grown from it: an empty text (every document
 *  the chips leave), or one long enough that every rule of {@link textRank} holds. */
const narrowsFrom = (t: string): boolean => t === '' || lengthOf(t) >= EVERY_RULE

function* docsOf(matches: readonly Match[]): Generator<SearchDocument> {
  for (const m of matches) yield m.doc
}

/** A part of a name as a result row draws it: `strong` where it matched the text. */
export interface NamePart {
  text: string
  strong: boolean
}

/**
 * Where the folded text `t` matched the folded `name`, as ranges of the FOLDED name: the whole text where the name holds it
 * (at a word's start where it does so, else its first place), else each word of the text at its first place - the ranges
 * {@link textRank} matched on, merged where they touch. None where the name did not match (an alias or a place did).
 */
export function matchedRanges(name: string, t: string): [number, number][] {
  if (!t) return []
  const at = (w: string): number => {
    for (let i = name.indexOf(w); i >= 0; i = name.indexOf(w, i + 1)) if (isBoundary(name, i)) return i
    return name.indexOf(w)
  }
  const whole = at(t)
  const words = whole >= 0 ? [t] : wordsOf(t)
  const ranges: [number, number][] = []
  for (const w of words) {
    const i = at(w)
    if (i < 0) return []
    ranges.push([i, i + w.length])
  }
  ranges.sort((a, b) => a[0] - b[0])
  const merged: [number, number][] = []
  for (const r of ranges) {
    const prev = merged.at(-1)
    if (prev && r[0] <= prev[1]) prev[1] = Math.max(prev[1], r[1])
    else merged.push([r[0], r[1]])
  }
  return merged
}

/**
 * `name` in parts, the parts that matched the folded text `t` marked `strong` ("**Kev**ins Geburtstag"). The match is found
 * in the folded name and mapped back to `name`'s own characters (`foldMap`): "Straße" for "strasse" is all of "Straße",
 * "Café" for "cafe" all of "Café". A name that does not fold character by character is one plain part.
 */
export function nameParts(name: string, t: string): NamePart[] {
  const map = t ? foldMap(name) : null
  const ranges = map ? matchedRanges(map.folded, t) : []
  if (!map || !ranges.length) return [{ text: name, strong: false }]
  const parts: NamePart[] = []
  let at = 0
  for (const [a, b] of ranges) {
    const from = Math.max(map.start[a], at)
    const to = map.end[b - 1]
    if (from > at) parts.push({ text: name.slice(at, from), strong: false })
    parts.push({ text: name.slice(from, to), strong: true })
    at = to
  }
  if (at < name.length) parts.push({ text: name.slice(at), strong: false })
  return parts
}

/** The text of a query as {@link matchAll} matches it (folded, trimmed, one space between words): what {@link nameParts} takes. */
export const matchedText = (text: string): string => foldedText({ chips: [], text })
