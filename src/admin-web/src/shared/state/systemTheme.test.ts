// @vitest-environment happy-dom
// The theme this surface can actually reach. `darkModeSelector: '.veyq-dark'` and a 467-line `dark.css` were
// both shipped and both unreachable until this module existed, so the cases below assert the class itself —
// the one thing every stylesheet keys off — and never the module's internals.
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { initializeTheme } from './systemTheme'

type SchemeListener = (event: { matches: boolean }) => void

/** Install a `matchMedia` answering `prefers-color-scheme: dark` with `dark`, and return a handle that fires
 *  the change listener — the half that makes this "follow the OS" rather than "read it once at boot". */
function stubColorScheme(dark: boolean) {
  const listeners: SchemeListener[] = []
  const query = {
    matches: dark,
    media: '(prefers-color-scheme: dark)',
    addEventListener: (_: string, listener: SchemeListener) => listeners.push(listener),
    removeEventListener: () => {},
  }
  vi.stubGlobal('matchMedia', vi.fn().mockReturnValue(query))
  return { flipTo: (next: boolean) => listeners.forEach((l) => l({ matches: next })) }
}

const faviconHref = () => document.getElementById('veyq-favicon')?.getAttribute('href') ?? null

describe('systemTheme', () => {
  beforeEach(() => {
    vi.unstubAllGlobals()
    document.documentElement.className = ''
    document.getElementById('veyq-favicon')?.remove()
  })

  it('marks the document dark when the OS prefers dark', () => {
    stubColorScheme(true)

    initializeTheme()

    expect(document.documentElement.classList.contains('veyq-dark')).toBe(true)
  })

  it('leaves the document light when the OS prefers light', () => {
    stubColorScheme(false)

    initializeTheme()

    expect(document.documentElement.classList.contains('veyq-dark')).toBe(false)
  })

  it('keeps following the OS after boot', () => {
    const scheme = stubColorScheme(false)
    initializeTheme()

    scheme.flipTo(true)

    expect(document.documentElement.classList.contains('veyq-dark')).toBe(true)
  })

  it('serves a different favicon per scheme', () => {
    stubColorScheme(true)
    initializeTheme()
    const dark = faviconHref()

    stubColorScheme(false)
    initializeTheme()

    expect(dark).toBeTruthy()
    expect(faviconHref()).toBeTruthy()
    expect(faviconHref()).not.toBe(dark)
  })
})
