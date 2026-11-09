// What Memories adds to core's search (ADR 0125 decision 6, uc.platform.search step 3). Two parts, both through the
// registry - core never imports this:
//
// - **A Memory's and a chapter's facets** (`KindDescriptor.search.facets`): its date range as years, its place's name and its
//   participants' user ids, read from its sealed meta under the key core hands the hook for that call. Never a name of a
//   person: core's directory names them when a chip or a row shows one.
// - **The open Memory's photos and videos** (`MemoryDetail`'s contribution, `core/search/contribution.ts`): one document per
//   medium of the listing the gallery already opened (ADR 0105) - the original's name, its capture time and its place, as
//   `openedMeta` holds them - at no fetch of its own. Opening one opens the lightbox on it.
//
// A photo's own place and date are searched only here, in the open Memory (ADR 0125 decision 8a is not built).
import { unsealNodeMeta } from '../../core/node/nodeMeta'
import type { KindSearch, SearchFacetContext } from '../../core/applications/registry'
import type { ContributedDocument, SearchFacets, YearSpan } from '../../core/search/model'
import { normalizeDate, type MemoryDate } from './memoryDate'
import { normalizeLocation } from './memoryLocation'
import { normalizeParticipants } from './memoryParticipants'
import { effectiveCapturedAt, isVideoMediaType, mediaTypeOf, type MediaItem } from './media'
import { openedBlobMeta, openedMeta } from './openedListing'

/** The kind token of a photo's and a video's document. */
export const PHOTO_DOCUMENT = 'memories.photo'
export const VIDEO_DOCUMENT = 'memories.video'

/** Memories' part of a MEMORY's or a CHAPTER's descriptor: the kind's noun (`label`, its own namespace). */
export function memorySearch(label: string): KindSearch {
  return {
    label,
    note: 'memory.search.note',
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

/** A Memory's or a chapter's facets from its sealed meta (see the header). */
export function memorySearchFacets(ctx: SearchFacetContext): SearchFacets {
  const meta = unsealNodeMeta(ctx.nodeKey, { scopeId: ctx.scopeId, nodeId: ctx.node.id }, ctx.node.wrappedMeta)
  const years = yearsOfDate(normalizeDate(meta.date))
  const place = normalizeLocation(meta.location)?.name?.trim()
  const people = normalizeParticipants(meta.participants)
  return { ...(years ? { years } : {}), ...(place ? { places: [place] } : {}), ...(people.length ? { people } : {}) }
}

/** What the gallery hands over to make its documents. */
export interface MediaDocumentsInput {
  scopeId: string
  /** The open Memory's key, the one the gallery opened its listing under. */
  key: Uint8Array
  items: readonly MediaItem[]
  /** A medium's name when its original carries none (its words, e.g. "Photo"). */
  unnamed: (video: boolean) => string
  /** Open the lightbox on the medium. */
  open: (mediaId: string) => void
}

/** One document per medium of the open Memory (see the header). */
export function mediaDocuments(input: MediaDocumentsInput): ContributedDocument[] {
  return input.items.map((item) => {
    const meta = openedMeta(input.scopeId, input.key, item)
    const blob = openedBlobMeta(input.scopeId, input.key, item)
    const video = isVideoMediaType(mediaTypeOf(blob))
    const capturedAt = effectiveCapturedAt(meta)
    const place = meta?.placeName?.trim()
    return {
      kind: video ? VIDEO_DOCUMENT : PHOTO_DOCUMENT,
      id: item.mediaId,
      // the seals the document was read from: a meta edit or a replaced original is a new document
      revision: `${item.metaRevision ?? 0}:${item.original.blobId}`,
      name: blob?.name?.trim() || input.unnamed(video),
      facets: { ...(capturedAt !== undefined ? { capturedAt } : {}), ...(place ? { places: [place] } : {}) },
      icon: video ? 'play' : 'image',
      open: () => input.open(item.mediaId),
    }
  })
}
