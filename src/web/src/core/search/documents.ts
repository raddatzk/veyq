// One space's node documents (ADR 0125 decisions 2 and 6): every node of an opened tree as a document - its name, the names
// above it, and the facets its kind adds (`KindDescriptor.search.facets`), asked while the walk holds the tree's keys.
//
// **No key outlives the walk.** The node keys are opened once each (`core/node/keyResolver.ts`) and zeroed before this
// returns, whatever a hook did; the scope key is the caller's, who zeroes it (the index) or keeps owning it (the active
// space's). **Rebuilt per seal, not per walk:** a node whose sealed meta is the one a previous document was derived from keeps
// that document's facets, so a re-opened tree unseals only what moved. **One node per step** ({@link nodeDocumentSteps}): the
// index runs a space it opened itself in slices, so a big tree never holds the main thread whole (cr-search-at-volume).
import { registry } from '../applications/registry'
import { keyResolver } from '../node/keyResolver'
import { runThrough, type TreeNode } from '../node/tree'
import type { ScopeKey } from '../../features/spaces/epochs'
import { searchDocument, type ScopeDocument, type SearchDocument, type SearchFacets } from './model'

/** A node's document and the seal its facets were read from (what the next walk compares). */
export interface NodeDocument {
  doc: SearchDocument
  seal: string | null
}

/** The revision a node's document is derived at: its placement (name, parent) and its sealed meta. */
export const nodeRevision = (node: TreeNode): string => `${node.placementRevision}:${node.metaRevision}`

/**
 * The documents of every node of `nodes` - one space's opened tree - by node id. `previous` is the last walk's (by node id):
 * a node whose `wrappedMeta` is the seal its document was read from keeps its facets.
 */
export function nodeDocuments(
  scopeId: string, epochs: ScopeKey, nodes: readonly TreeNode[], previous?: ReadonlyMap<string, NodeDocument>,
): Map<string, NodeDocument> {
  return runThrough(nodeDocumentSteps(scopeId, epochs, nodes, previous))
}

/** {@link nodeDocuments} one node per step, for a caller that runs it in slices (`searchIndex.ts`). The keys it opened are
 *  zeroed when it ends - finished, thrown, or stopped early by the caller's `return()`. */
export function* nodeDocumentSteps(
  scopeId: string, epochs: ScopeKey, nodes: readonly TreeNode[], previous?: ReadonlyMap<string, NodeDocument>,
): Generator<void, Map<string, NodeDocument>, void> {
  const byId = new Map(nodes.map((n) => [n.id, n]))
  const resolver = keyResolver(scopeId, epochs, nodes)
  const out = new Map<string, NodeDocument>()
  try {
    for (const node of nodes) {
      const seal = node.wrappedMeta ?? null
      const before = previous?.get(node.id)
      const facets = before && before.seal === seal ? before.doc.facets : facetsOf(scopeId, node, byId, epochs, resolver.keyOf)
      const above = placeOf(node, byId)
      out.set(node.id, {
        seal,
        doc: searchDocument({
          scopeId, kind: node.kind, id: node.id, nodeId: node.id, revision: nodeRevision(node), name: node.name,
          path: above.names, pathIds: above.ids, facets,
        }),
      })
      yield
    }
  } finally {
    resolver.zero()
  }
  return out
}

function facetsOf(
  scopeId: string, node: TreeNode, byId: ReadonlyMap<string, TreeNode>, epochs: ScopeKey, keyOf: (n: TreeNode) => Uint8Array | null,
): SearchFacets {
  const kind = registry.kind(node.kind)
  const hook = kind.supported ? kind.search?.facets : undefined
  if (!hook) return {}
  const nodeKey = keyOf(node)
  const parent = node.parentId ? byId.get(node.parentId) : undefined
  const parentKey = node.parentId === null ? epochs.key : parent ? keyOf(parent) : null
  if (!nodeKey || !parentKey) return {}
  try {
    return hook({ scopeId, node, nodeKey, parentKey }) ?? {}
  } catch {
    return {} // a meta that does not open is a node found by its name alone
  }
}

/**
 * The documents of an application's objects in one space (`ApplicationSearch.scopeDocuments`, ADR 0125 decision 8a), one
 * object per step: where each lives is its node's path and the node's own name (a photo's Memory). An object whose node is
 * not in the space's tree is left out - the walk could not have opened it.
 */
export function* scopeDocumentSteps(
  scopeId: string, objects: readonly ScopeDocument[], byId: ReadonlyMap<string, TreeNode>,
): Generator<void, SearchDocument[], void> {
  const out: SearchDocument[] = []
  // one path per node, shared by every object of it (a Memory's thousands of photos hold one pair of arrays)
  const places = new Map<string, NodePlace>()
  for (const o of objects) {
    const node = byId.get(o.nodeId)
    if (node) {
      let at = places.get(node.id)
      if (!at) places.set(node.id, (at = placeWithin(node, byId)))
      out.push(searchDocument({
        scopeId, kind: o.kind, id: o.id, nodeId: o.nodeId, revision: o.revision, name: o.name, path: at.names, pathIds: at.ids,
        facets: o.facets, icon: o.icon,
      }))
    }
    yield
  }
  return out
}

/** Where a document lies: the names and the ids of the nodes above it, outermost first. */
export interface NodePlace {
  names: string[]
  ids: string[]
}

/** The nodes above `node`, outermost first (a cycle a tampered tree could hold ends the walk). */
export function placeOf(node: Pick<TreeNode, 'id' | 'parentId'>, byId: ReadonlyMap<string, Pick<TreeNode, 'id' | 'parentId' | 'name'>>): NodePlace {
  const names: string[] = []
  const ids: string[] = []
  const seen = new Set<string>([node.id])
  let cur = node.parentId ? byId.get(node.parentId) : undefined
  while (cur && !seen.has(cur.id)) {
    seen.add(cur.id)
    names.unshift(cur.name)
    ids.unshift(cur.id)
    cur = cur.parentId ? byId.get(cur.parentId) : undefined
  }
  return { names, ids }
}

/** Where an object inside `node` lies (a photo of a Memory): the nodes above it and the node itself. */
export function placeWithin(node: Pick<TreeNode, 'id' | 'parentId' | 'name'>, byId: ReadonlyMap<string, Pick<TreeNode, 'id' | 'parentId' | 'name'>>): NodePlace {
  const above = placeOf(node, byId)
  return { names: [...above.names, node.name], ids: [...above.ids, node.id] }
}
