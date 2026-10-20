<script setup lang="ts">
// Mounts core's cover dialog for a view's `useNodeCover` (inbox #411): every view that offers the cover action renders ONE of
// these beside its content - the level (rows and the folder header), a drop box's page, a Memory's page. It is open while the
// composable's `editor` state is set, and hands the dialog's intent back to the composable's `save` for that node.
import type { UseNodeCover } from '../useNodeCover'
import CoverDialog from './CoverDialog.vue'

defineProps<{ cover: UseNodeCover }>()
</script>

<template>
  <CoverDialog
    v-if="cover.editor.value"
    :key="cover.editor.value.node.id"
    :cover="cover.editor.value.cover"
    :existing-src="cover.editor.value.existingSrc"
    @save="cover.editor.value && cover.save(cover.editor.value.node, $event)"
    @close="cover.close()"
  />
</template>
