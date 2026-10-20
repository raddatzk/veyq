// The level view's selected application tab (inbox #348): a stable client setting, one per USER of the browser — an
// account's own (`veyq:level-tab:u:<userId>`), and one for every share guest page (`veyq:level-tab:guest`), never one
// value for every role (`scopedPreference.ts` says why and how). The value is an opaque tab id (`core/node/levelTabs.ts`)
// or 'all'; whether the level at hand has that tab is the level's question (`effectiveTab`), so an id no level offers is
// simply shown as 'all' and stays stored. Every storage access is guarded: blocked storage means an in-memory preference.
import { readonly } from 'vue'
import { createScopedPreference, preferenceStorageKey } from './scopedPreference'

const LEVEL_TAB_BASE_KEY = 'veyq:level-tab'
const DEFAULT_TAB = 'all'
const pref = createScopedPreference<string>(LEVEL_TAB_BASE_KEY, (raw) => raw || undefined, DEFAULT_TAB)

/** Where the tab of whoever looks now is stored, or null while there is no owner to name. */
export const levelTabStorageKey = (): string | null => preferenceStorageKey(LEVEL_TAB_BASE_KEY)

export function useLevelTab() {
  return { levelTab: readonly(pref.value), setLevelTab }
}

export function setLevelTab(id: string): void {
  pref.set(id)
}
