<script setup lang="ts">
// The Memories application's adapter between the host's generic node-detail context (core/applications/registry:
// `NodeDetailProps`) and the props/events `MemoryDetail` already takes (ADR 0079). `MemoryDetail` itself is not
// edited: everything Memories-shaped the shell used to pass — the pin (favorite) state, the item-mark key, the
// personal-scope flag, the chapters bucket, the meta patch — is resolved here, inside the application.
import { computed } from 'vue'
import type { NodeDetailProps, NodeDetailEmits } from '../../../core/applications/registry'
import { CHAPTER_KIND } from '../kinds'
import { activeSpace, activeEpochKeys } from '../../../app/activeSpace'
import { isFavorite, toggleFavorite } from '../favoritesStore'
import { patchNodeMeta } from '../../../core/node/treeStore'
import { keyAt } from '../../spaces/epochs'
import MemoryDetail from './MemoryDetail.vue'

const props = defineProps<NodeDetailProps>()
const emit = defineEmits<NodeDetailEmits>()

const chapters = computed(() => props.children.byKind.get(CHAPTER_KIND) ?? [])

// Item marks seal under `K_SC` at the **base epoch `E_0`** (ADR 0068), never under `activeScopeKey()`, which is
// the *write* key `E_current`: the marked set is re-sealed whole on every toggle, so a rotation would strand it.
// `activeSpace.epoch` is the reactive signal that a rotation happened — without reading it this would not
// recompute after `refreshActiveScope`.
const itemMarkKey = computed<Uint8Array | null>(() => {
  void activeSpace.epoch
  const eks = activeEpochKeys()
  return eks ? keyAt(eks, 0) : null
})

function onToggleFavorite() {
  if (activeSpace.scopeId) void toggleFavorite(props.node.id, activeSpace.scopeId)
}
</script>

<template>
  <MemoryDetail
    :node="node"
    :scope-id="scopeId"
    :memory-key="contentKey"
    :scope-key="itemMarkKey"
    :personal-scope="activeSpace.kind === 'PERSONAL'"
    :favorite="isFavorite(node.id)"
    :chapters="chapters"
    :shareable="shareable"
    @toggle-favorite="onToggleFavorite"
    @rename="emit('rename')"
    @delete="emit('delete')"
    @create-chapter="emit('createChild', CHAPTER_KIND)"
    @open-chapter="emit('openNode', $event)"
    @share="emit('share')"
    @share-upload="emit('shareUpload')"
    @meta-updated="patchNodeMeta($event.nodeId, $event.wrappedMeta, $event.metaVersion)"
  />
</template>
