// Amounts as a person reads and types them, and back to the minor units the format seals (ADR 0108 decision 9): exact, in
// `BigInt` and strings, never through a float — a typed "0.10" is 10 cents, not 0.1 × 100. The currencies and their
// minor-unit digits come from the browser's `Intl` (ISO 4217); the digits a Pot or an entry was written with are sealed in
// it, so a later browser's table never moves a stored amount.
import { RATE } from './domain/money'

const MAX_SAFE = BigInt(Number.MAX_SAFE_INTEGER)

/** A short list for an engine without `Intl.supportedValuesOf` (every current browser has it). */
const FALLBACK_CURRENCIES = ['AUD', 'CAD', 'CHF', 'CZK', 'DKK', 'EUR', 'GBP', 'HUF', 'JPY', 'NOK', 'PLN', 'SEK', 'USD']

/** The ISO 4217 codes this browser knows, sorted. */
export function knownCurrencies(): string[] {
  const supported = (Intl as unknown as { supportedValuesOf?: (key: string) => string[] }).supportedValuesOf
  const list = supported ? supported('currency') : FALLBACK_CURRENCIES
  return [...new Set(list.filter((c) => /^[A-Z]{3}$/.test(c)))].sort()
}

/** The minor-unit digits of [currency] (the euro 2, the yen 0, the dinar 3), from `Intl`, within the format's 0..4. */
export function minorUnitsOf(currency: string): number {
  try {
    const digits = new Intl.NumberFormat('en', { style: 'currency', currency }).resolvedOptions().maximumFractionDigits ?? 2
    return Math.min(4, Math.max(0, digits))
  } catch {
    return 2
  }
}

/** `amount` minor units as a plain decimal string with a dot: `1250n, 2` → `"12.50"`, `-5n, 2` → `"-0.05"`. */
export function decimalOf(amount: bigint, minorUnits: number): string {
  const negative = amount < 0n
  const digits = (negative ? -amount : amount).toString().padStart(minorUnits + 1, '0')
  const whole = minorUnits === 0 ? digits : digits.slice(0, -minorUnits)
  const frac = minorUnits === 0 ? '' : `.${digits.slice(-minorUnits)}`
  return `${negative ? '-' : ''}${whole}${frac}`
}

/** `amount` minor units of [currency] for the reader's [locale] ("€12.50", "12,50 €"). The value reaches `Intl` as a decimal
 *  string, which it formats exactly. */
export function formatMoney(amount: bigint, currency: string, minorUnits: number, locale: string): string {
  const value = decimalOf(amount, minorUnits)
  try {
    return new Intl.NumberFormat(locale, { style: 'currency', currency, minimumFractionDigits: minorUnits, maximumFractionDigits: minorUnits })
      .format(value as unknown as number)
  } catch {
    return `${value} ${currency}`
  }
}

/** What a person typed as an amount, in minor units: digits with at most one decimal separator (`.` or `,`) and at most
 *  [minorUnits] digits after it. Null for anything else, for zero, and past the format's safe-integer bound. */
export function parseAmount(text: string, minorUnits: number): bigint | null {
  const m = /^([0-9]+)(?:[.,]([0-9]*))?$/.exec(text.trim().replace(/\s/g, ''))
  if (!m) return null
  const frac = m[2] ?? ''
  if (frac.length > minorUnits) return null
  const value = BigInt(m[1]! + frac.padEnd(minorUnits, '0'))
  return value > 0n && value <= MAX_SAFE ? value : null
}

/** An amount as the editor shows it to be typed over: the decimal string with the reader's separator. */
export function amountText(amount: bigint, minorUnits: number, locale: string): string {
  const sep = (1.5).toLocaleString(locale).includes(',') ? ',' : '.'
  return decimalOf(amount, minorUnits).replace('.', sep)
}

/** A typed rate as the format seals it (a decimal string with a dot, the schema's `rate`), or null when it is none. */
export function parseRate(text: string): string | null {
  const rate = text.trim().replace(',', '.')
  return RATE.test(rate) ? rate : null
}
