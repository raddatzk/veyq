// Core's DEFAULT row actions rename, move and delete (owner 2026-11-01): the level row's mirror of the header's
// `standardNodeActions` (`nodeHeader.ts`), whose builder they share. Every node of every kind gets them - a folder, a Memory,
// a Pot, a file - and no application builds them: the row's application answers its fail-closed rights for THAT node in
// `LevelRow.standard` (`levelRow.ts`; a folder's answer is core's `folderRights`), core adds the granted ones, and each runs
// through the level's own door (`LevelView`'s host: core's name dialog, move dialog, and the delete question with its Undo).
//
// **Fail closed twice.** An action is offered only where the application said yes AND the level has a door for it: a share
// guest's host (`ShareTreePage`) mounts the move dialog alone - no share role renames or deletes a node core's way, so a
// guest gets no default rename or delete whatever an answer says (an application whose guest renames or deletes
// something, as Depot's own file delete, implements that action itself).
//
// **An application's own action replaces the default** ({@link withStandardRowActions}): an action of id `rename`, `move` or
// `delete` in the row's `actions` stands in for core's, whatever `standard` says - its own `shown` decides. Depot's file
// does so for all three (its delete asks, writes through its source and offers its own Undo, inbox #385 (c)).
import type { Access } from '../access'
import type { TreeNode } from './tree'
import type { LevelRow, LevelRowAction } from './levelRow'
import type { SelectedForDelete } from './selection'
import { standardNodeActions } from './nodeHeader'

type Translate = (key: string) => string

/** The ids of core's default row actions. */
export const STANDARD_ROW_ACTION_IDS = ['rename', 'move', 'delete'] as const

/** The level's doors for its default actions: a missing one offers nothing. */
export interface StandardRowDoors {
  rename?: (node: TreeNode) => void
  move?: (node: TreeNode) => void
  /** Delete the node, with the kind's own delete write where its selection names one (`LevelRowSelection.remove`). */
  remove?: (item: SelectedForDelete) => void
}

/** The doors `access` reaches: a member's all three; a guest's the move alone (see the header). */
export function standardRowDoors(access: Access, doors: Required<StandardRowDoors>): StandardRowDoors {
  return access.mode === 'member' ? doors : { move: doors.move }
}

/** Core's default actions for `node`'s row: the granted ones that have a door, in the order rename, move, delete. */
export function standardRowActions(t: Translate, node: TreeNode, row: Pick<LevelRow, 'standard' | 'selection'>, doors: StandardRowDoors): LevelRowAction[] {
  const granted = row.standard ?? {}
  const remove = row.selection?.remove
  return standardNodeActions(
    t,
    { rename: !!granted.rename && !!doors.rename, move: !!granted.move && !!doors.move, delete: !!granted.delete && !!doors.remove },
    {
      rename: () => doors.rename?.(node),
      move: () => doors.move?.(node),
      remove: () => doors.remove?.({ node, ...(remove ? { remove } : {}) }),
    },
  )
}

/** The application's actions with core's defaults added where it has none of that id: rename and move before its own
 *  (so core's "Copy to…" finds "Move to…" to sit beside), delete after them. The row's menu orders them anyway
 *  (`splitRowActions`). */
export function withStandardRowActions(app: readonly LevelRowAction[], standard: readonly LevelRowAction[]): LevelRowAction[] {
  const own = new Set(app.map((a) => a.id))
  const kept = standard.filter((a) => !own.has(a.id))
  return [...kept.filter((a) => a.id !== 'delete'), ...app, ...kept.filter((a) => a.id === 'delete')]
}
