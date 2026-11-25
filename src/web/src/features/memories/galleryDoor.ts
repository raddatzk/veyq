// The gallery model's door on the web (ADR 0155 decision 8, § The gallery model's move): what `GalleryModel` (Memories'
// Kotlin model, `@veyq/core-js/memories`) reads and writes, written over the session's data source (`./source.ts`) — a
// member's or a link guest's. The model decides what the page lists and when it reads anew; the door is only I/O: the
// listing, opened in the listing Worker (`openedListing.ts`) and held here, the live poll's conditional read applied to it
// (core's `reconcileItems`, the server's keyset delta: a change is never a full re-list), and the writes. No key crosses
// into the model: it is handed the opened facts it orders and splits by, the web reads every byte itself.
import type { GalleryDoorShape, GalleryItemShape } from '@veyq/core-js/memories'
import { effectiveCapturedAt, type MediaItem } from './media'
import { openListing, openedBlobMeta, openedMeta } from './openedListing'
import type { MemorySource } from './source'
import { mediaRefusalOf } from './mediaRefusal'
import { reconcileItems } from '../../core/items/itemsLive'
import { noteScopeGone } from '../../core/node/treeStore'

export interface GalleryDoorCtx {
  source: MemorySource
  scopeId: string
  nodeId: string
  key: Uint8Array
  /** A medium the live poll found blocked (or no longer listed): its decrypted thumbnail goes. */
  onWithdrawn?: (mediaId: string) => void
  /** The settle (the `auto` date and place), at the model's settle points; whether it wrote. */
  settle: () => Promise<void>
}

/** The door, and what it holds: the listed media by id, for the page to draw the model's rows with. */
export interface GalleryDoor extends GalleryDoorShape {
  /** The media as last listed, by id (the same objects the lightbox's snapshot holds). */
  readonly byId: ReadonlyMap<string, MediaItem>
  /** The media as last listed, in the listing's order (the server's, merged by the live poll). */
  readonly listed: readonly MediaItem[]
}

const finite = (n: unknown): number | null => (typeof n === 'number' && Number.isFinite(n) ? n : null)

export function galleryDoor(ctx: GalleryDoorCtx): GalleryDoor {
  const { source, scopeId, nodeId, key } = ctx
  let held: MediaItem[] | null = null
  let byId = new Map<string, MediaItem>()
  // the live poll's cursor (uc.memory.live-updates, cr-0107): the tag echoed as If-None-Match (null: no live poll here),
  // the delete revision, and the keyset cursor the server answered with the last page it read (ADR 0033 decision 6)
  let tag: string | null = null
  let deleteRevision = 0
  let since: string | undefined
  // a change the live poll just applied: the model's read that follows it takes the listing as held, no request
  let fresh = false

  function hold(items: MediaItem[]) {
    held = items
    byId = new Map(items.map((m) => [m.mediaId, m] as const))
  }

  /** The model's item: the opened facts it orders and splits by; no key, no seal. */
  function shape(item: MediaItem): GalleryItemShape {
    const meta = openedMeta(scopeId, key, item)
    const blob = openedBlobMeta(scopeId, key, item)
    return {
      mediaId: item.mediaId,
      type: typeof blob?.type === 'string' ? blob.type : null,
      name: typeof blob?.name === 'string' ? blob.name : null,
      blocked: !!item.blocked,
      ownerKey: item.ownerKey ?? null,
      width: finite(meta?.w),
      height: finite(meta?.h),
      capturedAt: finite(meta?.capturedAt),
      capturedAtOverride: finite(meta?.capturedAtOverride),
    }
  }

  /** One conditional read from the held cursor, applied to the held listing; whether anything changed. */
  async function poll(): Promise<boolean> {
    if (!source.poll || tag === null || !held) return false
    let page: Awaited<ReturnType<NonNullable<MemorySource['poll']>>>
    try {
      page = await source.poll(nodeId, { tag, since, deleteRevision })
    } catch (e) {
      await noteScopeGone(scopeId, e) // removed from the group: core's page says so and unmounts this (inbox #546)
      throw e
    }
    if (page.notModified) return false
    // a delta's seals are opened before it is merged, so an inserted tile takes its final place at once
    await openListing(scopeId, key, page.items)
    hold(reconcileItems(held, page.items, page.mediaIds, {
      idOf: (m) => m.mediaId,
      patch: (existing, m) => {
        existing.wrappedMeta = m.wrappedMeta // a meta edit re-sealed the tier; patched in place (shared with the lightbox)
        existing.metaRevision = m.metaRevision // the base of the next meta write on this item (ADR 0077)
        existing.updatedAt = m.updatedAt
        // a block or its lift (ADR 0093 § 6) bumps the item's revision: the tile shows it at once, its thumbnail goes
        if (m.blocked && !existing.blocked) {
          existing.blocked = true
          ctx.onWithdrawn?.(m.mediaId)
        } else if (!m.blocked && existing.blocked) delete existing.blocked
      },
      onDrop: (id) => ctx.onWithdrawn?.(id),
    }))
    tag = page.tag
    deleteRevision = page.deleteRevision
    since = page.cursor ?? since // a delta that carried no row (a removal only) leaves the cursor where it was
    return true
  }

  return {
    get byId() {
      return byId
    },
    get listed() {
      return held ?? []
    },
    async gallery() {
      if (fresh && held) fresh = false
      // read anew where the page already holds a listing: the conditional read, a 304 the listing as held
      else if (held && source.poll && tag !== null) await poll()
      else {
        const listing = await source.listMedia(nodeId) // every page of it
        // every listed seal opened before the model orders them: the order is the sealed capture time (ADR 0105 decision 4)
        await openListing(scopeId, key, listing.items)
        hold(listing.items)
        // the live poll continues from the FIRST page's revisions and the LAST page's cursor (ADR 0033 decision 6)
        tag = listing.revision?.tag ?? null
        deleteRevision = listing.revision?.delete ?? 0
        since = listing.cursor ?? undefined
      }
      return held!.map(shape)
    },
    async moved() {
      const changed = await poll()
      if (changed) fresh = true
      return changed
    },
    async trash(mediaId) {
      const del = source.deleteMedia
      if (del?.reach !== 'any') return 'failed'
      try {
        await del.trash(mediaId)
        return 'done'
      } catch (e) {
        return removalOf(await mediaRefusalOf(e))
      }
    },
    async restore(mediaId) {
      const del = source.deleteMedia
      if (del?.reach !== 'any') return 'failed'
      try {
        await del.restore(mediaId)
        return 'done'
      } catch (e) {
        return removalOf(await mediaRefusalOf(e))
      }
    },
    isOwn(mediaId) {
      const del = source.deleteMedia
      return del?.reach === 'own' && del.isOwn(mediaId)
    },
    async purge(mediaId) {
      const del = source.deleteMedia
      if (del?.reach !== 'own') return false
      try {
        await del.purge(mediaId)
        return true
      } catch {
        return false
      }
    },
    async settle() {
      await ctx.settle()
      return false // the page's own facts are its own (the settle patches them in place); the model reads none
    },
  }
}

function removalOf(r: Awaited<ReturnType<typeof mediaRefusalOf>>): string {
  return r === 'blocked_by_operator' ? 'blocked' : r
}

/** The effective capture time the model orders by, as the web reads it (for a caller placing an in-flight tile among the rows). */
export function capturedAtOf(scopeId: string, key: Uint8Array | null, item: MediaItem): number | undefined {
  return key ? effectiveCapturedAt(openedMeta(scopeId, key, item)) : undefined
}
