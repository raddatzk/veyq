import { describe, it, expect } from 'vitest'
import { existsSync, readFileSync } from 'node:fs'
import { resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import { randomBytes } from '@noble/hashes/utils.js'

const here = dirname(fileURLToPath(import.meta.url))
if (!existsSync(resolve(here, '../dist/expenses.d.mts'))) {
  throw new Error('dist/ is missing: run `pnpm run build` (the core compiled by Gradle) before the check')
}
// The package's own entry, as the web imports it.
const x = await import('@veyq/core-js/expenses')
type HeldShape = import('@veyq/core-js/expenses').HeldShape
type LedgerShape = import('@veyq/core-js/expenses').LedgerShape
type JsonShape = import('@veyq/core-js/expenses').JsonShape

/* eslint-disable @typescript-eslint/no-explicit-any -- the vectors are JSON, read case by case */
// repo contracts/ (4 levels up from src/packages/core-js/src/)
const contracts = resolve(here, '../../../../contracts')
const vectors = JSON.parse(readFileSync(resolve(contracts, 'expenses/v1-vectors.json'), 'utf8')) as any

// Expenses' façade against every section of the vectors (ADR 0155 decision 6): the shipped module answers each case the
// core's ExpensesVectorsTest answers on the JVM and natively, and the web's `features/expenses/vectors.test.ts` through
// the web's own glue.
function readAnswer(read: () => unknown): unknown {
  try {
    return { body: read() }
  } catch (e) {
    if ((e as Error).name === 'RevisionFormatInvalid') return { path: (e as { path: string }).path }
    throw e
  }
}
const heldOf = (h: any): HeldShape => ({
  entryId: h.entryId, revision: h.revision, createdAt: h.createdAt, sealedBody: new Uint8Array(), wrappedRevisionKey: new Uint8Array(),
  digest: h.digest, body: h.body === null ? null : x.readRevisionBody(h.body), unreadable: h.body === null ? 'body' : null,
} as HeldShape)
// an entry's content is the web's own body object it was read from, `v` and `heads` with it, as the TypeScript's was
const strip = ({ v: _v, heads: _h, ...c }: any) => c
const pairs = (m: ReadonlyMap<string, any>, f: (v: any) => unknown = (v) => Number(v)) => [...m].map(([k, v]) => [k, f(v)])
const ledgerJson = (l: LedgerShape) => ({
  participants: l.participants,
  entries: l.entries.map((e) => ({
    id: e.id, revision: e.revision, status: e.status, problem: e.problem, edited: e.edited, recordedAt: e.recordedAt, content: e.content == null ? null : strip(e.content),
    effect: e.effect == null ? null : { base: Number(e.effect.base), paid: pairs(e.effect.paid), received: pairs(e.effect.received), shares: pairs(e.effect.shares) },
  })),
  balances: pairs(l.balances),
  settleUp: l.settleUp.map((t) => ({ from: t.from, to: t.to, amount: Number(t.amount) })),
  overview: {
    byCategory: pairs(l.overview.byCategory),
    byPerson: pairs(l.overview.byPerson, (p) => ({ paid: Number(p.paid), share: Number(p.share) })),
    byCategoryAndPerson: pairs(l.overview.byCategoryAndPerson, (m) => pairs(m)),
  },
})
const bytesOf = (c: any) => (c.hex !== undefined ? new Uint8Array(Buffer.from(c.hex, 'hex')) : new TextEncoder().encode(c.utf8))
const potKey = randomBytes(32)
const address = { scopeId: '22222222-2222-4222-8222-222222222222', nodeId: '0e000000-0000-4000-b000-000000000001', entryId: '0e000000-0000-4000-9000-000000000001', revision: 1 }

const answers: Record<string, (c: any) => unknown> = {
  read: (c) => readAnswer(() => x.readRevisionBody(c.body)),
  check: (c) => {
    const held = (c.held as any[]).map(heldOf)
    const r = x.potRevisions(held)
    return { check: x.checkLedger(r), currentHeads: x.currentHeads(r).map((h) => `${h.entryId}:${h.revision}`), headsToName: x.headsToName(r) }
  },
  entryProblem: (c) => x.entryProblem(c.content, c.participants ? (id) => (c.participants as string[]).includes(id) : undefined),
  splitBreaks: (c) => x.splitBreaks(c.problem, c.split, c.part === null ? null : BigInt(c.part)),
  named: (c) => x.namedParticipants(c.content),
  ledger: (c) => ledgerJson(x.buildLedger(x.potRevisions((c.held as any[]).map(heldOf)), c.base)),
  settleUp: (c) => x.settleUp(new Map((c.balances as [string, number][]).map(([k, v]) => [k, BigInt(v)])), c.order).map((t) => ({ from: t.from, to: t.to, amount: Number(t.amount) })),
  amounts: (c) => {
    switch (c.op) {
      case 'parseAmount': {
        const n = x.parseAmount(c.text, c.minorUnits)
        return n == null ? null : n.toString()
      }
      case 'decimalOf':
        return x.decimalOf(BigInt(c.amount), c.minorUnits)
      case 'amountText':
        return x.amountText(BigInt(c.amount), c.minorUnits, c.language)
      case 'parseRate':
        return x.parseRate(c.text)
      case 'formatMoney':
        return x.formatMoney(BigInt(c.amount), c.currency, c.minorUnits, c.language)
    }
    throw new Error(`no op ${c.op}`)
  },
  rates: (c) => (c.op === 'parseTable' ? x.parseTable(c.answer ?? JSON.parse(c.text), c.known) : x.crossRate(c.table, c.from, c.to)),
}

// `conversion`, `rounding` and `pots`: the arithmetic through what the web calls — an entry's effect (a transfer's base
// total is its conversion; an expense split by shares in join order is the allocation, its weights scaled to integers) and
// the ledger of a whole Pot.
const P = (i: number) => `0e000000-0000-4000-8000-${String(i).padStart(12, '0')}`
const gcd = (a: bigint, b: bigint): bigint => (b === 0n ? a : gcd(b, a % b))
function allocated(total: number, weights: string[]): number[] {
  const fr = weights.map((w) => w.split('/').map(BigInt) as [bigint, bigint?])
  const common = fr.reduce((acc, [, d = 1n]) => (acc * d) / gcd(acc, d), 1n)
  const shares = fr.map(([n, d = 1n], i) => ({ participant: P(i + 1), shares: Number((n * common) / d) }))
  const content = { kind: 'expense', title: 'x', category: 'other', date: '2026-01-01', currency: 'EUR', minorUnits: 2, rate: '1', amount: total, receipts: [],
    payers: [{ participant: P(0), amount: total }], allocation: { items: [], rest: { mode: 'shares', shares } } }
  const effect = x.entryEffect(content as never, { currency: 'EUR', minorUnits: 2 }, new Map(shares.map((y, i) => [y.participant, i])))
  return shares.map((y) => Number(effect.shares.get(y.participant)))
}
const potHeld = (pot: any, extra: any[] = []): HeldShape[] => [
  ...pot.participants.map((p: any, i: number) => heldOf({ entryId: p.id, revision: 1, createdAt: i, digest: '', body: { v: 1, heads: [], kind: 'participant', name: p.name, active: true, joined: p.joined } })),
  ...[...pot.entries, ...extra].map((e: any, i: number) => heldOf({ entryId: e.id, revision: 1, createdAt: 100 + i, digest: '', body: { v: 1, heads: [], ...e.body } })),
]
const asNumbers = (m: ReadonlyMap<string, bigint>) => Object.fromEntries([...m].map(([k, v]) => [k, Number(v)]))

describe('the arithmetic and the whole Pots through the façade', () => {
  ;(vectors.conversion as any[]).forEach((c, i) => {
    it(`conversion #${i}: ${c.amount} at ${c.rate}`, () => {
      const transfer = { kind: 'transfer', date: '2026-01-01', currency: 'XXX', minorUnits: c.minorUnits, rate: c.rate, amount: c.amount, from: P(1), to: P(2), receipts: [] }
      expect(Number(x.entryEffect(transfer as never, { currency: 'EUR', minorUnits: c.baseMinorUnits }, new Map()).base)).toBe(c.expected)
    })
  })
  ;(vectors.rounding as any[]).forEach((c, i) => {
    it(`rounding #${i}: ${c.total} by ${c.weights.join(':')}`, () => expect(allocated(c.total, c.weights)).toEqual(c.expected))
  })
  ;(vectors.pots as any[]).forEach((pot) => {
    it(`the Pot "${pot.name}"`, () => {
      const ledger = x.buildLedger(x.potRevisions(potHeld(pot)), pot.base)
      expect(ledger.participants.map((p) => p.id)).toEqual(pot.expected.joinOrder)
      for (const [id, want] of Object.entries(pot.expected.entries) as [string, any][]) {
        const e = ledger.entries.find((y) => y.id === id)!
        expect(e.status).toBe('live')
        expect([Number(e.effect!.base), asNumbers(e.effect!.paid), asNumbers(e.effect!.received), asNumbers(e.effect!.shares)])
          .toEqual([want.base, want.paid ?? {}, want.received ?? {}, want.shares ?? {}])
      }
      expect(asNumbers(ledger.balances)).toEqual(pot.expected.balances)
      expect(ledger.settleUp.map((t) => ({ from: t.from, to: t.to, amount: Number(t.amount) }))).toEqual(pot.expected.settleUp)
      for (const inv of pot.invalid ?? []) {
        const withIt = x.buildLedger(x.potRevisions(potHeld(pot, [inv])), pot.base)
        expect(withIt.entries.find((y) => y.id === inv.id)).toMatchObject({ status: 'invalid', problem: inv.problem, effect: null })
        expect(x.entryProblem(inv.body)).toBe(inv.problem)
        expect(asNumbers(withIt.balances)).toEqual(pot.expected.balances)
      }
    })
  })
})

describe('the Expenses vectors through the façade (ADR 0155 decision 6)', () => {
  for (const [section, answer] of Object.entries(answers)) {
    describe(section, () => {
      ;(vectors[section] as any[]).forEach((c, i) => {
        // compared as JSON, as the file holds it: a -0 is the 0 the file writes
        it(`#${i} ${c.rule}`, () => expect(JSON.parse(JSON.stringify(answer(c)) ?? 'null')).toEqual(c.expected))
      })
    })
  }

  // `decode` is the reader of plaintext bytes, which a façade call reaches only behind an open: the conformance binary runs it
  // over the same JavaScript (its `JSON.parse`, its UTF-8), never an export of the shipped module.
  describe('decode', () => {
    ;(vectors.decode as any[]).forEach((c, i) => {
      it(`#${i} ${c.rule}`, async () => {
        const conformance = await import('../dist/conformance/expenses/veyq-mobile-apps-expenses-conformance.mjs')
        expect(JSON.parse(conformance.decodeRevisionPlaintext(bytesOf(c)))).toEqual(c.expected)
      })
    })
  })

  describe('encode, through a sealed revision', () => {
    ;(vectors.encode as any[]).forEach((c, i) => {
      it(`#${i} ${c.rule}`, () => {
        let got: unknown
        try {
          const sealed = x.sealRevision(address, potKey, c.body)
          got = { json: x.openRevision(address, potKey, sealed) }
        } catch (e) {
          if ((e as Error).name !== 'RevisionFormatInvalid') throw e
          got = { path: (e as { path: string }).path }
        }
        const want = c.expected.path !== undefined ? { path: c.expected.path } : { json: c.expected.json }
        expect(JSON.parse(JSON.stringify(got))).toEqual(want)
      })
    })
  })
})

describe('the codec over the web\'s primitives', () => {
  const crypto = JSON.parse(readFileSync(resolve(contracts, 'crypto/vectors/v1.json'), 'utf8')) as any
  const unhex = (h: string) => new Uint8Array(Buffer.from(h, 'hex'))

  it("opens contracts/crypto/vectors/v1.json's expense-entry case, whose plaintext is a revision the reader takes", () => {
    const c = crypto.field_seal['expense-entry']
    const body = x.openRevisionWithKey(c.context, unhex(c.parent_key), unhex(c.envelope))
    expect(body.v).toBe(1)
    // control: the same envelope at another revision does not open
    expect(() => x.openRevisionWithKey({ ...c.context, revision: c.context.revision + 1 }, unhex(c.parent_key), unhex(c.envelope))).toThrow(/revision_unreadable: body/)
  })

  it('a revision refuses to open at another address, under another key, and opens at its own', () => {
    const body = { v: 1, heads: [], kind: 'participant', name: 'Anna', active: true, joined: 1 } as any
    const sealed = x.sealRevision(address, potKey, body)
    expect(x.openRevision(address, potKey, sealed)).toEqual(body)
    for (const at of [{ ...address, revision: 2 }, { ...address, entryId: '0e000000-0000-4000-9000-000000000002' }, { ...address, nodeId: '0e000000-0000-4000-b000-000000000002' }]) {
      expect(() => x.openRevision(at, potKey, sealed)).toThrow(expect.objectContaining({ name: 'RevisionUnreadable', reason: 'key' }))
    }
    expect(() => x.openRevision(address, randomBytes(32), sealed)).toThrow(expect.objectContaining({ reason: 'key' }))
    const other = x.sealRevision(address, potKey, { ...body, name: 'Ben' })
    expect(() => x.openRevision(address, potKey, { sealedBody: other.sealedBody, wrappedRevisionKey: sealed.wrappedRevisionKey })).toThrow(expect.objectContaining({ reason: 'body' }))
  })

  it('seals to the contract\'s size classes and refuses past 16 KiB', () => {
    expect(x.formatRules().sizeClasses).toEqual([512, 2048, 8192, 16384])
    const sealed = x.sealRevision(address, potKey, { v: 1, heads: [], kind: 'deleted' } as any)
    expect(Buffer.from(sealed.sealedBody).toString('base64')).toHaveLength(744)
    const items = Array.from({ length: 200 }, (_, i) => ({ label: `${i}`.padEnd(200, 'x'), amount: 1, split: { mode: 'equal', participants: [address.entryId] } }))
    const huge = { v: 1, heads: [], kind: 'expense', title: 'x', date: '2026-07-01', category: 'food', currency: 'EUR', minorUnits: 2, rate: '1', amount: 200, payers: [{ participant: address.entryId, amount: 200 }], allocation: { items }, receipts: [] }
    expect(() => x.sealRevision(address, potKey, huge as any)).toThrow('pad_too_large')
  })

  it('a key crosses as a view of the caller\'s buffer: zeroed by the caller, it opens nothing', () => {
    const key = randomBytes(32)
    const body = { v: 1, heads: [], kind: 'deleted' } as any
    const sealed = x.sealRevision(address, key, body)
    expect(x.openRevision(address, key, sealed)).toEqual(body) // control: the key opens it
    const kRev = x.revisionKeyOf(address, key, sealed.wrappedRevisionKey)
    expect(x.openRevisionWithKey(address, kRev, sealed.sealedBody)).toEqual(body)
    kRev.fill(0)
    expect(() => x.openRevisionWithKey(address, kRev, sealed.sealedBody)).toThrow(expect.objectContaining({ reason: 'body' }))
    key.fill(0)
    expect(() => x.openRevision(address, key, sealed)).toThrow(expect.objectContaining({ reason: 'key' }))
  })

  it('a re-key moves K_rev and never the body: the digest stays, the old key is out', () => {
    const body = { v: 1, heads: [], kind: 'deleted' } as any
    const sealed = x.sealRevision(address, potKey, body)
    const newKey = randomBytes(32)
    const moved = { sealedBody: sealed.sealedBody, wrappedRevisionKey: x.rewrapRevisionKey(address, potKey, newKey, sealed.wrappedRevisionKey) }
    expect(x.openRevision(address, newKey, moved)).toEqual(body)
    expect(x.revisionDigest(moved.sealedBody)).toBe(x.revisionDigest(sealed.sealedBody))
    expect(() => x.openRevision(address, potKey, moved)).toThrow(expect.objectContaining({ reason: 'key' }))
    expect(() => x.rewrapRevisionKey({ ...address, revision: 2 }, potKey, newKey, sealed.wrappedRevisionKey)).toThrow(expect.objectContaining({ reason: 'key' }))
  })

  it('opens every served row, holding one that does not open as unreadable, never dropping it', () => {
    const body = { v: 1, heads: [], kind: 'deleted' } as any
    const sealed = x.sealRevision(address, potKey, body)
    const rows = [{ entryId: address.entryId, revision: 1, createdAt: 5, writer: 'user:u', ...sealed }, { entryId: address.entryId, revision: 2, createdAt: 6, ...sealed }]
    const held = x.openServedRevisions(address.scopeId, address.nodeId, potKey, rows)
    expect(held.map((h) => [h.revision, h.unreadable, h.body?.kind ?? null, h.writer ?? null, h.digest === x.revisionDigest(sealed.sealedBody)])).toEqual([
      [1, null, 'deleted', 'user:u', true],
      [2, 'key', null, null, true],
    ])
    expect(x.byRecord(x.potRevisions(held)).get(address.entryId)!.map((h) => h.revision)).toEqual([1, 2])
    expect(x.byRecord(x.potRevisions(held)).get(address.entryId)![0]).toBe(held[0]) // the caller's own objects come back
  })
})

describe('what the web passes crosses', () => {
  it('the rules a caller checks a field by are the format\'s', () => {
    const r = x.formatRules()
    expect(new RegExp(r.uuid).test(address.entryId)).toBe(true)
    expect([r.maxHeads, r.maxReceipts, r.percentWhole]).toEqual([64, 100, 10000])
    expect([...r.defaultCategories]).toEqual(['food', 'groceries', 'accommodation', 'transport', 'leisure', 'household', 'other'])
  })

  it('the currencies and their digits are the core\'s table, not the runtime\'s Intl', () => {
    expect(x.knownCurrencies()).toContain('EUR')
    expect([x.minorUnitsOf('EUR'), x.minorUnitsOf('JPY'), x.minorUnitsOf('BHD'), x.minorUnitsOf('IQD')]).toEqual([2, 0, 3, 0])
  })

  it('money past 2^53 keeps every digit: a bigint in, a bigint out', () => {
    expect(x.decimalOf(9007199254740993n, 2)).toBe('90071992547409.93')
    const held = [{ entryId: '0e000000-0000-4000-8000-000000000001', revision: 1, createdAt: 0, digest: '', sealedBody: new Uint8Array(), wrappedRevisionKey: new Uint8Array(), unreadable: null,
      body: x.readRevisionBody({ v: 1, heads: [], kind: 'participant', name: 'A', active: true, joined: 1 } as JsonShape) }] as HeldShape[]
    const ledger = x.buildLedger(x.potRevisions(held), { currency: 'EUR', minorUnits: 2 })
    expect(typeof ledger.balances.get('0e000000-0000-4000-8000-000000000001')).toBe('bigint')
    expect(x.isSettled(ledger, '0e000000-0000-4000-8000-000000000001')).toBe(true)
    expect(ledger.balances instanceof Map).toBe(true)
  })

  it('a refusal crosses as an Error carrying its path', () => {
    expect(() => x.readRevisionBody({ v: 2 } as JsonShape)).toThrow(expect.objectContaining({ name: 'RevisionFormatInvalid', path: '/v' }))
    expect(() => x.readRevisionBody(null)).toThrow(expect.objectContaining({ path: '' }))
  })
})
