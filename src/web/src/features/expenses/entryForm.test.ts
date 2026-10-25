// The entry editor's form against contracts/expenses/v1-vectors.json: every vector entry — an equal split, line items
// with a tip, a foreign currency with a typed rate, a transfer, a refund, exact amounts and shares — goes through the
// form as a person would edit it and comes out as the very body, and the Pot written from those forms through the real
// write path computes the vectors' balances and settle-up. A form that does not add up is refused before anything is sealed.
import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import * as v1 from '@veyq/crypto/v1'
import { fromB64std } from '@veyq/crypto/base64'
import { contentOf, emptyForm, formOf, problemKey, withCurrency } from './entryForm'
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
  it('a new expense: typed amount, the first person paying, split equally among everyone offered', () => {
    const form = { ...emptyForm(EUR, [A, B, C]), title: 'Dinner', amount: '100,00', date: '2026-07-01', category: 'food' }
    expect(contentOf(form)).toEqual({
      content: {
        kind: 'expense', title: 'Dinner', category: 'food', date: '2026-07-01', currency: 'EUR', minorUnits: 2, rate: '1', amount: 10000, receipts: [],
        payers: [{ participant: A, amount: 10000 }], allocation: { mode: 'equal', participants: [A, B, C] },
      },
    })
  })

  it('another currency takes its own digits and asks for the rate (control: back to the base, the rate is 1 again)', () => {
    const yen = withCurrency(emptyForm(EUR, [A, B]), 'JPY', EUR)
    expect([yen.minorUnits, yen.rate]).toEqual([0, ''])
    expect(contentOf({ ...yen, title: 'Ramen', amount: '5000' })).toEqual({ problem: 'rate' })
    const typed = contentOf({ ...yen, title: 'Ramen', amount: '5000', rate: '0,0061234' })
    expect('content' in typed && [typed.content.amount, typed.content.rate, typed.content.minorUnits]).toEqual([5000, '0.0061234', 0])
    expect(withCurrency(yen, 'EUR', EUR)).toMatchObject({ minorUnits: 2, rate: '1' })
  })

  it('refuses what does not add up — never saved (owner 2026-10-24) — and names what to fix', () => {
    const base = { ...emptyForm(EUR, [A, B]), title: 'Taxi', amount: '10' }
    expect(contentOf({ ...base, title: ' ' })).toEqual({ problem: 'title' })
    expect(contentOf({ ...base, amount: '10.001' })).toEqual({ problem: 'amount' })
    expect(contentOf({ ...base, payers: [{ participant: A, amount: '4' }, { participant: B, amount: '5' }] })).toEqual({ problem: 'payers-unbalanced' })
    expect(contentOf({ ...base, payers: [{ participant: A, amount: '4' }, { participant: B, amount: '6' }] })).toHaveProperty('content') // control
    expect(contentOf({ ...base, mode: 'exact', exact: { [A]: '3', [B]: '3' } })).toEqual({ problem: 'exact-unbalanced' })
    expect(contentOf({ ...base, mode: 'items', items: [{ label: 'Wine', amount: '11', participants: [A] }] })).toEqual({ problem: 'items-above-total' })
    expect(contentOf({ ...base, mode: 'equal', equal: [] })).toEqual({ problem: 'split' })
    expect(contentOf({ ...base, kind: 'transfer', from: A, to: A })).toEqual({ problem: 'transfer' })
    expect(contentOf(base, (id) => id === A)).toEqual({ problem: 'unknown-participant' })
  })

  it('every problem it or the write path can name has a sentence in both languages', () => {
    const reasons = ['title', 'date', 'amount', 'rate', 'payers', 'split', 'items', 'transfer', 'payers-unbalanced', 'receivers-unbalanced', 'exact-unbalanced',
      'items-above-total', 'named-twice', 'unknown-participant', 'record-twice', 'erasure-through-append', 'not-a-participant', 'not-settled', 'joined-moved', 'empty', 'receipt-dropped-and-kept']
    for (const locale of ['en', 'de'] as const) {
      const sentences = expensesMessages[locale].expenses.entry.problem as Record<string, string>
      expect(reasons.map(problemKey).filter((k) => !sentences[k])).toEqual([])
      expect(Object.keys(sentences).sort()).toEqual(reasons.map(problemKey).sort())
    }
  })
})
