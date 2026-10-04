package com.veyq.core.validation

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer

/**
 * Trims surrounding whitespace off a string field during JSON deserialization, so validation
 * (e.g. @NotBlank / @Size) runs on the normalized value and the handler stores the clean value.
 *
 * Wired per field from the OpenAPI spec via `x-field-extra-annotation`:
 *   @field:com.fasterxml.jackson.databind.annotation.JsonDeserialize(
 *       using = com.veyq.core.validation.TrimmingStringDeserializer::class)
 *
 * Only for server-visible metadata (display names etc.) — never for content, which is ciphertext the
 * server must not touch.
 */
class TrimmingStringDeserializer : JsonDeserializer<String>() {
    override fun deserialize(parser: JsonParser, context: DeserializationContext): String =
        parser.valueAsString?.trim() ?: ""
}
