package me.raddatz.veyq.core.http

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.deser.std.StdScalarDeserializer
import java.util.UUID

/**
 * **An id is a UUID the id columns can hold.** Every id column is MariaDB's native `UUID` (inbox #387), and that type
 * refuses some canonical UUIDs with error 1292 — measured on the pinned 11.4.13 (`SELECT CAST(… AS UUID)` over every
 * version and variant nibble): a version nibble of 8 or more with a variant byte from `0x01` to `0x80`, e.g.
 * `00000000-0000-9000-4000-000000000000`. Java's `UUID` and the contract's `format: uuid` take them, so a client-minted
 * one reached the `INSERT` and answered 500 (found by the API fuzzing's positive writes, ADR 0091 decision 9).
 *
 * The rule here is the plain one around that set: a version nibble of 0 to 7. Every id veyq mints is version 4
 * (`UUID.randomUUID()`, the browser's `crypto.randomUUID()`), a time-ordered client would mint 7, and the tests' fixed
 * ids are 4 or the nil UUID; versions 8 to 15 are custom or unassigned, and the column refuses part of them. Refused
 * like any other id that is not one: `400 invalid_request` in a body ([StrictJsonTypes]), the `404` of a path segment
 * that matches nothing, `400` in a query ([StrictParamConverters]). `IdBoundarySliceTest` holds each entry.
 */
object StorableUuid {
    /** The canonical text form the contract's `format: uuid` means — no shorter groups. */
    private val SHAPE = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-7][0-9a-fA-F]{3}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

    /** The id `text` names, or null when it is no canonical UUID or one the id columns cannot hold. */
    fun parse(text: String): UUID? = if (SHAPE.matches(text)) UUID.fromString(text) else null

    /** A request body's UUID: a JSON string [parse] accepts, anything else a malformed request. */
    object Deserializer : StdScalarDeserializer<UUID>(UUID::class.java) {
        private fun readResolve(): Any = Deserializer

        override fun deserialize(p: JsonParser, ctxt: DeserializationContext): UUID {
            if (p.currentToken != JsonToken.VALUE_STRING) return ctxt.handleUnexpectedToken(UUID::class.java, p) as UUID
            val text = p.text
            return parse(text) ?: ctxt.handleWeirdStringValue(UUID::class.java, text, "not a UUID an id column can hold") as UUID
        }
    }
}
