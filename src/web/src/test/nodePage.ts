// A kind's `detail` mounted as both hosts mount it: inside core's frame `core/node/components/NodePage.vue`, which supplies
// core's own header actions of the node (the heart, the cover and its dialog, "Copy to…", the download, Report) and emits
// `copy` / `download` itself. A test of what core adds to a page mounts through here; one of the detail alone may mount the
// detail bare (its header then carries the application's actions only). The detail's own events are its component's:
// `w.findComponent(Detail).emitted(...)`.
import { mount, type ComponentMountingOptions } from '@vue/test-utils'
import type { Component } from 'vue'
import NodePage from '../core/node/components/NodePage.vue'
import type { NodeDetailProps } from '../core/applications/registry'
import { coreNodeActionsKey, type CoreNodeActions } from '../core/node/coreNodeActions'
import type { CoreHeaderAction } from '../core/node/nodeHeader'
import type { UseNodeCover } from '../core/node/cover/useNodeCover'

export function mountNodePage(detail: Component, options: Omit<ComponentMountingOptions<typeof NodePage>, 'props'> & { props: NodeDetailProps }) {
  return mount(NodePage, { ...options, props: { ...options.props, detail } } as ComponentMountingOptions<typeof NodePage>)
}

/** A `global.provide` entry standing in for the view around a header: core's actions of a node are `core` (none for the
 *  space's top level). For a test of where a page puts core's actions, not of which ones core offers. */
export function coreActionsStandIn(core: readonly CoreHeaderAction[]): Record<symbol, CoreNodeActions> {
  return { [coreNodeActionsKey as symbol]: { cover: {} as UseNodeCover, header: (node) => (node ? [...core] : []), row: () => [] } }
}
