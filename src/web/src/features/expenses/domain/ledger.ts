// What the client computes from a Pot's entries (ADR 0108 decision 9, docs/apps/expenses.md § What the client computes):
// each entry's effect in the base currency, the balances, the settle-up suggestion and the overviews — every rule about
// money runs here, on the device, because the server holds only ciphertext. `contracts/expenses/v1-vectors.json` pins the
// arithmetic; `ledger.test.ts` runs every case of it through this file.
//
// **The unbalanced-entry rule** (owner 2026-10-24): an entry whose sums do not add up — payers' or receivers' amounts
// beside the total, items above it, a rest above zero with no split, percents beside 100.00 %, a split's amounts beside the
// amount of the part they split, a person named twice — is
// never saved by this client
// ({@link entryProblem}, which the write path asks first), and one that arrives anyway counts in NO balance, NO overview
// and NO settle-up, and is shown as invalid. So is one naming a person who is not a participant of the Pot: it has no
// place in the join order the rounding breaks ties by. The server cannot check any of it; it sees only ciphertext.
import { allocate, convertToBase, fraction } from './money'
import { isMoney, PERCENT_WHOLE, type Allocation, type MoneyContent, type RevisionContent, type Split } from './format'
import { byRecord, type HeldRevision } from './revisions'

/** The Pot's base currency, from its sealed meta: what every balance is in. */
export type BaseCurrency = { currency: string; minorUnits: number }

/** A participant of the Pot, as its record's latest revision says. An erased one (deleted by a member) keeps its id and
 *  its join order and has no name; the screen shows every erased one as one "deleted people". `member` is the user id of
 *  the member it is (ADR 0108 decision 2), or null for a name — decided by the record's FIRST revision, never a later one
 *  ({@link participantMemberOf}); `name` is the record's own (for a member, the display name sealed at adding). */
export type Participant = { id: string; name: string | null; active: boolean; joined: number; erased: boolean; member: string | null }

/**
 * The member a participant record is, from its revisions ascending: the `member` of its earliest revision that opened to a
 * participant, or null. A participant never changes kind (ADR 0108 decision 2), so a later revision that adds, drops or
 * changes the field — an older client that did not know it, or a writer turning someone else's name into their own
 * account — moves nothing; this client's writer carries the first's unchanged (`potApi.ts`' `sealPotWrites`).
 */
export function participantMemberOf(revisions: readonly HeldRevision[]): string | null {
  for (const r of revisions) if (r.body?.kind === 'participant') return r.body.member?.userId ?? null
  return null
}

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

const asc = (a: string, b: string) => (a < b ? -1 : a > b ? 1 : 0)

/** Join order: `joined` (ms), then the record id. The rounding and the settle-up break every tie by it. */
export function joinOrder(participants: readonly Pick<Participant, 'id' | 'joined'>[]): string[] {
  return [...participants].sort((a, b) => a.joined - b.joined || asc(a.id, b.id)).map((p) => p.id)
}

/** Each participant's index in [order] (join order): the `rank` {@link entryEffect} breaks every tie by. */
export const rankOf = (order: readonly string[]): Map<string, number> => new Map(order.map((id, i) => [id, i] as const))

/** Every person a money entry names: payers or receivers, beneficiaries, a transfer's two ends. */
export function namedParticipants(c: MoneyContent): string[] {
  if (c.kind === 'transfer') return [c.from, c.to]
  const who = (c.kind === 'expense' ? c.payers : c.receivers).map((p) => p.participant)
  return [...who, ...allocationPeople(c.allocation)]
}

/** The splits an allocation holds: each item's, then the rest's where one is sealed (checked even at a zero rest). */
const splitsOf = (a: Allocation): Split[] => [...a.items.map((i) => i.split), ...(a.rest ? [a.rest] : [])]

const total = (xs: readonly { amount: number }[]): bigint => xs.reduce((acc, x) => acc + BigInt(x.amount), 0n)
const twice = (ids: readonly string[]): boolean => new Set(ids).size !== ids.length

/** The people a split names, beside their weights (1 each, their shares, their basis points, their amounts), in the
 *  split's order. */
