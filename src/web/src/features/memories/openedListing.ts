// The opened listing (ADR 0105 decision 3, inbox #489): every listed seal of an open Memory — each media's listed tier
// (capture time, geometry, GPS, place) and its original's blob meta (type, name) — opened ONCE per seal and kept, so a
// sort, a filter, a resize, the trash, the organizer, the auto date and place, the lightbox and the export all read the
// same opened value instead of opening it again on the main thread (the gallery used to re-open every meta on every
// recompute: 14 call sites, no memo).
//
// Kept per (scope, the node key's ARRAY): a Memory's chapter has a key of its own and so a listing of its own, and a
// listing never outlives the key array that opened it (a WeakMap, as `mediaKey`'s K_up cache). An entry is valid for the
// exact seal it opened — the listed tier by its `wrappedMeta`, the blob meta by the original's blob id and
// `wrappedBlobMeta` — so a meta edit (a new `wrappedMeta` at a new `metaRevision`) is opened anew, and nothing else is.
// The seal, not `metaRevision`, is the stamp: a share guest's rows carry no revision, and the seal is what is opened.
//
// [openListing] opens a batch in the listing opener Worker (`listingOpener.ts`), off the page's main thread; a value
// asked for before its batch arrived is opened on the page, once, and kept — correct either way, only slower. The
// Worker holds the Memory's keys from the first batch to [closeListing], which the gallery calls when it leaves the node.
import { mediaKey, mediaKeyId } from './mediaKey'
import type { OpenedBlobMeta } from '../../core/blob/meta'
import type { FrameOpener } from '../../core/blob/transport'
import { closeMemory, newMemoryHandle, openFramesFor, openItems, openMemory } from './listingOpener'
import type { OpenItem, OpenedItem } from './listingOpenerCore'
import { originalBlobMeta, unsealMeta, type MediaItem, type MediaMeta } from './media'

/** How many media one Worker message carries: big enough that 10,000 media are ten messages, small enough that the
 *  first batch's answer does not wait behind the last one's opens. */
export const OPEN_BATCH = 1000

type Entry = {
  meta?: { seal: string | undefined; value: MediaMeta | null }
  blob?: { seal: string; value: OpenedBlobMeta | null }
}

type Listing = {
  scopeId: string
  key: Uint8Array
  handle: number
  entries: Map<string, Entry>
  /** The keys the Worker holds for this Memory, by [mediaKeyId]; undefined until the Memory is opened there. */
  sent: Set<string> | undefined
  /** Batches on their way, per media id — a second open of the same media waits for the first. */
  inflight: Map<string, Promise<void>>
  closed: boolean
}

const listings = new WeakMap<Uint8Array, Map<string, Listing>>()

function listingOf(scopeId: string, key: Uint8Array): Listing {
  let byScope = listings.get(key)
  if (!byScope) listings.set(key, (byScope = new Map()))
  let listing = byScope.get(scopeId)
  if (!listing) {
    listing = { scopeId, key, handle: newMemoryHandle(), entries: new Map(), sent: undefined, inflight: new Map(), closed: false }
    byScope.set(scopeId, listing)
  }
  return listing
}

const blobSeal = (item: MediaItem) => `${item.original.blobId}|${item.original.wrappedBlobMeta ?? ''}`

function entryOf(listing: Listing, mediaId: string): Entry {
  let entry = listing.entries.get(mediaId)
  if (!entry) listing.entries.set(mediaId, (entry = {}))
  return entry
}

const isOpened = (listing: Listing, item: MediaItem) => {
  const e = listing.entries.get(item.mediaId)
  return metaOpened(e, item) && blobOpened(e, item)
}

/** The key `item`'s seals are under, or null when its chain does not open (an uploader row moved or tampered). */
function keyFor(listing: Listing, item: MediaItem): Uint8Array | null {
  try {
    return mediaKey(listing.scopeId, listing.key, item)
  } catch {
    return null
  }
}

/** Open one media's listed tier on the page thread — the memo's fall-back, and the Worker's twin: `unsealMeta` opens
 *  through the same `mediaOpen.ts` the Worker runs. */
const pageMeta = (listing: Listing, item: MediaItem): MediaMeta | null => unsealMeta(listing.scopeId, listing.key, item)

/** The original's blob meta on the page thread (`originalBlobMeta`: `core/blob/keys.ts`, as the Worker). */
const pageBlobMeta = (listing: Listing, item: MediaItem): OpenedBlobMeta | null => originalBlobMeta(listing.scopeId, listing.key, item)

/** `item`'s listed tier, opened once per seal (see the header): the memo's value, else opened now on the page and kept. */
export function openedMeta(scopeId: string, key: Uint8Array, item: MediaItem): MediaMeta | null {
  const listing = listingOf(scopeId, key)
  const entry = entryOf(listing, item.mediaId)
  if (entry.meta && entry.meta.seal === item.wrappedMeta) return entry.meta.value
  const value = pageMeta(listing, item)
  entry.meta = { seal: item.wrappedMeta, value }
  return value
}

/** `item`'s original's blob meta (its type, the name it was uploaded under), opened once per seal as [openedMeta]. */
export function openedBlobMeta(scopeId: string, key: Uint8Array, item: MediaItem): OpenedBlobMeta | null {
  const listing = listingOf(scopeId, key)
  const entry = entryOf(listing, item.mediaId)
  const seal = blobSeal(item)
  if (entry.blob && entry.blob.seal === seal) return entry.blob.value
  const value = pageBlobMeta(listing, item)
  entry.blob = { seal, value }
  return value
}

