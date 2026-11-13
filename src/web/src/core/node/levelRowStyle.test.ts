// The look of the level list's items is CSS, which a unit test cannot render - so what the owner's design rests on (2026-11-04:
// two groups, the folders and the contents, each list one surface of rows led by the compact header's 16:9 thumbnail; in the
// grid the folders compact tiles, every other node a card with its cover flush at the top and quiet actions over the cover's
// corner, shown on hover and focus, always without hover; the groups' quiet headings) and the phone-width
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

  it('a tile has a 16:9 cover, the tiles narrower than before (210 px, was 280-340 px), no shadow', () => {
    expect(rule('.veyq-level-item.is-grid .veyq-level-item-visual')).toMatch(/aspect-ratio:\s*16 \/ 9/)
    expect(rule('.veyq-level-list')).toMatch(/repeat\(auto-fill, minmax\(min\(100%, 210px\), 1fr\)\)/)
    expect(css).not.toMatch(/\.veyq-level-item\.is-grid[^{]*\{[^}]*box-shadow/)
  })

  it('every tile but a folder\'s is a card with the list\'s surface, frame and radius: the cover flush at its top, the text inside', () => {
    const card = rule('.veyq-level-item.is-grid:not(.is-compact)')
    const list = rule('.veyq-level-list.is-list')
    for (const prop of ['border', 'border-radius', 'background']) {
      const of = (decl: string) => new RegExp(`(?:^|[\\s;])${prop}:\\s*([^;]+);`).exec(decl)?.[1]
      expect(of(card), `the card's ${prop} is the list's`).toBe(of(list))
      expect(of(card), prop).toBeDefined()
    }
    // the cover: no frame of its own, its top corners the card's (one pixel inside its border), its bottom square
    const cover = rule('.veyq-level-item.is-grid:not(.is-compact) .veyq-level-item-visual')
    expect(cover).toMatch(/border:\s*0/)
    expect(cover).toMatch(/border-radius:\s*calc\(var\(--app-radius\) - 1px\) calc\(var\(--app-radius\) - 1px\) 0 0/)
    // the name and meta line inside the card: padded on both sides and below, nothing above (the cover is the top)
    expect(rule('.veyq-level-item.is-grid .veyq-level-item-text')).toMatch(/padding:\s*0 0\.75rem 0\.75rem/)
    // selected, a tile - compact or card - takes the accent frame
    expect(rule('.veyq-level-item.is-grid.is-selected')).toMatch(/border-color:\s*var\(--app-accent\)/)
  })

  it('the groups: each its own list (no trick placing the first content tile), a small gap between, a quiet heading', () => {
    expect(css).not.toMatch(/grid-column-start/)
    expect(rule('.veyq-level-item.is-compact')).toMatch(/border:\s*1px solid var\(--app-line\)/)
    expect(rem(rule('.veyq-level-groups'), 'gap')).toBeLessThanOrEqual(1.5)
    const heading = rule('.veyq-level-group-heading')
    expect(heading).toMatch(/color:\s*var\(--app-muted\)/)
    expect(rem(heading, 'font-size')).toBeLessThan(1)
    expect(heading).toMatch(/margin:\s*0/)
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

  it('a tile draws its heart last, in the corner, pressed or not; a compact tile at rest keeps it off the name', () => {
    const hover = media('(hover: hover)')
    // every tile, card and compact: drawn after the ⋯ (whose transparent box would otherwise sit between it and the corner),
    // keyed on the action so a click never moves it from under the pointer
    expect(rule('.veyq-level-item.is-grid .veyq-level-item-actions > .veyq-button[data-action="favorite"]')).toMatch(/order:\s*1/)
    expect(css).not.toMatch(/\.is-pressed\s*\{[^}]*order:/)
    // the other buttons keep their box (transparent at rest, never no width): a button with no box is hidden to a test
    // driver's "visible" - lane L1's export-import spec timed out on the ⋯ when it was folded to width 0
    expect(css).not.toMatch(/\.veyq-level-item[^{]*\.veyq-level-item-actions[^{]*\{[^}]*(?:^|[\s;])width:\s*0/m)
    // the room the heart takes (an icon button, 2.55rem, plus its inset) is kept free of the name
    expect(rule('.veyq-level-item.is-compact:has(.veyq-level-item-actions > .veyq-button.is-pressed) .veyq-level-item-main', hover))
      .toMatch(/padding-inline-end:\s*2\.9rem/)
  })

  it('the meta line wraps to two lines instead of one clipped line (details are real text, long ones must not overflow)', () => {
    const meta = rule('.veyq-level-item-meta')
    expect(meta).toMatch(/-webkit-line-clamp:\s*2/)
    expect(meta).toMatch(/overflow-wrap:\s*anywhere/)
    expect(meta).not.toMatch(/white-space:\s*nowrap/)
  })

  it('draws colours from tokens only (the dark theme needs no rule of its own)', () => {
    for (const sel of [
      '.veyq-level-item', '.veyq-level-item-visual', '.veyq-level-item-meta', '.veyq-level-list.is-list', '.veyq-level-item.is-compact',
      '.veyq-level-item.is-grid:not(.is-compact)', '.veyq-level-group-heading',
    ]) {
      expect(rule(sel), sel).not.toMatch(/#[0-9a-fA-F]{3,8}\b|rgba?\(/)
    }
  })

  it('a phone gets a single column of tiles', () => {
    expect(css).toMatch(/@media \(max-width: 520px\) \{\s*\.veyq-level-list \{\s*grid-template-columns: minmax\(0, 1fr\);/)
  })
})
