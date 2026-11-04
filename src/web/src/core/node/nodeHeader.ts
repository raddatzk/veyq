// What core's `NodeHeader` (`core/node/components/NodeHeader.vue`) is fed: the data of ONE header row. Every node page and
// level header — a folder's, a file's, a drop box's, a Memory's, a Pot's — is that one component, and core draws it: the
// folder level (`LevelView`) and core's node page (`NodePage`) mount it, never an application (owner 2026-11-03, ADR 0110
// decision 10). The kind's icon, whether it takes a cover and its priority (`order`) are its descriptor's
// (`KindDescriptor.icon`, `.cover`, `.header`); what depends on the page - a subtitle, the application's actions, its
// FACTS (a date, a place, people, a line of text) and a banner - the page's detail contributes as data
// (`nodeHeaderContribution.ts`). Core decides the look, the spacing, which actions are buttons, the keyboard and the dark
// mode (owner design 2026-10-12, inbox #406).
//
// **As many actions as fit are buttons, the rest one menu** (ADR 0110): the page that draws the header names its kind's
// priority as an id list (`order`), core measures the row and {@link fitHeaderActions} says how many of the actions that
// may be buttons are; the rest, and every {@link isMenuOnly} action (delete, report) after a separator, are entries of
// one ⋯ menu. No action is lost at any width. Which of its two layouts the header takes is {@link headerLayout}'s answer,
// from the header's own width and fixed sizes; on a wide header the name comes first, down to {@link headerFloor}.
//
// **An action is already a rights answer.** The caller builds the list from its fail-closed rights function
// (`core/access.ts`, `../rights.ts`): an action it was not granted is `shown: false` or simply left out, and the
// header decides nothing of its own. Core's own actions (heart, cover, copy, download, report) are not the page's to
// build: the header takes them for its `node` from the view around it (`coreNodeActions.ts`). A share guest gets the same header as a member with fewer actions (inbox #401) —
// there is no guest-only header.

import type { Component } from 'vue'
import type { VeyqIconName } from '../../shared/ui/icons'
import type { NodeRights } from '../access'

/** A fact under the name in core's chip look: a date or a place (the kind picks the icon), a button where `onClick` is given
 *  (the application's own editor), else plain text. Consecutive chips share one row. */
export interface NodeHeaderChipFact {
  kind: 'date' | 'place'
  /** What the chip reads (already translated): the value, or the application's words for its absence. */
  label: string
  /** No value yet: drawn muted. */
  empty?: boolean
  /** What a click opens (the application's editor). Omitted: plain text. */
  onClick?: () => void
  /** The button's accessible name and tooltip ("Edit date"); default `label`. */
  actionLabel?: string
  /** Rendered but not clickable (keys still loading, no right to edit). */
  disabled?: boolean
}

/** One person of a {@link NodeHeaderPeopleFact}. */
export interface NodeHeaderPerson {
  /** A member's id: their picture and their profile link (`#people/<id>`), on a member's page only. Null: a person with no
   *  member identity (a guest), drawn by name alone. */
  userId: string | null
  /** How the interface names them (already translated where it is a placeholder, "Former member"). */
  name: string
  /** No longer a member of the space: greyed, no link, no picture. */
  former?: boolean
}

/** People under the name (who was there, who uploaded the file): one person by avatar and name, several as overlapping
 *  avatars (three, or two and "+N") whose hover card lists everyone. Core draws them and decides the links: a profile link and a
 *  member's picture only where the header's `linkPeople` says so (a member's page), never on a share page. */
export interface NodeHeaderPeopleFact {
  kind: 'people'
  /** The group's accessible name ("Participants", "Uploaded by"). */
  label: string
  people: readonly NodeHeaderPerson[]
  /** Visible words before the people ("Uploaded by"). Omitted: none. */
  caption?: string
  /** A muted line beside them (loading the names, a read that failed). */
  status?: string
  /** What edits the people (the application's picker): a button beside them, or - with nobody yet - the empty chip that
   *  is the way in (`emptyLabel`). Omitted: the people are only shown, and with nobody there is no fact. This is the one way
   *  to the editor: a header offers no action of its own for what it shows editable in place (ADR 0110 decision 11). */
  onEdit?: () => void
  /** The edit button's accessible name and tooltip ("Edit participants"); default `label`. */
  editLabel?: string
  /** The chip's words while nobody is listed ("Add participants"); default `editLabel`. */
  emptyLabel?: string
}

/** A plain line of text under the name. */
export interface NodeHeaderTextFact {
  kind: 'text'
  text: string
}

/** What a page's detail may show under the header's name, in its order: core draws each in one look. A fact the node does not
 *  have is left out (never a placeholder), unless the application's placeholder is itself the way in (Memories' "Add date"). */
export type NodeHeaderFact = NodeHeaderChipFact | NodeHeaderPeopleFact | NodeHeaderTextFact

