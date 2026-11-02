package me.raddatz.veyq.core.http

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.deser.DeserializationProblemHandler
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException
import io.quarkus.jackson.ObjectMapperCustomizer
import jakarta.inject.Singleton

/**
 * **A request body whose schema says `additionalProperties: false` refuses a field it does not name** — a malformed
 * request, `400 invalid_request` ([MismatchedInputExceptionMapper]: an [UnrecognizedPropertyException] is a
 * `MismatchedInputException`). The contract puts it on the generated model as `x-class-extra-annotation`, beside the
 * `additionalProperties: false` it enforces. Everywhere else Jackson keeps Quarkus' default and drops an unknown field.
 *
 * Narrow on purpose: a rotation's stage bodies carry wraps only (ADR 0115 decision 2), and a value field sent into one —
 * a member's name, an item-mark set — must not read as staged when it was dropped; the sender learns it at once.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class UnknownFieldsRefused

/** Installs [UnknownFieldsRefused]: Jackson asks a problem handler about every unknown field before it drops one. */
@Singleton
class UnknownFieldsRefusedCustomizer : ObjectMapperCustomizer {
    override fun customize(mapper: ObjectMapper) {
        mapper.addHandler(Refusal)
    }

    private object Refusal : DeserializationProblemHandler() {
        override fun handleUnknownProperty(
            ctxt: DeserializationContext, p: JsonParser, deserializer: JsonDeserializer<*>, beanOrClass: Any?, propertyName: String,
        ): Boolean {
            // A bean read through its constructor (every generated model) names its class; one read by setters, itself.
            val type = beanOrClass as? Class<*> ?: beanOrClass?.javaClass ?: return false
            if (!type.isAnnotationPresent(UnknownFieldsRefused::class.java)) return false
            throw UnrecognizedPropertyException.from(p, beanOrClass, propertyName, deserializer.knownPropertyNames)
        }
    }
}
