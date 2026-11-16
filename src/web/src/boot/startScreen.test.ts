// @vitest-environment happy-dom
// The start screen of index.html (ADR 0136 decision 1) and its sheet `styles/start.css`.
//   - It paints under the built CSP (cc.security.csp): no `style="…"` attribute, no inline script, no <style>.
//   - Before the app applied the stored theme it follows the OS, with base.css's dark values for the roles it paints
//     with, spelled again in start.css (a sheet cannot read another rule's custom properties) — held equal here.
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'

// Paths through node:path: under happy-dom `URL` is the DOM's, which node:fs does not take.
const here = dirname(fileURLToPath(import.meta.url))
const read = (path: string) => readFileSync(resolve(here, path), 'utf8').replace(/\/\*[\s\S]*?\*\//g, '')
// Parsed, not matched: the parser drops comments and reads tags and attributes in any case, which a regex over the
// text would have to get right by hand.
const INDEX = new DOMParser().parseFromString(readFileSync(resolve(here, '../../index.html'), 'utf8'), 'text/html')
const SCRIPTS = [...INDEX.querySelectorAll('script')]
const START = read('../styles/start.css')
const BASE = read('../styles/base.css')

/** The custom properties a block declares, `--name` → value. */
function declarations(block: string): Map<string, string> {
  return new Map([...block.matchAll(/(--[\w-]+)\s*:\s*([^;]+);/g)].map((m) => [m[1], m[2].trim()]))
}

describe('the start screen', () => {
  it('carries no style attribute, no <style> and no inline script', () => {
    expect(INDEX.querySelectorAll('[style]')).toHaveLength(0)
    expect(INDEX.querySelectorAll('style')).toHaveLength(0)
    expect(SCRIPTS).toHaveLength(2)
    for (const script of SCRIPTS) {
      expect(script.getAttribute('src')).toBeTruthy()
      expect(script.textContent?.trim()).toBe('')
    }
  })

  it('loads the watchdog async, before the app’s entry, and leaves it out of the app’s bundle', () => {
    const watchdog = SCRIPTS.findIndex((script) => script.getAttribute('src') === '/boot-watchdog.js')
    const app = SCRIPTS.findIndex((script) => script.getAttribute('src') === '/src/main.ts')
    expect(watchdog).toBeGreaterThanOrEqual(0)
    expect(app).toBeGreaterThan(watchdog)
    const tag = SCRIPTS[watchdog]
    expect(tag.getAttribute('type')).toBe('module')
    expect(tag.hasAttribute('async')).toBe(true)
    expect(tag.hasAttribute('vite-ignore')).toBe(true)
  })

  it('in the OS’s dark mode uses base.css’s dark values', () => {
    const dark = /:root\.veyq-dark\s*\{([^}]*)\}/.exec(BASE)?.[1]
    const start = /@media \(prefers-color-scheme: dark\)\s*\{\s*:root:not\(\[data-theme\]\) \.veyq-start\s*\{([^}]*)\}/.exec(START)?.[1]
    expect(dark).toBeDefined()
    expect(start).toBeDefined()
    const theirs = declarations(dark!)
    const ours = declarations(start!)
    expect(ours.size).toBeGreaterThan(5)
    for (const [name, value] of ours) {
      // base.css's dark `--app-on-fill` is `var(--app-backdrop-strong)`: resolve it once.
      const expected = theirs.get(name)?.replace(/^var\((--[\w-]+)\)$/, (_, ref: string) => theirs.get(ref) ?? ref)
      expect([name, value]).toEqual([name, expected])
    }
  })

  it('stops its spinner where the person asked for reduced motion', () => {
    expect(START).toMatch(/@media \(prefers-reduced-motion: reduce\)\s*\{[^}]*\.veyq-start \.veyq-spinner__spin[^{]*\{\s*animation: none;/)
  })
})
