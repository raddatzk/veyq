<script setup lang="ts">
// Core's frame of an opened node's page (owner 2026-11-01, ADR 0110): both hosts - the member's `views/SpaceView.vue` and a
// guest's `core/sharing/ShareTreePage.vue` - mount a kind's registered `detail` through it, never bare. It supplies core's
// actions of the node (`../coreNodeActions.ts`: the heart, the cover, "Copy to…", the ZIP download and Report) to the
// `NodeHeader` the detail opens with, so every kind's header carries them - Report above all, a legal duty - without the
// application building or forwarding one, and it mounts the cover dialog that core's cover action opens. The copy and the
// download are the page's doors (`../nodeHost.ts`), which the detail below injects too for everything else it asks of the
// page (rename, move, delete, share, a child's create, opening a node): one host, no event passes through here.
import type { Component } from 'vue'
import type { NodeDetailProps } from '../../applications/registry'
import { provideCoreNodeActions, useCoreNodeActions } from '../coreNodeActions'
import { injectNodeHost } from '../nodeHost'
import CoverEditor from '../cover/components/CoverEditor.vue'

defineOptions({ inheritAttrs: false })
const props = defineProps<NodeDetailProps & { /** The kind's registered page. */ detail: Component }>()
const host = injectNodeHost()

// The page's ONE cover (the node's): the header's cover action opens its dialog, and a kind's page that draws the cover or
// a control of its own takes this very instance (`injectCoreNodeActions`) - one resolution, one dialog.
const actions = useCoreNodeActions(() => props.access, () => props.tree, () => [props.node], {
  ...(host.copy ? { copy: (nodes) => host.copy!(nodes) } : {}),
  ...(host.download ? { download: (node) => host.download!(node) } : {}),
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
