package me.raddatz.veyq.mobile.json

// On the web: `JSON.parse` and `JSON.stringify`, walked into and out of the tree.

internal actual fun parseJsonText(text: String): JsonValue {
    val parsed: dynamic = try {
        JSON.parse<Any?>(text)
    } catch (e: Throwable) {
        throw IllegalArgumentException("json_malformed")
    }
    return JsJson.fromJs(parsed)
}

internal actual fun jsonTextOf(value: JsonValue): String = JSON.stringify(JsJson.toJs(value))

/** A JavaScript value and the tree, both ways: what the façades hand in and out. */
object JsJson {
    /**
     * A JavaScript value as the tree: what `JSON.parse` yields, or a plain object the web hands a façade. A field whose value
     * is `undefined` is left out, as `JSON.stringify` leaves it out; anything JSON has no form of (a function, a `bigint`) is
     * refused.
     */
    fun fromJs(v: dynamic): JsonValue {
        if (v == null) return JsonValue.Null
        return when (val type = jsTypeOf(v)) {
            "string" -> JsonValue.Str(v.unsafeCast<String>())
            "number" -> JsonValue.Num(v.unsafeCast<Double>())
            "boolean" -> JsonValue.Bool(v.unsafeCast<Boolean>())
            "object" -> if (js("Array").isArray(v).unsafeCast<Boolean>()) {
                val a = v.unsafeCast<Array<dynamic>>()
                JsonValue.Arr(List(a.size) { fromJs(a[it]) })
            } else {
                val keys = js("Object").keys(v).unsafeCast<Array<String>>()
                val fields = LinkedHashMap<String, JsonValue>(keys.size * 2)
                for (k in keys) {
                    val f: dynamic = v[k]
                    if (f !== undefined) fields[k] = fromJs(f)
                }
                JsonValue.Obj(fields)
            }
            else -> throw IllegalArgumentException("json_unrepresentable: $type")
        }
    }

    /** The tree as a plain JavaScript value. */
    fun toJs(v: JsonValue): dynamic = when (v) {
        JsonValue.Null -> null
        is JsonValue.Str -> v.value
        is JsonValue.Num -> v.value
        is JsonValue.Bool -> v.value
        is JsonValue.Arr -> {
            val a = js("[]")
            for (item in v.items) a.push(toJs(item))
            a
        }
        is JsonValue.Obj -> {
            val o = js("({})")
            for ((k, f) in v.fields) o[k] = toJs(f)
            o
        }
    }
}
