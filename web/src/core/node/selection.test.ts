// The level's selection model (inbox #421): a set of node ids with toggle, all, none, clear and retain. "All" and "None" act
// on the ids they are handed (what the tab on screen shows) and leave every other selected id alone - that is what keeps a
// selection across the level's tabs.
import { describe, it, expect } from 'vitest'
import { useSelection } from './selection'

const ids = (s: ReturnType<typeof useSelection>) => [...s.ids.value].sort()

describe('useSelection', () => {
  it('toggles one id on and off again', () => {
    const s = useSelection()
    s.toggle('a')
    expect(ids(s)).toEqual(['a'])
    expect(s.has('a')).toBe(true)
    s.toggle('a')
    expect(ids(s)).toEqual([])
    expect(s.size.value).toBe(0)
  })

  it('"all" adds what it is handed and keeps what was selected besides', () => {
    const s = useSelection()
    s.toggle('hidden')
    s.all(['a', 'b'])
    expect(ids(s)).toEqual(['a', 'b', 'hidden'])
    expect(s.size.value).toBe(3)
  })

  it('"none" takes away exactly what it is handed: a selected id another tab hides stays', () => {
    const s = useSelection()
    s.all(['a', 'b', 'hidden'])
    s.none(['a', 'b'])
    expect(ids(s)).toEqual(['hidden'])
  })

  it('"clear" empties it; "retain" keeps only the ids still at the level', () => {
    const s = useSelection()
    s.all(['a', 'b', 'c'])
    s.retain(['b', 'c', 'other'])
    expect(ids(s)).toEqual(['b', 'c'])
    s.clear()
    expect(ids(s)).toEqual([])
  })

  it('a change is a new set (the view re-renders on it); an operation that changes nothing keeps the set', () => {
    const s = useSelection()
    s.all(['a'])
    const before = s.ids.value
    s.retain(['a'])
    s.all(['a'])
    s.none(['zzz'])
    expect(s.ids.value).toBe(before)
    s.toggle('b')
    expect(s.ids.value).not.toBe(before)
  })
})
