// uc.memory.gallery (web) — the live-gallery core of MemoryDetail, extracted into a composable (cr-0151, audit
// #77/P4a). Owns the listed media, the thumbnail pipeline (cr-0150) + optimistic tiles (cr-0074), the cr-0107
// live poll (load/merge/upsert), the justified layout, and the optimistic-delete/undo. MemoryDetail keeps only
// orchestration + the template. Operator-blind: thumbnails decrypt on device under K_node; nothing plaintext crosses.
import { ref, computed, watch, onBeforeUnmount } from 'vue'
import { useI18n } from 'vue-i18n'
import { useToast } from '../../shared/state/useToast'
import { useUndo } from '../../shared/state/useUndo'
import type { TreeNode } from '../../core/node/tree'
import { hasDerivatives, unsealMeta, effectiveCapturedAt, newestCursor, type MediaItem } from './media'
import type { MemorySource } from './source'
import { mediaRefusalOf, sealedMediaName } from './mediaRefusal'
import { startMediaRevisionPolling, stopMediaRevisionPolling } from './mediaRevisionPolling'
import { itemsPollTick, reconcileItems } from '../../core/items/itemsLive'
import { galleryDisplayAspectRatio } from './mediaGridLayout'
import { useThumbnailPipeline } from './useThumbnailPipeline'
import { useOptimisticTiles, type OptimisticTileVM } from './useOptimisticTiles'
import { byCaptureTime } from './optimisticUpload'

/** The reactive context MemoryDetail hands the gallery core — getters so props stay reactive, plus the reconcile
 *  settle callback (auto-date + auto-location self-heal) run after every list refresh. */
export interface MediaGalleryLiveCtx {
  /** The session's data source (`./source.ts`): the list, the tiles' bytes, the live poll and the delete — each only
   *  where the source carries it (a guest's has no poll and no trash). */
  source: MemorySource
  scopeId: () => string
  node: () => TreeNode
  memoryKey: () => Uint8Array | null
  onSettle: () => Promise<void>
  /**
   * The item-favorite filter (ADR 0068), or null when the gallery shows everything. It is applied to
   * `sortedItems` — the one list the grid, each tile's `index` and `openLightboxAt`'s snapshot all derive from
   * — so filtering narrows the lightbox with the grid. Filtering only the grid would let prev/next page
   * straight out of the selection the user just made.
   */
  markFilter?: () => ((mediaId: string) => boolean) | null
}

/** A grid tile handed to [MediaGrid]: an in-flight optimistic upload or a listed media. */
export type MemoryTileItem =
  | { id: string; aspectRatio: number; kind: 'optimistic'; optimistic: OptimisticTileVM }
  | { id: string; aspectRatio: number; kind: 'media'; media: MediaItem; index: number }

