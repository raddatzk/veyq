// A kind's `detail` mounted as both hosts mount it: inside core's page `core/node/components/NodePage.vue`, which draws the
// node's header (from the kind's descriptor and what the detail contributes) with core's own actions of the node (the heart,
// the cover and its dialog, "Copy to…", the download, Report), below the page's doors (`core/node/nodeHost.ts`) - by default
// the doors of the page `props.access` is on (`hostFor`: a member's every one, a guest's the guest page's), each recording
// what the page was asked (`calls`). A test of the header mounts through here (or `src/test/nodeHeader.ts`'s `withHeader`);
// a detail mounted bare draws no header at all.
import { mount, type ComponentMountingOptions } from '@vue/test-utils'
import { ref, type Component } from 'vue'
import type { LoadedCover } from '../core/node/cover/coverModel'
import type { TreeNode } from '../core/node/tree'
import NodePage from '../core/node/components/NodePage.vue'
import type { NodeDetailProps } from '../core/applications/registry'
import { coreNodeActionsKey, type CoreNodeActions } from '../core/node/coreNodeActions'
import type { CoreHeaderAction } from '../core/node/nodeHeader'
import type { UseNodeCover } from '../core/node/cover/useNodeCover'
import { hostFor, type RecordingHost } from './nodeHost'

export function mountNodePage(
  detail: Component,
  options: Omit<ComponentMountingOptions<typeof NodePage>, 'props'> & { props: NodeDetailProps; host?: RecordingHost },
) {
  const { host, ...rest } = options
  const rec = host ?? hostFor(options.props.access)
  const given = (rest.global ?? {}) as NonNullable<ComponentMountingOptions<typeof NodePage>['global']>
  const global = { ...given, provide: { ...rec.provide, ...((given.provide as object | undefined) ?? {}) } }
  const w = mount(NodePage, { ...rest, global, props: { ...options.props, detail } } as ComponentMountingOptions<typeof NodePage>)
  return Object.assign(w, { calls: rec.calls })
}

/** A `global.provide` entry standing in for the view around a header: core's actions of a node are `core` (none for the
 *  space's top level), and its cover the stand-in `cover` (by default: none resolved, none settable). For a test of where a
 *  page puts core's actions, not of which ones core offers. */
export function coreActionsStandIn(core: readonly CoreHeaderAction[], cover: UseNodeCover = coverStandIn()): Record<symbol, CoreNodeActions> {
  return { [coreNodeActionsKey as symbol]: { cover, header: (node) => (node ? [...core] : []), row: () => [] } }
}

/** A view's cover as a header reads it: `covers` resolved by node id, `canSet` the right's answer, `open` recorded. */
export function coverStandIn(opts: { covers?: Map<string, LoadedCover>; canSet?: boolean; open?: (node: TreeNode) => void } = {}): UseNodeCover {
  const covers = ref(opts.covers ?? new Map<string, LoadedCover>())
  return {
    covers,
    has: (id) => covers.value.has(id),
    ensure: () => {},
    headerActions: () => [],
    rowActions: () => [],
    canSet: () => opts.canSet ?? false,
    editor: ref(null),
    open: (node) => opts.open?.(node),
    close: () => {},
    save: () => {},
  }
}
