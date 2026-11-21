// The results: ONE list after the chip suggestions (ADR 0125 decision 5, uc.platform.search step 6; owner 2026-11-09:
// "where a result lies should set the order, not a group"). The order is the Kotlin core's (`search/Results.kt`, through
// `@veyq/core-js/search`, ADR 0155): the match first, then where the result lies - below the open node, the rest of the
// active space, the other spaces, then the documents of no space -, then the name, then the id. The name is the one
// order the web keeps: its browser's `Intl.Collator` in the UI's language, passed to the core (ADR 0155 § Consequences),
// where the apps order by the core's own. The list is ranked only as far as it is drawn; `total` stays exact. Each row
// says where it lies (`SearchField.vue`), so the order needs no heading.
import * as core from '@veyq/core-js/search'
import { corpusOf, matcherHandle, type Matcher, type SearchQuery } from './query'
import type { SearchDocument } from './model'

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

/** How far the list is ranked, and the field's matcher (`createMatcher`), which remembers and narrows. */
export interface SearchResultOptions {
  limit?: number
  match?: Matcher
}

/** The matches of `query` in `docs` as one ordered list (see the header): its first `limit` and how many match. */
export function searchResults(
  docs: readonly SearchDocument[], query: SearchQuery, ctx: SearchContext, collator: Intl.Collator, options: SearchResultOptions = {},
): SearchResults {
  const limit = options.limit === undefined || !Number.isFinite(options.limit) ? null : options.limit
  const hits = core.search(corpusOf(docs), query, ctx, limit, options.match ? matcherHandle(options.match) : null, collator.compare)
  return { results: hits.positions.map((at) => docs[at]), total: hits.total }
}