function splitWeights(s: Split): { id: string; weight: bigint }[] {
  switch (s.mode) {
    case 'equal':
      return s.participants.map((id) => ({ id, weight: 1n }))
    case 'shares':
      return s.shares.map((x) => ({ id: x.participant, weight: BigInt(x.shares) }))
    case 'percent':
      return s.percents.map((x) => ({ id: x.participant, weight: BigInt(x.percent) }))
    case 'amounts':
      return s.amounts.map((x) => ({ id: x.participant, weight: BigInt(x.amount) }))
  }
}

/** The people a split names, in its order. */
export const splitPeople = (s: Split): string[] => splitWeights(s).map((w) => w.id)

/**
 * Whether one split breaks [rule]: names a person twice, has percents beside 100.00 %, or amounts beside [part] — the
 * amount of the part it splits (null: it splits nothing, a rest split sealed at a zero rest, held to no sum).
 * {@link entryProblem} asks it of every split; the editor (`../entryForm.ts`) asks it to say which split is wrong.
 */
export function splitBreaks(rule: 'named-twice' | 'percent-unbalanced' | 'amounts-unbalanced', s: Split, part: bigint | null): boolean {
  switch (rule) {
    case 'named-twice':
      return twice(splitPeople(s))
    case 'percent-unbalanced':
      return s.mode === 'percent' && s.percents.reduce((acc, x) => acc + x.percent, 0) !== PERCENT_WHOLE
    case 'amounts-unbalanced':
      return s.mode === 'amounts' && part !== null && total(s.amounts) !== part
  }
}

function allocationPeople(a: Allocation): string[] {
  return [...new Set(splitsOf(a).flatMap(splitPeople))]
}

/** `amount - sum(items)`: what the rest split gets (negative: items above the total). */
export const restOf = (c: { amount: number; allocation: Allocation }): bigint => BigInt(c.amount) - total(c.allocation.items)

/**
 * Why a money entry may not count — or be saved — or null when its sums add up. [isParticipant] tells whether an id is a
 * participant of the Pot (omit it to check the sums alone). The write path refuses any entry this answers for.
 */
export function entryProblem(c: MoneyContent, isParticipant?: (id: string) => boolean): EntryProblem | null {
  if (c.kind !== 'transfer') {
    const contributors = c.kind === 'expense' ? c.payers : c.receivers
    if (twice(contributors.map((p) => p.participant))) return 'named-twice'
    if (total(contributors) !== BigInt(c.amount)) return c.kind === 'expense' ? 'payers-unbalanced' : 'receivers-unbalanced'
    const splits = splitsOf(c.allocation)
    if (splits.some((s) => splitBreaks('named-twice', s, null))) return 'named-twice'
    if (splits.some((s) => splitBreaks('percent-unbalanced', s, null))) return 'percent-unbalanced'
    const rest = restOf(c)
    if (rest < 0n) return 'items-above-total'
    if (rest > 0n && !c.allocation.rest) return 'rest-unallocated'
    const parts = [...c.allocation.items.map((i) => [i.split, BigInt(i.amount)] as const), ...(c.allocation.rest ? [[c.allocation.rest, rest > 0n ? rest : null] as const] : [])]
    if (parts.some(([s, part]) => splitBreaks('amounts-unbalanced', s, part))) return 'amounts-unbalanced'
  }
  if (isParticipant && namedParticipants(c).some((id) => !isParticipant(id))) return 'unknown-participant'
  return null
}

/** `amount` allocated among [people] by their weights (largest remainder), the people taken in join order ([rank]); each
 *  person's share is added to what [into] holds for them. */
function allocateInto(into: Map<string, bigint>, amount: bigint, people: readonly { id: string; weight: bigint }[], rank: Map<string, number>) {
  const ordered = [...people].sort((a, b) => rank.get(a.id)! - rank.get(b.id)!)
  const shares = allocate(amount, ordered.map((p) => fraction(p.weight)))
  ordered.forEach((p, i) => into.set(p.id, (into.get(p.id) ?? 0n) + shares[i]!))
}

/**
 * Each beneficiary's share of a valid entry's base total (the schema's description): the total allocated to the entry's
 * parts — each item in its order, then the rest when it is above zero — by their amounts in the entry's currency, then
 * each part's base amount among its split's people by their weights, in join order, a person's parts summed. Where the
 * currency is the base, a part's weight IS its base amount, so the first step hands every part exactly its own amount.
 */
