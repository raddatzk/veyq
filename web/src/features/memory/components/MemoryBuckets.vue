<script setup lang="ts">
// The "Beiträge" organizer (uc.memory.buckets, en.memory.buckets, cr-0113 · action-bar redesign cr-0122) — ported
// from the pre-E2EE MemoryAssetBuckets UX, rewired to the operator-blind data layer. A single-column view: a
// content-width hierarchy Select (primary bucket per uploader → its sub-buckets), the bucket actions as explicit
// buttons beside it (upload / create sub-bucket / rename / delete — shown only where they apply), the reused
// MediaGrid filtered to the active bucket with a per-tile checkbox (top-left) + 3-dot (top-right), and a selection
// bar with a select-all toggle + visible Move/Delete. Delete is a straight soft-delete to trash with a local undo
// bar (no confirm modal). The primary bucket is VIRTUAL until the first sub-bucket or upload materializes it (the
// server's lazy ensurePrimaryBucket). Names are sealed under K_mem — the client unseals; the server never sees one.
import { ref, computed, watch, onBeforeUnmount } from 'vue'
import { useI18n } from 'vue-i18n'
import Select from 'primevue/select'
import Menu from 'primevue/menu'
import type { MenuItem } from 'primevue/menuitem'
import Dialog from 'primevue/dialog'
import MediaGrid from './MediaGrid.vue'
import MediaTile from './MediaTile.vue'
import { galleryDisplayAspectRatio } from '../mediaGridLayout'
import {
  openBucketName, unsealMeta, originalMediaType, isVideoMediaType, hasDerivatives, type MediaItem, type BucketItem,
} from '../media'
import { useMemoryView } from '../source'
import { useToast } from '../../../shared/state/useToast'
import { useUndo } from '../../../shared/state/useUndo'

const props = defineProps<{
  scopeId: string
  nodeId: string
  memoryKey: Uint8Array | null
  media: MediaItem[] // all live media in the Memory (each carrying ownerKey/bucketId)
  thumbs: Map<string, string> // mediaId → decrypted thumbnail object-url (owned by the parent gallery)
}>()
const emit = defineEmits<{
  changed: [] // a bucket/media mutation landed — the parent should reload media (ownerKey/bucketId change)
  openLightbox: [{ items: MediaItem[]; index: number }]
  uploadBucket: [string | null] // open the upload scoped to this bucket (null = your primary)
}>()

const { t, locale } = useI18n()
const toast = useToast()
// Every read and write of the organizer goes through the Memory detail's data source (`../source.ts`, inbox #401 S4):
// its buckets door — the scope's buckets and members, the tree's ancestor keys. A member's only; the detail mounts this
// where the capabilities AND the source say so, and outside a detail, or on a source without the door, it throws rather
// than reach for the member's API itself.
const buckets = (() => {
  const door = useMemoryView().source.buckets
  if (!door) throw new Error('MemoryBuckets mounted without a buckets door')
  return door
})()
const SELF_PRIMARY = '__veyq_self_primary__'

type BucketVM = {
  id: string
  ownerKey: string
  parentBucketId: string | null
  isPrimary: boolean
  displayName: string
  mediaCount: number
  canManage: boolean // you may create/rename/delete/move here (your own bucket)
  virtual: boolean // client-only placeholder for your not-yet-materialized primary
}
type BucketRow = BucketVM & { depth: number }

const rawBuckets = ref<BucketVM[]>([])
const status = ref<'idle' | 'loading' | 'ready' | 'error'>('idle')
const activeBucketId = ref('')
const selected = ref<Set<string>>(new Set())

// The per-tile 3-dot is the only ellipsis menu left — the bucket + selection menus are now explicit buttons (cr-0122).
const mediaMenu = ref<InstanceType<typeof Menu> | null>(null)
const menuMediaId = ref<string | null>(null)

const bucketDialog = ref<{ open: boolean; mode: 'create' | 'rename'; name: string; saving: boolean; error: string }>(
  { open: false, mode: 'create', name: '', saving: false, error: '' },
)
const deleteBucketDialog = ref<{ open: boolean; typed: string; saving: boolean; error: string }>(
  { open: false, typed: '', saving: false, error: '' },
)
const moveDialog = ref<{ open: boolean; target: string; saving: boolean; error: string }>(
  { open: false, target: '', saving: false, error: '' },
)
// A soft-delete just landed — an undo-toast offers to restore it until it auto-dismisses (cr-0157/ADR 0039). The
// soft-delete is eager, so the window elapsing commits nothing; only `undoDelete` (restore) is a real action.
const undo = useUndo<{ ids: string[]; count: number }>({ timeoutMs: 8000 })
const dropActive = ref(false)

