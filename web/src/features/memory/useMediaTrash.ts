// uc.memory.delete-media (web) — the Memory Trash-tab reactive slice of MemoryDetail, extracted into a composable
// (cr-0147, audit #77/P4a). A lightbox delete soft-deletes a photo into a per-Memory trash; this tab lists the
// trashed media (restorable until the retention purge, cr-0110), loaded lazily on open, with batch selection, a
// per-tile restore/purge menu, and the irreversible-purge confirm (cr-0111). It owns its own thumbnail object-URL
// lifecycle (revoked on unmount). Two outward effects go back to the host via callbacks: a restore/purge lifts the
// gallery's optimistic-delete tombstone (`liftTombstone`) and a restore re-lists the gallery (`reloadGallery`). A
// refused restore is named with its reason, as the organizer's delete and Undo name theirs (inbox #427, #429, #432).
import { ref, computed } from 'vue'
import { useI18n } from 'vue-i18n'
import Menu from 'primevue/menu'
import type { MenuItem } from 'primevue/menuitem'
import { hasDerivatives, unsealMeta, type MediaItem } from './media'
import type { MemorySource } from './source'
import { galleryDisplayAspectRatio } from './mediaGridLayout'
import { refusalsByReason, sealedMediaName } from './mediaRefusal'
import { useObjectUrls } from '../../shared/state/useObjectUrls'
import { useToast } from '../../shared/state/useToast'
import type { TreeNode } from '../../core/node/tree'

/** The reactive context MemoryDetail hands the trash slice — getters so props stay reactive, plus the two host
 *  effects: re-list the gallery after a restore, and lift the gallery's pending-delete tombstone. */
export interface MediaTrashContext {
  /** The session's data source: its media trash (`trash`; absent — a guest's — nothing here reads or writes) and the
   *  tiles' grid loader. */
  source: MemorySource
  scopeId: () => string
  node: () => TreeNode
  memoryKey: () => Uint8Array | null
  reloadGallery: () => void
  liftTombstone: (mediaId: string) => void
}

