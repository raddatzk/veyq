// The entry editor's form and its translation to and from the format v1 body (contracts/expenses/entry.v1.schema.json):
// what a person types — amounts as text, a typed rate, the payers and the items with their splits — and the exact content a
// write seals. Pure, so the editor (`components/EntryEditor.vue`) holds no rule of its own: what may be saved is decided
// here and by `ledger.ts`'s `entryProblem` (the unbalanced-entry rule, owner 2026-10-24), which the write path asks again
// before sealing. What cannot be saved names the field it is at ({@link ProblemAt}), so the editor says it there.
//
// An expense's (a refund's) amount is never typed: it is the sum of what its payers (receivers) paid, every row with an
// amount of its own. Its allocation is the items, each an exact amount with its own split, and the rest — the amount less
// the items — by a split of its own; a new entry has no items, so the whole amount is rest, split equally among everyone
// offered (the Pot's active people), and those people are sealed by name in the rest's split.
//
// A split is typed as people in rows, each with a value per mode — shares, a percent, an amount in the entry's currency
// (owner 2026-11-03). "Equally" is no mode of the editor's: shares of 1 each are sealed as the format's `equal` split — the
// same weights, the form every client already reads for the everyday case, and the one an entry opened again shows as its
// one line "everyone, equally" (`expanded` false) when it names everyone offered.
import { amountText, minorUnitsOf, parseAmount, parseRate } from './amounts'
import { PERCENT_WHOLE, type Allocation, type Contribution, type Item, type MoneyContent, type Split } from './domain/format'
import { entryEffect, entryProblem, rankOf, restOf, splitBreaks, splitPeople, type BaseCurrency, type EntryEffect, type EntryProblem } from './domain/ledger'

export type EntryKind = MoneyContent['kind']
/** The editor's split modes. Shares of 1 each are sealed as `equal`, which therefore needs no mode of its own. */
export type SplitMode = 'shares' | 'percent' | 'amounts'

/** One person of a split as typed: who, and what was typed for them in each mode — switching back and forth loses
 *  nothing; only the picked mode's values are sealed. */
export type FormSplitRow = { participant: string; shares: string; percent: string; amount: string }

/** One split as it is typed: its mode and its people, one row each. */
export interface FormSplit {
  mode: SplitMode
  rows: FormSplitRow[]
  /** False: the editor shows it as one line, "everyone, equally", with a button to adjust it — a new split, or one opened
   *  equal over everyone offered; true: the modes and the rows. Nothing of it is sealed. */
  expanded: boolean
}

export type FormItem = { label: string; amount: string; split: FormSplit }

export interface EntryForm {
  kind: EntryKind
  title: string
  /** `YYYY-MM-DD`. */
  date: string
  category: string
  currency: string
  /** The currency's minor-unit digits; the entry seals them. */
  minorUnits: number
  /** The typed rate to the base currency (`1` while the currency is the base). */
  rate: string
  /** A transfer's amount, as typed. An expense's or a refund's is the sum of its payers' rows ({@link payersTotal}). */
  amount: string
  /** An expense's payers or a refund's receivers, each with the amount they paid or received. */
  payers: { participant: string; amount: string }[]
  /** The items, each an exact amount with its own split. */
  items: FormItem[]
  /** The split of the rest (the amount less the items), sealed only while the rest is above zero. */
  rest: FormSplit
  /** A transfer's two ends. */
  from: string
  to: string
  /** The receipt ids the revision lists: the edited revision's, less the removed ones, plus those added in the dialog
   *  (`EntryEditor`; docs/apps/expenses.md § Receipts). */
  receipts: string[]
  refunds?: string
}

/** Why the form cannot be saved: a field the person must fix (an i18n key under `expenses.entry.problem`), or one of the
 *  ledger's rules. */
export type FormProblem =
  | 'title'
  | 'date'
  | 'amount'
  | 'rate'
  | 'payers'
  | 'split'
  | 'split-shares'
  | 'split-percents'
  | 'split-amounts'
  | 'items'
  | 'transfer'
  | EntryProblem

