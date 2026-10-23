// What core's `NodeHeader` (`core/node/components/NodeHeader.vue`) is fed: the data of ONE header row. Every node page and
// level header — a folder's, a file's, a drop box's, a Memory's — is that one component; an application only says WHAT
// is in it (a leading icon or image, a title, a subtitle, a meta row, its actions) and core decides the look, the
// spacing, the wrap, the keyboard and the dark mode (owner design 2026-10-12, inbox #406).
//
// **An action is already a rights answer.** The caller builds the list from its fail-closed rights function
// (`core/access.ts`, `../rights.ts`): an action it was not granted is `shown: false` or simply left out, and the
// header decides nothing of its own. A share guest gets the same header as a member with fewer actions (inbox #401) —
// there is no guest-only header.

import type { VeyqIconName } from '../../shared/ui/icons'

/** One button of the header's action row. */
export interface NodeHeaderAction {
  /** Stable key within the header (`share`, `rename`, `upload`…); also the button's `data-action`. */
  id: string
  /** The button's accessible name and tooltip (already translated; an icon-only button must be labelled). */
  label: string
  /** The icon. */
  icon: VeyqIconName
  onClick: () => void
  /** `primary` is the one filled call to action, `danger` a destructive one (delete). Default `secondary`. */
  severity?: 'primary' | 'secondary' | 'danger'
  /** Rendered but not clickable (e.g. keys still loading). */
  disabled?: boolean
  /** Not rendered at all (the rights answer was no). Default: shown. */
  shown?: boolean
  /** A toggle's state (a heart): sets `aria-pressed` and `is-pressed`. Omitted: not a toggle. */
  pressed?: boolean
  /** An extra class for the application's own styling hook. */
  class?: string
}

/** A core-owned action (e.g. a future favorite heart that every node gets, whatever its application): contributed by
 *  core next to the application's own, gated by a core rights function, never supplied by the application. */
/** Where a core action goes among the application's: before them, after them, or right after the application's action of
 *  that id (core's "Copy to…" beside "Move to…") - after them all where the application offers no such action. */
export type CoreActionPlacement = 'before' | 'after' | { after: string }

export interface CoreHeaderAction extends NodeHeaderAction {
  /** Before or after the application's actions, or beside one of them. Default `after`. */
  placement?: CoreActionPlacement
}

/** Core's contributions placed around (and beside) the application's list, stable order within each group: the one rule
 *  of {@link mergeHeaderActions} and the level row's `mergeRowActions`. */
export function placeCoreActions<A extends { id: string }, C extends A & { placement?: CoreActionPlacement }>(core: readonly C[], app: readonly A[]): A[] {
  const before = core.filter((a) => a.placement === 'before')
  const beside = (id: string) => core.filter((a) => typeof a.placement === 'object' && a.placement.after === id)
  const ids = new Set(app.map((a) => a.id))
  const after = core.filter((a) => a.placement === undefined || a.placement === 'after' || (typeof a.placement === 'object' && !ids.has(a.placement.after)))
  return [...before, ...app.flatMap((a) => [a, ...beside(a.id)]), ...after]
}

/** The header's action row: core's contributions around the application's list (stable order within each group). This
 *  is where a core action plugs in — `NodeHeader` takes them as `coreActions`; no application change is needed. */
export function mergeHeaderActions(core: readonly CoreHeaderAction[], app: readonly NodeHeaderAction[]): NodeHeaderAction[] {
  return placeCoreActions(core, app)
}

/** The four actions every container-like node shares, in their fixed order: share, rename, move, delete. */
export interface StandardActionFlags {
  share?: boolean
  rename?: boolean
  move?: boolean
  remove?: boolean
  /** Where the kind says more than core's neutral words ("Delete drop box"). */
  shareLabel?: string
  deleteLabel?: string
}
export interface StandardActionHandlers {
  share?: () => void
  rename?: () => void
  move?: () => void
  remove?: () => void
}

/** The share / rename / move / delete actions of a node header from the caller's rights answers: only the granted
 *  ones, in that order. `t` is the caller's translate function (core's neutral words for the labels). */
export function standardNodeActions(
  t: (key: string) => string,
  flags: StandardActionFlags,
  on: StandardActionHandlers,
): NodeHeaderAction[] {
  const noop = () => {}
  const all: NodeHeaderAction[] = [
    { id: 'share', label: flags.shareLabel ?? t('share.share'), icon: 'share-alt', onClick: on.share ?? noop, shown: !!flags.share },
    { id: 'rename', label: t('node.rename'), icon: 'pencil', onClick: on.rename ?? noop, shown: !!flags.rename },
    { id: 'move', label: t('node.move.action'), icon: 'file-export', onClick: on.move ?? noop, shown: !!flags.move },
    { id: 'delete', label: flags.deleteLabel ?? t('node.delete'), icon: 'trash', onClick: on.remove ?? noop, severity: 'danger', shown: !!flags.remove },
  ]
  return all.filter((a) => a.shown)
}
