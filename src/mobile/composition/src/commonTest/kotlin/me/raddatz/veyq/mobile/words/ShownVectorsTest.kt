package me.raddatz.veyq.mobile.words

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import me.raddatz.veyq.mobile.expenses.Currencies
import me.raddatz.veyq.mobile.qr.readRepoFile
import me.raddatz.veyq.mobile.search.testEnv
import me.raddatz.veyq.mobile.search.writeRepoFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [Shown] against `src/mobile/words/format-vectors.json` (ADR 0154 decision 5), which the web's own formatters write
 * (`src/web/scripts/format-vectors.mts`, pinned by the web's `formatVectors.test.ts`), and money and numbers against
 * `money-vectors.json`, which this test writes from the core (ADR 0155: money moved with Expenses): every case in English and German,
 * on the JVM and on `macosArm64` (and on Linux and Windows through `run.py test`). A failure names the section and the
 * input. A moment is held in UTC ([Shown.wallMoment]); the device's zone is [me.raddatz.veyq.mobile.session.LocalDays]'.
 */
class ShownVectorsTest {
    private val vectors = Json.parseToJsonElement(readRepoFile(FILE).decodeToString()).jsonObject

    /** The money and numbers the core writes itself (ADR 0155: money moved with Expenses), `money-vectors.json`. */
    private val moneyVectors = Json.parseToJsonElement(readRepoFile(MONEY_FILE).decodeToString()).jsonObject
    private val writing = testEnv("VEYQ_WRITE_VECTORS") == "1"

    private fun cases(section: String): List<JsonObject> =
        (if (section == "money" || section == "number") moneyVectors else vectors).getValue(section).jsonArray.map { it.jsonObject }
    private fun JsonObject.s(k: String) = getValue(k).jsonPrimitive.content

    private fun each(section: String, write: (JsonObject, String) -> String) {
        if (writing && (section == "money" || section == "number")) return // the write mode records them (writeTheMoneyVectors)
        val wrong = mutableListOf<String>()
        val all = cases(section)
        for (c in all) for (language in LANGUAGES) {
            val got = write(c, language)
            if (got != c.s(language)) wrong += "$section $language ${c.toString().take(120)}: wrote \"$got\""
        }
        assertTrue(all.isNotEmpty(), "$section has no case")
        assertEquals(emptyList(), wrong, "${wrong.size} of ${all.size * LANGUAGES.size} $section cases differ from the web")
    }

    @Test fun sizes() = each("size") { c, l -> Shown.size(c.getValue("bytes").jsonPrimitive.long, l) }

    @Test fun days() = each("day") { c, l -> Shown.day(c.s("iso"), l) }

    @Test fun moments() = each("moment") { c, l -> Shown.wallMoment(c.getValue("epochMs").jsonPrimitive.long, l) }

    @Test fun memoryDays() = each("memoryDay") { c, l -> Shown.memoryDay(c.s("iso"), l) }

    @Test fun dayRanges() = each("dayRange") { c, l -> Shown.dayRange(c.s("from"), c.s("to"), l) }

    @Test fun money() = each("money") { c, l ->
        Shown.money(c.getValue("amount").jsonPrimitive.long, c.s("currency"), c.getValue("minorUnits").jsonPrimitive.int, l)
    }

    @Test fun numbers() = each("number") { c, l -> Shown.number(c.getValue("amount").jsonPrimitive.long, c.getValue("minorUnits").jsonPrimitive.int, l) }

    @Test fun lists() = each("list") { c, l -> Shown.list(c.getValue("items").jsonArray.map { it.jsonPrimitive.content }, l) }

    @Test fun lengths() {
        val wrong = cases("length").filter { Shown.length(it.getValue("ms").jsonPrimitive.long) != it.s("text") }
        assertEquals(emptyList(), wrong)
    }

    @Test fun dayLabels() = each("dayLabel") { c, l -> Shown.dayLabel(c.s("iso"), c.getValue("withYear").jsonPrimitive.boolean, l) }

    /** The web's distance from the raw metres: the core's rounding (`Gpx.roundedDistance`) and [Shown.distance] together. */
    @Test fun distances() = each("distance") { c, l ->
        val (value, km) = me.raddatz.veyq.mobile.memories.tracks.Gpx.roundedDistance(c.getValue("meters").jsonPrimitive.double)
        Shown.distance(value, km, l)
    }

    /** A time alone is the moment's time part (held by [moments]); its two forms named. */
    @Test fun clocks() {
        assertEquals(listOf("12:00 AM", "2:05 PM", "12:30 PM"), listOf(Shown.clock(0, 0, "en"), Shown.clock(14, 5, "en"), Shown.clock(12, 30, "en")))
        assertEquals(listOf("00:00", "14:05", "09:07"), listOf(Shown.clock(0, 0, "de"), Shown.clock(14, 5, "de"), Shown.clock(9, 7, "de")))
    }

    /** Every currency a Pot offers has its money held: the file's currencies are the core's [Currencies.known]. */
    @Test fun everyCurrencyAPotOffersIsHeld() {
        if (writing) return
        assertEquals(Currencies.known.toSet(), cases("money").map { it.s("currency") }.toSet())
    }

    /**
     * The digits a new Pot or entry is sealed with — the web's and the apps' alike since the web draws [Currencies] — are
     * the recorded ones for every currency offered: each currency's first case (the later ones are amounts sealed with
     * other digits). A changed digit is a deliberate change of the table and rewrites the file.
     */
    @Test fun theDigitsAreRecorded() {
        if (writing) return
        val wrong = cases("money").groupBy { it.s("currency") }.mapValues { (_, c) -> c.first().getValue("minorUnits").jsonPrimitive.int }
            .filter { (code, digits) -> Currencies.minorUnits(code) != digits }.toList()
        assertEquals(emptyList(), wrong, "currencies whose digits differ from the recorded ones")
    }

    /**
     * The write mode of `money-vectors.json` (ADR 0155 § Consequences: the core's output is the one source):
     * `VEYQ_WRITE_VECTORS=1 ./gradlew :core:jvmTest --tests '*ShownVectorsTest*'` records [Shown.money] for every currency
     * the core offers at its own digits and at others, and [Shown.number] at every digit count, as JavaScript's
     * `JSON.stringify(file, null, 1)` writes them — the cases the web's `formatMoney` recorded before money moved.
     */
    @Test fun writeTheMoneyVectors() {
        if (!writing) return
        fun said(write: (String) -> String) = arrayOf("en" to JsonPrimitive(write("en")), "de" to JsonPrimitive(write("de")))
        val money = mutableListOf<JsonObject>()
        for (code in Currencies.known) {
            val digits = Currencies.minorUnits(code)
            for (amount in AMOUNTS) {
                money += JsonObject(mapOf("amount" to JsonPrimitive(amount), "currency" to JsonPrimitive(code), "minorUnits" to JsonPrimitive(digits), *said { Shown.money(amount, code, digits, it) }))
            }
        }
        // an amount sealed with other digits than today's table gives its currency (ADR 0108 decision 9)
        for ((code, digits) in listOf("EUR" to 0, "EUR" to 3, "JPY" to 2, "KWD" to 4, "USD" to 1)) {
            money += JsonObject(mapOf("amount" to JsonPrimitive(123_456_789L), "currency" to JsonPrimitive(code), "minorUnits" to JsonPrimitive(digits), *said { Shown.money(123_456_789L, code, digits, it) }))
        }
        val numbers = (0..4).flatMap { digits -> AMOUNTS.map { amount -> JsonObject(mapOf("amount" to JsonPrimitive(amount), "minorUnits" to JsonPrimitive(digits), *said { Shown.number(amount, digits, it) })) } }
        val file = JsonObject(mapOf(
            "//" to JsonPrimitive(
                "Written by the core's ShownVectorsTest from words/Shown.kt's money and number and expenses/Currencies.kt (ADR 0155: " +
                    "money moved with Expenses); never by hand. The web draws the same functions through @veyq/core-js/expenses.",
            ),
            "money" to JsonArray(money),
            "number" to JsonArray(numbers),
        ))
        writeRepoFile(MONEY_FILE, stringify(file, "") + "\n")
    }

    /** [e] as JavaScript's `JSON.stringify(e, null, 1)` writes it. */
    private fun stringify(e: JsonElement, indent: String): String {
        val inner = "$indent "
        return when (e) {
            is JsonPrimitive -> if (e.isString) quote(e.content) else e.content
            is JsonArray -> if (e.isEmpty()) "[]" else e.joinToString(",\n", "[\n", "\n$indent]") { inner + stringify(it, inner) }
            is JsonObject -> if (e.isEmpty()) "{}" else e.entries.joinToString(",\n", "{\n", "\n$indent}") { (k, v) -> "$inner${quote(k)}: ${stringify(v, inner)}" }
        }
    }

    private fun quote(s: String): String {
        val out = StringBuilder(s.length + 2).append('"')
        for (c in s) {
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c < ' ' -> out.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                else -> out.append(c)
            }
        }
        return out.append('"').toString()
    }

    private companion object {
        const val FILE = "src/mobile/words/format-vectors.json"
        const val MONEY_FILE = "src/mobile/words/money-vectors.json"
        val AMOUNTS = listOf(123_456_789L, -5L, 0L, 1L, 100L)
        val LANGUAGES = listOf("en", "de")
    }
}
