<script setup lang="ts">
// A Memory's detail surface = its **gallery** (cr-0049, uc.memory.gallery). Opens the Memory's media as a
// thumbnail grid, each tile's thumbnail fetched as ciphertext via presigned GET and decrypted on device under
// K_mem (operator-blind); clicking a tile opens the lightbox (preview, prev/next, download original). The host
// view (MemoryHomeView) owns the rename/delete dialogs + the favorites store and unwraps K_mem from the tree.
// Ported presentation from the design source (legacy/frontend-noe2ee); only the data layer is ours. Decrypted
// thumbnails become object URLs that are **revoked** on reload / unmount (no lingering plaintext blob URLs).
// Out of scope (own CRs): journal, chapters + chapter-nested media, map/tracks, playlists, buckets, sharing.
import { ref, computed, watch, onBeforeUnmount } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Tabs from 'primevue/tabs'
import TabList from 'primevue/tablist'
import Tab from 'primevue/tab'
import TabPanels from 'primevue/tabpanels'
import TabPanel from 'primevue/tabpanel'
import type { TreeNode } from '../../../core/node/tree'
import { hasDerivatives, unsealMeta, isVideoMediaType, type MediaItem } from '../media'
import { useMemoryDate } from '../useMemoryDate'
import { useMemoryLocation } from '../useMemoryLocation'
import { useMemoryCover } from '../useMemoryCover'
import { useMediaTrash } from '../useMediaTrash'
import MediaGrid from './MediaGrid.vue'
import MediaTile from './MediaTile.vue'
import MemoryBuckets from './MemoryBuckets.vue'
import OptimisticTile from './OptimisticTile.vue'
import { enqueueUploads, collectPickedFiles, inferMediaType, retryFile } from '../uploadQueue'
import { useMediaGalleryLive } from '../useMediaGalleryLive'
import {
  isMarkedByMe,
  markCount,
  markedByAnyone,
  loadItemFavoritesFor,
  toggleItemFavorite,
  clearItemFavorites,
} from '../itemFavoritesStore'
import MediaLightbox from './MediaLightbox.vue'
import MemoryDateEditor from './MemoryDateEditor.vue'
import MemoryLocationEditor from './MemoryLocationEditor.vue'
import MemoryCoverImage from './MemoryCoverImage.vue'
import MemoryCoverDialog from './MemoryCoverDialog.vue'
import MemoryCard from './MemoryCard.vue'
import ViewModeToggle from './ViewModeToggle.vue'
import Dialog from 'primevue/dialog'
import Menu from 'primevue/menu'
import { useViewMode } from '../../../shared/state/useViewMode'
import { useCapabilities } from '../../../shared/state/useCapabilities'
import { SHARING_UPLOADER_LINKS } from '../../platform/capabilities'
import type { NodeMetaUpdate } from '../useMetaWriteFeedback'

const props = defineProps<{
  node: TreeNode
  scopeId: string
  memoryKey: Uint8Array | null
  favorite?: boolean
  // The scope content key at the BASE epoch — what item marks seal under (ADR 0068). Not `memoryKey`: marks
  // belong to the scope's members, not to this Memory's content grain.
  scopeKey?: Uint8Array | null
  // A personal scope has nobody to share a mark with, so "everyone's / only mine" is degenerate there and the
  // filter is HIDDEN rather than rendered disabled (uc.memory.gallery E4).
  personalScope?: boolean
  chapters?: TreeNode[] // child CHAPTER nodes of this Memory/chapter (cr-0060)
  shareable?: boolean // this is a personal-scope MEMORY → offer the public-share affordance (cr-0100)
}>()
const emit = defineEmits<{
  toggleFavorite: []
  rename: []
  delete: []
  openChapter: [node: TreeNode]
  createChapter: []
  share: []
  shareUpload: []
  metaUpdated: [NodeMetaUpdate]
}>()

const { t } = useI18n()
const { viewMode } = useViewMode()
const { hasCapability } = useCapabilities()
// The uploader (drop-box) link is its OWN memory action (a separate dialog), offered only when the instance keeps
// the uploader-links capability (ADR 0032) — the server also 422-rejects createShare(WRITE_ONLY) when it is off.
const canUploadShare = computed(() => !!props.shareable && hasCapability(SHARING_UPLOADER_LINKS))

// --- item favorites (ADR 0068) ---
// The marks inside this Memory, shared with the scope. The filter is declared BEFORE the gallery core because
// the core takes it as a predicate; `all` means no filter at all, so the unfiltered gallery pays nothing.
const markFilter = ref<'all' | 'everyone' | 'mine'>('all')
const markFilterPredicate = computed<((mediaId: string) => boolean) | null>(() => {
  if (markFilter.value === 'mine') return (id) => isMarkedByMe(id)
  if (markFilter.value === 'everyone') {
    const marked = markedByAnyone()
    return (id) => marked.has(id)
  }
  return null
})

