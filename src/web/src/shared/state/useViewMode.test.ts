// @vitest-environment happy-dom
// cr-0063 — the card-grid view-mode preference round-trips toggle + list/grid and persists to localStorage; inbox #408 —
// it is one value per account and one for every share guest page, as the level tab is (`useLevelTab.test.ts`).
import { describe, it, expect, beforeEach } from 'vitest'
import { useViewMode, setViewMode, toggleViewMode, viewModeStorageKey } from './useViewMode'
import { setPreferenceAudience } from './scopedPreference'
import { setAccountUserId } from '../../app/account'

beforeEach(() => {
  localStorage.clear()
  setPreferenceAudience('account')
  setAccountUserId('user-1')
  setViewMode('grid') // reset the module singleton between tests
})

describe('useViewMode', () => {
  it('defaults to grid and toggles to list and back', () => {
    const { viewMode } = useViewMode()
    expect(viewMode.value).toBe('grid')
    toggleViewMode()
    expect(viewMode.value).toBe('list')
    toggleViewMode()
    expect(viewMode.value).toBe('grid')
  })

  it('persists the choice to localStorage under the account key', () => {
    setViewMode('list')
    expect(viewModeStorageKey()).toBe('veyq:view-mode:u:user-1')
    expect(localStorage.getItem('veyq:view-mode:u:user-1')).toBe('list')
    setViewMode('grid')
    expect(localStorage.getItem('veyq:view-mode:u:user-1')).toBe('grid')
  })

  it('supports the map mode (cr-0071) and restores it from storage', () => {
    setViewMode('map')
    expect(localStorage.getItem('veyq:view-mode:u:user-1')).toBe('map')
    // a fresh useViewMode() reads the same persisted singleton
    expect(useViewMode().viewMode.value).toBe('map')
  })

  it('reads an unknown stored mode as the grid without rewriting it', () => {
    localStorage.setItem('veyq:view-mode:u:user-2', 'hologram')
    setAccountUserId('user-2')
    expect(useViewMode().viewMode.value).toBe('grid')
    expect(localStorage.getItem('veyq:view-mode:u:user-2')).toBe('hologram')
  })

  it('keeps two accounts apart, and a share guest apart from both', () => {
    setViewMode('list')
    setAccountUserId('user-2')
    expect(useViewMode().viewMode.value).toBe('grid')
    setViewMode('map')
    setPreferenceAudience('guest')
    expect(useViewMode().viewMode.value).toBe('grid')
    setViewMode('list')
    expect(localStorage.getItem('veyq:view-mode:guest')).toBe('list')
    expect(localStorage.getItem('veyq:view-mode:u:user-1')).toBe('list')
    expect(localStorage.getItem('veyq:view-mode:u:user-2')).toBe('map')
    setPreferenceAudience('account')
    expect(useViewMode().viewMode.value).toBe('map')
  })
})
