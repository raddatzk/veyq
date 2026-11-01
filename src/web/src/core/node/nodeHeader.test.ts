// The node header's pure half (ADR 0110): the kind's priority, the menu-only split, which layout a header width gets and how
// many buttons fit a width. A unit test has no layout, so this is where the count and the switch are held; a real browser
// pass at phone and desktop width proves the rest.
import { describe, it, expect } from 'vitest'
import {
  fitHeaderActions,
  headerFloor,
  headerLayout,
  isMenuOnly,
  orderHeaderActions,
  partitionHeaderActions,
  type HeaderFit,
  type HeaderLayout,
  type HeaderLayoutInput,
  type NodeHeaderAction,
} from './nodeHeader'

const act = (id: string, over: Partial<NodeHeaderAction> = {}): NodeHeaderAction => ({ id, label: id, icon: 'heart', shown: true, onClick: () => {}, ...over })

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

  describe('with a floor (a wide header: the name first, ADR 0110 decision 3)', () => {
    it.each([
      ['a long name leaves no room: the floor\'s two stay buttons', { rowWidth: 20, floor: 2, menuOnly: true }, 2],
      ['a short name: as many as fit, above the floor', { rowWidth: 208, floor: 2, menuOnly: true }, 4],
      ['a guest with one action keeps it', { rowWidth: 0.5, count: 1, floor: headerFloor(1), menuOnly: true }, 1],
      ['a floor never exceeds the actions that may be buttons', { rowWidth: 20, count: 1, floor: 2, menuOnly: true }, 1],
      ['no action that may be a button: none', { rowWidth: 20, count: 0, floor: headerFloor(0), menuOnly: true }, 0],
    ] as const)('%s', (_name, over, expected) => {
      expect(fit(over)).toBe(expected)
    })

    it('the floor is the first two, fewer where there are fewer', () => {
      expect([0, 1, 2, 3, 11].map(headerFloor)).toEqual([0, 1, 2, 2, 2])
    })
  })
})

describe('headerLayout', () => {
  // The sizes measured 2026-10-23: 16 px gaps beside the name, a 12rem name minimum, 41 px buttons 7.2 px apart, a 41 px ⋯;
  // the floor's two buttons and the ⋯ need 16 + 82 + 41 + 14.4 = 153.4 px beside the name.
  const base: HeaderLayoutInput = { headerWidth: 0, leadingWide: 0, leadingGap: 16, nameMin: 192, rowGap: 16, floor: 2, more: true, buttonWidth: 41, gap: 7.2, moreWidth: 41 }
  const memory = { ...base, leadingWide: 180 }
  const icon = { ...base, leadingWide: 42 }
  const cover = { ...base, leadingWide: 88 }

  it.each([
    ['a Memory (180 px cover)', memory, 541.4],
    ['an icon tile (42 px)', icon, 403.4],
    ['a folder or drop box with a cover (5.5rem)', cover, 449.4],
  ] as const)('%s: compact one pixel below its switch, wide at it', (_name, kind, at) => {
    expect(headerLayout({ ...kind, headerWidth: at - 1 })).toBe('compact')
    expect(headerLayout({ ...kind, headerWidth: at })).toBe('wide')
  })

  it('a 375 px phone (a 343 px header) is compact for every kind', () => {
    for (const kind of [memory, icon, cover]) expect(headerLayout({ ...kind, headerWidth: 343 })).toBe('compact')
  })

  it('unmeasured (width 0) is wide: the markup every unit test has', () => {
    expect(headerLayout({ ...memory, headerWidth: 0 })).toBe('wide')
  })

  it('no leading visual and no action: only the name\'s minimum counts', () => {
    expect(headerLayout({ ...base, floor: 0, more: false, headerWidth: 192 })).toBe('wide')
    expect(headerLayout({ ...base, floor: 0, more: false, headerWidth: 191 })).toBe('compact')
  })

  it('a guest with one action and no ⋯ needs room for that one button only', () => {
    expect(headerLayout({ ...memory, floor: 1, more: false, headerWidth: 180 + 16 + 192 + 16 + 41 })).toBe('wide')
  })

  // The component reads the leading visual's WIDE width, which no layout changes (a registered custom property). Each frame
  // re-reads its inputs; `leadingOf` is what the leading visual reads as in the layout in force.
  function frames(headerWidth: number, leadingOf: (layout: HeaderLayout) => number): HeaderLayout[] {
    let layout: HeaderLayout = 'wide'
    const seen: HeaderLayout[] = []
    for (let i = 0; i < 4; i++) {
      layout = headerLayout({ ...memory, headerWidth, leadingWide: leadingOf(layout) })
      seen.push(layout)
    }
    return seen
  }

  it('for every header width from 300 to 1400 px the answer settles at once and flips at most once across the sweep', () => {
    let flips = 0
    let previous: HeaderLayout | null = null
    for (let w = 300; w <= 1400; w++) {
      const seen = frames(w, () => 180)
      expect(new Set(seen), `${w} px: ${seen.join(' ')}`).toEqual(new Set([seen[0]]))
      if (previous && seen[0] !== previous) flips++
      previous = seen[0]
    }
    expect(flips).toBe(1)
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
