// The ledger's computations — each entry's effect, the balances, the settle-up — against contracts/expenses/v1-vectors.json's
// whole Pots, through the real code; then the unbalanced-entry rule (owner 2026-10-24): an entry whose sums do not add up
// counts in no balance, no overview and no settle-up, and is shown as invalid — each case beside its balanced control.
import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { buildLedger, entryProblem, joinOrder, settleUp, type BaseCurrency, type PotLedger } from './ledger'
import { readRevisionBody, type MoneyContent, type RevisionContent, type Split } from './format'
import type { HeldRevision } from './revisions'

type VectorPot = {
  name: string
  base: BaseCurrency
  participants: { id: string; name: string; joined: number }[]
  entries: { id: string; body: MoneyContent }[]
  /** Entries every client counts nowhere and shows as invalid, each with the reason this client names. */
  invalid?: { id: string; body: MoneyContent; problem: string }[]
  expected: {
    joinOrder: string[]
    entries: Record<string, { base: number; paid?: Record<string, number>; received?: Record<string, number>; shares?: Record<string, number> }>
    balances: Record<string, number>
    settleUp: { from: string; to: string; amount: number }[]
  }
}
const vectors = JSON.parse(readFileSync(resolve(__dirname, '../../../../../../contracts/expenses/v1-vectors.json'), 'utf8')) as { pots: VectorPot[] }

let clock = 0
/** A held revision without a seal: the ledger reads bodies only (the seals are codec.test.ts' and heads.test.ts'). */
function heldOf(entryId: string, content: RevisionContent, revision = 1): HeldRevision {
  return {
    entryId,
    revision,
    createdAt: new Date(1_767_225_600_000 + clock++),
    sealedBody: new Uint8Array(),
    wrappedRevisionKey: new Uint8Array(),
    digest: '',
    body: readRevisionBody({ v: 1, heads: [], ...content }), // every vector body is a valid v1 body
    unreadable: null,
  }
}

const participantsOf = (pot: VectorPot) =>
  pot.participants.map((p) => heldOf(p.id, { kind: 'participant', name: p.name, active: true, joined: p.joined }))
const potOf = (pot: VectorPot, entries = pot.entries) => [...participantsOf(pot), ...entries.map((e) => heldOf(e.id, e.body))]
const asNumbers = (m: ReadonlyMap<string, bigint>) => Object.fromEntries([...m].map(([k, v]) => [k, Number(v)]))

describe.each(vectors.pots.map((p) => [p.name, p] as const))('the vector Pot "%s"', (_, pot) => {
  const ledger = buildLedger(potOf(pot), pot.base)

  it('orders its people by join time, then id', () => {
    expect(joinOrder(pot.participants)).toEqual(pot.expected.joinOrder)
    expect(ledger.participants.map((p) => p.id)).toEqual(pot.expected.joinOrder)
  })

  it.each(pot.entries.map((e) => [e.body.title ?? e.id, e] as const))('computes "%s" in the base currency', (_, e) => {
    const entry = ledger.entries.find((x) => x.id === e.id)!
    const want = pot.expected.entries[e.id]!
    expect(entry.status).toBe('live')
    expect(Number(entry.effect!.base)).toBe(want.base)
    expect(asNumbers(entry.effect!.paid)).toEqual(want.paid ?? {})
    expect(asNumbers(entry.effect!.received)).toEqual(want.received ?? {})
    expect(asNumbers(entry.effect!.shares)).toEqual(want.shares ?? {})
  })

  it.each((pot.invalid ?? []).map((e) => [e.body.title ?? e.id, e] as const))('counts "%s" nowhere and names why', (_, e) => {
    const withInvalid = buildLedger(potOf(pot, [...pot.entries, e]), pot.base)
    const entry = withInvalid.entries.find((x) => x.id === e.id)!
    expect(entry.status).toBe('invalid')
    expect(entry.problem).toBe(e.problem)
    expect(entry.effect).toBeNull()
    expect(entryProblem(e.body)).toBe(e.problem) // the write path refuses it before sealing
    expect(asNumbers(withInvalid.balances)).toEqual(pot.expected.balances)
  })

  it('balances to the vectors, and to zero in sum', () => {
    expect(asNumbers(ledger.balances)).toEqual(pot.expected.balances)
    expect([...ledger.balances.values()].reduce((a, b) => a + b, 0n)).toBe(0n)
  })

  it('suggests the settle-up of the vectors, in their order', () => {
    expect(ledger.settleUp.map((t) => ({ ...t, amount: Number(t.amount) }))).toEqual(pot.expected.settleUp)
    expect(ledger.settleUp.length).toBeLessThan(pot.participants.length)
  })

  it('is settled once its settle-up is recorded as transfers', () => {
    const transfers = ledger.settleUp.map((t, i) => ({
      id: `0e000000-0000-4000-a000-${String(i).padStart(12, '0')}`,
      body: { kind: 'transfer', date: '2026-08-02', currency: pot.base.currency, minorUnits: pot.base.minorUnits, rate: '1', amount: Number(t.amount), from: t.from, to: t.to, receipts: [] } as MoneyContent,
    }))
    const after = buildLedger(potOf(pot, [...pot.entries, ...transfers]), pot.base)
    expect([...after.balances.values()].every((v) => v === 0n)).toBe(true)
    expect(after.settleUp).toEqual([])
  })
})

