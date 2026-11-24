package me.raddatz.veyq.mobile.expenses

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.raddatz.veyq.mobile.json.toTree
import me.raddatz.veyq.mobile.session.DeviceStore

/**
 * The day's exchange rates for the apps (ADR 0108 decision 8, uc.expenses.exchange-rates): where an entry's currency differs from its Pot's base, the device
 * fetches the whole day's table of the ECB's euro reference rates ONCE, from the address the tenant names, and the editor
 * fills the rate from it.
 *  - **The request is the day's table and nothing else**: the `{date}` template with the device's date, a plain `GET`
 *    with no currency, no amount, no header of the app's own, no bearer and no cookie (the account's `outside` client).
 *  - **One table per device and day, for every Pot**: kept in the device's store under the day; the attempt is recorded
 *    before the request goes, so a day whose fetch failed is not asked again (the notice says "at most once a day").
 *  - **The table and the cross rate** are [ExchangeRateTable]'s, the web's too.
 *  - **A save never waits for it, and a saved rate never moves**: the editor fills only an empty rate of a currency just
 *    picked, and the write seals whatever the field holds.
 */
object ExchangeRates {
    /** The capability whose `serviceUrl` is the table's `{date}` template. */
    const val CAPABILITY = "expenses.exchange-rates"

    private const val KEY = "veyq:expenses:rates"
    private const val PLACEHOLDER = "{date}"
    private val DAY = Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}$")

    /** One currency's row: its rate against the euro (a decimal, as the format writes one) and the day it is of. */
    data class Row(val rate: String, val date: String)

    /** A rate the editor fills: one unit of `from` in the base, and the day of the rows it was computed from. */
    data class Filled(val rate: String, val date: String)

    /** Whether an entry's rate is asked of the day's table: rates offered here, another currency than the base, no rate typed. */
    fun wanted(ratesOffered: Boolean, base: String?, currency: String, rate: String): Boolean =
        ratesOffered && currency != base && rate.isBlank()

    /**
     * What an answer does to the form whose rate is now [rate] (it may have been typed meanwhile): a [Fill] of the table's
     * rate in the person's [separator] with the day it is from, nothing over a typed rate, or that the table had none.
     */
    class Fill(val rate: String?, val filledOn: String?, val missing: Boolean)

    fun fill(answer: Filled?, rate: String, separator: Char): Fill = when {
        answer == null -> Fill(null, null, missing = true)
        rate.isBlank() -> Fill(answer.rate.replace('.', separator), answer.date, missing = false)
        else -> Fill(null, null, missing = false)
    }

    /** The table's address for [day], or null when the template holds no `{date}` (a client never guesses a URL). */
    fun tableUrl(template: String, day: String): String? =
        if (PLACEHOLDER !in template || !DAY.matches(day)) null else template.replace(PLACEHOLDER, day)

    /** The provider's answer as a table ([ExchangeRateTable.parseTable]). */
    fun parseTable(answer: JsonElement, known: Set<String>? = null): Map<String, Row>? = ExchangeRateTable.parseTable(answer.toTree(), known)

    /** One unit of [from] in [to] ([ExchangeRateTable.crossRate]). */
    fun crossRate(table: Map<String, Row>, from: String, to: String): Filled? = ExchangeRateTable.crossRate(table, from, to)

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
