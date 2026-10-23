// The node header's pure half (ADR 0110): the kind's priority, the menu-only split and how many buttons fit a width. A unit
// test has no layout, so this is where the count is held; a real browser pass at phone and desktop width proves the rest.
import { describe, it, expect } from 'vitest'
import { fitHeaderActions, isMenuOnly, orderHeaderActions, partitionHeaderActions, type HeaderFit, type NodeHeaderAction } from './nodeHeader'

const act = (id: string, over: Partial<NodeHeaderAction> = {}): NodeHeaderAction => ({ id, label: id, icon: 'heart', onClick: () => {}, ...over })

describe('fitHeaderActions', () => {
  // 36 px buttons, 7 px gaps, a 36 px ⋯: n buttons alone take 36n + 7(n - 1); k buttons and the ⋯ take 43k + 36.
  const base: HeaderFit = { count: 5, rowWidth: 0, buttonWidth: 36, gap: 7, moreWidth: 36, menuOnly: false }
  const fit = (over: Partial<HeaderFit>) => fitHeaderActions({ ...base, ...over })

  it.each([
    ['everything fits exactly, nothing menu-only: every one a button, no ⋯', { rowWidth: 208 }, 5],
    ['one pixel short: the ⋯ is reserved first, and three fit beside it', { rowWidth: 207 }, 3],
    ['room for the ⋯ and four', { rowWidth: 208, count: 6 }, 4],
    ['zero room: the ⋯ alone', { rowWidth: 40 }, 0],
    ['narrower than the ⋯ itself: still no button', { rowWidth: 10 }, 0],
    ['a menu-only action forces the ⋯ even when the rest would fit alone', { rowWidth: 208, menuOnly: true }, 4],
    ['a menu-only action and room for all of them beside the ⋯', { rowWidth: 251, menuOnly: true }, 5],
    ['nothing that may be a button', { rowWidth: 300, count: 0, menuOnly: true }, 0],
    ['unmeasured (width 0): every one a button', { rowWidth: 0, menuOnly: true }, 5],
    ['unmeasured (no button width yet): every one a button', { rowWidth: 100, buttonWidth: 0 }, 5],
  ] as const)('%s', (_name, over, expected) => {
    expect(fit(over)).toBe(expected)
  })

  it('a fractional width a hair under the exact fit still fits (measured widths are not whole)', () => {
    expect(fit({ rowWidth: 207.995 })).toBe(5)
  })
})

describe('orderHeaderActions', () => {
  it('the listed ids first in the list\'s order, every unlisted one after them in the given order; an unknown id is ignored', () => {
    const ids = orderHeaderActions([act('a'), act('b'), act('c'), act('d')], ['c', 'x', 'a']).map((a) => a.id)
    expect(ids).toEqual(['c', 'a', 'b', 'd'])
  })

  it('no order: the given order', () => {
    expect(orderHeaderActions([act('b'), act('a')]).map((a) => a.id)).toEqual(['b', 'a'])
  })
})

describe('partitionHeaderActions', () => {
  it('a danger action and a menuOnly one are menu-only, the danger one last, whatever the order', () => {
    expect(isMenuOnly(act('delete', { severity: 'danger' }))).toBe(true)
    expect(isMenuOnly(act('report', { menuOnly: true }))).toBe(true)
    expect(isMenuOnly(act('upload', { severity: 'primary' }))).toBe(false)
    const parts = partitionHeaderActions([act('delete', { severity: 'danger' }), act('share'), act('report', { menuOnly: true }), act('move')])
    expect(parts.buttons.map((a) => a.id)).toEqual(['share', 'move'])
    expect(parts.menuOnly.map((a) => a.id)).toEqual(['report', 'delete'])
  })
})