/** A component core draws above the header row of a node's page (Memories' banner): the application's own presentation, with
 *  its props. Core gives it the page's width; it brings its own frame. */
export interface NodeHeaderBanner {
  component: Component
  props?: Record<string, unknown>
}

/** The node's name as the header's title edits it in place (ADR 0110 decision 11): a click on the name makes it a field,
 *  Enter writes, Esc cancels. Given only where whoever looks may rename the node - the rights answer AND the page's door,
 *  as {@link standardNodeRename} builds it; absent, the name is plain text (a guest, a reader). */
export interface NodeHeaderRename {
  /** Write `name` (trimmed, changed, not empty) as the node's new name: the kind's own rename write through the page's door
   *  (`NodeHost.renameTo`). Rejects with the write's error, which core words under the field (`nodeNameErrorText`). */
  save: (name: string) => Promise<void>
}

/** The facts as the header lays them out: consecutive chips share one row, every other fact has a row of its own. */
export function factRows(facts: readonly NodeHeaderFact[]): NodeHeaderFact[][] {
  const rows: NodeHeaderFact[][] = []
  for (const f of facts) {
    const last = rows.at(-1)
    if (f.kind !== 'people' && f.kind !== 'text' && last && last[0]!.kind !== 'people' && last[0]!.kind !== 'text') last.push(f)
    else rows.push([f])
  }
  return rows
}

/** How many rows of each kind the header's body draws beside the leading visual, the title row aside (always one): the
 *  subtitle, the rows of chips, of people and of text ({@link factRows}). The leading visual is exactly as tall as these
 *  rows and the gaps between them - each kind of row has a fixed block size in `styles/ui.css` (`--veyq-node-header-row-*`),
 *  and the header hands these counts to that sum as custom properties - so its size is computed from the data, never
 *  measured, and the layout answer that reads its wide width cannot flip (ADR 0110 decisions 1 and 8). */
export interface HeaderRowCounts {
  subtitle: number
  chips: number
  people: number
  text: number
}

export function headerRowCounts(subtitle: boolean, facts: readonly NodeHeaderFact[]): HeaderRowCounts {
  const counts: HeaderRowCounts = { subtitle: subtitle ? 1 : 0, chips: 0, people: 0, text: 0 }
  for (const row of factRows(facts)) {
    const kind = row[0]!.kind
    if (kind === 'people') counts.people++
    else if (kind === 'text') counts.text++
    else counts.chips++
  }
  return counts
}

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
  /** Whether it is rendered at all: the caller's rights answer for it, REQUIRED so an action nobody gated does not
   *  compile (a page passes what its fail-closed rights function said, never a constant where one exists). */
  shown: boolean
  /** A toggle's state (a heart): sets `aria-pressed` and `is-pressed`. Omitted: not a toggle. */
  pressed?: boolean
  /** An extra class for the application's own styling hook. */
  class?: string
  /** Never a button, always an entry of the ⋯ menu's last group (report; a `danger` action is one anyway, ADR 0110). */
  menuOnly?: boolean
}

/** Where a core action goes among the application's: before them, after them, or right after the application's action of
 *  that id (core's "Copy to…" beside "Move to…") - after them all where the application offers no such action. */
export type CoreActionPlacement = 'before' | 'after' | { after: string }

/** A core-owned action (the favorite heart, the cover, copy, download, report - `coreNodeActions.ts`): contributed by core
 *  next to the application's own, gated by a core rights function, never supplied by the application. */
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
 *  is where a core action plugs in — `NodeHeader` merges what `coreNodeActions.ts` supplies for its node; no application
 *  change is needed. */
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
  /** The buttons kept whatever the room (a wide header's floor, {@link headerFloor}); default none. */
  floor?: number
}

/** How many of the actions that may be buttons are buttons: all of them when they fit and no menu-only action needs the
 *  ⋯; otherwise the ⋯ is reserved first and as many as fit beside it are (none at all in a row too narrow for one), but
 *  never fewer than the `floor`. Unmeasured — no width yet, as in a unit test or a hidden tab — every one is a button
 *  (ADR 0110). */
export function fitHeaderActions(f: HeaderFit): number {
  if (!(f.rowWidth > 0) || !(f.buttonWidth > 0)) return f.count
  const floor = Math.max(0, Math.min(f.floor ?? 0, f.count))
  // A hundredth of a pixel of slack: a measured width is fractional, and a row exactly as wide as its buttons fits them.
  const room = f.rowWidth + 0.01
  if (!f.menuOnly && f.count * f.buttonWidth + Math.max(0, f.count - 1) * f.gap <= room) return f.count
  return Math.max(floor, Math.min(f.count, Math.floor((room - f.moreWidth) / (f.buttonWidth + f.gap))))
}

/** A wide header's floor (ADR 0110 decision 3): the kind's first two shown actions that may be buttons stay buttons
 *  however long the name; a guest with fewer has that many. */
export function headerFloor(count: number): number {
  return Math.max(0, Math.min(2, count))
}

