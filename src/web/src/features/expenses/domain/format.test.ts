// The v1 reader held to contracts/expenses/entry.v1.schema.json: its patterns and enums ARE the schema's (read from the
// file, so a schema change that this reader does not follow is red here), every vector body reads, and each rule the
// schema states refuses a body that breaks it — beside a control that differs in that rule alone.
import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { CURRENCY, DATE, DEFAULT_CATEGORIES, DIGEST, MAX_HEADS, PERCENT_WHOLE, RATE, UUID, decodeRevisionBody, encodeRevisionBody, readRevisionBody, RevisionFormatInvalid, type RevisionBody } from './format'
import { expensesMessages } from '../i18n'

const contracts = resolve(__dirname, '../../../../../../contracts/expenses')
const schema = JSON.parse(readFileSync(resolve(contracts, 'entry.v1.schema.json'), 'utf8'))
const vectors = JSON.parse(readFileSync(resolve(contracts, 'v1-vectors.json'), 'utf8')) as { pots: { participants: { id: string; name: string; joined: number }[]; entries: { body: object }[] }[] }
const defs = schema.$defs

describe('the reader is the schema', () => {
  it('uses the schema\'s own patterns', () => {
    expect(UUID.source).toBe(defs.uuid.pattern)
    expect(DIGEST.source).toBe(defs.head.properties.digest.pattern)
    expect(CURRENCY.source).toBe(defs.currency.pattern)
    expect(DATE.source).toBe(defs.date.pattern)
    expect(RATE.source).toBe(defs.rate.pattern)
    expect(MAX_HEADS).toBe(schema.properties.heads.maxItems)
  })

  it('knows the schema\'s kinds and default categories, and the catalog names each category in both languages', () => {
    expect(schema.properties.kind.enum).toEqual(['expense', 'refund', 'transfer', 'participant', 'deleted', 'erasure'])
    expect([...DEFAULT_CATEGORIES]).toEqual(defs.category.anyOf[0].enum)
    for (const locale of ['en', 'de'] as const) expect(Object.keys(expensesMessages[locale].expenses.category)).toEqual([...DEFAULT_CATEGORIES])
  })

  it('knows the schema\'s allocation: items with a split each, an optional rest split, and the three split modes', () => {
    expect(defs.allocation.required).toEqual(['items'])
    expect(defs.allocation.properties.rest.$ref).toBe('#/$defs/split')
    expect(defs.allocation.properties.items.items.required).toEqual(['label', 'amount', 'split'])
    expect(defs.split.oneOf.map((s: { properties: { mode: { const: string } } }) => s.properties.mode.const)).toEqual(['equal', 'shares', 'percent'])
    expect(defs.split.oneOf[2].properties.percents.items.properties.percent.maximum).toBe(PERCENT_WHOLE)
  })
})

const P = '0e000000-0000-4000-8000-000000000001'
const Q = '0e000000-0000-4000-8000-000000000002'
const head = { entryId: P, revision: 1, digest: 'A'.repeat(43) }
const expense = {
  v: 1, heads: [head], kind: 'expense', title: 'Taxi', date: '2026-07-04', category: 'transport', currency: 'EUR', minorUnits: 2, rate: '1', amount: 1000,
  payers: [{ participant: P, amount: 1000 }], allocation: { items: [{ label: 'Fare', amount: 600, split: { mode: 'percent', percents: [{ participant: P, percent: 2550 }, { participant: Q, percent: 7450 }] } }], rest: { mode: 'equal', participants: [P, Q] } }, receipts: [],
}
const withItem = (split: unknown) => ({ ...expense, allocation: { items: [{ label: 'x', amount: 1, split }] } })

