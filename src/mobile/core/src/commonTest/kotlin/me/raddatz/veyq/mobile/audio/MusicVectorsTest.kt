package me.raddatz.veyq.mobile.audio

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import me.raddatz.veyq.mobile.qr.readRepoFile
import me.raddatz.veyq.mobile.search.testEnv
import me.raddatz.veyq.mobile.search.writeRepoFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The music player's order and fades against `contracts/platform/audio/v1-vectors.json` (ADR 0155 decision 7.2, the
 * seventh slice; ADR 0135 decisions 6 and 7): the platform's unbiased draw ([randomIntOf]), a queue's order under scripted
 * randomness ([PlayOrder] — every draw must be asked with the `n` the case lists, so Fisher–Yates' call order is held too)
 * and the fades on `Double`s ([autoFadeMs] …) — on the JVM and on `macosArm64` (and on Linux and Windows through
 * `run.py test`); the web runs the same cases through `@veyq/core-js/audio` (the package's `audio.test.ts`, the web's
 * `music.vectors.test.ts`). A failing case names its section, index and rule.
 *
 * **The write mode is here** (the core's output is the one source): `VEYQ_WRITE_VECTORS=1 ./gradlew :core:jvmTest --tests
 * '*MusicVectorsTest*'` records every case's `expected` from the core, as JavaScript's `JSON.stringify(vectors, null, 2)`
 * writes it (an infinite number as null); a case's `draws` are its input and kept.
 */
class MusicVectorsTest {
    private val vectors = Json.parseToJsonElement(readRepoFile(FILE).decodeToString()).jsonObject
    private val writing = testEnv("VEYQ_WRITE_VECTORS") == "1"

    private fun str(e: JsonElement?) = e!!.jsonPrimitive.content
    private fun obj(vararg fields: Pair<String, JsonElement>) = JsonObject(linkedMapOf(*fields))

    /** A number as JSON holds it: null is Infinity (what `JSON.stringify` makes of it). */
    private fun ms(e: JsonElement?): Double = if (e == null || e is JsonNull) Double.POSITIVE_INFINITY else e.jsonPrimitive.double

    /** A double as `JSON.stringify` writes it: an integral one without a fraction, an infinite one as null. */
    private fun num(d: Double): JsonElement = when {
        d.isInfinite() || d.isNaN() -> JsonNull
        d == kotlin.math.floor(d) && kotlin.math.abs(d) < 1e15 -> JsonPrimitive(d.toLong())
        else -> JsonPrimitive(d)
    }

    private fun num(i: Int): JsonElement = JsonPrimitive(i)

    // --- randomInt -------------------------------------------------------------------------------------------------------

    private fun answerRandom(case: JsonObject): JsonElement {
        val words = case["words"]!!.jsonArray.map { it.jsonPrimitive.long }
        var drawn = 0
        val random = randomIntOf { n ->
            check(n % 4 == 0) { "asked for $n bytes, not whole words" }
            ByteArray(n).also { b ->
                for (i in 0 until n / 4) {
                    check(drawn < words.size) { "the words ran out" }
                    val w = words[drawn++]
                    for (k in 0 until 4) b[i * 4 + k] = (w ushr (8 * k)).toByte()
                }
            }
        }
        val value = random(case["n"]!!.jsonPrimitive.long.toInt())
        return obj("value" to num(value), "drawn" to num(drawn))
    }

    // --- order -----------------------------------------------------------------------------------------------------------

    private fun answerOrder(case: JsonObject): JsonElement {
        val draws = case["draws"]!!.jsonArray.map { d -> d.jsonArray.let { it[0].jsonPrimitive.int to it[1].jsonPrimitive.int } }
        var at = 0
        val random: RandomInt = { n ->
            check(at < draws.size) { "draw $at (n = $n) is one the web did not make" }
            val (asked, value) = draws[at++]
            check(asked == n) { "draw ${at - 1} asked n = $n where the web asked n = $asked" }
            value
        }
        val ids = case["ids"]!!.jsonArray.map { str(it) }
        val order = PlayOrder(ids, random, case["startId"]?.let { str(it) })
        val answers = case["steps"]!!.jsonArray.map { s ->
            val step = s.jsonObject
            when (val op = str(step["op"])) {
                "current" -> order.current().let { if (it == null) JsonNull else JsonPrimitive(it) }
                "next" -> order.next().let { if (it == null) JsonNull else JsonPrimitive(it) }
                "peek" -> order.peek().let { if (it == null) JsonNull else JsonPrimitive(it) }
                "size" -> num(order.size)
                "previous" -> when (val p = order.previous(ms(step["positionMs"]))) {
                    PreviousStep.Restart -> obj("restart" to JsonPrimitive(true))
                    is PreviousStep.Back -> obj("restart" to JsonPrimitive(false), "id" to JsonPrimitive(p.id))
                }
                "drop" -> JsonNull.also { order.drop(str(step["id"])) }
                else -> error("no op $op")
            }
        }
        check(at == draws.size) { "${draws.size - at} of the web's draws were not made" }
        return JsonArray(answers)
    }

    // --- fades -----------------------------------------------------------------------------------------------------------

    private fun answerFades(case: JsonObject): JsonElement {
        val span = case["span"]!!.jsonObject
        val start = ms(span["startMs"])
        val end = ms(span["endMs"])
        return obj(
            "autoFadeMs" to num(autoFadeMs(start, end)),
            "pressFadeMs" to num(pressFadeMs(start, end)),
            "fadeOutAtMs" to num(fadeOutAtMs(start, end)),
            "fadeLengthAt" to JsonArray(case["positionsMs"]!!.jsonArray.map { num(fadeLengthAt(start, end, ms(it))) }),
        )
    }

    // --- every section -------------------------------------------------------------------------------------------------

    private fun answer(section: String, case: JsonObject): JsonElement = when (section) {
        "randomInt" -> answerRandom(case)
        "order" -> answerOrder(case)
        "fades" -> answerFades(case)
        else -> error("no section $section")
    }

    /** Two JSON values alike, a number by its value (a JSON `1` and `1.0` are one number). */
    private fun same(a: JsonElement, b: JsonElement): Boolean = when {
        a is JsonObject && b is JsonObject -> a.keys == b.keys && a.keys.all { same(a[it]!!, b[it]!!) }
        a is JsonArray && b is JsonArray -> a.size == b.size && a.indices.all { same(a[it], b[it]) }
        a is JsonPrimitive && b is JsonPrimitive && !a.isString && !b.isString && a !is JsonNull && b !is JsonNull ->
            a.content == b.content || a.doubleOrNull?.let { it == b.doubleOrNull } == true
        else -> a == b
    }

    private fun section(name: String) {
        if (writing) return
        val cases = vectors[name]!!.jsonArray
        assertTrue(cases.isNotEmpty(), "$name holds no case")
        val failures = ArrayList<String>()
        cases.forEachIndexed { i, c ->
            val case = c.jsonObject
            val rule = case["rule"]?.jsonPrimitive?.content ?: ""
            val got = try {
                answer(name, case)
            } catch (e: IllegalStateException) {
                failures += "$name #$i $rule: ${e.message}"
                return@forEachIndexed
            }
            if (!same(got, case["expected"]!!)) failures += "$name #$i $rule: expected ${case["expected"].toString().take(400)}, got ${got.toString().take(400)}"
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test fun theFileIsTheFormatThisTestReads() = assertEquals("veyq-music-order-v1", str(vectors["format"]))

    @Test fun theFactsAreTheCores() = assertEquals(facts(), vectors["facts"])

    private fun facts() = obj(
        "fadeMs" to JsonPrimitive(FADE_MS), "pressFadeMs" to JsonPrimitive(PRESS_FADE_MS),
        "previousRestartsAfterMs" to JsonPrimitive(PREVIOUS_RESTARTS_AFTER_MS),
    )

    @Test fun randomInt() = section("randomInt")
    @Test fun order() = section("order")
    @Test fun fades() = section("fades")

    /** The write mode (see the class): every section's answers as its cases' `expected`. */
    @Test
    fun writeTheVectors() {
        if (!writing) return
        val recorded = LinkedHashMap<String, JsonElement>()
        for ((key, value) in vectors) {
            recorded[key] = when (key) {
                "facts" -> facts()
                in SECTIONS -> JsonArray(value.jsonArray.map { c ->
                    val case = c.jsonObject
                    val got = answer(key, case)
                    JsonObject(case.mapValues { (k, v) -> if (k == "expected") got else v })
                })
                else -> value
            }
        }
        writeRepoFile(FILE, stringify(JsonObject(recorded), "") + "\n")
    }

    /** [e] as JavaScript's `JSON.stringify(e, null, 2)` writes it, so a rewrite diffs only where an answer changed. */
    private fun stringify(e: JsonElement, indent: String): String {
        val inner = "$indent  "
        return when (e) {
            is JsonNull -> "null"
            is JsonPrimitive -> if (e.isString) quote(e.content) else e.content
            is JsonArray -> if (e.isEmpty()) "[]" else e.joinToString(",\n", "[\n", "\n$indent]") { inner + stringify(it, inner) }
            is JsonObject -> if (e.isEmpty()) "{}" else e.entries.joinToString(",\n", "{\n", "\n$indent}") { (k, v) -> "$inner${quote(k)}: ${stringify(v, inner)}" }
        }
    }

    /** A string as `JSON.stringify` quotes it (the vectors' strings need only the quote and the backslash). */
    private fun quote(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    companion object {
        const val FILE = "contracts/platform/audio/v1-vectors.json"
        private val SECTIONS = setOf("randomInt", "order", "fades")
    }
}