export function useMediaGalleryLive(ctx: MediaGalleryLiveCtx) {
  const { t } = useI18n()
  const toast = useToast()
  const items = ref<MediaItem[]>([])
  // Optimistic in-grid upload tiles (uc.memory.upload, cr-0074): the shared queue's still-in-flight files for THIS
  // node (grouping key = the node id) as instant preview tiles, reconciled into the real thumbnail by mediaId.
  // `donorPreviewFor` feeds the thumbnail pipeline the preview to hand off.
  const listedMediaIds = computed(() => new Set(items.value.map((it) => it.mediaId)))
  const { optimisticTiles, donorPreviewFor } = useOptimisticTiles(computed(() => ctx.source.batchKey(ctx.node().id)), listedMediaIds)
  // The gallery thumbnail pipeline (cr-0150) — decrypt-per-tile → object-URL map, the single revoke owner of every
  // thumbnail (incl. a donated optimistic preview). Decrypt = the grid tier under K_node (operator-blind).
  const pipeline = useThumbnailPipeline<MediaItem>({
    idOf: (m) => m.mediaId,
    isDerivable: hasDerivatives,
    decryptTile: (m, signal) => {
      const key = ctx.memoryKey()
      return key ? ctx.source.loaders.grid(m, key, signal) : Promise.resolve(null)
    },
    donorPreviewFor,
  })
  const thumbs = pipeline.thumbs // mediaId → thumbnail object URL (derivable items only)
  const thumbFailed = pipeline.failed // mediaIds whose thumbnail read failed for good: the tile's error state
  const status = ref<'loading' | 'ready' | 'error'>('loading')
  // live media updates (uc.memory.live-updates, cr-0107): the cached per-node counters — mediaRevision (the ETag
  // echoed as If-None-Match; null until the first load) + deleteRevision (echoed as sinceDelete to gate the id-set).
  const mediaRevision = ref<number | null>(null)
  const deleteRevision = ref(0)
  const lightboxIndex = ref<number | null>(null)
  const lightboxItems = ref<MediaItem[]>([]) // a snapshot of the sorted order at open time (so an in-lightbox edit can't reorder underfoot)
  // trash undo (cr-0110): a lightbox-only delete soft-deletes into a per-Memory trash; an undo-toast offers restore
  // (cr-0157/ADR 0039). The soft-delete is eager, so the window elapsing commits nothing — it just lets the trash
  // stand; only `onUndoDelete` (restore) is a real action, hence no `onExpire`.
  const undo = useUndo<{ mediaId: string; name: string }>({ timeoutMs: 6000 })
  // Tombstone of mediaIds optimistically removed by a trash but not yet confirmed gone (cr-0111): filtered out of
  // the merge/poll paths so a stale live read (a poll firing before the delete commits) can't re-add them.
  const pendingDelete = new Set<string>()
  // date sort (cr-0066): tiles order by effective capturedAt; newest first by default.
  const sortDir = ref<'newest' | 'oldest'>('newest')

  // The effective capture time of an item (from its sealed metadata tier), or undefined when it has none.
  function capturedAtOf(item: MediaItem): number | undefined {
    const key = ctx.memoryKey()
    return key ? effectiveCapturedAt(unsealMeta(ctx.scopeId(), key, item)) : undefined
  }
  const sortDirection = computed<1 | -1>(() => (sortDir.value === 'newest' ? -1 : 1))
  // The LISTED media in grid order — the lightbox's world: the `index` below and `openLightboxAt`'s snapshot are
  // both positions in THIS list, never in the merged one, so an in-flight tile can never shift which photo a
  // click opens. The merged list sorts on the same key, so the media order inside it is identical to this.
  const sortedItems = computed(() => {
    const keep = ctx.markFilter?.() ?? null
    const listed = keep ? items.value.filter((it) => keep(it.mediaId)) : items.value
    return byCaptureTime(listed, capturedAtOf, sortDirection.value)
  })

  const aspectRatios = computed(() => {
    const key = ctx.memoryKey()
    const m = new Map<string, number>()
    for (const item of items.value) {
      const dims = key ? unsealMeta(ctx.scopeId(), key, item) : null
      m.set(item.mediaId, galleryDisplayAspectRatio(dims?.w, dims?.h))
    }
    return m
  })
  const FALLBACK_ASPECT = galleryDisplayAspectRatio(undefined, undefined)
  // The tile list handed to [MediaGrid] — ONE order over both kinds of tile (uc.memory.gallery § "One order for
  // every tile"), each carrying its content so the grid slot renders it without a lookup. An in-flight upload
  // sorts on the capture time it will be sealed with, so it appears where it will stay: settling changes a
  // tile's content, never its place. Optimistic tiles are passed first so an equal key resolves in their
  // favour — that is how a settling tile hands its own slot to the media it becomes.
  const layoutItems = computed<MemoryTileItem[]>(() =>
    byCaptureTime<MemoryTileItem>(
      [
        // An in-flight upload carries no marks yet, so it is not part of a filtered view — showing it would
        // put an unmarked tile inside "only mine". Unfiltered, it keeps its capture-time slot as always.
        ...(ctx.markFilter?.()
          ? []
          : optimisticTiles.value.map((t) => ({ id: t.layoutId, aspectRatio: t.aspectRatio, kind: 'optimistic' as const, optimistic: t }))),
        ...sortedItems.value.map((it, index) => ({
          id: it.mediaId,
          aspectRatio: aspectRatios.value.get(it.mediaId) ?? FALLBACK_ASPECT,
          kind: 'media' as const,
          media: it,
          index,
        })),
      ],
      (tile) => (tile.kind === 'optimistic' ? tile.optimistic.capturedAt : capturedAtOf(tile.media)),
      sortDirection.value,
    ),
  )
  // Whether the gallery has anything to show — real media or in-flight optimistic uploads.
  const hasContent = computed(() => items.value.length > 0 || optimisticTiles.value.length > 0)

  // --- optimistic delete / undo (uc.memory.delete-media, cr-0110/0111) ---

  /** Trash the current lightbox photo: optimistic grid/lightbox removal, the server soft-delete, and a transient
   *  undo-toast. The live poll would also drop it; the optimistic removal is instant. */
  async function onDeleteFromLightbox(payload: { mediaId: string }) {
    const del = ctx.source.deleteMedia
    if (!ctx.memoryKey() || del?.reach !== 'any') return // only a trash has an undo; a guest's purge is asked first
    const name = mediaNameOf(payload.mediaId) // before the removal: the Undo names a refused restore by it (inbox #429)
    removeFromGrid(payload.mediaId)
    undo.arm({ mediaId: payload.mediaId, name })
    toast.showUndo({ summary: t('memory.trash.movedToTrash'), onUndo: onUndoDelete, life: 6000 })
    try {
      await del.trash(payload.mediaId)
    } catch {
      undo.cancel() // the trash failed → drop the stale undo-toast, re-list to restore the true state
      toast.dismissUndo()
      void load()
    }
  }

  /** Drop a media from the open grid + lightbox snapshot (optimistic), revoking its thumbnail object URL. */
  function removeFromGrid(mediaId: string) {
    pendingDelete.add(mediaId) // cr-0111: tombstone so a racing live read can't re-add it before the delete commits
    pipeline.dropThumb(mediaId)
    items.value = items.value.filter((it) => it.mediaId !== mediaId)
    lightboxItems.value = lightboxItems.value.filter((it) => it.mediaId !== mediaId)
  }

  /** A medium's name for a refusal: the file name sealed in its original's blob meta, else its place in the grid the
   *  lightbox was opened on (the organizer's wording, `memory.buckets.unnamedMedia`). */
  function mediaNameOf(mediaId: string): string {
    const opened = lightboxItems.value.findIndex((it) => it.mediaId === mediaId)
    const list = opened >= 0 ? lightboxItems.value : sortedItems.value
    const at = list.findIndex((it) => it.mediaId === mediaId)
    const item = at >= 0 ? list[at] : items.value.find((it) => it.mediaId === mediaId)
    return sealedMediaName(ctx.scopeId(), ctx.memoryKey(), item) || t('memory.buckets.unnamedMedia', { n: at >= 0 ? at + 1 : 1 })
  }

  /** Undo the just-trashed media: restore it server-side, then re-list so it re-appears in the grid. A refused restore
   *  is named with its reason, as the organizer's Undo names one (inbox #429); a restore that went says nothing. */
  async function onUndoDelete() {
    const u = undo.cancel()
    const del = ctx.source.deleteMedia
    if (!u || del?.reach !== 'any') return
    pendingDelete.delete(u.mediaId) // cr-0111: a genuine restore lifts the tombstone so the media can re-list
    try {
      await del.restore(u.mediaId)
    } catch (e) {
      const reason = t(`memory.buckets.deleteRefusedReason.${await mediaRefusalOf(e)}`)
      toast.error(t('memory.buckets.restoreRefused', { names: u.name, reason }))
    } finally {
      void load()
    }
  }

  /** Lift the gallery tombstone for a media (a trash restore/purge from the Trash tab, via useMediaTrash). */
  const liftTombstone = (mediaId: string) => pendingDelete.delete(mediaId)

  /** Open the lightbox on a grid tile, snapshotting the current sorted order so an in-lightbox edit can't reorder it. */
  function openLightboxAt(index: number) {
    lightboxItems.value = sortedItems.value.slice()
    lightboxIndex.value = index
  }
  /** Open the lightbox on a bucket-scoped list the organizer hands up (so prev/next stays within the picked bucket). */
  function openBucketLightbox(payload: { items: MediaItem[]; index: number }) {
    lightboxItems.value = payload.items.slice()
    lightboxIndex.value = payload.index
  }
  /** A date correction from the lightbox → patch the item's sealed tier so the grid re-sorts (no refetch). The item
   *  object is shared with the open lightbox's snapshot, so the reopened detail stays consistent too. */
  function onCapturedAtUpdated(payload: { mediaId: string; wrappedMeta: string | null; metaRevision: number }) {
    const item = items.value.find((it) => it.mediaId === payload.mediaId)
    if (!item) return
    item.wrappedMeta = payload.wrappedMeta ?? undefined
    item.metaRevision = payload.metaRevision // the base of the next meta write on this item (ADR 0077)
    items.value = [...items.value] // reassign so sortedItems recomputes and the grid re-sorts
  }

  /** Merge a fresh media list into `items` WITHOUT an empty-flash: the pipeline revokes only dropped thumbnails and
   *  donates a done upload's preview for each newly-listed media (its optimistic tile hands off with no gray gap,
   *  the real thumbnail replacing the donation once it decrypts); this keeps the `pendingDelete` tombstone. */
  function mergeItems(listed: MediaItem[]) {
    for (const id of [...pendingDelete]) if (!listed.some((m) => m.mediaId === id)) pendingDelete.delete(id) // cr-0111: confirmed gone
    const visible = listed.filter((m) => !pendingDelete.has(m.mediaId)) // cr-0111: keep optimistically-trashed media out until the delete commits
    pipeline.reconcile(visible)
    items.value = visible
  }

  // `reset` (a Memory/chapter switch) clears the grid up front; an incremental reload (post-upload batch) merges
  // the fresh list into place so existing tiles don't blink empty — jarring next to the optimistic tiles (cr-0074).
  /** `list` in grid order, so the tiles on screen when a gallery opens are asked for first (the read scheduler serves a
   *  lane in the order asked, inbox #488); a media the grid does not show (a mark filter) comes after them. */
  function inGridOrder(list: readonly MediaItem[]): MediaItem[] {
    const rank = new Map(sortedItems.value.map((it, i) => [it.mediaId, i]))
    const at = (m: MediaItem) => rank.get(m.mediaId) ?? Number.MAX_SAFE_INTEGER
    return [...list].sort((a, b) => at(a) - at(b))
  }

  async function load({ reset = false }: { reset?: boolean } = {}) {
    const key = ctx.memoryKey()
    const nodeId = ctx.node().id
    if (!key) {
      pipeline.revokeAll()
      items.value = []
      mediaRevision.value = null // E4: no K_node (locked / not openable) → gallery unavailable, no live poll
      deleteRevision.value = 0
      status.value = 'error'
      return
    }
    if (reset) {
      pipeline.revokeAll()
      items.value = []
      mediaRevision.value = null
      deleteRevision.value = 0
    }
    status.value = items.value.length ? 'ready' : 'loading'
    try {
      const page = await ctx.source.listMedia(nodeId) // the full list
      if (ctx.node().id !== nodeId) return // navigated away while listing
      mergeItems(page.items)
      mediaRevision.value = page.revision?.media ?? null // cache the ETag + delete gate for the live poll (none: no poll)
      deleteRevision.value = page.revision?.delete ?? 0
      status.value = 'ready'
      await pipeline.decrypt(inGridOrder(page.items), () => ctx.node().id === nodeId)
      if (ctx.node().id === nodeId) await ctx.onSettle() // settle: auto-date, then auto-location (RMW-safe)
    } catch {
      if (ctx.node().id === nodeId) status.value = 'error'
    }
  }

  /** The live poll's tick (uc.memory.live-updates, cr-0107; the conditional read is core's `itemsPollTick`, inbox #410):
   *  a conditional GET keyed on the cached `mediaRevision`. A 304 is a no-op; a change upserts the keyset delta (new
   *  tiles auto-insert), reconciles removals via the id-set, and decrypts just the delta's tiles. Node-race-guarded
   *  (`isCurrent`) so a mid-poll navigation can't write into the wrong gallery, and fenced by the live poll's `signal`
   *  so a poll still in flight when the loop stops (close, lock) writes nothing. A failed fetch REJECTS: the loop backs
   *  off and the next successful poll re-syncs in one round-trip (E6) while the last grid stays. */
  const pollTick = itemsPollTick<MediaItem, string>({
    cursor: () => {
      if (!ctx.memoryKey() || !ctx.source.poll || mediaRevision.value === null) return null // no live poll here / not an open, loaded gallery
      return {
        key: ctx.node().id,
        revision: mediaRevision.value,
        deleteRevision: deleteRevision.value,
        since: newestCursor(items.value),
      }
    },
    fetch: async (cursor) => {
      const page = await ctx.source.poll!(cursor.key, {
        mediaRevision: cursor.revision,
        since: cursor.since,
        deleteRevision: cursor.deleteRevision,
      })
      return page.notModified
        ? { notModified: true }
        : { notModified: false, items: page.items, revision: page.mediaRevision, deleteRevision: page.deleteRevision, ids: page.mediaIds }
    },
    isCurrent: (nodeId) => ctx.node().id === nodeId,
    apply: async (change, cursor, signal) => {
      upsertItems(change.items, change.ids)
      mediaRevision.value = change.revision
      deleteRevision.value = change.deleteRevision
      await pipeline.decrypt(change.items, () => ctx.node().id === cursor.key) // decrypt only the delta's new/changed tiles
      if (!signal.aborted && ctx.node().id === cursor.key) await ctx.onSettle()
    },
  })

  /** One live poll, errors swallowed (offline/transient: keep the current grid) - for a caller that polls by hand. */
  async function pollMedia(signal: AbortSignal = new AbortController().signal) {
    try {
      await pollTick(signal)
    } catch {
      // the revision compare makes the next successful poll a single round-trip
    }
  }

  /** Merge a live delta into `items` (cr-0107) by core's `reconcileItems`: upsert each delta media by `mediaId` - a new
   *  media auto-inserts, an in-place meta edit patches the existing tile (keeping object identity, so an open lightbox
   *  snapshot stays consistent) - and, **only when the id-set is present** (a delete advanced the delete revision),
   *  drop any cached media absent from it, revoking its thumbnail. Reassigns `items` so the justified grid re-sorts. */
  function upsertItems(delta: MediaItem[], mediaIds: string[] | null) {
    items.value = reconcileItems(items.value, delta, mediaIds, {
      idOf: (m) => m.mediaId,
      skip: (id) => pendingDelete.has(id), // cr-0111: don't re-add an optimistically-trashed media (a stale pre-commit read)
      patch: (existing, m) => {
        existing.wrappedMeta = m.wrappedMeta // a meta edit re-sealed the tier; patch in place (shared with the lightbox snapshot)
        existing.metaRevision = m.metaRevision // …and the version it is at, or the next write from here would 409 once
        existing.updatedAt = m.updatedAt
      },
      onIds: (listed) => {
        for (const id of [...pendingDelete]) if (!listed.has(id)) pendingDelete.delete(id) // cr-0111: server confirmed it's gone → lift the tombstone
      },
      onDrop: (id) => pipeline.dropThumb(id),
    })
  }

  // The live-gallery lifecycle: on a Memory/chapter switch tear down the previous poll, clear the grid, reload, and
  // (re)start the poll — but only for an open, unlocked gallery (E4: no K_node ⇒ no polling).
  watch(
    () => [ctx.node().id, ctx.memoryKey()] as const,
    () => {
      lightboxIndex.value = null
      pendingDelete.clear()
      undo.cancel() // a node switch drops any open undo window (the trash stands) + its toast
      toast.dismissUndo()
      stopMediaRevisionPolling()
      void load({ reset: true }).then(() => {
        if (ctx.memoryKey() && ctx.source.poll) startMediaRevisionPolling(pollTick)
      })
    },
    { immediate: true },
  )

  onBeforeUnmount(() => {
    stopMediaRevisionPolling() // stop the live poll on close/navigate away
    pipeline.revokeAll()
    toast.dismissUndo() // drop any lingering undo-toast (the `useUndo` window tears itself down)
  })

  return {
    items,
    thumbs,
    thumbFailed,
    layoutItems,
    hasContent,
    status,
    sortDir,
    lightboxItems,
    lightboxIndex,
    openLightboxAt,
    openBucketLightbox,
    onDeleteFromLightbox,
    onCapturedAtUpdated,
    load,
    pollMedia,
    liftTombstone,
    removeMedia: removeFromGrid,
  }
}
