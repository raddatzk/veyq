// The application tabs of the level view (inbox #348): `All | <one tab per application section shown at this level>`.
// The tab bar is a FILTER over the current folder's own children, not a search: `All` is the whole level (folders, every
// section, the fallback sections); an application tab shows that one section's nodes, and the sub-folders stay in every
// tab so one can still navigate deeper. No subtree query: a tab never shows more than the folder on screen holds.
//
// Which tab is shown is a stable client setting (`shared/state/useLevelTab`, one global value like the view mode). The
// level shows the stored tab only where this level has it (`effectiveTab`), else `All`, and never rewrites the stored
// value: a folder without Files shows `All`, and the next folder with Files shows the Files tab again.
//
// A tab is named by its section's first kind (the section's stable identity across levels), labelled by the i18n key
// its application supplies (`KindSection.tabLabel`) — core names no application. A section without a label has no tab.

/** The tab that is the whole level. */
export const ALL_TAB = 'all'

/** One tab of an application section: its id (the section's first kind) and the i18n key of its label. */
export interface LevelTab {
  readonly id: string
  readonly label: string
}

/** The tab bar only where it can choose: two or more application tabs. One section needs no filter (`All` is it). */
export function showTabBar(tabs: readonly LevelTab[]): boolean {
  return tabs.length >= 2
}

/** The tab a level shows: the stored one where the bar offers it, else `All` (also when there is no bar). The stored
 *  value is never rewritten here. */
export function effectiveTab(stored: string, tabs: readonly LevelTab[]): string {
  return showTabBar(tabs) && tabs.some((t) => t.id === stored) ? stored : ALL_TAB
}

/** Whether the section with tab id `id` (undefined: it has no tab) is drawn under the shown tab: all of them under
 *  `All`, otherwise only the section of that tab. */
export function sectionVisible(shown: string, id: string | undefined): boolean {
  return shown === ALL_TAB || shown === id
}

/** Whether the fallback sections (kinds no section serves) are drawn: only under `All`, they belong to no application tab. */
export function fallbacksVisible(shown: string): boolean {
  return shown === ALL_TAB
}
