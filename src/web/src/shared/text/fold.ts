// Text compared as a person reads it, case and accents aside (ADR 0125 decision 4): "Zoë", "ZOE" and "zoe" are the same
// letters, and so are "Straße" and "STRASSE". One helper for every place that matches what a person typed against a name -
// the search (`core/search/`), a picker's filter (Memories' participants, Expenses' members) - so two of them never disagree
// about what matches.
//
// **Not a slug.** `shared/naming.ts`' `slugify` folds a name into a URL segment and a blinded slug token, and that folding is
// frozen into stored tokens (ADR 0080 decision 16); this one is for comparing in memory and may change with what reads
// naturally. Never use one for the other.

/** `s` decomposed (NFD), its combining marks dropped, lower-cased, `ß` as `ss`. */
export function fold(s: string): string {
  return s.normalize('NFD').replace(/\p{M}/gu, '').toLowerCase().replace(/ß/g, 'ss')
}
