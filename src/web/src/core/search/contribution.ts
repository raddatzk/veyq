// What the page of the node on screen adds to the search (ADR 0125 decision 6): documents only it holds - Memories' photos
// and videos of the open Memory, from its already-opened listing (ADR 0105), at no fetch of their own. In the shape of the
// header's door (`core/node/nodeHeaderContribution.ts`): the detail calls {@link contributeSearchDocuments} in its setup with
// a builder over its own state, and the search re-reads it whenever that state moves; the contribution is withdrawn when
// the caller unmounts.
//
// **Only the member's page takes one.** The member's space page provides the sink ({@link provideSearchContributions}); a
// share guest's page does not (a guest has no search, ADR 0125 decision 7), and a bookmark's share shown in place inside the
// member's page blocks it ({@link provideNoSearchContributions}): what a link serves is not indexed beyond the bookmark's own
// name. Mounted without a sink - a guest's page, a unit test of the detail alone - the call is a no-op.
import { inject, onBeforeUnmount, provide, shallowRef, type InjectionKey } from 'vue'
import type { ContributedDocument } from './model'

/** What a page contributes: the node it is the page of, and the documents it holds. */
export interface SearchContribution {
  scopeId: string
  nodeId: string
  documents: readonly ContributedDocument[]
}

type Builder = () => SearchContribution | null

/** The one contribution in force: the page's (one detail on screen; the last caller wins). Read by the search. */
const current = shallowRef<Builder | null>(null)

const sinkKey: InjectionKey<boolean> = Symbol('veyq.searchContributions')

/** The member's space page: the details below it may contribute. */
export function provideSearchContributions(): void {
  provide(sinkKey, true)
}

/** A region below the member's page whose details contribute nothing (a bookmark's share shown in place, ADR 0121). */
export function provideNoSearchContributions(): void {
  provide(sinkKey, false)
}

/** A detail: contribute `build` for as long as the caller is mounted. Called in setup. */
export function contributeSearchDocuments(build: Builder): void {
  if (!inject(sinkKey, false)) return
  current.value = build
  onBeforeUnmount(() => {
    if (current.value === build) current.value = null
  })
}

/** The contribution in force, or null (reactive: it re-reads the page's state). */
export function searchContribution(): SearchContribution | null {
  return current.value?.() ?? null
}
