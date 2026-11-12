// What Memories adds to core's search (ADR 0125 decisions 6 and 8a, uc.platform.search step 3). Three parts, all through the
// registry - core never imports this:
//
// - **A Memory's and a chapter's facets** (`KindDescriptor.search.facets`): its date range as years and as months, its place's name and its
//   participants' user ids, read from its sealed meta under the key core hands the hook for that call. Never a name of a
//   person: core's directory names them when a chip or a row shows one.
// - **Every photo and video of every space** (`ApplicationRegistrar.search`, {@link scopeMediaDocuments}): the space's
//   listing (`listScopeMedia`) read by its tag - a `304` keeps the documents -, its rows opened per node in the listing
//   opener Worker (`openedListing.ts`) under the node keys core hands over for that call, and closed again before it
//   returns. One document per medium - the original's name, its capture time and its place; opening one opens its Memory
//   with the medium in the lightbox (core's `openRequest.ts`, taken by `MemoryDetail`).
// - **The open Memory's photos and videos** (`MemoryDetail`'s contribution, `core/search/contribution.ts`): the same
//   documents from the listing the gallery already opened (ADR 0105), at no fetch of their own. They win over the same
//   medium's document from the space's listing, and open the lightbox in place.
//
// A blocked medium (a moderator's measure) becomes no document: what it shows is the operator's placeholder, not its own.
import { unsealNodeMeta } from '../../core/node/nodeMeta'
import type { ApplicationSearch, KindSearch, ScopeDocumentsAnswer, ScopeDocumentsContext, SearchFacetContext } from '../../core/applications/registry'
import type { ContributedDocument, MonthSpan, ScopeDocument, SearchFacets, YearSpan } from '../../core/search/model'
import { isHttpStatus, HttpStatus } from '../../shared/httpError'
import { withRefresh } from '../../app/sessionRefresh'
import { i18n } from '../../i18n'
import type { ScopeMediaPage, ScopeMedium } from '../../api/generated/media'
import { mediaApi } from './api'
import { normalizeDate, type MemoryDate } from './memoryDate'
import { normalizeLocation } from './memoryLocation'
import { normalizeParticipants } from './memoryParticipants'
import { effectiveCapturedAt, isVideoMediaType, mediaTypeOf, type MediaItem } from './media'
import { uploaderOriginOf } from './mediaKey'
import { closeListing, openedBlobMeta, openedMeta, openListing } from './openedListing'
import { isTrackMeta } from './track/gpx'

/** The kind token of a photo's and a video's document. */
export const PHOTO_DOCUMENT = 'memories.photo'
export const VIDEO_DOCUMENT = 'memories.video'

/** How many media one page of a space's listing carries: the contract's cap. */
export const SCOPE_MEDIA_PAGE = 1000

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

/** The seals a medium's document is read from: a meta edit or a replaced original is a new document. */
const revisionOf = (item: MediaItem) => `${item.metaRevision ?? 0}:${item.original.blobId}`

