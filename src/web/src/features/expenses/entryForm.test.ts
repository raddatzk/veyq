// The entry editor's form against contracts/expenses/v1-vectors.json: every vector entry — all rest, items with splits by
// equal, shares and percent, a rest by shares, two payers, a foreign currency with a typed rate, a transfer, a refund with
// receivers and an item, exact amounts as one item each — goes through the form as a person would edit it and comes out as
// the very body, and the Pot written from those forms through the real write path computes the vectors' balances and
// settle-up. A form that does not add up is refused before anything is sealed.
import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import * as v1 from '@veyq/crypto/v1'
import { fromB64std } from '@veyq/crypto/base64'
import { contentOf, emptyForm, emptyItem, equalSplit, formOf, parsePercent, payersTotal, problemKey, restLeft, withCurrency, withKind } from './entryForm'
import type { MoneyContent } from './domain/format'
import { buildLedger } from './domain/ledger'
import { sealPotWrites, type PotWrite } from './domain/potApi'
import { openServedRevisions, type HeldRevision } from './domain/revisions'
import { expensesMessages } from './i18n'

type VectorPot = {
  name: string
  base: { currency: string; minorUnits: number }
  participants: { id: string; name: string; joined: number }[]
  entries: { id: string; body: MoneyContent }[]
  expected: { balances: Record<string, number>; settleUp: { from: string; to: string; amount: number }[] }
}
const vectors = JSON.parse(readFileSync(resolve(__dirname, '../../../../../contracts/expenses/v1-vectors.json'), 'utf8')) as { pots: VectorPot[] }
const SCOPE = '22222222-2222-4222-8222-222222222222'
const POT = '0e000000-0000-4000-b000-000000000001'

describe.each(vectors.pots.map((p) => [p.name, p] as const))('the vector Pot "%s" through the editor', (_, pot) => {
  const people = pot.participants.map((p) => p.id)
  const isParticipant = (id: string) => people.includes(id)

  it('every entry, opened in the form and saved unchanged, is the very body — in either language', () => {
    for (const locale of ['en', 'de']) {
      for (const e of pot.entries) {
        const out = contentOf(formOf(e.body, pot.base, people, locale), isParticipant)
        expect(out, `${e.body.kind} ${locale}`).toEqual({ content: e.body })
      }
    }
  })

  it('the Pot written from those forms computes the vectors\' balances and settle-up', () => {
    const key = v1.randomBytes(32)
    let held: HeldRevision[] = []
    const write = (writes: PotWrite[]) => {
      const rows = sealPotWrites({ scopeId: SCOPE, nodeId: POT }, key, held, writes, pot.base).map((w) => ({
        entryId: w.entryId, revision: w.revision, createdAt: new Date(0), sealedBody: fromB64std(w.sealedBody), wrappedRevisionKey: fromB64std(w.wrappedRevisionKey),
      }))
      held = [...held, ...openServedRevisions(SCOPE, POT, key, rows)]
    }
    write(pot.participants.map((p) => ({ entryId: p.id, content: { kind: 'participant', name: p.name, active: true, joined: p.joined } })))
    write(pot.entries.map((e) => {
      const out = contentOf(formOf(e.body, pot.base, people, 'de'), isParticipant)
      if (!('content' in out)) throw new Error(out.problem)
      return { entryId: e.id, content: out.content }
    }))
    const ledger = buildLedger(held, pot.base)
    expect(Object.fromEntries([...ledger.balances].map(([id, v]) => [id, Number(v)]))).toEqual(pot.expected.balances)
    expect(ledger.settleUp.map((t) => ({ ...t, amount: Number(t.amount) }))).toEqual(pot.expected.settleUp)
  })
})

const [A, B, C] = ['0e000000-0000-4000-8000-000000000001', '0e000000-0000-4000-8000-000000000002', '0e000000-0000-4000-8000-000000000003']
const EUR = { currency: 'EUR', minorUnits: 2 }

