// The look of the level list's items is CSS, which a unit test cannot render - so what the owner's design rests on (2026-11-04:
// the list one surface of rows led by the compact header's 16:9 thumbnail; the grid's folders a compact group above flat
// tiles whose quiet actions sit over the cover's corner, shown on hover and focus, always without hover) and the phone-width
// reasoning are pinned at the source: sizes in rem (they follow the reader's font size), tokens for every colour, the
// narrow-width fallbacks. A real browser pass is still the proof of the look; this stops the numbers drifting back unseen.
import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'

const css = readFileSync(new URL('../../styles/ui.css', import.meta.url), 'utf8')

/** The declarations of the FIRST rule whose selector list is exactly `selector` (it starts after a rule, a comment or a block's
 *  opening brace - never inside a longer selector list). */
function rule(selector: string, within = css): string {
  const re = new RegExp(`(?:^|[{}]|\\*/)\\s*${selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\s*\\{([^}]*)\\}`)
  const m = re.exec(within)
  if (!m) throw new Error(`no rule for ${selector}`)
  return m[1]
}
const rem = (decl: string, prop: string): number => {
  const m = new RegExp(`(?:^|[\\s;])${prop}:\\s*([\\d.]+)rem`).exec(decl)
  if (!m) throw new Error(`no ${prop} in rem`)
  return Number(m[1])
}
/** The body of the `@media <query>` block that starts with `selector`. */
function media(query: string): string {
  const start = css.indexOf(`@media ${query} {`)
  expect(start, query).toBeGreaterThan(-1)
  return css.slice(start, css.indexOf('\n}\n', start))
}
const ROW = '.veyq-level-item.is-list,\n.veyq-level-item.is-compact'

describe('level item styles', () => {
  it('a row and a folder\'s compact tile lead with the compact header\'s 16:9 thumbnail, 4 x 2.25 rem, on every width', () => {
    const visual = rule('.veyq-level-item.is-list .veyq-level-item-visual,\n.veyq-level-item.is-compact .veyq-level-item-visual')
    expect(rem(visual, 'width')).toBe(4)
    expect(rem(visual, 'height')).toBe(2.25)
    expect(visual).toMatch(/aspect-ratio:\s*16 \/ 9/)
    // the visual column is the thumbnail's width, so every icon and cover of a mixed list stays in one flush column
    expect(rule('.veyq-level-item.is-list .veyq-level-item-main,\n.veyq-level-item.is-compact .veyq-level-item-main')).toMatch(/grid-template-columns:\s*4rem minmax\(0, 1fr\)/)
    // no wider variant from some width up (the former 6.5 x 4 rem one)
    expect(css).not.toMatch(/@media \(min-width: 521px\)[^@]*\.veyq-level-item\.is-list/)
  })

  it('a row is about half the former height: a slim padding, no frame or shadow of its own', () => {
    const row = rule(ROW)
    const pad = /padding:\s*([\d.]+)rem/.exec(row)
    expect(Number(pad?.[1])).toBeLessThanOrEqual(0.35)
    expect(rule('.veyq-level-item')).not.toMatch(/box-shadow|border:/)
  })

  it('the list is ONE surface in one frame, its rows parted by hairlines', () => {
    const list = rule('.veyq-level-list.is-list')
    expect(list).toMatch(/gap:\s*0/)
    expect(list).toMatch(/border:\s*1px solid var\(--app-line\)/)
    expect(list).toMatch(/background:\s*var\(--app-surface\)/)
    expect(rule('.veyq-level-item.is-list + .veyq-level-item.is-list')).toMatch(/border-top:\s*1px solid var\(--app-line\)/)
  })

  it('a tile is flat with a 16:9 cover, the tiles narrower than before (210 px, was 280-340 px)', () => {
    expect(rule('.veyq-level-item.is-grid .veyq-level-item-visual')).toMatch(/aspect-ratio:\s*16 \/ 9/)
    expect(rule('.veyq-level-list')).toMatch(/repeat\(auto-fill, minmax\(min\(100%, 210px\), 1fr\)\)/)
    expect(css).not.toMatch(/\.veyq-level-item\.is-grid[^{]*\{[^}]*box-shadow/)
  })

  it('the grid\'s folders are one group: the first other tile starts a new row', () => {
    expect(rule('.veyq-level-item.is-compact + .veyq-level-item:not(.is-compact)')).toMatch(/grid-column-start:\s*1/)
    expect(rule('.veyq-level-item.is-compact')).toMatch(/border:\s*1px solid var\(--app-line\)/)
  })

  it('a tile\'s actions sit over the cover\'s top-right corner, shown on hover and focus by opacity alone, always without hover', () => {
    const at = rule('.veyq-level-item.is-grid:not(.is-compact) .veyq-level-item-actions')
    expect(at).toMatch(/position:\s*absolute/)
    expect(at).toMatch(/top:/)
    expect(at).toMatch(/right:/)
    const hidden = rule('.veyq-level-item.is-grid .veyq-level-item-actions > .veyq-button')
    expect(hidden).toMatch(/opacity:\s*0/)
    // never taken out of the Tab order: no display:none or visibility on the actions anywhere
    expect(css).not.toMatch(/\.veyq-level-item[^{]*\.veyq-level-item-actions[^{]*\{[^}]*(display:\s*none|visibility:\s*hidden)/)
    const shown = rule([
      '.veyq-level-item.is-grid:hover .veyq-level-item-actions > .veyq-button',
      '.veyq-level-item.is-grid:focus-within .veyq-level-item-actions > .veyq-button',
      '.veyq-level-item.is-grid .veyq-level-item-actions > .veyq-button.is-pressed',
    ].join(',\n'))
    expect(shown).toMatch(/opacity:\s*1/)
    expect(rule('.veyq-level-item.is-grid .veyq-level-item-actions > .veyq-button', media('(hover: none)'))).toMatch(/opacity:\s*1/)
  })

  it('a folder\'s compact tile lays its actions over its row\'s end where a pointer hovers, so its name keeps the width', () => {
    const hover = media('(hover: hover)')
    expect(rule('.veyq-level-item.is-compact .veyq-level-item-actions', hover)).toMatch(/position:\s*absolute/)
    expect(rule('.veyq-level-item.is-grid.is-compact', hover)).toMatch(/grid-template-columns:\s*minmax\(0, 1fr\);/)
    // where nothing hovers they stay in the row (no overlay over the name), shown
    expect(media('(hover: none)')).not.toMatch(/position:\s*absolute/)
  })

  it('the meta line wraps to two lines instead of one clipped line (details are real text, long ones must not overflow)', () => {
    const meta = rule('.veyq-level-item-meta')
    expect(meta).toMatch(/-webkit-line-clamp:\s*2/)
    expect(meta).toMatch(/overflow-wrap:\s*anywhere/)
    expect(meta).not.toMatch(/white-space:\s*nowrap/)
  })

  it('draws colours from tokens only (the dark theme needs no rule of its own)', () => {
    for (const sel of ['.veyq-level-item', '.veyq-level-item-visual', '.veyq-level-item-meta', '.veyq-level-list.is-list', '.veyq-level-item.is-compact']) {
      expect(rule(sel), sel).not.toMatch(/#[0-9a-fA-F]{3,8}\b|rgba?\(/)
    }
  })

  it('a phone gets a single column of tiles', () => {
    expect(css).toMatch(/@media \(max-width: 520px\) \{\s*\.veyq-level-list \{\s*grid-template-columns: minmax\(0, 1fr\);/)
  })
})
