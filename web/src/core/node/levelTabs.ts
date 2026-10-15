// The application tabs of the level view (inbox #348, a pure FILTER since inbox #407): `All | <one tab per application>`.
// The unified level list (`LevelView.vue`) is ONE list of the current folder's own children; the tab bar filters it. `All` is
// everything - the folders, every kind, the kinds nobody lists (as unsupported rows); an application tab shows the nodes of the
// kinds it names, and the FOLDERS stay in every tab so one can still navigate deeper. No subtree query: a tab never shows more
// than the folder on screen holds.
//
// Which tab is shown is a stable client setting (`shared/state/useLevelTab`, one value per account and one for the share-guest
// pages, like the view mode). The level shows the stored tab only where this level offers it (`effectiveTab`), else `All`, and
// never rewrites the stored value: a folder without Depot shows `All`, and the next folder with Depot shows the Depot tab again.
//
// A tab is named by its first kind (the tab's stable identity across levels, what the preference stores), labelled by the i18n
// key its application supplies (`KindList.tabLabel`) - core names no application. Kinds sharing a label share a tab (Memories:
// MEMORY and CHAPTER); a kind without a label has no tab and shows under `All` only.

/** The tab that is the whole level. */
export const ALL_TAB = 'all'

/** One tab of an application: its id (its first kind) and the i18n key of its label. */
export interface LevelTab {
  readonly id: string
  readonly label: string
}

/** The tab bar only where it can choose: two or more application tabs. One application needs no filter (`All` is it). */
export function showTabBar(tabs: readonly LevelTab[]): boolean {
  return tabs.length >= 2
}

/** The tab a level shows: the stored one where the bar offers it, else `All` (also when there is no bar). The stored
 *  value is never rewritten here. */
export function effectiveTab(stored: string, tabs: readonly LevelTab[]): string {
  return showTabBar(tabs) && tabs.some((t) => t.id === stored) ? stored : ALL_TAB
}

/** A tab as the registry derives it (`registry.tabs()`): its id, its label key and every kind it filters to. */
export interface KindTab extends LevelTab {
  readonly kinds: readonly string[]
}

/** The tabs this level offers: one whose kinds have a node here, or whose application the viewer may add to (a kind with an
 *  allowed "New" entry) - so a reader is not shown an empty "Drop boxes" tab, a writer is shown every place to start. */
export function offeredTabs<T extends KindTab>(specs: readonly T[], present: ReadonlySet<string>, creatable: ReadonlySet<string>): T[] {
  return specs.filter((t) => t.kinds.some((k) => present.has(k) || creatable.has(k)))
}

/** Whether a node of `kind` is drawn under the `shown` tab: everything under `All` (a kind with no tab included), else only
 *  the kinds of that tab. FOLDERS are not asked: they are drawn under every tab. */
export function kindVisible(shown: string, tabs: readonly KindTab[], kind: string): boolean {
  return shown === ALL_TAB || tabs.some((t) => t.id === shown && t.kinds.includes(kind))
}
