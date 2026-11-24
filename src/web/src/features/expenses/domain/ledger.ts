// What the client computes from a Pot's entries (ADR 0108 decision 9, docs/apps/expenses.md § What the client computes):
// each entry's effect in the base currency, the balances, the settle-up suggestion and the overviews — every rule about
// money runs on the device, because the server holds only ciphertext. The rules are the Kotlin core's (`expenses/Ledger.kt`,
// through `@veyq/core-js/expenses`, ADR 0155 decision 7.3), the web's and the apps' one, pinned by
// `contracts/expenses/v1-vectors.json`; this file keeps the types the web draws by and hands the core what it asks. It holds
// no rule.
//
// **The unbalanced-entry rule** (owner 2026-10-24): an entry whose sums do not add up, or that names a person twice or one
// who is not a participant of the Pot, is never saved by this client ({@link entryProblem}, which the write path asks
// first), and one that arrives anyway counts in NO balance, NO overview and NO settle-up, and is shown as invalid.
import * as core from '@veyq/core-js/expenses'
import type { Allocation, MoneyContent, Split } from './format'
import { revisionsOf } from './revisions'
import type { HeldRevision } from './revisions'

/** The Pot's base currency, from its sealed meta: what every balance is in. */
export type BaseCurrency = { currency: string; minorUnits: number }

/** A participant of the Pot, as its record's latest revision says. An erased one (deleted by a member) keeps its id and
 *  its join order and has no name; the screen shows every erased one as one "deleted people". `member` is the user id of
 *  the member it is (ADR 0108 decision 2), or null for a name — decided by the record's FIRST revision, never a later one
 *  ({@link participantMemberOf}); `name` is the record's own (for a member, the display name sealed at adding). */
export type Participant = { id: string; name: string | null; active: boolean; joined: number; erased: boolean; member: string | null }

export type EntryProblem =
  | 'payers-unbalanced'
  | 'receivers-unbalanced'
  | 'items-above-total'
  | 'rest-unallocated'
  | 'percent-unbalanced'
  | 'amounts-unbalanced'
  | 'named-twice'
  | 'unknown-participant'

/** One entry's effect, in minor units of the base currency: its converted total, what each payer paid (an expense) or
 *  receiver received (a refund), and each beneficiary's share. A transfer has its total only. */
export type EntryEffect = {
  base: bigint
  paid: Map<string, bigint>
  received: Map<string, bigint>
  shares: Map<string, bigint>
}

/** A money record of the Pot: an expense, a refund or a transfer, as its latest revision leaves it. */
export type LedgerEntry = {
  id: string
  revision: number
  /** `live` counts; `deleted` is in the Pot's deleted list (its content the last one before the delete, for a restore);
   *  `invalid` counts nowhere and is shown as invalid (its `problem` says why); `unreadable` did not open. */
  status: 'live' | 'deleted' | 'invalid' | 'unreadable'
  content: MoneyContent | null
  problem: EntryProblem | null
  effect: EntryEffect | null
  /** It has more than one revision: the entry shows that it was edited. */
  edited: boolean
  /** When the server stored its first revision held: the order of entries recorded on the same date. */
  recordedAt: Date
}

export type Transfer = { from: string; to: string; amount: bigint }

export type Overview = {
  /** Cost per category: expenses' totals, refunds' counted negatively. Transfers are not costs. */
  byCategory: Map<string, bigint>
  /** Per person what they paid (refunds received taken off) beside what their share was (refund shares taken off). */
  byPerson: Map<string, { paid: bigint; share: bigint }>
  /** Each person's share per category. */
  byCategoryAndPerson: Map<string, Map<string, bigint>>
}

export type PotLedger = {
  /** Every participant, live, deactivated or erased, in join order. */
  participants: Participant[]
  entries: LedgerEntry[]
  /** Positive: is owed. Every participant has one, 0 included; their sum is always 0. */
  balances: Map<string, bigint>
  settleUp: Transfer[]
  overview: Overview
}

const content = (c: MoneyContent | { amount: number; allocation: Allocation }) => c as unknown as core.ContentShape
const split = (s: Split) => s as unknown as core.SplitShape

/**
 * The member a participant record is, from its revisions ascending: the `member` of its earliest revision that opened to a
 * participant, or null. A participant never changes kind (ADR 0108 decision 2), so a later revision moves nothing.
 */
export const participantMemberOf = (revisions: readonly HeldRevision[]): string | null =>
  core.participantMemberOf(revisions as unknown as readonly core.HeldShape[]) ?? null

/** Join order: `joined` (ms), then the record id. The rounding and the settle-up break every tie by it. */
export const joinOrder = (participants: readonly Pick<Participant, 'id' | 'joined'>[]): string[] => [...core.joinOrder(participants)]

/** Each participant's index in [order] (join order): the `rank` {@link entryEffect} breaks every tie by. */
export const rankOf = (order: readonly string[]): Map<string, number> => core.rankOf(order) as Map<string, number>

/** Every person a money entry names: payers or receivers, beneficiaries, a transfer's two ends. */
export const namedParticipants = (c: MoneyContent): string[] => [...core.namedParticipants(content(c))]

/** The people a split names, in its order. */
export const splitPeople = (s: Split): string[] => [...core.splitPeople(split(s))]

/** Whether one split breaks [rule]; [part] is the amount of the part it splits (null: it splits nothing). */
export const splitBreaks = (rule: 'named-twice' | 'percent-unbalanced' | 'amounts-unbalanced', s: Split, part: bigint | null): boolean =>
  core.splitBreaks(rule, split(s), part)

/** `amount - sum(items)`: what the rest split gets (negative: items above the total). */
export const restOf = (c: { amount: number; allocation: Allocation }): bigint => core.restOf(content(c))

/** Why a money entry may not count — or be saved — or null when its sums add up; [isParticipant] tells whether an id is a
 *  participant of the Pot (omit it to check the sums alone). */
export const entryProblem = (c: MoneyContent, isParticipant?: (id: string) => boolean): EntryProblem | null =>
  (core.entryProblem(content(c), isParticipant) ?? null) as EntryProblem | null

/** A valid money entry's effect in the base currency, people ranked by [rank] (their index in the join order). */
export const entryEffect = (c: MoneyContent, base: BaseCurrency, rank: Map<string, number>): EntryEffect =>
  core.entryEffect(content(c), base, rank) as unknown as EntryEffect

/** The settle-up of [balances], ties broken by join order. */
export const settleUp = (balances: ReadonlyMap<string, bigint>, order: readonly string[]): Transfer[] => [...core.settleUp(balances, order)]

/** An entry's amount (minor units of its currency) in minor units of the Pot's base currency, rounded a half away from zero. */
export const convertToBase = (amount: bigint, minorUnits: number, rate: string, baseMinorUnits: number): bigint =>
  core.convertToBase(amount, minorUnits, rate, baseMinorUnits)

/**
 * The Pot's ledger from every revision held: each record's state is its LATEST revision's, and the balances, the settle-up
 * and the overviews count the live, valid entries only. The ledger check (`heads.ts`) is the caller's to show beside it.
 */
export const buildLedger = (held: readonly HeldRevision[], base: BaseCurrency): PotLedger =>
  core.buildLedger(revisionsOf(held), base) as unknown as PotLedger

/** A balance is settled when it is zero — the precondition for a member's deactivate or delete (docs/apps/expenses.md). */
export const isSettled = (ledger: PotLedger, participantId: string): boolean => core.isSettled(ledger as unknown as core.LedgerShape, participantId)
