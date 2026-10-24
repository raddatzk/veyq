// The arithmetic of format v1 against contracts/expenses/v1-vectors.json — every `conversion` and `rounding` case through
// the real code, so this client computes the cent every other client computes (ADR 0108 decision 9).
import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { allocate, convertToBase, floorDiv, fraction, parseFraction, parseRate, roundHalfAwayFromZero } from './money'

type Vectors = {
  conversion: { amount: number; minorUnits: number; rate: string; baseMinorUnits: number; expected: number }[]
  rounding: { total: number; weights: string[]; expected: number[] }[]
}
const vectors = JSON.parse(readFileSync(resolve(__dirname, '../../../../../../contracts/expenses/v1-vectors.json'), 'utf8')) as Vectors

describe('format v1 arithmetic, against the vectors', () => {
  it('reads the vectors it is pinned by (a parse that found nothing would pass every loop below)', () => {
    expect(vectors.conversion.length).toBeGreaterThanOrEqual(15)
    expect(vectors.rounding.length).toBeGreaterThanOrEqual(10)
  })

  it.each(vectors.conversion.map((c) => [`${c.amount} at ${c.rate} (${c.minorUnits} → ${c.baseMinorUnits} digits)`, c] as const))(
    'converts %s',
    (_, c) => {
      expect(convertToBase(BigInt(c.amount), c.minorUnits, c.rate, c.baseMinorUnits)).toBe(BigInt(c.expected))
    },
  )

  it.each(vectors.rounding.map((r) => [`${r.total} by ${r.weights.join(':')}`, r] as const))('allocates %s by the largest remainder', (_, r) => {
    const shares = allocate(BigInt(r.total), r.weights.map(parseFraction))
    expect(shares).toEqual(r.expected.map(BigInt))
    expect(shares.reduce((a, b) => a + b, 0n)).toBe(BigInt(r.total)) // never a cent made or lost
  })
})

describe('the exact pieces', () => {
  it('reads a rate as an exact fraction and refuses what the schema refuses', () => {
    expect(parseRate('1.1725')).toEqual(fraction(11725n, 10000n))
    expect(parseRate('0.000000000001')).toEqual(fraction(1n, 10n ** 12n))
    expect(parseRate('100')).toEqual(fraction(100n))
    for (const bad of ['0', '0.0', '-1', '1e3', '.5', '1.', '01', '1.0000000000001', '1234567890123', '']) expect(() => parseRate(bad)).toThrow('rate_malformed')
  })

  it('rounds a half away from zero, both signs', () => {
    expect(roundHalfAwayFromZero(fraction(5n, 2n))).toBe(3n)
    expect(roundHalfAwayFromZero(fraction(-5n, 2n))).toBe(-3n)
    expect(roundHalfAwayFromZero(fraction(7n, 3n))).toBe(2n)
    expect(roundHalfAwayFromZero(fraction(-7n, 3n))).toBe(-2n)
    expect(roundHalfAwayFromZero(fraction(0n))).toBe(0n)
  })

  it('floors toward minus infinity', () => {
    expect(floorDiv(7n, 2n)).toBe(3n)
    expect(floorDiv(-7n, 2n)).toBe(-4n)
    expect(floorDiv(-6n, 2n)).toBe(-3n)
  })

  it('stays exact past the float range: a total near 2^53 split three ways sums back to itself', () => {
    const total = BigInt(Number.MAX_SAFE_INTEGER) * 1000n + 7n
    const shares = allocate(total, [fraction(1n), fraction(1n), fraction(1n)])
    expect(shares.reduce((a, b) => a + b, 0n)).toBe(total)
    expect(shares[0]! - shares[2]!).toBeLessThanOrEqual(1n)
  })

  it('refuses an allocation it cannot make', () => {
    expect(() => allocate(10n, [fraction(0n), fraction(0n)])).toThrow('allocate_no_weight')
    expect(() => allocate(10n, [fraction(-1n), fraction(2n)])).toThrow('allocate_negative_weight')
    expect(() => allocate(-1n, [fraction(1n)])).toThrow('allocate_negative_total')
    expect(() => fraction(1n, 0n)).toThrow('fraction_zero_denominator')
  })
})
