<script setup lang="ts">
// The current tree level (cr-0030). Template + classes are the design source's MemoryFolderOverview verbatim
// (an optional folder-header card, then the Memories section + the Folders section, each a card grid with a
// "create" card) so memory.css styles it identically; only the data is ours — decrypted TreeNodes instead of
// server Memory/MemoryFolder DTOs, and the breadcrumb/navigation are derived on device (the server is blind).
// The folder header carries delete (as in the original) plus rename, added in-style. Two deliberate deviations
// from that source: the browse toolbar (trash + view mode) sits *inside* the Memories section header rather than
// on a row of its own — right-aligned controls on an otherwise empty row read as a blank line — and the Memories
// section stays mounted in map mode (the map is its body instead of the grid), which is what keeps the toolbar
// reachable there without a second copy of it.
import { computed, watch } from 'vue'
import { reactive } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import { nodeContentKey, type TreeNode } from '../tree'
import { keyAt, type EpochKeys } from '../../spaces/epochs'
import { sortNodesByDate } from '../memoryDate'
import { nodeById } from '../browse'
import { treeStore } from '../treeStore'
import { collectLocatedPins } from '../spaceMap'
import { loadNodeCover, type LoadedCover, type CoverCrop } from '../memoryCover'
import { isFavorite } from '../../../app/favoritesStore'
import { activeSpace } from '../../../app/activeSpace'
import { useViewMode } from '../../../shared/state/useViewMode'
import { useObjectUrls } from '../../../shared/state/useObjectUrls'
import MemoryCard from './MemoryCard.vue'
import ViewModeToggle from './ViewModeToggle.vue'
import SpaceMap from './SpaceMap.vue'

// `epochs` (the scope's epoch key set, ADR 0024) unseals each Memory's sealed date/cover/pins under its own epoch's
// K_SC — a rotated scope mixes epochs across the grid; null (locked) ⇒ no dates/covers/pins.
// `readonly` (cr-0090): the scope is in pending-deletion → hide create/rename/delete affordances (writes are
// server-rejected during the grace window; view + download stay open).
const props = defineProps<{ folder?: TreeNode | null; folders: TreeNode[]; memories: TreeNode[]; epochs?: EpochKeys | null; readonly?: boolean }>()
const emit = defineEmits<{
  openFolder: [folder: TreeNode]
  openMemory: [memory: TreeNode]
  toggleFavorite: [memory: TreeNode]
  createFolder: []
  createMemory: []
  renameFolder: []
  deleteFolder: []
  openTrash: []
}>()

const { t } = useI18n()
const { viewMode } = useViewMode()

// Memories ordered by their sealed start date (newest first, undated last) — each opened under its own epoch's
// K_SC (a top-level Memory's K_mem wraps under E_{node.epoch}); read from wrappedMeta, no media load.
const datedMemories = computed(() =>
  sortNodesByDate((node) => (props.epochs ? keyAt(props.epochs, node.epoch) : null), props.memories),
)

// The Karte pins: every located node in this folder's subtree (or the whole space at root), unsealed on device
// from the wrappedMeta `listNodes` already returned (operator-blind — no locations API). Locked ⇒ no pins.
const pins = computed(() =>
  props.epochs ? collectLocatedPins(props.epochs, treeStore.nodes, props.folder?.id ?? null) : [],
)

/** A pin click carries a node id — resolve it to the TreeNode and open it, exactly like a card click. */
function onPinOpen(nodeId: string) {
  const node = nodeById(treeStore.nodes, nodeId)
  if (node) emit('openMemory', node)
}

// Each node's chosen cover (nodeId → decrypted thumbnail + crop, cr-0072), loaded lazily on device. Kept here
// (we hold `scopeKey` + `scopeId`); passed read-only to the cards AND SpaceMap. `nodeContentKey` is resolved off
// the reactive proxy so K_mem bytes never enter Vue's reactivity (matching activeSpace's key handling). A node
// without a cover (or a not-yet-openable key) is retried until settled, so a `null` outcome isn't looked up forever.
// The object-URL lifecycle (incl. revoke-on-unmount) is owned by `useObjectUrls`; the crop rides alongside.
const coverUrls = useObjectUrls()
const coverCrops = reactive(new Map<string, CoverCrop>())
const coverTried = new Set<string>() // nodeIds resolved to a definite cover-or-none (so a "no cover" isn't retried)
// The display map the cards + SpaceMap read: pair each minted URL with its crop.
const covers = computed(() => {
  const m = new Map<string, LoadedCover>()
  for (const [id, url] of coverUrls.urls.value) {
    const crop = coverCrops.get(id)
    if (crop) m.set(id, { url, crop })
  }
  return m
})

/** Resolve the chosen cover for the currently-visible nodes — the card grid's Memories, or the map's pins —
 *  decrypting the chosen media's thumbnail on device (best-effort, parallel), cached so a settled node isn't redone. */