// --- load + label resolution ------------------------------------------------

/** The keys of this node's ancestors, nearest first, from the loaded tree: a guest of a link rooted ABOVE this node
 *  (a shared folder, a shared Memory for a chapter) named itself under that root's key (`openBucketName`). */
function ancestorKeys(): Uint8Array[] {
  return buckets.ancestorKeys(props.nodeId)
}

function labelFor(b: BucketItem, key: Uint8Array | null, members: Map<string, string>, above: Uint8Array[]): string {
  // Both a sub-bucket's name and a contributor primary's are bound to their object (ADR 0072), addressed by the
  // scope + node this panel is for; openBucketName picks the right seal from the row.
  if (!b.isPrimary || b.ownerKey.startsWith('share:')) {
    return (key && openBucketName(props.scopeId, props.nodeId, key, b, above)) || t('memory.buckets.contributor')
  }
  return members.get(b.ownerKey.slice('user:'.length)) ?? t('memory.buckets.member')
}

async function loadBuckets() {
  const key = props.memoryKey
  status.value = 'loading'
  try {
    const [raw, members] = await Promise.all([
      buckets.list(props.nodeId),
      buckets.members().catch(() => []),
    ])
    const names = new Map(members.map((m) => [m.userId, m.username]))
    const above = ancestorKeys()
    rawBuckets.value = raw.map((b) => ({
      id: b.bucketId,
      ownerKey: b.ownerKey,
      parentBucketId: b.parentBucketId ?? null,
      isPrimary: b.isPrimary,
      displayName: labelFor(b, key, names, above),
      mediaCount: b.mediaCount,
      canManage: b.ownerKey.startsWith('user:'), // your own bucket; a contributor (share:) is not yours to manage/move (yet — cr-0123)
      virtual: false,
    }))
    status.value = 'ready'
  } catch {
    status.value = 'error'
  }
}

watch(() => [props.nodeId, props.memoryKey] as const, () => { selected.value = new Set(); void loadBuckets() }, { immediate: true })

// --- the virtual own-primary + the flattened hierarchy ----------------------

// media that are yours and not yet in a materialized bucket → shown under the virtual primary before it exists.
const virtualPrimaryCount = computed(() =>
  props.media.filter((m) => (!m.ownerKey || m.ownerKey.startsWith('user:')) && !m.bucketId).length,
)

const bucketsWithSelf = computed<BucketVM[]>(() => {
  const list = rawBuckets.value
  if (list.some((b) => b.isPrimary && b.ownerKey.startsWith('user:'))) return list
  // no materialized own primary yet → synthesize a virtual one so you can create sub-buckets / upload
  return [
    {
      id: SELF_PRIMARY, ownerKey: 'user:self', parentBucketId: null, isPrimary: true,
      displayName: t('memory.buckets.you'), mediaCount: virtualPrimaryCount.value, canManage: true, virtual: true,
    },
    ...list,
  ]
})

const bucketRows = computed<BucketRow[]>(() => {
  const all = bucketsWithSelf.value
  const kids = new Map<string | null, BucketVM[]>()
  for (const b of all) (kids.get(b.parentBucketId) ?? kids.set(b.parentBucketId, []).get(b.parentBucketId)!).push(b)
  for (const arr of kids.values()) arr.sort((a, b) => a.displayName.localeCompare(b.displayName, locale.value))
  const rows: BucketRow[] = []
  const visit = (b: BucketVM, depth: number) => {
    rows.push({ ...b, depth })
    for (const c of kids.get(b.id) ?? []) visit(c, depth + 1)
  }
  for (const root of kids.get(null) ?? []) visit(root, 0)
  return rows
})