/** Load every member's marks for this Memory — re-runs when the Memory or the scope key changes. */
async function loadMarks() {
  const key = props.scopeKey
  if (!key) return
  try {
    await loadItemFavoritesFor(props.scopeId, props.node.id, key)
  } catch {
    /* marks are an accent on the gallery, never a precondition for it — an empty aggregate is the fallback */
  }
}

async function onToggleMark(mediaId: string) {
  const key = props.scopeKey
  if (!key) return
  await toggleItemFavorite(mediaId, props.scopeId, props.node.id, key)
}

watch(
  () => [props.node.id, props.scopeKey] as const,
  () => {
    clearItemFavorites()
    markFilter.value = 'all' // a filter is about the Memory you are in, not a mode you carry into the next
    void loadMarks()
  },
  { immediate: true },
)

// The live-gallery core (uc.memory.gallery) — listed media, the thumbnail pipeline (cr-0150) + optimistic tiles,
// the cr-0107 live poll (load/merge/upsert), the justified layout, and the optimistic-delete/undo — extracted into
// `useMediaGalleryLive` (cr-0151). `onSettle` runs the auto-date then auto-location reconcile after a list refresh.
const {
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
} = useMediaGalleryLive({
  scopeId: () => props.scopeId,
  node: () => props.node,
  memoryKey: () => props.memoryKey,
  onSettle: () => settle(),
  markFilter: () => markFilterPredicate.value,
})
// The Trash tab (cr-0110/0111) lives in `useMediaTrash` (cr-0147): its listing, thumbnails, batch select, the
// restore/purge menu + confirm. A restore/purge lifts the gallery tombstone; a restore re-lists the gallery.
const {
  trashedItems,
  trashThumbs,
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
} = useMediaTrash({
  scopeId: () => props.scopeId,
  node: () => props.node,
  memoryKey: () => props.memoryKey,
  reloadGallery: () => void load(),
  liftTombstone,
})
// The persisted video signal (#104/#105): a media is a video iff its sealed `mediaType` (in the listed tier) is
// `video/*`. Unseal once per item on a list change (not per render), so the tile draws its play badge with no
// extra fetch. Pre-field media carry no type → no badge until the lazy backfill lands one (accepted, ADR 0047).
function videoIdSet(list: readonly MediaItem[]): Set<string> {
  const key = props.memoryKey
  const set = new Set<string>()
  if (!key) return set
  for (const it of list) if (isVideoMediaType(unsealMeta(props.scopeId, key, it)?.mediaType)) set.add(it.mediaId)
  return set
}
const galleryVideoIds = computed(() => videoIdSet(items.value))
const trashVideoIds = computed(() => videoIdSet(trashedItems.value))

const activeTab = ref<'gallery' | 'chapters' | 'beitraege' | 'trash'>('gallery') // local (the #fragment carries the tree path, cr-0031)
const fileInput = ref<HTMLInputElement | null>(null)

// --- Memory date (cr-0067, uc.memory.date) → extracted into `useMemoryDate` (cr-0145). The effective range
// is sealed on the NODE under K_mem (operator-blind) and shown in the header; `auto` materializes from the
// Memory's own media and self-heals on open + after an upload batch (via `reconcileAuto`, called at the settle
// points below). Props are passed as getters so the composable stays reactive to them.
const { dateEditorOpen, storedDate, autoRange, dateLabel, datedChapters, onDateSave, reconcileAuto } = useMemoryDate({
  scopeId: () => props.scopeId,
  node: () => props.node,
  memoryKey: () => props.memoryKey,
  chapters: () => props.chapters,
  items,
  onMetaUpdated: (payload) => emit('metaUpdated', payload),
})

// --- Memory location (cr-0069, uc.memory.place) → extracted into `useMemoryLocation` (cr-0146). A deliberate
// per-Memory named place sealed in the SAME node `wrappedMeta` blob as the date (read-modify-write); the map +
// geocoding gate behind a personal two-stage opt-in (ADR 0016/0018). `reconcileAutoLocation` is called at the
// settle points below. Props are passed as getters so the composable stays reactive to them.
const {
  locationEditorOpen,
  consentDialogOpen,
  storedLocation,
  gpsPoints,
  medoid,
  locationLabel,
  locationResolved,
  openLocationEditor,
  enableMaps,
  onLocationSave,
  reconcileAutoLocation,
} = useMemoryLocation({
  scopeId: () => props.scopeId,
  node: () => props.node,
  memoryKey: () => props.memoryKey,
  items,
  onMetaUpdated: (payload) => emit('metaUpdated', payload),
})

// --- Memory cover (cr-0072 + cr-0073, uc.memory.cover) → extracted into `useMemoryCover` (cr-0146). A chosen
// image + a 16:9 crop, sealed in the SAME node `wrappedMeta` blob as the date/location (read-modify-write). The
// source is a Memory photo (reusing its decrypted `thumbs` entry) or an uploaded standalone blob (decrypted on
// demand; the composable owns that object-URL's lifecycle incl. unmount cleanup). Props passed as getters.
const {
  coverEditorOpen,
  coverEditorMedia,
  storedCover,
  coverSrc,
  coverExistingSrc,
  coverMediaId,
  onSetCoverFromPhoto,
  openCoverEditor,
  closeCoverEditor,
  onCoverSave,
} = useMemoryCover({
  scopeId: () => props.scopeId,
  node: () => props.node,
  memoryKey: () => props.memoryKey,
  thumbs,
  onMetaUpdated: (payload) => emit('metaUpdated', payload),
  noSpaceMessage: () => t('media.uploadNoSpace'),
})