describe('the settle-up re-picks after every transfer', () => {
  it('takes the largest debtor and creditor again each step, ties by join order', () => {
    const order = ['a', 'b', 'c', 'd']
    // a and b owe the same; c and d are owed the same: the earlier-joined of each pair goes first.
    expect(settleUp(new Map([['a', -5n], ['b', -5n], ['c', 5n], ['d', 5n]]), order)).toEqual([
      { from: 'a', to: 'c', amount: 5n },
      { from: 'b', to: 'd', amount: 5n },
    ])
    expect(settleUp(new Map([['b', -5n], ['a', -5n], ['d', 5n], ['c', 5n]]), order)[0]).toEqual({ from: 'a', to: 'c', amount: 5n })
    expect(settleUp(new Map([['a', 0n], ['b', 0n]]), order)).toEqual([])
  })
})

// --- the unbalanced-entry rule ------------------------------------------------------------------------------------------

const trip = vectors.pots.find((p) => p.name === 'trip')!
const [ANNA, BEN, CLEO] = trip.expected.joinOrder as [string, string, string]
const STRANGER = '0e000000-0000-4000-8000-0000000000ff'
const ODD = '0e000000-0000-4000-9000-0000000000ee'
const base = trip.base

const all: Split = { mode: 'equal', participants: [ANNA, BEN, CLEO] }
const item = (label: string, amount: number, split: Split) => ({ label, amount, split })
const equal = (...participants: string[]): Split => ({ mode: 'equal', participants })
const percent = (...p: [string, number][]): Split => ({ mode: 'percent', percents: p.map(([participant, n]) => ({ participant, percent: n })) })
const amounts = (...p: [string, number][]): Split => ({ mode: 'amounts', amounts: p.map(([participant, n]) => ({ participant, amount: n })) })
const expense = (over: Partial<Extract<MoneyContent, { kind: 'expense' }>>): MoneyContent => ({
  kind: 'expense', title: 'Odd', date: '2026-07-09', category: 'other', currency: 'EUR', minorUnits: 2, rate: '1', amount: 900,
  payers: [{ participant: ANNA, amount: 900 }], allocation: { items: [], rest: all }, receipts: [], ...over,
})
const refund = (receivers: { participant: string; amount: number }[]): MoneyContent => ({
  kind: 'refund', title: 'Odd', date: '2026-07-09', category: 'other', currency: 'EUR', minorUnits: 2, rate: '1', amount: 900, receivers, allocation: { items: [], rest: all }, receipts: [],
})