const activeBucket = computed<BucketRow | null>(
  () => bucketRows.value.find((b) => b.id === activeBucketId.value) ?? bucketRows.value[0] ?? null,
)
// Which bucket the picker opens on: the last one chosen for this node (remembered), else YOUR OWN bucket (not the
// alphabetically-first, which may be a contributor's), else the first row (cr-0122 f/u).
const BUCKET_MEMO_KEY = 'veyq.beitraege.bucket'
function preferredBucketId(rows: BucketRow[]): string {
  const remembered = localStorage.getItem(`${BUCKET_MEMO_KEY}.${props.nodeId}`)
  if (remembered && rows.some((r) => r.id === remembered)) return remembered
  const own = rows.find((r) => r.isPrimary && r.ownerKey.startsWith('user:'))
  return own?.id ?? rows[0]?.id ?? ''
}
watch(bucketRows, (rows) => {
  if (!rows.some((r) => r.id === activeBucketId.value)) activeBucketId.value = preferredBucketId(rows)
}, { immediate: true })

const selectOptions = computed(() =>
  bucketRows.value.map((b) => ({ value: b.id, displayName: b.displayName, mediaCount: b.mediaCount, isPrimary: b.isPrimary, depth: b.depth })),
)

// media of the active bucket: a materialized bucket by bucketId; the virtual primary = your unbucketed media.
const filteredMedia = computed<MediaItem[]>(() => {
  const b = activeBucket.value
  if (!b) return []
  if (b.virtual) return props.media.filter((m) => (!m.ownerKey || m.ownerKey.startsWith('user:')) && !m.bucketId)
  return props.media.filter((m) => m.bucketId === b.id)
})

// Unseal each tile's listed metadata once (operator-blind, under K_mem) for the aspect ratio (cr-0059), and its
// original's blob meta for the video signal (#105, the sealed type, inbox #397).
const layoutItems = computed(() =>
  filteredMedia.value.map((m, index) => {
    const meta = props.memoryKey ? unsealMeta(props.scopeId, props.memoryKey, m) : null
    return {
      id: m.mediaId,
      aspectRatio: galleryDisplayAspectRatio(meta?.w, meta?.h),
      media: m,
      index,
      isVideo: props.memoryKey ? isVideoMediaType(originalMediaType(props.scopeId, props.memoryKey, m)) : false,
    }
  }),
)

// where you may move the selection: any bucket sharing the active bucket's owner-root, excluding the active one.
// A member may re-bucket within one root (own or a contributor's) but never across owners (cr-0123 authz), so the
// picker only offers same-owner targets — enforcing "no cross-user move" in the UI too.
const moveTargets = computed<BucketRow[]>(() => {
  const active = activeBucket.value
  if (!active) return []
  return bucketRows.value.filter((b) => b.ownerKey === active.ownerKey && !b.virtual && b.id !== active.id)
})

// --- bucket actions ---------------------------------------------------------

function chooseBucket(id: string) {
  activeBucketId.value = id
  try { localStorage.setItem(`${BUCKET_MEMO_KEY}.${props.nodeId}`, id) } catch { /* private mode / quota — memory is best-effort */ }
  selected.value = new Set()
}

// Bucket actions render as buttons only where they apply. A member may create a sub under any primary root + rename
// or delete any sub (own or a contributor's — cr-0123); a primary itself stays read-only.
const canCreateSub = computed(() => !!activeBucket.value?.isPrimary)
const canRenameBucket = computed(() => !!activeBucket.value && !activeBucket.value.isPrimary)
const canDeleteBucket = computed(() => !!activeBucket.value && !activeBucket.value.isPrimary && !activeBucket.value.virtual)

function uploadToActive() { const b = activeBucket.value; if (b) emit('uploadBucket', b.virtual ? null : b.id) }

function openCreate() {
  if (!activeBucket.value?.isPrimary) return
  bucketDialog.value = { open: true, mode: 'create', name: '', saving: false, error: '' }
}
function openRename() {
  const b = activeBucket.value
  if (!b || b.isPrimary) return
  bucketDialog.value = { open: true, mode: 'rename', name: b.displayName, saving: false, error: '' }
}
async function submitBucketDialog() {
  const key = props.memoryKey
  const name = bucketDialog.value.name.trim()
  if (!key || !name) return
  bucketDialog.value.saving = true
  bucketDialog.value.error = ''
  try {
    if (bucketDialog.value.mode === 'create') {
      // Nest under the active primary root — a real id targets that owner (incl. a contributor's); the virtual
      // own-primary passes none, so the server nests under (and materializes) your own primary.
      const parent = activeBucket.value
      await buckets.create(props.nodeId, key, name, parent && !parent.virtual ? parent.id : undefined)
    } else if (activeBucket.value) {
      await buckets.rename(props.nodeId, key, activeBucket.value.id, name)
    }
    bucketDialog.value.open = false
    await loadBuckets()
    emit('changed')
  } catch {
    bucketDialog.value.error = t('memory.buckets.saveFailed')
  } finally {
    bucketDialog.value.saving = false
  }
}

