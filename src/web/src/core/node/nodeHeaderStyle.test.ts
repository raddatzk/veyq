// The node header's layout is CSS, which a unit test cannot render - so the rules it rests on are pinned at the source (as
// `levelRowStyle.test.ts` does for the level's items), among them the ones the component's measuring reads (ADR 0110): the
// two registered lengths the layout answer reads, the column gap no layout changes, the compact title row that draws no
// box, and the wide name's one line whose `scrollWidth` is its natural width. A real browser pass is still the proof of the
// look; this stops a rule being dropped unseen.
import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { headerRowCounts, type NodeHeaderFact } from './nodeHeader'

const css = readFileSync(new URL('../../styles/ui.css', import.meta.url), 'utf8')

/** The declarations of the rule whose selector list is exactly `selector`, inside `within`. */
function rule(selector: string, within: string): string {
  const re = new RegExp(`(?:^|\\})\\s*${selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\s*\\{([^}]*)\\}`, 'm')
  const m = re.exec(within)
  if (!m) throw new Error(`no rule for ${selector}`)
  return m[1]
}

/** The custom properties a rule declares (a value may span lines: the visual's height is one `calc`). */
function customProperties(declarations: string): Record<string, string> {
  const out: Record<string, string> = {}
  for (const d of declarations.split(';')) {
    const m = /^\s*(--[\w-]+)\s*:\s*([\s\S]+?)\s*$/.exec(d)
    if (m) out[m[1]] = m[2].replace(/\s+/g, ' ')
  }
  return out
}

/** A length in px as a browser computes it at a 16 px root: every `var()` resolved from `vars` (or its fallback), `rem` and
 *  `px` to numbers, `calc` to the arithmetic it is. Anything else is refused, so the sheet cannot hide a unit this cannot read. */
