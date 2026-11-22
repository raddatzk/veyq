// The web's drawing of the core's plans (`plans.ts`): what stays the browser's — which row entries are inline buttons — and
// that a plan is drawn whole. The plans' rules themselves are the core's, held by contracts/platform/plans/v1-vectors.json
// (`plans.vectors.test.ts`).
import { describe, it, expect } from 'vitest'
import { MAX_FIRST_ACTIONS, headerPlan, rowPlan } from './plans'
import type { LevelRowAction } from './levelRow'

const a = (id: string, over: Partial<LevelRowAction> = {}): LevelRowAction => ({ id, label: id, icon: 'times', shown: true, onClick: () => {}, ...over })
const ids = (list: readonly LevelRowAction[]) => list.map((x) => x.id)

describe('a row', () => {
  it('draws the entries marked first as buttons, the rest of the menu after rename and move, delete in the last group', () => {
    const { buttons, menu, last } = rowPlan([a('copy'), a('download', { first: true }), a('rename'), a('delete', { severity: 'danger' })])
    expect(ids(buttons)).toEqual(['download'])
    expect(ids(menu)).toEqual(['rename', 'copy'])
    expect(ids(last)).toEqual(['delete'])
  })

  it('never makes delete or report a button, even marked first', () => {
    const { buttons, last } = rowPlan([a('delete', { severity: 'danger', first: true }), a('report', { first: true }), a('favorite', { first: true })])
    expect(ids(buttons)).toEqual(['favorite'])
    expect(ids(last)).toEqual(['report', 'delete'])
  })

  it(`shows at most ${MAX_FIRST_ACTIONS} buttons, in the menu's order: a third first-class entry stays in the menu`, () => {
    const { buttons, menu } = rowPlan([a('favorite', { first: true }), a('download', { first: true }), a('copy', { first: true }), a('move')])
    // the menu is move, favorite, copy (beside the move), download: its first two marked first are the buttons
    expect(ids(buttons)).toEqual(['favorite', 'copy'])
    expect(ids(menu)).toEqual(['move', 'download'])
  })

  it('drops an action that is not shown - from the buttons and from the menu alike (fail closed)', () => {
    const { buttons, menu } = rowPlan([a('favorite', { first: true, shown: false }), a('copy', { shown: false }), a('move')])
    expect(buttons).toEqual([])
    expect(ids(menu)).toEqual(['move'])
  })

  it('keeps a disabled action (rendered, not clickable)', () => {
    expect(ids(rowPlan([a('download', { first: true, disabled: true })]).buttons)).toEqual(['download'])
  })
})

describe('a header', () => {
  it('lists the kind\'s order first, every other entry after it in the merge order; an id the order names but nothing offers is passed over', () => {
    const p = headerPlan([a('share'), a('favorite'), a('upload'), a('download')], ['download', 'music', 'favorite'])
    expect(ids(p.buttons)).toEqual(['download', 'favorite', 'share', 'upload'])
  })

  it('puts move, copy, report and delete in the menu\'s last group, the delete last, whatever the order', () => {
    const p = headerPlan([a('delete', { severity: 'danger' }), a('share'), a('report'), a('move'), a('copy')], ['copy', 'move'])
    expect(ids(p.buttons)).toEqual(['share'])
    expect(p.menu).toEqual([])
    expect(ids(p.last)).toEqual(['copy', 'move', 'report', 'delete'])
  })
})

describe('a plan is drawn whole', () => {
  it('an action the core does not know throws, naming it (no web entry of its own, ADR 0154 decision 2)', () => {
    expect(() => headerPlan([a('select')])).toThrow(/select/)
    expect(() => rowPlan([a('open')])).toThrow(/open/)
  })

  it('a header has no rename only because none is offered (renamed in place): offered, the core places it, as an app\'s', () => {
    expect(ids(headerPlan([a('rename')]).menu)).toEqual(['rename'])
  })

  it('two actions of one id on one node throw', () => {
    expect(() => headerPlan([a('share'), a('share')])).toThrow(/share/)
  })
})
