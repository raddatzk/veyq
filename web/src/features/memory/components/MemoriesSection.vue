<script setup lang="ts">
// Memories' section of the folder level view (cr-files-application P4b): what core's `core/node/LevelView.vue` renders
// for the MEMORY and CHAPTER children of a folder or the scope root (`register.ts` names it as both kinds' `section`;
// the backend places a CHAPTER only inside a MEMORY, so at a folder level `nodes` holds Memories). Split out of the
// former `MemoryBrowse.vue`, whose template and classes were the design source's MemoryFolderOverview verbatim; the
// overview card and the Folders section around it are core's now, this is the Memories half, markup unchanged.
//
// Two deliberate deviations from that source stay: the browse toolbar (trash + view mode) sits *inside* this section's
// header rather than on a row of its own — right-aligned controls on an otherwise empty row read as a blank line — and
// the section stays mounted in map mode (the map is its body instead of the grid), which keeps the toolbar reachable
// there without a second copy of it. The toolbar stays here until a second application needs it (owner decision, P4e).
import { computed, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import { parentContentKey, type TreeNode } from '../../../core/node/tree'
import type { LevelSectionProps, LevelSectionEmits } from '../../../core/applications/registry'
import { sortNodesByDate } from '../memoryDate'
import { nodeById } from '../../../core/node/browse'
import { treeStore } from '../../../core/node/treeStore'
import { collectLocatedPins } from '../spaceMap'
import { covers, ensureCovers } from '../coverStore'
import { activeSpace } from '../../../app/activeSpace'
import { isFavorite, toggleFavorite } from '../favoritesStore'
import { useViewMode } from '../../../shared/state/useViewMode'
import MemoryCard from './MemoryCard.vue'
import ViewModeToggle from './ViewModeToggle.vue'
import SpaceMap from './SpaceMap.vue'

// `epochs` (the scope's epoch key set, ADR 0024) unseals each Memory's sealed date/cover/pins under its own epoch's
// K_SC — a rotated scope mixes epochs across the grid; null (locked) ⇒ no dates/covers/pins.
// `readonly` (cr-0090): the scope is being deleted → no create card and no trash (a restore is a write).
const props = defineProps<LevelSectionProps>()
const emit = defineEmits<LevelSectionEmits>()

const { t } = useI18n()
const { viewMode } = useViewMode()

// Memories ordered by their sealed start date (newest first, undated last) — each K_mem opened under its parent's
// key: E_{node.epoch} at the scope root, the folder's key inside a folder (ADR 0080 Decision 1); read from
// wrappedMeta, no media load.
const datedMemories = computed(() => {
  const scopeId = activeSpace.scopeId
  const epochs = props.epochs
  return sortNodesByDate(scopeId ?? '', (node) => (epochs && scopeId ? parentContentKey(scopeId, epochs, treeStore.nodes, node) : null), [...props.nodes])
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
  if (node) emit('openNode', node)
}

/** The heart on a card: the member's own pin set for this scope (sealed under the Master Key, `favoritesStore`). */
function onToggleFavorite(node: TreeNode) {
  if (activeSpace.scopeId) void toggleFavorite(node.id, activeSpace.scopeId)
}

// The chosen covers come from the shared `coverStore` (cr-0072 + the sidebar rail): one decrypt per Memory per
// scope for every surface, instead of one per surface. This view only says WHICH nodes are on screen — the
// store owns the key chain, the object-URL lifecycle and the staleness rule, and the cards + SpaceMap read the
// same `Map<string, LoadedCover>` they always did.
function syncCovers() {
  const nodes = viewMode.value === 'map' ? pins.value.map((pin) => pin.node) : props.nodes
  ensureCovers(nodes.map((n) => n.id))
}

// The sealed meta is part of the key for the same reason it is in the rail: a cover edit patches `wrappedMeta`
// in place, so watching the array (whose identity does not change) would leave a stale cover on the very card
// the new one was chosen from. The store re-resolves on a changed meta — but only when it is asked to.
watch(
  [viewMode, pins, () => props.nodes.map((n) => `${n.id}:${n.wrappedMeta ?? ''}`).join('|')],
  syncCovers,
  { immediate: true },
)
</script>

<template>
  <!-- One Memories section in every view mode: its header carries the browse toolbar (so the controls share the
       heading's row instead of claiming an empty one of their own), its body is the card grid or the map. -->
  <section class="veyq-section memory-section">
    <div class="veyq-section-header memory-section-header">
      <h2>{{ t('memory.memories') }}</h2>
      <!-- the map's pins span the whole subtree, so this level's count would misread there -->
      <span v-if="viewMode !== 'map'">{{ nodes.length }}</span>
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
          @click="emit('openTrash')"
        />
        <ViewModeToggle show-map />
      </div>
    </div>
    <SpaceMap v-if="viewMode === 'map'" :pins="pins" :covers="covers" @open="onPinOpen" />
    <div v-else class="memory-card-grid" :class="{ 'is-list': viewMode === 'list' }">
      <p v-if="!nodes.length" class="veyq-empty veyq-empty-inline">
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
        @open="emit('openNode', $event)"
        @toggle-favorite="onToggleFavorite"
      />
      <button
        v-if="!readonly"
        class="veyq-card veyq-card-interactive memory-create-card memory-create-card-memory"
        type="button"
        @click="emit('create', 'create-memory')"
      >
        <span class="pi pi-plus" />
        <strong>{{ t('memory.create') }}</strong>
      </button>
    </div>
  </section>
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
