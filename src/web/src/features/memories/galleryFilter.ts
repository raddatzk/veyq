// A Memory's gallery narrowed by facts (uc.memory.gallery step 8): a capture day, a capture year where the Memory spans more
// than one, a place, a kind (photo, video), and free text over the original's name. It is the search's chip grammar over the
// gallery's own items - core's `core/search/query.ts` reads the typed text, suggests and tests the year, place and kind chips
// and matches the name - plus the capture DAY, which only a gallery has and which therefore lives here (core has no use for
// it). One reading of the text, never a copy of it.
//
// **What it reads.** Only what the opened listing holds (ADR 0105): an item's facts are Memories' search document of it
// (`mediaDocuments` in `search.ts`: the original's name, its capture time with the correction first, its place), so the
// gallery and the search never disagree about a photo. Nothing is fetched, sent or stored for the filter; its state is the
// view's. A medium blocked by the operator has no document (what it shows is the operator's placeholder, not its facts), so
// every active filter leaves it out.
//
// **The day and the year are the viewer's**, in the time zone the tiles and the lightbox show dates in: `timeZone` is left
// out in the app and named by a test only. A medium without a capture time matches no day and no year chip.
//
// **Combining** ({@link galleryPredicate}): an item stays when it passes the mark filter (ADR 0068) AND every chip AND the
// text. The grid's order is untouched; the predicate only removes items.
import { fold } from '../../shared/text/fold'
import { searchDocument, type SearchDocument } from '../../core/search/model'
import { chipKey, MIN_TAIL, PER_TYPE, Rank, satisfies, suggestChips, tailsOf, textRank, wordPrefix, type SearchChip } from '../../core/search/query'
import { mediaDocuments, PHOTO_DOCUMENT, VIDEO_DOCUMENT } from './search'
import type { MediaItem } from './media'

/** A capture day: `day` is `YYYY-MM-DD` in the viewer's time zone, `label` as the chip reads it ("3. Mai"). */
export interface DayChip {
  type: 'day'
  day: string
  label: string
}
/** The search's chips a gallery takes. */
export type FactSearchChip = Extract<SearchChip, { type: 'year' | 'place' | 'kind' }>
/** The chips of a gallery's filter: the search's year, place and kind, and the day. */
export type GalleryChip = FactSearchChip | DayChip

/** A chip's identity: two chips of one key are the same predicate. */
export function galleryChipKey(chip: GalleryChip): string {
  return chip.type === 'day' ? `day:${chip.day}` : chipKey(chip)
}

/** A chip as it reads (on the chip, in a suggestion, in the empty state). */
export function galleryChipLabel(chip: GalleryChip): string {
  return chip.type === 'year' ? String(chip.year) : chip.label
}

/** One medium's facts as the filter tests them. */
export interface GalleryFact {
  doc: SearchDocument
  /** Its capture day (`YYYY-MM-DD`, the viewer's), or null without a capture time. */
  day: string | null
}

/** What the gallery's items offer the filter. */
export interface GalleryFacts {
  byId: ReadonlyMap<string, GalleryFact>
  documents: readonly SearchDocument[]
  /** Every capture day that occurs, oldest first, with its chip's label. */
  days: ReadonlyMap<string, string>
  /** Every capture year that occurs. */
  years: ReadonlySet<number>
  photos: boolean
  videos: boolean
}

export interface GalleryFactsInput {
  scopeId: string
  /** The key the gallery opened its listing under. */
  key: Uint8Array
  items: readonly MediaItem[]
  /** The UI's language, for the day's label. */
  locale: string
  /** The time zone of the day and the year; the viewer's when left out. */
  timeZone?: string
}

/** The calendar day of `at` in `timeZone`, as `YYYY-MM-DD`. */
function dayKeyFormat(timeZone: string | undefined): (at: number) => string {
  const format = new Intl.DateTimeFormat('en-CA', { timeZone, year: 'numeric', month: '2-digit', day: '2-digit' })
  return (at) => {
    const parts = Object.fromEntries(format.formatToParts(at).map((p) => [p.type, p.value]))
    return `${parts.year}-${parts.month}-${parts.day}`
  }
}

