// The results, grouped by where the member stands (ADR 0125 decision 5, uc.platform.search step 6): the open node first -
// what its page contributed and the nodes below it -, then the rest of the active space, then every other space; after the
// spaces, the members of the tenant and the settings sections (decision 9: they live in no space, so their fixed place after
// content keeps a query's content results where they were). Ranking happens within a group only (`query.ts`); a group with
// nothing is left out, so its heading never says "none".
import { byRank, matchAll, type Match, type SearchQuery } from './query'
import { PERSON_DOCUMENT, SETTINGS_DOCUMENT, type SearchDocument } from './model'
import type { TreeNode } from '../node/tree'

export type SearchGroupId = 'open' | 'space' | 'other' | 'people' | 'settings'

/** Every group, in the order they are shown. */
export const SEARCH_GROUPS: readonly SearchGroupId[] = ['open', 'space', 'other', 'people', 'settings']

export interface SearchGroup {
  id: SearchGroupId
  /** Every match of the group, ranked. */
  results: readonly SearchDocument[]
}

/** Where the member stands. */
export interface SearchContext {
  /** The space on screen, or null (no space open). */
  activeScopeId: string | null
  /** The node whose page is on screen (not a folder level), in the active space, or null. */
  openNodeId: string | null
  /** The active space's tree, to tell what lies below the open node. */
  activeTree: readonly TreeNode[]
}

/** The ids of every node below `nodeId` in `tree` (not the node itself). */
export function descendantsOf(tree: readonly TreeNode[], nodeId: string): Set<string> {
  const children = new Map<string, string[]>()
  for (const n of tree) if (n.parentId) children.set(n.parentId, [...(children.get(n.parentId) ?? []), n.id])
  const out = new Set<string>()
  const stack = [...(children.get(nodeId) ?? [])]
  while (stack.length) {
    const id = stack.pop()!
    if (out.has(id)) continue
    out.add(id)
    stack.push(...(children.get(id) ?? []))
  }
  return out
}

/**
 * The matches of `query` in `docs`, grouped (see the header). A contributed document counts only for the node it was
 * contributed for, and only while that node is the one open in the active space: a page left behind contributes nothing.
 * A person and a settings section are no content: with any chip set - a filter over content - neither group is shown.
 */
export function searchGroups(docs: readonly SearchDocument[], query: SearchQuery, ctx: SearchContext, collator: Intl.Collator): SearchGroup[] {
  const below = ctx.openNodeId ? descendantsOf(ctx.activeTree, ctx.openNodeId) : new Set<string>()
  const groups: Record<SearchGroupId, Match[]> = { open: [], space: [], other: [], people: [], settings: [] }
  const filtered = query.chips.length > 0
  for (const m of matchAll(docs, query)) {
    const d = m.doc
    if (d.kind === PERSON_DOCUMENT) {
      if (!filtered) groups.people.push(m)
    } else if (d.kind === SETTINGS_DOCUMENT) {
      if (!filtered) groups.settings.push(m)
    } else if (d.open) {
      if (ctx.openNodeId && d.scopeId === ctx.activeScopeId && d.nodeId === ctx.openNodeId) groups.open.push(m)
    } else if (d.scopeId !== ctx.activeScopeId) groups.other.push(m)
    else if (below.has(d.id)) groups.open.push(m)
    else groups.space.push(m)
  }
  const order = byRank(collator)
  return SEARCH_GROUPS.filter((id) => groups[id].length > 0).map((id) => ({ id, results: groups[id].sort(order).map((m) => m.doc) }))
}
