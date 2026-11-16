// The start screen of index.html (ADR 0136 decision 1) and its sheet `styles/start.css`.
//   - It paints under the built CSP (cc.security.csp): no `style="…"` attribute, no inline script, no <style>.
//   - Before the app applied the stored theme it follows the OS, with base.css's dark values for the roles it paints
//     with, spelled again in start.css (a sheet cannot read another rule's custom properties) — held equal here.
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

const read = (path: string) => readFileSync(new URL(path, import.meta.url), 'utf8').replace(/\/\*[\s\S]*?\*\//g, '')
const INDEX = readFileSync(new URL('../../index.html', import.meta.url), 'utf8').replace(/<!--[\s\S]*?-->/g, '')
const START = read('../styles/start.css')
const BASE = read('../styles/base.css')

/** The custom properties a block declares, `--name` → value. */
function declarations(block: string): Map<string, string> {
  return new Map([...block.matchAll(/(--[\w-]+)\s*:\s*([^;]+);/g)].map((m) => [m[1], m[2].trim()]))
}

describe('the start screen', () => {
  it('carries no style attribute, no <style> and no inline script', () => {
    expect(INDEX).not.toMatch(/\sstyle\s*=/)
    expect(INDEX).not.toMatch(/<style\b/)
    const scripts = [...INDEX.matchAll(/<script\b([^>]*)>([\s\S]*?)<\/script>/g)]
    expect(scripts.length).toBe(2)
    for (const [, attributes, body] of scripts) {
      expect(attributes).toMatch(/\bsrc="/)
      expect(body.trim()).toBe('')
    }
  })

  it('loads the watchdog async, before the app’s entry, and leaves it out of the app’s bundle', () => {
    const watchdog = INDEX.indexOf('/boot-watchdog.js')
    expect(watchdog).toBeGreaterThan(0)
    expect(watchdog).toBeLessThan(INDEX.indexOf('/src/main.ts'))
    expect(INDEX).toMatch(/<script type="module" async src="\/boot-watchdog\.js" vite-ignore><\/script>/)
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