/** The reconcile settle point `useMediaGalleryLive` calls after each list refresh: auto-date first, then
 *  auto-location (which reads the date-reconciled wrappedMeta patched in place → read-modify-write safe). */
async function settle() {
  await reconcileAuto()
  await reconcileAutoLocation()
}

// The Beiträge tab (the media organizer, MemoryBuckets.vue) is ALWAYS available — it's where you create sub-buckets
// and file your own uploads (cr-0113). Its own state/loading live in that component; here we only host it + wire the
// lightbox/upload/reload it emits.
const showBeitraege = computed(() => true)

/** The organizer changed buckets/media (create/rename/delete/move) — re-list so each item's ownerKey/bucketId refresh. */
function onBucketChanged() { void load() }
/** Upload from the organizer — reuses the Memory upload; the caller's primary materializes on complete (moving into a
 *  specific sub-bucket after upload is a follow-up; for now uploads land in the primary, then move them in). */
function onBucketUpload() { triggerUpload() }

// --- upload trigger (uc.memory.upload). The Safari Photos picker populates input.files LATE / across repeated
// picks with empty MIME types, so a pick runs through `collectPickedFiles` — the shared E3 window that re-reads
// the input, merges by signature and releases it afterwards. It lives in the queue module because the
// contributor's picker in ShareView needs the identical behaviour; the window's rules (and why the deferred
// clear is load-bearing) are documented there, once.
let cancelPickWindow: (() => void) | null = null

function triggerUpload() {
  fileInput.value?.click()
}

function onFilesPicked() {
  const input = fileInput.value
  if (!input) return
  cancelPickWindow?.() // a fresh pick supersedes whatever the previous window was still waiting for
  cancelPickWindow = collectPickedFiles(input, enqueueBatch)
}

onBeforeUnmount(() => cancelPickWindow?.())

function enqueueBatch(picked: File[]) {
  const key = props.memoryKey
  if (!key) return
  const targetNodeId = props.node.id
  enqueueUploads(
    { scopeId: props.scopeId, nodeId: targetNodeId, memoryKey: key },
    picked.map((file) => ({ file, mediaType: inferMediaType(file) })),
    {
      label: props.node.name,
      // cr-0111: reconcile each file's optimistic tile the moment IT finishes — a targeted live poll lists just
      // the newly-attached media so its tile flips to the real thumbnail without waiting for the whole batch.
      onFileDone: () => { if (props.node.id === targetNodeId) void pollMedia() },
      onComplete: () => { if (props.node.id === targetNodeId) void load() }, // final settle (date/location self-heal)
    },
  )
}

// The Trash tab loads lazily on open — no extra list on every Memory open.
watch(activeTab, (tab) => { if (tab === 'trash') void loadTrash() })

// On a Memory/chapter switch: reset the tab to the gallery + drop the previous Memory's trash state. (The gallery
// grid + live-poll lifecycle is owned by `useMediaGalleryLive`, which watches the same node/key + tears down itself.)
watch(
  () => [props.node.id, props.memoryKey] as const,
  () => {
    activeTab.value = 'gallery'
    resetTrash() // cr-0110/0111: drop the previous Memory's trash listing/thumbs/selection
  },
  { immediate: true },
)
</script>