/** Each case: an entry that breaks the rule, and its control — the same entry with the sums made to add up. */
const CASES: { name: string; bad: MoneyContent; good: MoneyContent; problem: string }[] = [
  {
    name: "payers' amounts beside the total",
    bad: expense({ payers: [{ participant: ANNA, amount: 500 }, { participant: BEN, amount: 300 }] }),
    good: expense({ payers: [{ participant: ANNA, amount: 600 }, { participant: BEN, amount: 300 }] }),
    problem: 'payers-unbalanced',
  },
  {
    name: "receivers' amounts beside the total",
    bad: refund([{ participant: ANNA, amount: 901 }]),
    good: refund([{ participant: ANNA, amount: 900 }]),
    problem: 'receivers-unbalanced',
  },
  {
    name: 'items above the total',
    bad: expense({ allocation: { items: [item('A', 500, equal(BEN)), item('B', 401, equal(CLEO))], rest: all } }),
    good: expense({ allocation: { items: [item('A', 500, equal(BEN)), item('B', 400, equal(CLEO))], rest: all } }),
    problem: 'items-above-total',
  },
  {
    name: 'a rest above zero with no split for it',
    bad: expense({ allocation: { items: [item('A', 500, equal(BEN))] } }),
    good: expense({ allocation: { items: [item('A', 500, equal(BEN))], rest: equal(CLEO) } }),
    problem: 'rest-unallocated',
  },
  {
    name: "an item's percents beside 100.00 %",
    bad: expense({ allocation: { items: [item('A', 900, percent([BEN, 5000], [CLEO, 4999]))] } }),
    good: expense({ allocation: { items: [item('A', 900, percent([BEN, 5001], [CLEO, 4999]))] } }),
    problem: 'percent-unbalanced',
  },
  {
    name: "the rest's percents beside 100.00 %",
    bad: expense({ allocation: { items: [], rest: percent([BEN, 10000], [CLEO, 1]) } }),
    good: expense({ allocation: { items: [], rest: percent([BEN, 9999], [CLEO, 1]) } }),
    problem: 'percent-unbalanced',
  },
  {
    name: "an item's amounts beside the item's amount",
    bad: expense({ allocation: { items: [item('A', 900, amounts([BEN, 500], [CLEO, 401]))] } }),
    good: expense({ allocation: { items: [item('A', 900, amounts([BEN, 500], [CLEO, 400]))] } }),
    problem: 'amounts-unbalanced',
  },
  {
    name: "the rest's amounts beside the rest",
    bad: expense({ allocation: { items: [item('A', 300, equal(BEN))], rest: amounts([ANNA, 300], [CLEO, 299]) } }),
    good: expense({ allocation: { items: [item('A', 300, equal(BEN))], rest: amounts([ANNA, 300], [CLEO, 300]) } }),
    problem: 'amounts-unbalanced',
  },
  {
    name: 'a payer named twice',
    bad: expense({ payers: [{ participant: ANNA, amount: 450 }, { participant: ANNA, amount: 450 }] }),
    good: expense({ payers: [{ participant: ANNA, amount: 450 }, { participant: BEN, amount: 450 }] }),
    problem: 'named-twice',
  },
  {
    name: 'a beneficiary named twice in one split',
    bad: expense({ allocation: { items: [item('A', 900, { mode: 'shares', shares: [{ participant: BEN, shares: 1 }, { participant: BEN, shares: 2 }] })] } }),
    good: expense({ allocation: { items: [item('A', 900, { mode: 'shares', shares: [{ participant: BEN, shares: 1 }, { participant: CLEO, shares: 2 }] })] } }),
    problem: 'named-twice',
  },
  {
    name: 'a person who is no participant',
    bad: expense({ allocation: { items: [], rest: equal(ANNA, STRANGER) } }),
    good: expense({ allocation: { items: [], rest: equal(ANNA, BEN) } }),
    problem: 'unknown-participant',
  },
  {
    name: 'a transfer to a person who is no participant',
    bad: { kind: 'transfer', date: '2026-07-09', currency: 'EUR', minorUnits: 2, rate: '1', amount: 900, from: ANNA, to: STRANGER, receipts: [] },
    good: { kind: 'transfer', date: '2026-07-09', currency: 'EUR', minorUnits: 2, rate: '1', amount: 900, from: ANNA, to: BEN, receipts: [] },
    problem: 'unknown-participant',
  },
]

