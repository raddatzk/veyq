// The node header's layout is CSS, which a unit test cannot render - so the rules it rests on are pinned at the source (as
// `levelRowStyle.test.ts` does for the level's items), among them the two the component's measuring reads (ADR 0110): the
// title's flex-basis is its minimum beside the buttons, and on a phone the title row draws no box. A real browser pass is
// still the proof of the look; this stops a rule being dropped unseen.
import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'

const css = readFileSync(new URL('../../styles/ui.css', import.meta.url), 'utf8')

/** The body of the `@media` block that opens with `query`, braces counted. */
function mediaBlock(query: string): string {
  const start = css.indexOf(`@media ${query}`)
  expect(start, query).toBeGreaterThan(-1)
  const open = css.indexOf('{', start)
  let depth = 0
  for (let i = open; i < css.length; i++) {
    if (css[i] === '{') depth++
    else if (css[i] === '}' && --depth === 0) return css.slice(open + 1, i)
  }
  throw new Error(`unclosed ${query}`)
}

/** The declarations of the rule whose selector list is exactly `selector`, inside `within`. */
function rule(selector: string, within: string): string {
  const re = new RegExp(`(?:^|\\})\\s*${selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\s*\\{([^}]*)\\}`, 'm')
  const m = re.exec(within)
  if (!m) throw new Error(`no rule for ${selector}`)
  return m[1]
}

describe('node header styles', () => {
  const narrow = mediaBlock('(max-width: 540px)')

  it('never stacks: no rule turns the header into a column, at any width (ADR 0110 decision 1)', () => {
    expect(css).not.toMatch(/\.veyq-node-header(?![-\w])[^{]*\{[^}]*flex-direction:\s*column/)
  })

  it('the name is two lines at most, then an ellipsis, at every width', () => {
    const title = rule('.veyq-node-header-title', css)
    expect(title).toMatch(/display:\s*-webkit-box/)
    expect(title).toMatch(/-webkit-box-orient:\s*vertical/)
    expect(title).toMatch(/-webkit-line-clamp:\s*2/)
    expect(title).toMatch(/overflow:\s*hidden/)
  })

  it('wide, the title keeps a flex-basis (the minimum the component reserves for it) and the title row does not wrap', () => {
    expect(rule('.veyq-node-header-title', css)).toMatch(/flex:\s*1 1 12rem/)
    expect(rule('.veyq-node-header-titlerow', css)).not.toMatch(/flex-wrap:\s*wrap/)
    expect(rule('.veyq-node-header-actions', css)).not.toMatch(/flex-wrap:\s*wrap/)
  })

  it('on a phone the visual and the name share the first row and the actions get one of their own, the header wide', () => {
    expect(rule('.veyq-node-header', narrow)).toMatch(/display:\s*grid/)
    expect(rule('.veyq-node-header.has-leading', narrow)).toMatch(/grid-template-columns:\s*auto minmax\(0, 1fr\)/)
    // No box of their own: the title becomes the grid's second cell, and the component reads `contents` as "the actions'
    // own box is the room".
    expect(rule('.veyq-node-header-body,\n  .veyq-node-header-titlerow', narrow)).toMatch(/display:\s*contents/)
    expect(rule('.veyq-node-header-actions,\n  .veyq-node-header-body > :not(.veyq-node-header-titlerow)', narrow)).toMatch(/grid-column:\s*1 \/ -1/)
  })
})
