package me.raddatz.veyq.mobile.expenses

import me.raddatz.veyq.mobile.text.Pattern
/**
 * Expenses' arithmetic, the web's too (ADR 0155, through `@veyq/core-js/expenses`; ADR 0108 decision 9): exact fractions,
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
            if (den == Big.ONE) return Fraction(num, den) // already reduced: no gcd for a whole number (the ledger's weights)
            require(den.signum != 0) { "fraction_zero_denominator" }
            val sign = if (den.signum < 0) -Big.ONE else Big.ONE
            val g = gcd(num, den).let { if (it.signum == 0) Big.ONE else it }
            return Fraction(sign * num / g, sign * den / g)
        }

        fun of(num: Long, den: Long = 1) = of(Big.of(num), Big.of(den))

        /** A weight as the vectors write one: `"3"`, `"1/3"`, `"-2/4"`. */
        fun parse(text: String): Fraction {
            val m = Pattern("(-?[0-9]+)(?:/([0-9]+))?").groups(text) ?: throw IllegalArgumentException("fraction_malformed")
            return of(Big.parse(m[1]), m[2].takeIf { it.isNotEmpty() }?.let { Big.parse(it) } ?: Big.ONE)
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
    val RATE = Pattern("^(?:[1-9][0-9]{0,11}(?:\\.[0-9]{1,12})?|0\\.(?=[0-9]*[1-9])[0-9]{1,12})$")

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
    fun convertToBase(amount: Big, minorUnits: Int, rate: String, baseMinorUnits: Int): Big =
        convertToBase(amount, minorUnits, parseRate(rate), baseMinorUnits)

    /** [convertToBase] with the rate already read (a ledger reads each distinct rate once). */
    internal fun convertToBase(amount: Big, minorUnits: Int, rate: Fraction, baseMinorUnits: Int): Big {
        val shift = baseMinorUnits - minorUnits
        if (shift == 0 && rate.num == Big.ONE && rate.den == Big.ONE) return amount // the base itself: exactly the amount
        val scale = if (shift >= 0) Fraction.of(Big.pow10(shift)) else Fraction.of(Big.ONE, Big.pow10(-shift))
        return roundHalfAwayFromZero(Fraction.of(amount) * rate * scale)
    }

    /**
     * The largest-remainder allocation of the integer [total] by [weights], one per person in the order given (the
     * caller's join order, which also breaks a tie of remainders): each `floor(total * w / sum(w))`, the units left over
     * one each to the largest cut-off remainders. Weights are non-negative and not all zero; [total] is non-negative.
     */
    fun allocate(total: Big, weights: List<Fraction>): List<Big> {
        require(total.signum >= 0) { "allocate_negative_total" }
        require(weights.none { it.num.signum < 0 }) { "allocate_negative_weight" }
        if (weights.size == 1) { // one person takes it all: `total * w / w`
            require(weights[0].num.signum != 0) { "allocate_no_weight" }
            return listOf(total)
        }
        var common = Big.ONE
        for (w in weights) if (w.den != Big.ONE) common = common * w.den / gcd(common, w.den)
        // whole weights (every weight the ledger hands in) are their own integers
        val ints = if (common == Big.ONE) weights.map { it.num } else weights.map { it.num * common / it.den }
        var whole = Big.ZERO
        for (n in ints) whole += n
        require(whole.signum != 0) { "allocate_no_weight" }
        val shares = Array(ints.size) { total * ints[it] / whole }
        val remainders = Array(ints.size) { total * ints[it] % whole }
        var left = total
        for (s in shares) left -= s
        // The units left over, one each to the largest remainder not yet served; a tie keeps the given (join) order —
        // the earliest index wins, as a stable sort by remainder would place it. Fewer units are left than people.
        val served = BooleanArray(ints.size)
        while (left.signum > 0) {
            var best = -1
            for (i in remainders.indices) if (!served[i] && (best < 0 || remainders[i] > remainders[best])) best = i
            served[best] = true
            shares[best] = shares[best] + Big.ONE
            left -= Big.ONE
        }
        return shares.asList()
    }
}
