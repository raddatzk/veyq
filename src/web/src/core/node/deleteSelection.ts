// Deleting a level's selection (inbox #421, owner decisions 2026-10-14; uc.memory.delete-node step 1): each node is its own
// ordinary delete into the space's trash, sent through the door its single delete uses (core's `deleteNode`, or the kind's
// own `LevelRowSelection.remove`, none today), one after the other, and the tree is re-read ONCE after the
// last. No batch request: a node delete is already one soft-delete of a whole subtree (ADR 0061). A failure does not stop
// the rest; the undo restores exactly the nodes that went, each through core's `restoreNode` as the trash's own undo does
// (`trashUndo.ts`), and re-reads once more.
//
// Operator-blind, unchanged: the same requests as N single deletes and N single restores.
import { deleteNode, restoreNode, type TreeNode } from './tree'
import type { SelectedForDelete } from './selection'
import type { EpochKeys } from '../../features/spaces/epochs'
import { decodeHttpError } from '../../shared/httpError'

/** How one delete (or restore) of several ended: `error` null where it went through. */
export interface SelectionOutcome {
  node: TreeNode
  error: unknown
}

/** Delete each of `items` in order through its door, then `reload` once; a failure does not stop the rest. */
export async function deleteSelection(scopeId: string, items: readonly SelectedForDelete[], reload: () => Promise<void>): Promise<SelectionOutcome[]> {
  const outcomes: SelectionOutcome[] = []
  try {
    for (const { node, remove } of items) {
      try {
        await (remove ?? ((id: string) => deleteNode(scopeId, id)))(node.id)
        outcomes.push({ node, error: null })
      } catch (error) {
        outcomes.push({ node, error })
      }
    }
  } finally {
    await reload()
  }
  return outcomes
}

/** Bring back each of `nodes` (as they were before the delete) through core's trash restore, against the tree `tree()`
 *  reads at that moment, then `reload` once; a failure does not stop the rest. */
export async function restoreSelection(
  scopeId: string, epochs: EpochKeys, nodes: readonly TreeNode[], tree: () => TreeNode[], reload: () => Promise<void>,
): Promise<SelectionOutcome[]> {
  const outcomes: SelectionOutcome[] = []
  try {
    for (const node of nodes) {
      try {
        await restoreNode(scopeId, epochs, tree(), node)
        outcomes.push({ node, error: null })
      } catch (error) {
        outcomes.push({ node, error })
      }
    }
  } finally {
    await reload()
  }
  return outcomes
}

/** Why a node of a selection was not deleted, as the summary explains it (`node.deleteMany.refused.<code>`). */
export type DeleteRefusalCode = 'gone' | 'scope_deleting' | 'failed'

export async function deleteRefusalOf(e: unknown): Promise<DeleteRefusalCode> {
  const { code } = await decodeHttpError(e)
  if (code === 'node_not_found') return 'gone'
  if (code === 'scope_deleting') return 'scope_deleting'
  return 'failed'
}