function syncCovers() {
  if (!activeSpace.scopeId || !props.epochs) return
  const scopeId = activeSpace.scopeId
  const epochs = props.epochs
  const nodes = viewMode.value === 'map' ? pins.value.map((pin) => pin.node) : props.memories
  for (const node of nodes) {
    if (coverTried.has(node.id)) continue
    const nodeKey = nodeContentKey(epochs, treeStore.nodes, node.id)
    if (!nodeKey) continue // K_mem not openable yet (e.g. still unlocking) — leave un-tried so a later pass retries
    coverTried.add(node.id)
    void loadNodeCover(scopeId, nodeKey, node).then((cover) => {
      if (!cover) return
      coverCrops.set(node.id, cover.crop)
      coverUrls.set(node.id, cover.blob)
    })
  }
}

watch([viewMode, pins, () => props.memories], syncCovers, { immediate: true })
</script>

<template>
  <div class="memory-route-surface">
    <section v-if="folder" class="veyq-section memory-section">
      <div class="veyq-section-header">
        <div class="veyq-card memory-folder-card memory-folder-overview-card">
          <span class="pi pi-folder" />
          <h2>{{ folder.name }}</h2>
          <div v-if="!readonly" class="memory-folder-overview-actions">
            <Button
              type="button"
              class="veyq-icon-button veyq-prime-icon-button memory-folder-overview-action memory-action-inline memory-action-collapsible"
              icon="pi pi-pencil"
              :aria-label="t('memory.rename')"
              severity="secondary"
              outlined
              size="small"
              @click="$emit('renameFolder')"
            />
            <Button
              type="button"
              class="veyq-icon-button veyq-prime-icon-button memory-folder-overview-action memory-danger-action memory-action-inline memory-action-collapsible"
              icon="pi pi-trash"
              :aria-label="t('memory.delete')"
              severity="danger"
              outlined
              size="small"
              @click="$emit('deleteFolder')"
            />
          </div>
        </div>
      </div>
    </section>

    <!-- One Memories section in every view mode: its header carries the browse toolbar (so the controls share the
         heading's row instead of claiming an empty one of their own), its body is the card grid or the map. -->
    <section class="veyq-section memory-section">
      <div class="veyq-section-header memory-section-header">
        <h2>{{ t('memory.memories') }}</h2>
        <!-- the map's pins span the whole subtree, so this level's count would misread there -->
        <span v-if="viewMode !== 'map'">{{ memories.length }}</span>
        <div class="memory-browse-toolbar">
          <!-- The space's node trash (uc.memory.delete-node): a deleted Memory has no Memory to hang a Trash tab
               from, so it lives on the SPACE and opens from here. Hidden during a pending-deletion grace window,
               like every other write affordance — a restore is a write. -->
          <Button
            v-if="!readonly"
            type="button"
            :label="t('memory.spaceTrash.open')"
            icon="pi pi-trash"
            severity="secondary"
            outlined
            size="small"
            @click="$emit('openTrash')"
          />
          <ViewModeToggle show-map />
        </div>
      </div>
      <SpaceMap v-if="viewMode === 'map'" :pins="pins" :covers="covers" @open="onPinOpen" />
      <div v-else class="memory-card-grid" :class="{ 'is-list': viewMode === 'list' }">
        <p v-if="!memories.length" class="veyq-empty veyq-empty-inline">
          {{ t('memory.memoryEmpty') }}
        </p>
        <MemoryCard
          v-for="{ node: memory, date } in datedMemories"
          :key="memory.id"
          :memory="memory"
          :date="date"
          :favorite="isFavorite(memory.id)"
          :cover-src="covers.get(memory.id)?.url ?? null"
          :cover-crop="covers.get(memory.id)?.crop ?? null"
          @open="$emit('openMemory', $event)"
          @toggle-favorite="$emit('toggleFavorite', $event)"
        />
        <button
          v-if="!readonly"
          class="veyq-card veyq-card-interactive memory-create-card memory-create-card-memory"
          type="button"
          @click="$emit('createMemory')"
        >
          <span class="pi pi-plus" />
          <strong>{{ t('memory.create') }}</strong>
        </button>
      </div>
    </section>

    <section v-if="viewMode !== 'map'" class="veyq-section memory-section">
      <div class="veyq-section-header memory-section-header">
        <h2>{{ t('memory.folders') }}</h2>
        <span>{{ folders.length }}</span>
      </div>
      <div class="memory-card-grid" :class="{ 'is-list': viewMode === 'list' }">
        <p v-if="!folders.length" class="veyq-empty veyq-empty-inline">
          {{ t('memory.folderEmpty') }}
        </p>
        <button
          v-for="child in folders"
          :key="child.id"
          class="veyq-card veyq-card-interactive memory-folder-card"
          type="button"
          @click="$emit('openFolder', child)"
        >
          <span class="pi pi-folder" />
          <strong>{{ child.name }}</strong>
        </button>
        <button
          v-if="!readonly"
          class="veyq-card veyq-card-interactive memory-create-card"
          type="button"
          @click="$emit('createFolder')"
        >
          <span class="pi pi-plus" />
          <strong>{{ t('memory.folderCreate') }}</strong>
        </button>
      </div>
    </section>
  </div>
</template>

<style scoped>
.memory-browse-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.5rem;
  margin-left: auto;
}
</style>
