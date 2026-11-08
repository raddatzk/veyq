package me.raddatz.veyq.mobile.expenses

/**
 * Expenses' arithmetic, a port of `src/web/src/features/expenses/domain/money.ts` (ADR 0108 decision 9): exact fractions,
 * never floating point — the rounding rule is part of the format version, so every device computes the same cent from the
 * same entries, and `contracts/expenses/v1-vectors.json` pins it. [convertToBase] converts an entry's total once,
 * [allocate] splits an integer total by weights with the largest-remainder rule.
 */
class Fraction private constructor(val num: Big, val den: Big) {
    operator fun plus(o: Fraction) = of(num * o.den + o.num * den, den * o.den)
    operator fun minus(o: Fraction) = of(num * o.den - o.num * den, den * o.den)
    operator fun times(o: Fraction) = of(num * o.num, den * o.den)
    operator fun div(o: Fraction) = of(num * o.den, den * o.num)

    override fun equals(other: Any?) = other is Fraction && num == other.num && den == other.den
    override fun hashCode() = num.hashCode() * 31 + den.hashCode()
    override fun toString() = "$num/$den"

    companion object {
        val ZERO = Fraction(Big.ZERO, Big.ONE)

        /** `num / den`, reduced to a positive denominator; a zero denominator throws. */
        fun of(num: Big, den: Big = Big.ONE): Fraction {
            require(den.signum != 0) { "fraction_zero_denominator" }
            val sign = if (den.signum < 0) -Big.ONE else Big.ONE
            val g = gcd(num, den).let { if (it.signum == 0) Big.ONE else it }
            return Fraction(sign * num / g, sign * den / g)
        }

        fun of(num: Long, den: Long = 1) = of(Big.of(num), Big.of(den))

        /** A weight as the vectors write one: `"3"`, `"1/3"`, `"-2/4"`. */
        fun parse(text: String): Fraction {
            val m = Regex("^(-?[0-9]+)(?:/([0-9]+))?$").matchEntire(text) ?: throw IllegalArgumentException("fraction_malformed")
            return of(Big.parse(m.groupValues[1]), m.groupValues[2].takeIf { it.isNotEmpty() }?.let { Big.parse(it) } ?: Big.ONE)
        }
    }
}

internal fun gcd(a: Big, b: Big): Big {
    var x = a.abs()
    var y = b.abs()
    while (y.signum != 0) {
        val t = x % y
        x = y
        y = t
    }
    return x
}

object Money {
    /** The schema's `rate`: a decimal greater than zero, at most 12 digits on either side of the point. */
    val RATE = Regex("^(?:[1-9][0-9]{0,11}(?:\\.[0-9]{1,12})?|0\\.(?=[0-9]*[1-9])[0-9]{1,12})$")

    /** A rate as an exact fraction — `"1.1725"` is `11725 / 10000`. Throws on anything the schema's pattern refuses. */
    fun parseRate(rate: String): Fraction {
        require(RATE.matches(rate)) { "rate_malformed" }
        val whole = rate.substringBefore('.')
        val part = if ('.' in rate) rate.substringAfter('.') else ""
        return Fraction.of(Big.parse(whole + part), Big.pow10(part.length))
    }

    /** Floor of `num / den` for any sign. */
    fun floorDiv(num: Big, den: Big): Big {
        require(den.signum > 0) { "floor_denominator" }
        val q = num / den
        return if ((num % den).signum != 0 && num.signum < 0) q - Big.ONE else q
    }

    /** [f] rounded to the nearest integer, a half away from zero — `2.5 → 3`, `-2.5 → -3`. */
    fun roundHalfAwayFromZero(f: Fraction): Big {
        val n = f.num.abs()
        val two = Big.of(2)
        val rounded = (two * n + f.den) / (two * f.den)
        return if (f.num.signum < 0) -rounded else rounded
    }

    /** An entry's [amount] (minor units of its currency) in minor units of the Pot's base currency: converted once,
     *  rounded a half away from zero. */
    fun convertToBase(amount: Big, minorUnits: Int, rate: String, baseMinorUnits: Int): Big {
        val shift = baseMinorUnits - minorUnits
        val scale = if (shift >= 0) Fraction.of(Big.pow10(shift)) else Fraction.of(Big.ONE, Big.pow10(-shift))
        return roundHalfAwayFromZero(Fraction.of(amount) * parseRate(rate) * scale)
    }

    /**
     * The largest-remainder allocation of the integer [total] by [weights], one per person in the order given (the
     * caller's join order, which also breaks a tie of remainders): each `floor(total * w / sum(w))`, the units left over
     * one each to the largest cut-off remainders. Weights are non-negative and not all zero; [total] is non-negative.
     */
    fun allocate(total: Big, weights: List<Fraction>): List<Big> {
        require(total.signum >= 0) { "allocate_negative_total" }
        require(weights.none { it.num.signum < 0 }) { "allocate_negative_weight" }
        var common = Big.ONE
        for (w in weights) common = common * w.den / gcd(common, w.den)
        val ints = weights.map { it.num * common / it.den }
        val whole = ints.fold(Big.ZERO) { a, b -> a + b }
        require(whole.signum != 0) { "allocate_no_weight" }
        val shares = ints.map { total * it / whole }.toMutableList()
        val remainders = ints.map { total * it % whole }
        var left = total - shares.fold(Big.ZERO) { a, b -> a + b }
        // Largest remainder first; a tie keeps the given (join) order.
        val order = remainders.indices.sortedWith { a, b -> if (remainders[a] == remainders[b]) a - b else remainders[b].compareTo(remainders[a]) }
        for (i in order) {
            if (left.signum == 0) break
            shares[i] = shares[i] + Big.ONE
            left -= Big.ONE
        }
        return shares
    }
}