describe('an entry whose sums do not add up counts nowhere and is shown as invalid', () => {
  const clean = buildLedger(potOf(trip), base)
  const withEntry = (content: MoneyContent): PotLedger => buildLedger([...potOf(trip), heldOf(ODD, content)], base)
  const sameNumbers = (a: PotLedger, b: PotLedger) => {
    expect(asNumbers(a.balances)).toEqual(asNumbers(b.balances))
    expect(a.settleUp).toEqual(b.settleUp)
    expect(asNumbers(a.overview.byCategory)).toEqual(asNumbers(b.overview.byCategory))
    expect(JSON.stringify([...a.overview.byPerson].map(([k, v]) => [k, `${v.paid}/${v.share}`]))).toBe(
      JSON.stringify([...b.overview.byPerson].map(([k, v]) => [k, `${v.paid}/${v.share}`])),
    )
  }

  it.each(CASES.map((c) => [c.name, c] as const))('%s', (_, c) => {
    const bad = withEntry(c.bad)
    const entry = bad.entries.find((e) => e.id === ODD)!
    expect(entry.status).toBe('invalid')
    expect(entry.problem).toBe(c.problem)
    expect(entry.effect).toBeNull()
    sameNumbers(bad, clean) // it moved no balance, no settle-up, no overview

    // Control: the same entry made to add up counts, so the comparison above is not blind.
    const good = withEntry(c.good)
    expect(good.entries.find((e) => e.id === ODD)!.status).toBe('live')
    expect(asNumbers(good.balances)).not.toEqual(asNumbers(clean.balances))
  })

  it('the write path asks the same rule (sums alone, then with the Pot\'s people)', () => {
    for (const c of CASES.filter((x) => x.problem !== 'unknown-participant')) {
      expect(entryProblem(c.bad)).toBe(c.problem)
      expect(entryProblem(c.good)).toBeNull()
    }
    const known = new Set([ANNA, BEN, CLEO])
    for (const c of CASES.filter((x) => x.problem === 'unknown-participant')) {
      expect(entryProblem(c.bad)).toBeNull() // the sums alone are fine
      expect(entryProblem(c.bad, (id) => known.has(id))).toBe('unknown-participant')
      expect(entryProblem(c.good, (id) => known.has(id))).toBeNull()
    }
  })

  it('items exactly at the total add up with no rest split (a bill with no tip); a rest split sealed beside them moves nothing', () => {
    expect(entryProblem(expense({ allocation: { items: [item('A', 900, equal(BEN))] } }))).toBeNull()
    const sealed = withEntry(expense({ allocation: { items: [item('A', 900, equal(BEN))], rest: equal(CLEO) } }))
    expect(asNumbers(sealed.entries.find((e) => e.id === ODD)!.effect!.shares)).toEqual({ [BEN]: 900 })
  })

  it('a rest split by amounts sealed at a zero rest splits nothing, so no sum holds it (control: above zero it must add up)', () => {
    const sealed = expense({ allocation: { items: [item('A', 900, equal(BEN))], rest: amounts([CLEO, 1]) } })
    expect(entryProblem(sealed)).toBeNull()
    expect(asNumbers(withEntry(sealed).entries.find((e) => e.id === ODD)!.effect!.shares)).toEqual({ [BEN]: 900 })
    expect(entryProblem(expense({ allocation: { items: [item('A', 899, equal(BEN))], rest: amounts([CLEO, 2]) } }))).toBe('amounts-unbalanced')
  })

  it('a rest split sealed at a zero rest is still held to the rules: a stranger in it makes the entry invalid', () => {
    const c = expense({ allocation: { items: [item('A', 900, equal(BEN))], rest: equal(STRANGER) } })
    expect(entryProblem(c)).toBeNull()
    expect(entryProblem(c, (id) => [ANNA, BEN, CLEO].includes(id))).toBe('unknown-participant')
  })

  it('one person on several parts gets the sum of their parts, each part rounded on its own', () => {
    // 1.00 by thirds is 0.34/0.33/0.33 (Anna's extra cent by join order); the rest 0.01 equal between Ben and Cleo goes to
    // Ben. Rounded once over the whole entry instead (weights 33⅓, 33⅚, 33⅚), it would be 0.33/0.34/0.34.
    const c = expense({ amount: 101, payers: [{ participant: ANNA, amount: 101 }], allocation: { items: [item('A', 100, all)], rest: equal(BEN, CLEO) } })
    expect(asNumbers(withEntry(c).entries.find((e) => e.id === ODD)!.effect!.shares)).toEqual({ [ANNA]: 34, [BEN]: 34, [CLEO]: 33 })
  })
})

