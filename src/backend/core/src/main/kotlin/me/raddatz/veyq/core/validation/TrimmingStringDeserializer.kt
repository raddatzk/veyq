package me.raddatz.veyq.core.validation

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer

/**
 * Trims surrounding whitespace off a string field during JSON deserialization, so validation
 * (e.g. @NotBlank / @Size) runs on the normalized value and the handler stores the clean value.
 *
 * Wired per field from the OpenAPI spec via `x-field-extra-annotation`:
 *   @field:com.fasterxml.jackson.databind.annotation.JsonDeserialize(
 *       using = me.raddatz.veyq.core.validation.TrimmingStringDeserializer::class)
 *
 * Only for server-visible metadata (display names etc.) — never for content, which is ciphertext the
 * server must not touch.
 */
open class TrimmingStringDeserializer : JsonDeserializer<String>() {
    /** A JSON string only: anything else is a value of the wrong type, refused as one (`MismatchedInputException`,
     *  `400 invalid_request`). It used to read any scalar as its text and an object or array as `""` — so `false` became
     *  the user name "false" and `{}` an empty one (the API fuzzing's first run, ADR 0091 decision 9). */
    override fun deserialize(parser: JsonParser, context: DeserializationContext): String =
        if (parser.hasToken(JsonToken.VALUE_STRING)) parser.text.trim()
        else context.handleUnexpectedToken(String::class.java, parser) as String
}
