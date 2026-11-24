// Expenses' rules against contracts/expenses/v1-vectors.json's sections beyond the arithmetic and the whole Pots — the
// reader's acceptance and refusals (parsed and as bytes), what a writer seals, the ledger check and the heads a write
// names, why an entry may not count, each record's state and the overviews, the settle-up, amounts as typed and shown,
// and the day's rates. The apps' core answers every case of the same file (`ExpensesVectorsTest`); a case that fails here
// names its section, index and rule.
import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { decodeRevisionBody, encodeRevisionBody, readRevisionBody, RevisionFormatInvalid, type MoneyContent } from './domain/format'
import { buildLedger, entryProblem, namedParticipants, settleUp, splitBreaks, type PotLedger } from './domain/ledger'
import { checkLedger, currentHeads, headsToName } from './domain/heads'
import type { HeldRevision } from './domain/revisions'
import { amountText, decimalOf, formatMoney, parseAmount, parseRate } from './amounts'
import { crossRate, parseTable } from './exchangeRates'

const vectors = JSON.parse(readFileSync(resolve(__dirname, '../../../../../contracts/expenses/v1-vectors.json'), 'utf8')) as any

function readAnswer(read: () => unknown): unknown {
  try {
    return { body: read() }
  } catch (e) {
    if (e instanceof RevisionFormatInvalid) return { path: e.path }
    throw e
  }
}

const heldOf = (h: any): HeldRevision => ({
  entryId: h.entryId, revision: h.revision, createdAt: new Date(h.createdAt), sealedBody: new Uint8Array(), wrappedRevisionKey: new Uint8Array(),
  digest: h.digest, body: h.body === null ? null : readRevisionBody(h.body), unreadable: h.body === null ? 'body' : null,
})
const strip = ({ v: _v, heads: _h, ...c }: any) => c
const pairs = (m: ReadonlyMap<string, any>, f: (v: any) => unknown = (v) => Number(v)) => [...m].map(([k, v]) => [k, f(v)])
const ledgerJson = (l: PotLedger) => ({
  participants: l.participants,
  entries: l.entries.map((e) => ({
    id: e.id, revision: e.revision, status: e.status, problem: e.problem, edited: e.edited, recordedAt: e.recordedAt.getTime(),
    content: e.content === null ? null : strip(e.content),
    effect: e.effect === null ? null : { base: Number(e.effect.base), paid: pairs(e.effect.paid), received: pairs(e.effect.received), shares: pairs(e.effect.shares) },
  })),
  balances: pairs(l.balances),
  settleUp: l.settleUp.map((t) => ({ from: t.from, to: t.to, amount: Number(t.amount) })),
  overview: {
    byCategory: pairs(l.overview.byCategory),
    byPerson: pairs(l.overview.byPerson, (p) => ({ paid: Number(p.paid), share: Number(p.share) })),
    byCategoryAndPerson: pairs(l.overview.byCategoryAndPerson, (m) => pairs(m)),
  },
})
const big = (n: bigint | null) => (n === null ? null : n.toString())

const answers: Record<string, (c: any) => unknown> = {
  read: (c) => readAnswer(() => readRevisionBody(c.body)),
  decode: (c) => readAnswer(() => decodeRevisionBody(c.hex !== undefined ? new Uint8Array(Buffer.from(c.hex, 'hex')) : new TextEncoder().encode(c.utf8))),
  encode: (c) => {
    try {
      const bytes = encodeRevisionBody(c.body)
      return { length: bytes.length, json: JSON.parse(new TextDecoder().decode(bytes)) }
    } catch (e) {
      if (e instanceof RevisionFormatInvalid) return { path: e.path }
      throw e
    }
  },
  check: (c) => {
    const held = (c.held as any[]).map(heldOf)
    return { check: checkLedger(held), currentHeads: currentHeads(held).map((r) => `${r.entryId}:${r.revision}`), headsToName: headsToName(held) }
  },
  entryProblem: (c) => entryProblem(c.content as MoneyContent, c.participants ? (id) => (c.participants as string[]).includes(id) : undefined),
  splitBreaks: (c) => splitBreaks(c.problem, c.split, c.part === null ? null : BigInt(c.part)),
  named: (c) => namedParticipants(c.content as MoneyContent),
  ledger: (c) => ledgerJson(buildLedger((c.held as any[]).map(heldOf), c.base)),
  settleUp: (c) =>
    settleUp(new Map((c.balances as [string, number][]).map(([k, v]) => [k, BigInt(v)])), c.order).map((t) => ({ from: t.from, to: t.to, amount: Number(t.amount) })),
  amounts: (c) => {
    switch (c.op) {
      case 'parseAmount':
        return big(parseAmount(c.text, c.minorUnits))
      case 'decimalOf':
        return decimalOf(BigInt(c.amount), c.minorUnits)
      case 'amountText':
        return amountText(BigInt(c.amount), c.minorUnits, c.language)
      case 'parseRate':
        return parseRate(c.text)
      case 'formatMoney':
        return formatMoney(BigInt(c.amount), c.currency, c.minorUnits, c.language)
    }
    throw new Error(`no op ${c.op}`)
  },
  rates: (c) => (c.op === 'parseTable' ? parseTable(c.answer ?? JSON.parse(c.text), new Set(c.known)) : crossRate(c.table, c.from, c.to)),
}

describe('the Expenses vectors (contracts/expenses/v1-vectors.json)', () => {
  for (const [section, answer] of Object.entries(answers)) {
    describe(section, () => {
      it('has cases', () => expect((vectors[section] as unknown[]).length).toBeGreaterThan(0))
      ;(vectors[section] as any[]).forEach((c, i) => {
        // compared as JSON, as the file holds it: a -0 the reader passes on is the 0 the file writes
        it(`#${i} ${c.rule}`, () => expect(JSON.parse(JSON.stringify(answer(c)) ?? 'null')).toEqual(c.expected))
      })
    })
  }
})
