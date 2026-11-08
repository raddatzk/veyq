// The sidebar's Groups tile (`.sidebar-scope-icon[data-icon='users']`) is a shared scope, so it takes the warning roles
// the styleguide gives group/shared badges, in light and dark alike; danger is for destructive actions and errors only.
// Inbox #832: light painted it danger while dark painted it warning. Both are CSS a unit test cannot render, so the two
// rules are read from source here and their colour roles compared.
import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'

const read = (name: string) =>
  readFileSync(new URL(`../../styles/${name}`, import.meta.url), 'utf8').replace(/\/\*[\s\S]*?\*\//g, '')

/** The `background` and `color` of the last rule in `css` whose selector list names `selector` exactly. */
function roles(css: string, selector: string): { background?: string; color?: string } {
  let found: { background?: string; color?: string } | undefined
  for (const m of css.matchAll(/([^{}]+)\{([^{}]*)\}/g)) {
    if (!m[1].split(',').map((s) => s.trim()).includes(selector)) continue
    const decls = new Map(
      m[2].split(';').filter((d) => d.includes(':')).map((d) => [d.slice(0, d.indexOf(':')).trim(), d.slice(d.indexOf(':') + 1).trim()]),
    )
    found = { background: decls.get('background'), color: decls.get('color') }
  }
  if (!found) throw new Error(`no rule names ${selector}`)
  return found
}

const GROUPS = ".sidebar-scope-icon[data-icon='users']"
const WARNING = { background: 'var(--app-warning-soft)', color: 'var(--app-warning-text)' }

describe('the sidebar Groups tile colour roles', () => {
  it('is warning in light', () => {
    expect(roles(read('layout.css'), GROUPS)).toEqual(WARNING)
  })

  it('is warning in dark, where every scope tile is first reset to accent', () => {
    expect(roles(read('dark.css'), GROUPS)).toEqual(WARNING)
  })
})