describe('reading a body', () => {
  it('reads every body of the vectors, and every participant as a record', () => {
    let n = 0
    for (const pot of vectors.pots) {
      for (const e of pot.entries) expect(() => readRevisionBody({ v: 1, heads: [], ...e.body }), String(n++)).not.toThrow()
      for (const p of pot.participants) expect(readRevisionBody({ v: 1, heads: [], kind: 'participant', name: p.name, active: true, joined: p.joined }).kind).toBe('participant')
    }
    expect(n).toBeGreaterThan(10)
  })

  it('drops a field it does not know (additive under v: 1) and round-trips through the bytes', () => {
    const body = readRevisionBody({ ...expense, later: 'field', allocation: { ...expense.allocation, extra: 1, rest: { ...expense.allocation.rest, extra: 2 } } })
    expect(body).not.toHaveProperty('later')
    expect(body.kind === 'expense' && body.allocation).not.toHaveProperty('extra')
    expect(body.kind === 'expense' && body.allocation.rest).not.toHaveProperty('extra')
    expect(body.kind === 'expense' && body.allocation.items[0]!.split).toEqual(expense.allocation.items[0]!.split)
    expect(decodeRevisionBody(encodeRevisionBody(body))).toEqual(body)
  })

  const refused: [string, unknown, string][] = [
    ['another version', { ...expense, v: 2 }, '/v'],
    ['no heads', { ...expense, heads: undefined }, '/heads'],
    ['more heads than the schema allows', { ...expense, heads: Array.from({ length: MAX_HEADS + 1 }, () => head) }, '/heads'],
    ['a head with a short digest', { ...expense, heads: [{ ...head, digest: 'A'.repeat(42) }] }, '/heads/0/digest'],
    ['an unknown kind', { ...expense, kind: 'loan' }, '/kind'],
    ['a zero amount', { ...expense, amount: 0 }, '/amount'],
    ['an amount past the safe integers', { ...expense, amount: 2 ** 53 }, '/amount'],
    ['a fractional amount', { ...expense, amount: 10.5 }, '/amount'],
    ['a rate of zero', { ...expense, rate: '0' }, '/rate'],
    ['a rate as a number', { ...expense, rate: 1 }, '/rate'],
    ['a lowercase currency', { ...expense, currency: 'eur' }, '/currency'],
    ['five minor digits', { ...expense, minorUnits: 5 }, '/minorUnits'],
    ['a 13th month', { ...expense, date: '2026-13-01' }, '/date'],
    ['an empty title', { ...expense, title: '' }, '/title'],
    ['an unknown category', { ...expense, category: 'fun' }, '/category'],
    ['an uppercase uuid', { ...expense, payers: [{ participant: P.toUpperCase(), amount: 1000 }] }, '/payers/0/participant'],
    ['no payers', { ...expense, payers: [] }, '/payers'],
    ['an allocation without its items', { ...expense, allocation: { rest: expense.allocation.rest } }, '/allocation/items'],
    ['an allocation in the per-entry modes it replaced', { ...expense, allocation: { mode: 'equal', participants: [P, Q] } }, '/allocation/items'],
    ['an item without a split', { ...expense, allocation: { items: [{ label: 'x', amount: 1 }] } }, '/allocation/items/0/split'],
    ['an item without a label', { ...expense, allocation: { items: [{ label: '', amount: 1, split: { mode: 'equal', participants: [P] } }] } }, '/allocation/items/0/label'],
    ['an item of zero', { ...expense, allocation: { items: [{ label: 'x', amount: 0, split: { mode: 'equal', participants: [P] } }] } }, '/allocation/items/0/amount'],
    ['a beneficiary twice in an equal split', withItem({ mode: 'equal', participants: [P, P] }), '/allocation/items/0/split/participants'],
    ['an unknown split mode', withItem({ mode: 'random' }), '/allocation/items/0/split/mode'],
    ['the replaced exact mode', withItem({ mode: 'exact', amounts: [{ participant: P, amount: 1 }] }), '/allocation/items/0/split/mode'],
    ['zero shares', withItem({ mode: 'shares', shares: [{ participant: P, shares: 0 }] }), '/allocation/items/0/split/shares/0/shares'],
    ['a percent of zero', withItem({ mode: 'percent', percents: [{ participant: P, percent: 0 }] }), '/allocation/items/0/split/percents/0/percent'],
    ['a percent above 100.00 %', withItem({ mode: 'percent', percents: [{ participant: P, percent: 10001 }] }), '/allocation/items/0/split/percents/0/percent'],
    ['a fractional percent', withItem({ mode: 'percent', percents: [{ participant: P, percent: 33.5 }] }), '/allocation/items/0/split/percents/0/percent'],
    ['a rest split of an unknown mode', { ...expense, allocation: { items: [], rest: { mode: 'proportional' } } }, '/allocation/rest/mode'],
    ['a receipt twice', { ...expense, receipts: [P, P] }, '/receipts'],
    ['a participant without a join time', { v: 1, heads: [], kind: 'participant', name: 'Ada', active: true }, '/joined'],
    ['a participant with a 101-character name', { v: 1, heads: [], kind: 'participant', name: 'x'.repeat(101), active: true, joined: 1 }, '/name'],
    ['an erasure without its join time', { v: 1, heads: [], kind: 'erasure' }, '/joined'],
    ['a member that is no object', { v: 1, heads: [], kind: 'participant', name: 'Ada', active: true, joined: 1, member: Q }, '/member'],
    ['a member without a user id', { v: 1, heads: [], kind: 'participant', name: 'Ada', active: true, joined: 1, member: {} }, '/member/userId'],
    ['a member whose user id is no uuid', { v: 1, heads: [], kind: 'participant', name: 'Ada', active: true, joined: 1, member: { userId: 'u-ada' } }, '/member/userId'],
    ['not an object', [expense], ''],
  ]

  it.each(refused)('refuses %s', (_, body, path) => {
    let thrown: unknown
    try {
      readRevisionBody(body)
    } catch (e) {
      thrown = e
    }
    expect(thrown).toBeInstanceOf(RevisionFormatInvalid)
    expect((thrown as RevisionFormatInvalid).path).toBe(path)
  })

  // A participant that is a member (ADR 0108 decision 2, inbox #783): an optional field, additive under `v: 1`.
  const ada = { v: 1, heads: [], kind: 'participant', name: 'Ada Lovelace', active: true, joined: 1 } as const
  it('a member participant round-trips through the bytes with its user id and the name sealed beside it', () => {
    const body = readRevisionBody({ ...ada, member: { userId: Q } })
    expect(body).toEqual({ ...ada, member: { userId: Q } })
    expect(decodeRevisionBody(encodeRevisionBody(body))).toEqual(body)
  })

  it('a record written before the field reads as a name: no member, nothing invented (control: the one above has one)', () => {
    const body = readRevisionBody({ ...ada })
    expect(body).toEqual(ada)
    expect(body).not.toHaveProperty('member')
  })

  it('unknown fields stay additive beside and inside the member: dropped, the member kept (the rule by which a reader that predates the field drops `member` and reads the name)', () => {
    const body = readRevisionBody({ ...ada, member: { userId: Q, avatar: 'x' }, linkedLater: true })
    expect(body).toEqual({ ...ada, member: { userId: Q } })
  })

  it('the member field is the schema\'s: optional, an object whose required user id is the schema\'s uuid', () => {
    const def = defs.participant
    expect(def.required).toEqual(['kind', 'name', 'active', 'joined'])
    expect(def.properties.member.required).toEqual(['userId'])
    expect(def.properties.member.properties.userId.$ref).toBe('#/$defs/uuid')
  })

  it('counts a name in code points, as the schema does: a 100-emoji name reads', () => {
    expect(readRevisionBody({ v: 1, heads: [], kind: 'participant', name: '😀'.repeat(100), active: true, joined: 1 }).kind).toBe('participant')
  })

  it('refuses bytes that are not UTF-8 JSON', () => {
    expect(() => decodeRevisionBody(new Uint8Array([0xff, 0xfe]))).toThrow(RevisionFormatInvalid)
    expect(() => decodeRevisionBody(new TextEncoder().encode('{"v":1'))).toThrow(RevisionFormatInvalid)
  })

  it('a writer never encodes a body its reader would refuse', () => {
    expect(() => encodeRevisionBody({ ...expense, amount: 0 } as unknown as RevisionBody)).toThrow(RevisionFormatInvalid)
  })
})
