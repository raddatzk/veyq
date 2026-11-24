package me.raddatz.veyq.mobile.json

import me.raddatz.veyq.mobile.text.Pattern
/**
 * A JSON value as the core's own tree (ADR 0155 decision 4, the JSON seam): what a format reads and writes, on every
 * platform alike. A number is the IEEE double `JSON.parse` yields, so `1e2`, `100.0` and `1` are one value, a duplicate
 * key's last value is the one kept, and an integer past 2^53 is already rounded when a reader sees it — the web's answers,
 * which `contracts/expenses/v1-vectors.json` (`decode`) pins for the apps too. The text crosses through [JsonText]: on
 * JavaScript `JSON.parse` and `JSON.stringify`, on the JVM and natively kotlinx.serialization (`JsonText.threaded.kt`).
 * Code a façade reaches reads and writes this tree only, never a kotlinx type.
 */
sealed interface JsonValue {
    /** An object, its keys in the order the text or the writer gave them. */
    data class Obj(val fields: Map<String, JsonValue>) : JsonValue {
        operator fun get(key: String): JsonValue? = fields[key]
        fun has(key: String): Boolean = key in fields
    }

    data class Arr(val items: List<JsonValue>) : JsonValue

    data class Str(val value: String) : JsonValue

    data class Num(val value: Double) : JsonValue {
        constructor(value: Long) : this(value.toDouble())
        constructor(value: Int) : this(value.toDouble())
    }

    data class Bool(val value: Boolean) : JsonValue

    data object Null : JsonValue
}

/** An object of [fields] in their order; a null value leaves its key out (a writer's optional field). */
fun jsonObject(vararg fields: Pair<String, JsonValue?>): JsonValue.Obj =
    JsonValue.Obj(LinkedHashMap<String, JsonValue>().apply { for ((k, v) in fields) if (v != null) put(k, v) })

fun jsonArray(items: List<JsonValue>): JsonValue.Arr = JsonValue.Arr(items)

fun jsonString(s: String): JsonValue.Str = JsonValue.Str(s)

/** JSON text and back (see [JsonValue]). */
object JsonText {
    /** The value [text] holds; throws [IllegalArgumentException] for anything that is no JSON text. */
    fun parse(text: String): JsonValue = parseJsonText(text)

    /** Compact JSON text of [value], an integral number written without a fraction. */
    fun stringify(value: JsonValue): String = jsonTextOf(value)
}

internal expect fun parseJsonText(text: String): JsonValue

internal expect fun jsonTextOf(value: JsonValue): String

/**
 * A number as JavaScript's `String(n)` writes it — the shortest decimal that reads back as [d], plain between 10^-7 and
 * 10^21, in exponent form outside (`1e-7`, `1.5e+21`) — from the platform's own shortest form, whichever notation it uses.
 * What a reader that takes a number's text sees on every platform (`ExchangeRateTable.parseTable`).
 */
private val NUMBER_TEXT = Pattern("-?([0-9]*)(?:\\.([0-9]*))?(?:[eE]([+-]?[0-9]+))?")

fun jsNumberText(d: Double): String {
    if (d.isNaN()) return "NaN"
    if (d.isInfinite()) return if (d > 0) "Infinity" else "-Infinity"
    if (d == 0.0) return "0"
    val raw = d.toString()
    val negative = raw.startsWith('-')
    val m = NUMBER_TEXT.groups(raw) ?: return raw
    val whole = m[1]
    val frac = m[2]
    val exp = m[3].ifEmpty { "0" }.toInt()
    var digits = whole + frac
    // the decimal point after `whole`: value = 0.digits × 10^n
    var n = whole.length + exp
    val lead = digits.length - digits.trimStart('0').length
    digits = digits.trimStart('0')
    n -= lead
    digits = digits.trimEnd('0')
    val k = digits.length
    val body = when {
        n in k..21 -> digits + "0".repeat(n - k)
        n in 1..21 -> digits.substring(0, n) + "." + digits.substring(n)
        n in -5..0 -> "0." + "0".repeat(-n) + digits
        else -> {
            val e = n - 1
            val sign = if (e < 0) "-" else "+"
            (if (k == 1) digits else digits[0] + "." + digits.substring(1)) + "e" + sign + kotlin.math.abs(e)
        }
    }
    return if (negative) "-$body" else body
}
