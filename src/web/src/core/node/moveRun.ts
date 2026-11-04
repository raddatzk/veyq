// What a move does once its place is chosen (inbox #358, #421): the check before sending, the drop-box guest's question, the
// write through the session's door, the refusal's words and the toast with its Undo. ONE path for every way a move is
// started: core's move dialogs run it on "Move here" (`MoveNodeDialog` for one node, `MoveNodesDialog` for a level's
// selection), and a drop on the level or on a crumb of its breadcrumb (drag and drop, `nodeDrag.ts`) runs it through the
// host's `moveTo` door (`nodeDialogs.ts`). So a drop checks, asks, writes and says exactly what the dialog would have for
// the same nodes and place - there is no second move.
//
// It only runs and reports: where the outcome is shown is the caller's. A dialog puts a refusal and a summary into itself; a
// drop has no dialog open, so the door says a refusal in an error toast and opens the selection's dialog on its summary.
// A composable (it reads the app's i18n, confirm and toast), so it is called in a setup.
import { useI18n } from 'vue-i18n'
import type { TreeSource } from '../access'
import type { TreeNode } from './tree'
import { registry } from '../applications/registry'
import { collidingSibling } from './siblingCollision'
import { endsIdentity, moveRefusalOf, MoveDeclined, planMove, type NodeMover } from './move'
import { joinList } from './selection'
import { TRASH_UNDO_MS } from './trashUndo'
import { useConfirm } from '../../shared/state/useConfirm'
import { useToast } from '../../shared/state/useToast'

/** Where a move runs: the tree as the session reads it, its door, and the member's scope root as a message names it. */
export interface MoveRunTarget {
  tree: TreeSource<TreeNode>
  mover: NodeMover
  rootLabel?: string
}

/** How one node's move ended: moved (from its old parent), refused before or by the write (the words to say), or
 *  cancelled - the drop-box question declined, or a guest declined to name itself (nothing sent, nothing to say). */
export type MoveOneResult = { status: 'moved'; from: string | null } | { status: 'refused'; text: string } | { status: 'cancelled' }

/** How a selection's move ended: refused as a whole (nothing would go, or the door failed before any write), cancelled,
 *  or done - what moved, the toast's sentence, and one line per node left out or refused (none: everything moved). */
export type MoveManyResult =
  | { status: 'refused'; text: string }
  | { status: 'cancelled' }
  | { status: 'done'; moved: TreeNode[]; movedText: string; lines: string[] }

export interface MoveRunOptions {
  /** The write is running (true) or over (false): a dialog's busy state. Not called for what ends before the write. */
  sending?: (on: boolean) => void
  /** Called after the toast's Undo moved the nodes back. */
  afterUndo?: () => void
}

