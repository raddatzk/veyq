// @vitest-environment happy-dom
import { describe, it, expect, beforeEach } from 'vitest'
import { initializeTheme, setThemePreference, useThemeState } from './useThemeState'

beforeEach(() => {
  localStorage.clear()
  document.documentElement.className = ''
})

describe('useThemeState', () => {
  it('forces dark + persists, flipping the .veyq-dark class on <html>', () => {
    initializeTheme()
    setThemePreference('dark')
    expect(document.documentElement.classList.contains('veyq-dark')).toBe(true)
    expect(document.documentElement.classList.contains('veyq-light')).toBe(false)
    expect(localStorage.getItem('veyq:theme')).toBe('dark')
    expect(useThemeState().resolvedTheme.value).toBe('dark')
  })

  it('forces light', () => {
    setThemePreference('light')
    expect(document.documentElement.classList.contains('veyq-light')).toBe(true)
    expect(document.documentElement.classList.contains('veyq-dark')).toBe(false)
    expect(localStorage.getItem('veyq:theme')).toBe('light')
  })

  it('persists the system preference (no forced override)', () => {
    setThemePreference('system')
    expect(localStorage.getItem('veyq:theme')).toBe('system')
    expect(useThemeState().themePreference.value).toBe('system')
  })
})
