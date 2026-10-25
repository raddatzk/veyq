// The plaintext of one Expenses revision, format v1 — `contracts/expenses/entry.v1.schema.json` is the source of truth
// (ADR 0108 decisions 3-5, docs/apps/expenses.md). This file is its reader and writer: the types, a validator that holds
// a parsed body to the schema (`format.test.ts` checks every pattern here against the schema's own), and the compact
// JSON a writer seals. Readers ignore unknown fields — a field added under `v: 1` is additive — and a body of any other
// `v` is one this client cannot read.
//
// What the schema cannot say, and `ledger.ts` decides: whether the amounts add up (the unbalanced-entry rule) and whether
// the participants an entry names exist. A body valid here may still count in no balance.
import { RATE } from './money'

export const FORMAT_VERSION = 1

/** The default categories (docs/apps/expenses.md § The Pot): stable ids the client translates. A Pot's own are uuids. */
export const DEFAULT_CATEGORIES = ['food', 'groceries', 'accommodation', 'transport', 'leisure', 'household', 'other'] as const
export type DefaultCategory = (typeof DEFAULT_CATEGORIES)[number]

/** One revision the Pot's heads name (ADR 0108 decision 5): its address and the SHA-256 of its sealed body. */
export type Head = { entryId: string; revision: number; digest: string }

export type Contribution = { participant: string; amount: number }

export type Allocation =
  | { mode: 'equal'; participants: string[] }
  | { mode: 'shares'; shares: { participant: string; shares: number }[] }
  | { mode: 'exact'; amounts: Contribution[] }
  | { mode: 'items'; items: { label: string; amount: number; participants: string[] }[]; rest: 'proportional' | 'equal' }

type Money = { date: string; currency: string; minorUnits: number; rate: string; amount: number; receipts: string[] }

export type ExpenseBody = Money & { kind: 'expense'; title: string; category: string; payers: Contribution[]; allocation: Allocation }
export type RefundBody = Money & {
  kind: 'refund'
  title: string
  category: string
  receivers: Contribution[]
  allocation: Allocation
  refunds?: string
}
export type TransferBody = Money & { kind: 'transfer'; title?: string; from: string; to: string }
export type ParticipantBody = { kind: 'participant'; name: string; active: boolean; joined: number }
export type DeletedBody = { kind: 'deleted' }
export type ErasureBody = { kind: 'erasure'; joined: number }

/** What a revision says, without the `v` and `heads` every body carries. */
export type RevisionContent = ExpenseBody | RefundBody | TransferBody | ParticipantBody | DeletedBody | ErasureBody
export type MoneyContent = ExpenseBody | RefundBody | TransferBody

/** One revision's whole plaintext. */
export type RevisionBody = RevisionContent & { v: 1; heads: Head[] }

// --- the schema's patterns and bounds (pinned against the schema file by format.test.ts) ---------------------------
export const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
export const DIGEST = /^[A-Za-z0-9_-]{43}$/
export const CURRENCY = /^[A-Z]{3}$/
export const DATE = /^[0-9]{4}-(?:0[1-9]|1[0-2])-(?:0[1-9]|[12][0-9]|3[01])$/
export { RATE }

export const MAX_HEADS = 64
const MAX_SAFE = Number.MAX_SAFE_INTEGER
const MAX_LIST = 200
/** The most receipts one revision lists (the schema's `maxItems`). */
export const MAX_RECEIPTS = 100
const MAX_SHARES = 1_000_000

/** Why a body is not a v1 revision: the first rule it breaks, by the schema's path. */
export class RevisionFormatInvalid extends Error {
  constructor(readonly path: string) {
    super(`revision_format_invalid: ${path}`)
    this.name = 'RevisionFormatInvalid'
  }
}

type Obj = Record<string, unknown>
const fail = (path: string): never => {
  throw new RevisionFormatInvalid(path)
}
const obj = (v: unknown, path: string): Obj => (typeof v === 'object' && v !== null && !Array.isArray(v) ? (v as Obj) : fail(path))
const str = (v: unknown, path: string, pattern?: RegExp, min = 0, max = Infinity): string => {
  if (typeof v !== 'string' || [...v].length < min || [...v].length > max || (pattern && !pattern.test(v))) fail(path)
  return v as string
}
const int = (v: unknown, path: string, min: number, max: number): number =>
  typeof v === 'number' && Number.isInteger(v) && v >= min && v <= max ? v : fail(path)
const arr = (v: unknown, path: string, min: number, max: number): unknown[] =>
  Array.isArray(v) && v.length >= min && v.length <= max ? v : fail(path)
const uniq = (xs: string[], path: string): string[] => (new Set(xs).size === xs.length ? xs : fail(path))

const uuid = (v: unknown, path: string) => str(v, path, UUID)
const minor = (v: unknown, path: string) => int(v, path, 1, MAX_SAFE)
const uuids = (v: unknown, path: string) => uniq(arr(v, path, 1, MAX_LIST).map((x, i) => uuid(x, `${path}/${i}`)), path)

function contribution(v: unknown, path: string): Contribution {
  const o = obj(v, path)
  return { participant: uuid(o.participant, `${path}/participant`), amount: minor(o.amount, `${path}/amount`) }
}
const contributions = (v: unknown, path: string) => arr(v, path, 1, MAX_LIST).map((x, i) => contribution(x, `${path}/${i}`))

