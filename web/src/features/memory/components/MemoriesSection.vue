<script setup lang="ts">
// Memories' section of the folder level view (cr-files-application P4b): what core's `core/node/LevelView.vue` renders
// for the MEMORY and CHAPTER children of a folder or the scope root (`register.ts` names it as both kinds' `section`;
// the backend places a CHAPTER only inside a MEMORY, so at a folder level `nodes` holds Memories). Split out of the
// former `MemoryBrowse.vue`, whose template and classes were the design source's MemoryFolderOverview verbatim; the
// overview card and the Folders section around it are core's now, this is the Memories half, markup unchanged.
//
// One deliberate deviation from that source stays: the section stays mounted in map mode (the map is its body instead of
// the grid). The space's trash button and the grid/list/map switch were in this section's header until Files needed
// them too: both are core's level-view toolbar now (inbox #370, #385) — the switch offers the map because this section
// names it among its `views` (`register.ts`), and the section only draws what the mode the level hands it says
// (`viewMode`, the stored preference clamped to what is on screen, inbox #404).
//
// **A share guest's level too (inbox #401 S2).** Core's guest folder page renders this section with a guest's `access`
// and its `tree` (`core/sharing/guestAccess.ts`): the same cards, dates, covers and map, read from what the link opened —
// each date and place unsealed under the node's key the chain opened (`tree.keyOf`), each cover through the link's own
// reads (`../guestCover.ts`), the map's pins from the opened subtree — and none of the member's state is touched: no
// `treeStore`, no `activeSpace`, no `coverStore`, no pins (`favoritesStore`, sealed under the member's Master Key).
// What it offers is the same capability matrix: a guest gets no heart, and a create card only where its role creates.
import { computed, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { parentContentKey, type TreeNode } from '../../../core/node/tree'
import type { LevelSectionProps, LevelSectionEmits } from '../../../core/applications/registry'
import { sortNodesByDate } from '../memoryDate'
import { nodeById } from '../../../core/node/browse'
import { treeStore } from '../../../core/node/treeStore'
import { collectLocatedPins, collectLocatedPinsBy } from '../spaceMap'
import { covers, ensureCovers } from '../coverStore'
import { activeSpace } from '../../../app/activeSpace'
import { isFavorite, toggleFavorite } from '../favoritesStore'
import { memoryCapabilities } from '../capabilities'
import { targetOf, type TreeSource } from '../../../core/access'
import { useGuestCovers } from '../guestCover'
import MemoryCard from './MemoryCard.vue'
import SpaceMap from './SpaceMap.vue'

// `epochs` (the scope's epoch key set, ADR 0024) unseals each Memory's sealed date/cover/pins under its own epoch's
// K_SC — a rotated scope mixes epochs across the grid; null (locked) ⇒ no dates/covers/pins.
// What the section offers is Memories' capability matrix for the level (`../capabilities.ts`, inbox #401): the create card
// only where a Memory may be created (not during a pending deletion, cr-0090; not in a guest's identity folder), the
// pin only for a member.
const props = defineProps<LevelSectionProps>()
const emit = defineEmits<LevelSectionEmits>()

const { t } = useI18n()
const caps = computed(() =>
  memoryCapabilities(props.access, targetOf(props.tree, props.folder)),
)
// The guest's own cover reads, for this section's lifetime; a member's come from the shared `coverStore`. Who looks does
// not change while the section is mounted (a host mounts its level for one session).
const guestAccess = props.access.mode === 'guest' ? props.access : null
const guestCovers = guestAccess ? useGuestCovers(guestAccess) : null
const guestTree = computed(() => props.tree as TreeSource<TreeNode>)

/** Every node of `tree` in the subtree of `rootId`, `rootId`'s own included — what a guest's map spans. */
function subtreeNodes(tree: TreeSource<TreeNode>, rootId: string | null): TreeNode[] {
  const out: TreeNode[] = []
  const root = rootId ? tree.node(rootId) : null
  if (root) out.push(root)
  const queue: (string | null)[] = [rootId]
  for (let i = 0; i < queue.length; i++) {
    for (const child of tree.children(queue[i])) {
      out.push(child)
      queue.push(child.id)
    }
  }
  return out
}

// Memories ordered by their sealed start date (newest first, undated last) — each K_mem opened under its parent's
// key: E_{node.epoch} at the scope root, the folder's key inside a folder (ADR 0080 Decision 1); read from
// wrappedMeta, no media load.
const datedMemories = computed(() => {
  if (guestAccess) {
    const tree = props.tree
    return sortNodesByDate(guestAccess.head.scopeId, (node) => (node.parentId ? tree.keyOf(node.parentId) : null), [...props.nodes])
  }
  const scopeId = activeSpace.scopeId
  const epochs = props.epochs
  return sortNodesByDate(scopeId ?? '', (node) => (epochs && scopeId ? parentContentKey(scopeId, epochs, treeStore.nodes, node) : null), [...props.nodes])
})

// The Karte pins: every located node in this folder's subtree (or the whole space at root), unsealed on device
// from the wrappedMeta `listNodes` already returned (operator-blind — no locations API). Locked ⇒ no pins.
const pins = computed(() =>
  guestAccess
    ? collectLocatedPinsBy(guestAccess.head.scopeId, (id) => props.tree.keyOf(id), subtreeNodes(guestTree.value, props.folder?.id ?? null), props.folder?.id ?? null)
    : props.epochs && activeSpace.scopeId
    ? collectLocatedPins(activeSpace.scopeId, props.epochs, treeStore.nodes, props.folder?.id ?? null)
    : [],
)

/** A pin click carries a node id — resolve it to the TreeNode and open it, exactly like a card click. */
function onPinOpen(nodeId: string) {
  const node = guestAccess ? guestTree.value.node(nodeId) : nodeById(treeStore.nodes, nodeId)
  if (node) emit('openNode', node)
}

/** The heart on a card: the member's own pin set for this scope (sealed under the Master Key, `favoritesStore`). */
function onToggleFavorite(node: TreeNode) {
  if (caps.value.favorite && activeSpace.scopeId) void toggleFavorite(node.id, activeSpace.scopeId)
}

// The chosen covers come from the shared `coverStore` (cr-0072 + the sidebar rail): one decrypt per Memory per
// scope for every surface, instead of one per surface. This view only says WHICH nodes are on screen — the
// store owns the key chain, the object-URL lifecycle and the staleness rule, and the cards + SpaceMap read the
// same `Map<string, LoadedCover>` they always did.
function syncCovers() {
  const nodes = props.viewMode === 'map' ? pins.value.map((pin) => pin.node) : props.nodes
  if (guestCovers) guestCovers.ensure(nodes, (id) => props.tree.keyOf(id))
  else ensureCovers(nodes.map((n) => n.id))
}
const coverMap = computed(() => (guestCovers ? guestCovers.covers.value : covers.value))
/** A member's own pin of a Memory; never a guest's (pins are sealed under the member's Master Key). */
const favorite = (id: string) => caps.value.favorite && isFavorite(id)

// The sealed meta is part of the key for the same reason it is in the rail: a cover edit patches `wrappedMeta`
// in place, so watching the array (whose identity does not change) would leave a stale cover on the very card
// the new one was chosen from. The store re-resolves on a changed meta — but only when it is asked to.
watch(
  [() => props.viewMode, pins, () => props.nodes.map((n) => `${n.id}:${n.wrappedMeta ?? ''}`).join('|')],
  syncCovers,
  { immediate: true },
)
</script>

<template>
  <!-- One Memories section in every view mode: its body is the card grid or the map. -->
  <section class="veyq-section memory-section">
    <div class="veyq-section-header memory-section-header">
      <h2>{{ t('memory.memories') }}</h2>
      <!-- the map's pins span the whole subtree, so this level's count would misread there -->
      <span v-if="viewMode !== 'map'">{{ nodes.length }}</span>
    </div>
    <SpaceMap v-if="viewMode === 'map'" :pins="pins" :covers="coverMap" @open="onPinOpen" />
    <div v-else class="memory-card-grid" :class="{ 'is-list': viewMode === 'list' }">
      <p v-if="!nodes.length" class="veyq-empty veyq-empty-inline">
        {{ t('memory.memoryEmpty') }}
      </p>
      <MemoryCard
        v-for="{ node: memory, date } in datedMemories"
        :key="memory.id"
        :memory="memory"
        :date="date"
        :favorite="favorite(memory.id)"
        :favoritable="caps.favorite"
        :cover-src="coverMap.get(memory.id)?.url ?? null"
        :cover-crop="coverMap.get(memory.id)?.crop ?? null"
        @open="emit('openNode', $event)"
        @toggle-favorite="onToggleFavorite"
      />
    </div>
  </section>
</template>

