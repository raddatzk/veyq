// What a node page's detail adds to the header core draws above it (owner 2026-11-03, ADR 0110 decision 10). Core owns every
// node page's header: `components/NodePage.vue` draws it from the kind's descriptor (icon, cover, order) and from what the
// detail below contributes here - a subtitle, the application's own actions, its facts (`NodeHeaderFact`: a date, a place,
// people, a line of text) and a banner above the row. What it contributes depends on the detail's own state (a Memory's
// gallery dates it, a file's listing names its uploader), so it is a reactive builder, not a registration.
//
// **One door, data only.** The detail calls {@link contributeNodeHeader} in its setup with a builder that reads its state; the
// page re-reads it whenever that state changes. A detail never imports `NodeHeader.vue` (`validate_web_core_imports_no_applications.py`'s
// `header` rule): it cannot draw a header of its own, leave core's actions out or link a person on a share page. Mounted
// without core's page around it (a unit test of the detail alone) the call is a no-op and no header is drawn. The page holds
// one contribution: the detail's (the last caller wins; a page mounts one detail). It is withdrawn when the caller unmounts.
import { computed, inject, onBeforeUnmount, provide, shallowRef, type ComputedRef, type InjectionKey, type ShallowRef } from 'vue'
import type { NodeHeaderAction, NodeHeaderBanner, NodeHeaderFact } from './nodeHeader'

/** What a detail adds to its page's header. Everything is optional; core's own actions (the heart, the cover, copy, the
 *  download, Report) are core's and never contributed. */
export interface NodeHeaderContribution {
  /** A line under the name ("12 items", "In EUR"). */
  subtitle?: string
  /** The application's actions, each `shown` by its fail-closed rights function; merged with core's by the kind's `order`. */
  actions?: readonly NodeHeaderAction[]
  /** What core draws under the subtitle, in this order. */
  facts?: readonly NodeHeaderFact[]
  /** Drawn above the header row (Memories' banner). */
  banner?: NodeHeaderBanner | null
}

type Builder = () => NodeHeaderContribution

/** The page's sink; exported for a test harness that stands in for core's page (`src/test/nodeHeader.ts`). */
export const nodeHeaderContributionKey: InjectionKey<ShallowRef<Builder | null>> = Symbol('veyq.nodeHeaderContribution')

/** Core's node page: receive what the detail below contributes. The answer is the current contribution (empty without one). */
export function provideNodeHeader(): ComputedRef<NodeHeaderContribution> {
  const sink = shallowRef<Builder | null>(null)
  provide(nodeHeaderContributionKey, sink)
  return computed(() => sink.value?.() ?? {})
}

/** A detail: contribute `build` to the header of the page around it, for as long as the caller is mounted. Called in setup. */
export function contributeNodeHeader(build: Builder): void {
  const sink = inject(nodeHeaderContributionKey, null)
  if (!sink) return
  sink.value = build
  onBeforeUnmount(() => {
    if (sink.value === build) sink.value = null
  })
}
