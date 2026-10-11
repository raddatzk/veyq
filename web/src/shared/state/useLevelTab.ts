// The level view's selected application tab (inbox #348): one global client preference, persisted in localStorage like
// `useViewMode` (a ref plus a setter; no secrets). The value is an opaque tab id (`core/node/levelTabs.ts`) or 'all';
// whether the level at hand has that tab is the level's question (`effectiveTab`), so an id no level offers is simply
// shown as 'all' and stays stored. Every storage access is guarded: blocked storage means an in-memory preference.
import { readonly, ref } from 'vue'

export const LEVEL_TAB_STORAGE_KEY = 'veyq:level-tab'
const DEFAULT_TAB = 'all'
const levelTab = ref<string>(readStoredTab())

export function useLevelTab() {
  return { levelTab: readonly(levelTab), setLevelTab }
}

export function setLevelTab(id: string): void {
  levelTab.value = id
  try {
    storage()?.setItem(LEVEL_TAB_STORAGE_KEY, id)
  } catch {
    // quota / blocked: the choice lives in memory only
  }
}

function readStoredTab(): string {
  try {
    return storage()?.getItem(LEVEL_TAB_STORAGE_KEY) || DEFAULT_TAB
  } catch {
    return DEFAULT_TAB
  }
}

function storage(): Storage | null {
  try {
    return typeof localStorage === 'undefined' ? null : localStorage
  } catch {
    return null // storage disabled (private mode / blocked) → in-memory default only
  }
}
