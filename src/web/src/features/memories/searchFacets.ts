// A Memory's and a chapter's part of core's search (ADR 0125 decision 6; the whole of Memories' search is `search.ts`'s
// header): each kind's noun, its facets from its sealed meta and the document kinds it contributes. The registry holds these
// eagerly - core reads a kind's facets synchronously while it indexes - so they live apart from `search.ts`, whose space
// listing is fetched on the search's first call (`register.ts`).
import type { KindSearch, SearchFacetContext } from '../../core/applications/registry'
import type { MonthSpan, SearchFacets, YearSpan } from '../../core/search/model'
import { unsealNodeMeta } from '../../core/node/nodeMeta'
import { normalizeDate, type MemoryDate } from './memoryDate'
import { normalizeLocation } from './memoryLocation'
import { normalizeParticipants } from './memoryParticipants'

/** The kind token of a photo's and a video's document. */
export const PHOTO_DOCUMENT = 'memories.photo'
export const VIDEO_DOCUMENT = 'memories.video'

/** Memories' part of a MEMORY's or a CHAPTER's descriptor: the kind's noun (`label`, its own namespace). */
export function memorySearch(label: string): KindSearch {
  return {
    label,
    facets: memorySearchFacets,
    documents: [
      { kind: PHOTO_DOCUMENT, label: 'memory.search.photo', icon: 'image' },
      { kind: VIDEO_DOCUMENT, label: 'memory.search.video', icon: 'play' },
    ],
  }
}

/** The years a Memory's date spans: `from`'s to `to`'s, an ongoing one's up to this year; none without a start day. */
export function yearsOfDate(date: MemoryDate | null, now: Date = new Date()): YearSpan | null {
  const from = date?.from ? Number(date.from.slice(0, 4)) : NaN
  if (!Number.isFinite(from)) return null
  const to = date?.to ? Number(date.to.slice(0, 4)) : date?.mode === 'ongoing' ? Math.max(from, now.getFullYear()) : from
  return { from, to: Number.isFinite(to) && to >= from ? to : from }
}

/** The months a Memory's date spans (`YYYY-MM`): `from`'s to `to`'s, an ongoing one's up to this month; none without a
 *  start day - an undated Memory matches no month chip. */
export function monthsOfDate(date: MemoryDate | null, now: Date = new Date()): MonthSpan | null {
  const from = date?.from?.slice(0, 7)
  if (!from) return null
  const thisMonth = `${String(now.getFullYear()).padStart(4, '0')}-${String(now.getMonth() + 1).padStart(2, '0')}`
  const to = date?.to ? date.to.slice(0, 7) : date?.mode === 'ongoing' && thisMonth > from ? thisMonth : from
  return { from, to: to >= from ? to : from }
}

/** A Memory's or a chapter's facets from its sealed meta (see the header). */
export function memorySearchFacets(ctx: SearchFacetContext): SearchFacets {
  const meta = unsealNodeMeta(ctx.nodeKey, { scopeId: ctx.scopeId, nodeId: ctx.node.id }, ctx.node.wrappedMeta)
  const date = normalizeDate(meta.date)
  const years = yearsOfDate(date)
  const months = monthsOfDate(date)
  const place = normalizeLocation(meta.location)?.name?.trim()
  const people = normalizeParticipants(meta.participants)
  return {
    ...(years ? { years } : {}), ...(months ? { months } : {}), ...(place ? { places: [place] } : {}), ...(people.length ? { people } : {}),
  }
}
