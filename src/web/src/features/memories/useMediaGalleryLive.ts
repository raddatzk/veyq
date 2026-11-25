// uc.memory.gallery (web) — the live gallery of MemoryDetail, drawn over Memories' Kotlin gallery model (ADR 0155 decision
// 8, § The gallery model's move): `GalleryModel` decides what the page lists — its photos and videos in the grid's order
// (newest or oldest first), its GPX tracks, its music —, when it reads anew (its live poll, after an upload), a member's
// trash with its Undo and a link guest's purge of its own medium. The web keeps what a browser does: the listing's I/O and
// opening (`galleryDoor.ts`), the thumbnail pipeline (cr-0150), the in-flight upload tiles (cr-0074), the justified layout,
// the lightbox's snapshot and the toasts. Operator-blind: thumbnails decrypt on device under K_node; no key crosses into
// the model, and nothing plaintext leaves the device.
import { computed, shallowRef, watch, onBeforeUnmount } from 'vue'
import { useI18n } from 'vue-i18n'
import * as core from '@veyq/core-js/memories'
import type { GalleryStateShape } from '@veyq/core-js/memories'
import { useToast } from '../../shared/state/useToast'
import type { TreeNode } from '../../core/node/tree'
import { hasDerivatives, type MediaItem } from './media'
import { closeListing } from './openedListing'
import type { MemorySource } from './source'
import { MEDIA_REFUSAL_TEXT, type MediaRefusal } from './mediaRefusal'
import { galleryDisplayAspectRatio } from './mediaGridLayout'
import { openedMeta } from './openedListing'
import { useThumbnailPipeline } from './useThumbnailPipeline'
import { useOptimisticTiles, type OptimisticTileVM } from './useOptimisticTiles'
import { byCaptureTime } from './optimisticUpload'
import { galleryDoor, capturedAtOf, type GalleryDoor } from './galleryDoor'
import { GALLERY_LOADING, useGalleryModel } from './useGalleryModel'

/** The reactive context MemoryDetail hands the gallery — getters so props stay reactive, plus the settle (auto-date +
 *  auto-location self-heal) the model runs at its settle points: the opening, a trash, a poll that moved. */
export interface MediaGalleryLiveCtx {
  /** The session's data source (`./source.ts`): the list, the tiles' bytes, the live poll and the delete — each only
   *  where the source carries it (a guest's has no trash, and its delete reaches its own media only). */
  source: MemorySource
  scopeId: () => string
  node: () => TreeNode
  memoryKey: () => Uint8Array | null
  onSettle: () => Promise<void>
  /**
   * The gallery's filter - the item marks (ADR 0068) and the facts (uc.memory.gallery step 8), one predicate
   * (`galleryFilter.ts`' `galleryPredicate`) - or null when the gallery shows everything. It is applied to
   * `sortedItems` — the one list the grid, each tile's `index` and `openLightboxAt`'s snapshot all derive from
   * — so filtering narrows the lightbox with the grid.
   */
  filter?: () => ((item: MediaItem) => boolean) | null
}

/** A grid tile handed to [MediaGrid]: an in-flight optimistic upload or a listed media. */
export type MemoryTileItem =
  | { id: string; aspectRatio: number; kind: 'optimistic'; optimistic: OptimisticTileVM }
  | { id: string; aspectRatio: number; kind: 'media'; media: MediaItem; index: number }

/** The model's restore refusal reasons, as the web's words name them. */
const REASON: Record<string, MediaRefusal> = { gone: 'gone', scope_deleting: 'scope_deleting', blocked: 'blocked_by_operator', failed: 'failed' }

