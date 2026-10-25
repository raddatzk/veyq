// Amounts typed and shown exactly — never through a float: a typed "0.10" is 10 minor units, a value past 2^53 prints
// every digit, and the currencies' digits come from `Intl`.
import { describe, it, expect } from 'vitest'
import { amountText, decimalOf, formatMoney, knownCurrencies, minorUnitsOf, parseAmount, parseRate } from './amounts'

describe('amounts', () => {
  it('reads what a person types, in minor units', () => {
    expect(parseAmount('12.50', 2)).toBe(1250n)
    expect(parseAmount('12,5', 2)).toBe(1250n)
    expect(parseAmount('0.10', 2)).toBe(10n)
    expect(parseAmount(' 1 000 ', 0)).toBe(1000n)
    expect(parseAmount('3', 3)).toBe(3000n)
  })

  it('refuses zero, more decimals than the currency has, signs, grouping and past the safe bound', () => {
    for (const [text, mu] of [['0', 2], ['0.00', 2], ['1.234', 2], ['1.5', 0], ['-1', 2], ['1.000,50', 2], ['abc', 2], ['90071992547409.92', 2]] as const) {
      expect(parseAmount(text, mu), text).toBeNull()
    }
    expect(parseAmount('90071992547409.91', 2)).toBe(9007199254740991n) // control: the bound itself
  })

  it('writes minor units as a decimal, every digit kept', () => {
    expect(decimalOf(1250n, 2)).toBe('12.50')
    expect(decimalOf(-5n, 2)).toBe('-0.05')
    expect(decimalOf(7n, 0)).toBe('7')
    expect(decimalOf(9007199254740993n, 2)).toBe('90071992547409.93')
    expect(amountText(1250n, 2, 'de')).toBe('12,50')
    expect(amountText(1250n, 2, 'en')).toBe('12.50')
  })

  it('shows money for the reader\'s language, exactly', () => {
    expect(formatMoney(1250n, 'EUR', 2, 'en')).toBe('€12.50')
    expect(formatMoney(1250n, 'EUR', 2, 'de')).toMatch(/^12,50\s€$/)
    expect(formatMoney(500n, 'JPY', 0, 'en')).toBe('¥500')
    expect(formatMoney(9007199254740993n, 'EUR', 2, 'en')).toBe('€90,071,992,547,409.93')
  })

  it('takes a currency\'s digits from Intl, and a typed rate with either separator', () => {
    expect([minorUnitsOf('EUR'), minorUnitsOf('JPY'), minorUnitsOf('BHD')]).toEqual([2, 0, 3])
    expect(knownCurrencies()).toContain('EUR')
    expect(parseRate('1,0825')).toBe('1.0825')
    expect(parseRate('0')).toBeNull()
    expect(parseRate('')).toBeNull()
  })
})
