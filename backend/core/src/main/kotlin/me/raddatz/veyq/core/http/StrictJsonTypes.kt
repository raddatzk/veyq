package me.raddatz.veyq.core.http

import com.fasterxml.jackson.core.JsonParser
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
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.databind.type.LogicalType
import io.quarkus.jackson.ObjectMapperCustomizer
import jakarta.inject.Singleton
import java.time.Instant
import java.time.OffsetDateTime

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
    }

    private object DateTimesAreStrings : BeanDeserializerModifier() {
        private val dateTimes = setOf(OffsetDateTime::class.java, Instant::class.java)

        override fun modifyDeserializer(
            config: DeserializationConfig, beanDesc: BeanDescription, deserializer: JsonDeserializer<*>,
        ): JsonDeserializer<*> = if (beanDesc.beanClass in dateTimes) StringOnly(deserializer) else deserializer
    }

    private class StringOnly(delegate: JsonDeserializer<*>) : DelegatingDeserializer(delegate) {
        override fun newDelegatingInstance(newDelegatee: JsonDeserializer<*>): JsonDeserializer<*> = StringOnly(newDelegatee)

        override fun deserialize(p: JsonParser, ctxt: DeserializationContext): Any? =
            if (p.currentToken().isNumeric) ctxt.handleUnexpectedToken(handledType(), p) else _delegatee.deserialize(p, ctxt)
    }
}
