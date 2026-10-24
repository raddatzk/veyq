// The node header's layout is CSS, which a unit test cannot render - so the rules it rests on are pinned at the source (as
// `levelRowStyle.test.ts` does for the level's items), among them the ones the component's measuring reads (ADR 0110): the
// two registered lengths the layout answer reads, the column gap no layout changes, the compact title row that draws no
// box, and the wide name's one line whose `scrollWidth` is its natural width. A real browser pass is still the proof of the
// look; this stops a rule being dropped unseen.
import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'

const css = readFileSync(new URL('../../styles/ui.css', import.meta.url), 'utf8')

/** The declarations of the rule whose selector list is exactly `selector`, inside `within`. */
function rule(selector: string, within: string): string {
  const re = new RegExp(`(?:^|\\})\\s*${selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\s*\\{([^}]*)\\}`, 'm')
  const m = re.exec(within)
  if (!m) throw new Error(`no rule for ${selector}`)
  return m[1]
}

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

  it('core\'s leading visuals size themselves from their wide width; the cover is 64 px wide when compact', () => {
    expect(rule('.veyq-node-header-cover', css)).toMatch(/--veyq-node-header-leading-wide:\s*5\.5rem;[^}]*width:\s*var\(--veyq-node-header-leading-wide\)/)
    expect(rule('.veyq-node-header .veyq-node-header-icon', css)).toMatch(/--veyq-node-header-leading-wide:\s*42px;[^}]*width:\s*var\(--veyq-node-header-leading-wide\)/)
    expect(rule('.veyq-node-header.is-compact .veyq-node-header-cover', css)).toMatch(/width:\s*64px/)
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
    const title = rule('.veyq-node-header.is-compact .veyq-node-header-title', css)
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
