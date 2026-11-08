package me.raddatz.veyq.mobile.expenses

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.raddatz.veyq.mobile.session.DeviceStore

/**
 * The day's exchange rates (ADR 0108 decision 8, uc.expenses.exchange-rates), a port of
 * `src/web/src/features/expenses/exchangeRates.ts`: where an entry's currency differs from its Pot's base, the device
 * fetches the whole day's table of the ECB's euro reference rates ONCE, from the address the tenant names, and the editor
 * fills the rate from it.
 *  - **The request is the day's table and nothing else**: the `{date}` template with the device's date, a plain `GET`
 *    with no currency, no amount, no header of the app's own, no bearer and no cookie (the account's `outside` client).
 *  - **One table per device and day, for every Pot**: kept in the device's store under the day; the attempt is recorded
 *    before the request goes, so a day whose fetch failed is not asked again (the notice says "at most once a day").
 *  - **The rate from X to the base B is `rate(B) / rate(X)`**, exact in fractions (the euro is 1), rounded a half away
 *    from zero to the format's 12 places; a row may be of an earlier day than asked, and the fill names it.
 *  - **A save never waits for it, and a saved rate never moves**: the editor fills only an empty rate of a currency just
 *    picked, and the write seals whatever the field holds.
 */
object ExchangeRates {
    /** The capability whose `serviceUrl` is the table's `{date}` template. */
    const val CAPABILITY = "expenses.exchange-rates"

    private const val KEY = "veyq:expenses:rates"
    private const val PLACEHOLDER = "{date}"
    private const val PLACES = 12
    private val DAY = Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}$")
    private val ISO = Regex("^[A-Z]{3}$")

    /** One currency's row: its rate against the euro (a decimal, as the format writes one) and the day it is of. */
    data class Row(val rate: String, val date: String)

    /** A rate the editor fills: one unit of `from` in the base, and the day of the rows it was computed from. */
    data class Filled(val rate: String, val date: String)

    /** The table's address for [day], or null when the template holds no `{date}` (a client never guesses a URL). */
    fun tableUrl(template: String, day: String): String? =
        if (PLACEHOLDER !in template || !DAY.matches(day)) null else template.replace(PLACEHOLDER, day)

    /**
     * The provider's answer (Frankfurter's v2 shape: an array of `{date, base, quote, rate}`, base `EUR`) as a table, or
     * null for any other shape. A row against another base, a quote that is no ISO 4217 code ([known] when given), or a
     * rate that is no plain decimal is left out. A JSON number is read as the text the provider wrote, never a double.
     */
    fun parseTable(answer: JsonElement, known: Set<String>? = null): Map<String, Row>? {
        val rows = answer as? JsonArray ?: return null
        val table = LinkedHashMap<String, Row>()
        for (row in rows) {
            val o = row as? JsonObject ?: continue
            fun str(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val quote = str("quote")?.takeIf { ISO.matches(it) && (known == null || it in known) } ?: continue
            val date = str("date")?.takeIf { DAY.matches(it) } ?: continue
            if (str("base") != "EUR") continue
            val text = (o["rate"] as? JsonPrimitive)?.content ?: continue
            val decimal = text.replace(Regex("(\\.[0-9]*?)0+$"), "$1").removeSuffix(".")
            if (!Money.RATE.matches(decimal)) continue
            table[quote] = Row(decimal, date)
        }
        return table
    }

    /** One unit of [from] in [to] from [table], or null when either is missing; the euro is 1 whether or not the table
     *  has its row; the date is the older of the two rows'. */
    fun crossRate(table: Map<String, Row>, from: String, to: String): Filled? {
        fun row(c: String): Pair<Fraction, String?>? {
            val r = table[c]
            if (c == "EUR") return Fraction.of(1) to r?.date
            return r?.let { Money.parseRate(it.rate) to it.date }
        }
        val x = row(from) ?: return null
        val b = row(to) ?: return null
        val date = listOfNotNull(x.second, b.second).minOrNull() ?: return null
        val rate = decimalText(b.first / x.first) ?: return null
        return Filled(rate, date).takeIf { Money.RATE.matches(rate) }
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

    /** One request at a time: callers asking meanwhile wait for it, then read what it left in the store. */
    private val lock = Mutex()

    /**
     * [day]'s table from [template]: the device's copy, else ONE request ([get] answers the body of a
     * `GET`, or null), recorded before it goes. Null when the day's request failed or brought no table. Never throws.
     */
    suspend fun dayTable(template: String, day: String, store: DeviceStore, get: suspend (String) -> String?): Map<String, Row>? = lock.withLock {
        val held = runCatching { store.read(KEY)?.let { Json.parseToJsonElement(it).jsonObject } }.getOrNull()
        if (held != null && held["day"]?.jsonPrimitive?.content == day) {
            return@withLock held["table"]?.let { parseHeld(it) }
        }
        val url = tableUrl(template, day) ?: return@withLock null
        // The attempt, before it goes: at most one a day, whatever it brings.
        store.write(KEY, buildJsonObject { put("day", day); put("failed", true) }.toString())
        val table = runCatching { get(url)?.let { parseTable(Json.parseToJsonElement(it)) } }.getOrNull()
        if (table != null) store.write(KEY, buildJsonObject { put("day", day); put("table", heldJson(table)) }.toString())
        table
    }

    private fun heldJson(table: Map<String, Row>) = buildJsonObject {
        for ((code, row) in table) put(code, buildJsonObject { put("rate", row.rate); put("date", row.date) })
    }

    private fun parseHeld(e: JsonElement): Map<String, Row>? = runCatching {
        e.jsonObject.mapValues { (_, v) -> Row(v.jsonObject["rate"]!!.jsonPrimitive.content, v.jsonObject["date"]!!.jsonPrimitive.content) }
    }.getOrNull()
}