describe("a record's state is its latest revision's", () => {
  const dinner = trip.entries[0]!
  const deleted = { kind: 'deleted' } as const

  it('a deleted entry leaves the balances and keeps its content for a restore; a later revision brings it back', () => {
    const live = buildLedger(potOf(trip), base)
    const gone = buildLedger([...potOf(trip, trip.entries.slice(1)), heldOf(dinner.id, dinner.body, 1), heldOf(dinner.id, deleted, 2)], base)
    const entry = gone.entries.find((e) => e.id === dinner.id)!
    expect(entry.status).toBe('deleted')
    expect(entry.content).toMatchObject(dinner.body)
    const without = buildLedger(potOf(trip, trip.entries.slice(1)), base)
    expect(asNumbers(gone.balances)).toEqual(asNumbers(without.balances))
    const restored = buildLedger([...potOf(trip, trip.entries.slice(1)), heldOf(dinner.id, dinner.body, 1), heldOf(dinner.id, deleted, 2), heldOf(dinner.id, dinner.body, 3)], base)
    expect(asNumbers(restored.balances)).toEqual(asNumbers(live.balances))
    expect(restored.entries.find((e) => e.id === dinner.id)!.edited).toBe(true)
  })

  it('an erased participant keeps its join order and its balance, with no name', () => {
    const held = [...potOf(trip)]
    held.push(heldOf(CLEO, { kind: 'erasure', joined: 1767225601000 }, 2))
    const ledger = buildLedger(held, base)
    expect(ledger.participants.map((p) => p.id)).toEqual([ANNA, BEN, CLEO])
    expect(ledger.participants[2]).toMatchObject({ erased: true, name: null })
    expect(asNumbers(ledger.balances)).toEqual(trip.expected.balances) // counts as before (docs/apps/expenses.md)
  })

  it('a record whose latest revision does not open counts nowhere — no fallback to an earlier one', () => {
    const unreadable: HeldRevision = { ...heldOf(dinner.id, dinner.body, 2), body: null, unreadable: 'body' }
    const ledger = buildLedger([...potOf(trip), unreadable], base)
    expect(ledger.entries.find((e) => e.id === dinner.id)!.status).toBe('unreadable')
    const without = buildLedger(potOf(trip, trip.entries.slice(1)), base)
    expect(asNumbers(ledger.balances)).toEqual(asNumbers(without.balances))
  })
})

describe('the overviews', () => {
  const ledger = buildLedger(potOf(trip), base)

  it('total each category, refunds against theirs, transfers nowhere', () => {
    // food: Dinner 10000 + Trattoria 6001 + Bar 2500; transport: Taxi 1000 + Ferry 3908 − the train refund 1500.
    expect(asNumbers(ledger.overview.byCategory)).toEqual({ food: 18501, leisure: 3062, transport: 3408, groceries: 4001 })
  })

  it('put what each person paid beside their share, and the shares sum to the costs', () => {
    const anna = ledger.overview.byPerson.get(ANNA)!
    // paid: Dinner 10000 + Taxi 1000 + Groceries 2001 − her part of the refund 1000.
    expect(anna.paid).toBe(12001n)
    const shares = [...ledger.overview.byPerson.values()].reduce((a, p) => a + p.share, 0n)
    const costs = [...ledger.overview.byCategory.values()].reduce((a, b) => a + b, 0n)
    expect(shares).toBe(costs)
    const paid = [...ledger.overview.byPerson.values()].reduce((a, p) => a + p.paid, 0n)
    expect(paid).toBe(costs)
  })

  it('list the people in join order, whichever entry names them first (an import mints new entry ids)', () => {
    // The lane L1 pass: an imported Pot listed "deleted people" first, the original last —
    // the rows followed the first entry, by id, that named each person. The museum alone: Cleo pays, so she comes first.
    const museum = trip.entries.find((e) => e.body.title === 'Museum')!
    const one = buildLedger(potOf(trip, [museum]), base)
    expect([...one.overview.byPerson.keys()]).toEqual([ANNA, BEN, CLEO])
  })

  it('split each category by person', () => {
    const transport = ledger.overview.byCategoryAndPerson.get('transport')!
    // Taxi 0/700/300, Ferry 1694/1693/521, the refund −400/−400/−700.
    expect(asNumbers(transport)).toEqual({ [ANNA]: 1294, [BEN]: 1993, [CLEO]: 121 })
  })
})