<template>
  <div class="memory-route-surface memory-detail">
    <header class="gallery-header">
      <button
        type="button"
        class="memory-cover-preview"
        :class="{ 'is-empty': !coverSrc }"
        :disabled="!memoryKey"
        :aria-label="storedCover ? t('memory.cover.edit') : t('memory.cover.set')"
        :title="storedCover ? t('memory.cover.edit') : t('memory.cover.set')"
        @click="openCoverEditor"
      >
        <MemoryCoverImage :src="coverSrc" :crop="storedCover?.crop ?? null" :alt="node.name" />
        <span class="memory-cover-preview-hint">
          <span class="pi pi-image" />
          <span>{{ storedCover ? t('memory.cover.edit') : t('memory.cover.set') }}</span>
        </span>
      </button>
      <!-- Body column beside the cover: row 1 = title + actions, row 2 = count, row 3 = date + location. Because
           each row spans the body width (not squeezed by the actions, which live only in row 1), the date +
           location chips sit side by side at any width. -->
      <div class="gallery-header-body">
        <div class="gallery-header-titlerow">
          <h1 class="gallery-title">{{ node.name }}</h1>
          <div class="gallery-header-actions">
        <Button
          v-tooltip.bottom="t('media.upload')"
          type="button"
          :aria-label="t('media.upload')"
          icon="pi pi-cloud-upload"
          :disabled="!memoryKey"
          @click="triggerUpload"
        />
        <input
          ref="fileInput"
          type="file"
          multiple
          accept="image/*,video/*,.heic,.heif,.mov,.m4v,.mkv,.avi,.tif,.tiff"
          class="visually-hidden"
          @change="onFilesPicked"
        />
        <Button
          v-if="shareable"
          v-tooltip.bottom="t('share.share')"
          type="button"
          :aria-label="t('share.share')"
          icon="pi pi-share-alt"
          severity="secondary"
          text
          :disabled="!memoryKey"
          @click="$emit('share')"
        />
        <!-- The uploader (drop-box) link is its own action + own dialog (no role switcher) — capability-gated. -->
        <Button
          v-if="canUploadShare"
          v-tooltip.bottom="t('share.uploadRequest')"
          type="button"
          :aria-label="t('share.uploadRequest')"
          icon="pi pi-user-plus"
          severity="secondary"
          text
          :disabled="!memoryKey"
          @click="$emit('shareUpload')"
        />
        <Button
          v-tooltip.bottom="favorite ? t('memory.unfavorite') : t('memory.favorite')"
          type="button"
          class="memory-detail-fav"
          :class="{ 'is-favorite': favorite }"
          :aria-label="favorite ? t('memory.unfavorite') : t('memory.favorite')"
          :icon="favorite ? 'pi pi-heart-fill' : 'pi pi-heart'"
          severity="secondary"
          text
          @click="$emit('toggleFavorite')"
        />
        <Button
          v-tooltip.bottom="t('memory.rename')"
          type="button"
          :aria-label="t('memory.rename')"
          icon="pi pi-pencil"
          severity="secondary"
          text
          @click="$emit('rename')"
        />
        <Button
          v-tooltip.bottom="t('memory.delete')"
          type="button"
          :aria-label="t('memory.delete')"
          icon="pi pi-trash"
          severity="danger"
          text
          @click="$emit('delete')"
        />
          </div>
        </div>
        <span class="gallery-count">{{ t('media.itemCount', { count: items.length }, items.length) }}</span>
        <div class="memory-meta-row">
          <button
            type="button"
            class="memory-date-chip"
            :class="{ 'is-empty': !dateLabel }"
            :disabled="!memoryKey"
            :aria-label="t('memory.date.edit')"
            :title="t('memory.date.edit')"
            @click="dateEditorOpen = true"
          >
            <span class="pi pi-calendar" />
            <span>{{ dateLabel || t('memory.date.noDate') }}</span>
          </button>
          <button
            type="button"
            class="memory-date-chip memory-location-chip"
            :class="{ 'is-empty': !locationResolved }"
            :disabled="!memoryKey"
            :aria-label="locationResolved ? t('memory.place.edit') : t('memory.place.set')"
            :title="locationResolved ? t('memory.place.edit') : t('memory.place.set')"
            @click="openLocationEditor"
          >
            <span class="pi pi-map-marker" />
            <span>{{ locationLabel || t('memory.place.set') }}</span>
          </button>
        </div>
      </div>
    </header>

    <!-- Tabbed detail (cr-0061, ported from the design source): Gallery (the media grid) + Chapters (the
         nested Memories). Other design tabs — journal / map / playlists — arrive with their features. -->
    <Tabs v-model:value="activeTab" class="memory-tabs">
      <TabList>
        <Tab value="gallery">{{ t('memory.gallery') }}</Tab>
        <Tab value="chapters">
          {{ t('memory.chapters') }}<span v-if="chapters && chapters.length" class="tab-count">{{ chapters.length }}</span>
        </Tab>
        <Tab v-if="showBeitraege" value="beitraege">{{ t('memory.buckets.title') }}</Tab>
        <Tab value="trash">{{ t('memory.trash.title') }}</Tab>
      </TabList>
      <TabPanels>
        <TabPanel value="gallery">
          <div v-if="status === 'loading' && !hasContent" class="media-grid-placeholder">
            <span class="pi pi-spin pi-spinner" />
            <span>{{ t('media.loading') }}</span>
          </div>
          <p v-else-if="status === 'error'" class="veyq-empty">{{ t('media.unavailable') }}</p>
          <p v-else-if="!hasContent" class="veyq-empty">{{ t('media.empty') }}</p>
          <template v-else>
            <div v-if="items.length" class="gallery-toolbar">
              <button
                class="gallery-sort-toggle"
                type="button"
                :aria-label="sortDir === 'newest' ? t('media.sortNewest') : t('media.sortOldest')"
                :title="sortDir === 'newest' ? t('media.sortNewest') : t('media.sortOldest')"
                @click="sortDir = sortDir === 'newest' ? 'oldest' : 'newest'"
              >
                <span class="pi" :class="sortDir === 'newest' ? 'pi-sort-amount-down' : 'pi-sort-amount-up-alt'" />
                <span>{{ sortDir === 'newest' ? t('media.sortNewest') : t('media.sortOldest') }}</span>
              </button>
              <!-- The mark filter. In a personal scope there is nobody to share a mark with, so it is HIDDEN
                   rather than disabled (uc.memory.gallery E4) — a greyed-out control invites the question
                   "how do I enable this?", which has no answer. Its presence is also half of what tells the
                   user that marks here are seen by the others (ADR 0068). -->
              <div v-if="!personalScope" class="gallery-mark-filter" role="group" :aria-label="t('media.marks.filterLabel')">
                <button
                  v-for="opt in (['all', 'everyone', 'mine'] as const)"
                  :key="opt"
                  type="button"
                  class="gallery-mark-filter-option"
                  :class="{ 'is-active': markFilter === opt }"
                  :aria-pressed="markFilter === opt"
                  @click="markFilter = opt"
                >
                  {{ t(`media.marks.filter.${opt}`) }}
                </button>
              </div>
            </div>
            <!-- E5: a filter that empties the grid names itself, so it never reads as a broken Memory. -->
            <p v-if="markFilter !== 'all' && !layoutItems.length" class="veyq-empty">
              {{ markFilter === 'mine' ? t('media.marks.emptyMine') : t('media.marks.emptyEveryone') }}
            </p>
            <MediaGrid v-else :items="layoutItems" :aria-label="t('media.gallery')">
              <template #tile="{ tile, item }">
                <!-- optimistic in-grid upload tile (cr-0074): the shared OptimisticTile (preview + progress / retry) -->
                <OptimisticTile
                  v-if="item.kind === 'optimistic'"
                  :tile="item.optimistic"
                  :style="{
                    transform: `translate(${tile.left}px, ${tile.top}px)`,
                    width: `${tile.renderedWidth}px`,
                    height: `${tile.renderedHeight}px`,
                  }"
                  @retry="retryFile"
                />
                <MediaTile
                  v-else
                  :data-media-id="item.media.mediaId"
                  :style="{
                    transform: `translate(${tile.left}px, ${tile.top}px)`,
                    width: `${tile.renderedWidth}px`,
                    height: `${tile.renderedHeight}px`,
                  }"
                  :src="thumbs.get(item.media.mediaId)"
                  :has-derivatives="hasDerivatives(item.media)"
                  :is-video="galleryVideoIds.has(item.media.mediaId)"
                  :open-label="t('media.openItem', { index: item.index + 1 })"
                  :markable="!!scopeKey"
                  :mark-count="personalScope ? 0 : markCount(item.media.mediaId)"
                  :marked-by-me="isMarkedByMe(item.media.mediaId)"
                  :mark-label="isMarkedByMe(item.media.mediaId) ? t('media.marks.unmark') : t('media.marks.mark')"
                  @open="openLightboxAt(item.index)"
                  @toggle-mark="onToggleMark(item.media.mediaId)"
                />
              </template>
            </MediaGrid>
          </template>
        </TabPanel>

        <TabPanel value="chapters">
          <!-- Chapters as a MemoryCard grid + a create card, like the browse Memories section (design source).
               Each chapter opens to its own gallery; its media is sealed under the chapter's own K_mem. -->
          <div class="chapters-toolbar">
            <ViewModeToggle />
          </div>
          <div
            class="memory-card-grid"
            :class="{ 'is-list': viewMode === 'list' }"
            :aria-label="t('memory.chapters')"
          >
            <MemoryCard
              v-for="{ node: chapter, date } in datedChapters"
              :key="chapter.id"
              :memory="chapter"
              :date="date"
              :favoritable="false"
              @open="$emit('openChapter', $event)"
            />
            <button
              class="veyq-card veyq-card-interactive memory-create-card memory-create-card-memory"
              type="button"
              :disabled="!memoryKey"
              @click="$emit('createChapter')"
            >
              <span class="pi pi-plus" />
              <strong>{{ t('memory.addChapter') }}</strong>
            </button>
          </div>
        </TabPanel>

        <!-- Beiträge tab (uc.memory.buckets, cr-0113): the media ORGANIZER — a hierarchy picker (your primary +
             sub-buckets), create/rename/delete sub-buckets, move media in, upload into a bucket. Ported from the
             legacy MemoryAssetBuckets UX. Always present; the primary is virtual until a sub-bucket/upload materializes it. -->
        <TabPanel v-if="showBeitraege" value="beitraege">
          <MemoryBuckets
            :scope-id="scopeId"
            :node-id="node.id"
            :memory-key="memoryKey"
            :media="items"
            :thumbs="thumbs"
            @open-lightbox="openBucketLightbox"
            @changed="onBucketChanged"
            @upload-bucket="onBucketUpload"
          />
        </TabPanel>

        <!-- Trash tab (uc.memory.delete-media, cr-0110/0111): the Memory's soft-deleted media in the SAME justified
             MediaGrid, with a per-tile checkbox (batch select) + a 3-dot menu (restore / purge). Lazy on open. -->
        <TabPanel value="trash">
          <div v-if="trashStatus === 'loading'" class="media-grid-placeholder">
            <span class="pi pi-spin pi-spinner" />
            <span>{{ t('media.loading') }}</span>
          </div>
          <p v-else-if="trashStatus === 'error'" class="veyq-empty">{{ t('media.unavailable') }}</p>
          <p v-else-if="trashedItems.length === 0" class="veyq-empty">{{ t('memory.trash.empty') }}</p>
          <template v-else>
            <div class="memory-trash-toolbar">
              <span class="memory-trash-hint">{{ t('memory.trash.hint') }}</span>
              <!-- Selection bar — always visible; actions enable with a selection (cr-0152, the Beiträge mechanic). -->
              <div class="memory-trash-batch">
                <span class="memory-trash-batch-count">{{ selectedTrashLabel }}</span>
                <button type="button" class="veyq-text-button" @click="toggleSelectAllTrash">
                  {{ allTrashSelected ? t('memory.trash.deselectAll') : t('memory.trash.selectAllVisible') }}
                </button>
                <button type="button" class="bucket-action-button" :disabled="!selectedTrash.size" @click="restoreSelected">
                  <span class="pi pi-replay" /><span class="bucket-action-label">{{ t('memory.trash.restore') }}</span>
                </button>
                <button type="button" class="bucket-action-button bucket-action-danger" :disabled="!selectedTrash.size" @click="purgeSelected">
                  <span class="pi pi-trash" /><span class="bucket-action-label">{{ t('memory.trash.purge') }}</span>
                </button>
              </div>
            </div>
            <MediaGrid :items="trashLayoutItems" :aria-label="t('memory.trash.title')">
              <template #tile="{ tile, item }">
                <MediaTile
                  class="memory-trash-tile"
                  :style="{
                    transform: `translate(${tile.left}px, ${tile.top}px)`,
                    width: `${tile.renderedWidth}px`,
                    height: `${tile.renderedHeight}px`,
                  }"
                  :src="trashThumbs.get(item.media.mediaId)"
                  :has-derivatives="hasDerivatives(item.media)"
                  :is-video="trashVideoIds.has(item.media.mediaId)"
                  :open-label="t('media.openItem', { index: item.index + 1 })"
                  :selected="selectedTrash.has(item.media.mediaId)"
                  @open="toggleTrashSelect(item.media.mediaId)"
                >
                  <template #overlay>
                    <label class="media-tile-check" :class="{ 'is-on': selectedTrash.has(item.media.mediaId) }">
                      <input type="checkbox" :checked="selectedTrash.has(item.media.mediaId)" :aria-label="t('media.openItem', { index: item.index + 1 })" @click.stop @change="toggleTrashSelect(item.media.mediaId)" />
                      <span class="pi pi-check" aria-hidden="true" />
                    </label>
                    <button
                      type="button"
                      class="media-tile-menu"
                      :aria-label="t('common.more')"
                      @click.stop="openTrashMenu($event, item.media.mediaId)"
                    >
                      <span class="pi pi-ellipsis-v" />
                    </button>
                  </template>
                </MediaTile>
              </template>
            </MediaGrid>
            <Menu ref="trashMenu" :model="trashMenuModel" popup />
          </template>
        </TabPanel>
      </TabPanels>
    </Tabs>

    <MediaLightbox
      v-if="lightboxIndex !== null && memoryKey"
      :scope-id="scopeId"
      :memory-key="memoryKey"
      :items="lightboxItems"
      :start-index="lightboxIndex"
      :memory-name="node.name"
      :cover-media-id="coverMediaId"
      :suspend-keys="coverEditorOpen"
      @close="lightboxIndex = null"
      @updated="onCapturedAtUpdated"
      @set-cover="onSetCoverFromPhoto"
      @delete="onDeleteFromLightbox"
    />

    <MemoryDateEditor
      v-if="dateEditorOpen && memoryKey"
      :date="storedDate"
      :auto-range="autoRange"
      @save="onDateSave"
      @close="dateEditorOpen = false"
    />

    <MemoryLocationEditor
      v-if="locationEditorOpen && memoryKey"
      :location="storedLocation"
      :medoid="medoid"
      :points="gpsPoints"
      @save="onLocationSave"
      @close="locationEditorOpen = false"
    />

    <MemoryCoverDialog
      v-if="coverEditorOpen && memoryKey"
      :cover="storedCover"
      :existing-src="coverExistingSrc"
      :media-source="coverEditorMedia"
      :has-media="items.length > 0"
      @save="onCoverSave"
      @close="closeCoverEditor"
    />

    <Dialog
      :visible="consentDialogOpen"
      :header="t('memory.place.consentTitle')"
      modal
      class="memory-location-consent"
      @update:visible="consentDialogOpen = false"
    >
      <p>{{ t('memory.place.consentBody') }}</p>
      <div class="veyq-dialog-action-row">
        <Button type="button" :label="t('common.cancel')" icon="pi pi-times" severity="secondary" outlined @click="consentDialogOpen = false" />
        <Button type="button" :label="t('memory.place.consentEnable')" icon="pi pi-map" @click="enableMaps" />
      </div>
    </Dialog>

    <!-- Confirm the irreversible purge (cr-0111): permanently deletes the trashed media NOW (bypasses the 30-day retention). -->
    <Dialog
      :visible="purgeConfirm !== null"
      :header="t('memory.trash.purgeConfirmTitle')"
      modal
      class="memory-trash-purge-dialog"
      :closable="!purgeBusy"
      :close-on-escape="!purgeBusy"
      @update:visible="purgeConfirm = null"
    >
      <p>{{ t('memory.trash.purgeConfirmBody', { count: purgeConfirm?.ids.length ?? 0 }, purgeConfirm?.ids.length ?? 0) }}</p>
      <div class="veyq-dialog-action-row">
        <Button type="button" :label="t('common.cancel')" icon="pi pi-times" severity="secondary" outlined :disabled="purgeBusy" @click="purgeConfirm = null" />
        <Button type="button" :label="t('memory.trash.purge')" icon="pi pi-trash" severity="danger" :loading="purgeBusy" @click="confirmPurge" />
      </div>
    </Dialog>
  </div>