/** Where a problem is, so the editor says it at that field: the title, the date, the rate, a transfer's amount, the payers
 *  (receivers), a transfer's two ends, the items as a whole (above the amount), the rest's split, or the item at that
 *  index. None: it belongs to no field. */
export type ProblemAt = 'title' | 'date' | 'rate' | 'amount' | 'payers' | 'transfer' | 'items' | 'rest' | number

export function today(): string {
  const d = new Date()
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

/** A person's row of a split: one share, nothing typed for the other modes. */
export const splitRow = (participant: string): FormSplitRow => ({ participant, shares: '1', percent: '', amount: '' })

/** A split equally among [people] — shares of 1 each, sealed as `equal` — shown as its one line unless [expanded]. */
export function equalSplit(people: readonly string[], expanded = false): FormSplit {
  return { mode: 'shares', rows: people.map(splitRow), expanded }
}

/** [split] with every one of [everyone] on it at 1 share and no one else ("split equally among all"); what was typed for
 *  them in the other modes is kept. */
export function everyoneEqually(split: FormSplit, everyone: readonly string[]): FormSplit {
  const rows = everyone.map((id) => ({ ...(split.rows.find((r) => r.participant === id) ?? splitRow(id)), shares: '1' }))
  return { mode: 'shares', rows, expanded: true }
}

/** A new entry's form: an expense in the base currency, paid by [payer] (the first person offered), with no items, so the
 *  whole amount is rest, split equally among [people]. */
export function emptyForm(base: BaseCurrency, people: readonly string[], payer: string | undefined = people[0]): EntryForm {
  return {
    kind: 'expense',
    title: '',
    date: today(),
    category: 'other',
    currency: base.currency,
    minorUnits: base.minorUnits,
    rate: '1',
    amount: '',
    payers: [{ participant: payer ?? '', amount: '' }],
    items: [],
    rest: equalSplit(people),
    from: payer ?? '',
    to: people.find((p) => p !== payer) ?? '',
    receipts: [],
  }
}

/** A new item: no label, no amount, split equally among [people] (its one line, as a new entry's rest). */
export function emptyItem(people: readonly string[]): FormItem {
  return { label: '', amount: '', split: equalSplit(people) }
}

const text = (amount: number, minorUnits: number, locale: string) => amountText(BigInt(amount), minorUnits, locale)

/** Basis points as a percent is typed: `3333` → `33.33` (the reader's separator). */
export const percentText = (basisPoints: number, locale: string): string => amountText(BigInt(basisPoints), 2, locale)

/** The same people, each once, in any order. */
const samePeople = (a: readonly string[], b: readonly string[]) => a.length === b.length && new Set(a).size === a.length && a.every((id) => b.includes(id))

/** A sealed split as the editor shows it: `equal` over everyone in [people] as its one line, anything else as rows. */
function formSplitOf(s: Split, people: readonly string[], minorUnits: number, locale: string): FormSplit {
  switch (s.mode) {
    case 'equal':
      return equalSplit(s.participants, !samePeople(s.participants, people))
    case 'shares':
      return { mode: 'shares', rows: s.shares.map((x) => ({ ...splitRow(x.participant), shares: String(x.shares) })), expanded: true }
    case 'percent':
      return { mode: 'percent', rows: s.percents.map((x) => ({ ...splitRow(x.participant), percent: percentText(x.percent, locale) })), expanded: true }
    case 'amounts':
      return { mode: 'amounts', rows: s.amounts.map((x) => ({ ...splitRow(x.participant), amount: text(x.amount, minorUnits, locale) })), expanded: true }
  }
}

/** The form of an existing entry's [content], to edit it; [people] are those it offers (`people.ts`' `offeredPeople`). A
 *  rest of zero has no split sealed; the form offers the equal one among [people] for it, should the items shrink. */
export function formOf(content: MoneyContent, base: BaseCurrency, people: readonly string[], locale: string): EntryForm {
  const form = emptyForm(base, people)
  const mu = content.minorUnits
  Object.assign(form, {
    kind: content.kind,
    title: content.title ?? '',
    date: content.date,
    currency: content.currency,
    minorUnits: mu,
    rate: content.rate,
    amount: text(content.amount, mu, locale),
    receipts: [...content.receipts],
  })
  if (content.kind === 'transfer') {
    form.from = content.from
    form.to = content.to
    return form
  }
  form.category = content.category
  if (content.kind === 'refund' && content.refunds) form.refunds = content.refunds
  form.payers = (content.kind === 'expense' ? content.payers : content.receivers).map((p) => ({ participant: p.participant, amount: text(p.amount, mu, locale) }))
  form.items = content.allocation.items.map((i) => ({ label: i.label, amount: text(i.amount, mu, locale), split: formSplitOf(i.split, people, mu, locale) }))
  if (content.allocation.rest) form.rest = formSplitOf(content.allocation.rest, people, mu, locale)
  return form
}

/** Set the form's currency: its minor units from `Intl` (an entry being edited keeps the digits it was written with while
 *  its currency stays), the rate back to `1` for the base and left to be typed for any other. */
export function withCurrency(form: EntryForm, currency: string, base: BaseCurrency): EntryForm {
  if (currency === form.currency) return form
  const minorUnits = currency === base.currency ? base.minorUnits : minorUnitsOf(currency)
  return { ...form, currency, minorUnits, rate: currency === base.currency ? '1' : '' }
}

/** Switch the form's kind, carrying the amount across: a transfer's empty amount takes the payers' total, and a lone payer
 *  with no amount takes the transfer's. */
export function withKind(form: EntryForm, kind: EntryKind, locale: string): EntryForm {
  if (kind === form.kind) return form
  const next = { ...form, kind }
  if (kind === 'transfer' && form.amount.trim() === '') {
    const total = payersTotal(form)
    if (total > 0n) next.amount = amountText(total, form.minorUnits, locale)
  } else if (kind !== 'transfer' && form.kind === 'transfer' && form.payers.length === 1 && form.payers[0]!.amount.trim() === '') {
    next.payers = [{ ...form.payers[0]!, amount: form.amount }]
  }
  return next
}

const SAFE = BigInt(Number.MAX_SAFE_INTEGER)

/** A typed percent in basis points (`33,33` → 3333), or null past two decimals, at zero or above 100 %. */
export function parsePercent(typed: string): number | null {
  const v = parseAmount(typed, 2)
  return v !== null && v <= BigInt(PERCENT_WHOLE) ? Number(v) : null
}

/** The rows of a split that name a person (a row whose person is not picked yet counts nowhere). */
const named = (s: FormSplit): FormSplitRow[] => s.rows.filter((r) => r.participant)

/** The split as the format seals it, or why it cannot be: it names no one, or a value of its mode is none. Shares of 1
 *  each are sealed as `equal`. */
function splitOf(s: FormSplit, minorUnits: number): { split: Split } | { problem: 'empty' | 'split-shares' | 'split-percents' | 'split-amounts' } {
  const rows = named(s)
  if (!rows.length) return { problem: 'empty' }
  switch (s.mode) {
    case 'shares': {
      const shares: { participant: string; shares: number }[] = []
      for (const r of rows) {
        const typed = r.shares.trim()
        const n = Number(typed)
        if (!/^[0-9]+$/.test(typed) || n < 1 || n > 1_000_000) return { problem: 'split-shares' }
        shares.push({ participant: r.participant, shares: n })
      }
      return shares.every((x) => x.shares === 1)
        ? { split: { mode: 'equal', participants: shares.map((x) => x.participant) } }
        : { split: { mode: 'shares', shares } }
    }
    case 'percent': {
      const percents: { participant: string; percent: number }[] = []
      for (const r of rows) {
        const n = parsePercent(r.percent)
        if (n === null) return { problem: 'split-percents' }
        percents.push({ participant: r.participant, percent: n })
      }
      return { split: { mode: 'percent', percents } }
    }
    case 'amounts': {
      const amounts: { participant: string; amount: number }[] = []
      for (const r of rows) {
        const v = parseAmount(r.amount, minorUnits)
        if (v === null) return { problem: 'split-amounts' }
        amounts.push({ participant: r.participant, amount: Number(v) })
      }
      return { split: { mode: 'amounts', amounts } }
    }
  }
}

/** The body of the form, or the first thing wrong with it and where. [isParticipant] is the Pot's (the write path asks too). */
export function contentOf(
  form: EntryForm,
  isParticipant?: (id: string) => boolean,
): { content: MoneyContent } | { problem: FormProblem; at?: ProblemAt } {
  const mu = form.minorUnits
  const rate = parseRate(form.rate)
  const title = form.title.trim()
  const dated = /^[0-9]{4}-[0-9]{2}-[0-9]{2}$/.test(form.date)
  const money = (amount: bigint) => ({ date: form.date, currency: form.currency, minorUnits: mu, rate: rate!, amount: Number(amount), receipts: [...form.receipts] })

  let content: MoneyContent
  if (form.kind === 'transfer') {
    const amount = parseAmount(form.amount, mu)
    if (amount === null) return { problem: 'amount', at: 'amount' }
    if (rate === null) return { problem: 'rate', at: 'rate' }
    if (!dated) return { problem: 'date', at: 'date' }
    if (!form.from || !form.to || form.from === form.to) return { problem: 'transfer', at: 'transfer' }
    content = { kind: 'transfer', ...money(amount), from: form.from, to: form.to, ...(title ? { title } : {}) }
  } else {
    const rows = form.payers.filter((p) => p.participant)
    if (rows.length === 0) return { problem: 'payers', at: 'payers' }
    const contributors: Contribution[] = []
    for (const p of rows) {
      const v = parseAmount(p.amount, mu)
      if (v === null) return { problem: 'payers', at: 'payers' }
      contributors.push({ participant: p.participant, amount: Number(v) })
    }
    const amount = contributors.reduce((acc, p) => acc + BigInt(p.amount), 0n)
    if (amount > SAFE) return { problem: 'amount', at: 'payers' }
    if (rate === null) return { problem: 'rate', at: 'rate' }
    if (!dated) return { problem: 'date', at: 'date' }
    if (!title) return { problem: 'title', at: 'title' }
    const allocation = allocationOf(form, amount)
    if ('problem' in allocation) return allocation
    content =
      form.kind === 'expense'
        ? { kind: 'expense', title, category: form.category, ...money(amount), payers: contributors, allocation: allocation.allocation }
        : { kind: 'refund', title, category: form.category, ...money(amount), receivers: contributors, allocation: allocation.allocation, ...(form.refunds ? { refunds: form.refunds } : {}) }
  }
  const problem = entryProblem(content, isParticipant)
  if (!problem) return { content }
  const at = problemAt(content, problem, isParticipant)
  return at === undefined ? { problem } : { problem, at }
}

/** Where in the form the ledger's [problem] with [c] is: the payers, the items, or the split that breaks the rule — an
 *  item's by its index (the form's items and the content's are the same, in order), or the rest's. */
function problemAt(c: MoneyContent, problem: EntryProblem, isParticipant?: (id: string) => boolean): ProblemAt | undefined {
  if (c.kind === 'transfer') return 'transfer' // a transfer's only ledger problem: an end who is not in the Pot
  const contributors = (c.kind === 'expense' ? c.payers : c.receivers).map((p) => p.participant)
  const rest = restOf(c)
  const parts: [ProblemAt, Split, bigint | null][] = c.allocation.items.map((i, n) => [n, i.split, BigInt(i.amount)])
  if (c.allocation.rest) parts.push(['rest', c.allocation.rest, rest > 0n ? rest : null])
  const partWhere = (breaks: (s: Split, part: bigint | null) => boolean) => parts.find(([, s, part]) => breaks(s, part))?.[0]
  const unknown = (id: string) => !!isParticipant && !isParticipant(id)
  switch (problem) {
    case 'payers-unbalanced':
    case 'receivers-unbalanced':
      return 'payers'
    case 'items-above-total':
      return 'items'
    case 'rest-unallocated':
      return 'rest'
    case 'named-twice':
      return new Set(contributors).size !== contributors.length ? 'payers' : partWhere((s) => splitBreaks('named-twice', s, null))
    case 'percent-unbalanced':
    case 'amounts-unbalanced':
      return partWhere((s, part) => splitBreaks(problem, s, part))
    case 'unknown-participant':
      return contributors.some(unknown) ? 'payers' : partWhere((s) => splitPeople(s).some(unknown))
  }
}

/**
 * What the editor shows under an expense or a refund (uc.expenses.record-entries main flow 4): the entry's effect in the
 * [base] currency — its total converted with the form's rate, what each payer (receiver) gave and each person's share — by
 * the very `entryEffect` every balance is computed with, over the content the save would seal, so what is shown is what is
 * saved. [order] is the Pot's join order (`PotLedger.participants`, erased ones included, as `buildLedger` ranks them). Null
 * for a transfer, and while the form forms no valid entry — except for a missing title, which moves no money.
 */
export function sharesPreview(
  form: EntryForm,
  base: BaseCurrency,
  order: readonly string[],
  isParticipant?: (id: string) => boolean,
): EntryEffect | null {
  if (form.kind === 'transfer') return null
  const result = contentOf(form.title.trim() ? form : { ...form, title: '-' }, isParticipant)
  return 'content' in result ? entryEffect(result.content, base, rankOf(order)) : null
}

/** The items and, while the rest is above zero, the rest's split. Items above [amount] are left for `entryProblem` to name. */
function allocationOf(form: EntryForm, amount: bigint): { allocation: Allocation } | { problem: FormProblem; at: ProblemAt } {
  const items: Item[] = []
  for (const [i, item] of form.items.entries()) {
    const v = parseAmount(item.amount, form.minorUnits)
    const label = item.label.trim()
    if (v === null || !label) return { problem: 'items', at: i }
    const split = splitOf(item.split, form.minorUnits)
    if ('problem' in split) return { problem: split.problem === 'empty' ? 'items' : split.problem, at: i }
    items.push({ label, amount: Number(v), split: split.split })
  }
  const rest = amount - items.reduce((acc, i) => acc + BigInt(i.amount), 0n)
  if (rest <= 0n) return { allocation: { items } }
  const split = splitOf(form.rest, form.minorUnits)
  return 'problem' in split ? { problem: split.problem === 'empty' ? 'split' : split.problem, at: 'rest' } : { allocation: { items, rest: split.split } }
}

/** The catalog key of a problem (`expenses.entry.problem.<key>`): the reason's words in camelCase (`payers-unbalanced` →
 *  `payersUnbalanced`), as every key of the catalog is spelled. */
export const problemKey = (problem: string): string => problem.replace(/-([a-z])/g, (_, c: string) => c.toUpperCase())

/** The sum of typed amounts that parse, in minor units; one that does not parse counts nothing. */
export function typedSum(texts: readonly string[], minorUnits: number): bigint {
  let total = 0n
  for (const t of texts) {
    const v = parseAmount(t, minorUnits)
    if (v !== null && total + v <= SAFE) total += v
  }
  return total
}

/** An expense's (a refund's) amount as the editor shows it: the sum of its payers' typed amounts. */
export const payersTotal = (form: EntryForm): bigint => typedSum(form.payers.map((p) => p.amount), form.minorUnits)

/** What is left for the rest: the payers' total less the typed items (negative: the items are above the amount). */
export const restLeft = (form: EntryForm): bigint => payersTotal(form) - typedSum(form.items.map((i) => i.amount), form.minorUnits)

/** An item's typed amount in minor units (0 while it is none): what an amounts split of it must add up to. */
export const itemAmount = (form: EntryForm, item: FormItem): bigint => parseAmount(item.amount, form.minorUnits) ?? 0n

/** The basis points a percent split's typed values add up to (those that parse, of the rows that name a person). */
export function percentTotal(split: FormSplit): number {
  let total = 0
  for (const r of named(split)) total += parsePercent(r.percent) ?? 0
  return total
}

/** The minor units an amounts split's typed values add up to (those that parse, of the rows that name a person). */
export const amountsTotal = (split: FormSplit, minorUnits: number): bigint => typedSum(named(split).map((r) => r.amount), minorUnits)
