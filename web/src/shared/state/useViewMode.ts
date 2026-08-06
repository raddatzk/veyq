// Card-grid view mode (cr-0063): the memory card grid (browse + the Chapters tab) renders as cards ('grid'), a
// plain list ('list'), or — in the browse only — a places map ('map', cr-0071). One app-wide preference, persisted
// in localStorage — switching in one place applies everywhere. No secrets here; follows the useThemeState pattern
// (a ref + a setter/toggle).
import { readonly, ref } from 'vue'

export type ViewMode = 'grid' | 'list' | 'map'

const VIEW_MODE_STORAGE_KEY = 'veyq:view-mode'
const VIEW_MODES: readonly ViewMode[] = ['grid', 'list', 'map']
const viewMode = ref<ViewMode>(readStoredViewMode())

export function useViewMode() {
  return {
    viewMode: readonly(viewMode),
    setViewMode,
    toggleViewMode,
  }
}

export function setViewMode(mode: ViewMode): void {
  viewMode.value = mode
  storage()?.setItem(VIEW_MODE_STORAGE_KEY, mode)
}

export function toggleViewMode(): void {
  setViewMode(viewMode.value === 'grid' ? 'list' : 'grid')
}

function readStoredViewMode(): ViewMode {
  const stored = storage()?.getItem(VIEW_MODE_STORAGE_KEY)
  return VIEW_MODES.find((m) => m === stored) ?? 'grid'
}

function storage(): Storage | null {
  try {
    return typeof localStorage === 'undefined' ? null : localStorage
  } catch {
    return null // storage disabled (private mode / blocked) → in-memory default only
  }
}