function beneficiaryShares(c: Exclude<MoneyContent, { kind: 'transfer' }>, baseTotal: bigint, rank: Map<string, number>): Map<string, bigint> {
  const parts = c.allocation.items.map((i) => ({ amount: BigInt(i.amount), split: i.split }))
  const rest = restOf(c)
  if (rest > 0n) parts.push({ amount: rest, split: c.allocation.rest! })
  const partBase = allocate(baseTotal, parts.map((p) => fraction(p.amount)))
  const shares = new Map<string, bigint>()
  parts.forEach((p, i) => allocateInto(shares, partBase[i]!, splitWeights(p.split), rank))
  return new Map([...shares].sort((x, y) => rank.get(x[0])! - rank.get(y[0])!)) // in join order, as every list of the Pot
}

/**
 * A valid money entry's effect in the base currency: its total converted once, then allocated — payers (or receivers) by
 * the amounts they gave, beneficiaries part by part ({@link beneficiaryShares}) — each by the largest-remainder rule in
 * join order ([rank]: a participant's index in it). The caller checked [entryProblem] with the Pot's participants first.
 */
export function entryEffect(c: MoneyContent, base: BaseCurrency, rank: Map<string, number>): EntryEffect {
  const baseTotal = convertToBase(BigInt(c.amount), c.minorUnits, c.rate, base.minorUnits)
  const none = new Map<string, bigint>()
  if (c.kind === 'transfer') return { base: baseTotal, paid: none, received: none, shares: none }
  const contributors = c.kind === 'expense' ? c.payers : c.receivers
  const given = new Map<string, bigint>()
  allocateInto(given, baseTotal, contributors.map((p) => ({ id: p.participant, weight: BigInt(p.amount) })), rank)
  const shares = beneficiaryShares(c, baseTotal, rank)
  return c.kind === 'expense'
    ? { base: baseTotal, paid: given, received: none, shares }
    : { base: baseTotal, paid: none, received: given, shares }
}

/** Every participant's balance over the counting entries: paid minus share for an expense, the signs turned for a refund
 *  (its receivers hold the money, its beneficiaries are owed it), and a transfer credits its giver and debits its receiver. */
export function balancesOf(participants: readonly string[], entries: readonly LedgerEntry[]): Map<string, bigint> {
  const balances = new Map<string, bigint>(participants.map((id) => [id, 0n] as const))
  const move = (id: string, by: bigint) => balances.set(id, (balances.get(id) ?? 0n) + by)
  for (const e of entries) {
    if (e.status !== 'live' || !e.effect || !e.content) continue
    const { effect, content } = e
    if (content.kind === 'transfer') {
      move(content.from, effect.base)
      move(content.to, -effect.base)
      continue
    }
    for (const [id, v] of effect.paid) move(id, v)
    for (const [id, v] of effect.received) move(id, -v)
    const sign = content.kind === 'expense' ? -1n : 1n
    for (const [id, v] of effect.shares) move(id, sign * v)
  }
  return balances
}

/**
 * The settle-up suggestion: transfers that bring every balance to zero, greedily — the largest debtor pays the largest
 * creditor the smaller of the two amounts, both chosen AGAIN after every transfer, ties by join order ([order]). At most
 * one transfer fewer than there are people, the same on every device; not always the fewest possible.
 */
export function settleUp(balances: ReadonlyMap<string, bigint>, order: readonly string[]): Transfer[] {
  const open = new Map([...balances].filter(([, v]) => v !== 0n))
  // Walked in join order (anyone outside it after, by id), a strict `>` keeps the earliest of a tie.
  const known = new Set(order)
  const walk = [...order, ...[...open.keys()].filter((id) => !known.has(id)).sort(asc)]
  const largest = (side: 'debtor' | 'creditor'): string | undefined => {
    let best: string | undefined
    let most = 0n
    for (const id of walk) {
      const v = open.get(id)
      if (v === undefined) continue
      const amount = side === 'debtor' ? -v : v
      if (amount > most) [best, most] = [id, amount]
    }
    return best
  }
  const transfers: Transfer[] = []
  for (;;) {
    const debtor = largest('debtor')
    const creditor = largest('creditor')
    if (debtor === undefined || creditor === undefined) break
    const owed = -open.get(debtor)!
    const due = open.get(creditor)!
    const amount = owed < due ? owed : due
    transfers.push({ from: debtor, to: creditor, amount })
    const settle = (id: string, v: bigint) => (v === 0n ? open.delete(id) : open.set(id, v))
    settle(debtor, -owed + amount)
    settle(creditor, due - amount)
  }
  return transfers
}

