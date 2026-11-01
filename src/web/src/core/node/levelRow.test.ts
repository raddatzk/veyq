// The row model's pure half (inbox #407): which actions are buttons, which are in the overflow menu, and core's own.
import { describe, it, expect } from 'vitest'
import { MAX_FIRST_ACTIONS, levelRowDetails, mergeRowActions, splitRowActions, type CoreRowAction, type LevelRowAction } from './levelRow'

const a = (id: string, over: Partial<CoreRowAction> = {}): CoreRowAction => ({ id, label: id, icon: 'times', shown: true, onClick: () => {}, ...over })
const ids = (list: readonly LevelRowAction[]) => list.map((x) => x.id)

describe('splitRowActions', () => {
  it('makes the first-class actions buttons and everything else the menu: rename and move first, delete in the last group', () => {
    const { buttons, menu, menuOnly } = splitRowActions([a('open'), a('download', { first: true }), a('rename'), a('delete', { severity: 'danger' })])
    expect(ids(buttons)).toEqual(['download'])
    expect(ids(menu)).toEqual(['rename', 'open'])
    expect(ids(menuOnly)).toEqual(['delete'])
  })

  it('orders the menu: rename, move, the others in their order, then the menu-only group - report, then delete', () => {
    const { menu, menuOnly } = splitRowActions([
      a('delete', { severity: 'danger' }), a('cover'), a('report', { menuOnly: true }), a('move'), a('copy'), a('rename'), a('download'),
    ])
    expect(ids(menu)).toEqual(['rename', 'move', 'cover', 'copy', 'download'])
    expect(ids(menuOnly)).toEqual(['report', 'delete'])
  })

  it('never makes delete or report a button, even marked first', () => {
    const { buttons, menuOnly } = splitRowActions([a('delete', { severity: 'danger', first: true }), a('report', { menuOnly: true, first: true }), a('heart', { first: true })])
    expect(ids(buttons)).toEqual(['heart'])
    expect(ids(menuOnly)).toEqual(['report', 'delete'])
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
