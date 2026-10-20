<script setup lang="ts">
// The shell's **Favorites** section (en.memory.favorites, ADR 0068; core's since inbox #409): every pinned node of the active
// scope, whatever its kind - a folder, a file, a Memory, a drop box - each with its KIND's icon (the registry's descriptor, or its COVER when it has one, inbox #411) and
// its decrypted name, opening the node through `hashForNode` (the readable fragment path of inbox #328; never a hand-built
// fragment). The shell mounts it twice, once per `variant`: the desktop/compact rail (`rail`) and the mobile navigation sheet
// (`sheet`, whose rows close the sheet on navigation through the `navigate` event).
//
// **A pinned id whose node does not resolve is skipped**: purged, trashed (the tree lists live nodes only; a restore brings the
// row back), or in a scope this session cannot read. The server cannot read the set, so nothing prunes it there - the id is
// inert, never a broken row. A node of a kind this client does not draw (`registry.kind(...).supported === false`) is skipped
// too: the heart is not offered on it (`favoriteRights`), and a row the rail could not open would be a dead link.
import { computed, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink, useRoute } from 'vue-router'
import { favoritesStore } from './favoritesStore'
import { registry, type SidebarPinsProps, type SidebarPinsEmits } from '../applications/registry'
import { treeStore } from '../node/treeStore'
import { displayNameOf, nodeById } from '../node/browse'
import { hashForNode, parseHash } from '../node/location'
import { activeSpace, activeEpochKeys } from '../../app/activeSpace'
import { nodeContentKey } from '../node/tree'
import { memberCoverStore } from '../node/cover/coverStore'
import NodeCoverImage from '../node/cover/components/NodeCoverImage.vue'

defineProps<SidebarPinsProps>()
const emit = defineEmits<SidebarPinsEmits>()

const { t } = useI18n()
const route = useRoute()
// The active scope token (`~me`, `@group`, token-less tenant - ADR 0019): how a pinned node's hash is built.
const activeScope = computed(() => parseHash(route.hash).scope)

const pinned = computed(() =>
  [...favoritesStore.ids].flatMap((id) => {
    const node = nodeById(treeStore.nodes, id)
    if (!node) return []
    const info = registry.kind(node.kind)
    if (!info.supported) return []
    return [{
      id: node.id,
      name: displayNameOf(treeStore.nodes, node),
      icon: info.icon,
      kind: node.kind,
      hash: hashForNode(treeStore.nodes, node.id, activeScope.value),
      node,
    }]
  }),
)

// The pinned nodes' covers (inbox #411; Memories' rail showed its covers until the pins became core's, #409): the member's one
// cover store resolves them - a kind without a cover keeps its icon, and a cover that is missing or fails to open is the icon
// too. Watched on the sealed meta as well: a cover write patches `wrappedMeta` in place.
function ensureCovers(): void {
  const scopeId = activeSpace.scopeId
  const epochs = activeEpochKeys()
  if (!scopeId || !epochs) return
  memberCoverStore.ensure(scopeId, pinned.value.map((p) => p.node), (id) => nodeContentKey(scopeId, epochs, treeStore.nodes, id))
}
watch(() => pinned.value.map((p) => `${p.id}:${p.node.wrappedMeta ?? ''}`).join('|'), ensureCovers, { immediate: true })
const coverOf = (id: string) => memberCoverStore.covers.value.get(id) ?? null
</script>

<template>
  <section v-if="variant === 'rail' && pinned.length" class="sidebar-section sidebar-favorites-section">
    <div class="sidebar-section-heading">
      <p class="sidebar-section-label">{{ t('shell.favorites') }}</p>
    </div>
    <RouterLink
      v-for="node in pinned"
      :key="node.id"
      :to="{ name: 'home', hash: node.hash }"
      class="sidebar-scope-link sidebar-favorite-link"
      :data-kind="node.kind"
    >
      <span class="sidebar-favorite-cover">
        <NodeCoverImage v-if="coverOf(node.id)" :src="coverOf(node.id)!.url" :crop="coverOf(node.id)!.crop" />
        <span v-else :class="node.icon" aria-hidden="true" />
      </span>
      <span><strong>{{ node.name }}</strong></span>
    </RouterLink>
  </section>

  <section v-else-if="variant === 'sheet' && pinned.length" class="mobile-nav-section">
    <h2>{{ t('shell.favorites') }}</h2>
    <div class="mobile-nav-list">
      <RouterLink
        v-for="node in pinned"
        :key="node.id"
        :to="{ name: 'home', hash: node.hash }"
        class="mobile-nav-item mobile-nav-favorite"
        :data-kind="node.kind"
        @click="emit('navigate')"
      >
        <span class="sidebar-favorite-cover">
          <NodeCoverImage v-if="coverOf(node.id)" :src="coverOf(node.id)!.url" :crop="coverOf(node.id)!.crop" />
          <span v-else :class="node.icon" aria-hidden="true" />
        </span><span><strong>{{ node.name }}</strong></span>
      </RouterLink>
    </div>
  </section>
</template>