/** The overviews over the counting expenses and refunds (transfers are not costs). */
export function overviewOf(entries: readonly LedgerEntry[]): Overview {
  const byCategory = new Map<string, bigint>()
  const byPerson = new Map<string, { paid: bigint; share: bigint }>()
  const byCategoryAndPerson = new Map<string, Map<string, bigint>>()
  const person = (id: string) => {
    let p = byPerson.get(id)
    if (!p) byPerson.set(id, (p = { paid: 0n, share: 0n }))
    return p
  }
  for (const e of entries) {
    if (e.status !== 'live' || !e.effect || !e.content || e.content.kind === 'transfer') continue
    const { content, effect } = e
    const sign = content.kind === 'expense' ? 1n : -1n
    byCategory.set(content.category, (byCategory.get(content.category) ?? 0n) + sign * effect.base)
    for (const [id, v] of content.kind === 'expense' ? effect.paid : effect.received) person(id).paid += sign * v
    let cat = byCategoryAndPerson.get(content.category)
    if (!cat) byCategoryAndPerson.set(content.category, (cat = new Map()))
    for (const [id, v] of effect.shares) {
      person(id).share += sign * v
      cat.set(id, (cat.get(id) ?? 0n) + sign * v)
    }
  }
  return { byCategory, byPerson, byCategoryAndPerson }
}

/**
 * The Pot's ledger from every revision held: each record's state is its LATEST revision's — a participant (or an erased
 * one), a money entry (live, deleted, invalid) or unreadable — and the balances, the settle-up and the overviews count the
 * live, valid entries only. A record whose latest revision does not open counts nowhere: falling back to an earlier one
 * would count what the server chose to serve. The ledger check (`heads.ts`) is the caller's to show beside it.
 */
export function buildLedger(held: readonly HeldRevision[], base: BaseCurrency): PotLedger {
  const participants: Participant[] = []
  const money: { id: string; revisions: HeldRevision[] }[] = []
  for (const [id, revisions] of byRecord(held)) {
    const latest = revisions[revisions.length - 1]!.body
    if (latest?.kind === 'participant') participants.push({ id, name: latest.name, active: latest.active, joined: latest.joined, erased: false, member: participantMemberOf(revisions) })
    else if (latest?.kind === 'erasure') participants.push({ id, name: null, active: false, joined: latest.joined, erased: true, member: null })
    else money.push({ id, revisions })
  }
  const order = joinOrder(participants)
  const rank = rankOf(order)
  const isParticipant = (id: string) => rank.has(id)

  const entries: LedgerEntry[] = money.map(({ id, revisions }) => {
    const last = revisions[revisions.length - 1]!
    const common = { id, revision: last.revision, edited: last.revision > 1, recordedAt: revisions[0]!.createdAt }
    const body: RevisionContent | null = last.body
    if (body === null) return { ...common, status: 'unreadable', content: null, problem: null, effect: null }
    if (body.kind === 'deleted') {
      const before = [...revisions].reverse().find((r) => r.body && isMoney(r.body))?.body
      return { ...common, status: 'deleted', content: before && isMoney(before) ? before : null, problem: null, effect: null }
    }
    if (!isMoney(body)) return { ...common, status: 'unreadable', content: null, problem: null, effect: null }
    const problem = entryProblem(body, isParticipant)
    if (problem) return { ...common, status: 'invalid', content: body, problem, effect: null }
    return { ...common, status: 'live', content: body, problem: null, effect: entryEffect(body, base, rank) }
  })
  entries.sort((a, b) => asc(a.id, b.id))

  const balances = balancesOf(order, entries)
  const byId = new Map(participants.map((p) => [p.id, p] as const))
  const overview = overviewOf(entries)
  // The people in join order, as every other list of the Pot: in the order entries first name them they would follow the
  // entries' ids, which an import or a copy mints anew.
  overview.byPerson = new Map(order.filter((id) => overview.byPerson.has(id)).map((id) => [id, overview.byPerson.get(id)!] as const))
  return {
    participants: order.map((id) => byId.get(id)!),
    entries,
    balances,
    settleUp: settleUp(balances, order),
    overview,
  }
}

/** A balance is settled when it is zero — the precondition for a member's deactivate or delete (docs/apps/expenses.md). */
export const isSettled = (ledger: PotLedger, participantId: string): boolean => (ledger.balances.get(participantId) ?? 0n) === 0n
