// The query (ADR 0125 decision 4, uc.platform.search steps 4-5): one text, and chips - each a typed predicate over a
// document's fields, never text. The rules are the Kotlin core's (`search/Query.kt`, through `@veyq/core-js/search`,
// ADR 0155): the chips read from the typed text, a chip as a predicate over every document, the text matched against a
// name - exact, prefix, a word's start, inside a word, then a place or a settings section's word - and a result never
// listed for a reason the member cannot see. The apps answer with the same code, and both are held to
// `contracts/platform/search/v1-vectors.json`.
//
// What stays here is the web's: the shapes it draws (`SearchChip`, the suggestion, a name's parts), and the adapter that
// hands the core a document list once per generation - its corpus, an opaque handle held `markRaw` and dropped with the
// list - and a field's matcher, the core's memory of the queries asked while the list is open.
import { markRaw } from 'vue'
import * as core from '@veyq/core-js/search'
import type { SearchDocument } from './model'

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

/** A part of a name as a result row draws it: `strong` where it matched the text. */
export interface NamePart {
  text: string
  strong: boolean
}

const rules = core.searchRules()
/** The shortest tail of the text a chip is suggested for, folded (a year's four digits aside). */
export const MIN_TAIL = rules.minTail
/** How many chips of one type are suggested at most. */
export const PER_TYPE = rules.perType
/** The ranks of a text match, best first; `place` is a place's match or a settings section's word's. */
export const Rank = {
  exact: rules.rank.exact, prefix: rules.rank.prefix, wordStart: rules.rank.wordStart, inside: rules.rank.inside, place: rules.rank.place,
} as const

// `wordPrefix` and `tailsOf` are exported for a chip type an application adds over the same grammar (Memories' capture day
// in a gallery's filter, `features/memories/galleryFilter.ts`), so the reading of the typed text is one, never a copy.
export { wordPrefix, tailsOf, matchedText } from '@veyq/core-js/search'

/** A chip's identity: two chips of one key are the same predicate. */
export const chipKey = (chip: SearchChip): string => core.chipKey(chip)

/** The core's corpus of a document list: made once per list (the index's generation, a field's contribution moved), and
 *  dropped with it. */
const corpora = new WeakMap<readonly SearchDocument[], core.SearchCorpus>()
export function corpusOf(docs: readonly SearchDocument[]): core.SearchCorpus {
  let corpus = corpora.get(docs)
  if (!corpus) corpora.set(docs, (corpus = markRaw(core.corpus(docs.map(shapeOf)))))
  return corpus
}

/** A document as the core reads it: the web's own, its `open` as whether it opens itself. */
export function shapeOf(d: SearchDocument): core.DocumentShape {
  return {
    scopeId: d.scopeId, kind: d.kind, id: d.id, revision: d.revision, name: d.name, path: d.path, pathIds: d.pathIds, facets: d.facets,
    nodeId: d.nodeId, opensItself: d.open !== undefined, aliases: d.aliases, keywords: d.keywords, subtitle: d.subtitle,
  }
}

/** The chips offered for `text`, none already among `chips`; at most a few per type. */
export function suggestChips(text: string, chips: readonly SearchChip[], sources: ChipSources): ChipSuggestion[] {
  const offered = core.suggestChips(text, chips, {
    corpus: corpusOf(sources.documents), spaces: sources.spaces, people: sources.people, kinds: sources.kinds, months: sources.months,
  })
  // the core spells each chip as the union above does, its type's fields only
  return offered.map((s) => ({ chip: s.chip as SearchChip, text: s.text }))
}

/** Whether `doc` satisfies `chip`. */
export const satisfies = (doc: SearchDocument, chip: SearchChip): boolean => core.satisfies(shapeOf(doc), chip)

/** How `doc` matches the folded text `t`, as its {@link Rank}, or null where it does not. */
export const textRank = (doc: SearchDocument, t: string): number | null => core.textRank(shapeOf(doc), t) ?? null

/** The settings word that is the reason `doc` matches the folded text `t`, as the section names it, or null: a word is the
 *  reason only where neither the name nor another name it carries matched. The row draws it on its second line. */
export const reasonKeyword = (doc: SearchDocument, t: string): string | null => core.reasonKeyword(shapeOf(doc), t) ?? null

/** A value of a chip in force a result carries: `text` shown as it is, or a month chip's `year` and `month` (1-12), which
 *  the field names in the UI's language. */
export type MatchedValue = { text: string } | { year: number; month: number }

/** The values of `chips` that `doc` carries, each once, in the chips' order - its years (a span as "from–to"), the month,
 *  its place as it names it, the person -, drawn beside its row; a space, a kind and an *In:* chip add none. */
export const matchedValues = (doc: SearchDocument, chips: readonly SearchChip[]): MatchedValue[] =>
  core.matchedValues(shapeOf(doc), chips).map((v) => (typeof v.text === 'string' ? { text: v.text } : { year: v.year as number, month: v.month as number }))

/** `name` in parts, the parts that matched the folded text `t` marked `strong`, mapped back over `name`'s own characters. */
export const nameParts = (name: string, t: string): NamePart[] => core.nameParts(name, t).map((p) => ({ text: p.text, strong: p.strong }))

/** A field's memory of the queries asked while its list is open (`createMatcher`). */
export interface Matcher {
  /** Forget every remembered answer: the list closed, the index moved, the member signed out. */
  clear(): void
}

const handles = new WeakMap<Matcher, core.SearchMatcher>()
/** The core's matcher behind `matcher`. */
export const matcherHandle = (matcher: Matcher): core.SearchMatcher => handles.get(matcher)!

/**
 * A matcher for a field the member types into (the core's `Query.Matcher`): it remembers the matches of every query asked
 * while the field is open, for one document list - the same query again is its remembered answer, a query that grew from a
 * remembered one narrows that one's matches, another list forgets everything - and `clear` forgets on the field's word.
 * In memory only; nothing is written anywhere.
 */
export function createMatcher(): Matcher {
  const handle = markRaw(core.matcher())
  const matcher: Matcher = markRaw({ clear: () => core.clearMatcher(handle) })
  handles.set(matcher, handle)
  return matcher
}
