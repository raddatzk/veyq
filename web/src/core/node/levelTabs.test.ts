// The pure selection rules of the level view's application tabs (inbox #348), levelViews-style.
import { describe, it, expect } from 'vitest'
import { ALL_TAB, effectiveTab, fallbacksVisible, sectionVisible, showTabBar, type LevelTab } from './levelTabs'

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
