package me.raddatz.veyq.core.http

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.databind.BeanDescription
import com.fasterxml.jackson.databind.DeserializationConfig
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.MapperFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.cfg.CoercionAction
import com.fasterxml.jackson.databind.cfg.CoercionInputShape
import com.fasterxml.jackson.databind.deser.BeanDeserializerModifier
import com.fasterxml.jackson.databind.deser.std.DelegatingDeserializer
import com.fasterxml.jackson.databind.deser.std.StdScalarDeserializer
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.databind.type.LogicalType
import io.quarkus.jackson.ObjectMapperCustomizer
import jakarta.inject.Singleton
import java.time.Instant
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

/**
 * **A JSON value of the wrong type is a malformed request** — refused while Jackson binds it, as a
 * `MismatchedInputException`, which [MismatchedInputExceptionMapper] answers with the contract's `400 invalid_request`.
 *
 * Jackson's default coerces a scalar into the declared type: a number into a string field (`12345678` became the
 * capability tag `"12345678"`), `0` into a date-time (1970-01-01, then a domain refusal), an integer into a boolean or an
 * enum, a string into a number, `""` into an absent id. Every one is a request the contract forbids, answered past the boundary — the API
 * fuzzing's first run found about fifty (cr-api-fuzzing, owner decision Q5). The generated clients never send one, so
 * refusing them changes nothing a client does. `MalformedRequestSliceTest` holds each mechanism.
 *
 * Like [NullElementsRefused] it is the mapper's default, so a JSON read that is no request is held to it too — the same
 * rule: nothing veyq writes and reads back relies on a coercion.
 *
 * **An integer is a value, not a spelling** ([WholeNumber]): JSON Schema's `integer` admits `2.0`, so every contract
 * that says `type: integer` does, and the API fuzzing's positive writes sent one to every integer field and got `400`
 * (ADR 0091 decision 9). A whole number binds whatever its spelling; a fraction, or a number past the field's type, is
 * refused while binding — neither truncated (Jackson's `ACCEPT_FLOAT_AS_INT`) nor wrapped around.
 */
@Singleton
class StrictJsonTypes : ObjectMapperCustomizer {
    @Suppress("DEPRECATION") // `configure(MapperFeature, …)` on a built mapper: the customizer receives it built
    override fun customize(mapper: ObjectMapper) {
        mapper.configure(MapperFeature.ALLOW_COERCION_OF_SCALARS, false)
        mapper.enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
        mapper.disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
        val fail = CoercionAction.Fail
        mapper.coercionConfigFor(LogicalType.Textual)
            .setCoercion(CoercionInputShape.Integer, fail)
            .setCoercion(CoercionInputShape.Float, fail)
            .setCoercion(CoercionInputShape.Boolean, fail)
        mapper.coercionConfigFor(LogicalType.Boolean)
            .setCoercion(CoercionInputShape.Integer, fail)
            .setCoercion(CoercionInputShape.Float, fail)
            .setCoercion(CoercionInputShape.String, fail)
        mapper.coercionConfigFor(LogicalType.Integer)
            .setCoercion(CoercionInputShape.String, fail)
            .setCoercion(CoercionInputShape.Float, fail)
            .setCoercion(CoercionInputShape.Boolean, fail)
        mapper.coercionConfigFor(LogicalType.Float)
            .setCoercion(CoercionInputShape.String, fail)
            .setCoercion(CoercionInputShape.Boolean, fail)
        mapper.coercionConfigFor(LogicalType.Enum)
            .setCoercion(CoercionInputShape.Integer, fail)
        mapper.coercionConfigFor(LogicalType.DateTime)
            .setCoercion(CoercionInputShape.Integer, fail)
            .setCoercion(CoercionInputShape.Float, fail)
            .setCoercion(CoercionInputShape.EmptyString, fail)
        // `""` for a UUID (or another scalar read from a string) bound as null — an absent id where the contract says
        // `format: uuid`.
        mapper.coercionConfigFor(LogicalType.OtherScalar)
            .setCoercion(CoercionInputShape.EmptyString, fail)
        // The date-times read a number as an epoch timestamp on a path of their own, past the coercion config and
        // their leniency (measured: `0` still bound as 1970 with both set): they take a string only.
        mapper.registerModule(SimpleModule("veyq-date-times-are-strings").setDeserializerModifier(DateTimesAreStrings))
        // An id is a canonical UUID the id columns can hold ([StorableUuid]) — not Jackson's lenient UUID, which also
        // reads a 24-character base64 form and every version the database refuses.
        mapper.registerModule(SimpleModule("veyq-storable-uuids").addDeserializer(UUID::class.java, StorableUuid.Deserializer))
        mapper.registerModule(
            SimpleModule("veyq-whole-numbers")
                .addDeserializer(Int::class.javaObjectType, WholeNumber(Int::class.javaObjectType, Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()) { it.toInt() })
                .addDeserializer(Int::class.javaPrimitiveType, WholeNumber(Int::class.javaPrimitiveType!!, Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()) { it.toInt() })
                .addDeserializer(Long::class.javaObjectType, WholeNumber(Long::class.javaObjectType, Long.MIN_VALUE, Long.MAX_VALUE) { it })
                .addDeserializer(Long::class.javaPrimitiveType, WholeNumber(Long::class.javaPrimitiveType!!, Long.MIN_VALUE, Long.MAX_VALUE) { it }),
        )
    }

