// How a Pot's people read on screen (docs/apps/expenses.md § The Pot): every erased person is ONE "deleted people" — in
// the balances too, summed — a settled deactivated or deleted one leaves the balances until an edit unsettles them, and
// a new entry is offered only active people beside whoever the edited entry already names.
import { describe, it, expect } from 'vitest'
import { buildLedger } from './domain/ledger'
import { offeredPeople, personLabel, shownBalances } from './people'
import { ANNA, BEN, CLEO, EUR, PotServer, expense, person } from './potTestUtil'

const DAN = '0e000000-0000-4000-8000-000000000004'
const t = (key: string) => key

function pot() {
  const s = new PotServer()
  s.append([{ entryId: ANNA, content: person('Anna', 1) }, { entryId: BEN, content: person('Ben', 2) }, { entryId: CLEO, content: person('Cleo', 3) }, { entryId: DAN, content: person('Dan', 4) }])
  return s
}

describe('people on screen', () => {
  it('an erased person reads as "deleted people"; a live one by name', () => {
    const s = pot()
    s.erase(DAN)
    const ledger = buildLedger(s.held(), EUR)
    expect(personLabel(ledger, DAN, t)).toBe('expenses.people.deletedPeople')
    expect(personLabel(ledger, ANNA, t)).toBe('Anna')
  })

  it('erased people with an open balance are ONE line, their balances summed; settled ones are not shown', () => {
    const s = pot()
    s.erase(CLEO)
    s.erase(DAN)
    // An old entry edited after the erasures unsettles both (the entry names them by id).
    s.append([{ entryId: '0e000000-0000-4000-9000-000000000001', content: expense('Old bill', 4000, ANNA, [ANNA, BEN, CLEO, DAN]) }])
    const rows = shownBalances(buildLedger(s.held(), EUR))
    expect(rows.map((r) => [r.id, Number(r.balance)])).toEqual([[ANNA, 3000], [BEN, -1000], [null, -2000]])
    // control: with nothing open, no "deleted people" line at all
    const settled = pot()
    settled.erase(DAN)
    expect(shownBalances(buildLedger(settled.held(), EUR)).map((r) => r.id)).toEqual([ANNA, BEN, CLEO])
  })

  it('a deactivated person is not offered for a new entry, only on the entry that already names them', () => {
    const s = pot()
    s.append([{ entryId: BEN, content: person('Ben', 2, false) }])
    const ledger = buildLedger(s.held(), EUR)
    expect(offeredPeople(ledger).map((p) => p.id)).toEqual([ANNA, CLEO, DAN])
    expect(offeredPeople(ledger, [BEN]).map((p) => p.id)).toEqual([ANNA, BEN, CLEO, DAN])
    expect(shownBalances(ledger).map((r) => r.id)).toEqual([ANNA, CLEO, DAN]) // settled and deactivated: left out
  })
})