</template>

<style scoped>
.gallery-mark-filter {
  display: inline-flex;
  gap: 2px;
  padding: 2px;
  border-radius: var(--app-radius-sm);
  background: var(--app-control-bg);
}
.gallery-mark-filter-option {
  padding: 4px 10px;
  border: 0;
  border-radius: var(--app-radius-sm);
  background: transparent;
  color: var(--app-muted);
  font-size: 0.8125rem;
  cursor: pointer;
}
.gallery-mark-filter-option.is-active {
  background: var(--app-surface);
  color: var(--app-text);
}

.gallery-toolbar {
  display: flex;
  justify-content: flex-end;
  margin-bottom: 0.6rem;
}
/* Icon + label pill (NOT the icon-only .veyq-icon-button utility, which forces a square grid-centered box and
   stacks the icon over the label). Matches the header date/place chips for a consistent toolbar affordance. */
.gallery-sort-toggle {
  display: inline-flex;
  align-items: center;
  gap: 0.4rem;
  padding: 0.35rem 0.7rem;
  border: 1px solid var(--app-line);
  border-radius: 999px;
  background: var(--app-surface);
  color: var(--app-text);
  font-size: 0.86rem;
  cursor: pointer;
}
.gallery-sort-toggle:hover {
  background: var(--app-hover);
}
.gallery-sort-toggle:focus-visible {
  outline: 2px solid var(--app-focus);
  outline-offset: 2px;
}
.gallery-sort-toggle .pi {
  font-size: 0.9rem;
}
/* Query container on the detail surface so the header responds to its OWN width (not the viewport — the sidebar
   eats ~230px, so a viewport media query stacks too late). */
