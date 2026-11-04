// Drag and drop on the level (inbox #421, owner 2026-11-04): a SECOND way to move nodes, beside core's move dialog, and a
// shortcut only - the keyboard and touch path stays the dialog (a row's "Move to…", the Select bar's), and there is no touch
// or long-press drag. A row or tile of the level is dragged (`LevelView`: only where the viewer may move that node, and only
// while core draws the rows - never on an application's own presentation such as Memories' map); in Select mode a selected
// node drags the selection's movable part, an unselected one itself alone. It drops on a SUB-FOLDER of the same level or on
// an ancestor in the page's breadcrumb (the space root included, where the session reads it). No reordering: the level is
// sorted by name.
//
// **The dialog's rules, nothing of its own.** A place takes the drag exactly where the move dialog's "Move here" would be
// enabled there ({@link dropTakes}: `move.ts` `moveHereBlockOf` - never into itself or below itself, not where they already
// are, no guest's identity folder, the kinds' placement, a guest only into a folder of its link); a place that would refuse
// is not lit and does not take the drop (`dropEffect` none). The drop hands the nodes to the host's `moveTo` door, which runs
// the dialogs' own run (`moveRun.ts`): the name check, the drop-box question, the writes, the refusal's words, the toast
// with its Undo and a selection's summary.
//
// **An internal drag is told apart by its own type** ({@link NODE_DRAG_TYPE}) in `dataTransfer.types` - the only part of a
// drag a page may read before the drop. A drag from the operating system (files, a directory, a veyq download) carries
// `Files` and none of it: it never lights a folder (a file dropped on a folder's row is no feature) and stays the level's
// upload and import. What is being dragged lives here, in the one drag a document has at a time, because the drag's data
// cannot be read while it is over a target.
import { shallowRef } from 'vue'
import type { Access, TreeSource } from '../access'
import type { TreeNode } from './tree'
import { moveHereBlockOf } from './move'
import type { VeyqDropTarget, VeyqMenuItem } from '../../shared/ui/menu'

/** The `dataTransfer` type of a drag of nodes from the level; its data is the dragged ids, as JSON. */
export const NODE_DRAG_TYPE = 'application/x-veyq-nodes'

/** The drag under way (see the header): its nodes, whether a place takes them, and what a drop on one does. */
export interface NodeDrag {
  /** The dragged nodes' ids: the items drawn dimmed. */
  ids: ReadonlySet<string>
  /** Whether the place `to` (a node id; null the scope root) takes them. */
  accepts(to: string | null): boolean
  /** Move them under `to`. */
  drop(to: string | null): void
}

const current = shallowRef<NodeDrag | null>(null)

/** The drag under way in this document, or null. Reactive. */
export function activeNodeDrag(): NodeDrag | null {
  return current.value
}

/** Whether `e` is a drag of nodes from the level - never one from the operating system. */
export function isNodeDrag(e: DragEvent): boolean {
  return Array.from(e.dataTransfer?.types ?? []).includes(NODE_DRAG_TYPE)
}

/** Whether `nodes` may be moved to `to` by a drop: exactly where the move dialog would enable "Move here" there. */
export function dropTakes(tree: TreeSource, nodes: readonly TreeNode[], mode: Access['mode'], to: string | null): boolean {
  return nodes.length > 0 && moveHereBlockOf(tree, nodes, mode, to) === null
}

/** Start `drag` from `e` (a `dragstart`): the type that marks it, a move only, and - where several go - an image that
 *  says how many (`countLabel`). */
export function startNodeDrag(e: DragEvent, drag: NodeDrag, countLabel?: string): void {
  const data = e.dataTransfer
  if (!data) return
  data.setData(NODE_DRAG_TYPE, JSON.stringify([...drag.ids]))
  data.effectAllowed = 'move'
  if (countLabel) {
    // Drawn off screen for the browser to take its picture, gone right after (the browser keeps the picture).
    const image = document.createElement('div')
    image.className = 'veyq-node-drag-image'
    image.textContent = countLabel
    document.body.appendChild(image)
    data.setDragImage(image, 16, 16)
    setTimeout(() => image.remove(), 0)
  }
  current.value = drag
}

/** The drag ended (dropped, or given up): nothing is dragged any more. */
export function endNodeDrag(): void {
  current.value = null
}

/** A place's `dragenter`/`dragover`: true - and the event taken as a move - where the drag under way may go to `to`. A drag
 *  of nodes it does not take is told so (`dropEffect` none); any other drag (files) is left alone. */
export function overNodeTarget(e: DragEvent, to: string | null): boolean {
  if (!isNodeDrag(e)) return false
  const drag = current.value
  if (!drag || !drag.accepts(to)) {
    if (e.dataTransfer) e.dataTransfer.dropEffect = 'none'
    return false
  }
  e.preventDefault()
  if (e.dataTransfer) e.dataTransfer.dropEffect = 'move'
  return true
}

/** A place's `drop`: the drag under way moves to `to` where it may go there (true). A drop of nodes is consumed here either
 *  way, so it never reaches the level's upload. */
export function dropOnNodeTarget(e: DragEvent, to: string | null): boolean {
  if (!isNodeDrag(e)) return false
  e.preventDefault()
  e.stopPropagation()
  const drag = current.value
  // Ended here: the move may take the dragged item away before its `dragend` could say so.
  current.value = null
  if (!drag || !drag.accepts(to)) return false
  drag.drop(to)
  return true
}

/** Whether `e`, a `dragleave` of `target`, left it - not just moved onto one of its children. */
export function leftTarget(e: DragEvent): boolean {
  const target = e.currentTarget as Node | null
  const into = e.relatedTarget as Node | null
  return !(target && into && target.contains(into))
}

/** A page's breadcrumb as drop targets (`VeyqBreadcrumb`'s `drop-target`): `placeOf` names the place an entry stands for (a
 *  node id; null the scope root), undefined for an entry that is no place. Which of them take the drag is the drag's own
 *  answer - the place on screen never (its nodes are already there), an ancestor where the dialog would move them. */
export function crumbDropTarget(placeOf: (item: VeyqMenuItem) => string | null | undefined): VeyqDropTarget {
  return {
    over(item, e) {
      const to = placeOf(item)
      return to !== undefined && overNodeTarget(e, to)
    },
    drop(item, e) {
      const to = placeOf(item)
      if (to !== undefined) dropOnNodeTarget(e, to)
    },
  }
}