function openDeleteBucket() {
  const b = activeBucket.value
  if (!b || b.isPrimary || b.virtual) return
  deleteBucketDialog.value = { open: true, typed: '', saving: false, error: '' }
}
const canConfirmDeleteBucket = computed(() => !!activeBucket.value && deleteBucketDialog.value.typed === activeBucket.value.displayName)
async function confirmDeleteBucket() {
  const b = activeBucket.value
  if (!b || !canConfirmDeleteBucket.value) return
  deleteBucketDialog.value.saving = true
  try {
    await buckets.remove(props.nodeId, b.id) // media reparent to your primary (server-side)
    deleteBucketDialog.value.open = false
    await loadBuckets()
    emit('changed')
  } catch {
    deleteBucketDialog.value.error = t('memory.buckets.saveFailed')
  } finally {
    deleteBucketDialog.value.saving = false
  }
}

// --- media selection + move + delete ---------------------------------------

function toggleSelect(id: string) {
  const next = new Set(selected.value)
  if (next.has(id)) next.delete(id)
  else next.add(id)
  selected.value = next
}
const allSelected = computed(() => filteredMedia.value.length > 0 && filteredMedia.value.every((m) => selected.value.has(m.mediaId)))
function toggleSelectAll() {
  selected.value = allSelected.value ? new Set() : new Set(filteredMedia.value.map((m) => m.mediaId))
}
const selectedLabel = computed(() => t('memory.buckets.selected', { selected: selected.value.size, visible: filteredMedia.value.length }))

function openMediaMenu(event: Event, id: string) { menuMediaId.value = id; mediaMenu.value?.toggle(event) }
const mediaMenuItems = computed<MenuItem[]>(() => [
  { label: t('memory.buckets.moveMedia'), icon: 'pi pi-arrow-right-arrow-left', disabled: !moveTargets.value.length, command: () => { if (menuMediaId.value) { selected.value = new Set([menuMediaId.value]); openMove() } } },
  { label: t('memory.buckets.deleteMediaAction'), icon: 'pi pi-trash', command: () => { if (menuMediaId.value) { selected.value = new Set([menuMediaId.value]); void deleteSelected() } } },
])

function openMove() {
  if (!selected.value.size || !moveTargets.value.length) return
  moveDialog.value = { open: true, target: moveTargets.value[0].id, saving: false, error: '' }
}
async function submitMove() {
  if (!moveDialog.value.target || !selected.value.size) return
  moveDialog.value.saving = true
  moveDialog.value.error = ''
  try {
    await buckets.move(props.nodeId, [...selected.value], moveDialog.value.target)
    moveDialog.value.open = false
    selected.value = new Set()
    await loadBuckets()
    emit('changed')
  } catch {
    moveDialog.value.error = t('memory.buckets.moveFailed')
  } finally {
    moveDialog.value.saving = false
  }
}

// Delete = a recoverable soft-delete straight to the Papierkorb — no confirm modal, just an undo-toast (cr-0122).
async function deleteSelected() {
  const ids = [...selected.value]
  if (!ids.length) return
  selected.value = new Set()
  await Promise.allSettled(ids.map((id) => buckets.trash(id))) // soft-delete → trash
  await loadBuckets()
  emit('changed')
  undo.arm({ ids, count: ids.length })
  toast.showUndo({ summary: t('memory.buckets.deletedToTrash', { count: ids.length }, ids.length), onUndo: undoDelete, life: 8000 })
}
async function undoDelete() {
  const u = undo.cancel()
  if (!u) return
  await Promise.allSettled(u.ids.map((id) => buckets.restore(id)))
  await loadBuckets()
  emit('changed')
}
onBeforeUnmount(() => { toast.dismissUndo() }) // drop any lingering undo-toast (the `useUndo` window tears itself down)

// --- upload / drag-drop into the active bucket ------------------------------

function onDrop(event: DragEvent) {
  dropActive.value = false
  const files = Array.from(event.dataTransfer?.files ?? []).filter((f) => f.type.startsWith('image/') || f.type.startsWith('video/'))
  if (!files.length || !activeBucket.value) return
  // reuse the parent's upload; the target bucket materializes on complete (null = your primary)
  emit('uploadBucket', activeBucket.value.virtual ? null : activeBucket.value.id)
}

