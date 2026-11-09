// The results: ONE list after the chip suggestions (ADR 0125 decision 5, uc.platform.search step 6; owner 2026-11-09:
// "where a result lies should set the order, not a group"). The order is the match first - an exact name, a prefix, a
// word's start, inside a word, a place or a settings section's word (`query.ts` `Rank`) -, then where the result lies -
// below the open node (what its page contributed and the nodes below it), the rest of the active space, the other spaces,
// then the documents of no space (the tenant's members, the settings sections: decision 9) -, then the name's collation,
// then the id. So a strong match elsewhere comes before a weak one here: where the member stands breaks ties and never
// hides a result. Each row says where it lies (`SearchField.vue`), so the order needs no heading.
//
// **No kind of document is hidden by a rule of its own** (ADR 0125 decisions 4 and 9): a chip is a predicate over
// every document (`query.ts` `satisfies`), and a person or a settings section a chip leaves in is listed like content.
//
// **The list is ranked only as far as it is drawn** (ADR 0125 decision 5): the field shows a page of results, and a one-letter
// query matches thousands of documents. So the first `limit` results are SELECTED - kept in order in a list of that
// length while every match passes once - instead of sorting every match on every keystroke; `total` stays exact.
import { matchAll, type Matcher, type SearchQuery } from './query'
import { PERSON_DOCUMENT, SETTINGS_DOCUMENT, type SearchDocument } from './model'

/** Where a result lies, nearest first: its second key in the order. */
export const Place = { open: 0, space: 1, other: 2, nowhere: 3 } as const

/** Where the member stands. */
export interface SearchContext {
  /** The space on screen, or null (no space open). */
  activeScopeId: string | null
  /** The node whose page is on screen (not a folder level), in the active space, or null. */
  openNodeId: string | null
}

export interface SearchResults {
  /** The best matches, in order: the first `limit` (every match without one). */
  results: readonly SearchDocument[]
  /** How many documents match, those beyond `results` included. */
  total: number
}

/** How far the list is ranked, and how the documents are matched (a field's `createMatcher`). */
export interface SearchResultOptions {
  limit?: number
  match?: Matcher
}

/** A match with the two keys the order reads before the name. */
export interface Ranked {
  doc: SearchDocument
  rank: number
  place: number
}

/**
 * Where `doc` lies for a member standing at `ctx`, or null for a document that is not listed: a document a page
 * contributed counts only for the node it was contributed for, and only while that node is the one open in the active
 * space - a page left behind contributes nothing. Below the open node is what lies below it by id (`pathIds`, the rule an
 * *In:* chip reads), an object of the open node included; the node itself lies in its space.
 */
export function placeOf(doc: SearchDocument, ctx: SearchContext): number | null {
  if (doc.kind === PERSON_DOCUMENT || doc.kind === SETTINGS_DOCUMENT) return Place.nowhere
  const open = ctx.openNodeId !== null && doc.scopeId === ctx.activeScopeId
  if (doc.open) return open && doc.nodeId === ctx.openNodeId ? Place.open : null
  if (doc.scopeId !== ctx.activeScopeId) return Place.other
  return open && doc.pathIds.includes(ctx.openNodeId!) ? Place.open : Place.space
}

/** The matches of `query` in `docs` as one ordered list (see the header): its first `limit` and how many match. */
export function searchResults(
  docs: readonly SearchDocument[], query: SearchQuery, ctx: SearchContext, collator: Intl.Collator, options: SearchResultOptions = {},
): SearchResults {
  const ranked: Ranked[] = []
  for (const m of (options.match ?? matchAll)(docs, query)) {
    const place = placeOf(m.doc, ctx)
    if (place !== null) ranked.push({ doc: m.doc, rank: m.rank, place })
  }
  return { results: firstRanked(ranked, options.limit ?? Infinity, inOrder(collator)).map((r) => r.doc), total: ranked.length }
}

/** The order (see the header): the rank, where the result lies, the name's collation, the id (a stable answer for equal names). */
export function inOrder(collator: Intl.Collator): (a: Ranked, b: Ranked) => number {
  return (a, b) =>
    a.rank - b.rank || a.place - b.place || collator.compare(a.doc.name, b.doc.name) || (a.doc.id < b.doc.id ? -1 : a.doc.id > b.doc.id ? 1 : 0)
}

/** The first `n` of `matches` in `order`, without sorting the rest: each match is compared with the last one kept and, when
 *  it is better, put in its place by a binary search. `matches` itself is left as it was. */
export function firstRanked<T>(matches: readonly T[], n: number, order: (a: T, b: T) => number): T[] {
  if (matches.length <= n) return [...matches].sort(order)
  const kept: T[] = []
  if (n <= 0) return kept
  for (const m of matches) {
    if (kept.length === n && order(m, kept[n - 1]) >= 0) continue
    let lo = 0
    let hi = kept.length
    while (lo < hi) {
      const mid = (lo + hi) >> 1
      if (order(m, kept[mid]) < 0) hi = mid
      else lo = mid + 1
    }
    kept.splice(lo, 0, m)
    if (kept.length > n) kept.pop()
  }
  return kept
}
