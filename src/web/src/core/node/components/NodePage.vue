<script setup lang="ts">
// Core's frame of an opened node's page (owner 2026-11-01, ADR 0110): both hosts - the member's `views/MemoryHomeView.vue`
// and a guest's `core/sharing/ShareTreePage.vue` - mount a kind's registered `detail` through it, never bare. It supplies
// core's actions of the node (`../coreNodeActions.ts`: the heart, the cover, "Copy to…", the ZIP download and Report) to
// the `NodeHeader` the detail opens with, so every kind's header carries them - Report above all, a legal duty - without
// the application building or forwarding one, and it mounts the cover dialog that core's cover action opens. The copy
// and the download are the host's dialogs: this frame emits `copy` / `download` for the node, and every other event of
// the detail (`NodeDetailEmits`) passes through to the host unchanged.
import type { Component } from 'vue'
import type { NodeDetailProps } from '../../applications/registry'
import { provideCoreNodeActions, useCoreNodeActions } from '../coreNodeActions'
import CoverEditor from '../cover/components/CoverEditor.vue'

defineOptions({ inheritAttrs: false })
const props = defineProps<NodeDetailProps & { /** The kind's registered page. */ detail: Component }>()
const emit = defineEmits<{
  /** Copy the node elsewhere: the host mounts core's `CopyNodesDialog`. */
  copy: []
  /** Download the node and its subtree as a ZIP: the host mounts core's `ExportSubtreeDialog`. */
  download: []
}>()

// The page's ONE cover (the node's): the header's cover action opens its dialog, and a kind's page that draws the cover or
// a control of its own takes this very instance (`injectCoreNodeActions`) - one resolution, one dialog.
const actions = useCoreNodeActions(() => props.access, () => props.tree, () => [props.node], {
  copy: () => emit('copy'),
  download: () => emit('download'),
})
provideCoreNodeActions(actions)
</script>

<template>
  <component
    :is="detail"
    v-bind="$attrs"
    :node="node"
    :scope-id="scopeId"
    :epochs="epochs"
    :content-key="contentKey"
    :children="children"
    :access="access"
    :tree="tree"
  />
  <CoverEditor :cover="actions.cover" />
</template>