function openTile(index: number) { emit('openLightbox', { items: filteredMedia.value, index }) }
</script>

<template>
  <div class="veyq-bucket-organizer">
    <div v-if="status === 'loading'" class="media-grid-placeholder">
      <span class="pi pi-spin pi-spinner" /><span>{{ t('media.loading') }}</span>
    </div>
    <p v-else-if="status === 'error'" class="veyq-empty">{{ t('media.unavailable') }}</p>

    <div
      v-else
      class="bucket-detail"
      :class="{ 'bucket-drop-active': dropActive }"
      @dragenter.prevent="dropActive = true"
      @dragover.prevent="dropActive = true"
      @dragleave.prevent="dropActive = false"
      @drop.prevent="onDrop"
    >
      <!-- One row: the bucket picker + its action buttons on the left, the media selection bar on the right (cr-0122). -->
      <div class="bucket-action-row">
        <div class="bucket-picker">
          <Select
            class="bucket-select"
            :model-value="activeBucket?.id ?? ''"
            :options="selectOptions"
            option-label="displayName"
            option-value="value"
            :aria-label="t('memory.buckets.title')"
            @update:model-value="chooseBucket"
          >
            <template #value>
              <span v-if="activeBucket" class="bucket-select-value">
                <span class="pi" :class="activeBucket.isPrimary ? 'pi-user' : 'pi-folder'" />
                <span>{{ activeBucket.displayName }}</span>
                <small>{{ activeBucket.mediaCount }}</small>
              </span>
            </template>
            <template #option="{ option }">
              <span class="bucket-select-option" :style="{ paddingLeft: `${option.depth * 1.1}rem` }">
                <span v-if="option.depth > 0" class="bucket-select-branch" aria-hidden="true">↳</span>
                <span class="pi" :class="option.isPrimary ? 'pi-user' : 'pi-folder'" />
                <span class="bucket-select-name">{{ option.displayName }}</span>
                <small>{{ option.mediaCount }}</small>
              </span>
            </template>
          </Select>
          <!-- Bucket actions — shown only where they apply (was the 3-dot menu). -->
          <button v-if="activeBucket" type="button" class="bucket-action-button" :title="t('memory.buckets.upload')" :aria-label="t('memory.buckets.upload')" @click="uploadToActive">
            <span class="pi pi-upload" />
          </button>
          <button v-if="canCreateSub" type="button" class="bucket-action-button" :title="t('memory.buckets.createBucket')" :aria-label="t('memory.buckets.createBucket')" @click="openCreate">
            <span class="pi pi-folder-plus" />
          </button>
          <button v-if="canRenameBucket" type="button" class="bucket-action-button" :title="t('memory.buckets.rename')" :aria-label="t('memory.buckets.rename')" @click="openRename">
            <span class="pi pi-pencil" />
          </button>
          <button v-if="canDeleteBucket" type="button" class="bucket-action-button bucket-action-danger" :title="t('memory.buckets.delete')" :aria-label="t('memory.buckets.delete')" @click="openDeleteBucket">
            <span class="pi pi-trash" />
          </button>
        </div>

        <!-- Media selection bar — visible actions, active only with a selection. Shown for every bucket (cr-0122). -->
        <div v-if="filteredMedia.length" class="bucket-selection-bar">
          <span class="bucket-selection-count">{{ selectedLabel }}</span>
          <button type="button" class="veyq-text-button" @click="toggleSelectAll">
            {{ allSelected ? t('memory.buckets.deselectAll') : t('memory.buckets.selectAllVisible') }}
          </button>
          <button type="button" class="bucket-action-button" :disabled="!selected.size || !moveTargets.length" :title="t('memory.buckets.moveTitle')" @click="openMove">
            <span class="pi pi-arrow-right-arrow-left" /><span class="bucket-action-label">{{ t('memory.buckets.move') }}</span>
          </button>
          <button type="button" class="bucket-action-button bucket-action-danger" :disabled="!selected.size" :title="t('memory.buckets.deleteMediaAction')" @click="deleteSelected">
            <span class="pi pi-trash" /><span class="bucket-action-label">{{ t('memory.buckets.delete') }}</span>
          </button>
        </div>
      </div>

      <!-- dropzone banner -->
      <div v-if="dropActive && activeBucket" class="bucket-dropzone">
        <span class="pi pi-cloud-upload" />
        <div>
          <strong>{{ t('memory.buckets.dropTitle', { bucket: activeBucket.displayName }) }}</strong>
          <small>{{ t('memory.buckets.dropCopy') }}</small>
        </div>
      </div>

      <!-- the grid -->
      <p v-if="!filteredMedia.length" class="veyq-empty">{{ t('memory.buckets.emptyBucket') }}</p>
      <MediaGrid v-else :items="layoutItems" :aria-label="activeBucket?.displayName ?? t('memory.buckets.title')">
        <template #tile="{ tile, item }">
          <MediaTile
            :style="{ transform: `translate(${tile.left}px, ${tile.top}px)`, width: `${tile.renderedWidth}px`, height: `${tile.renderedHeight}px` }"
            :src="thumbs.get(item.media.mediaId)"
            :has-derivatives="hasDerivatives(item.media)"
            :is-video="item.isVideo"
            :open-label="t('media.openItem', { index: item.index + 1 })"
            :selected="selected.has(item.media.mediaId)"
            @open="openTile(item.index)"
          >
            <template #overlay>
              <label class="media-tile-check" :class="{ 'is-on': selected.has(item.media.mediaId) }">
                <input type="checkbox" :checked="selected.has(item.media.mediaId)" :aria-label="t('media.openItem', { index: item.index + 1 })" @click.stop @change="toggleSelect(item.media.mediaId)" />
                <span class="pi pi-check" aria-hidden="true" />
              </label>
              <button type="button" class="media-tile-menu" :aria-label="t('memory.buckets.options')" @click.stop="openMediaMenu($event, item.media.mediaId)">
                <span class="pi pi-ellipsis-v" />
              </button>
            </template>
          </MediaTile>
        </template>
      </MediaGrid>
      <Menu ref="mediaMenu" :model="mediaMenuItems" popup />
    </div>

    <!-- create / rename dialog -->
    <Dialog v-model:visible="bucketDialog.open" :header="bucketDialog.mode === 'create' ? t('memory.buckets.bucketDialogCreate') : t('memory.buckets.bucketDialogRename')" modal class="veyq-dialog-panel">
      <form class="veyq-form bucket-dialog-form" @submit.prevent="submitBucketDialog">
        <label for="bucket-name">{{ t('memory.buckets.bucketName') }}</label>
        <input id="bucket-name" v-model="bucketDialog.name" type="text" autocomplete="off" />
        <p v-if="bucketDialog.error" class="veyq-form-error">{{ bucketDialog.error }}</p>
        <div class="veyq-dialog-action-row">
          <button class="veyq-dialog-button veyq-dialog-button-secondary" type="button" :disabled="bucketDialog.saving" @click="bucketDialog.open = false">
            <span class="pi pi-times" /><span>{{ t('common.cancel') }}</span>
          </button>
          <button class="veyq-dialog-button veyq-dialog-button-primary" type="submit" :disabled="!bucketDialog.name.trim() || bucketDialog.saving">
            <span class="pi pi-save" /><span>{{ t('common.save') }}</span>
          </button>
        </div>
      </form>
    </Dialog>

    <!-- delete-bucket dialog (type-the-name) — a whole bucket is the destructive one, so it keeps its confirm -->
    <Dialog v-model:visible="deleteBucketDialog.open" :header="t('memory.buckets.deleteBucketTitle')" modal class="veyq-dialog-panel">
      <form class="veyq-form bucket-dialog-form" @submit.prevent="confirmDeleteBucket">
        <p class="bucket-dialog-copy">{{ t('memory.buckets.deleteBucketCopy', { bucket: activeBucket?.displayName }) }}</p>
        <label for="bucket-del-name">{{ t('memory.buckets.deleteBucketNameLabel') }}</label>
        <input id="bucket-del-name" v-model="deleteBucketDialog.typed" type="text" autocomplete="off" :placeholder="activeBucket?.displayName" />
        <div class="veyq-dialog-action-row">
          <button class="veyq-dialog-button veyq-dialog-button-secondary" type="button" :disabled="deleteBucketDialog.saving" @click="deleteBucketDialog.open = false">
            <span class="pi pi-times" /><span>{{ t('common.cancel') }}</span>
          </button>
          <button class="veyq-dialog-button veyq-dialog-button-danger" type="submit" :disabled="!canConfirmDeleteBucket || deleteBucketDialog.saving">
            <span class="pi pi-trash" /><span>{{ t('memory.buckets.deleteBucketConfirm') }}</span>
          </button>
        </div>
      </form>
    </Dialog>

    <!-- move dialog -->
    <Dialog v-model:visible="moveDialog.open" :header="t('memory.buckets.moveTitle')" modal class="veyq-dialog-panel">
      <form class="veyq-form bucket-dialog-form" @submit.prevent="submitMove">
        <label for="bucket-move-target">{{ t('memory.buckets.moveTarget') }}</label>
        <p v-if="!moveTargets.length" class="bucket-dialog-copy">{{ t('memory.buckets.moveEmpty') }}</p>
        <select v-else id="bucket-move-target" v-model="moveDialog.target">
          <option v-for="b in moveTargets" :key="b.id" :value="b.id">{{ '— '.repeat(b.depth) }}{{ b.displayName }}</option>
        </select>
        <div class="veyq-dialog-action-row">
          <button class="veyq-dialog-button veyq-dialog-button-secondary" type="button" :disabled="moveDialog.saving" @click="moveDialog.open = false">
            <span class="pi pi-times" /><span>{{ t('common.cancel') }}</span>
          </button>
          <button class="veyq-dialog-button veyq-dialog-button-primary" type="submit" :disabled="!moveDialog.target || moveDialog.saving">
            <span class="pi pi-arrow-right-arrow-left" /><span>{{ t('memory.buckets.move') }}</span>
          </button>
        </div>
      </form>
    </Dialog>
  </div>