.memory-detail {
  container: memory-detail-surface / inline-size;
}
/* Cover on the left, the body column beside it. */
.gallery-header {
  display: flex;
  align-items: flex-start;
  gap: 1rem;
  padding-bottom: 1.15rem;
  border-bottom: 1px solid var(--app-line);
}
/* The body column: row 1 = title + actions, row 2 = count, row 3 = date · location. Grows to fill; each row
   spans its full width, so the count + meta rows are never squeezed by the action bar (which lives in row 1). */
.gallery-header-body {
  display: flex;
  flex-direction: column;
  gap: 0.3rem;
  flex: 1 1 auto;
  min-width: 0;
}
/* Row 1: title on the left, the action bar pushed to the right (wraps under the title only on a tiny header). */
.gallery-header-titlerow {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  justify-content: space-between;
  gap: 0.5rem 1rem;
}

/* The Memory cover (cr-0072): a 16:9 tile showing the chosen, cropped cover (or the placeholder). It IS the
   "set/edit cover" button — clicking it opens the crop dialog. A hint label sits over it (always when empty,
   on hover once a cover is set). */
.memory-cover-preview {
  position: relative;
  flex: 0 0 auto;
  width: 180px;
  aspect-ratio: 16 / 9;
  padding: 0;
  overflow: hidden;
  border: 1px solid var(--app-line);
  border-radius: var(--app-radius-sm);
  background: var(--app-surface);
  cursor: pointer;
}
.memory-cover-preview:disabled {
  opacity: 0.6;
  cursor: default;
}
.memory-cover-preview-hint {
  position: absolute;
  inset-inline: 0;
  bottom: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 0.35rem;
  padding: 0.3rem;
  background: color-mix(in srgb, var(--app-backdrop-strong) 62%, transparent);
  color: #fff;
  font-size: 0.8rem;
  font-weight: 600;
  opacity: 0;
  transition: opacity 0.15s ease;
}
.memory-cover-preview.is-empty .memory-cover-preview-hint,
.memory-cover-preview:hover .memory-cover-preview-hint,
.memory-cover-preview:focus-visible .memory-cover-preview-hint {
  opacity: 1;
}
.memory-cover-preview-hint .pi {
  font-size: 0.85rem;
}
@container memory-detail-surface (max-width: 460px) {
  .memory-cover-preview {
    width: 100%;
    max-width: 260px;
  }
}

