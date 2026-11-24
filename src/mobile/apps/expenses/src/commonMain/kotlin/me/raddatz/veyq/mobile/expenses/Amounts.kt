package me.raddatz.veyq.mobile.expenses

import me.raddatz.veyq.mobile.text.Pattern
/**
 * Amounts as a person types them and reads them back to type over (ADR 0108 decision 9): exact, in digits and strings,
 * never through a float — a typed "0.10" is 10 cents, not 0.1 × 100. The web's and the apps' one rule (ADR 0155: the web
 * draws it through `@veyq/core-js/expenses`), pinned by `contracts/expenses/v1-vectors.json` (`amounts`). Its own file,
 * apart from `PotView`, whose serializers a façade must not reach.
 */
object Amounts {
    /**
     * A typed amount in minor units: digits with `.` or `,` and at most [minorUnits]
     * decimals, above zero and at most 2⁵³−1; null for anything else.
     */
    fun parse(text: String, minorUnits: Int): Long? {
        // every white space out, a no-break space too, as the web's `\s` takes it (the vectors' `amounts`)
        val t = text.filterNot { it.isWhitespace() }
        val m = TYPED.groups(t) ?: return null
        val frac = m[2]
        if (frac.length > minorUnits) return null
        val digits = (m[1] + frac.padEnd(minorUnits, '0')).trimStart('0').ifEmpty { "0" }
        if (digits.length > 16) return null
        val v = digits.toLongOrNull() ?: return null
        return v.takeIf { it in 1..RevisionFormat.MAX_SAFE }
    }

    private val TYPED = Pattern("([0-9]+)(?:[.,]([0-9]*))?")

    /** A typed rate as the format seals it (a decimal with a dot, the schema's `rate`), or null. */
    fun parseRate(text: String): String? = text.trim().replace(',', '.').takeIf { Money.RATE.matches(it) }

    /** [amount] minor units as a decimal string to be typed over, with [separator]. */
    fun text(amount: Long, minorUnits: Int, separator: Char = '.'): String {
        val neg = amount < 0
        val digits = (if (neg) -amount else amount).toString().padStart(minorUnits + 1, '0')
        val whole = digits.dropLast(minorUnits)
        val out = if (minorUnits == 0) whole else whole + separator + digits.takeLast(minorUnits)
        return if (neg) "-$out" else out
    }
}
