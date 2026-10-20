// The row model's pure half (inbox #407): which actions are buttons, which are in the overflow menu, and core's own.
import { describe, it, expect } from 'vitest'
import { MAX_FIRST_ACTIONS, levelRowDetails, mergeRowActions, splitRowActions, type CoreRowAction, type LevelRowAction } from './levelRow'

const a = (id: string, over: Partial<CoreRowAction> = {}): CoreRowAction => ({ id, label: id, icon: 'pi pi-x', onClick: () => {}, ...over })
const ids = (list: readonly LevelRowAction[]) => list.map((x) => x.id)

describe('splitRowActions', () => {
  it('makes the first-class actions buttons and everything else the menu, each in the given order', () => {
    const { buttons, menu } = splitRowActions([a('open'), a('download', { first: true }), a('rename'), a('delete', { severity: 'danger' })])
    expect(ids(buttons)).toEqual(['download'])
    expect(ids(menu)).toEqual(['open', 'rename', 'delete'])
  })

  it(`shows at most ${MAX_FIRST_ACTIONS} buttons: a third first-class action falls into the menu`, () => {
    const { buttons, menu } = splitRowActions([a('a', { first: true }), a('b', { first: true }), a('c', { first: true }), a('d')])
    expect(ids(buttons)).toEqual(['a', 'b'])
    expect(ids(menu)).toEqual(['c', 'd'])
  })

  it('drops an action that is not shown - from the buttons and from the menu alike (fail closed)', () => {
    const { buttons, menu } = splitRowActions([a('x', { first: true, shown: false }), a('y', { shown: false }), a('z', { shown: true })])
    expect(buttons).toEqual([])
    expect(ids(menu)).toEqual(['z'])
  })

  it('keeps a disabled action (rendered, not clickable)', () => {
    expect(ids(splitRowActions([a('w', { first: true, disabled: true })]).buttons)).toEqual(['w'])
  })
})

describe('mergeRowActions', () => {
  it('puts core\'s actions before or after the application\'s (default after), each group in its order', () => {
    const core: CoreRowAction[] = [a('c1', { placement: 'before' }), a('c2'), a('c3', { placement: 'after' })]
    expect(ids(mergeRowActions(core, [a('p'), a('q')]))).toEqual(['c1', 'p', 'q', 'c2', 'c3'])
  })

  it('is the application\'s list alone when core adds none', () => {
    expect(ids(mergeRowActions([], [a('p')]))).toEqual(['p'])
  })
})

describe('levelRowDetails', () => {
  it('keeps the details with text, in the given order', () => {
    expect(levelRowDetails({ details: [{ id: 'size', text: '3 kB' }, { id: 'modified', text: 'Modified today' }] }).map((d) => d.id)).toEqual(['size', 'modified'])
  })

  it('has none for a row without details', () => {
    expect(levelRowDetails({})).toEqual([])
    expect(levelRowDetails({ details: [] })).toEqual([])
  })

  it('drops a blank entry, so a placeholder-shaped hole never reaches the line', () => {
    expect(levelRowDetails({ details: [{ id: 'date', text: '' }, { id: 'place', text: '   ' }, { id: 'size', text: '1 B' }] }).map((d) => d.id)).toEqual(['size'])
  })
})
