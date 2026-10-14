// The unified level list (inbox #407, owner design 2026-10-12): ONE list or tile collection per folder level, drawn by
// core. An application no longer draws a "section"; for each node of its kinds it supplies ROW DATA, and core draws the
// row (or the tile): a leading visual, the name, one meta line, a primary click and the action buttons.
//
// **What an application supplies.** A kind names `list.rows` in its descriptor (`registry.ts`): a factory called once per
// level view (in its setup, so it may use composables, watchers and the app's stores) with the level's reactive
// {@link LevelRowsContext} and the {@link LevelRowsHost}, returning a {@link LevelRowSource}: `row(node)` for each of its
// nodes, plus what only the application can explain (`notices`) and its alternative presentation (`presentation`, e.g.
// Memories' map). It owns the DATA a row needs (Files' blob listing, Memories' covers) and the flows behind its actions
// (Files' upload and replace); it draws nothing of the list itself. Several kinds may name one factory: it is called once.
//
// **An action is already a capability answer.** The factory builds the action list from its application's fail-closed
// capability function (`core/access.ts`, `capabilities.ts`): an action it was not granted is `shown: false` or left out,
// and core decides nothing of its own. A share guest gets this very row with fewer actions (inbox #401), never a
// guest-only one. Core may add its own actions next to the application's (`coreActions`, merged by
// {@link mergeRowActions}): the favourite heart of inbox #409 will be one, with no change to any application.
//
// **Visible and overflow.** At most {@link MAX_FIRST_ACTIONS} actions the kind marks `first` are real buttons on the row;
// every other shown action sits in ONE "more" menu (a PrimeVue `Menu`), so a row stays readable at every width and no
// action is hidden by a media query (a width-hidden button is a lost action).
import type { Component, Ref } from 'vue'
import type { TreeNode } from './tree'
import type { EpochKeys } from '../../features/spaces/epochs'
import type { Access, TreeSource } from '../access'
import type { ViewMode } from '../../shared/state/useViewMode'
import type { CoreHeaderAction, NodeHeaderAction } from './nodeHeader'
import type { LevelActionHandler } from './levelActions'
import type { LoadedCover } from './cover/coverModel'
import type { DirectoryInput } from '../export/importSource'

/** How many `first` actions a row shows as buttons; further ones fall into the overflow menu. */
export const MAX_FIRST_ACTIONS = 2

/** One action of a row: the header action model of inbox #406 plus where it sits. */
export interface LevelRowAction extends NodeHeaderAction {
  /** A first-class action: a visible button on the row (at most {@link MAX_FIRST_ACTIONS}); the rest are in the menu. */
  first?: boolean
  /** The action is running (a download in progress): the button shows a spinner. */
  loading?: boolean
}

/** A core-owned action next to the application's (see the header). */
export interface CoreRowAction extends LevelRowAction {
  placement?: 'before' | 'after'
}

/** The row's leading visual when the kind's icon is not it: an application component with its props (Memories' cover). */
export interface LevelRowLeading {
  component: Component
  props?: Record<string, unknown>
}

/** One fact in a row's meta line: a stable `id` (a styling and testing hook, `data-detail`; core names none) and its text. */
export interface LevelRowDetail {
  id: string
  text: string
}

/** The details core draws: those with text, in the given order. A blank entry is dropped here as well as by the
 *  application, so a placeholder-shaped hole can never reach the line. */
export function levelRowDetails(row: Pick<LevelRow, 'details'>): LevelRowDetail[] {
  return (row.details ?? []).filter((d) => d.text.trim() !== '')
}

/** What core draws for one node. */
export interface LevelRow {
  /** The PrimeIcons class drawn when there is no `leading`. */
  icon: string
  leading?: LevelRowLeading
  title: string
  /** The kind line of the meta ("File", "Memory"), already translated. The row's details follow it on the same line. */
  meta: string
  /** What the application knows of THIS node worth a glance, in the order shown after {@link meta}: a Memory's date and
   *  place, a file's size and modified time. Already translated and formatted for the reader's locale. An entry the node
   *  has no value for is LEFT OUT (never a placeholder, "unknown", "-"); core also drops a blank one (`levelRowDetails`).
   *  Omitted or empty: the line is the kind alone. The same hook serves a share guest as a member - rights remove
   *  functions, not facts the served data already carries. */
  details?: readonly LevelRowDetail[]
  /** The row's primary click: what a plain click on the row does (open, or download). `label` is its accessible name. */
  primary: { label: string; onClick: () => void; disabled?: boolean }
  actions: readonly LevelRowAction[]
}