    private object DateTimesAreStrings : BeanDeserializerModifier() {
        private val dateTimes = setOf(OffsetDateTime::class.java, Instant::class.java)

        override fun modifyDeserializer(
            config: DeserializationConfig, beanDesc: BeanDescription, deserializer: JsonDeserializer<*>,
        ): JsonDeserializer<*> = if (beanDesc.beanClass in dateTimes) StringOnly(deserializer) else deserializer
    }

    /**
     * An `Int` or `Long` from a JSON number whose value is whole and within the type — `2`, `2.0`, `2e0` alike. Anything
     * else is the `MismatchedInputException` of a malformed request: a fraction, a value past the type, a string or a
     * boolean (the coercions above refuse those for every other type; this deserializer stands in for Jackson's own
     * here, so it refuses them itself). A JSON `null` reads as Jackson's own does: absent for a boxed type, 0 for a
     * primitive one (which `backend/CLAUDE.md`'s `[integer, "null"]` + `@NotNull` idiom keeps out of required fields).
     */
    private class WholeNumber<T : Any>(
        type: Class<T>, private val min: Long, private val max: Long, private val of: (Long) -> T,
    ) : StdScalarDeserializer<T>(type) {
        private val primitive = type.isPrimitive

        override fun deserialize(p: JsonParser, ctxt: DeserializationContext): T {
            val value: BigDecimal = when (p.currentToken()) {
                JsonToken.VALUE_NUMBER_INT -> BigDecimal(p.bigIntegerValue)
                JsonToken.VALUE_NUMBER_FLOAT -> p.decimalValue
                else -> return ctxt.handleUnexpectedToken(handledType(), p) as T
            }
            val whole = value.signum() == 0 || value.stripTrailingZeros().scale() <= 0
            if (!whole || value < BigDecimal.valueOf(min) || value > BigDecimal.valueOf(max)) {
                @Suppress("UNCHECKED_CAST")
                return ctxt.handleWeirdNumberValue(handledType(), p.numberValue, "not a whole number within the field's type") as T
            }
            return of(value.toLong())
        }

        @Suppress("UNCHECKED_CAST")
        override fun getNullValue(ctxt: DeserializationContext): T? = if (primitive) of(0) else null
    }

    /**
     * A date-time from a string only, and only one a time column can hold ([STORABLE]): past it an `INSERT`/`UPDATE`
     * fails with error 1292 — a share's `expiresAt` in 2577 answered 500 while the columns were MariaDB's `TIMESTAMP`
     * (the API fuzzing's positive writes, ADR 0091 decision 9; inbox #509 made them `DATETIME`). Every date-time a request
     * carries is one the server stores or compares with a stored one, so the range is the request's too, and the
     * contract says so on each request date-time.
     */
    private class StringOnly(delegate: JsonDeserializer<*>) : DelegatingDeserializer(delegate) {
        override fun newDelegatingInstance(newDelegatee: JsonDeserializer<*>): JsonDeserializer<*> = StringOnly(newDelegatee)

        override fun deserialize(p: JsonParser, ctxt: DeserializationContext): Any? {
            if (p.currentToken().isNumeric) return ctxt.handleUnexpectedToken(handledType(), p)
            val text = p.text
            val value = _delegatee.deserialize(p, ctxt)
            val instant = when (value) {
                is OffsetDateTime -> value.toInstant()
                is Instant -> value
                else -> return value
            }
            return if (instant in STORABLE) value
            else ctxt.handleWeirdStringValue(handledType(), text, "outside the range a stored time holds")
        }
    }

    private companion object {
        /**
         * What a `DATETIME` column holds as Hibernate writes it. The upper end is `DATETIME`'s own (9999-12-31, to the
         * millisecond the precision keeps). The lower end is not `DATETIME`'s 1000-01-01 but the Gregorian cutover:
         * Hibernate binds an `Instant` on MariaDB through `java.sql.Timestamp`, whose calendar is Julian before
         * 1582-10-15, so `1000-01-01T00:00:00Z` reaches the column as `0999-12-27` — refused, a 500 (measured).
         */
        val STORABLE = Instant.parse("1582-10-15T00:00:00Z")..Instant.parse("9999-12-31T23:59:59.999Z")
    }
}
