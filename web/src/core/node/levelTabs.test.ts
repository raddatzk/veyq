// The pure selection rules of the level view's application tabs (inbox #348), levelViews-style.
import { describe, it, expect } from 'vitest'
import { ALL_TAB, effectiveTab, fallbacksVisible, kindVisible, offeredTabs, sectionVisible, showTabBar, type KindTab, type LevelTab } from './levelTabs'

const A: LevelTab = { id: 'ALPHA', label: 'x.a' }
const B: LevelTab = { id: 'BETA', label: 'x.b' }

describe('showTabBar', () => {
  it('needs two or more application tabs: one section is the whole level already', () => {
    expect(showTabBar([])).toBe(false)
    expect(showTabBar([A])).toBe(false)
    expect(showTabBar([A, B])).toBe(true)
  })
})

describe('effectiveTab', () => {
  it('shows the stored tab where the bar offers it, else All, and All is always allowed', () => {
    expect(effectiveTab('BETA', [A, B])).toBe('BETA')
    expect(effectiveTab(ALL_TAB, [A, B])).toBe(ALL_TAB)
    expect(effectiveTab('GAMMA', [A, B])).toBe(ALL_TAB)
  })

  it('shows All when there is no bar, even for a stored tab that exists (one tab is no filter)', () => {
    expect(effectiveTab('ALPHA', [A])).toBe(ALL_TAB)
    expect(effectiveTab('ALPHA', [])).toBe(ALL_TAB)
  })
})

describe('what a tab draws', () => {
  it('All draws every section, an application tab only its own; a section without a tab only under All', () => {
    expect(sectionVisible(ALL_TAB, 'ALPHA')).toBe(true)
    expect(sectionVisible(ALL_TAB, undefined)).toBe(true)
    expect(sectionVisible('ALPHA', 'ALPHA')).toBe(true)
    expect(sectionVisible('ALPHA', 'BETA')).toBe(false)
    expect(sectionVisible('ALPHA', undefined)).toBe(false)
  })

  it('the fallback sections belong to All', () => {
    expect(fallbacksVisible(ALL_TAB)).toBe(true)
    expect(fallbacksVisible('ALPHA')).toBe(false)
  })
})

// inbox #407: the tabs are a pure filter over ONE list of nodes.
describe('offeredTabs and kindVisible (the unified list)', () => {
  const mem: KindTab = { id: 'MEMORY', label: 'x.mem', kinds: ['MEMORY', 'CHAPTER'] }
  const files: KindTab = { id: 'FILE', label: 'x.files', kinds: ['FILE'] }
  const boxes: KindTab = { id: 'DROP', label: 'x.boxes', kinds: ['DROP'] }
  const tabs = [mem, files, boxes]

  it('offers a tab where its kinds have a node here or the viewer may add one - a reader gets no empty tab', () => {
    expect(offeredTabs(tabs, new Set(['FILE']), new Set()).map((t) => t.id)).toEqual(['FILE'])
    expect(offeredTabs(tabs, new Set(['CHAPTER']), new Set()).map((t) => t.id)).toEqual(['MEMORY'])
    expect(offeredTabs(tabs, new Set(), new Set(['MEMORY', 'FILE', 'DROP'])).map((t) => t.id)).toEqual(['MEMORY', 'FILE', 'DROP'])
    expect(offeredTabs(tabs, new Set(), new Set())).toEqual([])
  })

  it("draws every kind under All - one without a tab too - and under a tab only that tab's kinds", () => {
    expect(kindVisible(ALL_TAB, tabs, 'NO_TAB')).toBe(true)
    expect(kindVisible('FILE', tabs, 'FILE')).toBe(true)
    expect(kindVisible('FILE', tabs, 'MEMORY')).toBe(false)
    expect(kindVisible('FILE', tabs, 'NO_TAB')).toBe(false)
    // kinds sharing a label share a tab
    expect(kindVisible('MEMORY', tabs, 'CHAPTER')).toBe(true)
  })

  it('draws nothing under a tab id nobody offers (the caller shows All for it, effectiveTab)', () => {
    expect(kindVisible('GONE', tabs, 'FILE')).toBe(false)
  })
})
