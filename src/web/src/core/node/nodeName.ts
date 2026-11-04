// A node's name, as a member writes it: ONE check, ONE rename write and ONE wording of a refusal for both ways in — core's
// name dialog (`FolderNameDialog.vue`, a create or a rename) and the header's name edited in place (`components/NodeHeader.vue`,
// through the page's `renameTo` door, `nodeHost.ts` / `nodeDialogs.ts`). Two copies of "is this name free here" and "what
// does a 409 say" would drift apart; this is the one.
//
// **The check** ({@link refuseTakenName}): before a create or a rename is sent, the loaded sibling the new name collides with
// - folded by each node's own kind (`siblingCollision.ts`, inbox #389) - refuses it, naming that sibling. The server's
// `409 slug_taken` stays the race backstop. **The write** ({@link renameMemberNode}): a rename is a placement to the same
// parent (ADR 0080 decision 3) - the key re-wraps, the name re-seals under the parent's key -, then every share rooted at the
// node gets the new name re-sealed (ADR 0080 decision 14), then the tree is re-read, so the caller's "done" means the write
// stands AND the loaded tree carries it (inbox #475: the page follows the node to its new path in the same step).
import { renameNode, type TreeNode } from './tree'
import { loadTree, treeStore } from './treeStore'
import type { NodeKind } from './kind'
import { collidingSibling, nameTakenMessage } from './siblingCollision'
import { registry } from '../applications/registry'
import { resealShareNames } from '../sharing/manageShares'
import { httpStatus, HttpStatus } from '../../shared/httpError'
import type { ScopeKey } from '../../features/spaces/epochs'

type Translate = (key: string, named?: Record<string, unknown>) => string

/** A name refused before anything was sent: a loaded sibling already has it (the same route group, the same slug). */
export class NodeNameTaken extends Error {
  constructor(readonly sibling: TreeNode) {
    super('name_taken')
    this.name = 'NodeNameTaken'
  }
}

/** Throw {@link NodeNameTaken} where `name` (trimmed) collides with a loaded sibling under `parentId` for a node of `kind`;
 *  `self` - the node being renamed - never collides with itself. */
export function refuseTakenName(nodes: readonly TreeNode[], parentId: string | null, kind: NodeKind, name: string, self: string | null = null): void {
  const sibling = collidingSibling(nodes, parentId, kind, name.trim(), self)
  if (sibling) throw new NodeNameTaken(sibling)
}

/** What a refused name or a failed name write says, inline under the field: the colliding sibling by its kind's noun, the
 *  server's `409` (a sibling this member cannot see, or one created since the read), its `422`, anything else. */
export function nodeNameErrorText(t: Translate, e: unknown): string {
  if (e instanceof NodeNameTaken) return nameTakenMessage(t, e.sibling)
  const status = httpStatus(e)
  return status === HttpStatus.conflict ? t('node.slugTaken') : status === HttpStatus.unprocessable ? t('node.illegalStructure') : t('node.saveFailed')
}

/** How much of `name` a rename pre-selects: what the node's kind says a rename usually changes (`KindDescriptor
 *  .renameSelectionEnd`, a file's name without its extension), else nothing said (undefined). */
export function renameSelectionEnd(node: TreeNode, name: string): number | undefined {
  const info = registry.kind(node.kind)
  return info.supported ? info.renameSelectionEnd?.(name) : undefined
}

/**
 * Rename `node` to `name` (trimmed) in the member's scope: refused where a loaded sibling has the name ({@link
 * NodeNameTaken}), else the placement write, the shares' names re-sealed and the tree re-read. Rejects with the write's
 * error ({@link nodeNameErrorText} words it). Resolves with whether every share's name was re-sealed: one it could not
 * re-seal keeps showing the old name to its guests - the caller says so in a toast, never as a failed rename.
 */
export async function renameMemberNode(scopeId: string, epochs: ScopeKey, node: TreeNode, name: string): Promise<{ resealed: boolean }> {
  const trimmed = name.trim()
  refuseTakenName(treeStore.nodes, node.parentId, node.kind, trimmed, node.id)
  await renameNode(scopeId, epochs, treeStore.nodes, node.id, trimmed)
  let resealed = true
  try {
    const report = await resealShareNames(node.id, trimmed, epochs)
    resealed = report.failed.length + report.skipped.length === 0
  } catch {
    resealed = false
  }
  // `loadTree` never throws.
  await loadTree()
  return { resealed }
}