/** One medium's document as Memories knows it, from the listing opened under `key` (the memo holds it once opened). */
function mediumDocument(scopeId: string, key: Uint8Array, item: MediaItem, nodeId: string, unnamed: (video: boolean) => string): ScopeDocument {
  const meta = openedMeta(scopeId, key, item)
  const blob = openedBlobMeta(scopeId, key, item)
  const video = isVideoMediaType(mediaTypeOf(blob))
  const capturedAt = effectiveCapturedAt(meta)
  const place = meta?.placeName?.trim()
  return {
    kind: video ? VIDEO_DOCUMENT : PHOTO_DOCUMENT,
    id: item.mediaId,
    nodeId,
    revision: revisionOf(item),
    name: blob?.name?.trim() || unnamed(video),
    facets: { ...(capturedAt !== undefined ? { capturedAt } : {}), ...(place ? { places: [place] } : {}) },
    icon: video ? 'play' : 'image',
  }
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

/** Whether a medium is a GPX track (ADR 0130 decision 7): no photo, so no photo document - it is on its Memory's Map tab. */
const isTrack = (scopeId: string, key: Uint8Array, item: MediaItem) => isTrackMeta(openedBlobMeta(scopeId, key, item))

/** One document per medium of the open Memory but a blocked one or a track (see the header). */
export function mediaDocuments(input: MediaDocumentsInput): ContributedDocument[] {
  return input.items
    .filter((item) => !item.blocked && !isTrack(input.scopeId, input.key, item))
    .map((item) => {
      const { nodeId: _node, ...doc } = mediumDocument(input.scopeId, input.key, item, '', input.unnamed)
      return { ...doc, open: () => input.open(item.mediaId) }
    })
}

/** A row of a space's listing as the gallery's item: its original's ref, its listed tier, its uploader (ADR 0031 point 7). */
export function scopeMediumItem(row: ScopeMedium, page: Pick<ScopeMediaPage, 'nodes'>): MediaItem {
  const node = page.nodes.find((n) => n.nodeId === row.nodeId)
  const wrapped = new Map((node?.uploaderKeys ?? []).map((k) => [k.identityId, k.wrappedIdentityKey]))
  return {
    mediaId: row.mediaId,
    original: {
      blobId: row.blobId, chunkCount: row.chunkCount, byteSize: row.byteSize, wrappedBlobKey: row.wrappedBlobKey,
      wrappedBlobMeta: row.wrappedBlobMeta ?? undefined, ownerId: row.mediaId, role: 'ORIGINAL',
    },
    derivatives: [],
    wrappedMeta: row.wrappedMeta ?? undefined,
    metaRevision: row.metaRevision,
    ownerKey: row.ownerKey ?? undefined,
    uploader: uploaderOriginOf(row.ownerKey, row.nodeId, node?.sealedDropPrivateKey, wrapped),
    ...(row.blockedByOperator ? { blocked: true } : {}),
  }
}

/** The name of a medium whose original carries none, in the UI's language. */
const unnamedWord = (video: boolean) => i18n.global.t(video ? 'memory.search.video' : 'memory.search.photo')

/**
 * Every photo and video of one space (see the header; `ApplicationSearch.scopeDocuments`). The listing is read by the tag
 * of the last answer (`304`: null, nothing opened, no key asked for); otherwise page by page, a medium at an unchanged
 * revision kept as it was, every other one opened under its node's key - each node's listing opened in the Worker and
 * closed again before this returns, so neither the Worker nor the memo keeps a key past the walk.
 */
export async function scopeMediaDocuments(ctx: ScopeDocumentsContext): Promise<ScopeDocumentsAnswer | null> {
  let first
  try {
    first = await withRefresh(() =>
      mediaApi().listScopeMediaRaw({ scopeId: ctx.scopeId, limit: SCOPE_MEDIA_PAGE, ifNoneMatch: ctx.tag ?? undefined }, { signal: ctx.signal }),
    )
  } catch (e) {
    if (ctx.tag && isHttpStatus(e, HttpStatus.notModified)) return null
    throw e
  }
  const tag = first.raw.headers.get('ETag')
  const documents: ScopeDocument[] = []
  const used = new Set<Uint8Array>()
  let keyOf: ((nodeId: string) => Uint8Array | null) | null = null
  try {
    let page = await first.value()
    for (;;) {
      // A blocked medium is left here, before anything of it is opened or kept (see the header).
      const items = page.media.filter((row) => !row.blockedByOperator).map((row) => ({ row, item: scopeMediumItem(row, page) }))
      // What must be opened: a medium whose document is not the one already held.
      const todo = new Map<string, MediaItem[]>()
      for (const { row, item } of items) {
        const before = ctx.previous.get(item.mediaId)
        if (before && before.revision === revisionOf(item) && before.nodeId === row.nodeId) continue
        todo.set(row.nodeId, [...(todo.get(row.nodeId) ?? []), item])
      }
      if (todo.size) keyOf ??= await ctx.keys()
      const keys = new Map<string, Uint8Array | null>()
      for (const [nodeId, nodeItems] of todo) {
        const key = keyOf!(nodeId)
        keys.set(nodeId, key)
        if (!key) continue // a node whose chain does not open: its media are not searched
        used.add(key)
        await openListing(ctx.scopeId, key, nodeItems)
      }
      ctx.signal.throwIfAborted()
      documents.push(...(await ctx.slices(pageDocuments(ctx, items, keys))))
      const last = page.media.at(-1)?.mediaId
      if (!page.more || !last) break
      page = await withRefresh(() => mediaApi().listScopeMedia({ scopeId: ctx.scopeId, after: last, limit: SCOPE_MEDIA_PAGE }, { signal: ctx.signal }))
    }
  } finally {
    for (const key of used) closeListing(ctx.scopeId, key)
  }
  return { tag, documents }
}

/** One page's documents, one medium per step: a held document at its revision, else the one opened under its node's key. */
function* pageDocuments(
  ctx: ScopeDocumentsContext, items: readonly { row: ScopeMedium; item: MediaItem }[], keys: ReadonlyMap<string, Uint8Array | null>,
): Generator<void, ScopeDocument[], void> {
  const out: ScopeDocument[] = []
  for (const { row, item } of items) {
    const before = ctx.previous.get(item.mediaId)
    const key = keys.get(row.nodeId)
    if (before && before.revision === revisionOf(item) && before.nodeId === row.nodeId) out.push(before)
    else if (key && !isTrack(ctx.scopeId, key, item)) out.push(mediumDocument(ctx.scopeId, key, item, row.nodeId, unnamedWord))
    yield
  }
  return out
}

/** Memories' part in the search of every space (`app.search`). */
export const memoriesSearch: ApplicationSearch = { scopeDocuments: scopeMediaDocuments }
