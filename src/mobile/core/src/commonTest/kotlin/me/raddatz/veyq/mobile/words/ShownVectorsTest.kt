package me.raddatz.veyq.mobile.words

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import me.raddatz.veyq.mobile.expenses.Currencies
import me.raddatz.veyq.mobile.qr.readRepoFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [Shown] against `src/mobile/words/format-vectors.json` (ADR 0154 decision 5), which the web's own formatters write
 * (`src/web/scripts/format-vectors.mts`, pinned by the web's `formatVectors.test.ts`): every case in English and German,
 * on the JVM and on `macosArm64` (and on Linux and Windows through `run.py test`). A failure names the section and the
 * input. A moment is held in UTC ([Shown.wallMoment]); the device's zone is [me.raddatz.veyq.mobile.session.LocalDays]'.
 */
class ShownVectorsTest {
    private val vectors = Json.parseToJsonElement(readRepoFile(FILE).decodeToString()).jsonObject

    private fun cases(section: String): List<JsonObject> = vectors.getValue(section).jsonArray.map { it.jsonObject }
    private fun JsonObject.s(k: String) = getValue(k).jsonPrimitive.content

    private fun each(section: String, write: (JsonObject, String) -> String) {
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

    /** Every currency a Pot offers has its money held: the web's `Intl` list is the core's [Currencies.known]. */
    @Test fun everyCurrencyAPotOffersIsHeld() {
        assertEquals(Currencies.known.toSet(), cases("money").map { it.s("currency") }.toSet())
    }

    private companion object {
        const val FILE = "src/mobile/words/format-vectors.json"
        val LANGUAGES = listOf("en", "de")
    }
}
