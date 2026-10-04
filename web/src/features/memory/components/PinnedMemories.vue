<script setup lang="ts">
// The Memories application's fill of the shell's `sidebarPins` slot: the **favorites** section — each pinned
// Memory with its own chosen cover (uc.memory.cover's fourth surface, after the browse card, the detail header
// and the map pin). It used to be inline in `AppSidebar`; it lives here because the pin set, the kind filter and
// the covers are Memories concepts the shell must not name (ADR 0079). The shell mounts it twice, once per
// `variant`: the desktop/compact rail (`rail`) and the mobile navigation sheet (`sheet`, whose rows close the
// sheet on navigation through the `navigate` event).
//
// The covers come from the shared `coverStore`: this component asks it WHICH nodes are on screen and reads the
// result, and imports **no key API at all** — the epoch chain that opens a node's `K_mem` has no business in an
// always-mounted rail, and keeping it out is a property worth preserving, not an accident of one diff.
import { computed, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink, useRoute } from 'vue-router'
import type { SidebarPinsProps, SidebarPinsEmits } from '../../../core/applications/registry'
import { NodeKind } from '../../../api/generated/content'
import { activeSpace } from '../../../app/activeSpace'
import { favoritesStore } from '../favoritesStore'
import { treeStore } from '../../../core/node/treeStore'
import { nodeById } from '../../../core/node/browse'
import { hashForNode, parseHash } from '../../../core/node/location'
import type { TreeNode } from '../../../core/node/tree'
import { covers, ensureCovers } from '../coverStore'
import MemoryCoverImage from './MemoryCoverImage.vue'

defineProps<SidebarPinsProps>()
const emit = defineEmits<SidebarPinsEmits>()

const { t } = useI18n()
const route = useRoute()
// The active scope token (`~me`, `@group`, token-less tenant — ADR 0019): how a pinned Memory's hash is built.
const activeScope = computed(() => parseHash(route.hash).scope)

// The pinned Memories, resolved against the local decrypted tree (a favorite whose node is gone just drops).
const favoriteMemories = computed(() =>
  [...favoritesStore.ids]
    .map((id) => nodeById(treeStore.nodes, id))
    .filter((n): n is TreeNode => !!n && n.kind === NodeKind.Memory)
    .map((n) => ({ id: n.id, name: n.name, hash: hashForNode(treeStore.nodes, n.id, activeScope.value) })),
)
/** The pinned Memories' covers, resolved by the shared store. A favorite without one keeps the placeholder —
 *  uc.memory.cover E1 stands: there is no auto-cover, and the rail adds no branch to say so. */
const favoriteCover = (id: string) => covers.value.get(id)
watch(
  // Keyed on each favorite's **sealed meta**, not merely on which ids are pinned — the same key the store uses
  // for staleness, because the store only re-resolves what it is asked about. `patchNodeMeta` mutates
  // `wrappedMeta` IN PLACE, so a watch over the ids, the node count or the array identity never fires and a
  // cover set in the detail header leaves the rail showing the old one. The epoch is here for the other half:
  // a rotation swaps the keys a cover was decrypted under and the store drops them, so the rail must ask again
  // rather than sit on an empty map.
  () => favoriteMemories.value.map((m) => `${m.id}:${nodeById(treeStore.nodes, m.id)?.wrappedMeta ?? ''}`).join('|') + `#${activeSpace.epoch}`,
  () => ensureCovers(favoriteMemories.value.map((m) => m.id)),
  { immediate: true },
)
</script>

<template>
  <section v-if="variant === 'rail' && favoriteMemories.length" class="sidebar-section sidebar-favorites-section">
    <div class="sidebar-section-heading">
      <p class="sidebar-section-label">{{ t('shell.favorites') }}</p>
    </div>
    <RouterLink
      v-for="memory in favoriteMemories"
      :key="memory.id"
      :to="{ name: 'home', hash: memory.hash }"
      class="sidebar-scope-link sidebar-favorite-link"
    >
      <span class="sidebar-favorite-cover">
        <MemoryCoverImage :src="favoriteCover(memory.id)?.url" :crop="favoriteCover(memory.id)?.crop ?? null" :alt="memory.name" />
      </span>
      <span><strong>{{ memory.name }}</strong></span>
    </RouterLink>
  </section>

  <section v-else-if="variant === 'sheet' && favoriteMemories.length" class="mobile-nav-section">
    <h2>{{ t('shell.favorites') }}</h2>
    <div class="mobile-nav-list">
      <RouterLink
        v-for="memory in favoriteMemories"
        :key="memory.id"
        :to="{ name: 'home', hash: memory.hash }"
        class="mobile-nav-item mobile-nav-favorite"
        @click="emit('navigate')"
      >
        <span class="sidebar-favorite-cover">
          <MemoryCoverImage :src="favoriteCover(memory.id)?.url" :crop="favoriteCover(memory.id)?.crop ?? null" :alt="memory.name" />
        </span><span><strong>{{ memory.name }}</strong></span>
      </RouterLink>
    </div>
  </section>
</template>
