// The Memory's cover is core's node header's leading visual (ADR 0110): core picks the header's layout from the cover's WIDE
// width, which it reads as `--veyq-node-header-leading-wide` (a number, no import of this application), and hands back
// `compact` through the slot. Pinned at the source, since a unit test renders no CSS: without the property core would read
// 0 and put the 180 px cover beside a name squeezed below its minimum; a size of its own (a container query) would size the
// cover apart from the header's answer.
import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'

const sfc = readFileSync(new URL('./MemoryDetail.vue', import.meta.url), 'utf8')
const style = sfc.slice(sfc.indexOf('<style'))

describe('the Memory cover in the node header', () => {
  it('sizes itself from its wide width, the number core reads', () => {
    expect(style).toMatch(/\.memory-cover-preview \{[^}]*--veyq-node-header-leading-wide:\s*180px;[^}]*width:\s*var\(--veyq-node-header-leading-wide\)/)
  })

  it('is a 64 px tile when the header is compact — the slot\'s answer, not a query of its own', () => {
    expect(sfc).toMatch(/#leading="\{ compact \}"/)
    expect(sfc).toMatch(/'is-compact': compact/)
    expect(style).toMatch(/\.memory-cover-preview\.is-compact \{\s*width:\s*64px;/)
    expect(style).not.toMatch(/@container memory-detail-surface \(max-width: 460px\)/)
  })
})
