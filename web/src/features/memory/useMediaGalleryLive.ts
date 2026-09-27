// uc.memory.gallery (web) — the live-gallery core of MemoryDetail, extracted into a composable (cr-0151, audit
// #77/P4a). Owns the listed media, the thumbnail pipeline (cr-0150) + optimistic tiles (cr-0074), the cr-0107
// live poll (load/merge/upsert), the justified layout, and the optimistic-delete/undo. MemoryDetail keeps only
// orchestration + the template. Operator-blind: thumbnails decrypt on device under K_mem; nothing plaintext crosses.
import { ref, computed, watch, onBeforeUnmount } from 'vue'
import { useI18n } from 'vue-i18n'
import { useToast } from '../../shared/state/useToast'
import { useUndo } from '../../shared/state/useUndo'
import type { TreeNode } from './tree'
import {
  openGrid,
  hasDerivatives,
  unsealMeta,
  effectiveCapturedAt,
  listMediaPage,
  newestCursor,
  deleteMedia,
  restoreMedia,
  type MediaItem,
} from './media'
import { startMediaRevisionPolling, stopMediaRevisionPolling } from './mediaRevisionPolling'
import { galleryDisplayAspectRatio } from './mediaGridLayout'
import { useThumbnailPipeline } from './useThumbnailPipeline'
import { useOptimisticTiles, type OptimisticTileVM } from './useOptimisticTiles'
import { byCaptureTime } from './optimisticUpload'

/** The reactive context MemoryDetail hands the gallery core — getters so props stay reactive, plus the reconcile
 *  settle callback (auto-date + auto-location self-heal) run after every list refresh. */
