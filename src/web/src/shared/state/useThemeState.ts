// Theme preference (cr-0031, ported from the design source). Drives the `.veyq-dark` class on <html> — which
// our vendored dark.css and the UI layer's roles key off — plus a matching favicon. The preference
// (system / light / dark) persists in localStorage; "system" follows the OS via a media query. No secrets here.
import { readonly, ref } from 'vue'
import logoDarkSvg from '../../assets/brand/logo-dark.svg?raw'
import logoLightSvg from '../../assets/brand/logo-light.svg?raw'

export type ThemePreference = 'system' | 'light' | 'dark'

const THEME_STORAGE_KEY = 'veyq:theme'
const FAVICON_ID = 'veyq-favicon'
const FAVICON_LIGHT_HREF = svgDataUrl(logoLightSvg)
const FAVICON_DARK_HREF = svgDataUrl(logoDarkSvg)
const themePreference = ref<ThemePreference>(readStoredThemePreference())
const resolvedTheme = ref<'light' | 'dark'>('light')
let mediaQuery: MediaQueryList | null = null

/** Wire up the OS-theme listener and apply the stored preference. Call once at app startup (main.ts). */
export function initializeTheme(): void {
  if (typeof window !== 'undefined') {
    mediaQuery = window.matchMedia('(prefers-color-scheme: dark)')
    mediaQuery.addEventListener('change', applyTheme)
  }
  applyTheme()
}

export function useThemeState() {
  return {
    themePreference: readonly(themePreference),
    resolvedTheme: readonly(resolvedTheme),
    setThemePreference,
  }
}

export function setThemePreference(preference: ThemePreference): void {
  themePreference.value = preference
  storage()?.setItem(THEME_STORAGE_KEY, preference)
  applyTheme()
}

function applyTheme(): void {
  const next = themePreference.value === 'system' ? (mediaQuery?.matches ? 'dark' : 'light') : themePreference.value
  resolvedTheme.value = next
  if (typeof document === 'undefined') return
  // `.veyq-dark` is the ONLY class any stylesheet reads: dark.css is one `.veyq-dark { ... }`
  // and base.css's dark roles sit under `:root.veyq-dark`. A `.veyq-light` companion used to be written
  // beside it and was read by nothing (inbox #137); admin-web already declined to carry either
  // (shared/state/systemTheme.ts), so it is gone here too rather than kept by symmetry.
  document.documentElement.classList.toggle('veyq-dark', next === 'dark')
  // `data-theme` is DELIBERATE and is not a CSS hook -- no stylesheet in either package reads it.
  // It carries the PREFERENCE ('system' | 'light' | 'dark'), which the class cannot: `.veyq-dark`
  // only reports the resolved theme, so "dark because the OS says so" and "dark because the user
  // chose it" look identical without this. Kept as a debugging and e2e handle on that basis.
  document.documentElement.dataset.theme = themePreference.value
  applyFavicon(next)
}

function applyFavicon(next: 'light' | 'dark'): void {
  if (typeof document === 'undefined') return
  document.getElementById(FAVICON_ID)?.remove()
  const link = document.createElement('link')
  link.id = FAVICON_ID
  link.rel = 'icon'
  link.type = 'image/svg+xml'
  link.href = next === 'dark' ? FAVICON_DARK_HREF : FAVICON_LIGHT_HREF
  document.head.append(link)
}

function readStoredThemePreference(): ThemePreference {
  const value = storage()?.getItem(THEME_STORAGE_KEY)
  return value === 'light' || value === 'dark' || value === 'system' ? value : 'system'
}

function storage(): Storage | null {
  try {
    return typeof window !== 'undefined' ? window.localStorage : null
  } catch {
    return null
  }
}

function svgDataUrl(svg: string): string {
  return `data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg)}`
}
