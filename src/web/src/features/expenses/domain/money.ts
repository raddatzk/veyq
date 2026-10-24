// Expenses' arithmetic (ADR 0108 decision 9, contracts/expenses/entry.v1.schema.json, docs/apps/expenses.md § Entries):
// exact, in `BigInt` fractions, never floating point — the rounding rule is part of the format version, so every device
// must compute the same cent from the same entries, and `contracts/expenses/v1-vectors.json` pins it. Two operations:
//
// - {@link convertToBase} — an entry's total into the Pot's base currency, ONCE:
//   `round_half_away_from_zero(amount * rate * 10^(baseMinorUnits - minorUnits))`.
// - {@link allocate} — an integer total by weights (largest remainder): each person's exact share `T * w / sum(w)`
//   rounded down, the units left over handed one each to the largest cut-off remainders, ties by the order the people
//   are given in (the caller gives them in join order).
//
// A `Fraction` is always reduced to a positive denominator; nothing here ever builds a `number` from a quotient.

/** An exact rational `num / den`, `den > 0`. */
export type Fraction = { readonly num: bigint; readonly den: bigint }

const abs = (n: bigint): bigint => (n < 0n ? -n : n)

function gcd(a: bigint, b: bigint): bigint {
  let x = abs(a)
  let y = abs(b)
  while (y !== 0n) [x, y] = [y, x % y]
  return x
}

/** `num / den`, reduced; a zero denominator throws (no weight, rate or share may divide by nothing). */
export function fraction(num: bigint, den: bigint = 1n): Fraction {
  if (den === 0n) throw new RangeError('fraction_zero_denominator')
  const sign = den < 0n ? -1n : 1n
  const g = gcd(num, den) || 1n
  return { num: (sign * num) / g, den: (sign * den) / g }
}

export const ZERO: Fraction = { num: 0n, den: 1n }

export const add = (a: Fraction, b: Fraction): Fraction => fraction(a.num * b.den + b.num * a.den, a.den * b.den)
export const sub = (a: Fraction, b: Fraction): Fraction => fraction(a.num * b.den - b.num * a.den, a.den * b.den)
export const mul = (a: Fraction, b: Fraction): Fraction => fraction(a.num * b.num, a.den * b.den)
export const div = (a: Fraction, b: Fraction): Fraction => fraction(a.num * b.den, a.den * b.num)
export const sum = (xs: Iterable<Fraction>): Fraction => {
  let acc = ZERO
  for (const x of xs) acc = add(acc, x)
  return acc
}

/** A weight written as the vectors write one: `"3"`, `"1/3"`, `"-2/4"`. */
export function parseFraction(text: string): Fraction {
  const m = /^(-?[0-9]+)(?:\/([0-9]+))?$/.exec(text)
  if (!m) throw new RangeError('fraction_malformed')
  return fraction(BigInt(m[1]!), m[2] === undefined ? 1n : BigInt(m[2]))
}

/** The schema's `rate`: a decimal string greater than zero, at most 12 digits on either side of the point. */
export const RATE = /^(?:[1-9][0-9]{0,11}(?:\.[0-9]{1,12})?|0\.(?=[0-9]*[1-9])[0-9]{1,12})$/

/** A rate as an exact fraction — `"1.1725"` is `11725 / 10000`. Throws on anything the schema's pattern refuses. */
export function parseRate(rate: string): Fraction {
  if (!RATE.test(rate)) throw new RangeError('rate_malformed')
  const [whole, part = ''] = rate.split('.')
  return fraction(BigInt(whole! + part), 10n ** BigInt(part.length))
}

/** Floor of `num / den` for any sign (BigInt division truncates toward zero). */
export function floorDiv(num: bigint, den: bigint): bigint {
  if (den <= 0n) throw new RangeError('floor_denominator')
  const q = num / den
  return num % den !== 0n && num < 0n ? q - 1n : q
}

/** `f` rounded to the nearest integer, a half away from zero — `2.5 → 3`, `-2.5 → -3`. */
export function roundHalfAwayFromZero(f: Fraction): bigint {
  const n = abs(f.num)
  const rounded = (2n * n + f.den) / (2n * f.den) // floor(n / den + 1/2) for n >= 0
  return f.num < 0n ? -rounded : rounded
}

/**
 * An entry's amount (minor units of its currency) in minor units of the Pot's base currency: converted once, rounded a
 * half away from zero. `minorUnits` and `baseMinorUnits` are the digits each currency's minor unit has (the yen 0, the
 * euro 2, the dinar 3).
 */
export function convertToBase(amount: bigint, minorUnits: number, rate: string, baseMinorUnits: number): bigint {
  const shift = baseMinorUnits - minorUnits
  const scale = shift >= 0 ? fraction(10n ** BigInt(shift)) : fraction(1n, 10n ** BigInt(-shift))
  return roundHalfAwayFromZero(mul(mul(fraction(amount), parseRate(rate)), scale))
}

/**
 * The largest-remainder allocation of the integer `total` by `weights`, one per person in the order given (the caller's
 * join order, which also breaks a tie of remainders). Each person gets `floor(total * w / sum(w))`; the units left over
 * go one each to the people with the largest cut-off remainders. Nobody ends more than one unit off their exact share.
 * Weights are non-negative and not all zero; `total` is non-negative.
 */
export function allocate(total: bigint, weights: readonly Fraction[]): bigint[] {
  if (total < 0n) throw new RangeError('allocate_negative_total')
  if (weights.some((w) => w.num < 0n)) throw new RangeError('allocate_negative_weight')
  // One common denominator turns every weight into an integer, so a share is `total * n_i / N` in integers.
  const common = weights.reduce((acc, w) => (acc * w.den) / gcd(acc, w.den), 1n)
  const ints = weights.map((w) => (w.num * common) / w.den)
  const whole = ints.reduce((a, b) => a + b, 0n)
  if (whole === 0n) throw new RangeError('allocate_no_weight')
  const shares = ints.map((n) => (total * n) / whole) // exact: every operand is non-negative, so this is the floor
  const remainders = ints.map((n) => (total * n) % whole)
  let left = total - shares.reduce((a, b) => a + b, 0n)
  // Largest remainder first; a tie keeps the given (join) order, so the sort must be stable on the index.
  const order = remainders.map((r, i) => [r, i] as const).sort((a, b) => (a[0] === b[0] ? a[1] - b[1] : a[0] > b[0] ? -1 : 1))
  for (const [, i] of order) {
    if (left === 0n) break
    shares[i]! += 1n
    left -= 1n
  }
  return shares
}