export interface MediaGalleryLiveCtx {
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
  const { optimisticTiles, donorPreviewFor } = useOptimisticTiles(computed(() => ctx.node().id), listedMediaIds)
  // The gallery thumbnail pipeline (cr-0150) — decrypt-per-tile → object-URL map, the single revoke owner of every
  // thumbnail (incl. a donated optimistic preview). Decrypt = the grid tier under K_mem (operator-blind).
  const pipeline = useThumbnailPipeline<MediaItem>({
    idOf: (m) => m.mediaId,
    isDerivable: hasDerivatives,
    decryptTile: (m) => {
      const key = ctx.memoryKey()
      return key ? openGrid(ctx.scopeId(), key, m) : Promise.resolve(null)
    },
    donorPreviewFor,
  })
  const thumbs = pipeline.thumbs // mediaId → thumbnail object URL (derivable items only)
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
  const undo = useUndo<{ mediaId: string }>({ timeoutMs: 6000 })
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
    if (!ctx.memoryKey()) return
    removeFromGrid(payload.mediaId)
    undo.arm({ mediaId: payload.mediaId })
    toast.showUndo({ summary: t('memory.trash.movedToTrash'), onUndo: onUndoDelete, life: 6000 })
    try {
      await deleteMedia(ctx.scopeId(), payload.mediaId)
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

  /** Undo the just-trashed media: restore it server-side, then re-list so it re-appears in the grid. */
  async function onUndoDelete() {
    const u = undo.cancel()
    if (!u) return
    pendingDelete.delete(u.mediaId) // cr-0111: a genuine restore lifts the tombstone so the media can re-list
    try {
      await restoreMedia(ctx.scopeId(), u.mediaId)
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
  function onCapturedAtUpdated(payload: { mediaId: string; wrappedMeta: string | null }) {
    const item = items.value.find((it) => it.mediaId === payload.mediaId)
    if (!item) return
    item.wrappedMeta = payload.wrappedMeta ?? undefined
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
  async function load({ reset = false }: { reset?: boolean } = {}) {
    const key = ctx.memoryKey()
    const nodeId = ctx.node().id
    if (!key) {
      pipeline.revokeAll()
      items.value = []
      mediaRevision.value = null // E4: no K_mem (locked / not openable) → gallery unavailable, no live poll
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
      const page = await listMediaPage(ctx.scopeId(), nodeId) // full list — no ETag/cursor, so never a 304
      if (ctx.node().id !== nodeId || page.notModified) return // navigated away while listing / (unreachable) 304
      mergeItems(page.items)
      mediaRevision.value = page.mediaRevision // cache the ETag + delete gate for the live poll
      deleteRevision.value = page.deleteRevision
      status.value = 'ready'
      await pipeline.decrypt(page.items, () => ctx.node().id === nodeId)
      if (ctx.node().id === nodeId) await ctx.onSettle() // settle: auto-date, then auto-location (RMW-safe)
    } catch {
      if (ctx.node().id === nodeId) status.value = 'error'
    }
  }

  /** One live poll (uc.memory.live-updates, cr-0107): a conditional GET keyed on the cached `mediaRevision`. A 304
   *  is a no-op; a change upserts the keyset delta (new tiles auto-insert), reconciles removals via the id-set, and
   *  decrypts just the delta's tiles. Errors are swallowed — the last grid stays and the next poll re-syncs in one
   *  round-trip (E6). Node-race-guarded so a mid-poll navigation can't write into the wrong gallery, and fenced by
   *  the live poll's `signal` so a poll still in flight when the loop stops (close, lock) writes nothing. */
  async function pollMedia(signal?: AbortSignal) {
    const key = ctx.memoryKey()
    const nodeId = ctx.node().id
    if (!key || mediaRevision.value === null) return // not an open, loaded gallery
    try {
      const page = await listMediaPage(ctx.scopeId(), nodeId, {
        ifNoneMatch: String(mediaRevision.value),
        since: newestCursor(items.value),
        sinceDelete: deleteRevision.value,
      })
      if (signal?.aborted || ctx.node().id !== nodeId || page.notModified) return // stopped / navigated away / nothing changed (304)
      upsertItems(page.items, page.mediaIds)
      mediaRevision.value = page.mediaRevision
      deleteRevision.value = page.deleteRevision
      await pipeline.decrypt(page.items, () => ctx.node().id === nodeId) // decrypt only the delta's new/changed tiles
      if (!signal?.aborted && ctx.node().id === nodeId) await ctx.onSettle()
    } catch {
      // offline/transient: keep the current grid; the revision compare makes the next successful poll a single round-trip
    }
  }

  /** Merge a live delta into `items` (cr-0107): upsert each delta media by `mediaId` — a new media auto-inserts, an
   *  in-place meta edit patches the existing tile (keeping object identity, so an open lightbox snapshot stays
   *  consistent) — and, **only when the id-set is present** (a delete advanced `delete_revision`), drop any cached
   *  media absent from it, revoking its thumbnail. Reassigns `items` so the justified grid re-sorts. */
  function upsertItems(delta: MediaItem[], mediaIds: string[] | null) {
    const byId = new Map(items.value.map((it) => [it.mediaId, it] as const))
    for (const m of delta) {
      if (pendingDelete.has(m.mediaId)) continue // cr-0111: don't re-add an optimistically-trashed media (a stale pre-commit read)
      const existing = byId.get(m.mediaId)
      if (existing) {
        existing.wrappedMeta = m.wrappedMeta // a meta edit re-sealed the tier; patch in place (shared with the lightbox snapshot)
        existing.updatedAt = m.updatedAt
      } else {
        byId.set(m.mediaId, m)
      }
    }
    if (mediaIds) {
      const keep = new Set(mediaIds)
      for (const id of [...pendingDelete]) if (!keep.has(id)) pendingDelete.delete(id) // cr-0111: server confirmed it's gone → lift the tombstone
      for (const id of [...byId.keys()]) {
        if (keep.has(id)) continue
        pipeline.dropThumb(id)
        byId.delete(id)
      }
    }
    items.value = [...byId.values()]
  }

  // The live-gallery lifecycle: on a Memory/chapter switch tear down the previous poll, clear the grid, reload, and
  // (re)start the poll — but only for an open, unlocked gallery (E4: no K_mem ⇒ no polling).
  watch(
    () => [ctx.node().id, ctx.memoryKey()] as const,
    () => {
      lightboxIndex.value = null
      pendingDelete.clear()
      undo.cancel() // a node switch drops any open undo window (the trash stands) + its toast
      toast.dismissUndo()
      stopMediaRevisionPolling()
      void load({ reset: true }).then(() => {
        if (ctx.memoryKey()) startMediaRevisionPolling((signal) => pollMedia(signal))
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
  }
}
