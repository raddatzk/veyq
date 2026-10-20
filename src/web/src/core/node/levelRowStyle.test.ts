// The look of the level list's items is CSS, which a unit test cannot render - so what the owner's design (2026-10-13: taller
// rows, a larger cover, a proper 16:9 tile) and the phone-width reasoning rest on is pinned at the source: sizes in rem (they
// follow the reader's font size), tokens for every colour, the narrow-width fallbacks. A real browser pass is still the proof of
// the look; this stops the numbers drifting back unseen.
import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'

const css = readFileSync(new URL('../../styles/ui.css', import.meta.url), 'utf8')

/** The declarations of the FIRST rule whose selector list is exactly `selector`, outside any @media. */
function rule(selector: string, within = css): string {
  const re = new RegExp(`(?:^|\\})\\s*${selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\s*\\{([^}]*)\\}`, 'm')
  const m = re.exec(within)
  if (!m) throw new Error(`no rule for ${selector}`)
  return m[1]
}
const rem = (decl: string, prop: string): number => {
  const m = new RegExp(`${prop}:\\s*([\\d.]+)rem`).exec(decl)
  if (!m) throw new Error(`no ${prop} in rem`)
  return Number(m[1])
}
/** The body of the `@media (min-width: 521px)` block. */
const wide = (() => {
  const start = css.indexOf('@media (min-width: 521px)')
  expect(start).toBeGreaterThan(-1)
  return css.slice(start, css.indexOf('/* A tile', start))
})()

describe('level item styles', () => {
  it('a list row\'s visual is larger than the former 4rem x 2.5rem on every width, and larger again from 521 px', () => {
    const narrow = rule('.veyq-level-item.is-list .veyq-level-item-visual')
    expect(rem(narrow, 'width')).toBeGreaterThan(4)
    expect(rem(narrow, 'height')).toBeGreaterThan(2.5)
    const w = rule('.veyq-level-item.is-list .veyq-level-item-visual', wide)
    expect(rem(w, 'width')).toBeGreaterThan(rem(narrow, 'width'))
    expect(rem(w, 'height')).toBeGreaterThan(rem(narrow, 'height'))
  })

  it('the visual column and the visual are the same width, so every icon and cover stays in one flush column', () => {
    expect(/grid-template-columns:\s*4\.5rem minmax\(0, 1fr\)/.test(rule('.veyq-level-item.is-list .veyq-level-item-main'))).toBe(true)
    expect(/grid-template-columns:\s*6\.5rem minmax\(0, 1fr\)/.test(rule('.veyq-level-item.is-list .veyq-level-item-main', wide))).toBe(true)
    expect(rem(rule('.veyq-level-item.is-list .veyq-level-item-visual', wide), 'width')).toBe(6.5)
  })

  it('a tile is a proper 16:9 cover on top and its columns are wider than before (240-260 px)', () => {
    expect(rule('.veyq-level-item.is-grid .veyq-level-item-visual')).toMatch(/aspect-ratio:\s*16 \/ 9/)
    expect(rule('.veyq-level-list')).toMatch(/minmax\(min\(100%, 280px\), 340px\)/)
  })

  it('the meta line wraps to two lines instead of one clipped line (details are real text, long ones must not overflow)', () => {
    const meta = rule('.veyq-level-item-meta')
    expect(meta).toMatch(/-webkit-line-clamp:\s*2/)
    expect(meta).toMatch(/overflow-wrap:\s*anywhere/)
    expect(meta).not.toMatch(/white-space:\s*nowrap/)
  })

  it('draws colours from tokens only (the dark theme needs no rule of its own)', () => {
    for (const sel of ['.veyq-level-item', '.veyq-level-item-visual', '.veyq-level-item-meta']) {
      expect(rule(sel), sel).not.toMatch(/#[0-9a-fA-F]{3,8}\b|rgba?\(/)
    }
  })

  it('a phone gets a single column of tiles', () => {
    expect(css).toMatch(/@media \(max-width: 520px\) \{\s*\.veyq-level-list \{\s*grid-template-columns: minmax\(0, 1fr\);/)
  })
})