export function useMoveRun() {
  const { t, locale } = useI18n()
  const { ask } = useConfirm()
  const toast = useToast()

  function destinationName(target: MoveRunTarget, to: string | null): string {
    return to === null ? (target.rootLabel ?? '') : target.tree.nameOf(to)
  }
  function nameTakenText(sibling: TreeNode): string {
    const info = registry.kind(sibling.kind)
    const noun = (info.supported && info.nounWithArticle) || 'node.anItem'
    return t('node.move.nameTakenThere', { kind: t(noun), name: sibling.name })
  }

  /** Move `node` (named `name` as its siblings tell it apart) under `to`: `MoveNodeDialog`'s "Move here". */
  async function moveOne(target: MoveRunTarget, node: TreeNode, name: string, to: string | null, opts: MoveRunOptions = {}): Promise<MoveOneResult> {
    // A sibling there whose name folds to the same slug (core's `collidingSibling`, inbox #389): named before anything is sent.
    const sibling = collidingSibling(target.tree.children(to), to, node.kind, node.name, node.id)
    if (sibling) return { status: 'refused', text: nameTakenText(sibling) }
    const identity = endsIdentity(node)
    if (identity) {
      const accepted = await ask({
        header: t('node.move.identityTitle'),
        message: t('node.move.identityConfirm', { name }),
        acceptLabel: t('node.move.identityAccept'),
        acceptIcon: 'file-export',
      })
      if (!accepted) return { status: 'cancelled' }
    }
    const from = node.parentId
    const mover = target.mover
    opts.sending?.(true)
    try {
      await mover.move(node.id, to)
    } catch (e) {
      opts.sending?.(false)
      if (e instanceof MoveDeclined) return { status: 'cancelled' }
      const code = await moveRefusalOf(e)
      // A lost race: re-read, so the next attempt builds on the node as it now is.
      if (code === 'stale_placement') await mover.refresh().catch(() => {})
      return { status: 'refused', text: t(`node.move.refused.${code}`) }
    }
    opts.sending?.(false)
    const summary = t('node.move.moved', { name, to: destinationName(target, to) })
    if (identity) {
      toast.success(summary) // nothing moves back into a drop box: no undo
    } else {
      const afterUndo = opts.afterUndo
      toast.showUndo({
        summary,
        life: TRASH_UNDO_MS,
        onUndo: () => {
          mover.move(node.id, from).then(
            () => afterUndo?.(),
            () => toast.error(t('node.move.undoFailed', { name })),
          )
        },
      })
    }
    return { status: 'moved', from }
  }

  /** Move `nodes` (each named by `nameOf`, read before the write) under `to`: `MoveNodesDialog`'s "Move here". */
  async function moveMany(
    target: MoveRunTarget, nodes: readonly TreeNode[], nameOf: (node: TreeNode) => string, to: string | null, opts: MoveRunOptions = {},
  ): Promise<MoveManyResult> {
    const quoted = (ns: readonly TreeNode[]) => joinList(locale.value, ns.map((n) => t('node.move.many.quoted', { name: nameOf(n) })))
    const { send, leftOut } = planMove(target.tree, nodes, to)
    const leftOutLines = leftOut.map((x) =>
      t('node.move.many.reason', { name: nameOf(x.node), reason: x.reason === 'current' ? t('node.move.here.current') : nameTakenText(x.sibling) }),
    )
    // Nothing would go: said as a whole.
    if (send.length === 0) return { status: 'refused', text: leftOutLines.join(' ') }
    const identities = send.filter(endsIdentity)
    if (identities.length > 0) {
      const accepted = await ask({
        header: t('node.move.identityTitle'),
        message: t('node.move.many.identityConfirm', { names: quoted(identities) }),
        acceptLabel: t('node.move.identityAccept'),
        acceptIcon: 'file-export',
      })
      if (!accepted) return { status: 'cancelled' }
    }
    const mover = target.mover
    const from = new Map(send.map((n) => [n.id, n.parentId]))
    opts.sending?.(true)
    let outcomes
    try {
      outcomes = await mover.moveMany(send.map((n) => ({ nodeId: n.id, to })))
    } catch (e) {
      opts.sending?.(false)
      if (e instanceof MoveDeclined) return { status: 'cancelled' } // a guest declined to name itself: nothing sent, nothing said
      return { status: 'refused', text: t(`node.move.refused.${await moveRefusalOf(e)}`) }
    }
    opts.sending?.(false)
    const byId = new Map(send.map((n) => [n.id, n]))
    const moved = outcomes.filter((o) => o.error === null).map((o) => byId.get(o.nodeId)!)
    const refusedLines: string[] = []
    for (const o of outcomes) {
      if (o.error !== null) refusedLines.push(t('node.move.many.reason', { name: nameOf(byId.get(o.nodeId)!), reason: t(`node.move.refused.${await moveRefusalOf(o.error)}`) }))
    }
    const movedText = moved.length > 0 ? t('node.move.many.moved', { count: moved.length, to: destinationName(target, to) }) : t('node.move.many.noneMoved')
    if (moved.length > 0) {
      // Nothing moves back into a drop box: a guest's folder that left one has no undo, the rest move back where they were.
      const undoable = moved.filter((n) => !endsIdentity(n))
      if (undoable.length === 0) {
        toast.success(movedText)
      } else {
        const afterUndo = opts.afterUndo
        toast.showUndo({
          summary: movedText,
          life: TRASH_UNDO_MS,
          onUndo: () => {
            mover.moveMany(undoable.map((n) => ({ nodeId: n.id, to: from.get(n.id) ?? null }))).then(
              (back) => {
                const failed = back.filter((o) => o.error !== null).map((o) => byId.get(o.nodeId)!)
                if (failed.length > 0) toast.error(t('node.move.many.undoFailed', { names: quoted(failed) }))
                afterUndo?.()
              },
              () => toast.error(t('node.move.many.undoFailed', { names: quoted(undoable) })),
            )
          },
        })
      }
    }
    return { status: 'done', moved, movedText, lines: [...leftOutLines, ...refusedLines] }
  }

  return { moveOne, moveMany }
}
