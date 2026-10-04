<script setup lang="ts">
// The Memories application's adapter between the host's generic level context (core/applications/registry:
// `LevelViewProps`) and the props/events `MemoryBrowse` already takes (ADR 0079). `MemoryBrowse` itself is not
// edited: the Memories bucket, the pin toggle and the two create modes are resolved here.
import { computed } from 'vue'
import type { LevelViewProps, LevelViewEmits } from '../../../core/applications/registry'
import { NodeKind } from '../../../api/generated/content'
import { activeSpace } from '../../../app/activeSpace'
import { toggleFavorite } from '../favoritesStore'
import type { TreeNode } from '../../../core/node/tree'
import MemoryBrowse from './MemoryBrowse.vue'

const props = defineProps<LevelViewProps>()
const emit = defineEmits<LevelViewEmits>()

const memories = computed(() => props.level.byKind.get(NodeKind.Memory) ?? [])

function onToggleFavorite(node: TreeNode) {
  if (activeSpace.scopeId) void toggleFavorite(node.id, activeSpace.scopeId)
}
</script>

<template>
  <MemoryBrowse
    :folder="folder"
    :folders="level.folders"
    :memories="memories"
    :epochs="epochs"
    :readonly="readonly"
    @open-folder="emit('openNode', $event)"
    @open-memory="emit('openNode', $event)"
    @toggle-favorite="onToggleFavorite"
    @create-folder="emit('create', 'create-folder')"
    @create-memory="emit('create', 'create-memory')"
    @rename-folder="emit('renameFolder')"
    @delete-folder="emit('deleteFolder')"
    @open-trash="emit('openTrash')"
  />
</template>
