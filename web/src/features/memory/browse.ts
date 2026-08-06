// Client-side tree navigation (cr-0030, uc.memory.browse). The operator-blind server computes no navigation,
// so the current level + the breadcrumb are derived here from the locally-decrypted tree (`listTree`).
import type { TreeNode } from './tree'
import type { Crumb } from '../../app/breadcrumb'
import { hashForNode } from './location'
import { NodeKind } from '../../api/generated/content'

const byOrder = (a: TreeNode, b: TreeNode): number => a.orderIndex - b.orderIndex || a.name.localeCompare(b.name)

/** The children of `parentId` (null = the scope root), split by kind, each ordered. Chapters live inside a
 *  Memory (or a chapter); folders + Memories live at the root or in a folder. */
export function childrenOf(
  tree: TreeNode[],
  parentId: string | null,
): { folders: TreeNode[]; memories: TreeNode[]; chapters: TreeNode[] } {
  const kids = tree.filter((n) => (n.parentId ?? null) === parentId)
  return {
    folders: kids.filter((n) => n.kind === NodeKind.Folder).sort(byOrder),
    memories: kids.filter((n) => n.kind === NodeKind.Memory).sort(byOrder),
    chapters: kids.filter((n) => n.kind === NodeKind.Chapter).sort(byOrder),
  }
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
