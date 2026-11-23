package me.raddatz.veyq.mobile.json

// The JSON seam's read-only form on the web: the values are what `JSON.parse` yields, read where they lie — no copy, so a
// rule's answer can be the web's own objects.

/** [JsonAccess] over JavaScript values (`JSON.parse`'s). */
object JsJsonAccess : JsonAccess {
    override fun isObject(v: Any?): Boolean = v != null && jsTypeOf(v) == "object" && !isArray(v)
    override fun isArray(v: Any?): Boolean = js("Array").isArray(v).unsafeCast<Boolean>()
    override fun isNull(v: Any?): Boolean = v === null
    override fun string(v: Any?): String? = if (jsTypeOf(v) == "string") v.unsafeCast<String>() else null
    override fun number(v: Any?): Double? = if (jsTypeOf(v) == "number") v.unsafeCast<Double>() else null
    override fun has(v: Any?, key: String): Boolean = v.asDynamic()[key] !== undefined
    override fun field(v: Any?, key: String): Any? = v.asDynamic()[key]
    override fun size(v: Any?): Int = v.asDynamic().length.unsafeCast<Int>()
    override fun item(v: Any?, i: Int): Any? = v.asDynamic()[i]
    override fun values(v: Any?): List<Any?> = js("Object").values(v).unsafeCast<Array<Any?>>().asList()
}