/** Each medium's facts from the opened listing (see the header), and what occurs among them. */
export function galleryFacts(input: GalleryFactsInput): GalleryFacts {
  const dayOf = dayKeyFormat(input.timeZone)
  const byId = new Map<string, GalleryFact>()
  const documents: SearchDocument[] = []
  const firstAt = new Map<string, number>()
  const years = new Set<number>()
  let photos = false
  let videos = false
  // No placeholder name: the text matches the original's own name, and a medium that carries none matches no text.
  for (const d of mediaDocuments({ scopeId: input.scopeId, key: input.key, items: input.items, unnamed: () => '', open: () => {} })) {
    const at = d.facets.capturedAt
    const day = at !== undefined && Number.isFinite(at) ? dayOf(at) : null
    const year = day ? Number(day.slice(0, 4)) : null
    // The year in the same zone as the day: core's year chip reads `years` before a capture time's own year.
    const facets = year !== null ? { ...d.facets, years: { from: year, to: year } } : d.facets
    const doc = searchDocument({ ...d, facets, scopeId: input.scopeId, nodeId: '', path: [] })
    byId.set(d.id, { doc, day })
    documents.push(doc)
    if (day && at !== undefined && !firstAt.has(day)) firstAt.set(day, at)
    if (year !== null) years.add(year)
    if (d.kind === VIDEO_DOCUMENT) videos = true
    else photos = true
  }
  // A day's label names its year only where the Memory spans more than one, so two "3. Mai" never read alike.
  const label = new Intl.DateTimeFormat(input.locale, { timeZone: input.timeZone, day: 'numeric', month: 'long', ...(years.size > 1 ? { year: 'numeric' } : {}) })
  const days = new Map([...firstAt].sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0)).map(([day, at]) => [day, label.format(at)]))
  return { byId, documents, days, years, photos, videos }
}

/** A chip offered for the typed text: `text` is what stays once it is chosen. */
export interface GallerySuggestion {
  chip: GalleryChip
  text: string
}

const isSearchChip = (c: GalleryChip): c is FactSearchChip => c.type !== 'day'

/**
 * The chips offered for `text`, none already among `chips`: a year that occurs where two or more do, the days whose label
 * the longest tail of the text that names any begins at a word (core's reading of the text), the places the items carry, and photo or video where both occur.
 * `words` are the kinds' words in the UI's language.
 */
export function suggestGalleryChips(
  text: string,
  chips: readonly GalleryChip[],
  facts: GalleryFacts,
  words: { photo: string; video: string },
): GallerySuggestion[] {
  const offered = suggestChips(text, chips.filter(isSearchChip), {
    documents: facts.documents,
    spaces: [],
    people: [],
    kinds: facts.photos && facts.videos ? [{ label: words.photo, kinds: [PHOTO_DOCUMENT] }, { label: words.video, kinds: [VIDEO_DOCUMENT] }] : [],
  }) as { chip: FactSearchChip; text: string }[]
  const years = offered.filter((s) => s.chip.type === 'year' && facts.years.size > 1 && facts.years.has(s.chip.year))
  const taken = new Set(chips.map(galleryChipKey))
  // The days of the LONGEST tail that names any: "4. mai" is the 4th, not every day of May with "4." left as text.
  const days: GallerySuggestion[] = []
  for (const { tail, rest } of tailsOf(text)) {
    const t = fold(tail)
    if (t.length < MIN_TAIL) continue
    for (const [day, label] of facts.days) {
      if (days.length >= PER_TYPE) break
      if (!taken.has(`day:${day}`) && wordPrefix(fold(label), t)) days.push({ chip: { type: 'day', day, label }, text: rest })
    }
    if (days.length) break
  }
  return [...years, ...days, ...offered.filter((s) => s.chip.type !== 'year')]
}

/** Whether one medium's facts pass every chip and the folded text `t` (the original's name; a place is a chip's). */
function keeps(fact: GalleryFact, chips: readonly GalleryChip[], t: string): boolean {
  for (const c of chips) if (c.type === 'day' ? fact.day !== c.day : !satisfies(fact.doc, c)) return false
  if (!t) return true
  const rank = textRank(fact.doc, t)
  return rank !== null && rank < Rank.place
}

/** Whether `chips` or `text` narrow anything at all. */
export function narrowsByFacts(chips: readonly GalleryChip[], text: string): boolean {
  return chips.length > 0 || text.trim() !== ''
}

/** The facts' predicate over an item, or null where neither a chip nor text is given (the gallery then pays nothing). */
export function factPredicate(facts: () => GalleryFacts | null, chips: readonly GalleryChip[], text: string): ((item: MediaItem) => boolean) | null {
  if (!narrowsByFacts(chips, text)) return null
  const all = facts()
  const t = fold(text.trim()).replace(/\s+/g, ' ')
  return (item) => {
    const fact = all?.byId.get(item.mediaId)
    return !!fact && keeps(fact, chips, t)
  }
}

/** The gallery's one predicate: the mark filter AND the facts; null where neither narrows (see the header). */
export function galleryPredicate(
  marks: ((mediaId: string) => boolean) | null,
  facts: ((item: MediaItem) => boolean) | null,
): ((item: MediaItem) => boolean) | null {
  if (!marks && !facts) return null
  return (item) => (!marks || marks(item.mediaId)) && (!facts || facts(item))
}
