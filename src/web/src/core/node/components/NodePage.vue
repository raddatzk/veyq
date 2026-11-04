<script setup lang="ts">
// Core's page of an opened node (owner 2026-11-01 and 2026-11-03, ADR 0110 decision 10): both hosts - the member's
// `views/SpaceView.vue` and a guest's `core/sharing/ShareTreePage.vue` - mount a kind's registered `detail` through it, never
// bare. **It draws the page's header, the same row for every application:** core's `NodeHeader` from the kind's descriptor
// (`registry.kind(node.kind)`: its icon, whether it takes a cover, its priority `header.order`) and from what the detail
// below contributes as data (`../nodeHeaderContribution.ts`: a subtitle, the application's actions, the rename in place, its
// facts - a date, a place, people, a line of text - and a banner drawn above the header). Then the detail, the application's
// own body.
//
// It supplies core's actions of the node (`../coreNodeActions.ts`: the heart, the cover dialog, "Copy to…", the ZIP download and
// Report) to the header, so every kind's header carries them - Report above all, a legal duty - without the application
// building or forwarding one, and it mounts the cover dialog that the cover action and the header's cover open. People in the
// header link to a profile and show a member's picture on a member's page only (`linkPeople`): a share page never does. The
// copy and the download are the page's doors (`../nodeHost.ts`), which the detail below injects too for everything else it
// asks of the page (rename, move, delete, share, a child's create, opening a node): one host, no event passes through here.
import { computed, type Component } from 'vue'
import { registry, type NodeDetailProps } from '../../applications/registry'
import { provideCoreNodeActions, useCoreNodeActions } from '../coreNodeActions'
import { provideNodeHeader } from '../nodeHeaderContribution'
import { injectNodeHost } from '../nodeHost'
import CoverEditor from '../cover/components/CoverEditor.vue'
import NodeHeader from './NodeHeader.vue'

defineOptions({ inheritAttrs: false })
const props = defineProps<NodeDetailProps & { /** The kind's registered page. */ detail: Component }>()
const host = injectNodeHost()

// The page's ONE cover (the node's): the header's cover action and its leading visual open its dialog - one resolution, one
// dialog.
const actions = useCoreNodeActions(() => props.access, () => props.tree, () => [props.node], {
  ...(host.copy ? { copy: (nodes) => host.copy!(nodes) } : {}),
  ...(host.download ? { download: (node) => host.download!(node) } : {}),
})
provideCoreNodeActions(actions)

const kind = computed(() => {
  const d = registry.kind(props.node.kind)
  return d.supported ? d : null
})
const header = provideNodeHeader()
const banner = computed(() => header.value.banner ?? null)
</script>

<template>
  <div class="veyq-node-page">
    <component :is="banner.component" v-if="banner" v-bind="banner.props ?? {}" />
    <NodeHeader
      :node="node"
      :title="node.name"
      :icon="kind?.icon"
      :cover="!!kind?.cover"
      :order="kind?.header?.order"
      :subtitle="header.subtitle"
      :actions="header.actions"
      :rename="header.rename ?? null"
      :facts="header.facts"
      :link-people="access.mode === 'member'"
    />
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
  </div>
  <CoverEditor :cover="actions.cover" />
</template>
