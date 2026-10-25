// The day's exchange rates (ADR 0108 decision 8, uc.expenses.exchange-rates): where an entry's currency differs from its
// Pot's base, the device fetches the whole day's table of the European Central Bank's euro reference rates once, from the
// address the instance names (a new instance's is Frankfurter's ECB endpoint), and the editor fills the rate from it.
//
// - **The request is the day's table and nothing else.** The address is a URL template holding `{date}`; the device puts
//   its own date (`YYYY-MM-DD`) there and sends a plain `GET`: no currency, no amount, no query parameter of its own, no
//   custom header (so no preflight), no credentials and no Referer. What the provider learns is the client's address,
//   the instance's `Origin` and a date — what the privacy notice's `exchange-rates` passage says.
// - **One table per date, for every Pot** (decision 2): kept in the device's local storage under the date alone, so any
//   number of Pots and editors on one device ask once a day. The attempt is recorded before the request goes, so a day
//   whose fetch failed is not asked again either (the notice says "at most once a day"); the rate is then typed. Without
//   storage (a private window) the table lives as long as the page.
// - **Who may fetch** is the caller's to say, by handing in the address or null: a member's from its tenant's set, a link
//   guest's from its Pot read (`exchangeRateService`), never the instance's manifest — the tenant owner's switch must
//   reach both (`potSession.ts`).
// - **The rate from X to the base B is `rate(B) / rate(X)`**, computed exactly in fractions (the euro is 1), rounded a
//   half away from zero to the 12 decimal places the format's `rate` holds. A row may carry an earlier date than asked
//   (a weekend, a holiday, a future date answers the latest rows); the fill names the rows' date. A currency the table
//   lacks, or a row that is no ISO 4217 code this browser knows, is never offered: its rate is typed.
// - **A save never waits for it, and a saved rate never moves**: the editor fills only an empty rate of a currency just
//   picked, and the write path seals whatever the field holds (`EntryEditor.vue`).
import { knownCurrencies } from './amounts'
import { div, fraction, parseRate as rateFraction, RATE, roundHalfAwayFromZero, type Fraction } from './domain/money'

/** One currency's row: its rate against the euro (a decimal string, as the format writes one) and the day it is of. */
export type RateRow = { rate: string; date: string }

/** A day's table by ISO 4217 code; the euro's own row is always `1`. */
export type RateTable = Readonly<Record<string, RateRow>>

/** A rate the editor fills: what one unit of `from` is worth in the base, and the day of the rows it was computed from. */
export type FilledRate = { rate: string; date: string }

const PREFIX = 'veyq:expenses:rates:'
const DAY = /^[0-9]{4}-[0-9]{2}-[0-9]{2}$/
const PLACEHOLDER = '{date}'
/** The decimal places a filled rate keeps: the format's `rate` holds at most 12 (`domain/money.ts`' `RATE`). */
const PLACES = 12

/** The table's address for [day]: the template with `{date}` replaced, or null when it holds none (the Admin API refuses
 *  such a template; a client never guesses a URL). */
export function tableUrl(template: string, day: string): string | null {
  if (!template.includes(PLACEHOLDER) || !DAY.test(day)) return null
  return template.split(PLACEHOLDER).join(day)
}

/** The provider's answer (Frankfurter's v2 shape: an array of `{date, base, quote, rate}`, base `EUR`) as a table of the
 *  currencies this browser knows. Null for an answer of any other shape. A row against another base, a non-ISO quote
 *  (`BTC`, `XAU`), or a rate that is no plain decimal is left out. */
export function parseTable(answer: unknown, known: ReadonlySet<string> = new Set(knownCurrencies())): RateTable | null {
  if (!Array.isArray(answer)) return null
  const table: Record<string, RateRow> = {}
  for (const row of answer) {
    if (typeof row !== 'object' || row === null) continue
    const { base, quote, rate, date } = row as Record<string, unknown>
    if (base !== 'EUR' || typeof quote !== 'string' || !known.has(quote) || typeof date !== 'string' || !DAY.test(date)) continue
    // A JSON number reaches us as a double; its shortest round-trip text is the decimal the provider wrote for any rate of
    // up to 15 significant digits (the ECB's have 5 or 6). One that prints in exponent form is left out, never guessed.
    const text = typeof rate === 'number' ? String(rate) : typeof rate === 'string' ? rate : ''
    const decimal = text.replace(/(\.[0-9]*?)0+$/, '$1').replace(/\.$/, '')
    if (!RATE.test(decimal)) continue
    table[quote] = { rate: decimal, date }
  }
  return table
}

