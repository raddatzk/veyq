// Card-grid view mode (cr-0063): the memory card grid (browse + the Chapters tab) renders as cards ('grid'), a
// plain list ('list'), or — in the browse only — a places map ('map', cr-0071). A stable client setting, one per USER of
// the browser (an account's own, one for every share guest page — `scopedPreference.ts`): switching in one place applies
// everywhere for whoever is looking. No secrets here; a ref plus a setter/toggle.
import { readonly } from 'vue'
import { createScopedPreference, preferenceStorageKey } from './scopedPreference'

export type ViewMode = 'grid' | 'list' | 'map'

const VIEW_MODE_BASE_KEY = 'veyq:view-mode'
const VIEW_MODES: readonly ViewMode[] = ['grid', 'list', 'map']
const pref = createScopedPreference<ViewMode>(VIEW_MODE_BASE_KEY, (raw) => VIEW_MODES.find((m) => m === raw), 'grid')

/** Where the view mode of whoever looks now is stored, or null while there is no owner to name. */
export const viewModeStorageKey = (): string | null => preferenceStorageKey(VIEW_MODE_BASE_KEY)

export function useViewMode() {
  return {
    viewMode: readonly(pref.value),
    setViewMode,
    toggleViewMode,
  }
}

export function setViewMode(mode: ViewMode): void {
  pref.set(mode)
}

export function toggleViewMode(): void {
  setViewMode(pref.value.value === 'grid' ? 'list' : 'grid')
}
