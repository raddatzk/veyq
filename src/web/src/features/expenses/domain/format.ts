// The plaintext of one Expenses revision, format v1 — `contracts/expenses/entry.v1.schema.json` is the source of truth
// (ADR 0108 decisions 3-5, docs/apps/expenses.md). The reader and writer are the Kotlin core's (`expenses/Format.kt`, through
// `@veyq/core-js/expenses`, ADR 0155 decision 7.3), the web's and the apps' one rule, pinned by
// `contracts/expenses/v1-vectors.json`; this file keeps the types the web draws by, the schema's patterns and bounds as the
// core states them, and the refusal as an error class of the web's own. It holds no rule.
//
// What the schema cannot say, and the ledger decides (`ledger.ts`): whether the amounts add up and whether the participants
// an entry names exist. A body valid here may still count in no balance.
import * as core from '@veyq/core-js/expenses'

export const FORMAT_VERSION = 1

/** A default category (docs/apps/expenses.md § The Pot): a stable id the client translates. A Pot's own are uuids. */
export type DefaultCategory = 'food' | 'groceries' | 'accommodation' | 'transport' | 'leisure' | 'household' | 'other'

/** One revision the Pot's heads name (ADR 0108 decision 5): its address and the SHA-256 of its sealed body. */
export type Head = { entryId: string; revision: number; digest: string }

export type Contribution = { participant: string; amount: number }

/** Who one part of an entry is for: each person 1, their shares, their percent in basis points (10000 = 100.00 %), or a
 *  fixed amount each in the entry's currency, summing to the part's amount. */
export type Split =
  | { mode: 'equal'; participants: string[] }
  | { mode: 'shares'; shares: { participant: string; shares: number }[] }
  | { mode: 'percent'; percents: { participant: string; percent: number }[] }
  | { mode: 'amounts'; amounts: { participant: string; amount: number }[] }

/** An item of an entry: an exact amount in the entry's currency, with its own split. */
export type Item = { label: string; amount: number; split: Split }

/** Who the amount is for: its items, and the rest (`amount - sum(items)`) by a split of its own — present while the rest is
 *  above zero, ignored at zero. No items: the whole amount is rest. */
export type Allocation = { items: Item[]; rest?: Split }

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
/** A participant that is a member of the space (ADR 0108 decision 2): their user id. The record's `name` beside it is their
 *  display name as it was when they were added, sealed with it — what a reader without the member directory (a link
 *  guest, an older client) shows. */
export type ParticipantMember = { userId: string }
/** A person of the Pot: a name, or a member with the name sealed at adding (`member`). A participant never changes kind:
 *  its first revision decides it for the record's life (`ledger.ts`' `buildLedger`, `potApi.ts`' `sealPotWrites`). */
export type ParticipantBody = { kind: 'participant'; name: string; active: boolean; joined: number; member?: ParticipantMember }
export type DeletedBody = { kind: 'deleted' }
export type ErasureBody = { kind: 'erasure'; joined: number }

/** What a revision says, without the `v` and `heads` every body carries. */
export type RevisionContent = ExpenseBody | RefundBody | TransferBody | ParticipantBody | DeletedBody | ErasureBody
export type MoneyContent = ExpenseBody | RefundBody | TransferBody

/** One revision's whole plaintext. */
export type RevisionBody = RevisionContent & { v: 1; heads: Head[] }

// --- the schema's patterns and bounds, the core's (`formatRules`) -------------------------------------------------
const rules = core.formatRules()
export const UUID = new RegExp(rules.uuid)
export const DIGEST = new RegExp(rules.digest)
export const CURRENCY = new RegExp(rules.currency)
export const DATE = new RegExp(rules.date)
export const RATE = new RegExp(rules.rate)
export const MAX_HEADS: number = rules.maxHeads
/** The most receipts one revision lists (the schema's `maxItems`). */
export const MAX_RECEIPTS: number = rules.maxReceipts
/** 100.00 %: a `percent` split's basis points sum to exactly this (a rule of the ledger's, not the schema's). */
export const PERCENT_WHOLE: number = rules.percentWhole
/** The default categories, in the core's order. */
export const DEFAULT_CATEGORIES = rules.defaultCategories as readonly DefaultCategory[]

/** Why a body is not a v1 revision: the first rule it breaks, by the schema's path. */
export class RevisionFormatInvalid extends Error {
  constructor(readonly path: string) {
    super(`revision_format_invalid: ${path}`)
    this.name = 'RevisionFormatInvalid'
  }
}

/** The core's refusal as the web's own error class; anything else as it was thrown. */
export function formatRefusal(e: unknown): unknown {
  return e instanceof Error && e.name === 'RevisionFormatInvalid' ? new RevisionFormatInvalid((e as Error & { path: string }).path) : e
}

/**
 * A parsed plaintext held to the v1 schema: the body with only the fields the schema names (an unknown one is dropped), or
 * [RevisionFormatInvalid] at the first rule it breaks. A `v` other than 1 is refused as `/v`.
 */
export function readRevisionBody(parsed: unknown): RevisionBody {
  try {
    return core.readRevisionBody(parsed as core.JsonValueShape) as unknown as RevisionBody
  } catch (e) {
    throw formatRefusal(e)
  }
}

export const isMoney = (c: RevisionContent): c is MoneyContent => core.isMoney(c as unknown as core.ContentShape)