/** Make sure the Worker holds the Memory and the keys `items` need; returns the key id per media (null: no key opens). */
function sendKeys(listing: Listing, items: readonly MediaItem[]): Map<string, string | null> {
  const ids = new Map<string, string | null>()
  const fresh: [string, Uint8Array][] = []
  const sent = (listing.sent ??= new Set())
  for (const item of items) {
    const id = mediaKeyId(listing.scopeId, item)
    if (!sent.has(id)) {
      const key = keyFor(listing, item)
      if (!key) {
        ids.set(item.mediaId, null)
        continue
      }
      sent.add(id)
      fresh.push([id, key])
    }
    ids.set(item.mediaId, id)
  }
  if (fresh.length || sent.size === 0) openMemory(listing.handle, fresh)
  return ids
}

/**
 * Open every seal of `items` not yet opened — in the listing opener Worker where there is one, in batches of
 * [OPEN_BATCH] — and keep the values; resolves once every one of them is in the memo (so the caller's order is final).
 * A media whose batch is already on its way is waited for, not sent again.
 */
export async function openListing(scopeId: string, key: Uint8Array, items: readonly MediaItem[]): Promise<void> {
  const listing = listingOf(scopeId, key)
  const waits: Promise<void>[] = []
  const todo: MediaItem[] = []
  for (const item of items) {
    const busy = listing.inflight.get(item.mediaId)
    if (busy) waits.push(busy)
    else if (!isOpened(listing, item)) todo.push(item)
  }
  for (let i = 0; i < todo.length; i += OPEN_BATCH) {
    const batch = todo.slice(i, i + OPEN_BATCH)
    const done = openBatch(listing, batch)
    for (const item of batch) listing.inflight.set(item.mediaId, done)
    waits.push(done)
  }
  await Promise.all(waits)
}

const metaOpened = (entry: Entry | undefined, item: MediaItem) => !!entry?.meta && entry.meta.seal === item.wrappedMeta
const blobOpened = (entry: Entry | undefined, item: MediaItem) => !!entry?.blob && entry.blob.seal === blobSeal(item)

async function openBatch(listing: Listing, batch: MediaItem[]): Promise<void> {
  try {
    const keyIds = sendKeys(listing, batch)
    const requests: OpenItem[] = []
    const sealOf = new Map<string, MediaItem>()
    for (const item of batch) {
      const keyId = keyIds.get(item.mediaId)
      const entry = listing.entries.get(item.mediaId)
      if (!keyId) {
        // no key opens this row: nothing of it opens, on any thread
        const e = entryOf(listing, item.mediaId)
        if (!metaOpened(e, item)) e.meta = { seal: item.wrappedMeta, value: null }
        if (!blobOpened(e, item)) e.blob = { seal: blobSeal(item), value: null }
        continue
      }
      sealOf.set(item.mediaId, item)
      // Only what the memo lacks: a value opened on the page before this batch is never opened again.
      const o = item.original
      requests.push({
        mediaId: item.mediaId,
        keyId,
        openMeta: !metaOpened(entry, item),
        wrappedMeta: metaOpened(entry, item) ? undefined : item.wrappedMeta,
        original: blobOpened(entry, item)
          ? undefined
          : { blobId: o.blobId, ownerId: o.ownerId, role: o.role, wrappedBlobKey: o.wrappedBlobKey, wrappedBlobMeta: o.wrappedBlobMeta ?? undefined },
      })
    }
    if (!requests.length) return
    const onPage = (): OpenedItem[] =>
      requests.map((r) => {
        const item = sealOf.get(r.mediaId)!
        const out: OpenedItem = { mediaId: r.mediaId }
        if (r.openMeta) out.meta = pageMeta(listing, item)
        if (r.original) out.blobMeta = pageBlobMeta(listing, item)
        return out
      })
    let opened: OpenedItem[]
    try {
      opened = await openItems(listing.handle, listing.scopeId, requests, onPage)
    } catch {
      return // refused: the listing was closed while the batch was on its way — nobody reads it any more
    }
    for (const o of opened) {
      const item = sealOf.get(o.mediaId)
      if (!item) continue
      // Stamped with the seal that was SENT: a seal edited meanwhile misses and is opened anew. A value the page opened
      // while the batch was away is the same value, and stays.
      const entry = entryOf(listing, o.mediaId)
      if (o.meta !== undefined && !metaOpened(entry, item)) entry.meta = { seal: item.wrappedMeta, value: o.meta }
      if (o.blobMeta !== undefined && !blobOpened(entry, item)) entry.blob = { seal: blobSeal(item), value: o.blobMeta }
    }
  } finally {
    for (const item of batch) listing.inflight.delete(item.mediaId)
  }
}

/**
 * Where a grid's thumbnail frames of this Memory are opened: the listing opener Worker, under the `K_blob` the page
 * unwrapped (`core/blob/transport.ts` `openBlobVia`). A frame read that arrives after [closeListing] is refused there and
 * rejects with an `AbortError` — the gallery that asked has left.
 */
export function gridFrameOpener(scopeId: string, key: Uint8Array): FrameOpener {
  const listing = listingOf(scopeId, key)
  if (!listing.sent) sendKeys(listing, [])
  return (blobKey, frames) =>
    listing.closed ? Promise.reject(new DOMException('The Memory was closed', 'AbortError')) : openFramesFor(listing.handle, blobKey, frames)
}

/** Leave a Memory: the Worker forgets its keys and the memo its values. A later read of the same key starts afresh. */
export function closeListing(scopeId: string, key: Uint8Array): void {
  const byScope = listings.get(key)
  const listing = byScope?.get(scopeId)
  if (!listing) return
  listing.closed = true
  byScope!.delete(scopeId)
  if (listing.sent) closeMemory(listing.handle)
}
