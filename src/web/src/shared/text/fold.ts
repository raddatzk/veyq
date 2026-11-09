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

/**
 * Where each character of `fold(s)` came from in `s`, for drawing a match found in the folded text over the original
 * (the search's bold part, uc.platform.search step 6): folding changes lengths ("ß" is two letters folded, "é" written as "e" + an accent is one), so a
 * folded index is no index of `s`. `start[i]` and `end[i]` are the bounds in `s` of the character that folded to the i-th
 * folded one; a mark that folds to nothing belongs to the character before it. Null when folding character by character
 * does not give `fold(s)` (a letter whose lower case depends on its neighbours) - the caller then draws no match.
 */
export function foldMap(s: string): { folded: string; start: number[]; end: number[] } | null {
  let folded = ''
  const start: number[] = []
  const end: number[] = []
  let at = 0
  for (const ch of s) {
    const f = fold(ch)
    for (let k = 0; k < f.length; k++) {
      start.push(at)
      end.push(at + ch.length)
    }
    if (!f.length && end.length) end[end.length - 1] = at + ch.length
    folded += f
    at += ch.length
  }
  return folded === fold(s) ? { folded, start, end } : null
}
