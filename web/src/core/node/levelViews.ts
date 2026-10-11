// The presentations a level's content can switch between (inbox #370): the one app-wide view mode
// (`shared/state/useViewMode`), offered by core's level-view toolbar as the union of what is on screen. Core's folders
// section presents a card grid and a list (`FOLDER_VIEWS`); a section brings its own through its kind's descriptor
// (`KindSection.views`, e.g. Memories' map); a section with one presentation names none. Core names no application's
// view: a view's label is an i18n key its owner supplies, as a kind's noun is. The level shows the stored mode only where
// something on screen offers it (`effectiveViewMode`, inbox #404) and hands that mode to its sections.
import type { ViewMode } from '../../shared/state/useViewMode'

/** One presentation: the mode it sets, its icon, and the i18n key of its label (tooltip and accessible name). */
export interface LevelViewOption {
  readonly mode: ViewMode
  readonly icon: string
  readonly label: string
}

export const GRID_VIEW: LevelViewOption = { mode: 'grid', icon: 'pi pi-th-large', label: 'node.viewGrid' }
export const LIST_VIEW: LevelViewOption = { mode: 'list', icon: 'pi pi-bars', label: 'node.viewList' }

/** Core's folders section: its card grid and the list variant of it. */
export const FOLDER_VIEWS: readonly LevelViewOption[] = [GRID_VIEW, LIST_VIEW]

/** Every mode of `lists` once, in the order first named (the toolbar's button order). */
export function unionViews(lists: readonly (readonly LevelViewOption[])[]): LevelViewOption[] {
  const out: LevelViewOption[] = []
  for (const view of lists.flat()) if (!out.some((v) => v.mode === view.mode)) out.push(view)
  return out
}

/**
 * The mode a level actually shows (inbox #404): the stored preference where something on screen offers it, else the
 * grid (where it is offered, or nothing is: a section without views draws its one presentation for the grid), else the
 * first offered. The stored value is never rewritten here, so a level without the map shows its grid and the next level
 * with a map shows the map again.
 */
export function effectiveViewMode(stored: ViewMode, offered: readonly LevelViewOption[]): ViewMode {
  if (offered.some((v) => v.mode === stored)) return stored
  if (!offered.length || offered.some((v) => v.mode === GRID_VIEW.mode)) return GRID_VIEW.mode
  return offered[0].mode
}
