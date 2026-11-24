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
            // views of the JavaScript value, read as a reader asks (no map or list built per object): a reader looks a
            // body's fields up once each, and copying every object first was most of reading a 10,000-entry Pot
            "object" -> if (js("Array").isArray(v).unsafeCast<Boolean>()) JsonValue.Arr(ArrayView(v)) else JsonValue.Obj(ObjectView(v))
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

/** A JavaScript array as the tree's list, each element walked when it is read. */
private class ArrayView(private val a: dynamic) : AbstractList<JsonValue>() {
    override val size: Int get() = a.length.unsafeCast<Int>()
    override fun get(index: Int): JsonValue {
        if (index < 0 || index >= size) throw IndexOutOfBoundsException("$index")
        return JsJson.fromJs(a[index])
    }
}

/** A plain JavaScript object as the tree's map: its own fields, an `undefined` one left out, each walked when it is read. */
private class ObjectView(private val o: dynamic) : AbstractMap<String, JsonValue>() {
    private fun has(key: String): Boolean =
        js("Object").prototype.hasOwnProperty.call(o, key).unsafeCast<Boolean>() && o[key] !== undefined

    override fun containsKey(key: String): Boolean = has(key)
    override fun get(key: String): JsonValue? = if (has(key)) JsJson.fromJs(o[key]) else null

    override val entries: Set<Map.Entry<String, JsonValue>>
        get() = js("Object").keys(o).unsafeCast<Array<String>>().filter { o[it] !== undefined }
            .map { k -> object : Map.Entry<String, JsonValue> {
                override val key: String = k
                override val value: JsonValue = JsJson.fromJs(o[k])
            } }.toSet()
}
