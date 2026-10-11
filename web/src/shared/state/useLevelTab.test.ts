// @vitest-environment happy-dom
// The level view's tab preference (inbox #348): defaults to All, persists, survives a reload, tolerates blocked storage.
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { useLevelTab, setLevelTab, LEVEL_TAB_STORAGE_KEY } from './useLevelTab'

beforeEach(() => {
  vi.unstubAllGlobals()
  localStorage.clear()
  setLevelTab('all') // reset the module singleton between tests
})

describe('useLevelTab', () => {
  it('defaults to all and persists the choice to localStorage', () => {
    localStorage.clear()
    expect(useLevelTab().levelTab.value).toBe('all')
    setLevelTab('FILE')
    expect(useLevelTab().levelTab.value).toBe('FILE')
    expect(localStorage.getItem(LEVEL_TAB_STORAGE_KEY)).toBe('FILE')
  })

  it('is restored from storage by a fresh module (a reload)', async () => {
    localStorage.setItem(LEVEL_TAB_STORAGE_KEY, 'MEMORY')
    vi.resetModules()
    const fresh = await import('./useLevelTab')
    expect(fresh.useLevelTab().levelTab.value).toBe('MEMORY')
  })

  it('falls back to all for an empty stored value', async () => {
    localStorage.setItem(LEVEL_TAB_STORAGE_KEY, '')
    vi.resetModules()
    const fresh = await import('./useLevelTab')
    expect(fresh.useLevelTab().levelTab.value).toBe('all')
  })

  it('keeps the choice in memory when storage throws on write, and starts at all when it throws on read', async () => {
    const real = localStorage
    const throwing = () => {
      throw new Error('blocked')
    }
    vi.stubGlobal('localStorage', { getItem: real.getItem.bind(real), setItem: throwing })
    setLevelTab('FILE')
    expect(useLevelTab().levelTab.value).toBe('FILE')
    expect(real.getItem(LEVEL_TAB_STORAGE_KEY)).toBe('all')
    vi.stubGlobal('localStorage', { getItem: throwing, setItem: throwing })
    vi.resetModules()
    const fresh = await import('./useLevelTab')
    expect(fresh.useLevelTab().levelTab.value).toBe('all')
    vi.unstubAllGlobals()
  })
})