function category(v: unknown, path: string): string {
  if (typeof v === 'string' && ((DEFAULT_CATEGORIES as readonly string[]).includes(v) || UUID.test(v))) return v
  return fail(path)
}

function allocation(v: unknown, path: string): Allocation {
  const o = obj(v, path)
  switch (o.mode) {
    case 'equal':
      return { mode: 'equal', participants: uuids(o.participants, `${path}/participants`) }
    case 'shares':
      return {
        mode: 'shares',
        shares: arr(o.shares, `${path}/shares`, 1, MAX_LIST).map((x, i) => {
          const s = obj(x, `${path}/shares/${i}`)
          return { participant: uuid(s.participant, `${path}/shares/${i}/participant`), shares: int(s.shares, `${path}/shares/${i}/shares`, 1, MAX_SHARES) }
        }),
      }
    case 'exact':
      return { mode: 'exact', amounts: contributions(o.amounts, `${path}/amounts`) }
    case 'items': {
      const rest = o.rest === 'proportional' || o.rest === 'equal' ? o.rest : fail(`${path}/rest`)
      const items = arr(o.items, `${path}/items`, 1, MAX_LIST).map((x, i) => {
        const it = obj(x, `${path}/items/${i}`)
        return {
          label: str(it.label, `${path}/items/${i}/label`, undefined, 1, 200),
          amount: minor(it.amount, `${path}/items/${i}/amount`),
          participants: uuids(it.participants, `${path}/items/${i}/participants`),
        }
      })
      return { mode: 'items', items, rest }
    }
    default:
      return fail(`${path}/mode`)
  }
}

function money(o: Obj): Money {
  return {
    date: str(o.date, '/date', DATE),
    currency: str(o.currency, '/currency', CURRENCY),
    minorUnits: int(o.minorUnits, '/minorUnits', 0, 4),
    rate: str(o.rate, '/rate', RATE),
    amount: minor(o.amount, '/amount'),
    receipts: uniq(arr(o.receipts, '/receipts', 0, MAX_RECEIPTS).map((x, i) => uuid(x, `/receipts/${i}`)), '/receipts'),
  }
}

const title = (v: unknown, path = '/title') => str(v, path, undefined, 1, 200)

function content(o: Obj): RevisionContent {
  switch (o.kind) {
    case 'expense':
      return { kind: 'expense', title: title(o.title), category: category(o.category, '/category'), ...money(o), payers: contributions(o.payers, '/payers'), allocation: allocation(o.allocation, '/allocation') }
    case 'refund': {
      const body: RefundBody = { kind: 'refund', title: title(o.title), category: category(o.category, '/category'), ...money(o), receivers: contributions(o.receivers, '/receivers'), allocation: allocation(o.allocation, '/allocation') }
      if (o.refunds !== undefined) body.refunds = uuid(o.refunds, '/refunds')
      return body
    }
    case 'transfer': {
      const body: TransferBody = { kind: 'transfer', ...money(o), from: uuid(o.from, '/from'), to: uuid(o.to, '/to') }
      if (o.title !== undefined) body.title = title(o.title)
      return body
    }
    case 'participant':
      return {
        kind: 'participant',
        name: str(o.name, '/name', undefined, 1, 100),
        active: typeof o.active === 'boolean' ? o.active : fail('/active'),
        joined: int(o.joined, '/joined', 0, MAX_SAFE),
      }
    case 'deleted':
      return { kind: 'deleted' }
    case 'erasure':
      return { kind: 'erasure', joined: int(o.joined, '/joined', 0, MAX_SAFE) }
    default:
      return fail('/kind')
  }
}

function head(v: unknown, path: string): Head {
  const o = obj(v, path)
  return { entryId: uuid(o.entryId, `${path}/entryId`), revision: int(o.revision, `${path}/revision`, 1, MAX_SAFE), digest: str(o.digest, `${path}/digest`, DIGEST) }
}

/**
 * A parsed plaintext held to the v1 schema: the body with only the fields the schema names (an unknown one is dropped,
 * so nothing a reader does not understand travels further), or [RevisionFormatInvalid] at the first rule it breaks. A `v`
 * other than 1 is refused as `/v`: this client cannot tell what a later format means.
 */
export function readRevisionBody(parsed: unknown): RevisionBody {
  const o = obj(parsed, '')
  if (o.v !== FORMAT_VERSION) fail('/v')
  const heads = arr(o.heads, '/heads', 0, MAX_HEADS).map((h, i) => head(h, `/heads/${i}`))
  return { v: 1, heads, ...content(o) }
}

/** The bytes of a revision's plaintext: compact UTF-8 JSON, held to the schema first — a writer never seals a body its
 *  own reader would refuse. The key order is the object's, which carries no meaning: a reader parses, never compares. */
export function encodeRevisionBody(body: RevisionBody): Uint8Array {
  const checked = readRevisionBody(JSON.parse(JSON.stringify(body)))
  return new TextEncoder().encode(JSON.stringify(checked))
}

/** The plaintext bytes back to a body; [RevisionFormatInvalid] for anything that is not UTF-8 JSON of the v1 schema. */
export function decodeRevisionBody(bytes: Uint8Array): RevisionBody {
  let parsed: unknown
  try {
    parsed = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes))
  } catch {
    return fail('')
  }
  return readRevisionBody(parsed)
}

export const isMoney = (c: RevisionContent): c is MoneyContent => c.kind === 'expense' || c.kind === 'refund' || c.kind === 'transfer'
