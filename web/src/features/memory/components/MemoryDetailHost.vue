<script setup lang="ts">
// The Memories application's adapter between the host's generic node-detail context (core/applications/registry:
// `NodeDetailProps`) and the props/events `MemoryDetail` takes (ADR 0079): everything Memories-shaped — the pin
// (favorite) state, the item-mark key, the personal-scope flag, the chapters bucket, the meta patch — is resolved here,
// inside the application, and so are the CAPABILITIES (inbox #401): Memories' own fail-closed matrix over the host's
// `access` and the opened node (`../capabilities.ts`), which is all `MemoryDetail` reads to show or hide a control.
import { computed } from 'vue'
import type { NodeDetailProps, NodeDetailEmits } from '../../../core/applications/registry'
import { CHAPTER_KIND } from '../kinds'
import { activeSpace, activeEpochKeys } from '../../../app/activeSpace'
import { isFavorite, toggleFavorite } from '../favoritesStore'
import { patchNodeMeta } from '../../../core/node/treeStore'
import { keyAt } from '../../spaces/epochs'
import { memoryCapabilities } from '../capabilities'
import { targetOf } from '../../../core/access'
import MemoryDetail from './MemoryDetail.vue'

const props = defineProps<NodeDetailProps>()
const emit = defineEmits<NodeDetailEmits>()

const chapters = computed(() => props.children.byKind.get(CHAPTER_KIND) ?? [])
const caps = computed(() => memoryCapabilities(props.access, targetOf(props.tree, props.node)))

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
    :caps="caps"
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
