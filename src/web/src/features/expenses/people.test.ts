// How a Pot's people read on screen (docs/apps/expenses.md § The Pot): every erased person is ONE "deleted people" — in
// the balances too, summed — a settled deactivated or deleted one leaves the balances until an edit unsettles them, and
// a new entry is offered only active people beside whoever the edited entry already names.
import { describe, it, expect } from 'vitest'
import { buildLedger } from './domain/ledger'
import { addableMembers, offeredPeople, personLabel, shownBalances, standingOf, type MemberDirectory } from './people'
import type { Person } from '../../core/people/directory'
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

// A participant that is a member (ADR 0108 decision 2): a member's view reads it by the directory's current name, as a
// former member by the sealed name once the directory no longer lists them; a guest, handed no directory, by the sealed one.
describe('a member participant on screen', () => {
  const U_DANA = '0d000000-0000-4000-8000-000000000001'
  const U_GUS = '0d000000-0000-4000-8000-000000000003'
  const U_ERIK = '0d000000-0000-4000-8000-000000000002'
  const GUS = '0e000000-0000-4000-8000-0000000000a7'
  const who = (userId: string, displayName: string): Person => ({ userId, username: displayName.toLowerCase(), realName: displayName, displayName })
  function withMembers() {
    const s = pot()
    s.append([
      { entryId: '0e000000-0000-4000-8000-0000000000d1', content: { kind: 'participant', name: 'Dana Old', active: true, joined: 5, member: { userId: U_DANA } } },
      { entryId: GUS, content: { kind: 'participant', name: 'Gus Sealed', active: true, joined: 6, member: { userId: U_GUS } } },
    ])
    return buildLedger(s.held(), EUR)
  }
  const DANA = '0e000000-0000-4000-8000-0000000000d1'
  const directory: MemberDirectory = new Map([[U_DANA, who(U_DANA, 'Dana Now')], [U_ERIK, who(U_ERIK, 'Erik')]])

  it('a member reads the current name; one the directory no longer lists is a former member by the sealed name', () => {
    const ledger = withMembers()
    expect(personLabel(ledger, DANA, t, directory)).toBe('Dana Now')
    expect(personLabel(ledger, GUS, t, directory)).toBe('Gus Sealed')
    const p = (id: string) => ledger.participants.find((x) => x.id === id)!
    expect([standingOf(p(DANA), directory), standingOf(p(GUS), directory), standingOf(p(ANNA), directory)]).toEqual(['member', 'former', 'name'])
  })

  it('a guest, handed no directory, reads the name sealed at adding and never a former member (control: the member\'s view above)', () => {
    const ledger = withMembers()
    expect(personLabel(ledger, DANA, t)).toBe('Dana Old')
    expect(standingOf(ledger.participants.find((x) => x.id === GUS)!, null)).toBe('sealed')
  })

  it('offers to add only the members not yet in the Pot — an erased one\'s member again', () => {
    const ledger = withMembers()
    const members = [who(U_DANA, 'Dana Now'), who(U_ERIK, 'Erik'), who(U_GUS, 'Gus')]
    expect(addableMembers(ledger, members).map((m) => m.userId)).toEqual([U_ERIK])
    const s = pot()
    s.append([{ entryId: DANA, content: { kind: 'participant', name: 'Dana', active: true, joined: 5, member: { userId: U_DANA } } }])
    s.erase(DANA)
    expect(addableMembers(buildLedger(s.held(), EUR), members).map((m) => m.userId)).toEqual([U_DANA, U_ERIK, U_GUS])
  })
})
