// The node header's narrow-width layout is CSS, which a unit test cannot render - so the rule a stacked header's leading
// visual rests on is pinned at the source (as `levelRowStyle.test.ts` does for the level's items). A real browser pass is
// still the proof of the look; this stops the rule being dropped unseen.
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

  it('stacks a header with a leading visual on a narrow screen', () => {
    expect(rule('.veyq-node-header.has-visual', narrow)).toMatch(/flex-direction:\s*column/)
  })

  it('stacked, the leading box spans the header, so a visual sized in % of it keeps its size (inbox #525)', () => {
    // Shrink-wrapped by the column's `align-items: flex-start`, the box was only as wide as its content - the Memory
    // cover's absolute `<img>` - and the cover button collapsed to 3.5 x 2 px at 375 px.
    expect(rule('.veyq-node-header.has-visual .veyq-node-header-leading', narrow)).toMatch(/align-self:\s*stretch/)
  })
})