/** The node header's two layouts (ADR 0110 decision 1): `compact` — a small leading visual, the name in two lines, the
 *  actions in a row of their own — or `wide` — the full leading visual, the name in one line and the actions in its row. */
export type HeaderLayout = 'wide' | 'compact'

/** What {@link headerLayout} weighs, in CSS pixels: the header's width and sizes that no layout changes. It takes no name
 *  width and no current layout, so the answer cannot depend on either. */
export interface HeaderLayoutInput {
  /** The header's own width; 0 (or less) is unmeasured. */
  headerWidth: number
  /** The leading visual's WIDE width (0 without one) and the gap between it and the name. */
  leadingWide: number
  leadingGap: number
  /** The name's minimum on a wide header, and the gap between the name and the actions. */
  nameMin: number
  rowGap: number
  /** The floor's buttons ({@link headerFloor}), and whether the ⋯ is drawn beside them (more actions, or a menu-only one). */
  floor: number
  more: boolean
  buttonWidth: number
  /** The gap between two buttons, and between the last button and the ⋯. */
  gap: number
  moreWidth: number
}

/** Wide while the wide leading visual, the name's minimum, the floor's buttons and the ⋯, with their gaps, fit in one row
 *  of the header's width; otherwise compact (ADR 0110 decision 8). Unmeasured it is wide, the markup every unit test has. */
export function headerLayout(l: HeaderLayoutInput): HeaderLayout {
  if (!(l.headerWidth > 0)) return 'wide'
  const items = l.floor + (l.more ? 1 : 0)
  const actions = items > 0 ? l.rowGap + l.floor * l.buttonWidth + (l.more ? l.moreWidth : 0) + (items - 1) * l.gap : 0
  const need = (l.leadingWide > 0 ? l.leadingWide + l.leadingGap : 0) + l.nameMin + actions
  return need <= l.headerWidth + 0.01 ? 'wide' : 'compact'
}

/** The four actions every container-like node shares, in their fixed order: share, rename, move, delete - granted by
 *  the node's rights answer under core's names (`NodeRights`, core/access.ts), so a page passes its rights object as it
 *  is and its header, its rows and its selection read one answer. */
export interface StandardActionFlags extends Partial<Pick<NodeRights, 'share' | 'rename' | 'move' | 'delete'>> {
  /** Where the kind says more than core's neutral words ("Delete drop box"). */
  shareLabel?: string
  deleteLabel?: string
}
export interface StandardActionHandlers {
  share?: () => void
  /** Open core's name dialog (a level row's rename). */
  rename?: () => void
  /** Write a new name in place (the header's name, {@link standardNodeRename}). */
  renameTo?: (name: string) => Promise<void>
  move?: () => void
  remove?: () => void
}

/** The share / rename / move / delete actions of a node from the caller's rights answers: only the granted ones, in that
 *  order. `t` is the caller's translate function (core's neutral words for the labels). A level row offers all four
 *  (`standardRowActions.ts`); a header {@link standardNodeActions}. */
export function standardActionList(
  t: (key: string) => string,
  flags: StandardActionFlags,
  on: StandardActionHandlers,
): NodeHeaderAction[] {
  const noop = () => {}
  const all: NodeHeaderAction[] = [
    { id: 'share', label: flags.shareLabel ?? t('share.share'), icon: 'share-alt', onClick: on.share ?? noop, shown: !!flags.share },
    { id: 'rename', label: t('node.rename'), icon: 'pencil', onClick: on.rename ?? noop, shown: !!flags.rename },
    { id: 'move', label: t('node.move.action'), icon: 'file-export', onClick: on.move ?? noop, shown: !!flags.move },
    { id: 'delete', label: flags.deleteLabel ?? t('node.delete'), icon: 'trash', onClick: on.remove ?? noop, severity: 'danger', shown: !!flags.delete },
  ]
  return all.filter((a) => a.shown)
}

/** A node HEADER's standard actions (ADR 0110 decision 11): share, then move and delete in the ⋯ menu - the move is
 *  menu-only there, as core's copy beside it. The rename is no action of a header: the name itself is edited in place
 *  ({@link standardNodeRename}), so a header offers no entry for it, whatever `flags.rename` says. */
export function standardNodeActions(
  t: (key: string) => string,
  flags: StandardActionFlags,
  on: StandardActionHandlers,
): NodeHeaderAction[] {
  return standardActionList(t, { ...flags, rename: false }, on).map((a) => (a.id === 'move' ? { ...a, menuOnly: true } : a))
}

/** The header's rename in place (ADR 0110 decision 11): where the rights answer grants the rename AND the page has the write
 *  (`on.renameTo`, from its `renameTo` door), else null - the name is plain text. */
export function standardNodeRename(flags: Pick<StandardActionFlags, 'rename'>, on: Pick<StandardActionHandlers, 'renameTo'>): NodeHeaderRename | null {
  const write = on.renameTo
  return flags.rename && write ? { save: (name) => write(name) } : null
}
