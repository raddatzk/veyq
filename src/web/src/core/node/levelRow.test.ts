// The row model's pure half (inbox #407): the meta line's details. Which actions are buttons and which are in the menu is
// the core's row plan (`plans.test.ts`, contracts/platform/plans/v1-vectors.json).
import { describe, it, expect } from 'vitest'
import { levelRowDetails } from './levelRow'

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