export function useMediaTrash(ctx: MediaTrashContext) {
  const { t } = useI18n()
  const toast = useToast()

  const trashedItems = ref<MediaItem[]>([])
  // Per-tile decrypted thumbnails — the object-URL lifecycle (replace/drop/reset/unmount) is owned by the primitive.
  const thumbs = useObjectUrls()
  // mediaIds whose thumbnail read failed for good: the tile's error state (inbox #488), reassigned on every change.
  const failed = ref<ReadonlySet<string>>(new Set())
  const trashStatus = ref<'idle' | 'loading' | 'ready' | 'error'>('idle')
  // Trash tab (cr-0111): batch selection + the per-tile overflow menu + the irreversible-purge confirm.
  const selectedTrash = ref<Set<string>>(new Set())
  const trashMenu = ref<InstanceType<typeof Menu> | null>(null)
  const menuTargetId = ref<string | null>(null)
  const purgeConfirm = ref<{ ids: string[] } | null>(null)

  /** Restore a media from the Trash tab (its 3-dot menu) — `restoreFromTrash` for the one. */
  async function onRestoreFromTrash(mediaId: string) {
    await restoreFromTrash([mediaId])
  }

  /** Restore [ids] from the Trash tab: drop them from the tab (optimistic), restore each server-side (a refusal does
   *  not stop the rest), one re-list of the gallery. Every refused restore is named — by the name it had in the tab:
   *  its sealed file name, else its place in the trash grid — with its reason, one error per reason, and the tab
   *  re-lists so a medium still in the trash shows again (inbox #432). What was restored just leaves the tab. */
  async function restoreFromTrash(ids: string[]) {
    const trash = ctx.source.trash
    if (!ids.length || !trash) return
    const names = ids.map(trashedNameOf) // before the drop: a name by its place needs the tab as it was
    ids.forEach((id) => { dropFromTrash(id); ctx.liftTombstone(id) }) // cr-0111: a genuine restore lifts the tombstone
    const results = await Promise.allSettled(ids.map((id) => trash.restore(id)))
    ctx.reloadGallery()
    const refused = await refusalsByReason(results, names)
    for (const [why, refusedNames] of refused) {
      toast.error(t('memory.buckets.restoreRefused', { names: refusedNames.join(', '), reason: t(`memory.buckets.deleteRefusedReason.${why}`) }))
    }
    if (refused.size) void loadTrash() // resync the tab, as a refused purge does
  }

  /** A trashed medium's name for a refusal: the file name sealed in its original's blob meta, else its place in the
   *  trash grid (the organizer's wording, `memory.buckets.unnamedMedia`). */
  function trashedNameOf(mediaId: string): string {
    const at = trashedItems.value.findIndex((it) => it.mediaId === mediaId)
    const item = at >= 0 ? trashedItems.value[at] : undefined
    return sealedMediaName(ctx.scopeId(), ctx.memoryKey(), item) || t('memory.buckets.unnamedMedia', { n: at >= 0 ? at + 1 : 1 })
  }

  /** Drop a media from the Trash tab locally (revoke its thumb + deselect) — the optimistic half of restore/purge. */
  function dropFromTrash(mediaId: string) {
    thumbs.drop(mediaId)
    trashedItems.value = trashedItems.value.filter((it) => it.mediaId !== mediaId)
    if (selectedTrash.value.has(mediaId)) {
      const n = new Set(selectedTrash.value)
      n.delete(mediaId)
      selectedTrash.value = n
    }
  }

  /** Load the Memory's trashed media for the Trash tab (lazy, on open) — decrypt each thumbnail like the gallery. */
  async function loadTrash() {
    const key = ctx.memoryKey()
    const trash = ctx.source.trash
    if (!key || !trash) return
    const nodeId = ctx.node().id
    trashStatus.value = 'loading'
    thumbs.clear()
    failed.value = new Set()
    try {
      const list = await trash.list(nodeId)
      if (ctx.node().id !== nodeId) return
      trashedItems.value = list
      trashStatus.value = 'ready'
      await Promise.all(
        list.map(async (m) => {
          if (!hasDerivatives(m)) return
          try {
            const bytes = await ctx.source.loaders.grid(m, key)
            if (ctx.node().id === nodeId) thumbs.set(m.mediaId, new Blob([bytes as BlobPart]))
          } catch {
            if (ctx.node().id === nodeId) failed.value = new Set([...failed.value, m.mediaId]) // the tile's error state
          }
        }),
      )
    } catch {
      if (ctx.node().id === nodeId) trashStatus.value = 'error'
    }
  }

  /** Drop the previous Memory's trash state when a different Memory/chapter opens (cr-0110/0111). */
  function resetTrash() {
    thumbs.clear()
    failed.value = new Set()
    trashedItems.value = []
    trashStatus.value = 'idle'
    selectedTrash.value = new Set()
  }

  // --- Trash tab redesign (cr-0111): the SAME MediaGrid, a per-tile checkbox (batch select) + a 3-dot menu
  // (restore / purge), and a confirm for the irreversible hard-delete. ---

  /** The trashed media as MediaGrid tiles — the same justified layout as the gallery (aspect from the sealed dims). */
  const trashLayoutItems = computed(() =>
    trashedItems.value.map((m, index) => {
      const dims = ctx.memoryKey() ? unsealMeta(ctx.scopeId(), ctx.memoryKey()!, m) : null
      return { id: m.mediaId, aspectRatio: galleryDisplayAspectRatio(dims?.w, dims?.h), media: m, index }
    }),
  )

  function toggleTrashSelect(mediaId: string) {
    const next = new Set(selectedTrash.value)
    if (next.has(mediaId)) next.delete(mediaId)
    else next.add(mediaId)
    selectedTrash.value = next
  }
  /** Whether every trashed item is selected — drives the select-all ↔ deselect-all toggle (cr-0152). */
  const allTrashSelected = computed(
    () => trashedItems.value.length > 0 && trashedItems.value.every((m) => selectedTrash.value.has(m.mediaId)),
  )
  function toggleSelectAllTrash() {
    selectedTrash.value = allTrashSelected.value ? new Set() : new Set(trashedItems.value.map((m) => m.mediaId))
  }
  /** "{selected} of {visible} selected" — the always-visible bar's count (matches the Beiträge bar, cr-0122). */
  const selectedTrashLabel = computed(() =>
    t('memory.trash.selected', { selected: selectedTrash.value.size, visible: trashedItems.value.length }),
  )

  /** Open the per-tile overflow menu (restore / purge) anchored at the click, targeting [mediaId]. */
  function openTrashMenu(event: Event, mediaId: string) {
    menuTargetId.value = mediaId
    trashMenu.value?.toggle(event)
  }
  const trashMenuModel = computed<MenuItem[]>(() => {
    const id = menuTargetId.value
    return [
      // A medium a moderator blocked is deleted, never restored (uc.moderation.take-a-measure): its menu offers no restore.
      ...(trashedItems.value.find((it) => it.mediaId === id)?.blocked
        ? []
        : [{ label: t('memory.trash.restore'), icon: 'pi pi-replay', command: () => { if (id) void onRestoreFromTrash(id) } }]),
      { label: t('memory.trash.purge'), icon: 'pi pi-trash', command: () => { if (id) requestPurge([id]) } },
    ]
  })

  /** Ask to confirm the irreversible purge of [ids] (a single item from the menu, or the batch selection). */
  function requestPurge(ids: string[]) {
    if (ids.length) purgeConfirm.value = { ids: [...ids] }
  }

  /** Batch restore the current selection — `restoreFromTrash` for the selection. */
  async function restoreSelected() {
    await restoreFromTrash([...selectedTrash.value])
  }
  function purgeSelected() {
    requestPurge([...selectedTrash.value])
  }

  /** Permanently delete the confirmed ids now (cr-0111): optimistic drop from the tab, then the hard-delete purge.
   *  Busy while the round-trip runs (cr-0154) — the confirm dialog shows progress and gates cancel/close on it. */
  const purgeBusy = ref(false)
  async function confirmPurge() {
    const ids = purgeConfirm.value?.ids ?? []
    const trash = ctx.source.trash
    if (!ids.length || !trash) {
      purgeConfirm.value = null
      return
    }
    purgeBusy.value = true
    try {
      ids.forEach(dropFromTrash)
      const results = await Promise.allSettled(ids.map((id) => trash.purge(id)))
      if (results.some((r) => r.status === 'rejected')) void loadTrash() // resync the tab on any failure
    } finally {
      purgeBusy.value = false
      purgeConfirm.value = null
    }
  }

  return {
    trashedItems, // read-only to the host (its empty-state check); mutated only inside this composable
    trashThumbs: thumbs.urls,
    trashThumbFailed: failed,
    trashStatus,
    selectedTrash,
    trashMenu,
    purgeConfirm,
    purgeBusy,
    trashLayoutItems,
    trashMenuModel,
    loadTrash,
    resetTrash,
    toggleTrashSelect,
    allTrashSelected,
    toggleSelectAllTrash,
    selectedTrashLabel,
    openTrashMenu,
    restoreSelected,
    purgeSelected,
    confirmPurge,
  }
}
