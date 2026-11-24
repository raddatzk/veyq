package me.raddatz.veyq.mobile.json

import kotlinx.serialization.json.Json

/** On the JVM and natively: kotlinx.serialization's parser and writer, converted at the tree (`JsonKotlinx.kt`). */
internal actual fun parseJsonText(text: String): JsonValue = try {
    Json.parseToJsonElement(text)
} catch (e: Exception) {
    throw IllegalArgumentException("json_malformed")
}.toTree()

internal actual fun jsonTextOf(value: JsonValue): String = value.toKotlinx().toString()
