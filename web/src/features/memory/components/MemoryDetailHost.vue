<script setup lang="ts">
// The Memories application's adapter between the host's generic node-detail context (core/applications/registry:
// `NodeDetailProps`) and the props/events `MemoryDetail` takes (ADR 0079): everything Memories-shaped — the pin
// (favorite) state, the item-mark key, the personal-scope flag, the chapters bucket, the meta patch — is resolved here,
// inside the application, and so are the CAPABILITIES (inbox #401): Memories' own fail-closed matrix over the host's
// `access` and the opened node (`../capabilities.ts`), which `MemoryDetail` narrows to its data source's doors.
//
// **The data source is picked here, once** (inbox #401 S4, `../source.ts`): the member's over the unlocked scope, or a
// share guest's over its link — by `access.mode`, which does not change while the detail is mounted (a page mounts it
// per node and session). For a guest nothing of the member's world is read: no `activeSpace` (the item-mark key, the
// scope kind), no pin store, no `treeStore` patch — the guest's capabilities grant none of them, and the guest's source
// carries no door to them. A guest gets it from core's share page (`core/sharing/ShareTreePage.vue`) for every Memory and
// chapter of a `READ` or `READ_WRITE` link, the link's root included (inbox #401 S5).
import { computed } from 'vue'
import type { NodeDetailProps, NodeDetailEmits } from '../../../core/applications/registry'
import { CHAPTER_KIND } from '../kinds'
import { activeSpace, activeEpochKeys } from '../../../app/activeSpace'
import { isFavorite, toggleFavorite } from '../favoritesStore'
import { patchNodeMeta } from '../../../core/node/treeStore'
import { keyAt } from '../../spaces/epochs'
import { memoryCapabilities } from '../capabilities'
import { targetOf } from '../../../core/access'
import { guestMemorySource, memberMemorySource } from '../source'
import MemoryDetail from './MemoryDetail.vue'

const props = defineProps<NodeDetailProps>()
const emit = defineEmits<NodeDetailEmits>()

const chapters = computed(() => props.children.byKind.get(CHAPTER_KIND) ?? [])
const caps = computed(() => memoryCapabilities(props.access, targetOf(props.tree, props.node)))

// Who looks is fixed for this mount (see the header).
const guest = props.access.mode === 'guest' ? props.access : null
const source = guest
  ? guestMemorySource(() => (props.access.mode === 'guest' ? props.access : guest), props.tree)
  : memberMemorySource({ scopeId: () => props.scopeId })

// Item marks seal under `K_SC` at the **base epoch `E_0`** (ADR 0068), never under `activeScopeKey()`, which is
// the *write* key `E_current`: the marked set is re-sealed whole on every toggle, so a rotation would strand it.
// `activeSpace.epoch` is the reactive signal that a rotation happened — without reading it this would not
// recompute after `refreshActiveScope`. A member's only.
const itemMarkKey = computed<Uint8Array | null>(() => {
  if (guest) return null
  void activeSpace.epoch
  const eks = activeEpochKeys()
  return eks ? keyAt(eks, 0) : null
})
const personalScope = computed(() => !guest && activeSpace.kind === 'PERSONAL')
const favorite = computed(() => !guest && isFavorite(props.node.id))

function onToggleFavorite() {
  if (!guest && activeSpace.scopeId) void toggleFavorite(props.node.id, activeSpace.scopeId)
}
function onMetaUpdated(e: { nodeId: string; wrappedMeta: string | null; metaVersion: number }) {
  if (!guest) patchNodeMeta(e.nodeId, e.wrappedMeta, e.metaVersion)
}
</script>

<template>
  <MemoryDetail
    :node="node"
    :scope-id="scopeId"
    :memory-key="contentKey"
    :scope-key="itemMarkKey"
    :personal-scope="personalScope"
    :favorite="favorite"
    :chapters="chapters"
    :caps="caps"
    :source="source"
    @toggle-favorite="onToggleFavorite"
    @rename="emit('rename')"
    @move="emit('move', node)"
    @delete="emit('delete')"
    @create-chapter="emit('createChild', CHAPTER_KIND)"
    @open-chapter="emit('openNode', $event)"
    @share="emit('share')"
    @share-upload="emit('shareUpload')"
    @meta-updated="onMetaUpdated"
  />
</template>