/** The level, reactive, as a row source reads it. */
export interface LevelRowsContext {
  folder: TreeNode | null
  /** The nodes of every kind that names this factory, at this level (not filtered by the shown tab: the data an
   *  application loads for them does not depend on it). */
  nodes: readonly TreeNode[]
  /** The member's epoch keys; `null` for a guest, whose keys come through `tree`. */
  epochs: EpochKeys | null
  access: Access
  tree: TreeSource
  /** The mode the level shows (inbox #404), already clamped to what is on screen. */
  viewMode: ViewMode
  /** The count of live refreshes completed so far (inbox #408); absent where the host does not poll. */
  liveTick: number | undefined
}

/** What the level lets a row source do. */
export interface LevelRowsHost {
  /** Open a node (its detail, or the folder). */
  open(node: TreeNode): void
  /** Rename a node in place (core's `FolderNameDialog`). */
  rename(node: TreeNode): void
  /** Move a node (core's `MoveNodeDialog`). */
  move(node: TreeNode): void
  /** Register the handler of a level action (a "New" menu entry that is no create dialog) for the level's life. */
  registerAction(id: string, handler: LevelActionHandler): void
  /** The decrypted covers of the nodes at this level (inbox #411), whatever their kind: core resolves them for the level
   *  (a member's from the shared store, a guest's through the link's own reads), so a row source that draws its own
   *  leading visual (Memories' cover tile) reads the very image core draws elsewhere and loads none of its own. */
  covers: Readonly<Ref<ReadonlyMap<string, LoadedCover>>>
  /** Resolve the covers of further nodes the source draws that are not at this level (a map's pins across the subtree). */
  ensureCovers(nodes: readonly TreeNode[]): void
  /** Look at files picked for an upload before they are uploaded (ADR 0082 decision 7): a veyq download among them is
   *  recognised by its content and, for a member who may create here, offered as an import - "Upload as a file" hands it
   *  back through `uploadAsFile`. Resolves the files to upload now (all of them for a guest, or where nothing is
   *  recognised). Absent: upload them all. */
  screenUpload?(files: File[], uploadAsFile: (files: File[]) => void): Promise<File[]>
  /** Look at a directory picked for a folder upload before it is uploaded (ADR 0082 decision 7, inbox #424): a download
   *  unpacked (`.veyq/manifest.json` at its root) is, for a member who may create here, offered as the import - true, and
   *  the caller uploads nothing. False: upload it as its tree (a guest's directory always; the `.veyq/` in it as files).
   *  Absent: upload it. */
  screenDirectory?(dir: DirectoryInput): boolean
}

/** A message the application shows above the list, e.g. why a file was not uploaded. */
export interface LevelNotice {
  key: string
  severity: 'error' | 'info'
  text: string
  /** Buttons of the message. */
  actions?: readonly { id: string; label: string; onClick: () => void }[]
  /** Present: the message has a dismiss button. */
  dismiss?: () => void
}

/** An application's own presentation of its nodes in a mode core does not draw (Memories' map): a component with props. */
export interface LevelPresentation {
  component: Component
  props: Record<string, unknown>
  on?: Record<string, (...args: never[]) => void>
}

/** What a row factory returns. */
export interface LevelRowSource {
  /** The row of `node`, or `null` for a node this source cannot draw (core then shows the explicit unsupported row). */
  row(node: TreeNode): LevelRow | null
  notices?: Readonly<Ref<readonly LevelNotice[]>>
  /** The application's presentation for `mode`, or null (core draws its rows). */
  presentation?(mode: ViewMode): LevelPresentation | null
}

export type LevelRowsFactory = (ctx: Readonly<Ref<LevelRowsContext>>, host: LevelRowsHost) => LevelRowSource

/** The row's actions: core's contributions around the application's list (stable order within each group). */
export function mergeRowActions(core: readonly CoreRowAction[], app: readonly LevelRowAction[]): LevelRowAction[] {
  const before = core.filter((a) => a.placement === 'before')
  const after = core.filter((a) => a.placement !== 'before')
  return [...before, ...app, ...after]
}

/** Split a row's actions into the buttons on the row and the overflow menu: the shown ones only, the first
 *  {@link MAX_FIRST_ACTIONS} of those marked `first` as buttons, everything else in the menu, each in the given order. */
export function splitRowActions(actions: readonly (LevelRowAction | CoreHeaderAction)[]): { buttons: LevelRowAction[]; menu: LevelRowAction[] } {
  const shown = actions.filter((a) => a.shown !== false) as LevelRowAction[]
  const buttons = shown.filter((a) => a.first === true).slice(0, MAX_FIRST_ACTIONS)
  return { buttons, menu: shown.filter((a) => !buttons.includes(a)) }
}
