// A member's sibling pre-check (inbox #389, ADR 0080 § a name's slug is folded by its node's kind): before a create or
// a rename is sent, the dialog looks for a sibling in the loaded, decrypted tree that the new name collides with, and
// names it. It compares what the server compares — siblings' slug tokens share the parent's key, so equal tokens are
// equal token inputs (`registry.tokenInputFor`): each name folded by ITS OWN kind's function, behind its application's
// route slug where it claimed one. So only siblings of one route group meet (ADR 0080 decision 16, amended 2026-11-01): a
// Memory and a Pot `Rom` coexist; among the plain names a folder `a.b` (`a-b`) and a file `a.b` (`a.b`) coexist, a
// folder `a.b` and a file `a-b` collide. The guest's twin is
// `core/sharing/guestTree.ts`. The server's `409 slug_taken` stays the race backstop (a sibling created since the tree
// was loaded, or one this member cannot open).
import { registry } from '../applications/registry'
import type { NodeKind } from './kind'
import type { TreeNode } from './tree'

/** The sibling under `parentId` (null: the scope root) whose token input equals the one `name` would have for a node of
 *  `kind` (the same route group, the same slug), or null. `self` is the node being renamed: its own entry never collides
 *  with itself. */
export function collidingSibling(
  nodes: readonly TreeNode[],
  parentId: string | null,
  kind: NodeKind,
  name: string,
  self: string | null = null,
): TreeNode | null {
  const input = registry.tokenInputFor(kind, name)
  return nodes.find((n) => n.parentId === parentId && n.id !== self && registry.tokenInputFor(n.kind, n.name) === input) ?? null
}

/** "There is already a file named “x” here." — the sibling's noun from ITS kind (`KindDescriptor.nounWithArticle`), the
 *  neutral `node.anItem` for a kind that registers none: core names no application kind in its own strings. */
export function nameTakenMessage(t: (key: string, named?: Record<string, unknown>) => string, sibling: TreeNode): string {
  const info = registry.kind(sibling.kind)
  const noun = (info.supported && info.nounWithArticle) || 'node.anItem'
  return t('node.nameTakenBy', { kind: t(noun), name: sibling.name })
}
