// How a Pot's people read on screen (docs/apps/expenses.md § The Pot): every erased person is ONE "deleted people" — in
// the balances too, summed — a settled deactivated or deleted one leaves the balances until an edit unsettles them, and
// a new entry is offered only active people beside whoever the edited entry already names.
import { describe, it, expect } from 'vitest'
import { buildLedger } from './domain/ledger'
import { addableMembers, membersMatching, offeredPeople, participantRecord, personLabel, shownBalances, standingOf, type MemberDirectory } from './people'
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

  it('erased people with an open balance are ONE line, their balances summed; settled ones without costs are not shown', () => {
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

  it('a settled deactivated or deleted person with costs keeps a row, so the rows add up to the costs (control: without costs, none)', () => {
    const s = pot()
    s.append([{ entryId: '0e000000-0000-4000-9000-000000000001', content: expense('Dinner', 6000, ANNA, [ANNA, BEN, CLEO]) }])
    // Ben and Cleo pay their part back; then Ben is deactivated and Cleo deleted, both settled.
    for (const [i, from] of [[2, BEN], [3, CLEO]] as const) {
      s.append([{ entryId: `0e000000-0000-4000-9000-00000000000${i}`, content: { kind: 'transfer', date: '2026-07-02', currency: 'EUR', minorUnits: 2, rate: '1', amount: 2000, receipts: [], from, to: ANNA } }])
    }
    s.append([{ entryId: BEN, content: person('Ben', 2, false) }])
    s.erase(CLEO)
    const ledger = buildLedger(s.held(), EUR)
    const rows = shownBalances(ledger)
    expect(rows.map((r) => [r.id, Number(r.balance), Number(r.consumed)])).toEqual([[ANNA, 0, 2000], [BEN, 0, 2000], [DAN, 0, 0], [null, 0, 2000]])
    const total = [...ledger.overview.byCategory.values()].reduce((a, b) => a + b, 0n)
    expect(rows.reduce((a, r) => a + r.consumed, 0n)).toBe(total)
    expect(rows.reduce((a, r) => a + r.paid, 0n)).toBe(total)
  })

  it('a row explains its balance: paid less consumed plus the net of its transfers', () => {
    const s = pot()
    s.append([{ entryId: '0e000000-0000-4000-9000-000000000001', content: expense('Dinner', 9000, ANNA, [ANNA, BEN, CLEO]) }])
    // Ben pays Anna back his part; Cleo does not.
    s.append([{ entryId: '0e000000-0000-4000-9000-000000000002', content: { kind: 'transfer', date: '2026-07-02', currency: 'EUR', minorUnits: 2, rate: '1', amount: 3000, receipts: [], from: BEN, to: ANNA } }])
    const rows = shownBalances(buildLedger(s.held(), EUR))
    const of = (id: string) => rows.find((r) => r.id === id)!
    expect([of(ANNA).paid, of(ANNA).consumed, of(ANNA).settled, of(ANNA).balance].map(Number)).toEqual([9000, 3000, -3000, 3000])
    expect([of(BEN).paid, of(BEN).consumed, of(BEN).settled, of(BEN).balance].map(Number)).toEqual([0, 3000, 3000, 0])
    expect([of(CLEO).paid, of(CLEO).consumed, of(CLEO).settled, of(CLEO).balance].map(Number)).toEqual([0, 3000, 0, -3000])
    for (const r of rows) expect(r.paid - r.consumed + r.settled).toBe(r.balance)
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

  it('the create dialog\'s people so far take a member off the offer as a record does (control: the ledger above)', () => {
    const members = [who(U_DANA, 'Dana'), who(U_ERIK, 'Erik')]
    expect(addableMembers({ participants: [{ erased: false, member: U_ERIK }, { erased: false, member: null }] }, members).map((m) => m.userId)).toEqual([U_DANA])
  })

  it('the create dialog suggests the members whose display name holds what is typed, case and accents aside; all of them for a blank query', () => {
    const members = [who(U_DANA, 'Dana Zoë'), who(U_ERIK, 'Erik'), who(U_GUS, 'Gustav ZOE')]
    const ids = (q: string) => membersMatching(members, q).map((m) => m.userId)
    expect(ids('zoe')).toEqual([U_DANA, U_GUS])
    expect(ids('  RIK ')).toEqual([U_ERIK])
    expect(ids('Zoë D')).toEqual([])
    expect(ids('ana z')).toEqual([U_DANA])
    expect(ids('   ')).toEqual([U_DANA, U_ERIK, U_GUS])
    expect(ids('Finn')).toEqual([])
  })

  it('a new participant\'s first record: a name as typed; a member by id with the display name sealed, cut to 100 characters', () => {
    expect(participantRecord({ name: 'Finn' }, 7)).toEqual({ kind: 'participant', name: 'Finn', active: true, joined: 7 })
    const long = 'Ä'.repeat(120)
    expect(participantRecord({ member: who(U_DANA, long) }, 8)).toEqual({ kind: 'participant', name: 'Ä'.repeat(100), active: true, joined: 8, member: { userId: U_DANA } })
  })
})
