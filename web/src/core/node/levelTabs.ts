// The application tabs of the level view (inbox #348): `All | <one tab per application section shown at this level>`.
// The tab bar is a FILTER over the current folder's own children, not a search: `All` is the whole level (folders, every
// section, the fallback sections); an application tab shows that one section's nodes, and the sub-folders stay in every
// tab so one can still navigate deeper. No subtree query: a tab never shows more than the folder on screen holds.
//
// Which tab is shown is a stable client setting (`shared/state/useLevelTab`, one value per account and one for the share-guest pages, like the view mode). The
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

// --- the tabs as a pure FILTER over one list (inbox #407) --------------------------------------------------------------

/** A tab as the registry derives it (`registry.tabs()`): its id (the first kind that named the label), its label key and
 *  every kind it filters to. */
export interface KindTab extends LevelTab {
  readonly kinds: readonly string[]
}

/** The tabs this level offers: one whose kinds have a node here, or whose application the viewer may add to (a kind with an
 *  allowed "New" entry) - so a reader is not shown an empty "Briefkästen" tab, a writer is shown every place to start. */
export function offeredTabs<T extends KindTab>(specs: readonly T[], present: ReadonlySet<string>, creatable: ReadonlySet<string>): T[] {
  return specs.filter((t) => t.kinds.some((k) => present.has(k) || creatable.has(k)))
}

/** Whether a node of `kind` is drawn under the `shown` tab: everything under `All` (a kind with no tab included), else only
 *  the kinds of that tab. FOLDERS are not asked: they are drawn under every tab. */
export function kindVisible(shown: string, tabs: readonly KindTab[], kind: string): boolean {
  return shown === ALL_TAB || tabs.some((t) => t.id === shown && t.kinds.includes(kind))
}
