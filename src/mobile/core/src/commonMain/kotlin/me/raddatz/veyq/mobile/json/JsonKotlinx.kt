package me.raddatz.veyq.mobile.json

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// The apps' edge between kotlinx.serialization and the core's own tree (ADR 0155 decision 4): where an app's caller holds a
// `JsonElement` (an import's entries, an export's, the offline queue's intents) it converts here, and the JVM's and
// native `JsonText` read and write through it. No façade reaches this file; the package's slice check proves it.

/** A kotlinx element as the tree: a number as the double `JSON.parse` would make of its text. */
fun JsonElement.toTree(): JsonValue = when (this) {
    JsonNull -> JsonValue.Null
    is JsonPrimitive -> when {
        isString -> JsonValue.Str(content)
        content == "true" -> JsonValue.Bool(true)
        content == "false" -> JsonValue.Bool(false)
        else -> JsonValue.Num(content.toDouble())
    }
    is JsonArray -> JsonValue.Arr(map { it.toTree() })
    is JsonObject -> JsonValue.Obj(LinkedHashMap<String, JsonValue>(size * 2).also { m -> forEach { (k, v) -> m[k] = v.toTree() } })
}

/** The tree as a kotlinx element: an integral number up to 2^53 as an integer, so it is written `1`, never `1.0`. */
fun JsonValue.toKotlinx(): JsonElement = when (this) {
    JsonValue.Null -> JsonNull
    is JsonValue.Str -> JsonPrimitive(value)
    is JsonValue.Bool -> JsonPrimitive(value)
    is JsonValue.Num -> if (value % 1.0 == 0.0 && kotlin.math.abs(value) <= 9007199254740992.0) JsonPrimitive(value.toLong()) else JsonPrimitive(value)
    is JsonValue.Arr -> JsonArray(items.map { it.toKotlinx() })
    is JsonValue.Obj -> JsonObject(fields.mapValues { it.value.toKotlinx() })
}
