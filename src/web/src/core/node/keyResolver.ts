// The content keys of a whole opened tree, each opened once (ADR 0080 decision 1: a node's key under its parent's, a
// scope-root node's under the scope key). A walk that reads every node's sealed meta - the search's documents
// (`core/search/documents.ts`), Memories' participant index - would otherwise open each ancestor chain again per node.
//
// **What it holds.** Every key it answers is a fresh array it opened itself (`openContentKey`); the scope key and an identity
// folder's entry key are read, never kept. So {@link KeyResolver.zero} wipes exactly what the walk opened and nothing the
// caller owns - the scope key stays the caller's to zero.
import { childEntryKey, openContentKey, type TreeNode } from './tree'
import type { ScopeKey } from '../../features/spaces/epochs'

export interface KeyResolver {
  /** `node`'s content key, or null when its chain does not open (a tampered wrap, a guest folder's key that does not open). */
  keyOf(node: TreeNode): Uint8Array | null
  /** Wipe every key it opened; the resolver answers nothing afterwards. */
  zero(): void
}

/** A memoizing resolver over `nodes`, opened under the scope's one key. */
export function keyResolver(scopeId: string, epochs: ScopeKey, nodes: readonly TreeNode[]): KeyResolver {
  const byId = new Map(nodes.map((n) => [n.id, n]))
  const keys = new Map<string, Uint8Array | null>()
  let zeroed = false
  const resolve = (node: TreeNode, depth: number): Uint8Array | null => {
    if (keys.has(node.id)) return keys.get(node.id)!
    let key: Uint8Array | null = null
    try {
      if (node.parentId === null) key = openContentKey(scopeId, epochs.key, node)
      else if (depth <= nodes.length) {
        const parent = byId.get(node.parentId)
        const parentKey = parent ? resolve(parent, depth + 1) : null
        if (parent && parentKey) key = openContentKey(scopeId, childEntryKey(scopeId, parent, parentKey, node), node)
      }
    } catch {
      key = null // a chain that does not open: this node is not read
    }
    keys.set(node.id, key)
    return key
  }
  return {
    keyOf: (node) => (zeroed ? null : resolve(node, 0)),
    zero() {
      zeroed = true
      for (const key of keys.values()) key?.fill(0)
      keys.clear()
    },
  }
}
