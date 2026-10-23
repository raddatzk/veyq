// The presentations a level's content can switch between (inbox #370): the one app-wide view mode
// (`shared/state/useViewMode`), offered by core's level-view toolbar as the union of what is on screen. Core draws every row
// as a tile or as a row (`FOLDER_VIEWS`: the grid and the list, whatever the kind); an application brings a presentation of its
// own through its kind's descriptor (`KindList.views`, e.g. Memories' map, drawn by the row factory's `presentation(mode)`); a
// kind with nothing of its own names none. Core names no application's view: a view's label is an i18n key its owner supplies,
// as a kind's noun is. The level shows the stored mode only where something on screen offers it (`effectiveViewMode`, inbox
// #404) and hands that mode to the row factories (`LevelRowsContext.viewMode`).
import type { ViewMode } from '../../shared/state/useViewMode'
import type { VeyqIconName } from '../../shared/ui/icons'

/** One presentation: the mode it sets, its icon, and the i18n key of its label (tooltip and accessible name). */
export interface LevelViewOption {
  readonly mode: ViewMode
  readonly icon: VeyqIconName
  readonly label: string
}

export const GRID_VIEW: LevelViewOption = { mode: 'grid', icon: 'th-large', label: 'node.viewGrid' }
export const LIST_VIEW: LevelViewOption = { mode: 'list', icon: 'bars', label: 'node.viewList' }

/** What core draws for every row: tiles and rows. */
export const FOLDER_VIEWS: readonly LevelViewOption[] = [GRID_VIEW, LIST_VIEW]

/** Every mode of `lists` once, in the order first named (the toolbar's button order). */
export function unionViews(lists: readonly (readonly LevelViewOption[])[]): LevelViewOption[] {
  const out: LevelViewOption[] = []
  for (const view of lists.flat()) if (!out.some((v) => v.mode === view.mode)) out.push(view)
  return out
}

/**
 * The mode a level actually shows (inbox #404): the stored preference where something on screen offers it, else the
 * grid (where it is offered, or nothing is), else the
 * first offered. The stored value is never rewritten here, so a level without the map shows its grid and the next level
 * with a map shows the map again.
 */
export function effectiveViewMode(stored: ViewMode, offered: readonly LevelViewOption[]): ViewMode {
  if (offered.some((v) => v.mode === stored)) return stored
  if (!offered.length || offered.some((v) => v.mode === GRID_VIEW.mode)) return GRID_VIEW.mode
  return offered[0].mode
}