describe('the form as a person fills it', () => {
  const dinner = () => {
    const form = { ...emptyForm(EUR, [A, B, C]), title: 'Dinner', date: '2026-07-01', category: 'food' }
    form.payers[0]!.amount = '100,00'
    return form
  }

  it('a new expense: the first person paying what they typed, no items, the whole amount split equally among everyone offered', () => {
    expect(contentOf(dinner())).toEqual({
      content: {
        kind: 'expense', title: 'Dinner', category: 'food', date: '2026-07-01', currency: 'EUR', minorUnits: 2, rate: '1', amount: 10000, receipts: [],
        payers: [{ participant: A, amount: 10000 }], allocation: { items: [], rest: { mode: 'equal', participants: [A, B, C] } },
      },
    })
  })

  it('the amount is the payers\' sum, never typed: two payers make it', () => {
    const form = { ...dinner(), payers: [{ participant: A, amount: '40,01' }, { participant: B, amount: '20' }] }
    expect(payersTotal(form)).toBe(6001n)
    const out = contentOf(form)
    expect('content' in out && [out.content.amount, out.content.kind === 'expense' && out.content.payers]).toEqual([6001, [{ participant: A, amount: 4001 }, { participant: B, amount: 2000 }]])
  })

  it('items take their exact amounts and splits; the rest goes by its own split, and is left out when the items fill the amount', () => {
    const form = dinner()
    form.items = [
      { ...emptyItem([A, B, C]), label: 'Wine', amount: '30' },
      { label: 'Cake', amount: '10,5', split: { mode: 'percent', equal: [], shares: {}, percent: { [A]: '33,33', [B]: '66.67', [C]: '' } } },
    ]
    form.rest = { mode: 'shares', equal: [A], shares: { [A]: '2', [C]: '1' }, percent: {} }
    expect(restLeft(form)).toBe(5950n)
    const out = contentOf(form)
    expect('content' in out && out.content.kind === 'expense' && out.content.allocation).toEqual({
      items: [
        { label: 'Wine', amount: 3000, split: { mode: 'equal', participants: [A, B, C] } },
        { label: 'Cake', amount: 1050, split: { mode: 'percent', percents: [{ participant: A, percent: 3333 }, { participant: B, percent: 6667 }] } },
      ],
      rest: { mode: 'shares', shares: [{ participant: A, shares: 2 }, { participant: C, shares: 1 }] },
    })
    // Exact amounts per person: one item each, filling the amount — no rest, so no rest split is sealed (control: the one above has one).
    const exact = { ...dinner(), items: [{ ...emptyItem([B]), label: 'Ben', amount: '70' }, { ...emptyItem([C]), label: 'Cleo', amount: '30' }] }
    const filled = contentOf(exact)
    expect('content' in filled && filled.content.kind === 'expense' && filled.content.allocation).toEqual({
      items: [{ label: 'Ben', amount: 7000, split: { mode: 'equal', participants: [B] } }, { label: 'Cleo', amount: 3000, split: { mode: 'equal', participants: [C] } }],
    })
  })

  it('a percent takes at most two decimals and at most 100 %', () => {
    expect([parsePercent('33,33'), parsePercent('100'), parsePercent('0,01')]).toEqual([3333, 10000, 1])
    expect([parsePercent('33,333'), parsePercent('100,01'), parsePercent('0'), parsePercent('x')]).toEqual([null, null, null, null])
  })

  it('switching the kind carries the amount between the payers and a transfer', () => {
    const transfer = withKind(dinner(), 'transfer', 'de')
    expect(transfer.amount).toBe('100,00')
    const back = withKind({ ...emptyForm(EUR, [A, B]), kind: 'transfer', amount: '12,50' }, 'expense', 'de')
    expect(back.payers).toEqual([{ participant: A, amount: '12,50' }])
  })

  it('another currency takes its own digits and asks for the rate (control: back to the base, the rate is 1 again)', () => {
    const yen = withCurrency(emptyForm(EUR, [A, B]), 'JPY', EUR)
    expect([yen.minorUnits, yen.rate]).toEqual([0, ''])
    const ramen = { ...yen, title: 'Ramen', payers: [{ participant: A, amount: '5000' }] }
    expect(contentOf(ramen)).toEqual({ problem: 'rate' })
    const typed = contentOf({ ...ramen, rate: '0,0061234' })
    expect('content' in typed && [typed.content.amount, typed.content.rate, typed.content.minorUnits]).toEqual([5000, '0.0061234', 0])
    expect(withCurrency(yen, 'EUR', EUR)).toMatchObject({ minorUnits: 2, rate: '1' })
  })

  it('refuses what does not add up — never saved (owner 2026-10-24) — and names what to fix', () => {
    const base = { ...emptyForm(EUR, [A, B]), title: 'Taxi', payers: [{ participant: A, amount: '10' }] }
    const item = (amount: string, split = equalSplit([A])) => ({ label: 'Wine', amount, split })
    expect(contentOf({ ...base, title: ' ' })).toEqual({ problem: 'title' })
    expect(contentOf({ ...base, payers: [{ participant: A, amount: '10.001' }] })).toEqual({ problem: 'payers' })
    expect(contentOf({ ...base, payers: [{ participant: A, amount: '4' }, { participant: B, amount: '' }] })).toEqual({ problem: 'payers' })
    expect(contentOf({ ...base, payers: [{ participant: A, amount: '4' }, { participant: B, amount: '6' }] })).toHaveProperty('content') // control
    expect(contentOf({ ...base, items: [item('11')] })).toEqual({ problem: 'items-above-total' })
    expect(contentOf({ ...base, items: [item('10')] })).toHaveProperty('content') // control: at the amount
    expect(contentOf({ ...base, items: [item('4', equalSplit([]))] })).toEqual({ problem: 'items' })
    expect(contentOf({ ...base, items: [{ ...item('4'), label: ' ' }] })).toEqual({ problem: 'items' })
    expect(contentOf({ ...base, rest: equalSplit([]) })).toEqual({ problem: 'split' })
    expect(contentOf({ ...base, items: [item('10')], rest: equalSplit([]) })).toHaveProperty('content') // control: no rest, no split asked
    expect(contentOf({ ...base, rest: { ...equalSplit([]), mode: 'percent', percent: { [A]: '50', [B]: '49,99' } } })).toEqual({ problem: 'percent-unbalanced' })
    expect(contentOf({ ...base, rest: { ...equalSplit([]), mode: 'shares', shares: { [A]: '1,5' } } })).toEqual({ problem: 'split' })
    expect(contentOf({ ...base, kind: 'transfer', amount: '10', from: A, to: A })).toEqual({ problem: 'transfer' })
    expect(contentOf({ ...base, kind: 'transfer', amount: '', from: A, to: B })).toEqual({ problem: 'amount' })
    expect(contentOf(base, (id) => id === A)).toEqual({ problem: 'unknown-participant' })
  })

  it('every problem it or the write path can name has a sentence in both languages', () => {
    const reasons = ['title', 'date', 'amount', 'rate', 'payers', 'split', 'items', 'transfer', 'payers-unbalanced', 'receivers-unbalanced', 'items-above-total',
      'rest-unallocated', 'percent-unbalanced', 'named-twice', 'unknown-participant', 'record-twice', 'erasure-through-append', 'not-a-participant', 'not-settled', 'joined-moved', 'empty', 'receipt-dropped-and-kept']
    for (const locale of ['en', 'de'] as const) {
      const sentences = expensesMessages[locale].expenses.entry.problem as Record<string, string>
      expect(reasons.map(problemKey).filter((k) => !sentences[k])).toEqual([])
      expect(Object.keys(sentences).sort()).toEqual(reasons.map(problemKey).sort())
    }
  })
})
