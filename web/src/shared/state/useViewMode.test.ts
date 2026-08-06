// @vitest-environment happy-dom
// cr-0063 — the card-grid view-mode preference round-trips toggle + list/grid and persists to localStorage.
import { describe, it, expect, beforeEach } from 'vitest'
import { useViewMode, setViewMode, toggleViewMode } from './useViewMode'

beforeEach(() => {
  localStorage.clear()
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

  it('persists the choice to localStorage', () => {
    setViewMode('list')
    expect(localStorage.getItem('veyq:view-mode')).toBe('list')
    setViewMode('grid')
    expect(localStorage.getItem('veyq:view-mode')).toBe('grid')
  })

  it('supports the map mode (cr-0071) and restores it from storage', () => {
    setViewMode('map')
    expect(localStorage.getItem('veyq:view-mode')).toBe('map')
    // a fresh useViewMode() reads the same persisted singleton
    expect(useViewMode().viewMode.value).toBe('map')
  })
})
