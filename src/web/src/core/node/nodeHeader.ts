// What core's `NodeHeader` (`core/node/components/NodeHeader.vue`) is fed: the data of ONE header row. Every node page and
// level header — a folder's, a file's, a drop box's, a Memory's — is that one component; an application only says WHAT
// is in it (a leading icon or image, a title, a subtitle, a meta row, its actions and their order) and core decides the
// look, the spacing, which actions are buttons, the keyboard and the dark mode (owner design 2026-10-12, inbox #406).
//
// **As many actions as fit are buttons, the rest one menu** (ADR 0110): the page that draws the header names its kind's
// priority as an id list (`order`), core measures the row and {@link fitHeaderActions} says how many of the actions that
// may be buttons are; the rest, and every {@link isMenuOnly} action (delete, report) after a separator, are entries of
// one ⋯ menu. No action is lost at any width.
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
  /** Never a button, always an entry of the ⋯ menu's last group (report; a `danger` action is one anyway, ADR 0110). */
  menuOnly?: boolean
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

/** Delete and report are never buttons (ADR 0110 decision 6): a button that moves with the width is too easy to hit by
 *  mistake, and both open a consequential dialog. */
export function isMenuOnly(a: Pick<NodeHeaderAction, 'menuOnly' | 'severity'>): boolean {
  return a.menuOnly === true || a.severity === 'danger'
}

/** The header's actions in the kind's priority: the ones whose ids `order` lists first, in that order, then every other
 *  one in the given (merge) order — so a core action added later appears, it never vanishes for being unlisted. */
export function orderHeaderActions<A extends { id: string }>(actions: readonly A[], order: readonly string[] = []): A[] {
  const rank = new Map(order.map((id, i) => [id, i]))
  const listed = actions.filter((a) => rank.has(a.id)).sort((a, b) => rank.get(a.id)! - rank.get(b.id)!)
  return [...listed, ...actions.filter((a) => !rank.has(a.id))]
}

/** The shown actions split into those that may be buttons (in order) and the menu-only ones, a `danger` one last. */
export function partitionHeaderActions<A extends NodeHeaderAction>(actions: readonly A[]): { buttons: A[]; menuOnly: A[] } {
  const menuOnly = actions.filter(isMenuOnly)
  return {
    buttons: actions.filter((a) => !isMenuOnly(a)),
    menuOnly: [...menuOnly.filter((a) => a.severity !== 'danger'), ...menuOnly.filter((a) => a.severity === 'danger')],
  }
}

/** What {@link fitHeaderActions} weighs, in CSS pixels. Header buttons are icon-only and one size, so one measured button
 *  width stands for all of them. */
export interface HeaderFit {
  /** How many actions may be buttons. */
  count: number
  /** The width the buttons and the ⋯ may take; 0 (or less) is unmeasured. */
  rowWidth: number
  buttonWidth: number
  /** The gap between two buttons, and between the last button and the ⋯. */
  gap: number
  moreWidth: number
  /** A menu-only action exists, so the ⋯ is drawn whatever fits. */
  menuOnly: boolean
}

/** How many of the actions that may be buttons are buttons: all of them when they fit and no menu-only action needs the
 *  ⋯; otherwise the ⋯ is reserved first and as many as fit beside it are (none at all in a row too narrow for one).
 *  Unmeasured — no width yet, as in a unit test or a hidden tab — every one is a button (ADR 0110). */
export function fitHeaderActions(f: HeaderFit): number {
  if (!(f.rowWidth > 0) || !(f.buttonWidth > 0)) return f.count
  // A hundredth of a pixel of slack: a measured width is fractional, and a row exactly as wide as its buttons fits them.
  const room = f.rowWidth + 0.01
  if (!f.menuOnly && f.count * f.buttonWidth + Math.max(0, f.count - 1) * f.gap <= room) return f.count
  return Math.max(0, Math.min(f.count, Math.floor((room - f.moreWidth) / (f.buttonWidth + f.gap))))
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