/* Date + location under the title, side by side (cr-0067/0069). A light metadata row rather than bordered pills:
   each is a borderless button (accent icon + label) that opens its editor, with a subtle hover so it still reads
   as clickable. The "set / pending / no-location" placeholder states render muted via `.is-empty`. */
.memory-meta-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.2rem 0.4rem;
  /* Pull left by the chip's horizontal padding so the first chip's CONTENT (not its padded box) starts flush with
     the title + count above — the chips still keep their hover/tap padding. */
  margin-left: -0.45rem;
}
.memory-date-chip {
  display: inline-flex;
  align-items: center;
  gap: 0.4rem;
  padding: 0.25rem 0.45rem;
  border: none;
  border-radius: var(--app-radius-sm);
  background: transparent;
  color: var(--app-text);
  font-size: 0.9rem;
  font-weight: 600;
  white-space: nowrap; /* the label (e.g. "Kein Ort erkannt") stays on one line, never breaks mid-phrase */
  cursor: pointer;
  transition: background 0.15s ease, color 0.15s ease;
}
.memory-date-chip:hover:not(:disabled) {
  background: var(--app-hover);
}
.memory-date-chip:disabled {
  opacity: 0.6;
  cursor: default;
}
.memory-date-chip.is-empty {
  color: var(--app-muted);
  font-weight: 500;
}
.memory-date-chip .pi {
  font-size: 0.95rem;
  color: var(--app-accent);
}
.memory-date-chip.is-empty .pi {
  color: var(--app-muted);
}
.gallery-header-actions {
  display: flex;
  flex: 0 0 auto;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 0.5rem;
}
/* Genuinely narrow header (mobile): stack the cover above the body so the body (title/count/meta) gets the full
   width. The action bar stays in the title row; the title font eases down. */
