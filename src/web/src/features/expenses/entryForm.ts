// The entry editor's form and its translation to and from the format v1 body (contracts/expenses/entry.v1.schema.json):
// what a person types — amounts as text, a typed rate, the payers and the items with their splits — and the exact content a
// write seals. Pure, so the editor (`components/EntryEditor.vue`) holds no rule of its own: what may be saved is decided
// here and by `ledger.ts`'s `entryProblem` (the unbalanced-entry rule, owner 2026-10-24), which the write path asks again
// before sealing.
//
// An expense's (a refund's) amount is never typed: it is the sum of what its payers (receivers) paid, every row with an
// amount of its own. Its allocation is the items, each an exact amount with its own split, and the rest — the amount less
// the items — by a split of its own; a new entry has no items, so the whole amount is rest, split equally among everyone
// offered (the Pot's active people), and those people are sealed by name in the rest's split.
import { amountText, minorUnitsOf, parseAmount, parseRate } from './amounts'
import { PERCENT_WHOLE, type Allocation, type Contribution, type Item, type MoneyContent, type Split } from './domain/format'
import { entryEffect, entryProblem, rankOf, type BaseCurrency, type EntryEffect, type EntryProblem } from './domain/ledger'

export type EntryKind = MoneyContent['kind']
export type SplitMode = Split['mode']

/** One split as it is typed. Each mode keeps what was typed for it, so switching back and forth loses nothing; only the
 *  picked mode's values are sealed. */
