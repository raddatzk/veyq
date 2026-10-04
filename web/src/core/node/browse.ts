// Client-side tree navigation (cr-0030, uc.memory.browse). The operator-blind server computes no navigation,
// so the current level + the breadcrumb are derived here from the locally-decrypted tree (`listTree`).
import type { TreeNode } from './tree'
import type { Crumb } from '../../app/breadcrumb'
import { hashForNode } from './location'
import { NodeKind } from '../../api/generated/content'

const byOrder = (a: TreeNode, b: TreeNode): number => a.orderIndex - b.orderIndex || a.name.localeCompare(b.name)

/** One level of the tree: the platform's folders, and every other kind's nodes bucketed by kind. */
export interface Level {
  folders: TreeNode[]
  /** The non-folder children keyed by kind, each bucket ordered; a kind with no child at this level has no key.
   *  Core reads no key by name (ADR 0079) — an application reads its own kinds' buckets. */
  byKind: ReadonlyMap<NodeKind, TreeNode[]>
}

/** The children of `parentId` (null = the scope root), split by kind, each bucket ordered (`orderIndex`, then
 *  name). Folders are the platform's and stay a named field; every other kind lands in `byKind`. */
export function childrenOf(tree: TreeNode[], parentId: string | null): Level {
  const folders: TreeNode[] = []
  const byKind = new Map<NodeKind, TreeNode[]>()
  for (const n of tree) {
    if ((n.parentId ?? null) !== parentId) continue
    if (n.kind === NodeKind.Folder) {
      folders.push(n)
      continue
    }
    const bucket = byKind.get(n.kind)
    if (bucket) bucket.push(n)
    else byKind.set(n.kind, [n])
  }
  folders.sort(byOrder)
  for (const bucket of byKind.values()) bucket.sort(byOrder)
  return { folders, byKind }
}

/** The breadcrumb from the root down to `nodeId` (null = root): `[root, …ancestors, node]`, each crumb
 *  carrying its `#fragment` in the active `scope` so the topbar can link to it. */
export function breadcrumbOf(tree: TreeNode[], nodeId: string | null, rootName: string, scope: string): Crumb[] {
  const byId = new Map(tree.map((n) => [n.id, n]))
  const trail: Crumb[] = []
  let cur = nodeId ? byId.get(nodeId) ?? null : null
  while (cur) {
    trail.unshift({ id: cur.id, name: cur.name, hash: hashForNode(tree, cur.id, scope) })
    cur = cur.parentId ? byId.get(cur.parentId) ?? null : null
  }
  trail.unshift({ id: null, name: rootName, hash: hashForNode(tree, null, scope) })
  return trail
}

/** A node by id (e.g. to title the folder/Memory currently open), or null. */
export function nodeById(tree: TreeNode[], nodeId: string | null): TreeNode | null {
  if (!nodeId) return null
  return tree.find((n) => n.id === nodeId) ?? null
}