export function useMediaGalleryLive(ctx: MediaGalleryLiveCtx) {
  const { t } = useI18n()
  const toast = useToast()
  /** The page's state as the model last said it — set in the model's own listener, so a settle the model runs right after
   *  a read sees its rows (`GALLERY_LOADING` before; an `error` while no key opens the node). */
  const state = shallowRef<GalleryStateShape>(GALLERY_LOADING)
  const model = useGalleryModel(state)
  let door: GalleryDoor | null = null

  /** The model's ids drawn as the door's listed media (the same objects every view and the lightbox's snapshot hold). */
  function itemsOf(ids: readonly string[]): MediaItem[] {
    const byId = door?.byId
    if (!byId) return []
    const out: MediaItem[] = []
    for (const id of ids) {
      const m = byId.get(id)
      if (m) out.push(m)
    }
    return out
  }
  // Every photo and video of the node in the grid's order (`GalleryMedium`, the model's split): the one list every view of
  // photos derives from (the grid, the lightbox, the count, the organizer, the search, the date and place reconcile). A
  // GPX track (ADR 0130 decision 1) and a music track (ADR 0135 decision 2) are media too and no photo: each is handed to its
  // own tab, as `tracks` (the Map tab) and `music` (the Music tab), in the listing's order.
  /** The model's ids in the listing's order, as the Map and the Music tab took them before (each tab orders its own). */
  function listedOf(ids: readonly string[]): MediaItem[] {
    const want = new Set(ids)
    return (door?.listed ?? []).filter((m) => want.has(m.mediaId))
  }
  const items = computed(() => itemsOf(state.value.rows))
  const tracks = computed(() => listedOf(state.value.tracks))
  const music = computed(() => listedOf(state.value.music))
  const status = computed(() => state.value.status as 'loading' | 'ready' | 'error')

  // Optimistic in-grid upload tiles (uc.memory.upload, cr-0074): the shared queue's still-in-flight files for THIS
  // node (grouping key = the node id) as instant preview tiles, reconciled into the real thumbnail by mediaId.
  const listedMediaIds = computed(() => {
    const s = state.value // read first: the state is what this follows (the door is no reactive value)
    return new Set([...s.rows, ...s.music, ...s.tracks])
  })
  const { optimisticTiles, donorPreviewFor, wantPreviews } = useOptimisticTiles(computed(() => ctx.source.batchKey(ctx.node().id)), listedMediaIds)
  // The gallery thumbnail pipeline (cr-0150) — decrypt-per-tile → object-URL map, the single revoke owner of every
  // thumbnail (incl. a donated optimistic preview). Decrypt = the grid tier under K_node (operator-blind).
  const pipeline = useThumbnailPipeline<MediaItem>({
    idOf: (m) => m.mediaId,
    // A medium a moderator blocked has no thumbnail to read: every byte of it answers 451 (uc.moderation.take-a-measure).
    isDerivable: (m) => !m.blocked && hasDerivatives(m),
    decryptTile: (m, signal) => {
      const key = ctx.memoryKey()
      return key ? ctx.source.loaders.grid(m, key, signal) : Promise.resolve(null)
    },
    donorPreviewFor,
  })
  // the pipeline reads what the grid wants from the list it holds, and revokes a thumbnail whose medium the rows dropped
  watch(items, (now) => pipeline.reconcile(now))
  const thumbs = pipeline.thumbs // mediaId → thumbnail object URL (derivable items only)
  const thumbFailed = pipeline.failed // mediaIds whose thumbnail read failed for good: the tile's error state
  const lightboxIndex = shallowRef<number | null>(null)
  const lightboxItems = shallowRef<MediaItem[]>([]) // a snapshot of the sorted order at open time (so an in-lightbox edit can't reorder underfoot)
  // date sort (cr-0066): newest first by default; the order is the model's (`MemoryMedia.order`)
  const sortDir = shallowRef<'newest' | 'oldest'>('newest')
  watch(sortDir, (dir) => {
    const h = model.handle()
    if (h) core.memoriesGallerySort(h, dir === 'oldest')
  })

  const capturedAt = (item: MediaItem) => capturedAtOf(ctx.scopeId(), ctx.memoryKey(), item)
  // The LISTED media in grid order, narrowed by the filter — the lightbox's world: the `index` below and
  // `openLightboxAt`'s snapshot are both positions in THIS list, never in the merged one.
  const sortedItems = computed(() => {
    const keep = ctx.filter?.() ?? null
    return keep ? items.value.filter(keep) : items.value
  })
  const aspectRatios = computed(() => {
    const key = ctx.memoryKey()
    const m = new Map<string, number>()
    for (const item of items.value) {
      const dims = key ? openedMeta(ctx.scopeId(), key, item) : null
      m.set(item.mediaId, galleryDisplayAspectRatio(dims?.w, dims?.h))
    }
    return m
  })
  const FALLBACK_ASPECT = galleryDisplayAspectRatio(undefined, undefined)
  // The tile list handed to [MediaGrid] — ONE order over both kinds of tile (uc.memory.gallery § "One order for every
  // tile"): an in-flight upload sorts on the capture time it will be sealed with among the model's rows, which keep their
  // order (the same key, ties in place), so it appears where it will stay; passed first, it hands its slot to the media it
  // becomes. The in-flight tiles' own placement onto the model's is inbox #1342.
  const layoutItems = computed<MemoryTileItem[]>(() => {
    const media = sortedItems.value.map((it, index) => ({
      id: it.mediaId,
      aspectRatio: aspectRatios.value.get(it.mediaId) ?? FALLBACK_ASPECT,
      kind: 'media' as const,
      media: it,
      index,
    }))
    // An in-flight upload carries no marks and no opened facts yet, so it is not part of a filtered view.
    if (ctx.filter?.() || optimisticTiles.value.length === 0) return media
    return byCaptureTime<MemoryTileItem>(
      [...optimisticTiles.value.map((o) => ({ id: o.layoutId, aspectRatio: o.aspectRatio, kind: 'optimistic' as const, optimistic: o })), ...media],
      (tile) => (tile.kind === 'optimistic' ? tile.optimistic.capturedAt : capturedAt(tile.media)),
      sortDir.value === 'newest' ? -1 : 1,
    )
  })
  // Whether the gallery has anything to show — real media or in-flight optimistic uploads.
  const hasContent = computed(() => items.value.length > 0 || optimisticTiles.value.length > 0)

  // --- a member's trash with its Undo (uc.memory.delete-media, cr-0110/0111): the model's ---

  // The Undo's toast while the model offers it; a restore it refused, named with its reason (inbox #429).
  watch(() => state.value.undo, (offered, was) => {
    if (offered && !was) toast.showUndo({ summary: t('words.memory_moved_to_trash'), onUndo: onUndoDelete, life: 6000 })
    else if (!offered && was) toast.dismissUndo()
  })
  watch(() => state.value.said, (said) => {
    const h = model.handle()
    if (!said || !h || said.kind !== 'restoreRefused') return
    core.memoriesGallerySeen(h)
    const name = said.name ?? t('memory.buckets.unnamedMedia', { n: said.unnamedAt ?? 1 })
    toast.error(t('memory.buckets.restoreRefused', { names: name, reason: t(MEDIA_REFUSAL_TEXT[REASON[said.reason ?? 'failed'] ?? 'failed']) }))
  })

  /** Trash the current lightbox photo: out of the grid and the lightbox at once, the server's soft delete, an Undo. */
  async function onDeleteFromLightbox(payload: { mediaId: string }) {
    const h = model.handle()
    if (!h || ctx.source.deleteMedia?.reach !== 'any') return // only a trash has an undo; a guest's purge is asked first
    dropFromLightbox(payload.mediaId)
    pipeline.dropThumb(payload.mediaId)
    await core.memoriesGalleryTrash(h, payload.mediaId)
  }

  /** The Undo of the just-trashed medium: the model restores it and reads anew. */
  async function onUndoDelete() {
    const h = model.handle()
    if (h) await core.memoriesGalleryUndo(h)
  }

  /**
   * A guest's own medium purged, the guest having confirmed (inbox #361: irreversible, so the page asks first): out of the
   * rows once it went. False where it did not (the tile stays, to try again).
   */
  async function purgeOwn(mediaId: string): Promise<boolean> {
    const h = model.handle()
    if (!h) return false
    await core.memoriesGalleryTrash(h, mediaId) // the model asks: a guest's own medium is a purge
    await core.memoriesGalleryPurge(h, true)
    const said = state.value.said
    if (said?.kind === 'purgeFailed') {
      core.memoriesGallerySeen(h)
      return false
    }
    dropFromLightbox(mediaId)
    pipeline.dropThumb(mediaId)
    return true
  }

  function dropFromLightbox(mediaId: string) {
    lightboxItems.value = lightboxItems.value.filter((it) => it.mediaId !== mediaId)
  }

  /** A trash restore from the Trash tab (via useMediaTrash): the model shows it with its next read. */
  const liftTombstone = (mediaId: string) => {
    const h = model.handle()
    if (h) core.memoriesGalleryRestored(h, [mediaId])
  }

  /** Open the lightbox on a grid tile, snapshotting the current sorted order so an in-lightbox edit can't reorder it. */
  function openLightboxAt(index: number) {
    lightboxItems.value = sortedItems.value.slice()
    lightboxIndex.value = index
  }
  /** Open the lightbox on the medium `mediaId` (a search result, ADR 0125): in the gallery's order, or - where a filter
   *  hides it - over every listed medium, so the result opens whatever the gallery shows. False when it is not listed. */
  function openLightboxOn(mediaId: string): boolean {
    const at = sortedItems.value.findIndex((it) => it.mediaId === mediaId)
    if (at >= 0) {
      openLightboxAt(at)
      return true
    }
    const all = items.value.findIndex((it) => it.mediaId === mediaId)
    if (all < 0) return false
    openBucketLightbox({ items: items.value, index: all })
    return true
  }
  /** Open the lightbox on a bucket-scoped list the organizer hands up (so prev/next stays within the picked bucket). */
  function openBucketLightbox(payload: { items: MediaItem[]; index: number }) {
    lightboxItems.value = payload.items.slice()
    lightboxIndex.value = payload.index
  }
  /** A date correction from the lightbox → the item's sealed tier patched (the object the lightbox shares), and the model
   *  reads anew, so the grid re-sorts by the new time. */
  function onCapturedAtUpdated(payload: { mediaId: string; wrappedMeta: string | null; metaRevision: number }) {
    const item = door?.byId.get(payload.mediaId)
    if (!item) return
    item.wrappedMeta = payload.wrappedMeta ?? undefined
    item.metaRevision = payload.metaRevision // the base of the next meta write on this item (ADR 0077)
    void load()
  }

  /** The gallery read anew (an upload's file in, a batch done, a restore): resolved once the rows hold that listing. */
  async function load() {
    const h = model.handle()
    if (h) await core.memoriesGalleryReload(h)
  }

  // The page's model: one per node and key. On a Memory/chapter switch the previous one ends (its poll, its Undo window —
  // the trash stands), the grid clears, and the new one reads; no key (locked, not openable): no model, the gallery fails.
  // The opened listing this gallery holds (`openedListing.ts`) is left when the gallery moves to another node's key or
  // unmounts, so the listing opener Worker forgets that Memory's keys (ADR 0105 decision 3).
  let holding: { scopeId: string; key: Uint8Array } | null = null
  function hold(key: Uint8Array | null) {
    if (holding && (holding.key !== key || holding.scopeId !== ctx.scopeId())) closeListing(holding.scopeId, holding.key)
    holding = key ? { scopeId: ctx.scopeId(), key } : null
  }
  // Two sources, compared one by one — never one getter returning `[id, key]`: that array is new on every run, and the
  // getter re-runs whenever the node OBJECT is replaced (the host's tree poll re-reads the tree under an open Memory,
  // inbox #503), so it would reset and reload the gallery for a change that is not this node's or its key's.
  watch(
    [() => ctx.node().id, () => ctx.memoryKey()] as const,
    () => {
      const key = ctx.memoryKey()
      hold(key)
      lightboxIndex.value = null
      lightboxItems.value = []
      toast.dismissUndo()
      model.close()
      pipeline.revokeAll()
      door = null
      if (!key) {
        state.value = { ...GALLERY_LOADING, status: 'error' } // E4: no K_node → gallery unavailable, no live poll
        return
      }
      const node = ctx.node()
      const del = ctx.source.deleteMedia
      door = galleryDoor({
        source: ctx.source, scopeId: ctx.scopeId(), nodeId: node.id, key,
        onWithdrawn: (id) => pipeline.dropThumb(id),
        settle: () => ctx.onSettle(),
      })
      model.open({
        door, scopeId: ctx.scopeId(), nodeId: node.id, kind: node.kind === 'CHAPTER' ? 'CHAPTER' : 'MEMORY',
        // who looks, as far as the model's own decisions go — its delete (the page's other rights are still the web's matrix,
        // inbox #1321): a link's contributor (a delete reaching its own media: a purge), a member who trashes, else one
        // who deletes nothing (a viewing guest and a read-only member answer alike in the model)
        role: del?.reach === 'own' ? 'READ_WRITE' : null,
        readonly: del?.reach !== 'any',
      })
      if (sortDir.value === 'oldest') core.memoriesGallerySort(model.handle()!, true)
    },
    { immediate: true },
  )

  onBeforeUnmount(() => {
    model.close()
    pipeline.revokeAll()
    hold(null)
    toast.dismissUndo() // drop any lingering undo-toast
  })

  return {
    items,
    /** The node's GPX tracks (ADR 0130): media, listed and live as every medium, shown on the Map tab only. */
    tracks,
    /** The node's music tracks (ADR 0135): media, listed and live as every medium, shown on the Music tab and played. */
    music,
    thumbs,
    thumbFailed,
    layoutItems,
    hasContent,
    status,
    sortDir,
    lightboxItems,
    lightboxIndex,
    openLightboxAt,
    openLightboxOn,
    openBucketLightbox,
    onDeleteFromLightbox,
    onCapturedAtUpdated,
    load,
    /** A targeted read after an upload's file is in: the model reads anew (the conditional read, its delta). */
    pollMedia: load,
    liftTombstone,
    purgeOwn,
    /** The tiles `owner` (a grid, the cover) wants a thumbnail for, nearest first (ADR 0105 decision 2) - an in-flight
     *  upload's tile its tile-sized preview (inbox #785). */
    wantThumbs: (owner: string, ids: readonly string[]) => {
      pipeline.want(owner, ids)
      wantPreviews(owner, ids)
    },
  }
}