export interface FormSplit {
  mode: SplitMode
  /** `equal`: who shares it. */
  equal: string[]
  /** `shares`: each person's shares, as typed (empty: not on it). */
  shares: Record<string, string>
  /** `percent`: each person's percent, as typed with at most two decimals (empty: not on it). */
  percent: Record<string, string>
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
  | 'items'
  | 'transfer'
  | EntryProblem

export function today(): string {
  const d = new Date()
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

/** A split equally among [people], nothing typed for the other modes. */
export function equalSplit(people: readonly string[]): FormSplit {
  return { mode: 'equal', equal: [...people], shares: {}, percent: {} }
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

/** A new item: no label, no amount, split equally among [people]. */
export function emptyItem(people: readonly string[]): FormItem {
  return { label: '', amount: '', split: equalSplit(people) }
}

const text = (amount: number, minorUnits: number, locale: string) => amountText(BigInt(amount), minorUnits, locale)

/** Basis points as a percent is typed: `3333` → `33.33` (the reader's separator). */
export const percentText = (basisPoints: number, locale: string): string => amountText(BigInt(basisPoints), 2, locale)

function formSplitOf(s: Split, locale: string): FormSplit {
  switch (s.mode) {
    case 'equal':
      return equalSplit(s.participants)
    case 'shares':
      return { mode: 'shares', equal: s.shares.map((x) => x.participant), shares: Object.fromEntries(s.shares.map((x) => [x.participant, String(x.shares)])), percent: {} }
    case 'percent':
      return { mode: 'percent', equal: s.percents.map((x) => x.participant), shares: {}, percent: Object.fromEntries(s.percents.map((x) => [x.participant, percentText(x.percent, locale)])) }
  }
}

/** The form of an existing entry's [content], to edit it. A rest of zero has no split sealed; the form offers the equal one
 *  among [people] for it, should the items shrink. */
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
  form.items = content.allocation.items.map((i) => ({ label: i.label, amount: text(i.amount, mu, locale), split: formSplitOf(i.split, locale) }))
  if (content.allocation.rest) form.rest = formSplitOf(content.allocation.rest, locale)
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

/** The split as the format seals it, or null when it names no one or holds a value that is none. */
function splitOf(s: FormSplit): Split | null {
  switch (s.mode) {
    case 'equal':
      return s.equal.length ? { mode: 'equal', participants: [...s.equal] } : null
    case 'shares': {
      const shares: { participant: string; shares: number }[] = []
      for (const [participant, typed] of Object.entries(s.shares)) {
        if (typed.trim() === '') continue
        const n = Number(typed.trim())
        if (!/^[0-9]+$/.test(typed.trim()) || n < 1 || n > 1_000_000) return null
        shares.push({ participant, shares: n })
      }
      return shares.length ? { mode: 'shares', shares } : null
    }
    case 'percent': {
      const percents: { participant: string; percent: number }[] = []
      for (const [participant, typed] of Object.entries(s.percent)) {
        if (typed.trim() === '') continue
        const n = parsePercent(typed)
        if (n === null) return null
        percents.push({ participant, percent: n })
      }
      return percents.length ? { mode: 'percent', percents } : null
    }
  }
}

/** The body of the form, or the first thing wrong with it. [isParticipant] is the Pot's (the write path asks too). */
export function contentOf(
  form: EntryForm,
  isParticipant?: (id: string) => boolean,
): { content: MoneyContent } | { problem: FormProblem } {
  const mu = form.minorUnits
  const rate = parseRate(form.rate)
  const title = form.title.trim()
  const dated = /^[0-9]{4}-[0-9]{2}-[0-9]{2}$/.test(form.date)
  const money = (amount: bigint) => ({ date: form.date, currency: form.currency, minorUnits: mu, rate: rate!, amount: Number(amount), receipts: [...form.receipts] })

  let content: MoneyContent
  if (form.kind === 'transfer') {
    const amount = parseAmount(form.amount, mu)
    if (amount === null) return { problem: 'amount' }
    if (rate === null) return { problem: 'rate' }
    if (!dated) return { problem: 'date' }
    if (!form.from || !form.to || form.from === form.to) return { problem: 'transfer' }
    content = { kind: 'transfer', ...money(amount), from: form.from, to: form.to, ...(title ? { title } : {}) }
  } else {
    const rows = form.payers.filter((p) => p.participant)
    if (rows.length === 0) return { problem: 'payers' }
    const contributors: Contribution[] = []
    for (const p of rows) {
      const v = parseAmount(p.amount, mu)
      if (v === null) return { problem: 'payers' }
      contributors.push({ participant: p.participant, amount: Number(v) })
    }
    const amount = contributors.reduce((acc, p) => acc + BigInt(p.amount), 0n)
    if (amount > SAFE) return { problem: 'amount' }
    if (rate === null) return { problem: 'rate' }
    if (!dated) return { problem: 'date' }
    if (!title) return { problem: 'title' }
    const allocation = allocationOf(form, amount)
    if ('problem' in allocation) return allocation
    content =
      form.kind === 'expense'
        ? { kind: 'expense', title, category: form.category, ...money(amount), payers: contributors, allocation: allocation.allocation }
        : { kind: 'refund', title, category: form.category, ...money(amount), receivers: contributors, allocation: allocation.allocation, ...(form.refunds ? { refunds: form.refunds } : {}) }
  }
  const problem = entryProblem(content, isParticipant)
  return problem ? { problem } : { content }
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
function allocationOf(form: EntryForm, amount: bigint): { allocation: Allocation } | { problem: FormProblem } {
  const items: Item[] = []
  for (const item of form.items) {
    const v = parseAmount(item.amount, form.minorUnits)
    const label = item.label.trim()
    const split = splitOf(item.split)
    if (v === null || !label || !split) return { problem: 'items' }
    items.push({ label, amount: Number(v), split })
  }
  const rest = amount - items.reduce((acc, i) => acc + BigInt(i.amount), 0n)
  if (rest <= 0n) return { allocation: { items } }
  const split = splitOf(form.rest)
  return split ? { allocation: { items, rest: split } } : { problem: 'split' }
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

/** The basis points a percent split's typed values add up to (those that parse). */
export function percentTotal(split: FormSplit): number {
  let total = 0
  for (const typed of Object.values(split.percent)) total += parsePercent(typed) ?? 0
  return total
}