function evaluate(expr: string, vars: Record<string, string>): number {
  let e = expr
  for (let i = 0; i < 10 && e.includes('var('); i++) {
    e = e.replace(/var\((--[\w-]+)(?:,\s*([^()]+))?\)/g, (_, name: string, fallback?: string) => {
      const v = vars[name] ?? fallback
      if (v === undefined) throw new Error(`unset ${name}`)
      return `(${v})`
    })
  }
  e = e.replace(/calc\(/g, '(').replace(/(\d*\.?\d+)rem/g, (_, n: string) => String(Number(n) * 16)).replace(/(\d*\.?\d+)px/g, '$1')
  if (!/^[\d.\s+\-*/()]+$/.test(e)) throw new Error(`not a length: ${e}`)
  return Function(`return (${e})`)() as number
}

/** The leading visual of a header that draws `subtitle` and `facts`: its height, a cover's wide width and the icon tile's, from
 *  the sheet's own rules and the counts the component sets (`headerRowCounts`). */
function visualOf(subtitle: boolean, facts: readonly NodeHeaderFact[]) {
  const c = headerRowCounts(subtitle, facts)
  const vars: Record<string, string> = {
    ...customProperties(rule('.veyq-node-header', css)),
    '--veyq-node-header-subtitle-rows': String(c.subtitle),
    '--veyq-node-header-chip-rows': String(c.chips),
    '--veyq-node-header-people-rows': String(c.people),
    '--veyq-node-header-text-rows': String(c.text),
  }
  const height = evaluate('var(--veyq-node-header-visual-height)', vars)
  const cover = evaluate(customProperties(rule('.veyq-node-header-cover', css))['--veyq-node-header-leading-wide'], vars)
  const tile = evaluate(customProperties(rule('.veyq-node-header .veyq-node-header-icon', css))['--veyq-node-header-leading-wide'], vars)
  return { height: Math.round(height * 10) / 10, cover: Math.round(cover * 10) / 10, tile: Math.round(tile * 10) / 10 }
}
const DATE: NodeHeaderFact = { kind: 'date', label: '2026' }
const PLACE: NodeHeaderFact = { kind: 'place', label: 'Rome' }
const PEOPLE: NodeHeaderFact = { kind: 'people', label: 'Participants', people: [] }
const TEXT: NodeHeaderFact = { kind: 'text', text: 'In EUR' }

describe('node header styles', () => {
  it('no viewport query places the header: the component\'s `is-compact` answer does (ADR 0110 decision 8)', () => {
    for (const m of css.matchAll(/@media[^{]*\{/g)) {
      const body = css.slice(m.index!, css.indexOf('}\n}', m.index!))
      expect(body, m[0]).not.toMatch(/\.veyq-node-header(?![-\w])/)
    }
  })

  it('never stacks: no rule turns the header into a column, in either layout (ADR 0110 decision 1)', () => {
    expect(css).not.toMatch(/\.veyq-node-header(?![-\w])[^{]*\{[^}]*flex-direction:\s*column/)
  })

  it('registers the two lengths the layout answer reads, so the computed style answers in px', () => {
    for (const name of ['--veyq-node-header-leading-wide', '--veyq-node-header-name-min']) {
      const reg = rule(`@property ${name}`, css)
      expect(reg, name).toMatch(/syntax:\s*'<length>'/)
      expect(reg, name).toMatch(/inherits:\s*false/)
    }
    expect(rule('.veyq-node-header', css)).toMatch(/--veyq-node-header-name-min:\s*12rem/)
  })

  it('the leading visual is as tall as the rows core draws beside it: computed per row set, never measured (owner 2026-11-04)', () => {
    // A Memory's four rows (the name, the count, date and place, the people): a cover near the former fixed 244 px.
    expect(visualOf(true, [DATE, PLACE, PEOPLE])).toEqual({ height: 137.2, cover: 243.9, tile: 137.2 })
    // A folder's name alone: a tile as tall as the name row (and a cover frame 16:9 at that height).
    expect(visualOf(false, [])).toEqual({ height: 42, cover: 74.7, tile: 42 })
    // A Pot (the name, its currency) and a file (the name, who uploaded it): each row its own size.
    expect(visualOf(true, []).height).toBe(64.4)
    expect(visualOf(false, [PEOPLE]).height).toBe(86.4)
    expect(visualOf(false, [TEXT]).height).toBe(64.4)
    // Consecutive chips are one row; a chip after a people row starts another.
    expect(visualOf(false, [DATE, PLACE]).height).toBe(visualOf(false, [DATE]).height)
    expect(visualOf(false, [DATE, PEOPLE, PLACE]).height).toBe(visualOf(false, [DATE, PLACE, PEOPLE]).height + visualOf(false, [DATE]).height - 42)
  })

  it('each row is its row\'s block size, so the sum is exact while nothing wraps; the body\'s gap is the sum\'s', () => {
    expect(rule('.veyq-node-header-titlerow', css)).toMatch(/min-block-size:\s*var\(--veyq-node-header-row-title\)/)
    expect(rule('.veyq-node-header-subtitle', css)).toMatch(/min-block-size:\s*var\(--veyq-node-header-row-subtitle\)/)
    expect(rule('.veyq-node-header-facts.is-chips', css)).toMatch(/min-block-size:\s*var\(--veyq-node-header-row-chips\)/)
    expect(rule('.veyq-node-header-facts.is-people', css)).toMatch(/min-block-size:\s*var\(--veyq-node-header-row-people\)/)
    expect(rule('.veyq-node-header-facts.is-text', css)).toMatch(/min-block-size:\s*var\(--veyq-node-header-row-text\)/)
    expect(rule('.veyq-node-header-body', css)).toMatch(/gap:\s*var\(--veyq-node-header-row-gap\)/)
    // A chip's line box is set, not the font's `normal` (which drew a 27 px row over its 26 px token in a real browser).
    expect(rule('.veyq-node-header-chip', css)).toMatch(/line-height:\s*1\.25/)
    expect(rule('.veyq-node-header-subtitle', css)).toMatch(/line-height:\s*var\(--veyq-node-header-row-subtitle\)/)
    // The content is centred against the visual (a folder's one-line name in the middle of its tile).
    expect(rule('.veyq-node-header', css)).toMatch(/align-items:\s*center/)
  })

  it('a cover is 16:9 and the icon tile a square, each sized from the wide width core reads', () => {
    // Without the property the layout answer would read 0 and put the cover beside a name squeezed below its minimum.
    const cover = rule('.veyq-node-header-cover', css)
    expect(cover).toMatch(/--veyq-node-header-leading-wide:\s*calc\(var\(--veyq-node-header-visual-height\) \* 16 \/ 9\);[^}]*width:\s*var\(--veyq-node-header-leading-wide\)/)
    expect(cover).toMatch(/aspect-ratio:\s*16 \/ 9/)
    const icon = rule('.veyq-node-header .veyq-node-header-icon', css)
    expect(icon).toMatch(/--veyq-node-header-leading-wide:\s*var\(--veyq-node-header-visual-height\);[^}]*width:\s*var\(--veyq-node-header-leading-wide\)/)
    expect(icon).toMatch(/height:\s*var\(--veyq-node-header-leading-wide\)/)
    // The empty frame of a kind that takes a cover: the kind's icon in its middle, a line around it.
    const empty = rule('.veyq-node-header-cover.is-empty', css)
    expect(empty).toMatch(/place-items:\s*center/)
    expect(empty).toMatch(/border:\s*1px solid var\(--app-line\)/)
    // No visual sizes itself by a query of its own: the header's `is-compact` answer is the only switch.
    expect(css).not.toMatch(/@container[^{]*\{[^}]*\.veyq-node-header/)
  })

  it('the name is one size, cover or not: 2rem wide, 1.5rem compact', () => {
    expect(rule('.veyq-node-header-title', css)).toMatch(/font-size:\s*2rem/)
    expect(rule('.veyq-node-header.is-compact .veyq-node-header-title', css)).toMatch(/font-size:\s*1\.5rem/)
    expect(css).not.toMatch(/has-visual/)
  })

  it('the cover button\'s hint shows on hover, on keyboard focus and on an empty wide frame three rows tall; its words only there', () => {
    const shown = rule(
      '.veyq-node-header-visual-button:hover .veyq-node-header-cover-hint,\n.veyq-node-header-visual-button:focus-visible .veyq-node-header-cover-hint,\n.veyq-node-header:not(.is-compact) .veyq-node-header-visual-button.is-empty.is-roomy .veyq-node-header-cover-hint',
      css,
    )
    expect(shown).toMatch(/opacity:\s*1/)
    expect(rule('.veyq-node-header-cover-hint', css)).toMatch(/opacity:\s*0/)
    // a compact header and a lower frame (a folder's name alone): the glyph over the whole frame, no words
    const glyphOnly = '.veyq-node-header.is-compact .veyq-node-header-cover-hint,\n.veyq-node-header-visual-button:not(.is-roomy) .veyq-node-header-cover-hint'
    expect(rule(glyphOnly, css)).toMatch(/inset-block-start:\s*0/)
    expect(rule('.veyq-node-header.is-compact .veyq-node-header-cover-hint-label,\n.veyq-node-header-visual-button:not(.is-roomy) .veyq-node-header-cover-hint-label', css)).toMatch(/display:\s*none/)
  })

  it('on a folder level the header\'s action buttons and the toolbar\'s controls are one height', () => {
    expect(rule('.veyq-level-head', css)).toMatch(/--veyq-level-control-height:\s*2\.25rem/)
    expect(rule('.veyq-level-head .veyq-node-header-actions .veyq-button', css)).toMatch(/height:\s*var\(--veyq-level-control-height\)/)
    expect(rule('.veyq-level-toolbar > .veyq-button', css)).toMatch(/height:\s*var\(--veyq-level-control-height\)/)
    const toggle = readFileSync(new URL('./ViewModeToggle.vue', import.meta.url), 'utf8')
    expect(toggle).toMatch(/height:\s*var\(--veyq-level-control-height, auto\)/)
  })

  it('core\'s page is the inline-size container an application\'s banner answers to', () => {
    expect(rule('.veyq-node-page', css)).toMatch(/container:\s*veyq-node-page \/ inline-size/)
  })

  it('compact, the cover is 64 px wide and the icon tile 42 px', () => {
    expect(rule('.veyq-node-header.is-compact .veyq-node-header-cover', css)).toMatch(/width:\s*64px/)
    const icon = rule('.veyq-node-header.is-compact .veyq-node-header-icon', css)
    expect(icon).toMatch(/width:\s*42px/)
    expect(icon).toMatch(/height:\s*42px/)
  })

  it('wide, the name is one line with an ellipsis at its natural width; the title row does not wrap', () => {
    const title = rule('.veyq-node-header-title', css)
    expect(title).toMatch(/white-space:\s*nowrap/)
    expect(title).toMatch(/text-overflow:\s*ellipsis/)
    expect(title).toMatch(/overflow:\s*hidden/)
    expect(title).toMatch(/flex:\s*0 1 auto/)
    expect(rule('.veyq-node-header-titlerow', css)).not.toMatch(/flex-wrap:\s*wrap/)
    expect(rule('.veyq-node-header-actions', css)).not.toMatch(/flex-wrap:\s*wrap/)
  })

  it('compact, the name is two lines at most, then an ellipsis', () => {
    const title = rule('.veyq-node-header.is-compact .veyq-node-header-title,\n.veyq-node-header.is-compact .veyq-node-header-rename', css)
    expect(title).toMatch(/display:\s*-webkit-box/)
    expect(title).toMatch(/white-space:\s*normal/)
    expect(title).toMatch(/-webkit-box-orient:\s*vertical/)
    expect(title).toMatch(/-webkit-line-clamp:\s*2/)
  })

  it('compact, the visual and the name share the first row and the actions get one of their own, the header wide', () => {
    expect(rule('.veyq-node-header.is-compact', css)).toMatch(/display:\s*grid/)
    expect(rule('.veyq-node-header.is-compact.has-leading', css)).toMatch(/grid-template-columns:\s*auto minmax\(0, 1fr\)/)
    // No box of their own: the title becomes the grid's second cell, and the component reads the actions' own box as the room.
    expect(rule('.veyq-node-header.is-compact .veyq-node-header-body,\n.veyq-node-header.is-compact .veyq-node-header-titlerow', css)).toMatch(/display:\s*contents/)
    expect(rule('.veyq-node-header.is-compact .veyq-node-header-actions,\n.veyq-node-header.is-compact .veyq-node-header-body > :not(.veyq-node-header-titlerow)', css)).toMatch(
      /grid-column:\s*1 \/ -1/,
    )
  })

  it('no compact rule moves a gap the layout answer reads (the header\'s and the title row\'s column gap): it would flip', () => {
    for (const m of css.matchAll(/\.veyq-node-header\.is-compact[^{]*\{([^}]*)\}/g)) {
      expect(m[1], m[0]).not.toMatch(/(^|[\s;])(gap|column-gap):/)
    }
  })
})
