package me.raddatz.veyq.mobile.expenses

import me.raddatz.veyq.mobile.text.Pattern
import me.raddatz.veyq.mobile.json.JsonValue
import me.raddatz.veyq.mobile.json.jsNumberText

/**
 * The day's table of exchange rates and the rate the editor fills from it (ADR 0108 decision 8, uc.expenses.exchange-rates),
 * pure: the web and the apps read a provider's answer and compute a cross rate alike (`contracts/expenses/v1-vectors.json`
 * `rates`). Fetching the day's table once and keeping it is each platform's (`ExchangeRates.dayTable` for the apps, the
 * web's `exchangeRates.ts`): this object is apart from `ExchangeRates`, whose lock and store a façade must not reach.
 *  - **The rate from X to the base B is `rate(B) / rate(X)`**, exact in fractions (the euro is 1), rounded a half away
 *    from zero to the format's 12 places; a row may be of an earlier day than asked, and the fill names it.
 *  - **A save never waits for it, and a saved rate never moves**: the editor fills only an empty rate of a currency just
 *    picked, and the write seals whatever the field holds.
 */
object ExchangeRateTable {
    private const val PLACES = 12
    private val DAY = Pattern("^[0-9]{4}-[0-9]{2}-[0-9]{2}$")
    private val ISO = Pattern("^[A-Z]{3}$")

    /**
     * The provider's answer (Frankfurter's v2 shape: an array of `{date, base, quote, rate}`, base `EUR`) as a table, or
     * null for any other shape. A row against another base, a quote that is no ISO 4217 code ([known] when given), or a
     * rate that is no plain decimal is left out. A number's text is the one JavaScript writes for it (`String(n)`): its
     * shortest decimal, which is the provider's for a rate of up to 15 significant digits (the ECB's have 5 or 6); one in
     * exponent form is left out, never guessed.
     */
    fun parseTable(answer: JsonValue, known: Set<String>? = null): Map<String, ExchangeRates.Row>? {
        val rows = (answer as? JsonValue.Arr)?.items ?: return null
        val table = LinkedHashMap<String, ExchangeRates.Row>()
        for (row in rows) {
            val o = row as? JsonValue.Obj ?: continue
            fun str(k: String) = (o[k] as? JsonValue.Str)?.value
            val quote = str("quote")?.takeIf { ISO.matches(it) && (known == null || it in known) } ?: continue
            val date = str("date")?.takeIf { DAY.matches(it) } ?: continue
            if (str("base") != "EUR") continue
            val text = when (val r = o["rate"]) {
                is JsonValue.Num -> jsNumberText(r.value)
                is JsonValue.Str -> r.value
                else -> continue
            }
            // a fraction's trailing zeros out, then a bare point (`1.50` → `1.5`, `2.0` → `2`); an exponent form keeps its text
            val dot = text.indexOf('.')
            val trimmed = if (dot >= 0 && text.substring(dot + 1).all { it in '0'..'9' }) text.trimEnd('0') else text
            val decimal = trimmed.removeSuffix(".")
            if (!Money.RATE.matches(decimal)) continue
            table[quote] = ExchangeRates.Row(decimal, date)
        }
        return table
    }

    /** One unit of [from] in [to] from [table], or null when either is missing; the euro is 1 whether or not the table
     *  has its row; the date is the older of the two rows'. */
    fun crossRate(table: Map<String, ExchangeRates.Row>, from: String, to: String): ExchangeRates.Filled? {
        fun row(c: String): Pair<Fraction, String?>? {
            val r = table[c]
            if (c == "EUR") return Fraction.of(1) to r?.date
            return r?.let { Money.parseRate(it.rate) to it.date }
        }
        val x = row(from) ?: return null
        val b = row(to) ?: return null
        val date = listOfNotNull(x.second, b.second).minOrNull() ?: return null
        val rate = decimalText(b.first / x.first) ?: return null
        return ExchangeRates.Filled(rate, date).takeIf { Money.RATE.matches(rate) }
    }

    /** [f] rounded a half away from zero to 12 places, without trailing zeros; null when it rounds to zero. */
    private fun decimalText(f: Fraction): String? {
        val scaled = Money.roundHalfAwayFromZero(f * Fraction.of(Big.pow10(PLACES)))
        if (scaled.signum <= 0) return null
        val digits = scaled.toString().padStart(PLACES + 1, '0')
        val whole = digits.dropLast(PLACES)
        val part = digits.takeLast(PLACES).trimEnd('0')
        return if (part.isEmpty()) whole else "$whole.$part"
    }
}
