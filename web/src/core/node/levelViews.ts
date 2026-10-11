// The presentations a level's content can switch between (inbox #370): the one app-wide view mode
// (`shared/state/useViewMode`), offered by core's level-view toolbar as the union of what is on screen. Core's folders
// section presents a card grid and a list (`FOLDER_VIEWS`); a section brings its own through its kind's descriptor
// (`KindSection.views`, e.g. Memories' map); a section with one presentation names none. Core names no application's
// view: a view's label is an i18n key its owner supplies, as a kind's noun is.
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
