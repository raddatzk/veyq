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
import { searchDocument, type SearchDocument, type SearchFacets } from './model'

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
      out.set(node.id, {
        seal,
        doc: searchDocument({
          scopeId, kind: node.kind, id: node.id, nodeId: node.id, revision: nodeRevision(node), name: node.name,
          path: pathOf(node, byId), facets,
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

/** The names above `node`, outermost first (a cycle a tampered tree could hold ends the walk). */
function pathOf(node: TreeNode, byId: ReadonlyMap<string, TreeNode>): string[] {
  const names: string[] = []
  const seen = new Set<string>([node.id])
  let cur = node.parentId ? byId.get(node.parentId) : undefined
  while (cur && !seen.has(cur.id)) {
    seen.add(cur.id)
    names.unshift(cur.name)
    cur = cur.parentId ? byId.get(cur.parentId) : undefined
  }
  return names
}
