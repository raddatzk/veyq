// The entry editor's form and its translation to and from the format v1 body (contracts/expenses/entry.v1.schema.json):
// what a person types — amounts as text, a typed rate, a split — and the exact content a write seals. Pure, so the editor
// (`components/EntryEditor.vue`) holds no rule of its own: what may be saved is decided here and by `ledger.ts`'s
// `entryProblem` (the unbalanced-entry rule, owner 2026-10-24), which the write path asks again before sealing.
import { amountText, minorUnitsOf, parseAmount, parseRate } from './amounts'
import type { Allocation, Contribution, MoneyContent } from './domain/format'
import { entryProblem, type BaseCurrency, type EntryProblem } from './domain/ledger'

export type EntryKind = MoneyContent['kind']
export type SplitMode = Allocation['mode']

export type FormItem = { label: string; amount: string; participants: string[] }

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
  /** The total, as typed. */
  amount: string
  /** An expense's payers or a refund's receivers. With ONE row its amount is the total (the field is not shown). */
  payers: { participant: string; amount: string }[]
  mode: SplitMode
  /** `equal`: who shares it. */
  equal: string[]
  /** `shares`: each person's shares, as typed (empty: not on it). */
  shares: Record<string, string>
  /** `exact`: each person's amount, as typed (empty: not on it). */
  exact: Record<string, string>
  items: FormItem[]
  rest: 'proportional' | 'equal'
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

/** A new entry's form: an expense in the base currency, paid by [payer] (the first person offered), split equally among
 *  [people]. */
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
    mode: 'equal',
    equal: [...people],
    shares: {},
    exact: {},
    items: [],
    rest: 'proportional',
    from: payer ?? '',
    to: people.find((p) => p !== payer) ?? '',
    receipts: [],
  }
}

const text = (amount: number, minorUnits: number, locale: string) => amountText(BigInt(amount), minorUnits, locale)

/** The form of an existing entry's [content], to edit it. */
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
  const a = content.allocation
  form.mode = a.mode
  form.equal = a.mode === 'equal' ? [...a.participants] : []
  if (a.mode === 'shares') form.shares = Object.fromEntries(a.shares.map((s) => [s.participant, String(s.shares)]))
  if (a.mode === 'exact') form.exact = Object.fromEntries(a.amounts.map((s) => [s.participant, text(s.amount, mu, locale)]))
  if (a.mode === 'items') {
    form.items = a.items.map((i) => ({ label: i.label, amount: text(i.amount, mu, locale), participants: [...i.participants] }))
    form.rest = a.rest
  }
  return form
}

/** Set the form's currency: its minor units from `Intl` (an entry being edited keeps the digits it was written with while
 *  its currency stays), the rate back to `1` for the base and left to be typed for any other. */
export function withCurrency(form: EntryForm, currency: string, base: BaseCurrency): EntryForm {
  if (currency === form.currency) return form
  const minorUnits = currency === base.currency ? base.minorUnits : minorUnitsOf(currency)
  return { ...form, currency, minorUnits, rate: currency === base.currency ? '1' : '' }
}

const SAFE = BigInt(Number.MAX_SAFE_INTEGER)

/** The body of the form, or the first thing wrong with it. [isParticipant] is the Pot's (the write path asks too). */
export function contentOf(
  form: EntryForm,
  isParticipant?: (id: string) => boolean,
): { content: MoneyContent } | { problem: FormProblem } {
  const mu = form.minorUnits
  const amount = parseAmount(form.amount, mu)
  if (amount === null) return { problem: 'amount' }
  const rate = parseRate(form.rate)
  if (rate === null) return { problem: 'rate' }
  if (!/^[0-9]{4}-[0-9]{2}-[0-9]{2}$/.test(form.date)) return { problem: 'date' }
  const money = { date: form.date, currency: form.currency, minorUnits: mu, rate, amount: Number(amount), receipts: [...form.receipts] }
  const title = form.title.trim()

  let content: MoneyContent
  if (form.kind === 'transfer') {
    if (!form.from || !form.to || form.from === form.to) return { problem: 'transfer' }
    content = { kind: 'transfer', ...money, from: form.from, to: form.to, ...(title ? { title } : {}) }
  } else {
    if (!title) return { problem: 'title' }
    const rows = form.payers.filter((p) => p.participant)
    if (rows.length === 0) return { problem: 'payers' }
    let contributors: Contribution[]
    if (rows.length === 1) contributors = [{ participant: rows[0]!.participant, amount: Number(amount) }]
    else {
      const parsed = rows.map((p) => ({ participant: p.participant, amount: parseAmount(p.amount, mu) }))
      if (parsed.some((p) => p.amount === null)) return { problem: 'payers' }
      contributors = parsed.map((p) => ({ participant: p.participant, amount: Number(p.amount) }))
    }
    const allocation = allocationOf(form)
    if ('problem' in allocation) return allocation
    content =
      form.kind === 'expense'
        ? { kind: 'expense', title, category: form.category, ...money, payers: contributors, allocation: allocation.allocation }
        : { kind: 'refund', title, category: form.category, ...money, receivers: contributors, allocation: allocation.allocation, ...(form.refunds ? { refunds: form.refunds } : {}) }
  }
  const problem = entryProblem(content, isParticipant)
  return problem ? { problem } : { content }
}

function allocationOf(form: EntryForm): { allocation: Allocation } | { problem: FormProblem } {
  const mu = form.minorUnits
  switch (form.mode) {
    case 'equal':
      return form.equal.length ? { allocation: { mode: 'equal', participants: [...form.equal] } } : { problem: 'split' }
    case 'shares': {
      const shares: { participant: string; shares: number }[] = []
      for (const [participant, typed] of Object.entries(form.shares)) {
        if (typed.trim() === '') continue
        const n = Number(typed.trim())
        if (!Number.isInteger(n) || n < 1 || n > 1_000_000) return { problem: 'split' }
        shares.push({ participant, shares: n })
      }
      return shares.length ? { allocation: { mode: 'shares', shares } } : { problem: 'split' }
    }
    case 'exact': {
      const amounts: Contribution[] = []
      for (const [participant, typed] of Object.entries(form.exact)) {
        if (typed.trim() === '') continue
        const v = parseAmount(typed, mu)
        if (v === null) return { problem: 'split' }
        amounts.push({ participant, amount: Number(v) })
      }
      return amounts.length ? { allocation: { mode: 'exact', amounts } } : { problem: 'split' }
    }
    case 'items': {
      if (form.items.length === 0) return { problem: 'items' }
      const items = []
      for (const item of form.items) {
        const v = parseAmount(item.amount, mu)
        const label = item.label.trim()
        if (v === null || !label || item.participants.length === 0) return { problem: 'items' }
        items.push({ label, amount: Number(v), participants: [...item.participants] })
      }
      return { allocation: { mode: 'items', items, rest: form.rest } }
    }
  }
}

/** The catalog key of a problem (`expenses.entry.problem.<key>`): the reason's words in camelCase (`payers-unbalanced` →
 *  `payersUnbalanced`), as every key of the catalog is spelled. */
export const problemKey = (problem: string): string => problem.replace(/-([a-z])/g, (_, c: string) => c.toUpperCase())

/** The sum of typed amounts that parse (for the editor's "left to split" hint), in minor units; one that does not parse
 *  counts nothing. */
export function typedSum(texts: readonly string[], minorUnits: number): bigint {
  let total = 0n
  for (const t of texts) {
    const v = parseAmount(t, minorUnits)
    if (v !== null && total + v <= SAFE) total += v
  }
  return total
}