/** One unit of [from] in [to], from [table], or null when either is missing from it. The euro is 1 whether or not the
 *  table has its row; the date is the older of the two rows'. */
export function crossRate(table: RateTable, from: string, to: string): FilledRate | null {
  const row = (c: string): { value: Fraction; date: string | null } | null => {
    const r = table[c]
    if (c === 'EUR') return { value: fraction(1n), date: r?.date ?? null }
    return r ? { value: rateFraction(r.rate), date: r.date } : null
  }
  const x = row(from)
  const b = row(to)
  if (!x || !b) return null
  const date = [x.date, b.date].filter((d): d is string => d !== null).sort()[0]
  if (!date) return null
  const rate = decimalText(div(b.value, x.value))
  return rate && RATE.test(rate) ? { rate, date } : null
}

/** [f] rounded a half away from zero to [PLACES] decimal places, without trailing zeros; null when it rounds to zero. */
function decimalText(f: Fraction): string | null {
  const scaled = roundHalfAwayFromZero(fraction(f.num * 10n ** BigInt(PLACES), f.den))
  if (scaled <= 0n) return null
  const digits = scaled.toString().padStart(PLACES + 1, '0')
  const whole = digits.slice(0, -PLACES)
  const part = digits.slice(-PLACES).replace(/0+$/, '')
  return part ? `${whole}.${part}` : whole
}

// --- the device's cache and the one request a day -------------------------------------------------------------------

/** What the device holds for a day: the table, or the mark that the day's request was made and brought none. */
type Held = { table: RateTable } | { failed: true }

export interface RateSource {
  fetch: (url: string, init: RequestInit) => Promise<Response>
  storage: () => Storage | null
}

function deviceStorage(): Storage | null {
  try {
    return typeof localStorage === 'undefined' ? null : localStorage
  } catch {
    return null
  }
}

const browser: RateSource = { fetch: (url, init) => fetch(url, init), storage: deviceStorage }
let source: RateSource = browser
/** The page's own copy, by day: what storage holds (or would, without storage), and the request in flight. */
const memory = new Map<string, Promise<RateTable | null>>()

/** Tests swap the network and the storage, and forget what the page held. */
export function setRateSourceForTest(next: RateSource | null): void {
  source = next ?? browser
  memory.clear()
}

function read(storage: Storage | null, day: string): Held | null {
  try {
    const raw = storage?.getItem(PREFIX + day)
    if (!raw) return null
    const held = JSON.parse(raw) as Held
    return 'table' in held || 'failed' in held ? held : null
  } catch {
    return null
  }
}

/** Keep [held] as [day]'s, and forget every other day's: one table per device. */
function write(storage: Storage | null, day: string, held: Held): void {
  if (!storage) return
  try {
    for (let i = storage.length - 1; i >= 0; i--) {
      const key = storage.key(i)
      if (key?.startsWith(PREFIX) && key !== PREFIX + day) storage.removeItem(key)
    }
    storage.setItem(PREFIX + day, JSON.stringify(held))
  } catch {
    // storage full or refused: the page's copy stands
  }
}

/**
 * [day]'s table from [template] — the device's copy, else ONE request, shared by every caller asking meanwhile and
 * recorded before it goes. Null when the day's request failed, or brought no table. Never throws.
 */
export function dayTable(template: string, day: string): Promise<RateTable | null> {
  const inPage = memory.get(day)
  if (inPage) return inPage
  const storage = source.storage()
  const held = read(storage, day)
  if (held) {
    const answer = Promise.resolve('table' in held ? held.table : null)
    memory.set(day, answer)
    return answer
  }
  const url = tableUrl(template, day)
  if (url === null) return Promise.resolve(null)
  write(storage, day, { failed: true }) // the attempt, before it goes: at most one a day, whatever it brings
  const request = (async () => {
    try {
      const res = await source.fetch(url, { method: 'GET', credentials: 'omit', referrerPolicy: 'no-referrer' })
      if (!res.ok) return null
      const table = parseTable(await res.json())
      if (table) write(storage, day, { table })
      return table
    } catch {
      return null
    }
  })()
  memory.set(day, request)
  return request
}