</template>

<style scoped>
.veyq-bucket-organizer { display: grid; gap: 1rem; }
.bucket-detail { display: grid; gap: 1rem; position: relative; }
.bucket-drop-active::after {
  content: ''; position: absolute; inset: -0.5rem; border: 2px dashed color-mix(in srgb, var(--app-accent) 60%, transparent);
  border-radius: 12px; pointer-events: none;
}
/* One row: picker + bucket actions on the left, the selection bar on the right; wraps on narrow. */
.bucket-action-row { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 0.5rem 1rem; }
.bucket-picker { display: flex; align-items: center; gap: 0.4rem; min-width: 0; }
.bucket-select { width: auto; min-width: 200px; max-width: 300px; }
.bucket-select-value, .bucket-select-option { display: inline-flex; align-items: center; gap: 0.5rem; min-width: 0; }
.bucket-select-value small, .bucket-select-option small {
  margin-left: auto; padding: 0 0.4rem; border-radius: 999px; background: var(--app-control-bg); color: var(--app-muted);
  font-size: 0.72rem; font-weight: 700;
}
.bucket-select-option { width: 100%; }
.bucket-select-name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.bucket-select-branch { color: var(--app-muted); }
/* .bucket-action-button + -label/-danger live in global memory.css — shared with the Papierkorb bar (cr-0152). */
.bucket-selection-bar { display: flex; flex-wrap: wrap; align-items: center; gap: 0.5rem; }
.bucket-selection-count { color: var(--app-muted); font-size: 0.85rem; }
.bucket-dropzone {
  display: grid; grid-template-columns: 40px 1fr; gap: 0.75rem; align-items: center; padding: 0.75rem 1rem;
  border: 1px dashed color-mix(in srgb, var(--app-accent) 55%, var(--app-line)); border-radius: 10px;
  background: color-mix(in srgb, var(--app-accent) 8%, transparent);
}
.bucket-dropzone strong { display: block; }
.bucket-dropzone small { color: var(--app-muted); }
/* Tiles are the shared MediaTile (cr-0125); its check/menu overlays use the global `.media-tile-*` classes. */
.bucket-dialog-form { display: grid; gap: 0.7rem; }
.bucket-dialog-copy { color: var(--app-muted); margin: 0; }
.bucket-dialog-form input[type='text'], .bucket-dialog-form select {
  width: 100%; padding: 0.5rem 0.65rem; border: 1px solid var(--app-line); border-radius: 8px;
  background: var(--app-surface); color: inherit; font: inherit;
}
.veyq-dialog-button-danger { background: var(--app-danger); color: var(--app-on-fill); }
@media (max-width: 760px) {
  .bucket-action-row { justify-content: flex-start; }
  .bucket-picker { width: 100%; }
  .bucket-select { flex: 1 1 auto; max-width: none; }
}
</style>
