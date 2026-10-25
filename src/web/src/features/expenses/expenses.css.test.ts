// The Pot page at a phone's width (cr-expenses-ledger's browser pass, step 10). Pinned at the source, since a unit test
// renders no CSS: the page and its panels are grids, and a grid's implicit column is `auto`, sized to its widest child's
// min-content — the tab row (Entries … Settings, unwrapped) is about 490 px — so at 375 px the whole page, header and
// rows included, was that wide and cut off by the shell's `overflow-x: clip`: the People and Settings tabs, the header's
// actions and every amount out of reach. One column of `minmax(0, 1fr)` lets each child shrink to the screen, and the
// tab row scrolls inside itself (`.veyq-tabs__list`, `overflow-x: auto`).
import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'

const css = readFileSync(new URL('./expenses.css', import.meta.url), 'utf8')
const rule = (selector: string) => new RegExp(`(^|\\n)${selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')} \\{[^}]*\\}`).exec(css)?.[0] ?? ''

describe('the Pot page fits a phone', () => {
  it('lays the page out in one column that may shrink below its widest child', () => {
    expect(rule('.expenses-pot')).toMatch(/grid-template-columns:\s*minmax\(0,\s*1fr\)/)
  })

  it('and each panel the same way', () => {
    expect(rule('.expenses-pot section')).toMatch(/grid-template-columns:\s*minmax\(0,\s*1fr\)/)
  })
})
