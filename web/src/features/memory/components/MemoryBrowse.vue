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
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import { parentContentKey, type TreeNode } from '../../../core/node/tree'
import type { EpochKeys } from '../../spaces/epochs'
import { sortNodesByDate } from '../memoryDate'
import { nodeById } from '../../../core/node/browse'
import { treeStore } from '../../../core/node/treeStore'
import { collectLocatedPins } from '../spaceMap'
import { covers, ensureCovers } from '../coverStore'
import { activeSpace } from '../../../app/activeSpace'
import { isFavorite } from '../favoritesStore'
import { useViewMode } from '../../../shared/state/useViewMode'
import MemoryCard from './MemoryCard.vue'
import ViewModeToggle from './ViewModeToggle.vue'
import SpaceMap from './SpaceMap.vue'

// `epochs` (the scope's epoch key set, ADR 0024) unseals each Memory's sealed date/cover/pins under its own epoch's
// K_SC — a rotated scope mixes epochs across the grid; null (locked) ⇒ no dates/covers/pins.
// `readonly` (cr-0090): the scope is being deleted → hide create/rename/delete affordances (every write is
// server-rejected during the grace window, uc.spaces.scope-access; view + download stay open).
// `shareable`: the host offers a public share of this folder (FOLDER's descriptor AND the instance capability, decided
// by the host as for a Memory) — the button opens core's folder share dialog through the `share` event.
const props = defineProps<{ folder?: TreeNode | null; folders: TreeNode[]; memories: TreeNode[]; epochs?: EpochKeys | null; readonly?: boolean; shareable?: boolean }>()
const emit = defineEmits<{
  openFolder: [folder: TreeNode]
  openMemory: [memory: TreeNode]
  toggleFavorite: [memory: TreeNode]
  createFolder: []
  createMemory: []
  renameFolder: []
  deleteFolder: []
  openTrash: []
  share: []
}>()

const { t } = useI18n()
const { viewMode } = useViewMode()

// Memories ordered by their sealed start date (newest first, undated last) — each K_mem opened under its parent's
// key: E_{node.epoch} at the scope root, the folder's key inside a folder (ADR 0080 Decision 1); read from
// wrappedMeta, no media load.
const datedMemories = computed(() => {
  const scopeId = activeSpace.scopeId
  const epochs = props.epochs
  return sortNodesByDate(scopeId ?? '', (node) => (epochs && scopeId ? parentContentKey(scopeId, epochs, treeStore.nodes, node) : null), props.memories)
})

// The Karte pins: every located node in this folder's subtree (or the whole space at root), unsealed on device
// from the wrappedMeta `listNodes` already returned (operator-blind — no locations API). Locked ⇒ no pins.
const pins = computed(() =>
  props.epochs && activeSpace.scopeId
    ? collectLocatedPins(activeSpace.scopeId, props.epochs, treeStore.nodes, props.folder?.id ?? null)
    : [],
)

/** A pin click carries a node id — resolve it to the TreeNode and open it, exactly like a card click. */
function onPinOpen(nodeId: string) {
  const node = nodeById(treeStore.nodes, nodeId)
  if (node) emit('openMemory', node)
}

// The chosen covers come from the shared `coverStore` (cr-0072 + the sidebar rail): one decrypt per Memory per
// scope for every surface, instead of one per surface. This view only says WHICH nodes are on screen — the
// store owns the key chain, the object-URL lifecycle and the staleness rule, and the cards + SpaceMap read the
// same `Map<string, LoadedCover>` they always did.
function syncCovers() {
  const nodes = viewMode.value === 'map' ? pins.value.map((pin) => pin.node) : props.memories
  ensureCovers(nodes.map((n) => n.id))
}

// The sealed meta is part of the key for the same reason it is in the rail: a cover edit patches `wrappedMeta`
// in place, so watching the array (whose identity does not change) would leave a stale cover on the very card
// the new one was chosen from. The store re-resolves on a changed meta — but only when it is asked to.
watch(
  [viewMode, pins, () => props.memories.map((n) => `${n.id}:${n.wrappedMeta ?? ''}`).join('|')],
  syncCovers,
  { immediate: true },
)
</script>

<template>
  <div class="memory-route-surface">
    <section v-if="folder" class="veyq-section memory-section">
      <div class="veyq-section-header">
        <div class="veyq-card memory-folder-card memory-folder-overview-card">
          <span class="pi pi-folder" />
          <h2>{{ folder.name }}</h2>
          <div v-if="!readonly || shareable" class="memory-folder-overview-actions">
            <!-- The same affordance as a Memory's share button (icon, label, tooltip); listing and copying links is a
                 read, so it stays during a pending deletion, where a new link is refused server-side anyway. -->
            <Button
              v-if="shareable"
              v-tooltip.bottom="t('share.share')"
              type="button"
              class="veyq-icon-button veyq-prime-icon-button memory-folder-overview-action memory-action-inline memory-action-collapsible"
              icon="pi pi-share-alt"
              :aria-label="t('share.share')"
              severity="secondary"
              outlined
              size="small"
              @click="$emit('share')"
            />
            <Button
              v-if="!readonly"
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
              v-if="!readonly"
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
