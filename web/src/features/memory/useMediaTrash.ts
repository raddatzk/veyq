// uc.memory.delete-media (web) — the Memory Trash-tab reactive slice of MemoryDetail, extracted into a composable
// (cr-0147, audit #77/P4a). A lightbox delete soft-deletes a photo into a per-Memory trash; this tab lists the
// trashed media (restorable until the retention purge, cr-0110), loaded lazily on open, with batch selection, a
// per-tile restore/purge menu, and the irreversible-purge confirm (cr-0111). It owns its own thumbnail object-URL
// lifecycle (revoked on unmount). Two outward effects go back to the host via callbacks: a restore/purge lifts the
// gallery's optimistic-delete tombstone (`liftTombstone`) and a restore re-lists the gallery (`reloadGallery`).
import { ref, computed } from 'vue'
import { useI18n } from 'vue-i18n'
import Menu from 'primevue/menu'
import type { MenuItem } from 'primevue/menuitem'
import { openGrid, hasDerivatives, unsealMeta, listTrash, restoreMedia, purgeMedia, type MediaItem } from './media'
import { galleryDisplayAspectRatio } from './mediaGridLayout'
import { useObjectUrls } from '../../shared/state/useObjectUrls'
import type { TreeNode } from './tree'

/** The reactive context MemoryDetail hands the trash slice — getters so props stay reactive, plus the two host
 *  effects: re-list the gallery after a restore, and lift the gallery's pending-delete tombstone. */
export interface MediaTrashContext {
  scopeId: () => string
  node: () => TreeNode
  memoryKey: () => Uint8Array | null
  reloadGallery: () => void
  liftTombstone: (mediaId: string) => void
}

export function useMediaTrash(ctx: MediaTrashContext) {
  const { t } = useI18n()

  const trashedItems = ref<MediaItem[]>([])
  // Per-tile decrypted thumbnails — the object-URL lifecycle (replace/drop/reset/unmount) is owned by the primitive.
  const thumbs = useObjectUrls()
  const trashStatus = ref<'idle' | 'loading' | 'ready' | 'error'>('idle')
  // Trash tab (cr-0111): batch selection + the per-tile overflow menu + the irreversible-purge confirm.
  const selectedTrash = ref<Set<string>>(new Set())
  const trashMenu = ref<InstanceType<typeof Menu> | null>(null)
  const menuTargetId = ref<string | null>(null)
  const purgeConfirm = ref<{ ids: string[] } | null>(null)

  /** Restore a media from the Trash tab: drop it from the tab (optimistic), restore server-side, re-list the gallery. */
  async function onRestoreFromTrash(mediaId: string) {
    dropFromTrash(mediaId)
    ctx.liftTombstone(mediaId) // cr-0111: a genuine restore lifts the gallery tombstone
    try {
      await restoreMedia(ctx.scopeId(), mediaId)
    } finally {
      ctx.reloadGallery()
    }
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
    if (!key) return
    const nodeId = ctx.node().id
    trashStatus.value = 'loading'
    thumbs.clear()
    try {
      const list = await listTrash(ctx.scopeId(), nodeId)
      if (ctx.node().id !== nodeId) return
      trashedItems.value = list
      trashStatus.value = 'ready'
      await Promise.all(
        list.map(async (m) => {
          if (!hasDerivatives(m)) return
          try {
            const bytes = await openGrid(ctx.scopeId(), key, m)
            if (ctx.node().id === nodeId) thumbs.set(m.mediaId, new Blob([bytes as BlobPart]))
          } catch {
            // a failed tile stays a placeholder
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
      { label: t('memory.trash.restore'), icon: 'pi pi-replay', command: () => { if (id) void onRestoreFromTrash(id) } },
      { label: t('memory.trash.purge'), icon: 'pi pi-trash', command: () => { if (id) requestPurge([id]) } },
    ]
  })

  /** Ask to confirm the irreversible purge of [ids] (a single item from the menu, or the batch selection). */
  function requestPurge(ids: string[]) {
    if (ids.length) purgeConfirm.value = { ids: [...ids] }
  }

  /** Batch restore the current selection: optimistic drop, restore each server-side, one re-list of the gallery. */
  async function restoreSelected() {
    const ids = [...selectedTrash.value]
    if (!ids.length) return
    ids.forEach((id) => { dropFromTrash(id); ctx.liftTombstone(id) })
    await Promise.allSettled(ids.map((id) => restoreMedia(ctx.scopeId(), id)))
    ctx.reloadGallery()
  }
  function purgeSelected() {
    requestPurge([...selectedTrash.value])
  }

  /** Permanently delete the confirmed ids now (cr-0111): optimistic drop from the tab, then the hard-delete purge.
   *  Busy while the round-trip runs (cr-0154) — the confirm dialog shows progress and gates cancel/close on it. */
  const purgeBusy = ref(false)
  async function confirmPurge() {
    const ids = purgeConfirm.value?.ids ?? []
    if (!ids.length) {
      purgeConfirm.value = null
      return
    }
    purgeBusy.value = true
    try {
      ids.forEach(dropFromTrash)
      const results = await Promise.allSettled(ids.map((id) => purgeMedia(ctx.scopeId(), id)))
      if (results.some((r) => r.status === 'rejected')) void loadTrash() // resync the tab on any failure
    } finally {
      purgeBusy.value = false
      purgeConfirm.value = null
    }
  }

  return {
    trashedItems, // read-only to the host (its empty-state check); mutated only inside this composable
    trashThumbs: thumbs.urls,
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
