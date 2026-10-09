// A member's sibling pre-check (inbox #389, ADR 0080 § a name's slug is folded by its node's kind): before a create or
// a rename is sent, the dialog looks for a sibling in the loaded, decrypted tree that the new name collides with, and
// names it. It compares what the server compares — siblings' slug tokens share the parent's key, so equal tokens are
// equal slugs, each folded by ITS OWN kind's function (`registry.slugFor`), whatever the two kinds are: a folder `a.b`
// (`a-b`) and a file `a.b` (`a.b`) coexist, a folder `a.b` and a file `a-b` collide. The guest's twin is
// `core/sharing/guestTree.ts`. The server's `409 slug_taken` stays the race backstop (a sibling created since the tree
// was loaded, or one this member cannot open).
import { registry } from '../applications/registry'
import type { NodeKind } from './kind'
import type { TreeNode } from './tree'

/** The sibling under `parentId` (null: the scope root) whose name folds to the same slug as `name` would for a node of
 *  `kind`, or null. `self` is the node being renamed: its own entry never collides with itself. */
export function collidingSibling(
  nodes: readonly TreeNode[],
  parentId: string | null,
  kind: NodeKind,
  name: string,
  self: string | null = null,
): TreeNode | null {
  const slug = registry.slugFor(kind, name)
  return nodes.find((n) => n.parentId === parentId && n.id !== self && registry.slugFor(n.kind, n.name) === slug) ?? null
}

/** "There is already a file named “x” here." — the sibling's noun from ITS kind (`KindDescriptor.nounWithArticle`), the
 *  neutral `node.anItem` for a kind that registers none: core names no application kind in its own strings. */
export function nameTakenMessage(t: (key: string, named?: Record<string, unknown>) => string, sibling: TreeNode): string {
  const info = registry.kind(sibling.kind)
  const noun = (info.supported && info.nounWithArticle) || 'node.anItem'
  return t('node.nameTakenBy', { kind: t(noun), name: sibling.name })
}