@container memory-detail-surface (max-width: 540px) {
  .gallery-header {
    flex-direction: column;
  }
  .gallery-title {
    font-size: 1.5rem;
  }
}
.memory-detail-fav.is-favorite :deep(.p-button-icon) {
  color: var(--app-favorite);
}

/* The Chapters tab's view toggle, right-aligned above the card grid. */
.chapters-toolbar {
  display: flex;
  justify-content: flex-end;
  margin-bottom: 0.75rem;
}
/* A small count next to the Chapters tab label. */
.tab-count {
  margin-left: 0.4rem;
  padding: 0 0.4rem;
  border-radius: 999px;
  background: var(--app-control-bg);
  color: var(--app-muted);
  font-size: 0.75rem;
  font-weight: 700;
}
/* Beiträge tab (cr-0113): the uploader picker — a row of chips (label + count), the active one accented. */
.beitraege-picker {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
  margin-bottom: 1rem;
}
.beitraege-chip {
  display: inline-flex;
  align-items: center;
  gap: 0.4rem;
  padding: 0.3rem 0.7rem;
  border: 1px solid var(--app-line);
  border-radius: 999px;
  background: transparent;
  color: inherit;
  font: inherit;
  cursor: pointer;
}
.beitraege-chip:hover {
  background: var(--app-control-bg);
}
.beitraege-chip.is-active {
  border-color: color-mix(in srgb, var(--app-accent) 55%, var(--app-line));
  background: color-mix(in srgb, var(--app-accent) 12%, transparent);
}
.beitraege-chip-count {
  padding: 0 0.35rem;
  border-radius: 999px;
  background: var(--app-control-bg);
  color: var(--app-muted);
  font-size: 0.75rem;
  font-weight: 700;
}
/* The justified-rows gallery (cr-0059): aspect-preserving tiles laid out by calculateJustifiedMediaLayout into
   rows that fill the container width (the per-tile aspect ratio comes from the E2EE-sealed dimensions). The
   container is position:relative with an explicit height; each tile is absolutely positioned via a transform +
   its rendered width/height. The tile-inner classes (.media-grid-tile-media / -placeholder / -icon) are the
   design source's (media.css). */
.gallery-grid {
  position: relative;
  width: 100%;
}
/* Gallery tiles are the shared MediaTile (cr-0125) — its wrapper/fit/icon styles live in the component + global
   memory.css. The optimistic upload tile's own styles live in the shared OptimisticTile component (cr-0074). */

/* --- Trash tab (cr-0110/0111): the same gallery grid, dimmed tiles + select-check + 3-dot menu --- */
.memory-trash-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 0.5rem;
  margin-bottom: 0.75rem;
}
.memory-trash-hint {
  color: var(--app-muted);
  font-size: 0.85rem;
}
/* The selection bar mirrors the Beiträge .bucket-selection-bar exactly (cr-0152): muted un-bolded count,
   the stock .veyq-text-button select-all toggle, and the shared .bucket-action-button actions (memory.css). */
.memory-trash-batch {
  display: inline-flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 0.5rem;
}
.memory-trash-batch-count {
  color: var(--app-muted);
  font-size: 0.85rem;
}
/* A trashed tile IS the shared MediaTile (cr-0125), just with the trashed media dimmed (the check/menu/outline are
   the standard `.media-tile*`). `:deep` reaches the image inside the child component. */
.memory-trash-tile :deep(.media-tile-img) {
  opacity: 0.72; /* trashed media reads as dimmed */
  transition: opacity 0.15s ease;
}
.memory-trash-tile.is-selected :deep(.media-tile-img) {
  opacity: 0.92;
}
</style>
