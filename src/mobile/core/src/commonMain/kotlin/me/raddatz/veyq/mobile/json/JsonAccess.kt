package me.raddatz.veyq.mobile.json

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A parsed JSON document read where it lies (ADR 0155 decision 4, the JSON seam's read-only form): a rule that only checks a
 * document — an untrusted manifest of up to 200,000 nodes — asks these questions of the platform's own value instead of a
 * copy. A value is whatever the platform parsed: on JavaScript what `JSON.parse` yields (`JsJsonAccess`, the web's own
 * object, so a rule can hand back the very objects it found), on the JVM and natively a kotlinx `JsonElement`
 * ([KotlinxJsonAccess], the apps' edge). The answers are JavaScript's: a number is a double, a field that holds `undefined`
 * is no field, JSON `null` is a value of its own.
 */
interface JsonAccess {
    /** An object: not an array, not null. */
    fun isObject(v: Any?): Boolean

    fun isArray(v: Any?): Boolean

    /** JSON `null` (never an absent field). */
    fun isNull(v: Any?): Boolean

    /** The text of a JSON string, else null. */
    fun string(v: Any?): String?

    /** A JSON number as the double JavaScript reads, else null. */
    fun number(v: Any?): Double?

    /** Whether object [v] has field [key]. */
    fun has(v: Any?, key: String): Boolean

    /** Field [key] of object [v]; read only where it [has] it. */
    fun field(v: Any?, key: String): Any?

    /** An array's length. */
    fun size(v: Any?): Int

    /** An array's item [i]. */
    fun item(v: Any?, i: Int): Any?

    /** An object's field values in their order. */
    fun values(v: Any?): List<Any?>
}

/** The seam over kotlinx.serialization's tree: what the apps parse. No façade reaches it; the package's slice check proves it. */
object KotlinxJsonAccess : JsonAccess {
    override fun isObject(v: Any?) = v is JsonObject
    override fun isArray(v: Any?) = v is JsonArray
    override fun isNull(v: Any?) = v is JsonNull
    override fun string(v: Any?): String? = (v as? JsonPrimitive)?.takeIf { it.isString }?.content

    override fun number(v: Any?): Double? {
        val p = v as? JsonPrimitive ?: return null
        if (p.isString || p is JsonNull || p.content == "true" || p.content == "false") return null
        return p.content.toDoubleOrNull()
    }

    override fun has(v: Any?, key: String) = (v as JsonObject).containsKey(key)
    override fun field(v: Any?, key: String): Any? = (v as JsonObject)[key]
    override fun size(v: Any?) = (v as JsonArray).size
    override fun item(v: Any?, i: Int): Any? = (v as JsonArray)[i]
    override fun values(v: Any?): List<Any?> = (v as JsonObject).values.toList()

    /** [v] as kotlinx's element, for a caller that holds one. */
    fun element(v: Any?): JsonElement = v as JsonElement
}
